package com.example.engine

import android.content.Context
import android.net.Uri
import com.example.data.AppDatabase
import com.example.data.ConversionTaskEntity
import com.example.data.IndexedDocumentEntity
import com.example.data.RedirectRecordEntity
import com.example.data.TaskCheckpointEntity
import com.example.export.ExportManager
import com.example.filter.ContentClassifier
import com.example.index.IndexManager
import com.example.links.LinkResolver
import com.example.links.PathIndexInfo
import com.example.markdown.SlugUtils
import com.example.metadata.MetadataManager
import com.example.model.ArchivePackageFormat
import com.example.model.AuditSummary
import com.example.model.ContentCategory
import com.example.model.ConversionConfig
import com.example.model.ConversionEvent
import com.example.model.ConversionProgress
import com.example.model.ConversionTaskStatus
import com.example.model.EntryDisposition
import com.example.model.ExcludedMediaRecord
import com.example.model.ExportMode
import com.example.model.ManifestRecord
import com.example.model.RedirectResolution
import com.example.model.ZimInspection
import com.example.parser.DiscoveredMediaRef
import com.example.parser.HtmlLinkResolver
import com.example.parser.HtmlSemanticParser
import com.example.parser.PlainTextAndXmlConverter
import com.example.redirects.RedirectManager
import com.example.search.SearchRepository
import com.example.validator.ConversionValidator
import com.example.zim.ZimArchiveSource
import com.example.zim.ZimOpener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ConversionEngine(
    private val context: Context?,
    private val db: AppDatabase,
    private val searchRepository: SearchRepository
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val taskDao = db.conversionTaskDao()
    private val pauseRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    private val _progress = MutableStateFlow(ConversionProgress())
    val progress: StateFlow<ConversionProgress> = _progress.asStateFlow()

    private val eventsBuffer = ArrayList<ConversionEvent>(120)

    fun requestPause() {
        pauseRequested.set(true)
        addEvent("WARN", "Pausa solicitada pelo usuário; concluindo lote em processamento...")
        _progress.value = _progress.value.copy(recentEvents = eventsSnapshot())
    }

    fun requestCancel() {
        cancelRequested.set(true)
        addEvent("WARN", "Cancelamento solicitado; preservando arquivos já gravados...")
        _progress.value = _progress.value.copy(recentEvents = eventsSnapshot())
    }

    suspend fun convertFromUri(
        uri: Uri,
        inspection: ZimInspection,
        config: ConversionConfig,
        outputRootDir: File,
        resumeTaskId: String? = null
    ): ConversionProgress = withContext(Dispatchers.IO) {
        val ctx = requireNotNull(context) { "Context necessário para abrir content:// URI" }
        ZimOpener.openFromUri(ctx, uri, config.memoryLimitMb).use { opened ->
            runConversion(
                archive = opened.archive,
                inspection = inspection,
                config = config,
                outputRootDir = outputRootDir,
                resumeTaskId = resumeTaskId
            )
        }
    }

    suspend fun convertFromFile(
        zimFile: File,
        inspection: ZimInspection,
        config: ConversionConfig,
        outputRootDir: File,
        resumeTaskId: String? = null
    ): ConversionProgress = withContext(Dispatchers.IO) {
        ZimOpener.openFromFile(zimFile, config.memoryLimitMb).use { opened ->
            runConversion(
                archive = opened.archive,
                inspection = inspection,
                config = config,
                outputRootDir = outputRootDir,
                resumeTaskId = resumeTaskId
            )
        }
    }

    private data class PlannedTextEntry(
        val orderIndex: Int,
        val originalPath: String,
        val title: String,
        val mimeType: String,
        val category: ContentCategory,
        val markdownRelPath: String,
        val dirCategory: String,
        var singleFileAnchorId: String? = null
    )

    private data class RawWorkItem(
        val planned: PlannedTextEntry,
        val rawBytes: ByteArray
    )

    private data class ProcessedOutcome(
        val planned: PlannedTextEntry,
        val readFailed: Boolean = false,
        val conversionFailed: Boolean = false,
        val writeFailed: Boolean = false,
        val mislabeledBinary: ExcludedMediaRecord? = null,
        val manifestRecord: ManifestRecord? = null,
        val checkpointEntity: TaskCheckpointEntity? = null,
        val searchEntity: IndexedDocumentEntity? = null,
        val writtenBytes: Long = 0L,
        val discoveredMedia: List<DiscoveredMediaRef> = emptyList(),
        val warnings: List<String> = emptyList(),
        val errorMessage: String? = null
    )

    internal suspend fun runConversion(
        archive: ZimArchiveSource,
        inspection: ZimInspection,
        config: ConversionConfig,
        outputRootDir: File,
        resumeTaskId: String?
    ): ConversionProgress {
        pauseRequested.set(false)
        cancelRequested.set(false)
        synchronized(eventsBuffer) { eventsBuffer.clear() }

        val startTime = System.currentTimeMillis()
        val taskId = resumeTaskId ?: UUID.randomUUID().toString()
        val existingTask = if (resumeTaskId != null) taskDao.getTaskById(resumeTaskId) else null
        val libraryFolderName = SlugUtils.sanitizeFileSegment(
            config.customLibraryName.ifBlank { inspection.metadata.title ?: inspection.fileName.substringBeforeLast('.') },
            "Biblioteca_Markdown"
        )
        val libraryDir = existingTask?.outputDirPath?.let { File(it) }
            ?: File(outputRootDir, "${libraryFolderName}_${taskId.take(8)}").apply { mkdirs() }
        if (!libraryDir.exists()) libraryDir.mkdirs()

        archive.configureCacheLimits(config.memoryLimitMb)

        val isResuming = existingTask != null && config.autoResumeEnabled
        val existingCheckpoints = if (isResuming) {
            taskDao.getCheckpointsForTask(taskId).associateBy { it.sourcePath }
        } else {
            emptyMap()
        }

        if (!isResuming) {
            searchRepository.clearTaskIndex(taskId)
        }

        addEvent(
            "INFO",
            if (isResuming) "Retomando conversão ($taskId)..." else "Iniciando conversão offline de ${inspection.fileName}"
        )

        val baseTask = ConversionTaskEntity(
            taskId = taskId,
            sourceUri = inspection.sourceUri,
            sourceFileName = inspection.fileName,
            sourceSizeBytes = inspection.fileSizeBytes,
            libraryTitle = inspection.metadata.title ?: inspection.fileName,
            uuid = inspection.metadata.uuid,
            configJson = json.encodeToString(config),
            status = ConversionTaskStatus.RUNNING.name,
            totalEntries = inspection.totalEntries,
            processedEntries = existingTask?.processedEntries ?: 0,
            articlesConverted = existingTask?.articlesConverted ?: 0,
            redirectsCount = inspection.redirectCount,
            mediaExcludedCount = inspection.nonTextualResourceCount,
            errorCount = existingTask?.errorCount ?: 0,
            lastCompletedIndex = existingTask?.lastCompletedIndex ?: -1,
            outputDirPath = libraryDir.absolutePath,
            createdAt = existingTask?.createdAt ?: startTime,
            updatedAt = startTime
        )
        taskDao.upsertTask(baseTask)

        var currentProgress = ConversionProgress(
            taskId = taskId,
            status = ConversionTaskStatus.RUNNING,
            phaseIndex = 1,
            phaseLabel = "Fase 1/3: Inventário e mapeamento de redirecionamentos...",
            totalEntries = inspection.totalEntries,
            outputDirectoryPath = libraryDir.absolutePath,
            recentEvents = eventsSnapshot()
        )
        _progress.value = currentProgress

        try {
            // --- PASS 1: Fast Inventory (no media blob reads) ---
            val plannedTextEntries = ArrayList<PlannedTextEntry>()
            val plannedTextByPath = HashMap<String, PlannedTextEntry>()
            val rawRedirects = ArrayList<RedirectManager.RawRedirectNode>()
            val excludedMediaMap = LinkedHashMap<String, ExcludedMediaRecord>()
            val pathCatalog = HashMap<String, PathIndexInfo>()
            val usedMdPaths = HashSet<String>()
            var duplicateCollisions = 0
            var unsupportedCount = 0

            var seqIdx = 0
            var lastInventoryNotify = 0L
            for (entry in archive.iterateEntriesEfficiently()) {
                currentCoroutineContext().ensureActive()
                if (pauseRequested.get() || cancelRequested.get()) break

                val path = entry.path
                val ns = ContentClassifier.extractNamespace(path)

                if (config.exportMode == ExportMode.CUSTOM_SELECTIVE) {
                    if (config.selectedNamespaces.isNotEmpty() && ns !in config.selectedNamespaces) {
                        seqIdx++
                        continue
                    }
                    if (config.selectedPathPrefixes.isNotEmpty() && config.selectedPathPrefixes.none { path.startsWith(it) }) {
                        seqIdx++
                        continue
                    }
                }

                if (entry.isRedirect) {
                    val immediateTarget = entry.getRedirectEntry()?.path
                    rawRedirects.add(
                        RedirectManager.RawRedirectNode(
                            sourcePath = path,
                            sourceTitle = entry.title.ifBlank { SlugUtils.stripNamespacePrefix(path) },
                            immediateTargetPath = immediateTarget
                        )
                    )
                    seqIdx++
                    continue
                }

                val item = entry.getItem(followRedirects = false)
                val mime = item?.mimeType.orEmpty()
                val size = item?.size ?: 0L
                val category = ContentClassifier.classifyByMetadata(mime, path, false, ns)

                if (ContentClassifier.isTextualCategory(category) && isCategoryEnabledByConfig(category, config)) {
                    if (config.exportMode == ExportMode.CUSTOM_SELECTIVE &&
                        config.maxArticlesLimit != null &&
                        plannedTextEntries.size >= config.maxArticlesLimit
                    ) {
                        seqIdx++
                        continue
                    }

                    val stripped = SlugUtils.stripNamespacePrefix(path)
                    val inferredDirCat = stripped.substringBefore('/', "outros").takeIf { it != stripped } ?: "outros"
                    var candidateMd = SlugUtils.toSafeRelativeMarkdownPath(
                        zimPath = path,
                        articlesSubdir = config.customArticlesSubdir,
                        preserveHierarchy = config.preserveOriginalHierarchy,
                        categoryOverride = if (config.organizeByCategories && !config.preserveOriginalHierarchy) inferredDirCat else null
                    )
                    if (!usedMdPaths.add(candidateMd.lowercase())) {
                        duplicateCollisions++
                        val stem = candidateMd.removeSuffix(".md")
                        var suffix = 2
                        while (!usedMdPaths.add("${stem}_$suffix.md".lowercase())) {
                            suffix++
                        }
                        candidateMd = "${stem}_$suffix.md"
                    }

                    val planned = PlannedTextEntry(
                        orderIndex = seqIdx,
                        originalPath = path,
                        title = entry.title.ifBlank { SlugUtils.stripKnownTextExtension(stripped.substringAfterLast('/')) },
                        mimeType = mime.ifEmpty { "text/html" },
                        category = category,
                        markdownRelPath = candidateMd,
                        dirCategory = inferredDirCat
                    )
                    plannedTextEntries.add(planned)
                    plannedTextByPath[path] = planned
                    pathCatalog[path] = PathIndexInfo(
                        originalPath = path,
                        category = category,
                        markdownRelativePath = candidateMd,
                        isRedirect = false,
                        finalTargetOriginalPath = path,
                        finalTargetMarkdownPath = candidateMd
                    )
                } else if (ContentClassifier.isMediaOrExcludedCategory(category)) {
                    val fileName = path.substringAfterLast('/')
                    excludedMediaMap[path] = ExcludedMediaRecord(
                        originalPath = path,
                        name = fileName,
                        mimeType = mime.ifEmpty { "application/octet-stream" },
                        sizeBytes = size,
                        referencedBy = emptyList(),
                        reason = "Regra absoluta: mídia/binário (${category.name}) excluído"
                    )
                    pathCatalog[path] = PathIndexInfo(
                        originalPath = path,
                        category = category,
                        markdownRelativePath = null,
                        isRedirect = false,
                        finalTargetOriginalPath = path,
                        finalTargetMarkdownPath = null
                    )
                } else {
                    unsupportedCount++
                }
                seqIdx++

                val now = System.currentTimeMillis()
                if (now - lastInventoryNotify >= config.progressUpdateIntervalMs) {
                    lastInventoryNotify = now
                    currentProgress = currentProgress.copy(
                        phaseIndex = 1,
                        phaseLabel = "Fase 1/3: Inventariando entradas (${plannedTextEntries.size} textuais, ${rawRedirects.size} aliases)...",
                        totalEntries = inspection.totalEntries.coerceAtLeast(seqIdx),
                        processedEntries = 0,
                        totalArticles = plannedTextEntries.size,
                        redirectsIdentified = rawRedirects.size,
                        mediaExcluded = excludedMediaMap.size,
                        elapsedMs = now - startTime,
                        currentEntryTitle = entry.title.ifBlank { path },
                        currentEntryPath = path,
                        recentEvents = eventsSnapshot()
                    )
                    _progress.value = currentProgress
                }
            }

            if (pauseRequested.get() || cancelRequested.get()) {
                return handleEarlyStop(
                    baseTask = baseTask,
                    currentProgress = currentProgress,
                    processedCount = 0,
                    convertedCount = 0,
                    excludedMediaCount = excludedMediaMap.size,
                    errorsCount = 0,
                    filesCreatedCount = 0,
                    totalBytesWritten = 0L,
                    startTime = startTime
                )
            }

            // Pre-allocate Mode B single-file anchor slugs identically to ExportManager.assembleSingleMarkdownFile
            if (config.exportMode == ExportMode.SINGLE_FILE) {
                val anchorCounts = HashMap<String, Int>()
                val grouped = plannedTextEntries.groupBy { it.dirCategory }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                for ((cat, docs) in grouped) {
                    val catSlug = SlugUtils.markdownAnchorSlug(cat)
                    anchorCounts[catSlug] = (anchorCounts[catSlug] ?: 0) + 1
                    for (doc in docs) {
                        val base = SlugUtils.markdownAnchorSlug(doc.title)
                        val count = anchorCounts[base] ?: 0
                        anchorCounts[base] = count + 1
                        val uniqueAnchor = if (count == 0) base else "$base-$count"
                        doc.singleFileAnchorId = uniqueAnchor
                        pathCatalog[doc.originalPath]?.let { prev ->
                            pathCatalog[doc.originalPath] = prev.copy(singleFileAnchorId = uniqueAnchor)
                        }
                    }
                }
            }

            // Resolve redirects
            val redirectManager = RedirectManager(config)
            val textualTargetMap = plannedTextEntries.associate { it.originalPath to (it.title to it.markdownRelPath) }
            val resolvedRedirects = if (config.preserveAllAliases) {
                redirectManager.resolveAll(
                    archive = archive,
                    rawRedirects = rawRedirects,
                    textualTargetMap = textualTargetMap,
                    excludedTargetSet = excludedMediaMap.keys
                )
            } else {
                emptyList()
            }

            for (r in resolvedRedirects) {
                val targetAnchor = r.finalTargetPath?.let { pathCatalog[it]?.singleFileAnchorId }
                pathCatalog[r.sourcePath] = PathIndexInfo(
                    originalPath = r.sourcePath,
                    category = ContentCategory.REDIRECT,
                    markdownRelativePath = r.markdownSourcePath,
                    isRedirect = true,
                    finalTargetOriginalPath = r.finalTargetPath,
                    finalTargetMarkdownPath = r.markdownTargetPath,
                    singleFileAnchorId = targetAnchor
                )
            }

            val aliasesByTarget = resolvedRedirects
                .filter { it.finalTargetPath != null }
                .groupBy { it.finalTargetPath!! }
                .mapValues { (_, list) -> list.map { it.sourceTitle } }

            addEvent(
                "INFO",
                "Inventário concluído: ${plannedTextEntries.size} textuais, ${resolvedRedirects.size} redirecionamentos, ${excludedMediaMap.size} mídias bloqueadas."
            )

            val linkResolver = LinkResolver(
                config = config,
                pathCatalog = pathCatalog,
                singleFileMode = config.exportMode == ExportMode.SINGLE_FILE
            )

            val manifestList = ArrayList<ManifestRecord>(plannedTextEntries.size)
            val orderIndexByPath = plannedTextEntries.associate { it.originalPath to it.orderIndex }
            val mediaRefSources = HashMap<String, MutableSet<String>>()
            val warningsList = mutableListOf<String>()
            val errorsList = mutableListOf<String>()

            var readFailures = 0
            var conversionFailures = 0
            var writeFailures = 0
            val missingTargets = resolvedRedirects.count { it.status == "MISSING_TARGET" }

            for (r in resolvedRedirects) {
                r.warningMessage?.let { warningsList.add("[Redirecionamento ${r.sourcePath}] $it") }
            }

            val checkpointBatch = ArrayList<TaskCheckpointEntity>(config.writeBatchSize)
            val searchBatch = ArrayList<IndexedDocumentEntity>(config.writeBatchSize)

            var processedCount = 0
            var convertedCount = 0
            var totalBytesWritten = 0L
            var filesCreatedCount = 0
            var lastUiNotify = 0L
            val conversionPhaseStartMs = System.currentTimeMillis()
            var newlyConvertedCount = 0

            val totalWorkCount = (plannedTextEntries.size + resolvedRedirects.size).coerceAtLeast(1)

            fun emitLiveConversionProgress(
                title: String,
                path: String,
                mime: String,
                force: Boolean = false
            ) {
                val now = System.currentTimeMillis()
                if (!force && now - lastUiNotify < config.progressUpdateIntervalMs) return
                lastUiNotify = now
                val linksSnapshot = linkResolver.getAuditSnapshot()
                val invalidLinks = linksSnapshot.count { it.linkType == "BROKEN" }
                val validLinks = linksSnapshot.size - invalidLinks
                val totalElapsed = (now - startTime).coerceAtLeast(1L)
                val phaseElapsedSec = ((now - conversionPhaseStartMs).coerceAtLeast(1L)) / 1000.0
                val artPerSec = if (newlyConvertedCount > 0) newlyConvertedCount / phaseElapsedSec else 0.0
                val bytesPerSec = totalBytesWritten / (totalElapsed / 1000.0)
                val remainingWork = (totalWorkCount - processedCount).coerceAtLeast(0)
                val etaMs = if (artPerSec > 0.05 && remainingWork > 0) {
                    ((remainingWork / artPerSec) * 1000.0).toLong()
                } else null

                currentProgress = ConversionProgress(
                    taskId = taskId,
                    status = ConversionTaskStatus.RUNNING,
                    phaseIndex = 2,
                    phaseLabel = "Fase 2/3: Convertendo artigos (${processedCount}/${plannedTextEntries.size}) • ${config.parallelismWorkers.coerceIn(1, 8)} worker(s)",
                    totalEntries = totalWorkCount,
                    processedEntries = processedCount,
                    totalArticles = plannedTextEntries.size,
                    articlesConverted = convertedCount,
                    redirectsIdentified = resolvedRedirects.size,
                    redirectsResolved = 0,
                    linksConverted = validLinks,
                    linksInvalid = invalidLinks,
                    mediaExcluded = excludedMediaMap.size,
                    markdownFilesCreated = filesCreatedCount,
                    outputBytesWritten = totalBytesWritten,
                    elapsedMs = now - startTime,
                    articlesPerSecond = artPerSec,
                    bytesPerSecond = bytesPerSec,
                    estimatedRemainingMs = etaMs,
                    currentEntryTitle = title,
                    currentEntryPath = path,
                    currentEntryMime = mime,
                    outputDirectoryPath = libraryDir.absolutePath,
                    recentEvents = eventsSnapshot()
                )
                _progress.value = currentProgress
            }

            emitLiveConversionProgress(
                title = "Iniciando pipeline de conversão...",
                path = "",
                mime = "",
                force = true
            )

            // --- PASS 2: Bounded Parallel Streaming Entry Conversion ---
            val workerCount = config.parallelismWorkers.coerceIn(1, 8)
            val queueCapacity = (workerCount * 3).coerceIn(4, 32)
            val memoryBudgetBytes = (config.memoryLimitMb.coerceIn(16, 512).toLong() * 1024L * 1024L) / 2L
            val stateMutex = Mutex()
            val workChannel = Channel<RawWorkItem>(capacity = queueCapacity)
            var queuedBytes = 0L
            val queueBytesMutex = Mutex()

            coroutineScope {
                val workers = List(workerCount) {
                    launch(Dispatchers.Default) {
                        val htmlParser = HtmlSemanticParser(config)
                        for (work in workChannel) {
                            currentCoroutineContext().ensureActive()
                            val planned = work.planned
                            val rawBytes = work.rawBytes
                            queueBytesMutex.withLock { queuedBytes = (queuedBytes - rawBytes.size).coerceAtLeast(0L) }

                            if (pauseRequested.get() || cancelRequested.get()) continue

                            val outcome = processArticleBytes(
                                planned = planned,
                                rawBytes = rawBytes,
                                htmlParser = htmlParser,
                                linkResolver = linkResolver,
                                aliases = aliasesByTarget[planned.originalPath].orEmpty(),
                                inspectionLanguage = inspection.metadata.language,
                                libraryDir = libraryDir,
                                taskId = taskId,
                                config = config
                            )

                            stateMutex.withLock {
                                if (outcome.readFailed) readFailures++
                                if (outcome.conversionFailed) conversionFailures++
                                if (outcome.writeFailed) writeFailures++
                                outcome.errorMessage?.let { errorsList.add(it) }
                                warningsList.addAll(outcome.warnings)

                                outcome.mislabeledBinary?.let { rec ->
                                    excludedMediaMap[planned.originalPath] = rec
                                }

                                for (m in outcome.discoveredMedia) {
                                    if (m.src.isNotBlank()) {
                                        val resolvedMediaPath = LinkResolver.resolveRelativeZimPath(planned.originalPath, m.src)
                                        mediaRefSources.getOrPut(resolvedMediaPath) { linkedSetOf() }.add(planned.originalPath)
                                        if (!excludedMediaMap.containsKey(resolvedMediaPath)) {
                                            excludedMediaMap[resolvedMediaPath] = ExcludedMediaRecord(
                                                originalPath = resolvedMediaPath,
                                                name = resolvedMediaPath.substringAfterLast('/'),
                                                mimeType = m.mediaType,
                                                sizeBytes = 0L,
                                                referencedBy = listOf(planned.originalPath),
                                                reason = "Referenciado no artigo e não exportado"
                                            )
                                        }
                                    }
                                }

                                outcome.manifestRecord?.let { rec ->
                                    manifestList.add(rec)
                                    convertedCount++
                                    newlyConvertedCount++
                                    filesCreatedCount++
                                    totalBytesWritten += outcome.writtenBytes
                                }
                                outcome.checkpointEntity?.let { checkpointBatch.add(it) }
                                outcome.searchEntity?.let { searchBatch.add(it) }

                                processedCount++

                                if (checkpointBatch.size >= config.writeBatchSize) {
                                    taskDao.upsertCheckpoints(checkpointBatch.toList())
                                    checkpointBatch.clear()
                                }
                                if (searchBatch.size >= config.writeBatchSize) {
                                    searchRepository.indexBatch(searchBatch.toList())
                                    searchBatch.clear()
                                }

                                emitLiveConversionProgress(
                                    title = outcome.manifestRecord?.title ?: planned.title,
                                    path = planned.originalPath,
                                    mime = planned.mimeType,
                                    force = processedCount == plannedTextEntries.size
                                )
                            }
                        }
                    }
                }

                // Sequential reader coroutine feeds raw article payloads safely into workChannel
                try {
                    for (entry in archive.iterateEntriesEfficiently()) {
                        currentCoroutineContext().ensureActive()
                        if (pauseRequested.get() || cancelRequested.get()) break

                        val planned = plannedTextByPath[entry.path] ?: continue

                        // Resume check: skip already written & checkpointed articles
                        val existingCp = existingCheckpoints[planned.originalPath]
                        if (existingCp != null) {
                            val existingFile = File(libraryDir, existingCp.markdownRelPath)
                            if (existingFile.exists() && existingFile.length() > 0L) {
                                stateMutex.withLock {
                                    manifestList.add(
                                        ManifestRecord(
                                            title = existingCp.title,
                                            originalPath = existingCp.sourcePath,
                                            markdownPath = existingCp.markdownRelPath,
                                            category = existingCp.category,
                                            mimeType = existingCp.mimeType,
                                            disposition = runCatching {
                                                EntryDisposition.valueOf(existingCp.disposition)
                                            }.getOrDefault(EntryDisposition.CONVERTED_SUCCESS),
                                            wordCount = existingCp.wordCount
                                        )
                                    )
                                    processedCount++
                                    convertedCount++
                                    filesCreatedCount++
                                    totalBytesWritten += existingFile.length()
                                    emitLiveConversionProgress(
                                        title = existingCp.title,
                                        path = planned.originalPath,
                                        mime = planned.mimeType
                                    )
                                }
                                continue
                            }
                        }

                        val item = try {
                            entry.getItem(followRedirects = false)
                        } catch (e: Throwable) {
                            stateMutex.withLock {
                                readFailures++
                                errorsList.add("Falha de leitura em '${planned.originalPath}': ${e.message}")
                                processedCount++
                                emitLiveConversionProgress(planned.title, planned.originalPath, planned.mimeType)
                            }
                            continue
                        }

                        if (item == null) {
                            stateMutex.withLock {
                                readFailures++
                                errorsList.add("Item nulo ao ler '${planned.originalPath}'")
                                processedCount++
                                emitLiveConversionProgress(planned.title, planned.originalPath, planned.mimeType)
                            }
                            continue
                        }

                        val rawBytes = try {
                            item.readBytes()
                        } catch (e: Throwable) {
                            stateMutex.withLock {
                                readFailures++
                                errorsList.add("Erro ao ler bytes de '${planned.originalPath}': ${e.message}")
                                processedCount++
                                emitLiveConversionProgress(planned.title, planned.originalPath, planned.mimeType)
                            }
                            continue
                        }

                        // Throttle producer if queued raw payloads exceed memory budget
                        var spinGuard = 0
                        while (queueBytesMutex.withLock { queuedBytes } > memoryBudgetBytes && spinGuard < 100) {
                            kotlinx.coroutines.delay(5)
                            spinGuard++
                        }

                        queueBytesMutex.withLock { queuedBytes += rawBytes.size }
                        workChannel.send(RawWorkItem(planned, rawBytes))
                    }
                } finally {
                    workChannel.close()
                }
                workers.forEach { it.join() }
            }

            // Flush remaining batches
            if (checkpointBatch.isNotEmpty()) {
                taskDao.upsertCheckpoints(checkpointBatch.toList())
                checkpointBatch.clear()
            }
            if (searchBatch.isNotEmpty()) {
                searchRepository.indexBatch(searchBatch.toList())
                searchBatch.clear()
            }

            // Handle Pause or Cancel cleanly without losing completed work
            if (pauseRequested.get() || cancelRequested.get()) {
                val linksSnapshot = linkResolver.getAuditSnapshot()
                val invalidLinks = linksSnapshot.count { it.linkType == "BROKEN" }
                return handleEarlyStop(
                    baseTask = baseTask,
                    currentProgress = currentProgress.copy(
                        redirectsIdentified = resolvedRedirects.size,
                        linksConverted = linksSnapshot.size - invalidLinks,
                        linksInvalid = invalidLinks
                    ),
                    processedCount = processedCount,
                    convertedCount = convertedCount,
                    excludedMediaCount = excludedMediaMap.size,
                    errorsCount = errorsList.size,
                    filesCreatedCount = filesCreatedCount,
                    totalBytesWritten = totalBytesWritten,
                    startTime = startTime
                )
            }

            // Restore deterministic ordering according to config.preserveOriginalOrder
            if (config.preserveOriginalOrder) {
                manifestList.sortBy { orderIndexByPath[it.originalPath] ?: Int.MAX_VALUE }
            } else {
                manifestList.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            }

            // --- PASS 3: Redirects, Modes B/C, Indexes, Audit & Reports ---
            var pass3RedirectsDone = 0
            fun emitPass3Progress(stageLabel: String, itemTitle: String, itemPath: String = "", force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastUiNotify < config.progressUpdateIntervalMs) return
                lastUiNotify = now
                val linksSnapshot = linkResolver.getAuditSnapshot()
                val invalidLinks = linksSnapshot.count { it.linkType == "BROKEN" }
                val stepDone = (plannedTextEntries.size + pass3RedirectsDone).coerceAtMost(totalWorkCount)
                currentProgress = currentProgress.copy(
                    status = ConversionTaskStatus.RUNNING,
                    phaseIndex = 3,
                    phaseLabel = stageLabel,
                    totalEntries = totalWorkCount,
                    processedEntries = stepDone,
                    totalArticles = plannedTextEntries.size,
                    articlesConverted = convertedCount,
                    redirectsIdentified = resolvedRedirects.size,
                    redirectsResolved = pass3RedirectsDone,
                    linksConverted = linksSnapshot.size - invalidLinks,
                    linksInvalid = invalidLinks,
                    mediaExcluded = excludedMediaMap.size,
                    markdownFilesCreated = filesCreatedCount,
                    outputBytesWritten = totalBytesWritten,
                    elapsedMs = now - startTime,
                    estimatedRemainingMs = null,
                    currentEntryTitle = itemTitle,
                    currentEntryPath = itemPath,
                    currentEntryMime = "text/markdown",
                    recentEvents = eventsSnapshot()
                )
                _progress.value = currentProgress
            }

            emitPass3Progress(
                stageLabel = "Fase 3/3: Gravando redirecionamentos e aliases...",
                itemTitle = "Redirecionamentos (${resolvedRedirects.size})",
                force = true
            )

            // Write individual redirect .md files
            val redirectEntities = ArrayList<RedirectRecordEntity>(resolvedRedirects.size)
            val redirectSearchBatch = ArrayList<IndexedDocumentEntity>(40)

            for (res in resolvedRedirects) {
                currentCoroutineContext().ensureActive()
                redirectEntities.add(
                    RedirectRecordEntity(
                        taskId = taskId,
                        sourceTitle = res.sourceTitle,
                        sourcePath = res.sourcePath,
                        immediateTargetPath = res.immediateTargetPath,
                        finalTargetPath = res.finalTargetPath,
                        finalTargetTitle = res.finalTargetTitle,
                        markdownSourcePath = res.markdownSourcePath,
                        markdownTargetPath = res.markdownTargetPath,
                        hopCount = res.hopCount,
                        status = res.status,
                        warningMessage = res.warningMessage
                    )
                )
                if (config.generateIndividualRedirectFiles &&
                    config.exportMode != ExportMode.SINGLE_FILE &&
                    config.exportMode != ExportMode.SPLIT_BY_CATEGORY
                ) {
                    val mdContent = redirectManager.renderRedirectMarkdown(res)
                    val f = ExportManager.writeMarkdownAtomically(
                        libraryDir,
                        res.markdownSourcePath,
                        mdContent,
                        config.ioBufferSizeKb
                    )
                    filesCreatedCount++
                    totalBytesWritten += f.length()

                    redirectSearchBatch.add(
                        IndexedDocumentEntity(
                            taskId = taskId,
                            title = res.sourceTitle,
                            normalizedTitle = SlugUtils.normalizeForSearch(res.sourceTitle),
                            aliasesText = res.sourceTitle,
                            categoriesText = "Redirecionamentos",
                            originalPath = res.sourcePath,
                            markdownRelPath = res.markdownSourcePath,
                            contentText = mdContent,
                            normalizedContent = SlugUtils.normalizeForSearch(mdContent),
                            metadataText = "${res.sourcePath} ${res.finalTargetPath.orEmpty()}"
                        )
                    )
                    if (redirectSearchBatch.size >= 40) {
                        searchRepository.indexBatch(redirectSearchBatch.toList())
                        redirectSearchBatch.clear()
                    }
                }
                pass3RedirectsDone++
                emitPass3Progress(
                    stageLabel = "Fase 3/3: Processando redirecionamentos ($pass3RedirectsDone/${resolvedRedirects.size})...",
                    itemTitle = res.sourceTitle,
                    itemPath = res.sourcePath
                )
            }
            if (redirectSearchBatch.isNotEmpty()) {
                searchRepository.indexBatch(redirectSearchBatch)
            }
            taskDao.deleteRedirectsForTask(taskId)
            if (redirectEntities.isNotEmpty()) {
                taskDao.insertRedirects(redirectEntities)
            }

            val enrichedExcluded = excludedMediaMap.values.map { rec ->
                val refs = mediaRefSources[rec.originalPath]?.toList().orEmpty()
                if (refs.isEmpty()) rec else rec.copy(referencedBy = refs)
            }

            val linksAudit = linkResolver.getAuditSnapshot()
            val relationships = linkResolver.getRelationshipsSnapshot()
            var effectiveManifest: List<ManifestRecord> = manifestList

            // Handle Export Modes B & C assembly & remap manifest paths so indexes/search match real files
            when (config.exportMode) {
                ExportMode.SINGLE_FILE -> {
                    emitPass3Progress("Fase 3/3: Consolidando biblioteca em arquivo único (Modo B)...", "Montando .MD único", force = true)
                    val assembly = ExportManager.assembleSingleMarkdownFile(libraryDir, inspection, manifestList, config)
                    File(libraryDir, SlugUtils.sanitizeFileSegment(config.customArticlesSubdir, "articles")).deleteRecursively()
                    effectiveManifest = manifestList.map { rec ->
                        val remapped = assembly.remappedMarkdownPaths[rec.originalPath] ?: rec.markdownPath
                        rec.copy(markdownPath = remapped)
                    }
                    filesCreatedCount = libraryDir.walkTopDown().count { it.isFile && it.extension.equals("md", ignoreCase = true) }
                    totalBytesWritten = libraryDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    searchRepository.rebuildIndexFromMarkdownDirectory(taskId, libraryDir, config.customIndexesSubdir)
                    addEvent("INFO", "Modo B: Arquivo único consolidado criado (${assembly.files.firstOrNull()?.name})")
                }
                ExportMode.SPLIT_BY_CATEGORY -> {
                    emitPass3Progress("Fase 3/3: Consolidando arquivos por categoria (Modo C)...", "Agrupando categorias", force = true)
                    val assembly = ExportManager.assembleCategorySplitFiles(libraryDir, manifestList, config.ioBufferSizeKb)
                    File(libraryDir, SlugUtils.sanitizeFileSegment(config.customArticlesSubdir, "articles")).deleteRecursively()
                    effectiveManifest = manifestList.map { rec ->
                        val remapped = assembly.remappedMarkdownPaths[rec.originalPath] ?: rec.markdownPath
                        rec.copy(markdownPath = remapped)
                    }
                    filesCreatedCount = libraryDir.walkTopDown().count { it.isFile && it.extension.equals("md", ignoreCase = true) }
                    totalBytesWritten = libraryDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    searchRepository.rebuildIndexFromMarkdownDirectory(taskId, libraryDir, config.customIndexesSubdir)
                    addEvent("INFO", "Modo C: ${assembly.files.size} arquivo(s) Markdown por categoria gerados.")
                }
                else -> Unit
            }

            val mainPageMd = inspection.metadata.mainPagePath?.let { mp ->
                val cleanMp = mp.trimStart('/')
                effectiveManifest.firstOrNull {
                    it.originalPath == cleanMp || it.originalPath == "A/$cleanMp" || it.originalPath == "C/$cleanMp"
                }?.markdownPath
                    ?: plannedTextByPath[cleanMp]?.markdownRelPath
                    ?: plannedTextByPath["A/$cleanMp"]?.markdownRelPath
                    ?: plannedTextByPath["C/$cleanMp"]?.markdownRelPath
            }

            // Generate metadata, indexes & reports
            emitPass3Progress("Fase 3/3: Gerando índices e relatórios de auditoria...", "INDEX.md & Auditoria", force = true)
            val indexManager = IndexManager(config)
            val validator = ConversionValidator()
            val idxSubdir = SlugUtils.sanitizeFileSegment(config.customIndexesSubdir, "indexes")
            val bufKb = config.ioBufferSizeKb

            fun writeAux(relPath: String, content: String) {
                val f = ExportManager.writeMarkdownAtomically(libraryDir, relPath, content, bufKb)
                filesCreatedCount++
                totalBytesWritten += f.length()
            }

            writeAux("METADATA.md", MetadataManager.buildMetadataMarkdown(inspection, config))
            writeAux("README.md", indexManager.buildReadme(inspection, effectiveManifest, resolvedRedirects))
            if (config.generateHomePage) {
                writeAux("HOME.md", indexManager.buildHome(inspection, effectiveManifest, mainPageMd))
            }
            if (config.generateGeneralIndex) {
                writeAux("INDEX.md", indexManager.buildGeneralIndex(effectiveManifest, resolvedRedirects))
            }
            if (config.generateAlphabeticalIndex) {
                writeAux("$idxSubdir/INDEX_BY_TITLE.md", indexManager.buildIndexByTitle(effectiveManifest))
            }
            if (config.generateCategoryIndex) {
                writeAux("$idxSubdir/INDEX_BY_CATEGORY.md", indexManager.buildIndexByCategory(effectiveManifest))
            }
            if (config.generatePathIndex) {
                writeAux("$idxSubdir/INDEX_BY_PATH.md", indexManager.buildIndexByPath(effectiveManifest))
            }
            if (config.generateRelationshipIndex) {
                writeAux(
                    "$idxSubdir/INDEX_BY_RELATIONSHIP.md",
                    indexManager.buildIndexByRelationship(effectiveManifest, relationships, resolvedRedirects)
                )
            }
            writeAux("MANIFEST.md", validator.buildManifestMarkdown(effectiveManifest))
            if (config.logExcludedMediaInReport) {
                writeAux("EXCLUDED_CONTENT.md", validator.buildExcludedContentMarkdown(enrichedExcluded))
            }
            if (config.generateRedirectsReport) {
                writeAux("REDIRECTS.md", validator.buildRedirectsMarkdown(resolvedRedirects))
            }
            writeAux("LINKS.md", validator.buildLinksMarkdown(linksAudit))
            writeAux("WARNINGS.md", validator.buildWarningsMarkdown(warningsList))
            writeAux("ERRORS.md", validator.buildErrorsMarkdown(errorsList))

            val elapsedMs = System.currentTimeMillis() - startTime
            val preliminaryAudit: AuditSummary = if (config.runIntegrityAudit) {
                validator.auditOutputDirectory(
                    outputDir = libraryDir,
                    inspection = inspection,
                    manifest = effectiveManifest,
                    excludedMedia = enrichedExcluded,
                    redirects = resolvedRedirects,
                    links = linksAudit,
                    errors = errorsList,
                    warnings = warningsList,
                    duplicateCollisions = duplicateCollisions,
                    unsupportedCount = unsupportedCount,
                    readFailures = readFailures,
                    conversionFailures = conversionFailures,
                    writeFailures = writeFailures,
                    missingTargets = missingTargets
                )
            } else {
                buildLightweightAudit(
                    inspection = inspection,
                    manifest = effectiveManifest,
                    excludedMedia = enrichedExcluded,
                    redirects = resolvedRedirects,
                    links = linksAudit,
                    duplicateCollisions = duplicateCollisions,
                    unsupportedCount = unsupportedCount,
                    readFailures = readFailures,
                    conversionFailures = conversionFailures,
                    writeFailures = writeFailures,
                    missingTargets = missingTargets,
                    mdFilesCreated = filesCreatedCount,
                    totalBytes = totalBytesWritten
                )
            }

            writeAux(
                "CONVERSION_REPORT.md",
                validator.buildConversionReportMarkdown(inspection, preliminaryAudit, config, elapsedMs)
            )

            // Optional ZIP / ZIP64 package
            var zipPath: String? = null
            if (config.packageFormat != ArchivePackageFormat.FOLDER_ONLY) {
                emitPass3Progress("Fase 3/3: Empacotando biblioteca em ${config.packageFormat.name} (.md apenas)...", "Empacotamento ZIP", force = true)
                val zipFile = File(libraryDir.parentFile, "${libraryDir.name}.zip")
                ExportManager.packageLibraryAsZip(libraryDir, zipFile, config.packageFormat, config.ioBufferSizeKb)
                zipPath = zipFile.absolutePath
                addEvent("INFO", "Pacote ${config.packageFormat.name} criado apenas com arquivos .md: ${zipFile.name}")
            }

            // Optional SAF tree mirroring
            val safUriStr = config.customDestinationTreeUri
            if (!safUriStr.isNullOrBlank() && context != null) {
                emitPass3Progress("Fase 3/3: Espelhando arquivos .md para pasta SAF...", "Cópia SAF", force = true)
                try {
                    val copied = ExportManager.mirrorLibraryToSafDirectory(
                        context = context,
                        libraryDir = libraryDir,
                        treeUri = Uri.parse(safUriStr),
                        bufferSizeKb = config.ioBufferSizeKb
                    )
                    addEvent("INFO", "Espelhados $copied arquivos .md para a pasta de destino selecionada.")
                } catch (e: Throwable) {
                    warningsList.add("Falha ao copiar para pasta SAF personalizada: ${e.message}")
                    addEvent("WARN", "Erro ao copiar para pasta externa: ${e.message}")
                }
            }

            val finalAudit = if (config.runIntegrityAudit) {
                validator.auditOutputDirectory(
                    outputDir = libraryDir,
                    inspection = inspection,
                    manifest = effectiveManifest,
                    excludedMedia = enrichedExcluded,
                    redirects = resolvedRedirects,
                    links = linksAudit,
                    errors = errorsList,
                    warnings = warningsList,
                    duplicateCollisions = duplicateCollisions,
                    unsupportedCount = unsupportedCount,
                    readFailures = readFailures,
                    conversionFailures = conversionFailures,
                    writeFailures = writeFailures,
                    missingTargets = missingTargets
                )
            } else {
                preliminaryAudit.copy(
                    markdownFilesCreated = filesCreatedCount,
                    outputTotalBytes = totalBytesWritten
                )
            }

            val finishedAt = System.currentTimeMillis()
            taskDao.upsertTask(
                baseTask.copy(
                    status = ConversionTaskStatus.COMPLETED.name,
                    processedEntries = totalWorkCount,
                    articlesConverted = convertedCount,
                    redirectsCount = resolvedRedirects.size,
                    mediaExcludedCount = enrichedExcluded.size,
                    errorCount = errorsList.size,
                    zipFilePath = zipPath,
                    auditSummaryJson = json.encodeToString(finalAudit),
                    updatedAt = finishedAt
                )
            )

            addEvent("INFO", "Conversão finalizada: ${finalAudit.markdownFilesCreated} arquivos .md gerados.")

            val totalSec = ((finishedAt - startTime).coerceAtLeast(1L)) / 1000.0
            val doneProgress = ConversionProgress(
                taskId = taskId,
                status = ConversionTaskStatus.COMPLETED,
                phaseIndex = 3,
                phaseLabel = if (finalAudit.integrityPassed) {
                    "Conversão concluída com auditoria aprovada"
                } else {
                    "Conversão concluída com alertas na auditoria"
                },
                totalEntries = totalWorkCount,
                processedEntries = totalWorkCount,
                totalArticles = plannedTextEntries.size,
                articlesConverted = convertedCount,
                redirectsIdentified = resolvedRedirects.size,
                redirectsResolved = finalAudit.redirectsResolved,
                linksConverted = finalAudit.internalLinksConverted + finalAudit.anchorsPreserved,
                linksInvalid = finalAudit.brokenLinksDetected,
                mediaExcluded = enrichedExcluded.size,
                markdownFilesCreated = finalAudit.markdownFilesCreated,
                outputBytesWritten = finalAudit.outputTotalBytes,
                elapsedMs = finishedAt - startTime,
                articlesPerSecond = convertedCount / totalSec,
                bytesPerSecond = finalAudit.outputTotalBytes / totalSec,
                estimatedRemainingMs = 0L,
                currentEntryTitle = "Concluído",
                currentEntryPath = "",
                currentEntryMime = "",
                outputDirectoryPath = libraryDir.absolutePath,
                zipFilePath = zipPath,
                auditSummary = finalAudit,
                recentEvents = eventsSnapshot()
            )
            _progress.value = doneProgress
            return doneProgress
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                val now = System.currentTimeMillis()
                addEvent("WARN", "Conversão interrompida/cancelada.")
                taskDao.upsertTask(
                    baseTask.copy(
                        status = ConversionTaskStatus.CANCELLED.name,
                        updatedAt = now
                    )
                )
                _progress.value = _progress.value.copy(
                    status = ConversionTaskStatus.CANCELLED,
                    phaseLabel = "Cancelado",
                    elapsedMs = now - startTime,
                    estimatedRemainingMs = null,
                    recentEvents = eventsSnapshot()
                )
            }
            throw ce
        } catch (t: Throwable) {
            val now = System.currentTimeMillis()
            val errMsg = t.message ?: "Erro inesperado durante a conversão"
            addEvent("ERROR", "Falha crítica: $errMsg")
            withContext(NonCancellable) {
                taskDao.upsertTask(
                    baseTask.copy(
                        status = ConversionTaskStatus.FAILED.name,
                        errorCount = baseTask.errorCount + 1,
                        updatedAt = now
                    )
                )
            }
            val failedProgress = currentProgress.copy(
                status = ConversionTaskStatus.FAILED,
                phaseLabel = "Falha na conversão: $errMsg",
                elapsedMs = now - startTime,
                estimatedRemainingMs = null,
                errorMessage = errMsg,
                recentEvents = eventsSnapshot()
            )
            _progress.value = failedProgress
            return failedProgress
        }
    }

    private suspend fun handleEarlyStop(
        baseTask: ConversionTaskEntity,
        currentProgress: ConversionProgress,
        processedCount: Int,
        convertedCount: Int,
        excludedMediaCount: Int,
        errorsCount: Int,
        filesCreatedCount: Int,
        totalBytesWritten: Long,
        startTime: Long
    ): ConversionProgress {
        val pausedStatus = if (cancelRequested.get()) ConversionTaskStatus.CANCELLED else ConversionTaskStatus.PAUSED
        val now = System.currentTimeMillis()
        taskDao.upsertTask(
            baseTask.copy(
                status = pausedStatus.name,
                processedEntries = processedCount,
                articlesConverted = convertedCount,
                mediaExcludedCount = excludedMediaCount,
                errorCount = errorsCount,
                updatedAt = now
            )
        )
        addEvent(
            "WARN",
            if (pausedStatus == ConversionTaskStatus.PAUSED) "Conversão pausada com segurança em $processedCount itens." else "Conversão cancelada com progresso preservado."
        )
        val result = currentProgress.copy(
            status = pausedStatus,
            phaseLabel = if (pausedStatus == ConversionTaskStatus.PAUSED) "Pausado — pronto para retomar" else "Cancelado",
            processedEntries = processedCount,
            articlesConverted = convertedCount,
            mediaExcluded = excludedMediaCount,
            markdownFilesCreated = filesCreatedCount,
            outputBytesWritten = totalBytesWritten,
            elapsedMs = now - startTime,
            estimatedRemainingMs = null,
            currentEntryTitle = "",
            currentEntryPath = "",
            recentEvents = eventsSnapshot()
        )
        _progress.value = result
        return result
    }

    private fun processArticleBytes(
        planned: PlannedTextEntry,
        rawBytes: ByteArray,
        htmlParser: HtmlSemanticParser,
        linkResolver: LinkResolver,
        aliases: List<String>,
        inspectionLanguage: String?,
        libraryDir: File,
        taskId: String,
        config: ConversionConfig
    ): ProcessedOutcome {
        val verifiedCategory = ContentClassifier.verifyWithSignature(planned.category, rawBytes)
        if (!ContentClassifier.isTextualCategory(verifiedCategory)) {
            return ProcessedOutcome(
                planned = planned,
                mislabeledBinary = ExcludedMediaRecord(
                    originalPath = planned.originalPath,
                    name = planned.originalPath.substringAfterLast('/'),
                    mimeType = planned.mimeType,
                    sizeBytes = rawBytes.size.toLong(),
                    referencedBy = emptyList(),
                    reason = "Assinatura binária detectada apesar do MIME '${planned.mimeType}'"
                )
            )
        }

        return try {
            var finalTitle = planned.title
            var finalDescription: String? = null
            var docCategories = listOf(planned.dirCategory)
            var disposition = EntryDisposition.CONVERTED_SUCCESS
            val markdownBody: String
            var wordCount = 0
            val discoveredMedia = mutableListOf<DiscoveredMediaRef>()
            val localWarnings = mutableListOf<String>()

            when (verifiedCategory) {
                ContentCategory.HTML, ContentCategory.XHTML -> {
                    val parsed = htmlParser.parseAndConvert(
                        bytes = rawBytes,
                        isXhtml = verifiedCategory == ContentCategory.XHTML,
                        fallbackTitle = planned.title,
                        linkResolver = HtmlLinkResolver { href, text ->
                            linkResolver.resolveForArticle(
                                sourceOriginalPath = planned.originalPath,
                                sourceMarkdownPath = planned.markdownRelPath,
                                rawHref = href,
                                linkText = text
                            )
                        }
                    )
                    linkResolver.registerArticleAnchors(planned.originalPath, parsed.headingAnchors)
                    finalTitle = parsed.extractedTitle ?: planned.title
                    finalDescription = parsed.extractedDescription
                    if (config.organizeByCategories && parsed.categories.isNotEmpty()) {
                        docCategories = parsed.categories
                    }
                    markdownBody = parsed.markdownBody
                    wordCount = parsed.wordCount
                    localWarnings.addAll(parsed.warnings)
                    discoveredMedia.addAll(parsed.discoveredMedia)
                }

                ContentCategory.PLAIN_TEXT -> {
                    val res = PlainTextAndXmlConverter.convertPlainText(rawBytes, planned.title)
                    markdownBody = res.markdownBody
                    wordCount = markdownBody.split(Regex("\\s+")).count { it.isNotBlank() }
                }

                ContentCategory.XML_TEXT -> {
                    val res = PlainTextAndXmlConverter.convertXmlText(rawBytes, planned.title)
                    finalTitle = res.title ?: planned.title
                    markdownBody = res.markdownBody
                    wordCount = markdownBody.split(Regex("\\s+")).count { it.isNotBlank() }
                    localWarnings.addAll(res.warnings)
                }

                ContentCategory.PDF -> {
                    val res = PlainTextAndXmlConverter.convertPdfSelectableText(rawBytes, planned.title, config)
                    finalTitle = res.title ?: planned.title
                    markdownBody = res.markdownBody
                    wordCount = markdownBody.split(Regex("\\s+")).count { it.isNotBlank() }
                    if (res.warnings.isNotEmpty()) {
                        disposition = EntryDisposition.CONVERTED_PARTIAL
                        localWarnings.addAll(res.warnings)
                    }
                }

                else -> {
                    disposition = EntryDisposition.UNSUPPORTED
                    markdownBody = ""
                }
            }

            if (markdownBody.isBlank()) {
                return ProcessedOutcome(
                    planned = planned,
                    discoveredMedia = discoveredMedia,
                    warnings = localWarnings
                )
            }

            val fm = MetadataManager.buildArticleFrontMatter(
                title = finalTitle,
                description = finalDescription,
                language = inspectionLanguage,
                sourcePath = planned.originalPath,
                sourceTitle = planned.title,
                sourceMime = planned.mimeType,
                categories = docCategories,
                conversionStatus = if (disposition == EntryDisposition.CONVERTED_SUCCESS) "success" else "partial",
                config = config
            )
            val fullDoc = fm + markdownBody + "\n"
            val writtenFile = try {
                ExportManager.writeMarkdownAtomically(
                    libraryDir,
                    planned.markdownRelPath,
                    fullDoc,
                    config.ioBufferSizeKb
                )
            } catch (we: Throwable) {
                return ProcessedOutcome(
                    planned = planned,
                    writeFailed = true,
                    discoveredMedia = discoveredMedia,
                    warnings = localWarnings,
                    errorMessage = "Falha ao gravar '${planned.markdownRelPath}': ${we.message}"
                )
            }

            val primaryCategory = docCategories.firstOrNull() ?: planned.dirCategory
            val manifestRec = ManifestRecord(
                title = finalTitle,
                originalPath = planned.originalPath,
                markdownPath = planned.markdownRelPath,
                category = primaryCategory,
                mimeType = planned.mimeType,
                disposition = disposition,
                wordCount = wordCount
            )
            val cpEntity = TaskCheckpointEntity(
                taskId = taskId,
                entryIndex = planned.orderIndex,
                sourcePath = planned.originalPath,
                markdownRelPath = planned.markdownRelPath,
                title = finalTitle,
                category = primaryCategory,
                mimeType = planned.mimeType,
                disposition = disposition.name,
                wordCount = wordCount
            )
            val bodyForIndex = markdownBody.take(16000)
            val searchEnt = IndexedDocumentEntity(
                taskId = taskId,
                title = finalTitle,
                normalizedTitle = SlugUtils.normalizeForSearch(finalTitle),
                aliasesText = aliases.joinToString(" | "),
                categoriesText = docCategories.joinToString(", "),
                originalPath = planned.originalPath,
                markdownRelPath = planned.markdownRelPath,
                contentText = bodyForIndex,
                normalizedContent = SlugUtils.normalizeForSearch(bodyForIndex),
                metadataText = "${planned.originalPath} ${planned.mimeType} ${finalDescription.orEmpty()}"
            )

            ProcessedOutcome(
                planned = planned,
                manifestRecord = manifestRec,
                checkpointEntity = cpEntity,
                searchEntity = searchEnt,
                writtenBytes = writtenFile.length(),
                discoveredMedia = discoveredMedia,
                warnings = localWarnings
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            ProcessedOutcome(
                planned = planned,
                conversionFailed = true,
                errorMessage = "Falha ao converter '${planned.originalPath}': ${e.message}"
            )
        }
    }

    private fun buildLightweightAudit(
        inspection: ZimInspection,
        manifest: List<ManifestRecord>,
        excludedMedia: List<ExcludedMediaRecord>,
        redirects: List<RedirectResolution>,
        links: List<com.example.model.LinkAuditRecord>,
        duplicateCollisions: Int,
        unsupportedCount: Int,
        readFailures: Int,
        conversionFailures: Int,
        writeFailures: Int,
        missingTargets: Int,
        mdFilesCreated: Int,
        totalBytes: Long
    ): AuditSummary {
        return AuditSummary(
            totalEntriesIdentified = inspection.totalEntries,
            textualConvertedSuccess = manifest.count { it.disposition == EntryDisposition.CONVERTED_SUCCESS },
            textualConvertedPartial = manifest.count { it.disposition == EntryDisposition.CONVERTED_PARTIAL },
            preservedAsTextReference = manifest.count { it.disposition == EntryDisposition.PRESERVED_TEXT_REF },
            excludedMediaCount = excludedMedia.size,
            unsupportedTextualCount = unsupportedCount,
            readFailures = readFailures,
            conversionFailures = conversionFailures,
            writeFailures = writeFailures,
            missingTargets = missingTargets,
            duplicatePathCollisionsResolved = duplicateCollisions,
            redirectsTotal = redirects.size,
            redirectsResolved = redirects.count { it.status == "RESOLVED" },
            redirectChainsResolved = redirects.count { it.status == "RESOLVED" && it.hopCount > 1 },
            redirectCyclesDetected = redirects.count { it.hasCycle },
            redirectMediaTargets = redirects.count { it.targetExcludedMedia },
            internalLinksConverted = links.count { it.linkType == "INTERNAL" },
            externalLinksPreserved = links.count { it.linkType == "EXTERNAL" },
            mediaLinksStripped = links.count { it.linkType == "MEDIA_EXCLUDED" },
            brokenLinksDetected = links.count { it.linkType == "BROKEN" },
            anchorsPreserved = links.count { it.linkType == "ANCHOR" },
            markdownFilesCreated = mdFilesCreated,
            nonMarkdownFilesInOutput = 0,
            outputTotalBytes = totalBytes,
            sourceArchiveUnmodified = true,
            integrityPassed = writeFailures == 0 && readFailures == 0,
            notes = listOf("Auditoria rápida sem varredura completa de diretórios (opção de auditoria completa desativada).")
        )
    }

    private fun isCategoryEnabledByConfig(category: ContentCategory, config: ConversionConfig): Boolean = when (category) {
        ContentCategory.HTML -> config.convertHtml
        ContentCategory.XHTML -> config.convertXhtml
        ContentCategory.PLAIN_TEXT -> config.convertPlainText
        ContentCategory.XML_TEXT -> config.convertXmlText
        ContentCategory.PDF -> config.extractPdfText
        else -> false
    }

    private fun addEvent(level: String, message: String) {
        synchronized(eventsBuffer) {
            eventsBuffer.add(ConversionEvent(level = level, message = message))
            if (eventsBuffer.size > 120) {
                eventsBuffer.removeAt(0)
            }
        }
    }

    private fun eventsSnapshot(): List<ConversionEvent> = synchronized(eventsBuffer) {
        eventsBuffer.toList()
    }
}
