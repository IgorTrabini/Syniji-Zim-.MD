package com.example.model

import kotlinx.serialization.Serializable

enum class ContentCategory {
    HTML,
    XHTML,
    PLAIN_TEXT,
    XML_TEXT,
    PDF,
    MEDIA_IMAGE,
    MEDIA_VIDEO,
    MEDIA_AUDIO,
    WEB_ASSET_JS_CSS_FONT,
    BINARY_OTHER,
    REDIRECT
}

enum class EntryDisposition {
    CONVERTED_SUCCESS,
    CONVERTED_PARTIAL,
    PRESERVED_TEXT_REF,
    EXCLUDED_INTENTIONALLY,
    UNSUPPORTED,
    FAILED_READ,
    FAILED_CONVERSION,
    FAILED_WRITE,
    MISSING_TARGET
}

enum class ConversionTaskStatus {
    INSPECTING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Serializable
data class ZimMetadata(
    val title: String? = null,
    val description: String? = null,
    val language: String? = null,
    val languages: List<String> = emptyList(),
    val publisher: String? = null,
    val creator: String? = null,
    val date: String? = null,
    val uuid: String = "",
    val mainPagePath: String? = null,
    val sourceName: String? = null,
    val rawMetadata: Map<String, String> = emptyMap()
)

@Serializable
data class ZimInspection(
    val sourceUri: String,
    val fileName: String,
    val accessiblePath: String,
    val fileSizeBytes: Long,
    val metadata: ZimMetadata,
    val totalEntries: Int,
    val articleCount: Int,
    val redirectCount: Int,
    val textualResourceCount: Int,
    val nonTextualResourceCount: Int,
    val hasTitleIndex: Boolean,
    val hasFulltextIndex: Boolean,
    val hasChecksum: Boolean,
    val structuralIntegrityValid: Boolean,
    val hasNewNamespaceScheme: Boolean,
    val namespacesFound: List<String>,
    val sampleCategories: List<String>,
    val estimatedMarkdownBytes: Long,
    val availableStorageBytes: Long,
    val engineUsed: String
)

data class ZimEntrySummary(
    val index: Int,
    val path: String,
    val title: String,
    val namespace: String,
    val isRedirect: Boolean,
    val mimeType: String?,
    val byteSize: Long,
    val category: ContentCategory,
    val redirectTargetPath: String? = null
)

@Serializable
data class RedirectResolution(
    val sourceTitle: String,
    val sourcePath: String,
    val immediateTargetPath: String?,
    val finalTargetPath: String?,
    val finalTargetTitle: String?,
    val markdownSourcePath: String,
    val markdownTargetPath: String?,
    val hopCount: Int,
    val status: String, // RESOLVED, CYCLE_DETECTED, SELF_REFERENCE, MISSING_TARGET, EXCLUDED_MEDIA_TARGET
    val hasCycle: Boolean = false,
    val isSelfReference: Boolean = false,
    val targetExcludedMedia: Boolean = false,
    val warningMessage: String? = null
)

@Serializable
data class LinkAuditRecord(
    val sourceArticlePath: String,
    val rawHref: String,
    val linkText: String,
    val linkType: String, // INTERNAL, EXTERNAL, ANCHOR, MEDIA_EXCLUDED, BROKEN
    val resolvedTarget: String?,
    val anchorFragment: String? = null,
    val note: String? = null
)

@Serializable
data class ExcludedMediaRecord(
    val originalPath: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val referencedBy: List<String>,
    val reason: String
)

@Serializable
data class ManifestRecord(
    val title: String,
    val originalPath: String,
    val markdownPath: String,
    val category: String,
    val mimeType: String,
    val disposition: EntryDisposition,
    val wordCount: Int
)

@Serializable
data class AuditSummary(
    val totalEntriesIdentified: Int,
    val textualConvertedSuccess: Int,
    val textualConvertedPartial: Int,
    val preservedAsTextReference: Int,
    val excludedMediaCount: Int,
    val unsupportedTextualCount: Int,
    val readFailures: Int,
    val conversionFailures: Int,
    val writeFailures: Int,
    val missingTargets: Int,
    val duplicatePathCollisionsResolved: Int,
    val redirectsTotal: Int,
    val redirectsResolved: Int,
    val redirectChainsResolved: Int,
    val redirectCyclesDetected: Int,
    val redirectMediaTargets: Int,
    val internalLinksConverted: Int,
    val externalLinksPreserved: Int,
    val mediaLinksStripped: Int,
    val brokenLinksDetected: Int,
    val anchorsPreserved: Int,
    val markdownFilesCreated: Int,
    val nonMarkdownFilesInOutput: Int,
    val outputTotalBytes: Long,
    val sourceArchiveUnmodified: Boolean,
    val integrityPassed: Boolean,
    val notes: List<String> = emptyList()
)

data class ConversionEvent(
    val timestampMs: Long = System.currentTimeMillis(),
    val level: String, // INFO, WARN, ERROR
    val message: String
)

data class ConversionProgress(
    val taskId: String = "",
    val status: ConversionTaskStatus = ConversionTaskStatus.INSPECTING,
    val phaseIndex: Int = 1,
    val phaseLabel: String = "",
    val totalEntries: Int = 0,
    val processedEntries: Int = 0,
    val totalArticles: Int = 0,
    val articlesConverted: Int = 0,
    val redirectsIdentified: Int = 0,
    val redirectsResolved: Int = 0,
    val linksConverted: Int = 0,
    val linksInvalid: Int = 0,
    val mediaExcluded: Int = 0,
    val markdownFilesCreated: Int = 0,
    val outputBytesWritten: Long = 0L,
    val elapsedMs: Long = 0L,
    val articlesPerSecond: Double = 0.0,
    val bytesPerSecond: Double = 0.0,
    val estimatedRemainingMs: Long? = null,
    val currentEntryTitle: String = "",
    val currentEntryPath: String = "",
    val currentEntryMime: String = "",
    val outputDirectoryPath: String = "",
    val zipFilePath: String? = null,
    val errorMessage: String? = null,
    val auditSummary: AuditSummary? = null,
    val recentEvents: List<ConversionEvent> = emptyList()
) {
    val progressFraction: Float
        get() = if (totalEntries > 0) (processedEntries.toFloat() / totalEntries.toFloat()).coerceIn(0f, 1f) else 0f
}
