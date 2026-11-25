package com.example.zim

import android.os.ParcelFileDescriptor
import com.example.model.ZimMetadata
import com.github.luben.zstd.Zstd
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream

/**
 * Pure Kotlin spec-compliant openZIM binary reader.
 * Used automatically when native libzim JNI cannot load (e.g., JVM/Robolectric tests)
 * or as a resilient fallback.
 */
class PureKotlinZimArchive private constructor(
    private val channel: FileChannel,
    private val closeables: List<AutoCloseable>,
    override val fileSizeBytes: Long
) : ZimArchiveSource {

    override val engineName: String = "PureKotlinZimReader (OpenZIM spec fallback)"

    private val majorVersion: Int
    private val minorVersion: Int
    override val uuid: String
    override val articleCount: Int
    private val clusterCount: Int
    private val urlPtrPos: Long
    private val titlePtrPos: Long
    private val clusterPtrPos: Long
    private val mimeListPos: Long
    private val mainPageIndex: Int
    private val layoutPageIndex: Int
    private val checksumPos: Long

    private val mimeTypes: List<String>
    private val urlOffsets: LongArray
    private val clusterOffsets: LongArray
    private val pathToIndex = HashMap<String, Int>()
    private val entriesCache = ArrayList<DirentRecord>()

    private var maxClusterCacheEntries = 2
    private val decompressedClusterCache = object : LinkedHashMap<Int, DecompressedCluster>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, DecompressedCluster>?): Boolean {
            return size > maxClusterCacheEntries
        }
    }

    init {
        val header = readExact(0L, 80)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bb.int
        if (magic != 0x044D495A) {
            throw IllegalArgumentException("Arquivo ZIM inválido: assinatura mágica incorreta ($magic)")
        }
        majorVersion = bb.short.toInt() and 0xFFFF
        minorVersion = bb.short.toInt() and 0xFFFF
        val uuidBytes = ByteArray(16)
        bb.get(uuidBytes)
        uuid = formatUuid(uuidBytes)
        articleCount = bb.int
        clusterCount = bb.int
        urlPtrPos = bb.long
        titlePtrPos = bb.long
        clusterPtrPos = bb.long
        mimeListPos = bb.long
        mainPageIndex = bb.int
        layoutPageIndex = bb.int
        checksumPos = bb.long

        mimeTypes = readMimeTypeList(mimeListPos)
        urlOffsets = readLongTable(urlPtrPos, articleCount)
        clusterOffsets = readLongTable(clusterPtrPos, clusterCount)

        for (i in 0 until articleCount) {
            val rec = readDirentAt(urlOffsets[i], i)
            entriesCache.add(rec)
            pathToIndex[rec.fullPath] = i
            if (!pathToIndex.containsKey(rec.url)) {
                pathToIndex[rec.url] = i
            }
        }
    }

    override val allEntryCount: Int get() = articleCount
    override val entryCount: Int get() = entriesCache.count { it.namespace != 'M' && it.namespace != 'X' }
    override val mediaCount: Int get() = entriesCache.count { rec ->
        !rec.isRedirect && rec.mimeType.let {
            it.startsWith("image/") || it.startsWith("video/") || it.startsWith("audio/")
        }
    }
    override val hasTitleIndex: Boolean get() = titlePtrPos > 0L
    override val hasFulltextIndex: Boolean get() = entriesCache.any { it.namespace == 'X' }
    override val hasChecksum: Boolean get() = checksumPos > 0L && checksumPos < fileSizeBytes
    override val hasNewNamespaceScheme: Boolean get() = majorVersion >= 6 || entriesCache.any { it.namespace == 'C' }

    override fun checkIntegrity(): Boolean {
        return articleCount >= 0 && clusterCount >= 0 && urlPtrPos < fileSizeBytes
    }

    override fun configureCacheLimits(memoryLimitMb: Int) {
        maxClusterCacheEntries = (memoryLimitMb / 16).coerceIn(1, 6)
    }

    override fun readMetadata(): ZimMetadata {
        val raw = linkedMapOf<String, String>()
        for (rec in entriesCache) {
            if (rec.namespace == 'M' && !rec.isRedirect) {
                val key = rec.url
                if (!key.startsWith("Illustration_", ignoreCase = true)) {
                    val text = String(readEntryBytes(rec), StandardCharsets.UTF_8).trim()
                    if (text.isNotEmpty()) raw[key] = text
                }
            }
        }
        val langs = raw["Language"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return ZimMetadata(
            title = raw["Title"],
            description = raw["Description"],
            language = langs.firstOrNull(),
            languages = langs,
            publisher = raw["Publisher"],
            creator = raw["Creator"],
            date = raw["Date"],
            uuid = uuid,
            mainPagePath = getMainPagePath(),
            sourceName = raw["Name"],
            rawMetadata = raw
        )
    }

    override fun getMainPagePath(): String? {
        if (mainPageIndex < 0 || mainPageIndex >= entriesCache.size) return null
        val entry = PureEntryHandle(entriesCache[mainPageIndex])
        return entry.getItem(true)?.path
    }

    @Synchronized
    override fun getEntryByPath(path: String): ZimEntryHandle? {
        val clean = path.trimStart('/')
        val idx = pathToIndex[clean]
            ?: pathToIndex["A/$clean"]
            ?: pathToIndex["C/$clean"]
            ?: return null
        return PureEntryHandle(entriesCache[idx])
    }

    override fun iterateEntriesEfficiently(): Sequence<ZimEntryHandle> = sequence {
        // Yield articles sorted by cluster and blob order for sequential access, then redirects
        val ordered = entriesCache.sortedWith(
            compareBy<DirentRecord>({ it.isRedirect }, { it.clusterNumber }, { it.blobNumber }, { it.index })
        )
        for (rec in ordered) {
            if (rec.namespace == 'M' || rec.namespace == 'X') continue
            yield(PureEntryHandle(rec))
        }
    }

    @Synchronized
    private fun readEntryBytes(rec: DirentRecord): ByteArray {
        if (rec.isRedirect) return ByteArray(0)
        val cluster = getOrDecompressCluster(rec.clusterNumber)
        return cluster.readBlob(rec.blobNumber)
    }

    @Synchronized
    private fun getOrDecompressCluster(clusterIdx: Int): DecompressedCluster {
        decompressedClusterCache[clusterIdx]?.let { return it }
        require(clusterIdx in clusterOffsets.indices) { "Índice de cluster inválido: $clusterIdx" }
        val start = clusterOffsets[clusterIdx]
        val end = if (clusterIdx + 1 < clusterOffsets.size) clusterOffsets[clusterIdx + 1] else {
            if (checksumPos in (start + 1)..fileSizeBytes) checksumPos else fileSizeBytes
        }
        val infoByte = readExact(start, 1)[0].toInt() and 0xFF
        val compressionType = infoByte and 0x0F
        val isExtended = (infoByte and 0x10) != 0
        val payloadLen = (end - start - 1).coerceAtLeast(0L).toInt()
        val compressed = readExact(start + 1, payloadLen)

        val rawBytes = when (compressionType) {
            0, 1 -> compressed
            2 -> InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
            4 -> XZInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
            5 -> decompressZstd(compressed)
            else -> throw UnsupportedOperationException("Compressão ZIM não suportada: $compressionType")
        }

        val parsed = DecompressedCluster.parse(rawBytes, isExtended)
        decompressedClusterCache[clusterIdx] = parsed
        return parsed
    }

    private fun decompressZstd(compressed: ByteArray): ByteArray {
        return try {
            val frameSize = Zstd.decompressedSize(compressed)
            if (frameSize > 0 && frameSize < 128 * 1024 * 1024) {
                Zstd.decompress(compressed, frameSize.toInt())
            } else {
                val out = ByteArrayOutputStream()
                com.github.luben.zstd.ZstdInputStream(ByteArrayInputStream(compressed)).use { input ->
                    input.copyTo(out, 16384)
                }
                out.toByteArray()
            }
        } catch (e: UnsatisfiedLinkError) {
            throw UnsupportedOperationException(
                "Biblioteca nativa Zstandard indisponível neste ambiente para descompressão de cluster ZIM.",
                e
            )
        }
    }

    private fun readMimeTypeList(pos: Long): List<String> {
        val result = mutableListOf<String>()
        if (pos < 80L || pos >= fileSizeBytes) return result
        var cursor = pos
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(1024)
        while (cursor < fileSizeBytes) {
            val toRead = minOf(chunk.size.toLong(), fileSizeBytes - cursor).toInt()
            val bytes = readExact(cursor, toRead)
            for (b in bytes) {
                if (b == 0.toByte()) {
                    if (buf.size() == 0) {
                        return result
                    }
                    result.add(String(buf.toByteArray(), StandardCharsets.UTF_8))
                    buf.reset()
                } else {
                    buf.write(b.toInt())
                }
            }
            cursor += toRead
        }
        return result
    }

    private fun readLongTable(pos: Long, count: Int): LongArray {
        if (count <= 0) return LongArray(0)
        val byteLenLong = count.toLong() * 8L
        require(pos >= 0L && byteLenLong <= (fileSizeBytes - pos) && byteLenLong <= Int.MAX_VALUE) {
            "Tabela ZIM fora dos limites do arquivo (pos=$pos, count=$count, size=$fileSizeBytes)"
        }
        val raw = readExact(pos, byteLenLong.toInt())
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return LongArray(count) { bb.long }
    }

    private fun readDirentAt(pos: Long, index: Int): DirentRecord {
        val head = readExact(pos, 16)
        val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val mimeIdx = bb.short.toInt() and 0xFFFF
        val paramLen = bb.get().toInt() and 0xFF
        val ns = (bb.get().toInt() and 0xFF).toChar()
        val revision = bb.int

        return if (mimeIdx == 0xFFFF) {
            val targetIdx = bb.int
            val stringsBytes = readNullStrings(pos + 12, 2, paramLen)
            val url = stringsBytes[0]
            val title = stringsBytes[1].ifEmpty { url }
            DirentRecord(
                index = index,
                namespace = ns,
                url = url,
                title = title,
                mimeType = "",
                isRedirect = true,
                redirectIndex = targetIdx,
                clusterNumber = 0,
                blobNumber = 0,
                revision = revision
            )
        } else {
            val clusterNum = bb.int
            val blobNum = bb.int
            val stringsBytes = readNullStrings(pos + 16, 2, paramLen)
            val url = stringsBytes[0]
            val title = stringsBytes[1].ifEmpty { url }
            val mime = mimeTypes.getOrElse(mimeIdx) { "application/octet-stream" }
            DirentRecord(
                index = index,
                namespace = ns,
                url = url,
                title = title,
                mimeType = mime,
                isRedirect = false,
                redirectIndex = -1,
                clusterNumber = clusterNum,
                blobNumber = blobNum,
                revision = revision
            )
        }
    }

    private fun readNullStrings(startPos: Long, count: Int, skipParams: Int): List<String> {
        var cursor = startPos + skipParams
        val out = mutableListOf<String>()
        val buf = ByteArrayOutputStream()
        while (out.size < count && cursor < fileSizeBytes) {
            val toRead = minOf(512L, fileSizeBytes - cursor).toInt()
            val bytes = readExact(cursor, toRead)
            for (b in bytes) {
                if (b == 0.toByte()) {
                    out.add(String(buf.toByteArray(), StandardCharsets.UTF_8))
                    buf.reset()
                    if (out.size == count) return out
                } else {
                    buf.write(b.toInt())
                }
            }
            cursor += toRead
        }
        while (out.size < count) out.add("")
        return out
    }

    private fun readExact(pos: Long, len: Int): ByteArray {
        if (len <= 0) return ByteArray(0)
        val out = ByteArray(len)
        val bb = ByteBuffer.wrap(out)
        var offset = pos
        while (bb.hasRemaining()) {
            val read = channel.read(bb, offset)
            if (read < 0) break
            offset += read
        }
        return out
    }

    override fun close() {
        decompressedClusterCache.clear()
        for (c in closeables) {
            try {
                c.close()
            } catch (_: Exception) {
            }
        }
    }

    private inner class PureEntryHandle(private val rec: DirentRecord) : ZimEntryHandle {
        override val title: String get() = rec.title
        override val path: String get() = rec.fullPath
        override val isRedirect: Boolean get() = rec.isRedirect

        override fun getRedirectEntry(): ZimEntryHandle? {
            if (!rec.isRedirect) return null
            val targetIdx = rec.redirectIndex
            if (targetIdx !in entriesCache.indices) return null
            return PureEntryHandle(entriesCache[targetIdx])
        }

        override fun getItem(followRedirects: Boolean): ZimItemHandle? {
            var curr = rec
            if (curr.isRedirect) {
                if (!followRedirects) return null
                val visited = HashSet<Int>()
                while (curr.isRedirect) {
                    if (!visited.add(curr.index)) return null
                    if (curr.redirectIndex !in entriesCache.indices) return null
                    curr = entriesCache[curr.redirectIndex]
                }
            }
            return PureItemHandle(curr)
        }
    }

    private inner class PureItemHandle(private val rec: DirentRecord) : ZimItemHandle {
        override val title: String get() = rec.title
        override val path: String get() = rec.fullPath
        override val mimeType: String get() = rec.mimeType
        override val size: Long
            get() {
                val cluster = getOrDecompressCluster(rec.clusterNumber)
                return cluster.blobSize(rec.blobNumber).toLong()
            }

        override fun readBytes(): ByteArray = readEntryBytes(rec)
    }

    private data class DirentRecord(
        val index: Int,
        val namespace: Char,
        val url: String,
        val title: String,
        val mimeType: String,
        val isRedirect: Boolean,
        val redirectIndex: Int,
        val clusterNumber: Int,
        val blobNumber: Int,
        val revision: Int
    ) {
        val fullPath: String get() = "$namespace/$url"
    }

    private class DecompressedCluster(
        private val raw: ByteArray,
        private val offsets: LongArray
    ) {
        fun blobSize(blobIndex: Int): Int {
            if (blobIndex < 0 || blobIndex + 1 >= offsets.size) return 0
            return (offsets[blobIndex + 1] - offsets[blobIndex]).toInt().coerceAtLeast(0)
        }

        fun readBlob(blobIndex: Int): ByteArray {
            if (blobIndex < 0 || blobIndex + 1 >= offsets.size) return ByteArray(0)
            val start = offsets[blobIndex].toInt()
            val end = offsets[blobIndex + 1].toInt()
            if (start < 0 || end > raw.size || start > end) return ByteArray(0)
            return raw.copyOfRange(start, end)
        }

        companion object {
            fun parse(raw: ByteArray, extended: Boolean): DecompressedCluster {
                val ptrSize = if (extended) 8 else 4
                if (raw.size < ptrSize) return DecompressedCluster(raw, LongArray(0))
                val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                val firstOffset = if (extended) bb.getLong(0) else (bb.getInt(0).toLong() and 0xFFFFFFFFL)
                if (firstOffset < ptrSize || firstOffset > raw.size.toLong() || firstOffset % ptrSize != 0L) {
                    throw IllegalArgumentException("Cabeçalho de cluster ZIM corrompido (firstOffset=$firstOffset, size=${raw.size})")
                }
                val count = (firstOffset / ptrSize).toInt()
                val offsets = LongArray(count)
                for (i in 0 until count) {
                    offsets[i] = if (extended) {
                        bb.getLong(i * 8)
                    } else {
                        bb.getInt(i * 4).toLong() and 0xFFFFFFFFL
                    }
                }
                return DecompressedCluster(raw, offsets)
            }
        }
    }

    companion object {
        fun openFile(file: File): PureKotlinZimArchive {
            val raf = RandomAccessFile(file, "r")
            return PureKotlinZimArchive(raf.channel, listOf(raf), file.length())
        }

        fun openParcelFileDescriptor(pfd: ParcelFileDescriptor): PureKotlinZimArchive {
            val fis = FileInputStream(pfd.fileDescriptor)
            val channel = fis.channel
            return PureKotlinZimArchive(channel, listOf(fis, pfd), channel.size())
        }

        private fun formatUuid(b: ByteArray): String {
            if (b.size != 16) return ""
            val hex = b.joinToString("") { "%02x".format(it) }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
        }
    }
}
