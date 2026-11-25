package com.example.parser

import com.example.model.ConversionConfig
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

data class PlainConversionResult(
    val title: String?,
    val markdownBody: String,
    val warnings: List<String> = emptyList()
)

object PlainTextAndXmlConverter {

    fun convertPlainText(bytes: ByteArray, fallbackTitle: String): PlainConversionResult {
        val text = String(bytes, StandardCharsets.UTF_8)
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()
        val body = buildString {
            append("# ").append(fallbackTitle).append("\n\n")
            append(text)
        }
        return PlainConversionResult(title = fallbackTitle, markdownBody = body)
    }

    fun convertXmlText(bytes: ByteArray, fallbackTitle: String): PlainConversionResult {
        val raw = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
        return try {
            val doc: Document = Jsoup.parse(raw, "", Parser.xmlParser())
            val title = doc.selectFirst("title")?.text()?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val sb = StringBuilder()
            sb.append("# ").append(title).append("\n\n")
            val rootChildren = doc.children()
            for (child in rootChildren) {
                appendXmlElement(child, sb, 2)
            }
            val result = sb.toString().trim()
            if (result == "# $title") {
                PlainConversionResult(title = title, markdownBody = "# $title\n\n```xml\n${raw.trim()}\n```")
            } else {
                PlainConversionResult(title = title, markdownBody = result)
            }
        } catch (e: Exception) {
            PlainConversionResult(
                title = fallbackTitle,
                markdownBody = "# $fallbackTitle\n\n```xml\n${raw.trim()}\n```",
                warnings = listOf("XML convertido em bloco literal devido a estrutura irregular: ${e.message}")
            )
        }
    }

    private fun appendXmlElement(el: Element, sb: StringBuilder, headingLevel: Int) {
        val tag = el.tagName()
        if (el.children().isEmpty()) {
            val text = el.text().trim()
            if (text.isNotEmpty()) {
                sb.append("- **").append(tag).append("**: ").append(text).append('\n')
            }
        } else {
            val level = headingLevel.coerceAtMost(6)
            sb.append('\n').append("#".repeat(level)).append(' ').append(tag).append("\n\n")
            val ownText = el.ownText().trim()
            if (ownText.isNotEmpty()) {
                sb.append(ownText).append("\n\n")
            }
            for (child in el.children()) {
                appendXmlElement(child, sb, headingLevel + 1)
            }
        }
    }

    fun convertPdfSelectableText(
        bytes: ByteArray,
        fallbackTitle: String,
        config: ConversionConfig
    ): PlainConversionResult {
        if (!config.extractPdfText) {
            return PlainConversionResult(
                title = fallbackTitle,
                markdownBody = "",
                warnings = listOf("Extração de texto de PDF desativada nas configurações.")
            )
        }

        return try {
            PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
                val infoTitle = document.documentInformation?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
                val stripper = PDFTextStripper()
                val extracted = stripper.getText(document)
                    .replace("\r\n", "\n")
                    .replace(Regex("\n{3,}"), "\n\n")
                    .trim()

                val warnings = mutableListOf<String>()
                val body = if (extracted.isBlank()) {
                    warnings.add("PDF não possui camada de texto selecionável (páginas baseadas em imagem não são rasterizadas ou exportadas).")
                    "# $infoTitle\n\n> [PDF sem texto selecionável — imagens de páginas não exportadas]"
                } else {
                    "# $infoTitle\n\n$extracted"
                }

                PlainConversionResult(title = infoTitle, markdownBody = body, warnings = warnings)
            }
        } catch (e: Throwable) {
            PlainConversionResult(
                title = fallbackTitle,
                markdownBody = "# $fallbackTitle\n\n> [Falha ao extrair texto selecionável do PDF: ${e.message ?: "erro desconhecido"}]",
                warnings = listOf("Erro ao processar PDF '$fallbackTitle': ${e.message}")
            )
        }
    }
}
