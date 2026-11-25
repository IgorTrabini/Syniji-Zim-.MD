package com.example.search

import com.example.data.AppDatabase
import com.example.data.IndexedDocumentEntity
import com.example.markdown.SlugUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

data class SearchHit(
    val taskId: String,
    val title: String,
    val markdownRelPath: String,
    val originalPath: String,
    val categories: List<String>,
    val aliases: List<String>,
    val snippet: String
)

enum class SearchSortMode {
    RELEVANCE,
    ALPHABETICAL
}

class SearchRepository(private val db: AppDatabase) {

    private val dao = db.searchIndexDao()

    suspend fun indexBatch(docs: List<IndexedDocumentEntity>) = withContext(Dispatchers.IO) {
        if (docs.isEmpty()) return@withContext
        val insertedIds = dao.insertDocuments(docs)
        if (db.isFts5Available) {
            try {
                val sqlDb = db.openHelper.writableDatabase
                sqlDb.beginTransaction()
                try {
                    val stmt = sqlDb.compileStatement(
                        "INSERT INTO doc_fts5(rowid, taskId, title, aliases, categories, originalPath, content, metadata) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                    )
                    for (i in docs.indices) {
                        val rowId = insertedIds.getOrNull(i) ?: continue
                        val d = docs[i]
                        stmt.clearBindings()
                        stmt.bindLong(1, rowId)
                        stmt.bindString(2, d.taskId)
                        stmt.bindString(3, d.title)
                        stmt.bindString(4, d.aliasesText)
                        stmt.bindString(5, d.categoriesText)
                        stmt.bindString(6, d.originalPath)
                        stmt.bindString(7, d.contentText)
                        stmt.bindString(8, d.metadataText)
                        stmt.executeInsert()
                    }
                    sqlDb.setTransactionSuccessful()
                } finally {
                    sqlDb.endTransaction()
                }
            } catch (_: Throwable) {
            }
        }
    }

    suspend fun clearTaskIndex(taskId: String) = withContext(Dispatchers.IO) {
        dao.deleteDocumentsForTask(taskId)
        if (db.isFts5Available) {
            try {
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM doc_fts5 WHERE taskId = ?",
                    arrayOf(taskId)
                )
            } catch (_: Throwable) {
            }
        }
    }

    suspend fun getCategories(taskId: String?): List<String> = withContext(Dispatchers.IO) {
        dao.getCategories(taskId)
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    suspend fun search(
        query: String,
        taskId: String? = null,
        categoryFilter: String? = null,
        sortMode: SearchSortMode = SearchSortMode.RELEVANCE,
        limit: Int = 100
    ): List<SearchHit> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        val docs: List<IndexedDocumentEntity> = if (trimmed.isEmpty()) {
            if (taskId != null) dao.listDocumentsForTask(taskId, limit) else dao.listAllDocuments(limit)
        } else {
            val ftsIds = if (db.isFts5Available) queryFts5Ids(trimmed, taskId, limit) else emptyList()
            if (ftsIds.isNotEmpty()) {
                val byId = dao.getDocumentsByIds(ftsIds).associateBy { it.docId }
                ftsIds.mapNotNull { byId[it] }
            } else {
                val norm = escapeLike(SlugUtils.normalizeForSearch(trimmed))
                dao.fallbackSearch(taskId, norm, limit)
            }
        }

        val filtered = if (categoryFilter.isNullOrBlank()) {
            docs
        } else {
            docs.filter { it.categoriesText.contains(categoryFilter, ignoreCase = true) }
        }

        val hits = filtered.map { d ->
            SearchHit(
                taskId = d.taskId,
                title = d.title,
                markdownRelPath = d.markdownRelPath,
                originalPath = d.originalPath,
                categories = d.categoriesText.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                aliases = d.aliasesText.split('|').map { it.trim() }.filter { it.isNotEmpty() },
                snippet = extractSnippet(d.contentText, trimmed)
            )
        }

        if (sortMode == SearchSortMode.ALPHABETICAL) {
            hits.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        } else {
            hits
        }
    }

    /**
     * Rebuilds the local search index by scanning exported article/redirect/category .md files directly on disk,
     * excluding audit reports and generated navigation index files.
     */
    suspend fun rebuildIndexFromMarkdownDirectory(
        taskId: String,
        libraryDir: File,
        indexesSubdir: String = "indexes"
    ): Int = withContext(Dispatchers.IO) {
        if (!libraryDir.exists() || !libraryDir.isDirectory) return@withContext 0
        clearTaskIndex(taskId)
        val baseCanonical = libraryDir.canonicalFile
        val baseLen = baseCanonical.path.length + 1
        val excludedRootReports = setOf(
            "README.md", "HOME.md", "INDEX.md", "METADATA.md", "MANIFEST.md",
            "EXCLUDED_CONTENT.md", "REDIRECTS.md", "LINKS.md", "WARNINGS.md",
            "ERRORS.md", "CONVERSION_REPORT.md"
        )
        val cleanIdxPrefix = "${SlugUtils.sanitizeFileSegment(indexesSubdir, "indexes")}/"
        val batch = mutableListOf<IndexedDocumentEntity>()
        var total = 0

        baseCanonical.walkTopDown().forEach { file ->
            if (file.isFile && file.extension.equals("md", ignoreCase = true)) {
                val rel = file.canonicalPath.substring(baseLen).replace('\\', '/')
                val isReportOrIndex = rel in excludedRootReports ||
                    rel.startsWith(cleanIdxPrefix, ignoreCase = true) ||
                    rel.startsWith("indexes/", ignoreCase = true)
                if (!isReportOrIndex) {
                    val raw = file.readText(StandardCharsets.UTF_8)
                    val meta = parseFrontMatter(raw)
                    val body = stripFrontMatter(raw)
                    val title = meta["title"] ?: file.nameWithoutExtension
                    val categories = meta["categories"].orEmpty()
                    val origPath = meta["source_path"] ?: meta["original_path"] ?: rel
                    val aliasInfo = if (meta["type"] == "redirect") title else ""
                    val snippetContent = body.take(16000)

                    batch.add(
                        IndexedDocumentEntity(
                            taskId = taskId,
                            title = title,
                            normalizedTitle = SlugUtils.normalizeForSearch(title),
                            aliasesText = aliasInfo,
                            categoriesText = categories,
                            originalPath = origPath,
                            markdownRelPath = rel,
                            contentText = snippetContent,
                            normalizedContent = SlugUtils.normalizeForSearch(snippetContent),
                            metadataText = meta.entries.joinToString(" ") { "${it.key}:${it.value}" }
                        )
                    )
                    if (batch.size >= 40) {
                        indexBatch(batch.toList())
                        total += batch.size
                        batch.clear()
                    }
                }
            }
        }
        if (batch.isNotEmpty()) {
            indexBatch(batch.toList())
            total += batch.size
        }
        total
    }

    private fun queryFts5Ids(rawQuery: String, taskId: String?, limit: Int): List<Long> {
        return try {
            val tokens = rawQuery.split(Regex("\\s+"))
                .map { it.replace("\"", "").trim() }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return emptyList()
            val matchExpr = tokens.joinToString(" ") { "\"$it\"*" }

            val sql = if (taskId != null) {
                "SELECT rowid FROM doc_fts5 WHERE doc_fts5 MATCH ? AND taskId = ? ORDER BY bm25(doc_fts5) LIMIT ?"
            } else {
                "SELECT rowid FROM doc_fts5 WHERE doc_fts5 MATCH ? ORDER BY bm25(doc_fts5) LIMIT ?"
            }
            val args: Array<Any> = if (taskId != null) {
                arrayOf(matchExpr, taskId, limit)
            } else {
                arrayOf(matchExpr, limit)
            }
            val ids = mutableListOf<Long>()
            db.openHelper.readableDatabase.query(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    ids.add(cursor.getLong(0))
                }
            }
            ids
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun extractSnippet(content: String, query: String): String {
        val clean = content.replace(Regex("[#*>|`_\\[\\]]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return ""
        if (query.isBlank()) return clean.take(140)
        val normClean = SlugUtils.normalizeForSearch(clean)
        val normQ = SlugUtils.normalizeForSearch(query)
        val idx = normClean.indexOf(normQ)
        if (idx < 0) return clean.take(140)
        val start = (idx - 45).coerceAtLeast(0)
        val end = (idx + query.length + 85).coerceAtMost(clean.length)
        return buildString {
            if (start > 0) append("…")
            append(clean.substring(start, end).trim())
            if (end < clean.length) append("…")
        }
    }

    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun parseFrontMatter(md: String): Map<String, String> {
        if (!md.startsWith("---\n")) return emptyMap()
        val endIdx = md.indexOf("\n---\n", startIndex = 4)
        if (endIdx < 0) return emptyMap()
        val block = md.substring(4, endIdx)
        val map = LinkedHashMap<String, String>()
        for (line in block.lines()) {
            val colon = line.indexOf(':')
            if (colon > 0) {
                val k = line.substring(0, colon).trim()
                val v = line.substring(colon + 1).trim().removeSurrounding("\"")
                map[k] = v
            }
        }
        return map
    }

    private fun stripFrontMatter(md: String): String {
        if (!md.startsWith("---\n")) return md
        val endIdx = md.indexOf("\n---\n", startIndex = 4)
        return if (endIdx >= 0) md.substring(endIdx + 5).trimStart() else md
    }
}
