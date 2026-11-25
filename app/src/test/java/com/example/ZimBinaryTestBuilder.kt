package com.example

import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Spec-compliant OpenZIM binary file builder for real end-to-end JVM & Robolectric tests.
 */
class ZimBinaryTestBuilder {

    sealed class EntrySpec {
        abstract val namespace: Char
        abstract val url: String
        abstract val title: String

        data class Article(
            override val namespace: Char,
            override val url: String,
            override val title: String,
            val mimeType: String,
            val data: ByteArray
        ) : EntrySpec()

        data class Redirect(
            override val namespace: Char,
            override val url: String,
            override val title: String,
            val targetFullPath: String
        ) : EntrySpec()
    }

    private val entries = mutableListOf<EntrySpec>()
    private var mainPageFullPath: String? = null

    fun addMetadata(key: String, value: String) {
        entries.add(EntrySpec.Article('M', key, key, "text/plain", value.toByteArray(StandardCharsets.UTF_8)))
    }

    fun addArticle(
        namespace: Char = 'A',
        url: String,
        title: String,
        mimeType: String = "text/html",
        content: String
    ) {
        entries.add(EntrySpec.Article(namespace, url, title, mimeType, content.toByteArray(StandardCharsets.UTF_8)))
    }

    fun addBinary(
        namespace: Char = 'I',
        url: String,
        mimeType: String,
        bytes: ByteArray
    ) {
        entries.add(EntrySpec.Article(namespace, url, url, mimeType, bytes))
    }

    fun addRedirect(
        namespace: Char = 'A',
        url: String,
        title: String,
        targetFullPath: String
    ) {
        entries.add(EntrySpec.Redirect(namespace, url, title, targetFullPath))
    }

    fun setMainPage(fullPath: String) {
        mainPageFullPath = fullPath
    }

    fun build(destFile: File, compressXz: Boolean = false) {
        // Sort entries by fullPath (namespace + '/' + url) per ZIM spec
        val sorted = entries.sortedBy { "${it.namespace}/${it.url}" }
        val pathToIdx = HashMap<String, Int>()
        sorted.forEachIndexed { idx, spec ->
            pathToIdx["${spec.namespace}/${spec.url}"] = idx
        }

        // Collect unique MIME types
        val mimeList = mutableListOf<String>()
        val mimeToIdx = HashMap<String, Int>()
        for (e in sorted) {
            if (e is EntrySpec.Article && !mimeToIdx.containsKey(e.mimeType)) {
                mimeToIdx[e.mimeType] = mimeList.size
                mimeList.add(e.mimeType)
            }
        }

        // Build MIME list block
        val mimeBuf = ByteArrayOutputStream()
        for (m in mimeList) {
            mimeBuf.write(m.toByteArray(StandardCharsets.UTF_8))
            mimeBuf.write(0)
        }
        mimeBuf.write(0) // terminating empty string
        val mimeBytes = mimeBuf.toByteArray()

        // Group articles into clusters (up to 64 blobs per cluster)
        val articleIndices = sorted.indices.filter { sorted[it] is EntrySpec.Article }
        val clustersData = mutableListOf<ByteArray>()
        val entryClusterBlob = HashMap<Int, Pair<Int, Int>>()

        val chunks = articleIndices.chunked(64)
        for ((cIdx, chunk) in chunks.withIndex()) {
            val blobPayloads = chunk.mapIndexed { bIdx, entryIdx ->
                entryClusterBlob[entryIdx] = cIdx to bIdx
                (sorted[entryIdx] as EntrySpec.Article).data
            }
            val rawCluster = ByteArrayOutputStream()
            val ptrCount = blobPayloads.size + 1
            var offset = ptrCount * 4
            val offsetHeader = ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN)
            for (payload in blobPayloads) {
                offsetHeader.putInt(offset)
                offset += payload.size
            }
            offsetHeader.putInt(offset)
            rawCluster.write(offsetHeader.array())
            for (payload in blobPayloads) {
                rawCluster.write(payload)
            }

            val rawClusterBytes = rawCluster.toByteArray()
            val clusterOut = ByteArrayOutputStream()
            if (compressXz) {
                clusterOut.write(4) // compression=4 (LZMA2/XZ), extended=0
                XZOutputStream(clusterOut, LZMA2Options(1)).use { xzos ->
                    xzos.write(rawClusterBytes)
                }
            } else {
                clusterOut.write(1) // compression=1 (uncompressed), extended=0
                clusterOut.write(rawClusterBytes)
            }
            clustersData.add(clusterOut.toByteArray())
        }

        // Build directory entries
        val direntBytesList = ArrayList<ByteArray>(sorted.size)
        for ((idx, spec) in sorted.withIndex()) {
            val out = ByteArrayOutputStream()
            when (spec) {
                is EntrySpec.Article -> {
                    val bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    bb.putShort(mimeToIdx.getValue(spec.mimeType).toShort())
                    bb.put(0) // paramLen
                    bb.put(spec.namespace.code.toByte())
                    bb.putInt(0) // revision
                    val (cNum, bNum) = entryClusterBlob.getValue(idx)
                    bb.putInt(cNum)
                    bb.putInt(bNum)
                    out.write(bb.array())
                }
                is EntrySpec.Redirect -> {
                    val bb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                    bb.putShort(0xFFFF.toShort())
                    bb.put(0) // paramLen
                    bb.put(spec.namespace.code.toByte())
                    bb.putInt(0) // revision
                    val targetIdx = pathToIdx[spec.targetFullPath] ?: -1
                    bb.putInt(targetIdx)
                    out.write(bb.array())
                }
            }
            out.write(spec.url.toByteArray(StandardCharsets.UTF_8))
            out.write(0)
            out.write(spec.title.toByteArray(StandardCharsets.UTF_8))
            out.write(0)
            direntBytesList.add(out.toByteArray())
        }

        val headerSize = 80L
        val mimeListPos = headerSize
        val urlPtrPos = mimeListPos + mimeBytes.size
        val urlPtrTableSize = sorted.size * 8L
        val titlePtrPos = urlPtrPos + urlPtrTableSize
        val titlePtrTableSize = sorted.size * 4L
        val direntsStartPos = titlePtrPos + titlePtrTableSize

        val urlPtrBuffer = ByteBuffer.allocate(urlPtrTableSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        var cursor = direntsStartPos
        for (db in direntBytesList) {
            urlPtrBuffer.putLong(cursor)
            cursor += db.size
        }

        val titlePtrBuffer = ByteBuffer.allocate(titlePtrTableSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        val sortedByTitle = sorted.indices.sortedBy { sorted[it].title.lowercase() }
        for (i in sortedByTitle) {
            titlePtrBuffer.putInt(i)
        }

        val clusterPtrPos = cursor
        val clusterPtrTableSize = clustersData.size * 8L
        val clustersStartPos = clusterPtrPos + clusterPtrTableSize
        val clusterPtrBuffer = ByteBuffer.allocate(clusterPtrTableSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        var cCursor = clustersStartPos
        for (cb in clustersData) {
            clusterPtrBuffer.putLong(cCursor)
            cCursor += cb.size
        }

        val checksumPos = cCursor
        val mainPageIdx = mainPageFullPath?.let { pathToIdx[it] } ?: -1

        val header = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x044D495A) // magic
        header.putShort(6.toShort()) // majorVersion
        header.putShort(0.toShort()) // minorVersion
        header.put(ByteArray(16) { (it + 1).toByte() }) // uuid
        header.putInt(sorted.size)
        header.putInt(clustersData.size)
        header.putLong(urlPtrPos)
        header.putLong(titlePtrPos)
        header.putLong(clusterPtrPos)
        header.putLong(mimeListPos)
        header.putInt(mainPageIdx)
        header.putInt(-1) // layoutPage
        header.putLong(checksumPos)

        destFile.parentFile?.mkdirs()
        destFile.outputStream().use { fos ->
            fos.write(header.array())
            fos.write(mimeBytes)
            fos.write(urlPtrBuffer.array())
            fos.write(titlePtrBuffer.array())
            for (db in direntBytesList) fos.write(db)
            fos.write(clusterPtrBuffer.array())
            for (cb in clustersData) fos.write(cb)
        }
    }
}
