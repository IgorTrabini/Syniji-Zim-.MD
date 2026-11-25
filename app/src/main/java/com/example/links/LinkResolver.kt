package com.example.links

import com.example.filter.ContentClassifier
import com.example.markdown.SlugUtils
import com.example.model.ContentCategory
import com.example.model.ConversionConfig
import com.example.model.LinkAuditRecord
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class PathIndexInfo(
    val originalPath: String,
    val category: ContentCategory,
    val markdownRelativePath: String?,
    val isRedirect: Boolean,
    val finalTargetOriginalPath: String?,
    val finalTargetMarkdownPath: String?,
    val singleFileAnchorId: String? = null
)

class LinkResolver(
    private val config: ConversionConfig,
    private val pathCatalog: Map<String, PathIndexInfo>,
    private val anchorRegistry: Map<String, Map<String, String>> = emptyMap(),
    private val singleFileMode: Boolean = false
) {
    private val auditRecords = mutableListOf<LinkAuditRecord>()
    private val relatedEdges = LinkedHashMap<String, MutableSet<String>>()
    private val dynamicAnchors = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>(anchorRegistry)

    fun registerArticleAnchors(originalPath: String, anchors: Map<String, String>) {
        if (anchors.isNotEmpty()) {
            dynamicAnchors[originalPath] = anchors
        }
    }

    fun resolveForArticle(
        sourceOriginalPath: String,
        sourceMarkdownPath: String,
        rawHref: String,
        linkText: String
    ): String {
        val trimmed = rawHref.trim()
        if (trimmed.isEmpty()) return linkText

        // Same-page fragment anchor
        if (trimmed.startsWith("#")) {
            val fragment = trimmed.substring(1)
            val resolvedSlug = resolveAnchor(sourceOriginalPath, fragment)
            recordAudit(
                LinkAuditRecord(
                    sourceArticlePath = sourceOriginalPath,
                    rawHref = rawHref,
                    linkText = linkText,
                    linkType = "ANCHOR",
                    resolvedTarget = "#$resolvedSlug",
                    anchorFragment = resolvedSlug
                )
            )
            return if (config.preserveAnchors) "[$linkText](#$resolvedSlug)" else linkText
        }

        // Non-navigable URI schemes (javascript:, data:) -> strip target safely
        val lower = trimmed.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("data:")) {
            if (config.identifyBrokenLinks) {
                recordAudit(
                    LinkAuditRecord(sourceOriginalPath, rawHref, linkText, "BROKEN", null, note = "Esquema não textual ignorado")
                )
            }
            return linkText
        }

        // External links
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) {
            recordAudit(
                LinkAuditRecord(sourceOriginalPath, rawHref, linkText, "EXTERNAL", trimmed)
            )
            return if (config.preserveExternalLinks) "[$linkText]($trimmed)" else linkText
        }

        // Separate fragment and query string
        val withoutFragment = trimmed.substringBefore('#')
        val rawFragment = trimmed.substringAfter('#', "").takeIf { trimmed.contains('#') && it.isNotEmpty() }
        val withoutQuery = withoutFragment.substringBefore('?')

        val decodedPath = try {
            URLDecoder.decode(withoutQuery, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            withoutQuery
        }

        val normalizedZimTarget = resolveRelativeZimPath(sourceOriginalPath, decodedPath)
        val targetInfo = lookupCatalog(normalizedZimTarget, decodedPath)

        if (targetInfo == null) {
            // Check if path clearly refers to an excluded media extension even if missing from catalog
            val guessedCat = ContentClassifier.classifyByMetadata(null, normalizedZimTarget, false)
            if (ContentClassifier.isMediaOrExcludedCategory(guessedCat)) {
                val label = mediaLabel(guessedCat)
                recordAudit(
                    LinkAuditRecord(sourceOriginalPath, rawHref, linkText, "MEDIA_EXCLUDED", null, note = "Mídia excluída ($label)")
                )
                return if (config.stripMediaLinkTargetsKeepText) "$linkText [$label não exportado]" else linkText
            }

            if (config.identifyBrokenLinks) {
                recordAudit(
                    LinkAuditRecord(sourceOriginalPath, rawHref, linkText, "BROKEN", null, note = "Destino não encontrado no ZIM: $normalizedZimTarget")
                )
            }
            return linkText
        }

        // Target is excluded media or non-textual resource
        val effectiveCategory = if (targetInfo.isRedirect) {
            targetInfo.finalTargetOriginalPath?.let { pathCatalog[it]?.category } ?: targetInfo.category
        } else {
            targetInfo.category
        }

        if (ContentClassifier.isMediaOrExcludedCategory(effectiveCategory)) {
            val label = mediaLabel(effectiveCategory)
            recordAudit(
                LinkAuditRecord(
                    sourceArticlePath = sourceOriginalPath,
                    rawHref = rawHref,
                    linkText = linkText,
                    linkType = "MEDIA_EXCLUDED",
                    resolvedTarget = null,
                    note = "Mídia excluída (${targetInfo.originalPath})"
                )
            )
            return if (config.stripMediaLinkTargetsKeepText) {
                "$linkText [$label não exportado]"
            } else {
                linkText
            }
        }

        val targetMdPath = targetInfo.finalTargetMarkdownPath ?: targetInfo.markdownRelativePath
        if (targetMdPath == null) {
            if (config.identifyBrokenLinks) {
                recordAudit(
                    LinkAuditRecord(sourceOriginalPath, rawHref, linkText, "BROKEN", null, note = "Destino sem saída Markdown")
                )
            }
            return linkText
        }

        // Record cross-article relationship
        val destOrig = targetInfo.finalTargetOriginalPath ?: targetInfo.originalPath
        if (destOrig != sourceOriginalPath) {
            synchronized(this) {
                relatedEdges.getOrPut(sourceOriginalPath) { linkedSetOf() }.add(destOrig)
            }
        }

        if (singleFileMode) {
            val anchor = targetInfo.finalTargetOriginalPath?.let { pathCatalog[it]?.singleFileAnchorId }
                ?: targetInfo.singleFileAnchorId
                ?: SlugUtils.markdownAnchorSlug(destOrig)
            recordAudit(
                LinkAuditRecord(sourceOriginalPath, rawHref, linkText, "INTERNAL", "#$anchor", anchorFragment = anchor)
            )
            return if (config.convertInternalLinks) "[$linkText](#$anchor)" else linkText
        }

        val relativeMd = computeRelativePath(sourceMarkdownPath, targetMdPath)
        val finalAnchor = rawFragment?.let { resolveAnchor(destOrig, it) }
        val finalUrl = if (config.preserveAnchors && finalAnchor != null) {
            "$relativeMd#$finalAnchor"
        } else {
            relativeMd
        }

        recordAudit(
            LinkAuditRecord(
                sourceArticlePath = sourceOriginalPath,
                rawHref = rawHref,
                linkText = linkText,
                linkType = if (finalAnchor != null) "ANCHOR" else "INTERNAL",
                resolvedTarget = finalUrl,
                anchorFragment = finalAnchor
            )
        )

        return if (config.convertInternalLinks) "[$linkText]($finalUrl)" else linkText
    }

    private fun lookupCatalog(normalizedPath: String, rawDecoded: String): PathIndexInfo? {
        val clean = normalizedPath.trimStart('/')
        val cleanDecoded = rawDecoded.trimStart('/')
        val candidates = listOf(
            clean,
            "A/$clean",
            "C/$clean",
            cleanDecoded,
            "A/$cleanDecoded",
            "C/$cleanDecoded",
            clean.replace(' ', '_'),
            "A/${clean.replace(' ', '_')}",
            "C/${clean.replace(' ', '_')}"
        )
        for (c in candidates) {
            pathCatalog[c]?.let { return it }
        }
        return null
    }

    private fun resolveAnchor(targetOriginalPath: String, rawFragment: String): String {
        val decoded = try {
            URLDecoder.decode(rawFragment, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            rawFragment
        }
        return dynamicAnchors[targetOriginalPath]?.get(decoded)
            ?: SlugUtils.markdownAnchorSlug(decoded)
    }

    @Synchronized
    private fun recordAudit(record: LinkAuditRecord) {
        auditRecords.add(record)
    }

    @Synchronized
    fun getAuditSnapshot(): List<LinkAuditRecord> = auditRecords.toList()

    @Synchronized
    fun getRelationshipsSnapshot(): Map<String, Set<String>> =
        relatedEdges.mapValues { it.value.toSet() }

    companion object {
        fun resolveRelativeZimPath(sourcePath: String, targetHref: String): String {
            if (targetHref.startsWith("/")) {
                return targetHref.trimStart('/')
            }
            val sourceDirParts = sourcePath.trimStart('/').split('/').dropLast(1).toMutableList()
            for (seg in targetHref.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (sourceDirParts.isNotEmpty()) sourceDirParts.removeAt(sourceDirParts.lastIndex)
                    else -> sourceDirParts.add(seg)
                }
            }
            return sourceDirParts.joinToString("/")
        }

        fun computeRelativePath(fromMarkdownFile: String, toMarkdownFile: String): String {
            val fromParts = fromMarkdownFile.trim('/').split('/').dropLast(1)
            val toParts = toMarkdownFile.trim('/').split('/')
            var common = 0
            while (common < fromParts.size && common < toParts.size && fromParts[common] == toParts[common]) {
                common++
            }
            val ups = List(fromParts.size - common) { ".." }
            val downs = toParts.drop(common)
            return (ups + downs).joinToString("/").ifEmpty { toParts.lastOrNull().orEmpty() }
        }

        private fun mediaLabel(cat: ContentCategory): String = when (cat) {
            ContentCategory.MEDIA_IMAGE -> "imagem"
            ContentCategory.MEDIA_VIDEO -> "vídeo"
            ContentCategory.MEDIA_AUDIO -> "áudio"
            ContentCategory.WEB_ASSET_JS_CSS_FONT -> "recurso web"
            else -> "mídia"
        }
    }
}
