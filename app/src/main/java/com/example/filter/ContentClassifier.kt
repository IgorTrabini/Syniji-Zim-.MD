package com.example.filter

import com.example.model.ContentCategory
import java.nio.charset.StandardCharsets

object ContentClassifier {

    private val imageMimes = setOf(
        "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif",
        "image/svg+xml", "image/bmp", "image/tiff", "image/x-icon", "image/vnd.microsoft.icon",
        "image/avif", "image/heic", "image/heif"
    )

    private val videoMimes = setOf(
        "video/mp4", "video/webm", "video/ogg", "video/avi", "video/mpeg",
        "video/quicktime", "video/x-matroska", "video/3gpp"
    )

    private val audioMimes = setOf(
        "audio/mpeg", "audio/mp3", "audio/ogg", "audio/wav", "audio/webm",
        "audio/aac", "audio/flac", "audio/opus", "audio/mp4", "audio/x-wav"
    )

    private val webAssetMimes = setOf(
        "text/css", "text/javascript", "application/javascript", "application/x-javascript",
        "application/ecmascript", "font/woff", "font/woff2", "font/ttf", "font/otf",
        "application/font-woff", "application/font-woff2", "application/vnd.ms-fontobject",
        "application/x-font-ttf", "application/x-font-otf", "application/wasm"
    )

    private val binaryExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "svg", "ico", "bmp", "tiff", "avif",
        "mp4", "webm", "ogv", "avi", "mkv", "mov", "mp3", "ogg", "oga", "wav",
        "flac", "aac", "opus", "woff", "woff2", "ttf", "otf", "eot", "css", "js",
        "mjs", "wasm", "zip", "gz", "xz", "bz2", "7z", "tar", "exe", "so", "dll", "bin"
    )

    fun classifyByMetadata(
        mimeType: String?,
        path: String,
        isRedirect: Boolean,
        namespace: String = extractNamespace(path)
    ): ContentCategory {
        if (isRedirect) return ContentCategory.REDIRECT

        val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()

        // Never trust extension alone, check MIME first and validate known asset namespaces
        if (normalizedMime in imageMimes || normalizedMime.startsWith("image/")) {
            return ContentCategory.MEDIA_IMAGE
        }
        if (normalizedMime in videoMimes || normalizedMime.startsWith("video/")) {
            return ContentCategory.MEDIA_VIDEO
        }
        if (normalizedMime in audioMimes || normalizedMime.startsWith("audio/")) {
            return ContentCategory.MEDIA_AUDIO
        }
        if (normalizedMime in webAssetMimes) {
            return ContentCategory.WEB_ASSET_JS_CSS_FONT
        }
        if (normalizedMime == "application/pdf") {
            return ContentCategory.PDF
        }
        if (normalizedMime == "application/xhtml+xml") {
            return ContentCategory.XHTML
        }
        if (normalizedMime == "text/html") {
            return ContentCategory.HTML
        }
        if (normalizedMime == "text/xml" || normalizedMime == "application/xml") {
            return ContentCategory.XML_TEXT
        }
        if (normalizedMime == "text/plain" || normalizedMime == "text/markdown" || normalizedMime == "text/csv") {
            return ContentCategory.PLAIN_TEXT
        }
        if (normalizedMime == "application/octet-stream" || normalizedMime.startsWith("font/") || normalizedMime.startsWith("model/")) {
            return ContentCategory.BINARY_OTHER
        }

        // Legacy ZIM image/asset namespaces
        if (namespace == "I" || namespace == "-") {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg", "jpeg", "png", "webp", "gif", "svg", "ico", "bmp", "avif" -> ContentCategory.MEDIA_IMAGE
                "mp4", "webm", "ogv", "mkv" -> ContentCategory.MEDIA_VIDEO
                "mp3", "ogg", "wav", "flac", "opus" -> ContentCategory.MEDIA_AUDIO
                "css", "js", "woff", "woff2", "ttf", "otf", "eot" -> ContentCategory.WEB_ASSET_JS_CSS_FONT
                else -> ContentCategory.BINARY_OTHER
            }
        }

        // Metadata / Xapian index namespaces should not be treated as user articles
        if (namespace == "M" || namespace == "X") {
            return ContentCategory.BINARY_OTHER
        }

        // Unknown/empty MIME fallback: inspect extension cautiously, later confirmed by magic bytes
        if (normalizedMime.isEmpty()) {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "html", "htm" -> ContentCategory.HTML
                "xhtml" -> ContentCategory.XHTML
                "txt", "md", "rst" -> ContentCategory.PLAIN_TEXT
                "xml" -> ContentCategory.XML_TEXT
                "pdf" -> ContentCategory.PDF
                in binaryExtensions -> {
                    when (ext) {
                        "css", "js", "mjs", "woff", "woff2", "ttf", "otf", "eot", "wasm" -> ContentCategory.WEB_ASSET_JS_CSS_FONT
                        "mp4", "webm", "ogv", "avi", "mkv", "mov" -> ContentCategory.MEDIA_VIDEO
                        "mp3", "ogg", "oga", "wav", "flac", "aac", "opus" -> ContentCategory.MEDIA_AUDIO
                        "jpg", "jpeg", "png", "webp", "gif", "svg", "ico", "bmp", "tiff", "avif" -> ContentCategory.MEDIA_IMAGE
                        else -> ContentCategory.BINARY_OTHER
                    }
                }
                else -> ContentCategory.HTML
            }
        }

        return if (normalizedMime.startsWith("text/")) ContentCategory.PLAIN_TEXT else ContentCategory.BINARY_OTHER
    }

    /**
     * Confirms classification using actual magic byte signatures and UTF-8 readability,
     * ensuring mislabeled binary files are NEVER exported as text.
     */
    fun verifyWithSignature(initial: ContentCategory, headerBytes: ByteArray): ContentCategory {
        if (initial == ContentCategory.REDIRECT ||
            initial == ContentCategory.MEDIA_IMAGE ||
            initial == ContentCategory.MEDIA_VIDEO ||
            initial == ContentCategory.MEDIA_AUDIO ||
            initial == ContentCategory.WEB_ASSET_JS_CSS_FONT ||
            initial == ContentCategory.BINARY_OTHER
        ) {
            return initial
        }

        if (headerBytes.isEmpty()) return initial

        // Check binary signatures regardless of declared MIME type
        if (isPng(headerBytes) || isJpeg(headerBytes) || isGif(headerBytes) || isWebp(headerBytes)) {
            return ContentCategory.MEDIA_IMAGE
        }
        if (isPdf(headerBytes)) {
            return ContentCategory.PDF
        }
        if (isZipOrArchive(headerBytes) || isMp3OrOggOrMp4(headerBytes)) {
            return ContentCategory.BINARY_OTHER
        }

        val probeText = String(headerBytes, 0, minOf(headerBytes.size, 512), StandardCharsets.UTF_8).trimStart()
        if (probeText.startsWith("<svg", ignoreCase = true) ||
            (probeText.startsWith("<?xml", ignoreCase = true) && probeText.contains("<svg", ignoreCase = true))
        ) {
            return ContentCategory.MEDIA_IMAGE
        }

        // Check for NUL bytes or excessive non-printable control characters -> binary
        if (looksBinary(headerBytes)) {
            return ContentCategory.BINARY_OTHER
        }

        if (initial == ContentCategory.PLAIN_TEXT &&
            (probeText.startsWith("<!DOCTYPE html", ignoreCase = true) ||
                probeText.startsWith("<html", ignoreCase = true))
        ) {
            return ContentCategory.HTML
        }

        return initial
    }

    fun isTextualCategory(category: ContentCategory): Boolean = when (category) {
        ContentCategory.HTML,
        ContentCategory.XHTML,
        ContentCategory.PLAIN_TEXT,
        ContentCategory.XML_TEXT,
        ContentCategory.PDF -> true
        else -> false
    }

    fun isMediaOrExcludedCategory(category: ContentCategory): Boolean = when (category) {
        ContentCategory.MEDIA_IMAGE,
        ContentCategory.MEDIA_VIDEO,
        ContentCategory.MEDIA_AUDIO,
        ContentCategory.WEB_ASSET_JS_CSS_FONT,
        ContentCategory.BINARY_OTHER -> true
        else -> false
    }

    fun extractNamespace(path: String): String {
        val clean = path.trimStart('/')
        val slash = clean.indexOf('/')
        return if (slash == 1) clean.substring(0, 1) else ""
    }

    private fun isPng(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()

    private fun isJpeg(b: ByteArray): Boolean =
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

    private fun isGif(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()

    private fun isWebp(b: ByteArray): Boolean =
        b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    private fun isPdf(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == '%'.code.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'D'.code.toByte() && b[3] == 'F'.code.toByte()

    private fun isZipOrArchive(b: ByteArray): Boolean =
        (b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 0x03.toByte() && b[3] == 0x04.toByte()) ||
            (b.size >= 2 && b[0] == 0x1F.toByte() && b[1] == 0x8B.toByte())

    private fun isMp3OrOggOrMp4(b: ByteArray): Boolean =
        (b.size >= 3 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) ||
            (b.size >= 4 && b[0] == 'O'.code.toByte() && b[1] == 'g'.code.toByte() && b[2] == 'g'.code.toByte() && b[3] == 'S'.code.toByte()) ||
            (b.size >= 8 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte())

    private fun looksBinary(b: ByteArray): Boolean {
        val sampleLen = minOf(b.size, 1024)
        var nonPrintable = 0
        for (i in 0 until sampleLen) {
            val v = b[i].toInt() and 0xFF
            if (v == 0) return true
            if (v < 0x09 || (v in 0x0E..0x1F)) {
                nonPrintable++
            }
        }
        return nonPrintable > sampleLen * 0.1
    }
}
