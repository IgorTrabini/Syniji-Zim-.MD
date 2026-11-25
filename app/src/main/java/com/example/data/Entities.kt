package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversion_tasks")
data class ConversionTaskEntity(
    @PrimaryKey val taskId: String,
    val sourceUri: String,
    val sourceFileName: String,
    val sourceSizeBytes: Long,
    val libraryTitle: String,
    val uuid: String,
    val configJson: String,
    val status: String, // INSPECTING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
    val totalEntries: Int,
    val processedEntries: Int,
    val articlesConverted: Int,
    val redirectsCount: Int,
    val mediaExcludedCount: Int,
    val errorCount: Int,
    val lastCompletedIndex: Int,
    val outputDirPath: String,
    val zipFilePath: String? = null,
    val auditSummaryJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "task_checkpoints",
    primaryKeys = ["taskId", "entryIndex"],
    indices = [Index(value = ["taskId", "sourcePath"])]
)
data class TaskCheckpointEntity(
    val taskId: String,
    val entryIndex: Int,
    val sourcePath: String,
    val markdownRelPath: String,
    val title: String,
    val category: String,
    val mimeType: String,
    val disposition: String,
    val wordCount: Int
)

@Entity(
    tableName = "redirect_records",
    indices = [Index(value = ["taskId", "sourcePath"]), Index(value = ["taskId", "markdownSourcePath"])]
)
data class RedirectRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val sourceTitle: String,
    val sourcePath: String,
    val immediateTargetPath: String?,
    val finalTargetPath: String?,
    val finalTargetTitle: String?,
    val markdownSourcePath: String,
    val markdownTargetPath: String?,
    val hopCount: Int,
    val status: String,
    val warningMessage: String?
)

@Entity(
    tableName = "indexed_documents",
    indices = [Index(value = ["taskId", "markdownRelPath"]), Index(value = ["taskId", "normalizedTitle"])]
)
data class IndexedDocumentEntity(
    @PrimaryKey(autoGenerate = true) val docId: Long = 0,
    val taskId: String,
    val title: String,
    val normalizedTitle: String,
    val aliasesText: String,
    val categoriesText: String,
    val originalPath: String,
    val markdownRelPath: String,
    val contentText: String,
    val normalizedContent: String,
    val metadataText: String
)
