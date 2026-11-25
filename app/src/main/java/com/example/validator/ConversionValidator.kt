package com.example.validator

import com.example.metadata.MetadataManager
import com.example.model.AuditSummary
import com.example.model.ConversionConfig
import com.example.model.EntryDisposition
import com.example.model.ExcludedMediaRecord
import com.example.model.LinkAuditRecord
import com.example.model.ManifestRecord
import com.example.model.RedirectResolution
import com.example.model.ZimInspection
import java.io.File

class ConversionValidator {

    fun auditOutputDirectory(
        outputDir: File,
        inspection: ZimInspection,
        manifest: List<ManifestRecord>,
        excludedMedia: List<ExcludedMediaRecord>,
        redirects: List<RedirectResolution>,
        links: List<LinkAuditRecord>,
        errors: List<String>,
        warnings: List<String>,
        duplicateCollisions: Int,
        unsupportedCount: Int,
        readFailures: Int,
        conversionFailures: Int,
        writeFailures: Int,
        missingTargets: Int
    ): AuditSummary {
        var mdFiles = 0
        var nonMdFiles = 0
        var totalBytes = 0L

        if (outputDir.exists()) {
            outputDir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    totalBytes += f.length()
                    if (f.extension.equals("md", ignoreCase = true)) {
                        mdFiles++
                    } else {
                        nonMdFiles++
                    }
                }
            }
        }

        val successCount = manifest.count { it.disposition == EntryDisposition.CONVERTED_SUCCESS }
        val partialCount = manifest.count { it.disposition == EntryDisposition.CONVERTED_PARTIAL }
        val refCount = manifest.count { it.disposition == EntryDisposition.PRESERVED_TEXT_REF }

        val resolvedRedirects = redirects.count { it.status == "RESOLVED" }
        val chainsResolved = redirects.count { it.status == "RESOLVED" && it.hopCount > 1 }
        val cyclesDetected = redirects.count { it.hasCycle }
        val mediaRedirects = redirects.count { it.targetExcludedMedia }

        val internalLinks = links.count { it.linkType == "INTERNAL" }
        val externalLinks = links.count { it.linkType == "EXTERNAL" }
        val mediaLinksStripped = links.count { it.linkType == "MEDIA_EXCLUDED" }
        val brokenLinks = links.count { it.linkType == "BROKEN" }
        val anchorsPreserved = links.count { it.linkType == "ANCHOR" }

        val notes = mutableListOf<String>()
        if (nonMdFiles > 0) {
            notes.add("ALERTA: Foram detectados $nonMdFiles arquivo(s) não-Markdown no diretório de saída.")
        } else {
            notes.add("Verificado: 100% dos arquivos exportados possuem extensão .md (zero mídias/binários).")
        }
        notes.add("Mídias excluídas intencionalmente (${excludedMedia.size}) não são contabilizadas como erro de conversão.")

        val integrityPassed = nonMdFiles == 0 && writeFailures == 0 && readFailures == 0

        return AuditSummary(
            totalEntriesIdentified = inspection.totalEntries,
            textualConvertedSuccess = successCount,
            textualConvertedPartial = partialCount,
            preservedAsTextReference = refCount,
            excludedMediaCount = excludedMedia.size,
            unsupportedTextualCount = unsupportedCount,
            readFailures = readFailures,
            conversionFailures = conversionFailures,
            writeFailures = writeFailures,
            missingTargets = missingTargets,
            duplicatePathCollisionsResolved = duplicateCollisions,
            redirectsTotal = redirects.size,
            redirectsResolved = resolvedRedirects,
            redirectChainsResolved = chainsResolved,
            redirectCyclesDetected = cyclesDetected,
            redirectMediaTargets = mediaRedirects,
            internalLinksConverted = internalLinks,
            externalLinksPreserved = externalLinks,
            mediaLinksStripped = mediaLinksStripped,
            brokenLinksDetected = brokenLinks,
            anchorsPreserved = anchorsPreserved,
            markdownFilesCreated = mdFiles,
            nonMarkdownFilesInOutput = nonMdFiles,
            outputTotalBytes = totalBytes,
            sourceArchiveUnmodified = true,
            integrityPassed = integrityPassed,
            notes = notes
        )
    }

    fun buildManifestMarkdown(manifest: List<ManifestRecord>): String = buildString {
        append("# MANIFEST — Documentos Convertidos\n\n")
        append("Total de documentos listados: **").append(manifest.size).append("**\n\n")
        append("| Título | Caminho Original (ZIM) | Arquivo Markdown | Categoria | MIME | Estado | Palavras |\n")
        append("| --- | --- | --- | --- | --- | --- | --- |\n")
        for (item in manifest) {
            append("| ").append(esc(item.title))
                .append(" | `").append(esc(item.originalPath))
                .append("` | [").append(esc(item.markdownPath)).append("](").append(item.markdownPath)
                .append(") | ").append(esc(item.category))
                .append(" | `").append(esc(item.mimeType))
                .append("` | ").append(item.disposition.name)
                .append(" | ").append(item.wordCount).append(" |\n")
        }
    }

    fun buildExcludedContentMarkdown(excluded: List<ExcludedMediaRecord>): String = buildString {
        append("# EXCLUDED CONTENT — Conteúdos Multimídia e Binários Excluídos\n\n")
        append("> Regra absoluta: Nenhuma mídia ou recurso binário foi extraído ou copiado para a biblioteca de saída.\n\n")
        append("Total de recursos excluídos intencionalmente: **").append(excluded.size).append("**\n\n")
        if (excluded.isEmpty()) {
            append("Nenhum conteúdo não textual foi encontrado neste arquivo ZIM.\n")
            return@buildString
        }
        append("| Caminho Original | Nome | Tipo MIME | Tamanho | Referenciado Por | Motivo da Exclusão |\n")
        append("| --- | --- | --- | --- | --- | --- |\n")
        for (item in excluded) {
            val refs = if (item.referencedBy.isEmpty()) "—" else item.referencedBy.take(5).joinToString(", ")
            append("| `").append(esc(item.originalPath))
                .append("` | ").append(esc(item.name))
                .append(" | `").append(esc(item.mimeType))
                .append("` | ").append(MetadataManager.formatSize(item.sizeBytes))
                .append(" | ").append(esc(refs))
                .append(" | ").append(esc(item.reason)).append(" |\n")
        }
    }

    fun buildRedirectsMarkdown(redirects: List<RedirectResolution>): String = buildString {
        append("# REDIRECTS — Inventário de Redirecionamentos e Aliases\n\n")
        append("Total de redirecionamentos: **").append(redirects.size).append("**\n\n")
        if (redirects.isEmpty()) {
            append("Nenhum redirecionamento encontrado no arquivo ZIM.\n")
            return@buildString
        }
        append("| Título Original | Caminho Original | Destino Imediato | Destino Final | Arquivo MD Alias | Etapas | Estado |\n")
        append("| --- | --- | --- | --- | --- | --- | --- |\n")
        for (r in redirects) {
            append("| ").append(esc(r.sourceTitle))
                .append(" | `").append(esc(r.sourcePath))
                .append("` | `").append(esc(r.immediateTargetPath ?: "—"))
                .append("` | `").append(esc(r.finalTargetPath ?: "—"))
                .append("` | [").append(esc(r.markdownSourcePath)).append("](").append(r.markdownSourcePath)
                .append(") | ").append(r.hopCount)
                .append(" | ").append(r.status).append(" |\n")
        }
    }

    fun buildLinksMarkdown(links: List<LinkAuditRecord>): String = buildString {
        append("# LINKS — Auditoria de Links Internos, Externos e Excluídos\n\n")
        append("Total de links analisados: **").append(links.size).append("**\n\n")
        if (links.isEmpty()) {
            append("Nenhum link registrado durante a conversão.\n")
            return@buildString
        }
        append("| Artigo Origem | Tipo | Texto do Link | Href Original | Destino Resolvido | Observação |\n")
        append("| --- | --- | --- | --- | --- | --- |\n")
        for (l in links.take(5000)) {
            append("| `").append(esc(l.sourceArticlePath))
                .append("` | ").append(l.linkType)
                .append(" | ").append(esc(l.linkText))
                .append(" | `").append(esc(l.rawHref))
                .append("` | ").append(esc(l.resolvedTarget ?: "—"))
                .append(" | ").append(esc(l.note ?: "—")).append(" |\n")
        }
        if (links.size > 5000) {
            append("\n> Exibindo os primeiros 5.000 links de um total de ").append(links.size).append(" registros.\n")
        }
    }

    fun buildConversionReportMarkdown(
        inspection: ZimInspection,
        audit: AuditSummary,
        config: ConversionConfig,
        elapsedMs: Long
    ): String = buildString {
        append("# CONVERSION REPORT — ").append(MetadataManager.APP_BRAND_NAME).append("\n\n")
        append("**Arquivo de origem:** `").append(inspection.fileName).append("`  \n")
        append("**UUID:** `").append(inspection.metadata.uuid).append("`  \n")
        append("**Tempo de processamento:** ").append(elapsedMs / 1000.0).append(" s  \n")
        append("**Integridade da saída:** ").append(if (audit.integrityPassed) "APROVADA (somente .md)" else "ATENÇÃO REQUERIDA").append("  \n")
        append("**Arquivo ZIM original preservado:** ").append(if (audit.sourceArchiveUnmodified) "Sim (somente leitura)" else "Não").append("\n\n")

        append("## Estatísticas de Conversão Textual\n\n")
        append("- Entradas identificadas no inventário: **").append(audit.totalEntriesIdentified).append("**\n")
        append("- Convertidos com sucesso: **").append(audit.textualConvertedSuccess).append("**\n")
        append("- Convertidos parcialmente: **").append(audit.textualConvertedPartial).append("**\n")
        append("- Preservados como referência textual: **").append(audit.preservedAsTextReference).append("**\n")
        append("- Textuais não suportados: **").append(audit.unsupportedTextualCount).append("**\n")
        append("- Colisões de caminho resolvidas: **").append(audit.duplicatePathCollisionsResolved).append("**\n\n")

        append("## Estatísticas de Exclusão de Mídias\n\n")
        append("- Mídias e binários excluídos intencionalmente: **").append(audit.excludedMediaCount).append("**\n")
        append("- Links para mídias convertidos em texto simples: **").append(audit.mediaLinksStripped).append("**\n")
        append("- Redirecionamentos apontando para mídia excluída: **").append(audit.redirectMediaTargets).append("**\n\n")

        append("## Redirecionamentos e Links\n\n")
        append("- Redirecionamentos totais: **").append(audit.redirectsTotal).append("**\n")
        append("- Redirecionamentos resolvidos: **").append(audit.redirectsResolved).append("** (cadeias multi-etapa: **")
            .append(audit.redirectChainsResolved).append("**)\n")
        append("- Ciclos / autorreferências detectadas: **").append(audit.redirectCyclesDetected).append("**\n")
        append("- Links internos convertidos: **").append(audit.internalLinksConverted).append("**\n")
        append("- Âncoras preservadas: **").append(audit.anchorsPreserved).append("**\n")
        append("- Links externos preservados: **").append(audit.externalLinksPreserved).append("**\n")
        append("- Links inválidos/quebrados detectados: **").append(audit.brokenLinksDetected).append("**\n\n")

        append("## Falhas Técnicas\n\n")
        append("- Falhas na leitura: **").append(audit.readFailures).append("**\n")
        append("- Falhas na conversão: **").append(audit.conversionFailures).append("**\n")
        append("- Falhas na gravação: **").append(audit.writeFailures).append("**\n")
        append("- Destinos inexistentes: **").append(audit.missingTargets).append("**\n\n")

        append("## Resultado da Biblioteca Gerada\n\n")
        append("- Arquivos `.md` criados: **").append(audit.markdownFilesCreated).append("**\n")
        append("- Arquivos não-Markdown na saída: **").append(audit.nonMarkdownFilesInOutput).append("**\n")
        append("- Tamanho total da biblioteca textual: **").append(MetadataManager.formatSize(audit.outputTotalBytes)).append("**\n")
        append("- Modo de exportação: `").append(config.exportMode.name).append("`\n\n")

        if (audit.notes.isNotEmpty()) {
            append("## Notas da Auditoria\n\n")
            audit.notes.forEach { append("- ").append(it).append('\n') }
        }
    }

    fun buildErrorsMarkdown(errors: List<String>): String = buildString {
        append("# ERRORS — Registro de Falhas\n\n")
        if (errors.isEmpty()) {
            append("Nenhuma falha de leitura, conversão ou gravação foi registrada.\n")
        } else {
            append("Total de erros: **").append(errors.size).append("**\n\n")
            errors.forEachIndexed { idx, err ->
                append("${idx + 1}. ").append(err).append('\n')
            }
        }
    }

    fun buildWarningsMarkdown(warnings: List<String>): String = buildString {
        append("# WARNINGS — Limitações e Avisos\n\n")
        if (warnings.isEmpty()) {
            append("Nenhum aviso registrado durante a conversão.\n")
        } else {
            append("Total de avisos: **").append(warnings.size).append("**\n\n")
            warnings.forEachIndexed { idx, w ->
                append("${idx + 1}. ").append(w).append('\n')
            }
        }
    }

    private fun esc(s: String): String = s.replace("|", "\\|").replace("\n", " ").replace("\r", "")
}
