package com.example.parser

import com.example.markdown.SlugUtils
import com.example.model.ConversionConfig
import com.example.model.ExportMode
import com.example.model.MarkdownDialect
import com.example.model.MediaMarkerMode
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.nio.charset.StandardCharsets

data class DiscoveredMediaRef(
    val mediaType: String, // "Imagem", "Vídeo", "Áudio", "Gráfico"
    val src: String,
    val alt: String?,
    val title: String?,
    val caption: String?,
    val transcript: String?,
    val credit: String?
)

data class HtmlParseResult(
    val extractedTitle: String?,
    val extractedDescription: String?,
    val categories: List<String>,
    val markdownBody: String,
    val headingAnchors: Map<String, String>, // original HTML id -> Markdown anchor slug
    val discoveredMedia: List<DiscoveredMediaRef>,
    val warnings: List<String>,
    val wordCount: Int
)

fun interface HtmlLinkResolver {
    fun resolve(rawHref: String, linkText: String): String
}

class HtmlSemanticParser(
    private val config: ConversionConfig
) {

    fun parseAndConvert(
        bytes: ByteArray,
        isXhtml: Boolean,
        fallbackTitle: String,
        linkResolver: HtmlLinkResolver
    ): HtmlParseResult {
        val rawHtml = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
        val doc: Document = if (isXhtml) {
            Jsoup.parse(rawHtml, "", Parser.xmlParser())
        } else {
            Jsoup.parse(rawHtml)
        }

        val extractedTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.title().trim().takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackTitle

        val extractedDescription = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        val categories = extractCategories(doc)

        // Remove non-textual execution & styling nodes completely
        doc.select("script, style, noscript, link[rel=stylesheet], template").remove()

        val anchorMap = LinkedHashMap<String, String>()
        val slugCounts = HashMap<String, Int>()
        val mediaRefs = mutableListOf<DiscoveredMediaRef>()
        val warnings = mutableListOf<String>()

        val bodyElement: Element = doc.body()

        // Register all element IDs & heading anchors first for accurate intra-article and cross-article anchors
        collectHeadingAnchors(bodyElement, anchorMap, slugCounts)

        val sb = StringBuilder()
        val hasH1 = bodyElement.selectFirst("h1") != null
        if (!hasH1 && config.preserveHeadings) {
            sb.append("# ").append(extractedTitle).append("\n\n")
        }

        val ctx = RenderContext(
            sb = sb,
            linkResolver = linkResolver,
            mediaRefs = mediaRefs,
            warnings = warnings
        )

        renderChildren(bodyElement, ctx)

        var markdown = postProcessMarkdown(ctx.sb.toString())
        if (config.exportMode == ExportMode.OPTIMIZED_MINIMAL) {
            markdown = compactMarkdown(markdown)
        }

        val words = markdown.split(Regex("\\s+")).count { it.isNotBlank() }

        return HtmlParseResult(
            extractedTitle = extractedTitle,
            extractedDescription = extractedDescription,
            categories = categories,
            markdownBody = markdown,
            headingAnchors = anchorMap,
            discoveredMedia = mediaRefs,
            warnings = warnings,
            wordCount = words
        )
    }

    private fun extractCategories(doc: Document): List<String> {
        val cats = linkedSetOf<String>()
        doc.select("meta[name=keywords], meta[name=category], meta[property=article:section]").forEach { el ->
            el.attr("content").split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { cats.add(it) }
        }
        doc.select("#catlinks li a, .mw-normal-catlinks ul li a, .categories a").forEach { el ->
            val txt = el.text().trim()
            if (txt.isNotEmpty() && !txt.equals("Categorias", ignoreCase = true) && !txt.equals("Categories", ignoreCase = true)) {
                cats.add(txt)
            }
        }
        return cats.toList()
    }

    private fun collectHeadingAnchors(
        root: Element,
        anchorMap: MutableMap<String, String>,
        slugCounts: MutableMap<String, Int>
    ) {
        root.select("h1, h2, h3, h4, h5, h6").forEach { h ->
            val text = h.text().trim()
            if (text.isNotEmpty()) {
                val baseSlug = SlugUtils.markdownAnchorSlug(text)
                val count = slugCounts[baseSlug] ?: 0
                slugCounts[baseSlug] = count + 1
                val uniqueSlug = if (count == 0) baseSlug else "$baseSlug-$count"
                val id = h.id().trim()
                if (id.isNotEmpty()) {
                    anchorMap[id] = uniqueSlug
                }
                h.select("[id]").forEach { childWithId ->
                    val cid = childWithId.id().trim()
                    if (cid.isNotEmpty() && !anchorMap.containsKey(cid)) {
                        anchorMap[cid] = uniqueSlug
                    }
                }
            }
        }
    }

    private data class RenderContext(
        val sb: StringBuilder,
        val linkResolver: HtmlLinkResolver,
        val mediaRefs: MutableList<DiscoveredMediaRef>,
        val warnings: MutableList<String>,
        var listDepth: Int = 0,
        var inPre: Boolean = false,
        var blockquoteDepth: Int = 0
    )

    private fun renderChildren(parent: Node, ctx: RenderContext) {
        for (child in parent.childNodes()) {
            renderNode(child, ctx)
        }
    }

    private fun renderNode(node: Node, ctx: RenderContext) {
        when (node) {
            is Comment -> return
            is TextNode -> {
                if (ctx.inPre) {
                    ctx.sb.append(node.wholeText)
                } else {
                    val collapsed = collapseWhitespace(node.wholeText)
                    if (collapsed.isNotEmpty()) {
                        appendTextWithSpacing(ctx.sb, collapsed)
                    }
                }
            }
            is Element -> renderElement(node, ctx)
        }
    }

    private fun renderElement(el: Element, ctx: RenderContext) {
        val tag = el.tagName().lowercase()

        // MathML / Wikipedia Math elements
        if (tag == "math" || el.hasClass("mwe-math-element")) {
            if (config.preserveFormulas) {
                renderMathElement(el, ctx)
            } else {
                ctx.sb.append(el.text())
            }
            return
        }

        when (tag) {
            "script", "style", "noscript", "link", "meta", "head" -> return

            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = tag.substring(1).toInt()
                val text = renderInlineText(el, ctx).trim()
                if (text.isNotEmpty()) {
                    ensureBlockBreak(ctx.sb)
                    if ((level == 1 && config.preserveHeadings) || (level > 1 && config.preserveSubheadings)) {
                        ctx.sb.append("#".repeat(level)).append(' ').append(text)
                    } else {
                        ctx.sb.append("**").append(text).append("**")
                    }
                    ensureBlockBreak(ctx.sb)
                }
            }

            "p", "section", "article", "header", "footer", "main" -> {
                if (tag == "p") ensureBlockBreak(ctx.sb)
                renderChildren(el, ctx)
                if (tag == "p") ensureBlockBreak(ctx.sb)
            }

            "div" -> {
                val isBlock = el.children().isNotEmpty()
                if (isBlock) ensureBlockBreak(ctx.sb)
                renderChildren(el, ctx)
                if (isBlock) ensureBlockBreak(ctx.sb)
            }

            "br" -> {
                if (ctx.inPre) ctx.sb.append('\n')
                else ctx.sb.append("  \n")
            }

            "hr" -> {
                ensureBlockBreak(ctx.sb)
                ctx.sb.append("---\n\n")
            }

            "strong", "b" -> {
                val inner = renderInlineText(el, ctx).trim()
                if (inner.isNotEmpty()) ctx.sb.append("**").append(inner).append("**")
            }

            "em", "i", "cite" -> {
                val inner = renderInlineText(el, ctx).trim()
                if (inner.isNotEmpty()) ctx.sb.append('*').append(inner).append('*')
            }

            "del", "s", "strike" -> {
                val inner = renderInlineText(el, ctx).trim()
                if (inner.isNotEmpty()) {
                    if (config.dialect == MarkdownDialect.GFM) {
                        ctx.sb.append("~~").append(inner).append("~~")
                    } else {
                        ctx.sb.append("<del>").append(inner).append("</del>")
                    }
                }
            }

            "sup" -> {
                val inner = renderInlineText(el, ctx).trim()
                if (inner.isNotEmpty()) {
                    if (config.preserveFootnotes && (el.hasClass("reference") || inner.matches(Regex("\\[?\\d+]?")))) {
                        val cleanNum = inner.removePrefix("[").removeSuffix("]")
                        ctx.sb.append("[^").append(cleanNum).append("]")
                    } else {
                        ctx.sb.append("<sup>").append(inner).append("</sup>")
                    }
                }
            }

            "sub" -> {
                val inner = renderInlineText(el, ctx).trim()
                if (inner.isNotEmpty()) ctx.sb.append("<sub>").append(inner).append("</sub>")
            }

            "kbd" -> {
                val codeText = el.text().trim()
                if (codeText.isNotEmpty()) {
                    val fence = if (codeText.contains('`')) "``" else "`"
                    ctx.sb.append(fence).append(codeText).append(fence)
                }
            }

            "mark" -> {
                val inner = renderInlineText(el, ctx).trim()
                if (inner.isNotEmpty()) {
                    ctx.sb.append("**").append(inner).append("**")
                }
            }

            "q" -> {
                val inner = renderInlineText(el, ctx).trim()
                if (inner.isNotEmpty()) {
                    ctx.sb.append('"').append(inner).append('"')
                }
            }

            "abbr" -> {
                val inner = renderInlineText(el, ctx).trim()
                val expansion = el.attr("title").trim()
                if (inner.isNotEmpty()) {
                    ctx.sb.append(inner)
                    if (expansion.isNotEmpty() && !expansion.equals(inner, ignoreCase = true)) {
                        ctx.sb.append(" (").append(expansion).append(')')
                    }
                }
            }

            "code" -> {
                if (ctx.inPre) {
                    ctx.sb.append(el.wholeText())
                } else {
                    val codeText = el.text()
                    if (codeText.isNotEmpty()) {
                        val fence = if (codeText.contains('`')) "``" else "`"
                        ctx.sb.append(fence).append(codeText).append(fence)
                    }
                }
            }

            "pre" -> {
                ensureBlockBreak(ctx.sb)
                if (config.preserveCodeBlocks) {
                    val codeEl = el.selectFirst("code")
                    val lang = codeEl?.classNames()
                        ?.firstOrNull { it.startsWith("language-") || it.startsWith("lang-") }
                        ?.substringAfter('-')
                        .orEmpty()
                    val codeContent = (codeEl?.wholeText() ?: el.wholeText()).trimEnd('\n')
                    ctx.sb.append("```").append(lang).append('\n')
                    ctx.sb.append(codeContent).append('\n')
                    ctx.sb.append("```\n\n")
                } else {
                    ctx.sb.append(el.text()).append("\n\n")
                }
            }

            "blockquote" -> {
                ensureBlockBreak(ctx.sb)
                if (config.preserveBlockquotes) {
                    val subSb = StringBuilder()
                    val subCtx = ctx.copy(sb = subSb, blockquoteDepth = ctx.blockquoteDepth + 1)
                    renderChildren(el, subCtx)
                    val lines = subSb.toString().trim().lines()
                    for (line in lines) {
                        ctx.sb.append("> ").append(line).append('\n')
                    }
                    ctx.sb.append('\n')
                } else {
                    renderChildren(el, ctx)
                    ensureBlockBreak(ctx.sb)
                }
            }

            "ul", "ol" -> {
                if (ctx.listDepth == 0) ensureBlockBreak(ctx.sb)
                val isOrdered = tag == "ol"
                val isReferenceList = el.hasClass("references")
                var itemNum = el.attr("start").toIntOrNull() ?: 1
                for (child in el.children()) {
                    if (child.tagName().equals("li", ignoreCase = true)) {
                        if (config.preserveLists) {
                            val indent = "  ".repeat(ctx.listDepth)
                            val marker = if (isOrdered) "${itemNum++}." else "-"
                            val liSub = StringBuilder()
                            val liCtx = ctx.copy(sb = liSub, listDepth = ctx.listDepth + 1)
                            renderChildren(child, liCtx)
                            val rawLi = liSub.toString().trim()
                            val liLines = rawLi.lines()
                            if (liLines.isNotEmpty()) {
                                val firstLine = if (isReferenceList && config.preserveReferences) {
                                    "[^${itemNum - 1}]: ${liLines.first()}"
                                } else {
                                    "$indent$marker ${liLines.first()}"
                                }
                                ctx.sb.append(firstLine).append('\n')
                                for (extra in liLines.drop(1)) {
                                    if (extra.isBlank()) continue
                                    if (extra.trimStart().startsWith("-") || extra.trimStart().matches(Regex("\\d+\\..*"))) {
                                        ctx.sb.append(extra).append('\n')
                                    } else {
                                        ctx.sb.append(indent).append("  ").append(extra).append('\n')
                                    }
                                }
                            }
                        } else {
                            renderChildren(child, ctx)
                            ctx.sb.append('\n')
                        }
                    }
                }
                if (ctx.listDepth == 0) ctx.sb.append('\n')
            }

            "dl" -> {
                ensureBlockBreak(ctx.sb)
                for (child in el.children()) {
                    when (child.tagName().lowercase()) {
                        "dt" -> ctx.sb.append("**").append(renderInlineText(child, ctx).trim()).append("**\n")
                        "dd" -> ctx.sb.append(": ").append(renderInlineText(child, ctx).trim()).append("\n\n")
                    }
                }
            }

            "details" -> {
                ensureBlockBreak(ctx.sb)
                val summary = el.selectFirst("summary")?.let { renderInlineText(it, ctx).trim() }
                if (!summary.isNullOrEmpty()) {
                    ctx.sb.append("**").append(summary).append("**\n\n")
                }
                for (child in el.childNodes()) {
                    if (child is Element && child.tagName().equals("summary", ignoreCase = true)) continue
                    renderNode(child, ctx)
                }
                ensureBlockBreak(ctx.sb)
            }

            "a" -> {
                val href = el.attr("href").trim()
                val hasNestedMedia = el.selectFirst("img, picture, svg, video, audio") != null
                if (hasNestedMedia && el.text().isBlank()) {
                    // Render nested media directly so blockquotes are never nested inside [link](href)
                    renderChildren(el, ctx)
                } else {
                    val text = renderInlineText(el, ctx).trim().ifEmpty { href }
                    if (href.isEmpty()) {
                        ctx.sb.append(text)
                    } else {
                        ctx.sb.append(ctx.linkResolver.resolve(href, text))
                    }
                }
            }

            "table" -> {
                ensureBlockBreak(ctx.sb)
                if (config.preserveTables) {
                    renderTable(el, ctx)
                } else {
                    ctx.sb.append(el.text()).append("\n\n")
                }
            }

            "figure" -> handleFigureElement(el, ctx)
            "img" -> handleImageElement(el, null, ctx)
            "picture" -> handlePictureElement(el, ctx)
            "svg" -> handleGraphicFallback(el, "Gráfico SVG", ctx)
            "canvas" -> handleGraphicFallback(el, "Gráfico Canvas", ctx)
            "video" -> handleAudioVideoElement(el, isVideo = true, ctx)
            "audio" -> handleAudioVideoElement(el, isVideo = false, ctx)
            "source", "track" -> return

            else -> renderChildren(el, ctx)
        }
    }

    private fun renderInlineText(el: Element, ctx: RenderContext): String {
        val tempSb = StringBuilder()
        val inlineCtx = ctx.copy(sb = tempSb)
        renderChildren(el, inlineCtx)
        return tempSb.toString()
    }

    private fun renderMathElement(el: Element, ctx: RenderContext) {
        val tex = el.selectFirst("annotation[encoding=application/x-tex]")?.text()?.trim()
            ?: el.attr("alttext").trim().takeIf { it.isNotEmpty() }
            ?: el.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
        if (!tex.isNullOrEmpty()) {
            val isBlock = el.attr("display").equals("block", ignoreCase = true) || el.parent()?.tagName()?.equals("dd", true) == true
            if (isBlock) {
                ensureBlockBreak(ctx.sb)
                ctx.sb.append("$$\n").append(tex).append("\n$$\n\n")
            } else {
                ctx.sb.append('$').append(tex).append('$')
            }
        } else {
            val plainMath = el.text().trim()
            if (plainMath.isNotEmpty()) ctx.sb.append(plainMath)
        }
    }

    private fun renderTable(table: Element, ctx: RenderContext) {
        val caption = table.selectFirst("caption")?.text()?.trim()
        if (!caption.isNullOrEmpty()) {
            ctx.sb.append("*").append(caption).append("*\n\n")
        }

        val rows = table.select("tr").filter { it.closest("table") == table }
        if (rows.isEmpty()) return

        val matrix = mutableListOf<List<String>>()
        var maxCols = 0
        for (row in rows) {
            val cells = row.children().filter {
                it.tagName().equals("th", true) || it.tagName().equals("td", true)
            }
            if (cells.isEmpty()) continue
            val rowTexts = cells.map { cell ->
                renderInlineText(cell, ctx)
                    .replace("  \n", "; ")
                    .replace("\n", " ")
                    .replace("|", "\\|")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            maxCols = maxOf(maxCols, rowTexts.size)
            matrix.add(rowTexts)
        }

        if (matrix.isEmpty() || maxCols == 0) return

        val headerRow = matrix.first()
        ctx.sb.append("| ")
        for (c in 0 until maxCols) {
            ctx.sb.append(headerRow.getOrElse(c) { "" }).append(" | ")
        }
        ctx.sb.append('\n')

        ctx.sb.append("| ")
        for (c in 0 until maxCols) {
            ctx.sb.append("--- | ")
        }
        ctx.sb.append('\n')

        for (r in 1 until matrix.size) {
            val row = matrix[r]
            ctx.sb.append("| ")
            for (c in 0 until maxCols) {
                ctx.sb.append(row.getOrElse(c) { "" }).append(" | ")
            }
            ctx.sb.append('\n')
        }
        ctx.sb.append('\n')
    }

    private fun handleFigureElement(figure: Element, ctx: RenderContext) {
        val caption = figure.selectFirst("figcaption")?.let { renderInlineText(it, ctx).trim() }?.takeIf { it.isNotEmpty() }
        val img = figure.selectFirst("img")
        val video = figure.selectFirst("video")
        val audio = figure.selectFirst("audio")
        val svg = figure.selectFirst("svg")

        when {
            img != null -> handleImageElement(img, caption, ctx)
            video != null -> handleAudioVideoElement(video, isVideo = true, ctx, externalCaption = caption)
            audio != null -> handleAudioVideoElement(audio, isVideo = false, ctx, externalCaption = caption)
            svg != null -> handleGraphicFallback(svg, "Figura SVG", ctx, externalCaption = caption)
            else -> {
                if (!caption.isNullOrEmpty()) {
                    ensureBlockBreak(ctx.sb)
                    ctx.sb.append("> Legenda: ").append(caption).append("\n\n")
                }
            }
        }
    }

    private fun handlePictureElement(picture: Element, ctx: RenderContext) {
        val img = picture.selectFirst("img")
        if (img != null) {
            handleImageElement(img, null, ctx)
        } else {
            val src = picture.selectFirst("source")?.attr("srcset")?.substringBefore(' ')?.trim().orEmpty()
            ctx.mediaRefs.add(
                DiscoveredMediaRef("Imagem", src, null, null, null, null, null)
            )
        }
    }

    private fun handleImageElement(img: Element, externalCaption: String?, ctx: RenderContext) {
        val src = img.attr("src").trim().ifEmpty { img.attr("data-src").trim() }
        val alt = if (config.preserveAltText) img.attr("alt").trim().takeIf { it.isNotEmpty() } else null
        val title = if (config.preserveTextDescriptions) img.attr("title").trim().takeIf { it.isNotEmpty() } else null
        val caption = if (config.preserveCaptions) externalCaption else null
        val credit = img.attr("data-credit").trim().takeIf { it.isNotEmpty() }

        ctx.mediaRefs.add(
            DiscoveredMediaRef(
                mediaType = "Imagem",
                src = src,
                alt = alt,
                title = title,
                caption = caption,
                transcript = null,
                credit = credit
            )
        )

        if (config.mediaMarkerMode == MediaMarkerMode.REMOVE_COMPLETELY) {
            if (!caption.isNullOrEmpty()) {
                ensureBlockBreak(ctx.sb)
                ctx.sb.append(caption).append("\n\n")
            }
            return
        }

        val desc = alt ?: title
        if (desc == null && caption == null && credit == null) {
            // Do not invent fictitious text when no description or caption exists
            return
        }

        ensureBlockBreak(ctx.sb)
        if (desc != null) {
            ctx.sb.append("> [Imagem não exportada: ").append(desc).append("]\n")
        } else {
            ctx.sb.append("> [Imagem não exportada]\n")
        }
        if (caption != null) {
            ctx.sb.append(">\n> Legenda: ").append(caption).append('\n')
        }
        if (title != null && title != alt) {
            ctx.sb.append("> Descrição: ").append(title).append('\n')
        }
        if (credit != null) {
            ctx.sb.append("> Créditos: ").append(credit).append('\n')
        }
        ctx.sb.append('\n')
    }

    private fun handleAudioVideoElement(
        el: Element,
        isVideo: Boolean,
        ctx: RenderContext,
        externalCaption: String? = null
    ) {
        val label = if (isVideo) "Vídeo" else "Áudio"
        val src = el.attr("src").trim().ifEmpty { el.selectFirst("source")?.attr("src")?.trim().orEmpty() }
        val title = if (config.preserveTextDescriptions) {
            el.attr("title").trim().ifEmpty { el.attr("aria-label").trim() }.takeIf { it.isNotEmpty() }
        } else null
        val caption = if (config.preserveCaptions) externalCaption else null
        val trackText = if (config.preserveTranscripts) {
            el.select("track").mapNotNull { it.attr("label").trim().takeIf { l -> l.isNotEmpty() } }
                .joinToString(", ").takeIf { it.isNotEmpty() }
        } else null
        val fallbackInner = el.ownText().trim().takeIf { it.isNotEmpty() }

        ctx.mediaRefs.add(
            DiscoveredMediaRef(
                mediaType = label,
                src = src,
                alt = null,
                title = title,
                caption = caption,
                transcript = trackText ?: fallbackInner,
                credit = null
            )
        )

        if (config.mediaMarkerMode == MediaMarkerMode.REMOVE_COMPLETELY) {
            if (!caption.isNullOrEmpty()) {
                ensureBlockBreak(ctx.sb)
                ctx.sb.append(caption).append("\n\n")
            }
            return
        }

        val info = title ?: caption ?: trackText ?: fallbackInner
        if (info == null) return

        ensureBlockBreak(ctx.sb)
        ctx.sb.append("> [").append(label).append(" não exportado")
        if (title != null) ctx.sb.append(": ").append(title)
        ctx.sb.append("]\n")
        if (caption != null && caption != title) {
            ctx.sb.append(">\n> Legenda: ").append(caption).append('\n')
        }
        if (trackText != null) {
            ctx.sb.append("> Faixas textuais: ").append(trackText).append('\n')
        }
        if (fallbackInner != null && fallbackInner != title) {
            ctx.sb.append("> Transcrição/Nota: ").append(fallbackInner).append('\n')
        }
        ctx.sb.append('\n')
    }

    private fun handleGraphicFallback(
        el: Element,
        label: String,
        ctx: RenderContext,
        externalCaption: String? = null
    ) {
        val title = el.selectFirst("title")?.text()?.trim()
            ?: el.attr("aria-label").trim().takeIf { it.isNotEmpty() }
        val desc = el.selectFirst("desc")?.text()?.trim()
        ctx.mediaRefs.add(
            DiscoveredMediaRef(label, "", title, desc, externalCaption, null, null)
        )
        if (config.mediaMarkerMode == MediaMarkerMode.REMOVE_COMPLETELY) {
            if (!externalCaption.isNullOrEmpty()) {
                ensureBlockBreak(ctx.sb)
                ctx.sb.append(externalCaption).append("\n\n")
            }
            return
        }
        val best = title ?: desc ?: externalCaption ?: return
        ensureBlockBreak(ctx.sb)
        ctx.sb.append("> [").append(label).append(" não exportado: ").append(best).append("]\n")
        if (externalCaption != null && externalCaption != best) {
            ctx.sb.append(">\n> Legenda: ").append(externalCaption).append('\n')
        }
        ctx.sb.append('\n')
    }

    private fun collapseWhitespace(text: String): String {
        val sb = StringBuilder(text.length)
        var lastWasSpace = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                if (!lastWasSpace) {
                    sb.append(' ')
                    lastWasSpace = true
                }
            } else {
                sb.append(ch)
                lastWasSpace = false
            }
        }
        return sb.toString()
    }

    private fun appendTextWithSpacing(sb: StringBuilder, text: String) {
        if (text == " ") {
            if (sb.isNotEmpty() && !sb.last().isWhitespace()) {
                sb.append(' ')
            }
        } else {
            val trimmedStart = if (sb.isEmpty() || sb.last() == '\n') text.trimStart() else text
            sb.append(trimmedStart)
        }
    }

    private fun ensureBlockBreak(sb: StringBuilder) {
        if (sb.isEmpty()) return
        val len = sb.length
        when {
            len >= 2 && sb[len - 1] == '\n' && sb[len - 2] == '\n' -> Unit
            sb[len - 1] == '\n' -> sb.append('\n')
            else -> sb.append("\n\n")
        }
    }

    private fun postProcessMarkdown(raw: String): String {
        return raw
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun compactMarkdown(raw: String): String {
        return raw
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("\n{2,}"), "\n\n")
            .trim()
    }
}
