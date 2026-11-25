package com.example.model

import kotlinx.serialization.Serializable

@Serializable
enum class MarkdownDialect {
    COMMONMARK,
    GFM
}

@Serializable
enum class ExportMode {
    ORGANIZED_LIBRARY,    // Modo A — Biblioteca Markdown organizada
    SINGLE_FILE,          // Modo B — Arquivo Markdown único
    SPLIT_BY_CATEGORY,    // Modo C — Markdown dividido por categorias
    OPTIMIZED_MINIMAL,    // Modo D — Markdown otimizado
    CUSTOM_SELECTIVE      // Modo E — Conversão personalizada
}

@Serializable
enum class MediaMarkerMode {
    BLOCKQUOTE_WITH_METADATA, // > [Imagem não exportada: ALT] + Legenda
    REMOVE_COMPLETELY         // Remove marcadores de mídia completamente
}

@Serializable
enum class ArchivePackageFormat {
    FOLDER_ONLY,
    ZIP,
    ZIP64
}

@Serializable
data class ConversionConfig(
    // Formato e Modo
    val dialect: MarkdownDialect = MarkdownDialect.GFM,
    val exportMode: ExportMode = ExportMode.ORGANIZED_LIBRARY,
    val customLibraryName: String = "",

    // Conteúdo
    val convertHtml: Boolean = true,
    val convertXhtml: Boolean = true,
    val convertPlainText: Boolean = true,
    val convertXmlText: Boolean = true,
    val extractPdfText: Boolean = true,
    val preserveHeadings: Boolean = true,
    val preserveSubheadings: Boolean = true,
    val preserveFootnotes: Boolean = true,
    val preserveTables: Boolean = true,
    val preserveFormulas: Boolean = true,
    val preserveCodeBlocks: Boolean = true,
    val preserveReferences: Boolean = true,
    val preserveLists: Boolean = true,
    val preserveBlockquotes: Boolean = true,

    // Tratamento de Mídias (Regra absoluta: binários sempre excluídos)
    val excludeAllMedia: Boolean = true,
    val preserveAltText: Boolean = true,
    val preserveCaptions: Boolean = true,
    val preserveTextDescriptions: Boolean = true,
    val preserveTranscripts: Boolean = true,
    val mediaMarkerMode: MediaMarkerMode = MediaMarkerMode.BLOCKQUOTE_WITH_METADATA,
    val logExcludedMediaInReport: Boolean = true,

    // Organização
    val preserveOriginalHierarchy: Boolean = true,
    val preserveOriginalPaths: Boolean = true,
    val preserveOriginalNamesInMetadata: Boolean = true,
    val organizeByCategories: Boolean = true,
    val preserveOriginalOrder: Boolean = true,

    // Índices
    val generateGeneralIndex: Boolean = true,
    val generateAlphabeticalIndex: Boolean = true,
    val generateCategoryIndex: Boolean = true,
    val generatePathIndex: Boolean = true,
    val generateRelationshipIndex: Boolean = true,
    val generateHomePage: Boolean = true,

    // Redirecionamentos
    val preserveAllAliases: Boolean = true,
    val generateIndividualRedirectFiles: Boolean = true,
    val resolveRedirectChains: Boolean = true,
    val preserveImmediateTarget: Boolean = true,
    val recordFinalTarget: Boolean = true,
    val detectRedirectCycles: Boolean = true,
    val detectInvalidTargets: Boolean = true,
    val generateRedirectsReport: Boolean = true,

    // Links
    val convertInternalLinks: Boolean = true,
    val preserveExternalLinks: Boolean = true,
    val preserveAnchors: Boolean = true,
    val stripMediaLinkTargetsKeepText: Boolean = true,
    val identifyBrokenLinks: Boolean = true,

    // Exportação
    val packageFormat: ArchivePackageFormat = ArchivePackageFormat.FOLDER_ONLY,
    val customDestinationTreeUri: String? = null,
    val customArticlesSubdir: String = "articles",
    val customRedirectsSubdir: String = "redirects",
    val customIndexesSubdir: String = "indexes",

    // Desempenho
    val parallelismWorkers: Int = 2,
    val memoryLimitMb: Int = 64,
    val ioBufferSizeKb: Int = 64,
    val writeBatchSize: Int = 25,
    val progressUpdateIntervalMs: Long = 250L,
    val autoResumeEnabled: Boolean = true,
    val runIntegrityAudit: Boolean = true,

    // Modo E (Seleção Personalizada)
    val selectedNamespaces: Set<String> = emptySet(),
    val selectedPathPrefixes: Set<String> = emptySet(),
    val maxArticlesLimit: Int? = null
)
