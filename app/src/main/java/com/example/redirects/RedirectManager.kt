package com.example.redirects

import com.example.filter.ContentClassifier
import com.example.links.LinkResolver
import com.example.markdown.SlugUtils
import com.example.markdown.YamlFrontMatter
import com.example.model.ConversionConfig
import com.example.model.RedirectResolution
import com.example.zim.ZimArchiveSource
import com.example.zim.ZimEntryHandle

class RedirectManager(private val config: ConversionConfig) {

    data class RawRedirectNode(
        val sourcePath: String,
        val sourceTitle: String,
        val immediateTargetPath: String?
    )

    fun resolveAll(
        archive: ZimArchiveSource,
        rawRedirects: List<RawRedirectNode>,
        textualTargetMap: Map<String, Pair<String, String>>, // originalPath -> (title, markdownPath)
        excludedTargetSet: Set<String>
    ): List<RedirectResolution> {
        val redirectIndex = rawRedirects.associateBy { it.sourcePath }
        val usedRedirectPaths = HashSet<String>()
        val results = ArrayList<RedirectResolution>(rawRedirects.size)

        for (node in rawRedirects) {
            val mdRelPath = allocateRedirectMarkdownPath(node.sourcePath, usedRedirectPaths)

            if (node.immediateTargetPath == null) {
                if (config.detectInvalidTargets) {
                    results.add(
                        RedirectResolution(
                            sourceTitle = node.sourceTitle,
                            sourcePath = node.sourcePath,
                            immediateTargetPath = null,
                            finalTargetPath = null,
                            finalTargetTitle = null,
                            markdownSourcePath = mdRelPath,
                            markdownTargetPath = null,
                            hopCount = 0,
                            status = "MISSING_TARGET",
                            warningMessage = "Destino imediato não encontrado no arquivo ZIM."
                        )
                    )
                }
                continue
            }

            if (node.immediateTargetPath == node.sourcePath && config.detectRedirectCycles) {
                results.add(
                    RedirectResolution(
                        sourceTitle = node.sourceTitle,
                        sourcePath = node.sourcePath,
                        immediateTargetPath = node.immediateTargetPath,
                        finalTargetPath = node.sourcePath,
                        finalTargetTitle = node.sourceTitle,
                        markdownSourcePath = mdRelPath,
                        markdownTargetPath = null,
                        hopCount = 1,
                        status = "SELF_REFERENCE",
                        hasCycle = true,
                        isSelfReference = true,
                        warningMessage = "Autorreferência detectada: redirecionamento aponta para si mesmo."
                    )
                )
                continue
            }

            var currentPath: String? = node.immediateTargetPath
            val visited = linkedSetOf(node.sourcePath)
            var hops = 1
            var cycleDetected = false

            if (config.resolveRedirectChains) {
                while (currentPath != null && redirectIndex.containsKey(currentPath)) {
                    if (!visited.add(currentPath)) {
                        cycleDetected = config.detectRedirectCycles
                        break
                    }
                    if (hops >= 64) {
                        cycleDetected = config.detectRedirectCycles
                        break
                    }
                    currentPath = redirectIndex[currentPath]?.immediateTargetPath
                    if (currentPath != null) hops++
                }
            }

            // Fallback lookup directly via libzim/archive if target wasn't in our rawRedirects list
            if (!cycleDetected && currentPath != null && !textualTargetMap.containsKey(currentPath) && !excludedTargetSet.contains(currentPath)) {
                val entry: ZimEntryHandle? = archive.getEntryByPath(currentPath)
                if (entry != null && entry.isRedirect) {
                    val item = entry.getItem(followRedirects = true)
                    if (item != null) {
                        currentPath = item.path
                    }
                }
            }

            val finalPath = currentPath
            val textualInfo = finalPath?.let { textualTargetMap[it] }
            val isExcludedMedia = finalPath != null && (
                excludedTargetSet.contains(finalPath) ||
                    ContentClassifier.isMediaOrExcludedCategory(
                        ContentClassifier.classifyByMetadata(null, finalPath, false)
                    )
                )

            val resolution = when {
                cycleDetected -> RedirectResolution(
                    sourceTitle = node.sourceTitle,
                    sourcePath = node.sourcePath,
                    immediateTargetPath = node.immediateTargetPath,
                    finalTargetPath = finalPath,
                    finalTargetTitle = null,
                    markdownSourcePath = mdRelPath,
                    markdownTargetPath = null,
                    hopCount = hops,
                    status = "CYCLE_DETECTED",
                    hasCycle = true,
                    warningMessage = "Ciclo de redirecionamento detectado após $hops etapas."
                )
                isExcludedMedia -> RedirectResolution(
                    sourceTitle = node.sourceTitle,
                    sourcePath = node.sourcePath,
                    immediateTargetPath = node.immediateTargetPath,
                    finalTargetPath = finalPath,
                    finalTargetTitle = null,
                    markdownSourcePath = mdRelPath,
                    markdownTargetPath = null,
                    hopCount = hops,
                    status = "EXCLUDED_MEDIA_TARGET",
                    targetExcludedMedia = true,
                    warningMessage = "O redirecionamento aponta para um recurso não textual ($finalPath) que foi excluído da biblioteca final."
                )
                textualInfo != null -> RedirectResolution(
                    sourceTitle = node.sourceTitle,
                    sourcePath = node.sourcePath,
                    immediateTargetPath = node.immediateTargetPath,
                    finalTargetPath = finalPath,
                    finalTargetTitle = textualInfo.first,
                    markdownSourcePath = mdRelPath,
                    markdownTargetPath = textualInfo.second,
                    hopCount = hops,
                    status = "RESOLVED"
                )
                !config.detectInvalidTargets -> RedirectResolution(
                    sourceTitle = node.sourceTitle,
                    sourcePath = node.sourcePath,
                    immediateTargetPath = node.immediateTargetPath,
                    finalTargetPath = finalPath,
                    finalTargetTitle = null,
                    markdownSourcePath = mdRelPath,
                    markdownTargetPath = null,
                    hopCount = hops,
                    status = "UNRESOLVED"
                )
                else -> RedirectResolution(
                    sourceTitle = node.sourceTitle,
                    sourcePath = node.sourcePath,
                    immediateTargetPath = node.immediateTargetPath,
                    finalTargetPath = finalPath,
                    finalTargetTitle = null,
                    markdownSourcePath = mdRelPath,
                    markdownTargetPath = null,
                    hopCount = hops,
                    status = "MISSING_TARGET",
                    warningMessage = "Destino final inexistente ou não elegível: ${finalPath ?: "desconhecido"}"
                )
            }
            results.add(resolution)
        }

        return results
    }

    fun renderRedirectMarkdown(res: RedirectResolution): String {
        val relativeTarget = res.markdownTargetPath?.let {
            LinkResolver.computeRelativePath(res.markdownSourcePath, it)
        }

        val fm = YamlFrontMatter.build(
            linkedMapOf(
                "title" to res.sourceTitle,
                "type" to "redirect",
                "original_path" to res.sourcePath,
                "immediate_target" to if (config.preserveImmediateTarget) res.immediateTargetPath else null,
                "final_target" to if (config.recordFinalTarget) res.finalTargetPath else null,
                "redirect_target" to relativeTarget,
                "hops" to res.hopCount.toString(),
                "resolution_status" to res.status
            )
        )

        val body = buildString {
            append(fm)
            append("# ").append(res.sourceTitle).append("\n\n")
            when (res.status) {
                "RESOLVED" -> {
                    append("Este documento é um redirecionamento para outro artigo.\n\n")
                    if (relativeTarget != null) {
                        append("[Acessar o artigo original: ").append(res.finalTargetTitle ?: res.finalTargetPath).append("](").append(relativeTarget).append(")\n")
                    }
                }
                "EXCLUDED_MEDIA_TARGET" -> {
                    append("> **Aviso:** Este redirecionamento aponta para um recurso não textual (`")
                        .append(res.finalTargetPath ?: "mídia").append("`) que não possui representação exportada na biblioteca Markdown exclusivamente textual.\n")
                }
                "CYCLE_DETECTED", "SELF_REFERENCE" -> {
                    append("> **Aviso:** ").append(res.warningMessage ?: "Ciclo detectado no redirecionamento.").append('\n')
                }
                else -> {
                    append("> **Aviso:** ").append(res.warningMessage ?: "Destino do redirecionamento não encontrado.").append('\n')
                }
            }
        }
        return body
    }

    private fun allocateRedirectMarkdownPath(sourcePath: String, used: MutableSet<String>): String {
        val subdir = SlugUtils.sanitizeFileSegment(config.customRedirectsSubdir, "redirects")
        val stripped = SlugUtils.stripNamespacePrefix(sourcePath)
        val stem = SlugUtils.sanitizeFileSegment(SlugUtils.stripKnownTextExtension(stripped), "alias")
        var candidate = "$subdir/$stem.md"
        var counter = 2
        while (!used.add(candidate.lowercase())) {
            candidate = "$subdir/${stem}_$counter.md"
            counter++
        }
        return candidate
    }
}
