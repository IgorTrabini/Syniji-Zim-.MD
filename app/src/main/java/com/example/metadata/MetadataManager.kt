package com.example.metadata

import com.example.markdown.YamlFrontMatter
import com.example.model.ConversionConfig
import com.example.model.ZimInspection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MetadataManager {

    const val APP_BRAND_NAME = "Syniji Zim converter .MD"
    const val APP_VERSION = "1.0.0"

    fun buildArticleFrontMatter(
        title: String,
        description: String?,
        language: String?,
        sourcePath: String,
        sourceTitle: String,
        sourceMime: String,
        categories: List<String>,
        conversionStatus: String,
        config: ConversionConfig
    ): String {
        val fields = linkedMapOf<String, String?>(
            "title" to title,
            "description" to description,
            "language" to language,
            "source_format" to "ZIM",
            "source_path" to if (config.preserveOriginalPaths) sourcePath else null,
            "source_title" to if (config.preserveOriginalNamesInMetadata) sourceTitle else null,
            "source_mime" to sourceMime,
            "categories" to categories.takeIf { it.isNotEmpty() }?.joinToString(", "),
            "conversion_format" to "Markdown (${config.dialect.name})",
            "conversion_status" to conversionStatus
        )
        return YamlFrontMatter.build(fields)
    }

    fun buildMetadataMarkdown(
        inspection: ZimInspection,
        config: ConversionConfig,
        convertedAtMs: Long = System.currentTimeMillis()
    ): String {
        val meta = inspection.metadata
        val isoDate = formatIsoUtc(convertedAtMs)
        val sb = StringBuilder()

        sb.append(
            YamlFrontMatter.build(
                linkedMapOf(
                    "title" to (meta.title ?: inspection.fileName),
                    "type" to "library_metadata",
                    "uuid" to meta.uuid,
                    "converter" to "$APP_BRAND_NAME v$APP_VERSION",
                    "converted_at" to isoDate
                )
            )
        )

        sb.append("# Metadados da Biblioteca\n\n")
        sb.append("## Informações da Fonte ZIM\n\n")
        sb.append("| Campo | Valor |\n")
        sb.append("| --- | --- |\n")
        appendRow(sb, "Título da biblioteca", meta.title)
        appendRow(sb, "Descrição", meta.description)
        appendRow(sb, "Idioma principal", meta.language)
        if (meta.languages.isNotEmpty()) {
            appendRow(sb, "Idiomas disponíveis", meta.languages.joinToString(", "))
        }
        appendRow(sb, "Publicador", meta.publisher)
        appendRow(sb, "Criador", meta.creator)
        appendRow(sb, "Data original", meta.date)
        appendRow(sb, "UUID", meta.uuid)
        appendRow(sb, "Nome original do arquivo", inspection.fileName)
        appendRow(sb, "Tamanho original", "${inspection.fileSizeBytes} bytes (${formatSize(inspection.fileSizeBytes)})")
        appendRow(sb, "Página principal", meta.mainPagePath)
        appendRow(sb, "Índice de títulos", if (inspection.hasTitleIndex) "Disponível" else "Ausente")
        appendRow(sb, "Índice de pesquisa (fulltext)", if (inspection.hasFulltextIndex) "Disponível" else "Ausente")
        appendRow(sb, "Checksum ZIM", if (inspection.hasChecksum) "Presente" else "Ausente")
        appendRow(sb, "Esquema de namespaces", if (inspection.hasNewNamespaceScheme) "Novo (C/M/X)" else "Clássico (A/I/M/-)")

        val extraMeta = meta.rawMetadata.filterKeys {
            it !in setOf("Title", "Description", "Language", "Publisher", "Creator", "Date", "Name")
        }
        if (extraMeta.isNotEmpty()) {
            sb.append("\n## Identificadores e Metadados Adicionais do ZIM\n\n")
            sb.append("| Chave | Valor |\n")
            sb.append("| --- | --- |\n")
            for ((k, v) in extraMeta) {
                appendRow(sb, k, v)
            }
        }

        sb.append("\n## Informações da Conversão\n\n")
        sb.append("| Campo | Valor |\n")
        sb.append("| --- | --- |\n")
        appendRow(sb, "Aplicativo conversor", "$APP_BRAND_NAME v$APP_VERSION")
        appendRow(sb, "Data da conversão", isoDate)
        appendRow(sb, "Motor de leitura ZIM", inspection.engineUsed)
        appendRow(sb, "Dialeto Markdown", config.dialect.name)
        appendRow(sb, "Modo de exportação", config.exportMode.name)
        appendRow(sb, "Exclusão absoluta de mídias", if (config.excludeAllMedia) "Ativada (somente texto)" else "Ativada")
        appendRow(sb, "Preservar caminhos originais", config.preserveOriginalPaths.toString())
        appendRow(sb, "Preservar redirecionamentos", config.preserveAllAliases.toString())
        appendRow(sb, "Extrair texto de PDF", config.extractPdfText.toString())

        return sb.toString()
    }

    private fun appendRow(sb: StringBuilder, key: String, value: String?) {
        if (value.isNullOrBlank()) return
        val safeVal = value.replace("|", "\\|").replace("\n", " ").trim()
        sb.append("| ").append(key).append(" | ").append(safeVal).append(" |\n")
    }

    fun formatIsoUtc(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }
}
