package com.example.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.markdown.SlugUtils
import com.example.model.ArchivePackageFormat
import com.example.model.ConversionConfig
import com.example.model.ManifestRecord
import com.example.model.ZimInspection
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ExportManager {

    /**
     * Resolves and confines a relative path strictly inside [baseDir] to prevent path traversal.
     */
    fun resolveConfinedFile(baseDir: File, relativePath: String): File {
        val cleanRel = relativePath.replace('\\', '/').trimStart('/')
        require(cleanRel.endsWith(".md", ignoreCase = true)) {
            "Bloqueado por segurança: apenas arquivos .md são permitidos na saída ($cleanRel)"
        }
        val baseCanonical = baseDir.canonicalFile
        val target = File(baseCanonical, cleanRel).canonicalFile
        val basePrefix = baseCanonical.path + File.separator
        require(target.path.startsWith(basePrefix)) {
            "Tentativa de path traversal bloqueada: $relativePath"
        }
        return target
    }

    /**
     * Atomically writes UTF-8 Markdown content using a temp file + rename in the target directory.
     */
    fun writeMarkdownAtomically(
        baseDir: File,
        relativePath: String,
        content: String,
        bufferSizeKb: Int = 64
    ): File {
        val target = resolveConfinedFile(baseDir, relativePath)
        val parent = target.parentFile ?: baseDir
        if (!parent.exists()) parent.mkdirs()

        val bufBytes = (bufferSizeKb.coerceIn(8, 1024)) * 1024
        val tempFile = File(parent, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                val bos = BufferedOutputStream(fos, bufBytes)
                bos.write(content.toByteArray(StandardCharsets.UTF_8))
                bos.flush()
                fos.fd.sync()
            }
            if (target.exists()) target.delete()
            if (!tempFile.renameTo(target)) {
                tempFile.copyTo(target, overwrite = true)
                tempFile.delete()
            }
            return target
        } catch (e: Throwable) {
            tempFile.delete()
            throw e
        }
    }

    data class ConsolidatedAssemblyResult(
        val files: List<File>,
        val remappedMarkdownPaths: Map<String, String> // originalPath -> relative .md[#anchor]
    )

    /**
     * Builds Mode B (Single consolidated Markdown file) from converted articles on disk
     * without holding all articles simultaneously in RAM.
     */
    fun assembleSingleMarkdownFile(
        libraryDir: File,
        inspection: ZimInspection,
        manifest: List<ManifestRecord>,
        config: ConversionConfig
    ): ConsolidatedAssemblyResult {
        val libTitle = config.customLibraryName.ifBlank {
            inspection.metadata.title ?: inspection.fileName
        }
        val singleFileName = "${SlugUtils.sanitizeFileSegment(libTitle, "BIBLIOTECA_COMPLETA")}.md"
        val outFile = resolveConfinedFile(libraryDir, singleFileName)
        val tempFile = File(libraryDir, ".$singleFileName.tmp")
        val bufBytes = (config.ioBufferSizeKb.coerceIn(8, 1024)) * 1024

        val byCategory = manifest.groupBy { it.category }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val remappedPaths = HashMap<String, String>(manifest.size)
        val slugCounts = HashMap<String, Int>()
        // Reserve top-level summary heading slugs so article deduplication matches Markdown TOC
        slugCounts[SlugUtils.markdownAnchorSlug(libTitle.uppercase())] = 1
        slugCounts[SlugUtils.markdownAnchorSlug("Sumário de Categorias")] = 1

        tempFile.bufferedWriter(StandardCharsets.UTF_8, bufBytes).use { writer ->
            writer.write("# ")
            writer.write(libTitle.uppercase())
            writer.write("\n\n")
            inspection.metadata.description?.takeIf { it.isNotBlank() }?.let {
                writer.write(it)
                writer.write("\n\n")
            }
            writer.write("## Sumário de Categorias\n\n")
            for ((cat, docs) in byCategory) {
                val catSlug = allocateUniqueSlug(cat, slugCounts)
                writer.write("- [${cat}](#$catSlug) (${docs.size} artigos)\n")
            }
            writer.write("\n---\n\n")

            val emittedCatCounts = HashMap<String, Int>()
            for ((cat, docs) in byCategory) {
                val baseCatSlug = SlugUtils.markdownAnchorSlug(cat)
                emittedCatCounts[baseCatSlug] = (emittedCatCounts[baseCatSlug] ?: 0) + 1
                writer.write("## ")
                writer.write(cat)
                writer.write("\n\n")
                for (doc in docs) {
                    val baseAnchor = SlugUtils.markdownAnchorSlug(doc.title)
                    val idx = emittedCatCounts[baseAnchor] ?: 0
                    emittedCatCounts[baseAnchor] = idx + 1
                    val uniqueAnchor = if (idx == 0) baseAnchor else "$baseAnchor-$idx"
                    remappedPaths[doc.originalPath] = "$singleFileName#$uniqueAnchor"

                    val articleFile = resolveConfinedFile(libraryDir, doc.markdownPath)
                    writer.write("### ")
                    writer.write(doc.title)
                    writer.write("\n\n")
                    writer.write("> Caminho original: `${doc.originalPath}`\n\n")
                    if (articleFile.exists()) {
                        val stripped = stripYamlFrontMatter(articleFile.readText(StandardCharsets.UTF_8))
                        writer.write(stripped)
                    }
                    writer.write("\n\n---\n\n")
                }
            }
        }

        if (outFile.exists()) outFile.delete()
        if (!tempFile.renameTo(outFile)) {
            tempFile.copyTo(outFile, overwrite = true)
            tempFile.delete()
        }
        return ConsolidatedAssemblyResult(listOf(outFile), remappedPaths)
    }

    /**
     * Builds Mode C (Markdown split by category) files inside categories/ without loading everything into RAM.
     */
    fun assembleCategorySplitFiles(
        libraryDir: File,
        manifest: List<ManifestRecord>,
        bufferSizeKb: Int = 64
    ): ConsolidatedAssemblyResult {
        val catDir = File(libraryDir, "categories").apply { mkdirs() }
        val byCategory = manifest.groupBy { it.category }
        val used = HashSet<String>()
        val created = mutableListOf<File>()
        val remappedPaths = HashMap<String, String>(manifest.size)
        val bufBytes = (bufferSizeKb.coerceIn(8, 1024)) * 1024

        for ((cat, docs) in byCategory) {
            val stem = SlugUtils.sanitizeFileSegment(cat, "outros")
            var fileName = "$stem.md"
            var suffix = 2
            while (!used.add(fileName.lowercase())) {
                fileName = "${stem}_$suffix.md"
                suffix++
            }
            val relPath = "categories/$fileName"
            val target = resolveConfinedFile(libraryDir, relPath)
            val temp = File(catDir, ".$fileName.tmp")
            val fileSlugCounts = HashMap<String, Int>()
            temp.bufferedWriter(StandardCharsets.UTF_8, bufBytes).use { writer ->
                writer.write("# Categoria: ")
                writer.write(cat)
                writer.write("\n\nTotal de artigos: **${docs.size}**\n\n---\n\n")
                for (doc in docs) {
                    val anchor = allocateUniqueSlug(doc.title, fileSlugCounts)
                    remappedPaths[doc.originalPath] = "$relPath#$anchor"

                    val src = resolveConfinedFile(libraryDir, doc.markdownPath)
                    writer.write("## ")
                    writer.write(doc.title)
                    writer.write("\n\n> Origem: `${doc.originalPath}`\n\n")
                    if (src.exists()) {
                        writer.write(stripYamlFrontMatter(src.readText(StandardCharsets.UTF_8)))
                    }
                    writer.write("\n\n---\n\n")
                }
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            created.add(target)
        }
        return ConsolidatedAssemblyResult(created, remappedPaths)
    }

    private fun allocateUniqueSlug(text: String, counts: MutableMap<String, Int>): String {
        val base = SlugUtils.markdownAnchorSlug(text)
        val c = counts[base] ?: 0
        counts[base] = c + 1
        return if (c == 0) base else "$base-$c"
    }

    /**
     * Exports strictly .md files into a ZIP or ZIP64 archive (never includes binary/non-md files).
     */
    fun packageLibraryAsZip(
        libraryDir: File,
        destZipFile: File,
        format: ArchivePackageFormat,
        bufferSizeKb: Int = 64
    ): File {
        destZipFile.parentFile?.mkdirs()
        val bufBytes = (bufferSizeKb.coerceIn(8, 1024)) * 1024
        FileOutputStream(destZipFile).use { fos ->
            writeZipToStream(libraryDir, BufferedOutputStream(fos, bufBytes), format, bufBytes)
        }
        return destZipFile
    }

    fun packageLibraryToUri(
        context: Context,
        libraryDir: File,
        destUri: Uri,
        format: ArchivePackageFormat,
        bufferSizeKb: Int = 64
    ) {
        val bufBytes = (bufferSizeKb.coerceIn(8, 1024)) * 1024
        val os = context.contentResolver.openOutputStream(destUri)
            ?: throw IllegalStateException("Não foi possível abrir o destino ZIP: $destUri")
        os.use { stream ->
            writeZipToStream(libraryDir, BufferedOutputStream(stream, bufBytes), format, bufBytes)
        }
    }

    private fun writeZipToStream(
        libraryDir: File,
        outputStream: OutputStream,
        format: ArchivePackageFormat,
        bufBytes: Int
    ) {
        val baseCanonical = libraryDir.canonicalFile
        val baseLen = baseCanonical.path.length + 1
        ZipOutputStream(outputStream, StandardCharsets.UTF_8).use { zos ->
            if (format == ArchivePackageFormat.ZIP64) {
                zos.setLevel(6)
            }
            baseCanonical.walkTopDown().forEach { file ->
                if (file.isFile && file.extension.equals("md", ignoreCase = true)) {
                    val relName = file.canonicalPath.substring(baseLen).replace('\\', '/')
                    val entry = ZipEntry(relName).apply {
                        time = file.lastModified()
                    }
                    zos.putNextEntry(entry)
                    file.inputStream().use { input ->
                        input.copyTo(zos, bufBytes)
                    }
                    zos.closeEntry()
                }
            }
            zos.finish()
        }
    }

    /**
     * Copies exported .md library files into a user-selected SAF directory tree.
     */
    fun mirrorLibraryToSafDirectory(
        context: Context,
        libraryDir: File,
        treeUri: Uri,
        bufferSizeKb: Int = 64
    ): Int {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Pasta de destino SAF inacessível")
        val baseCanonical = libraryDir.canonicalFile
        val baseLen = baseCanonical.path.length + 1
        val dirCache = HashMap<String, DocumentFile>()
        dirCache[""] = rootDoc
        val bufBytes = (bufferSizeKb.coerceIn(8, 1024)) * 1024
        var copied = 0

        baseCanonical.walkTopDown().forEach { file ->
            if (file.isFile && file.extension.equals("md", ignoreCase = true)) {
                val rel = file.canonicalPath.substring(baseLen).replace('\\', '/')
                val parts = rel.split('/')
                val parentRel = parts.dropLast(1).joinToString("/")
                val parentDoc = getOrCreateSafDir(dirCache, rootDoc, parentRel)
                val fileName = parts.last()
                parentDoc.findFile(fileName)?.delete()
                val created = parentDoc.createFile("text/markdown", fileName)
                if (created != null) {
                    context.contentResolver.openOutputStream(created.uri)?.use { out ->
                        file.inputStream().use { input -> input.copyTo(out, bufBytes) }
                    }
                    copied++
                }
            }
        }
        return copied
    }

    private fun getOrCreateSafDir(
        cache: MutableMap<String, DocumentFile>,
        root: DocumentFile,
        relDir: String
    ): DocumentFile {
        if (relDir.isEmpty()) return root
        cache[relDir]?.let { return it }
        var current = root
        var acc = ""
        for (seg in relDir.split('/')) {
            acc = if (acc.isEmpty()) seg else "$acc/$seg"
            val existing = cache[acc] ?: current.findFile(seg)?.takeIf { it.isDirectory }
                ?: current.createDirectory(seg)
                ?: throw IllegalStateException("Falha ao criar pasta SAF: $acc")
            cache[acc] = existing
            current = existing
        }
        return current
    }

    private fun stripYamlFrontMatter(md: String): String {
        if (!md.startsWith("---\n")) return md
        val endIdx = md.indexOf("\n---\n", startIndex = 4)
        return if (endIdx >= 0) md.substring(endIdx + 5).trimStart() else md
    }
}
