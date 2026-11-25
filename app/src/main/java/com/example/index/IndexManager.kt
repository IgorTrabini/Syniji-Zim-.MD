package com.example.index

import com.example.links.LinkResolver
import com.example.markdown.SlugUtils
import com.example.metadata.MetadataManager
import com.example.model.ConversionConfig
import com.example.model.ManifestRecord
import com.example.model.RedirectResolution
import com.example.model.ZimInspection

class IndexManager(private val config: ConversionConfig) {

    fun buildReadme(
        inspection: ZimInspection,
        manifest: List<ManifestRecord>,
        redirects: List<RedirectResolution>
    ): String {
        val title = config.customLibraryName.ifBlank {
            inspection.metadata.title ?: inspection.fileName
        }
        return buildString {
            append("# ").append(title).append("\n\n")
            inspection.metadata.description?.takeIf { it.isNotBlank() }?.let {
                append(it).append("\n\n")
            }
            append("> Biblioteca Markdown exclusivamente textual convertida offline por **")
                .append(MetadataManager.APP_BRAND_NAME)
                .append(" v").append(MetadataManager.APP_VERSION)
                .append("**. Nenhuma mídia binária foi extraída ou exportada.\n\n")

            append("## Navegação Rápida\n\n")
            if (config.generateHomePage) append("- [Página Inicial (HOME.md)](HOME.md)\n")
            if (config.generateGeneralIndex) append("- [Índice Geral (INDEX.md)](INDEX.md)\n")
            append("- [Metadados da Fonte (METADATA.md)](METADATA.md)\n")
            append("- [Manifesto de Documentos (MANIFEST.md)](MANIFEST.md)\n")
            if (config.generateRedirectsReport) append("- [Redirecionamentos e Aliases (REDIRECTS.md)](REDIRECTS.md)\n")
            append("- [Mapa de Links (LINKS.md)](LINKS.md)\n")
            if (config.logExcludedMediaInReport) append("- [Conteúdo Multimídia Excluído (EXCLUDED_CONTENT.md)](EXCLUDED_CONTENT.md)\n")
            append("- [Relatório de Conversão (CONVERSION_REPORT.md)](CONVERSION_REPORT.md)\n")
            append("- [Avisos (WARNINGS.md)](WARNINGS.md)\n")
            append("- [Erros (ERRORS.md)](ERRORS.md)\n\n")

            val idxDir = SlugUtils.sanitizeFileSegment(config.customIndexesSubdir, "indexes")
            append("## Índices Detalhados\n\n")
            if (config.generateAlphabeticalIndex) append("- [Por Título]($idxDir/INDEX_BY_TITLE.md)\n")
            if (config.generateCategoryIndex) append("- [Por Categoria]($idxDir/INDEX_BY_CATEGORY.md)\n")
            if (config.generatePathIndex) append("- [Por Caminho Original]($idxDir/INDEX_BY_PATH.md)\n")
            if (config.generateRelationshipIndex) append("- [Por Relacionamento]($idxDir/INDEX_BY_RELATIONSHIP.md)\n")

            append("\n## Resumo\n\n")
            append("- Documentos textuais convertidos: **").append(manifest.size).append("**\n")
            append("- Redirecionamentos mapeados: **").append(redirects.size).append("**\n")
        }
    }

    fun buildHome(
        inspection: ZimInspection,
        manifest: List<ManifestRecord>,
        mainPageMarkdownPath: String?
    ): String {
        val title = config.customLibraryName.ifBlank {
            inspection.metadata.title ?: inspection.fileName
        }
        val categories = manifest.groupBy { it.category }.entries.sortedByDescending { it.value.size }
        return buildString {
            append("# ").append(title).append(" — Página Inicial\n\n")
            inspection.metadata.description?.takeIf { it.isNotBlank() }?.let {
                append(it).append("\n\n")
            }
            if (mainPageMarkdownPath != null) {
                append("## Artigo Principal da Biblioteca\n\n")
                append("- [Abrir Página Principal Original](").append(mainPageMarkdownPath).append(")\n\n")
            }
            append("## Principais Seções e Categorias\n\n")
            for ((cat, items) in categories.take(25)) {
                val anchor = SlugUtils.markdownAnchorSlug(cat)
                val idxDir = SlugUtils.sanitizeFileSegment(config.customIndexesSubdir, "indexes")
                append("- [").append(cat).append("](").append(idxDir).append("/INDEX_BY_CATEGORY.md#").append(anchor)
                    .append(") (").append(items.size).append(" artigos)\n")
            }
            append("\n## Primeiros Documentos\n\n")
            for (doc in manifest.take(30)) {
                append("- [").append(doc.title).append("](").append(doc.markdownPath).append(")\n")
            }
        }
    }

    fun buildGeneralIndex(
        manifest: List<ManifestRecord>,
        redirects: List<RedirectResolution>
    ): String {
        val byCategory = manifest.groupBy { it.category }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        return buildString {
            append("# Índice Geral da Biblioteca\n\n")
            append("Total de documentos textuais: **").append(manifest.size).append("**  \n")
            append("Total de redirecionamentos: **").append(redirects.size).append("**\n\n")

            append("## Categorias\n\n")
            for ((cat, docs) in byCategory) {
                append("- [").append(cat).append("](#").append(SlugUtils.markdownAnchorSlug(cat))
                    .append(") (").append(docs.size).append(")\n")
            }
            append('\n')

            for ((cat, docs) in byCategory) {
                append("## ").append(cat).append("\n\n")
                val ordered = if (config.preserveOriginalOrder) docs else docs.sortedBy { it.title.lowercase() }
                for (doc in ordered) {
                    append("- [").append(doc.title).append("](").append(doc.markdownPath).append(") `")
                        .append(doc.originalPath).append("`\n")
                }
                append('\n')
            }
        }
    }

    fun buildIndexByTitle(manifest: List<ManifestRecord>): String {
        val idxDir = SlugUtils.sanitizeFileSegment(config.customIndexesSubdir, "indexes")
        val sorted = manifest.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        val grouped = sorted.groupBy {
            val ch = it.title.trim().firstOrNull()?.uppercaseChar() ?: '#'
            if (ch.isLetter()) ch.toString() else "#"
        }
        return buildString {
            append("# Índice Alfabético por Título\n\n")
            for ((letter, items) in grouped) {
                append("## ").append(letter).append("\n\n")
                for (doc in items) {
                    val rel = LinkResolver.computeRelativePath("$idxDir/INDEX_BY_TITLE.md", doc.markdownPath)
                    append("- [").append(doc.title).append("](").append(rel).append(") — `")
                        .append(doc.originalPath).append("`\n")
                }
                append('\n')
            }
        }
    }

    fun buildIndexByCategory(manifest: List<ManifestRecord>): String {
        val idxDir = SlugUtils.sanitizeFileSegment(config.customIndexesSubdir, "indexes")
        val byCat = manifest.groupBy { it.category }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        return buildString {
            append("# Índice por Categoria\n\n")
            for ((cat, items) in byCat) {
                append("## ").append(cat).append("\n\n")
                for (doc in items.sortedBy { it.title.lowercase() }) {
                    val rel = LinkResolver.computeRelativePath("$idxDir/INDEX_BY_CATEGORY.md", doc.markdownPath)
                    append("- [").append(doc.title).append("](").append(rel).append(")\n")
                }
                append('\n')
            }
        }
    }

    fun buildIndexByPath(manifest: List<ManifestRecord>): String {
        val idxDir = SlugUtils.sanitizeFileSegment(config.customIndexesSubdir, "indexes")
        val sorted = manifest.sortedBy { it.originalPath }
        return buildString {
            append("# Índice por Caminho Original\n\n")
            append("| Caminho Original (ZIM) | Título | Arquivo Markdown |\n")
            append("| --- | --- | --- |\n")
            for (doc in sorted) {
                val rel = LinkResolver.computeRelativePath("$idxDir/INDEX_BY_PATH.md", doc.markdownPath)
                append("| `").append(doc.originalPath.replace("|", "\\|")).append("` | ")
                    .append(doc.title.replace("|", "\\|")).append(" | [")
                    .append(doc.markdownPath).append("](").append(rel).append(") |\n")
            }
        }
    }

    fun buildIndexByRelationship(
        manifest: List<ManifestRecord>,
        relationships: Map<String, Set<String>>,
        redirects: List<RedirectResolution>
    ): String {
        val idxDir = SlugUtils.sanitizeFileSegment(config.customIndexesSubdir, "indexes")
        val byOrigPath = manifest.associateBy { it.originalPath }
        val redirectsByTarget = redirects.filter { it.finalTargetPath != null }.groupBy { it.finalTargetPath!! }

        return buildString {
            append("# Índice de Relacionamentos e Referências Cruzadas\n\n")
            if (relationships.isEmpty() && redirectsByTarget.isEmpty()) {
                append("Nenhuma referência cruzada ou alias registrado nesta biblioteca.\n")
                return@buildString
            }
            for (doc in manifest) {
                val outgoing = relationships[doc.originalPath].orEmpty()
                val aliases = redirectsByTarget[doc.originalPath].orEmpty()
                if (outgoing.isEmpty() && aliases.isEmpty()) continue

                val selfRel = LinkResolver.computeRelativePath("$idxDir/INDEX_BY_RELATIONSHIP.md", doc.markdownPath)
                append("## [").append(doc.title).append("](").append(selfRel).append(")\n\n")

                if (aliases.isNotEmpty()) {
                    append("**Aliases / Redirecionamentos recebidos:**\n")
                    for (al in aliases) {
                        val alRel = LinkResolver.computeRelativePath("$idxDir/INDEX_BY_RELATIONSHIP.md", al.markdownSourcePath)
                        append("- [").append(al.sourceTitle).append("](").append(alRel).append(") (`")
                            .append(al.sourcePath).append("`)\n")
                    }
                    append('\n')
                }
                if (outgoing.isNotEmpty()) {
                    append("**Artigos referenciados:**\n")
                    for (targetOrig in outgoing) {
                        val targetDoc = byOrigPath[targetOrig]
                        if (targetDoc != null) {
                            val tRel = LinkResolver.computeRelativePath("$idxDir/INDEX_BY_RELATIONSHIP.md", targetDoc.markdownPath)
                            append("- [").append(targetDoc.title).append("](").append(tRel).append(")\n")
                        } else {
                            append("- `").append(targetOrig).append("`\n")
                        }
                    }
                    append('\n')
                }
            }
        }
    }
}
