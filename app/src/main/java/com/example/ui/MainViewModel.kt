package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ConversionTaskEntity
import com.example.data.SettingsRepository
import com.example.engine.ArchiveInspector
import com.example.engine.ConversionEngine
import com.example.export.ExportManager
import com.example.model.ArchivePackageFormat
import com.example.model.AuditSummary
import com.example.model.ConversionConfig
import com.example.model.ConversionProgress
import com.example.model.ConversionTaskStatus
import com.example.model.ZimInspection
import com.example.search.SearchHit
import com.example.search.SearchRepository
import com.example.search.SearchSortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

data class UiMessage(
    val text: String,
    val isError: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val settingsRepo = SettingsRepository(appContext)
    private val searchRepo = SearchRepository(db)
    private val engine = ConversionEngine(appContext, db, searchRepo)
    private val json = Json { ignoreUnknownKeys = true }

    val librariesRootDir: File = File(appContext.filesDir, "libraries").apply { mkdirs() }

    val config: StateFlow<ConversionConfig> = settingsRepo.configFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConversionConfig())

    val historyTasks: StateFlow<List<ConversionTaskEntity>> = db.conversionTaskDao().observeAllTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversionProgress: StateFlow<ConversionProgress> = engine.progress

    private val _availableStorageBytes = MutableStateFlow(ArchiveInspector.queryAvailableStorageBytes(appContext))
    val availableStorageBytes: StateFlow<Long> = _availableStorageBytes.asStateFlow()

    private val _currentInspection = MutableStateFlow<ZimInspection?>(null)
    val currentInspection: StateFlow<ZimInspection?> = _currentInspection.asStateFlow()

    private val _isInspecting = MutableStateFlow(false)
    val isInspecting: StateFlow<Boolean> = _isInspecting.asStateFlow()

    private val _draftConfig = MutableStateFlow(ConversionConfig())
    val draftConfig: StateFlow<ConversionConfig> = _draftConfig.asStateFlow()

    private val _uiMessage = MutableStateFlow<UiMessage?>(null)
    val uiMessage: StateFlow<UiMessage?> = _uiMessage.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchHit>>(emptyList())
    val searchResults: StateFlow<List<SearchHit>> = _searchResults.asStateFlow()

    private val _searchCategories = MutableStateFlow<List<String>>(emptyList())
    val searchCategories: StateFlow<List<String>> = _searchCategories.asStateFlow()

    private val _selectedSearchCategory = MutableStateFlow<String?>(null)
    val selectedSearchCategory: StateFlow<String?> = _selectedSearchCategory.asStateFlow()

    private val _searchSortMode = MutableStateFlow(SearchSortMode.RELEVANCE)
    val searchSortMode: StateFlow<SearchSortMode> = _searchSortMode.asStateFlow()

    private var activeConversionJob: Job? = null

    init {
        viewModelScope.launch {
            _draftConfig.value = settingsRepo.configFlow.first()
        }
    }

    fun clearMessage() {
        _uiMessage.value = null
    }

    fun refreshStorageSpace() {
        _availableStorageBytes.value = ArchiveInspector.queryAvailableStorageBytes(appContext)
    }

    fun inspectPickedZim(uri: Uri, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isInspecting.value = true
            try {
                try {
                    appContext.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                }

                val memLimit = _draftConfig.value.memoryLimitMb
                val inspection = withContext(Dispatchers.IO) {
                    ArchiveInspector.inspectUri(appContext, uri, memLimit)
                }
                _currentInspection.value = inspection
                refreshStorageSpace()
                onSuccess()
            } catch (e: Throwable) {
                _uiMessage.value = UiMessage(
                    "Não foi possível abrir o arquivo ZIM: ${e.message ?: "formato inválido ou corrompido"}",
                    isError = true
                )
            } finally {
                _isInspecting.value = false
            }
        }
    }

    fun updateDraftConfig(transform: (ConversionConfig) -> ConversionConfig) {
        _draftConfig.value = transform(_draftConfig.value)
    }

    fun saveDraftAsDefault() {
        viewModelScope.launch {
            settingsRepo.saveConfig(_draftConfig.value)
            _uiMessage.value = UiMessage("Configurações salvas como padrão.")
        }
    }

    fun resetConfigToDefaults() {
        _draftConfig.value = ConversionConfig()
        viewModelScope.launch {
            settingsRepo.saveConfig(ConversionConfig())
            _uiMessage.value = UiMessage("Configurações restauradas para os padrões.")
        }
    }

    fun startConversion(onNavigateToProgress: () -> Unit, onCompleted: (String) -> Unit) {
        val inspection = _currentInspection.value ?: return
        val cfg = _draftConfig.value
        activeConversionJob?.cancel()
        activeConversionJob = viewModelScope.launch {
            settingsRepo.saveConfig(cfg)
            onNavigateToProgress()
            try {
                val uri = Uri.parse(inspection.sourceUri)
                val finalProgress = engine.convertFromUri(
                    uri = uri,
                    inspection = inspection,
                    config = cfg,
                    outputRootDir = librariesRootDir,
                    resumeTaskId = null
                )
                refreshStorageSpace()
                if (finalProgress.status == ConversionTaskStatus.COMPLETED) {
                    onCompleted(finalProgress.taskId)
                }
            } catch (e: Throwable) {
                _uiMessage.value = UiMessage(
                    "Falha na conversão: ${e.message ?: "erro inesperado"}",
                    isError = true
                )
            }
        }
    }

    fun resumeTask(task: ConversionTaskEntity, onNavigateToProgress: () -> Unit, onCompleted: (String) -> Unit) {
        activeConversionJob?.cancel()
        activeConversionJob = viewModelScope.launch {
            try {
                val cfg = try {
                    json.decodeFromString<ConversionConfig>(task.configJson)
                } catch (_: Exception) {
                    _draftConfig.value
                }
                val uri = Uri.parse(task.sourceUri)
                val inspection = withContext(Dispatchers.IO) {
                    ArchiveInspector.inspectUri(appContext, uri, cfg.memoryLimitMb)
                }
                _currentInspection.value = inspection
                onNavigateToProgress()
                val finalProgress = engine.convertFromUri(
                    uri = uri,
                    inspection = inspection,
                    config = cfg,
                    outputRootDir = librariesRootDir,
                    resumeTaskId = task.taskId
                )
                refreshStorageSpace()
                if (finalProgress.status == ConversionTaskStatus.COMPLETED) {
                    onCompleted(finalProgress.taskId)
                }
            } catch (e: Throwable) {
                _uiMessage.value = UiMessage(
                    "Não foi possível retomar a tarefa: ${e.message}",
                    isError = true
                )
            }
        }
    }

    fun pauseConversion() {
        engine.requestPause()
    }

    fun cancelConversion() {
        engine.requestCancel()
    }

    fun resumeActiveTaskById(taskId: String, onCompleted: (String) -> Unit) {
        if (taskId.isBlank()) return
        viewModelScope.launch {
            val task = loadTask(taskId)
            if (task != null) {
                resumeTask(task, onNavigateToProgress = {}, onCompleted = onCompleted)
            }
        }
    }

    suspend fun loadTask(taskId: String): ConversionTaskEntity? = withContext(Dispatchers.IO) {
        db.conversionTaskDao().getTaskById(taskId)
    }

    fun parseAudit(task: ConversionTaskEntity?): AuditSummary? {
        val raw = task?.auditSummaryJson ?: return null
        return try {
            json.decodeFromString<AuditSummary>(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun deleteTask(task: ConversionTaskEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            File(task.outputDirPath).deleteRecursively()
            task.zipFilePath?.let { File(it).delete() }
            searchRepo.clearTaskIndex(task.taskId)
            db.conversionTaskDao().deleteCheckpointsForTask(task.taskId)
            db.conversionTaskDao().deleteRedirectsForTask(task.taskId)
            db.conversionTaskDao().deleteTaskById(task.taskId)
            refreshStorageSpace()
            _uiMessage.value = UiMessage("Biblioteca removida.")
        }
    }

    fun exportTaskZipToUri(taskId: String, destUri: Uri, format: ArchivePackageFormat = ArchivePackageFormat.ZIP64) {
        viewModelScope.launch {
            try {
                val task = loadTask(taskId) ?: return@launch
                val dir = File(task.outputDirPath)
                if (!dir.exists() || !dir.isDirectory) {
                    _uiMessage.value = UiMessage("Diretório da biblioteca não encontrado no dispositivo.", isError = true)
                    return@launch
                }
                val bufKb = _draftConfig.value.ioBufferSizeKb
                withContext(Dispatchers.IO) {
                    ExportManager.packageLibraryToUri(appContext, dir, destUri, format, bufKb)
                }
                _uiMessage.value = UiMessage("Pacote ZIP exclusivamente Markdown exportado com sucesso.")
            } catch (e: Throwable) {
                _uiMessage.value = UiMessage("Erro ao exportar ZIP: ${e.message}", isError = true)
            }
        }
    }

    fun shareTaskResult(taskId: String) {
        viewModelScope.launch {
            try {
                val task = loadTask(taskId) ?: return@launch
                val dir = File(task.outputDirPath)
                if (!dir.exists() || !dir.isDirectory) {
                    _uiMessage.value = UiMessage("Diretório da biblioteca não encontrado para compartilhamento.", isError = true)
                    return@launch
                }
                val shareFile = withContext(Dispatchers.IO) {
                    val existingZip = task.zipFilePath?.let { File(it) }?.takeIf { it.exists() }
                    if (existingZip != null) {
                        val cacheExport = File(appContext.cacheDir, "exports").apply { mkdirs() }
                        val target = File(cacheExport, existingZip.name)
                        existingZip.copyTo(target, overwrite = true)
                        target
                    } else {
                        val cacheExport = File(appContext.cacheDir, "exports").apply { mkdirs() }
                        val zipTarget = File(cacheExport, "${dir.name}.zip")
                        ExportManager.packageLibraryAsZip(dir, zipTarget, ArchivePackageFormat.ZIP)
                    }
                }
                val contentUri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    shareFile
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra(Intent.EXTRA_SUBJECT, "Biblioteca Markdown - ${task.libraryTitle}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(intent, "Compartilhar Biblioteca Markdown").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(chooser)
            } catch (e: Throwable) {
                _uiMessage.value = UiMessage("Não foi possível compartilhar: ${e.message}", isError = true)
            }
        }
    }

    // --- Offline Search & Reader helpers ---
    fun runSearch(query: String = _searchQuery.value, taskId: String? = null) {
        _searchQuery.value = query
        viewModelScope.launch {
            _searchCategories.value = searchRepo.getCategories(taskId)
            _searchResults.value = searchRepo.search(
                query = query,
                taskId = taskId,
                categoryFilter = _selectedSearchCategory.value,
                sortMode = _searchSortMode.value
            )
        }
    }

    fun setSearchCategory(cat: String?, taskId: String? = null) {
        _selectedSearchCategory.value = cat
        runSearch(_searchQuery.value, taskId)
    }

    fun setSearchSort(sort: SearchSortMode, taskId: String? = null) {
        _searchSortMode.value = sort
        runSearch(_searchQuery.value, taskId)
    }

    fun rebuildSearchIndex(taskId: String) {
        viewModelScope.launch {
            val task = loadTask(taskId) ?: return@launch
            val idxDir = try {
                json.decodeFromString<ConversionConfig>(task.configJson).customIndexesSubdir
            } catch (_: Exception) {
                "indexes"
            }
            val count = searchRepo.rebuildIndexFromMarkdownDirectory(taskId, File(task.outputDirPath), idxDir)
            _uiMessage.value = UiMessage("Índice offline reconstruído: $count documentos indexados.")
            runSearch(_searchQuery.value, taskId)
        }
    }

    suspend fun resolveRedirectTarget(taskId: String, mdRelPath: String): String? = withContext(Dispatchers.IO) {
        val rec = db.conversionTaskDao().findRedirect(taskId, mdRelPath.substringBefore('#'))
        rec?.markdownTargetPath
    }

    suspend fun readMarkdownDocument(taskId: String, relativePath: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        val task = db.conversionTaskDao().getTaskById(taskId)
            ?: return@withContext ("# Erro\n\nBiblioteca não encontrada." to null)
        val baseDir = File(task.outputDirPath)
        val cleanRel = relativePath.substringBefore('#').trimStart('/')
        return@withContext try {
            val targetFile = ExportManager.resolveConfinedFile(baseDir, cleanRel)
            if (!targetFile.exists()) {
                ("# Documento não encontrado\n\nCaminho: `$cleanRel`" to null)
            } else {
                val text = targetFile.readText()
                val redirectInfo = db.conversionTaskDao().findRedirect(taskId, cleanRel)
                text to redirectInfo?.markdownTargetPath
            }
        } catch (e: Throwable) {
            ("# Caminho inválido\n\nNão foi possível abrir `$cleanRel`: ${e.message ?: "acesso bloqueado"}" to null)
        }
    }

    suspend fun listLibraryEntries(taskId: String, subDir: String = ""): List<LibraryBrowserEntry> = withContext(Dispatchers.IO) {
        val task = db.conversionTaskDao().getTaskById(taskId) ?: return@withContext emptyList()
        val baseDir = File(task.outputDirPath).canonicalFile
        val targetDir = if (subDir.isBlank()) baseDir else File(baseDir, subDir).canonicalFile
        if (!targetDir.path.startsWith(baseDir.path) || !targetDir.exists()) return@withContext emptyList()
        val baseLen = baseDir.path.length + 1
        val children = targetDir.listFiles()?.toList().orEmpty()
        children
            .filter { it.isDirectory || it.extension.equals("md", ignoreCase = true) }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { f ->
                val rel = if (f.canonicalPath.length >= baseLen) {
                    f.canonicalPath.substring(baseLen).replace('\\', '/')
                } else ""
                LibraryBrowserEntry(
                    name = f.name,
                    relativePath = rel,
                    isDirectory = f.isDirectory,
                    sizeBytes = if (f.isFile) f.length() else 0L
                )
            }
    }
}

data class LibraryBrowserEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long
)
