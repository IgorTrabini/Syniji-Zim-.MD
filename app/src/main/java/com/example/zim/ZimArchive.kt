package com.example.zim

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.example.model.ZimMetadata
import java.io.Closeable
import java.io.File

interface ZimItemHandle {
    val title: String
    val path: String
    val mimeType: String
    val size: Long
    fun readBytes(): ByteArray
}

interface ZimEntryHandle {
    val title: String
    val path: String
    val isRedirect: Boolean
    fun getRedirectEntry(): ZimEntryHandle?
    fun getItem(followRedirects: Boolean = false): ZimItemHandle?
}

interface ZimArchiveSource : Closeable {
    val engineName: String
    val fileSizeBytes: Long
    val allEntryCount: Int
    val entryCount: Int
    val articleCount: Int
    val mediaCount: Int
    val uuid: String
    val hasTitleIndex: Boolean
    val hasFulltextIndex: Boolean
    val hasChecksum: Boolean
    val hasNewNamespaceScheme: Boolean

    fun checkIntegrity(): Boolean
    fun readMetadata(): ZimMetadata
    fun getMainPagePath(): String?
    fun getEntryByPath(path: String): ZimEntryHandle?
    fun iterateEntriesEfficiently(): Sequence<ZimEntryHandle>
    fun configureCacheLimits(memoryLimitMb: Int)
}

data class OpenedZimHandle(
    val archive: ZimArchiveSource,
    val fileName: String,
    val accessiblePath: String,
    private val tempCacheFile: File? = null
) : Closeable {
    override fun close() {
        try {
            archive.close()
        } finally {
            tempCacheFile?.delete()
        }
    }
}

object ZimOpener {

    fun openFromFile(file: File, memoryLimitMb: Int = 64): OpenedZimHandle {
        val archive = tryOpenLibzimFile(file, memoryLimitMb)
            ?: PureKotlinZimArchive.openFile(file)
        return OpenedZimHandle(
            archive = archive,
            fileName = file.name,
            accessiblePath = file.absolutePath
        )
    }

    fun openFromUri(context: Context, uri: Uri, memoryLimitMb: Int = 64): OpenedZimHandle {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path) { "Caminho de arquivo inválido" })
            return openFromFile(file, memoryLimitMb)
        }

        val displayName = queryDisplayName(context, uri) ?: "arquivo.zim"
        val pfd: ParcelFileDescriptor? = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Throwable) {
            null
        }

        if (pfd != null) {
            try {
                val archive = tryOpenLibzimFd(pfd, memoryLimitMb)
                    ?: PureKotlinZimArchive.openParcelFileDescriptor(pfd)
                return OpenedZimHandle(
                    archive = archive,
                    fileName = displayName,
                    accessiblePath = uri.toString()
                )
            } catch (t: Throwable) {
                try {
                    pfd.close()
                } catch (_: Exception) {
                }
                if (t is IllegalArgumentException || t.javaClass.name.contains("ZimFileFormatException")) {
                    throw t
                }
                // Fall through to InputStream temp cache fallback for non-seekable SAF providers
            }
        }

        val tempDir = File(context.cacheDir, "zim_stream_cache").apply { mkdirs() }
        val tempFile = File(tempDir, "stream_${System.nanoTime()}.zim")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Não foi possível abrir o arquivo ZIM selecionado: $uri")
            input.use { src ->
                tempFile.outputStream().use { dst ->
                    src.copyTo(dst, 64 * 1024)
                }
            }
            val archive = tryOpenLibzimFile(tempFile, memoryLimitMb)
                ?: PureKotlinZimArchive.openFile(tempFile)
            return OpenedZimHandle(
                archive = archive,
                fileName = displayName,
                accessiblePath = uri.toString(),
                tempCacheFile = tempFile
            )
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
    }

    private fun tryOpenLibzimFile(file: File, memoryLimitMb: Int): ZimArchiveSource? {
        return try {
            LibzimArchiveAdapter.openPath(file.absolutePath, memoryLimitMb)
        } catch (_: UnsatisfiedLinkError) {
            null
        } catch (_: NoClassDefFoundError) {
            null
        } catch (e: Throwable) {
            // If it's a genuine format error from libzim, rethrow; if native init failed, fallback
            if (e.javaClass.name.contains("ZimFileFormatException")) throw e
            null
        }
    }

    private fun tryOpenLibzimFd(pfd: ParcelFileDescriptor, memoryLimitMb: Int): ZimArchiveSource? {
        return try {
            LibzimArchiveAdapter.openParcelFileDescriptor(pfd, memoryLimitMb)
        } catch (_: UnsatisfiedLinkError) {
            null
        } catch (_: NoClassDefFoundError) {
            null
        } catch (e: Throwable) {
            if (e.javaClass.name.contains("ZimFileFormatException")) throw e
            null
        }
    }

    fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return File(uri.path ?: return null).name
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }
}
