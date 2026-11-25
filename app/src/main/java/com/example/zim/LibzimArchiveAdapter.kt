package com.example.zim

import android.os.ParcelFileDescriptor
import com.example.model.ZimMetadata
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Entry
import org.kiwix.libzim.EntryNotFoundException
import org.kiwix.libzim.Item
import org.kiwix.libzim.OpenConfig
import java.io.IOException

class LibzimArchiveAdapter private constructor(
    private val archive: Archive,
    private val ownedPfd: ParcelFileDescriptor?
) : ZimArchiveSource {

    private val jniLock = Any()

    override val engineName: String = "libzim (oficial Kiwix C++/JNI)"
    override val fileSizeBytes: Long get() = synchronized(jniLock) { archive.filesize }
    override val allEntryCount: Int get() = synchronized(jniLock) { archive.allEntryCount }
    override val entryCount: Int get() = synchronized(jniLock) { archive.entryCount }
    override val articleCount: Int get() = synchronized(jniLock) { archive.articleCount }
    override val mediaCount: Int get() = synchronized(jniLock) { archive.mediaCount }
    override val uuid: String get() = synchronized(jniLock) { archive.uuid }
    override val hasTitleIndex: Boolean get() = synchronized(jniLock) { archive.hasTitleIndex() }
    override val hasFulltextIndex: Boolean get() = synchronized(jniLock) { archive.hasFulltextIndex() }
    override val hasChecksum: Boolean get() = synchronized(jniLock) { archive.hasChecksum() }
    override val hasNewNamespaceScheme: Boolean get() = synchronized(jniLock) { archive.hasNewNamespaceScheme() }

    override fun checkIntegrity(): Boolean = synchronized(jniLock) {
        return try {
            if (archive.hasChecksum()) archive.check() else true
        } catch (_: Exception) {
            false
        }
    }

    override fun configureCacheLimits(memoryLimitMb: Int) = synchronized(jniLock) {
        val clampedMb = memoryLimitMb.coerceIn(16, 512)
        val clusterBytes = (clampedMb * 1024L * 1024L) / 2L
        val direntCount = (clampedMb * 128L).coerceAtLeast(1024L)
        try {
            archive.clusterCacheMaxSize = clusterBytes
            archive.direntCacheMaxSize = direntCount
        } catch (_: Exception) {
        }
    }

    override fun readMetadata(): ZimMetadata = synchronized(jniLock) {
        val rawMap = linkedMapOf<String, String>()
        val keys = try {
            archive.metadataKeys ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }
        for (key in keys) {
            if (key.startsWith("Illustration_", ignoreCase = true)) continue
            val value = try {
                archive.getMetadata(key)
            } catch (_: Exception) {
                null
            }
            if (!value.isNullOrBlank()) {
                rawMap[key] = value
            }
        }

        val languages = try {
            archive.languages?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        val mainLang = try {
            archive.mainLanguage.takeIf { !it.isNullOrBlank() }
        } catch (_: Exception) {
            null
        } ?: rawMap["Language"] ?: languages.firstOrNull()

        return ZimMetadata(
            title = rawMap["Title"],
            description = rawMap["Description"],
            language = mainLang,
            languages = languages,
            publisher = rawMap["Publisher"],
            creator = rawMap["Creator"],
            date = rawMap["Date"],
            uuid = uuid,
            mainPagePath = getMainPagePath(),
            sourceName = rawMap["Name"],
            rawMetadata = rawMap
        )
    }

    override fun getMainPagePath(): String? = synchronized(jniLock) {
        return try {
            if (!archive.hasMainEntry()) null
            else archive.mainEntry.getItem(true).path
        } catch (_: Exception) {
            null
        }
    }

    override fun getEntryByPath(path: String): ZimEntryHandle? = synchronized(jniLock) {
        val clean = path.trimStart('/')
        val candidates = listOf(clean, "A/$clean", "C/$clean")
        for (candidate in candidates) {
            try {
                val entry = archive.getEntryByPath(candidate)
                return LibzimEntryHandle(entry)
            } catch (_: EntryNotFoundException) {
            } catch (_: Exception) {
            }
        }
        return null
    }

    override fun iterateEntriesEfficiently(): Sequence<ZimEntryHandle> = sequence {
        val iterator = synchronized(jniLock) { archive.iterEfficient() }
        try {
            while (true) {
                val nextEntry = synchronized(jniLock) {
                    if (iterator.hasNext()) iterator.next() else null
                } ?: break
                yield(LibzimEntryHandle(nextEntry))
            }
        } finally {
            synchronized(jniLock) {
                try {
                    iterator.dispose()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun close() {
        synchronized(jniLock) {
            try {
                archive.dispose()
            } catch (_: Exception) {
            }
            try {
                ownedPfd?.close()
            } catch (_: IOException) {
            }
        }
    }

    private inner class LibzimEntryHandle(private val entry: Entry) : ZimEntryHandle {
        override val title: String get() = synchronized(jniLock) { entry.title ?: "" }
        override val path: String get() = synchronized(jniLock) { entry.path ?: "" }
        override val isRedirect: Boolean get() = synchronized(jniLock) { entry.isRedirect }

        override fun getRedirectEntry(): ZimEntryHandle? = synchronized(jniLock) {
            if (!entry.isRedirect) return null
            return try {
                LibzimEntryHandle(entry.redirectEntry)
            } catch (_: Exception) {
                null
            }
        }

        override fun getItem(followRedirects: Boolean): ZimItemHandle? = synchronized(jniLock) {
            return try {
                LibzimItemHandle(entry.getItem(followRedirects))
            } catch (_: Exception) {
                null
            }
        }
    }

    private inner class LibzimItemHandle(private val item: Item) : ZimItemHandle {
        override val title: String get() = synchronized(jniLock) { item.title ?: "" }
        override val path: String get() = synchronized(jniLock) { item.path ?: "" }
        override val mimeType: String get() = synchronized(jniLock) { item.mimetype ?: "" }
        override val size: Long get() = synchronized(jniLock) { item.size }

        override fun readBytes(): ByteArray = synchronized(jniLock) {
            val blob = item.data ?: return ByteArray(0)
            return try {
                blob.data ?: ByteArray(0)
            } finally {
                try {
                    blob.dispose()
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        fun openPath(path: String, memoryLimitMb: Int): LibzimArchiveAdapter {
            val config = OpenConfig(false, 256)
            val archive = Archive(path, config)
            return LibzimArchiveAdapter(archive, null).apply {
                configureCacheLimits(memoryLimitMb)
            }
        }

        fun openParcelFileDescriptor(
            pfd: ParcelFileDescriptor,
            memoryLimitMb: Int
        ): LibzimArchiveAdapter {
            val config = OpenConfig(false, 256)
            val archive = Archive(pfd.fileDescriptor, config)
            return LibzimArchiveAdapter(archive, pfd).apply {
                configureCacheLimits(memoryLimitMb)
            }
        }
    }
}
