package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversionTaskDao {
    @Query("SELECT * FROM conversion_tasks ORDER BY updatedAt DESC")
    fun observeAllTasks(): Flow<List<ConversionTaskEntity>>

    @Query("SELECT * FROM conversion_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun getTaskById(taskId: String): ConversionTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: ConversionTaskEntity)

    @Query("DELETE FROM conversion_tasks WHERE taskId = :taskId")
    suspend fun deleteTaskById(taskId: String)

    @Query("SELECT * FROM task_checkpoints WHERE taskId = :taskId")
    suspend fun getCheckpointsForTask(taskId: String): List<TaskCheckpointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoints(items: List<TaskCheckpointEntity>)

    @Query("DELETE FROM task_checkpoints WHERE taskId = :taskId")
    suspend fun deleteCheckpointsForTask(taskId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRedirects(items: List<RedirectRecordEntity>)

    @Query("SELECT * FROM redirect_records WHERE taskId = :taskId")
    suspend fun getRedirectsForTask(taskId: String): List<RedirectRecordEntity>

    @Query("SELECT * FROM redirect_records WHERE taskId = :taskId AND (sourcePath = :path OR markdownSourcePath = :path OR sourceTitle = :path) LIMIT 1")
    suspend fun findRedirect(taskId: String, path: String): RedirectRecordEntity?

    @Query("DELETE FROM redirect_records WHERE taskId = :taskId")
    suspend fun deleteRedirectsForTask(taskId: String)
}

@Dao
interface SearchIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocuments(docs: List<IndexedDocumentEntity>): List<Long>

    @Query("DELETE FROM indexed_documents WHERE taskId = :taskId")
    suspend fun deleteDocumentsForTask(taskId: String)

    @Query("SELECT * FROM indexed_documents WHERE taskId = :taskId ORDER BY title COLLATE NOCASE ASC LIMIT :limit")
    suspend fun listDocumentsForTask(taskId: String, limit: Int = 200): List<IndexedDocumentEntity>

    @Query("SELECT * FROM indexed_documents ORDER BY title COLLATE NOCASE ASC LIMIT :limit")
    suspend fun listAllDocuments(limit: Int = 200): List<IndexedDocumentEntity>

    @Query(
        """
        SELECT * FROM indexed_documents
        WHERE (:taskId IS NULL OR taskId = :taskId)
          AND (
            normalizedTitle LIKE '%' || :normQuery || '%' ESCAPE '\'
            OR aliasesText LIKE '%' || :normQuery || '%' ESCAPE '\'
            OR categoriesText LIKE '%' || :normQuery || '%' ESCAPE '\'
            OR originalPath LIKE '%' || :normQuery || '%' ESCAPE '\'
            OR normalizedContent LIKE '%' || :normQuery || '%' ESCAPE '\'
            OR metadataText LIKE '%' || :normQuery || '%' ESCAPE '\'
          )
        ORDER BY
          CASE WHEN normalizedTitle LIKE :normQuery || '%' ESCAPE '\' THEN 0
               WHEN normalizedTitle LIKE '%' || :normQuery || '%' ESCAPE '\' THEN 1
               ELSE 2 END,
          title COLLATE NOCASE ASC
        LIMIT :limit
        """
    )
    suspend fun fallbackSearch(taskId: String?, normQuery: String, limit: Int = 100): List<IndexedDocumentEntity>

    @Query("SELECT DISTINCT categoriesText FROM indexed_documents WHERE (:taskId IS NULL OR taskId = :taskId) AND categoriesText != ''")
    suspend fun getCategories(taskId: String?): List<String>

    @Query("SELECT * FROM indexed_documents WHERE docId IN (:ids)")
    suspend fun getDocumentsByIds(ids: List<Long>): List<IndexedDocumentEntity>
}
