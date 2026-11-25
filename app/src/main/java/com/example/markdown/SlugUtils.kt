package com.example.markdown

import java.text.Normalizer
import java.util.Locale

object SlugUtils {

    private val forbiddenFileChars = Regex("[\\\\:*?\"<>|\\u0000-\\u001F]")
    private val multiUnderscore = Regex("_+")

    fun sanitizeFileSegment(raw: String, fallback: String = "documento"): String {
        if (raw.isBlank()) return fallback
        var s = raw.trim()
            .replace("/", "_")
            .replace("..", "_")
        s = forbiddenFileChars.replace(s, "_")
        s = s.replace(Regex("\\s+"), "_")
        s = multiUnderscore.replace(s, "_").trim('_', '.')
        if (s.isEmpty() || s == "." || s == "..") return fallback
        return if (s.length > 100) s.substring(0, 100).trimEnd('_', '.') else s
    }

    /**
     * Converts a ZIM entry path like "A/Historia/Brasil.html" or "C/Linux" into
     * a safe relative path without path traversal, preserving directory hierarchy.
     */
    fun toSafeRelativeMarkdownPath(
        zimPath: String,
        articlesSubdir: String,
        preserveHierarchy: Boolean,
        categoryOverride: String? = null
    ): String {
        val clean = stripNamespacePrefix(zimPath)
            .replace('\\', '/')
            .trim('/')

        val rawSegments = clean.split('/').filter { it.isNotBlank() && it != "." && it != ".." }
        val baseDir = sanitizeFileSegment(articlesSubdir, "articles")

        if (!preserveHierarchy || rawSegments.isEmpty()) {
            val leaf = rawSegments.lastOrNull() ?: "artigo"
            val stem = stripKnownTextExtension(leaf)
            val catPart = categoryOverride?.takeIf { it.isNotBlank() }?.let { sanitizeFileSegment(it) }
            return if (catPart != null) {
                "$baseDir/$catPart/${sanitizeFileSegment(stem)}.md"
            } else {
                "$baseDir/${sanitizeFileSegment(stem)}.md"
            }
        }

        val dirSegments = rawSegments.dropLast(1).map { sanitizeFileSegment(it, "dir") }
        val fileStem = stripKnownTextExtension(rawSegments.last())
        val safeFile = "${sanitizeFileSegment(fileStem, "artigo")}.md"

        val prefixParts = if (categoryOverride != null && categoryOverride.isNotBlank() && dirSegments.isEmpty()) {
            listOf(baseDir, sanitizeFileSegment(categoryOverride))
        } else {
            listOf(baseDir) + dirSegments
        }
        return (prefixParts + safeFile).joinToString("/")
    }

    fun stripNamespacePrefix(zimPath: String): String {
        val trimmed = zimPath.trimStart('/')
        return if (trimmed.length >= 2 && trimmed[1] == '/' &&
            trimmed[0] in charArrayOf('A', 'C', 'I', 'M', 'X', '-')
        ) {
            trimmed.substring(2)
        } else {
            trimmed
        }
    }

    fun stripKnownTextExtension(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        val extensions = listOf(".html", ".htm", ".xhtml", ".txt", ".xml", ".pdf", ".md")
        for (ext in extensions) {
            if (lower.endsWith(ext) && name.length > ext.length) {
                return name.substring(0, name.length - ext.length)
            }
        }
        return name
    }

    fun markdownAnchorSlug(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noDiacritics
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .ifEmpty { "secao" }
    }

    fun normalizeForSearch(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }
}
