package com.example.engine

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import com.example.filter.ContentClassifier
import com.example.markdown.SlugUtils
import com.example.model.ContentCategory
import com.example.model.ZimInspection
import com.example.zim.ZimArchiveSource
import com.example.zim.ZimOpener
import java.io.File

object ArchiveInspector {

    fun inspectUri(context: Context, uri: Uri, memoryLimitMb: Int = 64): ZimInspection {
        ZimOpener.openFromUri(context, uri, memoryLimitMb).use { opened ->
            return inspectOpened(
                archive = opened.archive,
                sourceUri = uri.toString(),
                fileName = opened.fileName,
                accessiblePath = opened.accessiblePath,
                availableStorageBytes = queryAvailableStorageBytes(context)
            )
        }
    }

    fun inspectFile(file: File, availableStorageBytes: Long = queryAvailableFileBytes(file.parentFile)): ZimInspection {
        ZimOpener.openFromFile(file).use { opened ->
            return inspectOpened(
                archive = opened.archive,
                sourceUri = Uri.fromFile(file).toString(),
                fileName = opened.fileName,
                accessiblePath = opened.accessiblePath,
                availableStorageBytes = availableStorageBytes
            )
        }
    }

    fun inspectOpened(
        archive: ZimArchiveSource,
        sourceUri: String,
        fileName: String,
        accessiblePath: String,
        availableStorageBytes: Long
    ): ZimInspection {
        val metadata = archive.readMetadata()
        var totalEntries = 0
        var articleEntries = 0
        var redirectEntries = 0
        var textualCount = 0
        var nonTextualCount = 0
        var estimatedBytes = 0L

        val namespaces = linkedSetOf<String>()
        val categoryCounts = HashMap<String, Int>()

        for (entry in archive.iterateEntriesEfficiently()) {
            totalEntries++
            val path = entry.path
            val ns = ContentClassifier.extractNamespace(path)
            if (ns.isNotEmpty()) namespaces.add(ns)

            if (entry.isRedirect) {
                redirectEntries++
                estimatedBytes += 700L
                continue
            }

            val item = entry.getItem(followRedirects = false)
            val mime = item?.mimeType
            val size = item?.size ?: 0L
            val cat = ContentClassifier.classifyByMetadata(mime, path, false, ns)

            if (ContentClassifier.isTextualCategory(cat)) {
                textualCount++
                articleEntries++
                // Conservative estimate: Markdown is typically ~55% of raw HTML + front matter overhead
                val factor = if (cat == ContentCategory.HTML || cat == ContentCategory.XHTML) 0.55 else 0.95
                estimatedBytes += (size * factor).toLong().coerceAtLeast(256L) + 320L

                val stripped = SlugUtils.stripNamespacePrefix(path)
                val firstDir = stripped.substringBefore('/', "")
                if (firstDir.isNotEmpty() && firstDir != stripped) {
                    categoryCounts[firstDir] = (categoryCounts[firstDir] ?: 0) + 1
                }
            } else {
                nonTextualCount++
            }
        }

        // Add overhead for indexes, manifest, reports
        estimatedBytes += (textualCount + redirectEntries) * 160L + 32_768L

        val sampleCats = categoryCounts.entries
            .sortedByDescending { it.value }
            .take(15)
            .map { "${it.key} (${it.value})" }

        return ZimInspection(
            sourceUri = sourceUri,
            fileName = fileName,
            accessiblePath = accessiblePath,
            fileSizeBytes = archive.fileSizeBytes,
            metadata = metadata,
            totalEntries = totalEntries.coerceAtLeast(archive.entryCount),
            articleCount = articleEntries.coerceAtLeast(0),
            redirectCount = redirectEntries,
            textualResourceCount = textualCount,
            nonTextualResourceCount = nonTextualCount,
            hasTitleIndex = archive.hasTitleIndex,
            hasFulltextIndex = archive.hasFulltextIndex,
            hasChecksum = archive.hasChecksum,
            structuralIntegrityValid = archive.checkIntegrity(),
            hasNewNamespaceScheme = archive.hasNewNamespaceScheme,
            namespacesFound = namespaces.toList(),
            sampleCategories = sampleCats,
            estimatedMarkdownBytes = estimatedBytes,
            availableStorageBytes = availableStorageBytes,
            engineUsed = archive.engineName
        )
    }

    fun queryAvailableStorageBytes(context: Context): Long {
        return try {
            val dir = context.filesDir ?: Environment.getDataDirectory()
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    private fun queryAvailableFileBytes(dir: File?): Long {
        return try {
            val target = dir ?: File(".")
            val stat = StatFs(target.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            1024L * 1024L * 1024L
        }
    }
}
