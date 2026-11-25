package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.ConversionTaskEntity
import com.example.model.ArchivePackageFormat
import com.example.model.ConversionTaskStatus
import com.example.search.SearchSortMode
import com.example.ui.screens.ConfigScreen
import com.example.ui.screens.ConversionScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.InspectScreen
import com.example.ui.screens.LibraryBrowserScreen
import com.example.ui.screens.MarkdownReaderScreen
import com.example.ui.screens.ResultScreen
import com.example.ui.screens.SearchScreen
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object InspectRoute
@Serializable data class ConfigRoute(val hasInspection: Boolean = false)
@Serializable object ConversionRoute
@Serializable data class ResultRoute(val taskId: String)
@Serializable data class LibraryBrowserRoute(val taskId: String, val subDir: String = "")
@Serializable data class MarkdownReaderRoute(val taskId: String, val relativePath: String)
@Serializable data class SearchRoute(val taskId: String? = null)
@Serializable object HistoryRoute

private data class TopTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynijiApp(
    viewModel: MainViewModel,
    incomingZimUri: Uri? = null,
    onConsumeIncomingUri: () -> Unit = {}
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val availableStorage by viewModel.availableStorageBytes.collectAsStateWithLifecycle()
    val historyTasks by viewModel.historyTasks.collectAsStateWithLifecycle()
    val currentInspection by viewModel.currentInspection.collectAsStateWithLifecycle()
    val isInspecting by viewModel.isInspecting.collectAsStateWithLifecycle()
    val draftConfig by viewModel.draftConfig.collectAsStateWithLifecycle()
    val conversionProgress by viewModel.conversionProgress.collectAsStateWithLifecycle()
    val uiMessage by viewModel.uiMessage.collectAsStateWithLifecycle()

    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val searchCategories by viewModel.searchCategories.collectAsStateWithLifecycle()
    val selectedSearchCategory by viewModel.selectedSearchCategory.collectAsStateWithLifecycle()
    val searchSortMode by viewModel.searchSortMode.collectAsStateWithLifecycle()

    var pendingZipExportTaskId by remember { mutableStateOf<String?>(null) }

    val pickZimLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.inspectPickedZim(uri) {
                navController.navigate(InspectRoute)
            }
        }
    }

    val pickDestTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            viewModel.updateDraftConfig { it.copy(customDestinationTreeUri = uri.toString()) }
        }
    }

    val createZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val taskId = pendingZipExportTaskId
        pendingZipExportTaskId = null
        if (uri != null && taskId != null) {
            viewModel.exportTaskZipToUri(taskId, uri, ArchivePackageFormat.ZIP64)
        }
    }

    LaunchedEffect(incomingZimUri) {
        val uri = incomingZimUri ?: return@LaunchedEffect
        onConsumeIncomingUri()
        viewModel.inspectPickedZim(uri) {
            navController.navigate(InspectRoute) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(uiMessage) {
        uiMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg.text)
            viewModel.clearMessage()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val latestCompletedTaskId = historyTasks.firstOrNull {
        it.status == ConversionTaskStatus.COMPLETED.name
    }?.taskId

    val tabs = listOf(
        TopTab("Início", Icons.Filled.Home, Icons.Outlined.Home, "nav_home"),
        TopTab("Biblioteca", Icons.Filled.FolderOpen, Icons.Outlined.FolderOpen, "nav_library"),
        TopTab("Pesquisa", Icons.Filled.Search, Icons.Outlined.Search, "nav_search"),
        TopTab("Histórico", Icons.Filled.History, Icons.Outlined.History, "nav_history"),
        TopTab("Ajustes", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
    )

    val selectedTabIndex = when {
        currentDest?.hasRoute<HomeRoute>() == true || currentDest?.hasRoute<InspectRoute>() == true -> 0
        currentDest?.hasRoute<LibraryBrowserRoute>() == true || currentDest?.hasRoute<MarkdownReaderRoute>() == true -> 1
        currentDest?.hasRoute<SearchRoute>() == true -> 2
        currentDest?.hasRoute<HistoryRoute>() == true -> 3
        currentDest?.hasRoute<ConfigRoute>() == true -> 4
        else -> 0
    }

    val navigateTab: (Int) -> Unit = { idx ->
        when (idx) {
            0 -> navController.navigate(HomeRoute) {
                popUpTo<HomeRoute> { inclusive = false }
                launchSingleTop = true
            }
            1 -> {
                if (latestCompletedTaskId != null) {
                    navController.navigate(LibraryBrowserRoute(latestCompletedTaskId)) { launchSingleTop = true }
                } else {
                    navController.navigate(HistoryRoute) { launchSingleTop = true }
                }
            }
            2 -> navController.navigate(SearchRoute(latestCompletedTaskId)) { launchSingleTop = true }
            3 -> navController.navigate(HistoryRoute) { launchSingleTop = true }
            4 -> navController.navigate(ConfigRoute(currentInspection != null)) { launchSingleTop = true }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                val showBackArrow = navController.previousBackStackEntry != null && (
                    currentDest?.hasRoute<InspectRoute>() == true ||
                        currentDest?.hasRoute<ResultRoute>() == true ||
                        currentDest?.hasRoute<MarkdownReaderRoute>() == true ||
                        (currentDest?.hasRoute<ConfigRoute>() == true && currentInspection != null) ||
                        (currentDest?.hasRoute<ConversionRoute>() == true &&
                            conversionProgress.status != ConversionTaskStatus.RUNNING)
                    )
                TopAppBar(
                    navigationIcon = {
                        if (showBackArrow) {
                            IconButton(
                                onClick = { navController.popBackStack() },
                                modifier = Modifier.testTag("top_bar_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Voltar"
                                )
                            }
                        }
                    },
                    title = {
                        Text(
                            text = "Syniji Zim converter .MD",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                if (!isWide) {
                    NavigationBar {
                        tabs.forEachIndexed { idx, tab ->
                            val selected = selectedTabIndex == idx
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTab(idx) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label, maxLines = 1) },
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWide) {
                    NavigationRail {
                        tabs.forEachIndexed { idx, tab ->
                            val selected = selectedTabIndex == idx
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTab(idx) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = HomeRoute
                    ) {
                        composable<HomeRoute> {
                            HomeScreen(
                                availableStorageBytes = availableStorage,
                                isInspecting = isInspecting,
                                recentTasks = historyTasks,
                                onPickZimFile = { pickZimLauncher.launch(arrayOf("*/*")) },
                                onOpenHistory = { navController.navigate(HistoryRoute) },
                                onOpenSettings = { navController.navigate(ConfigRoute(currentInspection != null)) },
                                onResumeTask = { task ->
                                    viewModel.resumeTask(
                                        task = task,
                                        onNavigateToProgress = { navController.navigate(ConversionRoute) },
                                        onCompleted = { doneId ->
                                            navController.navigate(ResultRoute(doneId)) {
                                                popUpTo<ConversionRoute> { inclusive = true }
                                            }
                                        }
                                    )
                                },
                                onOpenResult = { tid -> navController.navigate(ResultRoute(tid)) },
                                onBrowseLibrary = { tid -> navController.navigate(LibraryBrowserRoute(tid)) }
                            )
                        }

                        composable<InspectRoute> {
                            val inspection = currentInspection
                            if (inspection != null) {
                                InspectScreen(
                                    inspection = inspection,
                                    config = draftConfig,
                                    onSelectExportMode = { mode ->
                                        viewModel.updateDraftConfig { it.copy(exportMode = mode) }
                                    },
                                    onOpenConfig = { navController.navigate(ConfigRoute(hasInspection = true)) },
                                    onStartConversion = {
                                        viewModel.startConversion(
                                            onNavigateToProgress = { navController.navigate(ConversionRoute) },
                                            onCompleted = { doneId ->
                                                navController.navigate(ResultRoute(doneId)) {
                                                    popUpTo<ConversionRoute> { inclusive = true }
                                                }
                                            }
                                        )
                                    }
                                )
                            } else {
                                Text("Nenhum arquivo ZIM selecionado.", modifier = Modifier.padding(24.dp))
                            }
                        }

                        composable<ConfigRoute> { entry ->
                            val route = entry.toRoute<ConfigRoute>()
                            ConfigScreen(
                                config = draftConfig,
                                hasActiveInspection = route.hasInspection && currentInspection != null,
                                onUpdate = { transform -> viewModel.updateDraftConfig(transform) },
                                onPickDestinationFolder = { pickDestTreeLauncher.launch(null) },
                                onSaveDefaults = { viewModel.saveDraftAsDefault() },
                                onResetDefaults = { viewModel.resetConfigToDefaults() },
                                onStartConversion = {
                                    viewModel.startConversion(
                                        onNavigateToProgress = { navController.navigate(ConversionRoute) },
                                        onCompleted = { doneId ->
                                            navController.navigate(ResultRoute(doneId)) {
                                                popUpTo<ConversionRoute> { inclusive = true }
                                            }
                                        }
                                    )
                                }
                            )
                        }

                        composable<ConversionRoute> {
                            ConversionScreen(
                                progress = conversionProgress,
                                onPause = { viewModel.pauseConversion() },
                                onResume = {
                                    viewModel.resumeActiveTaskById(conversionProgress.taskId) { doneId ->
                                        navController.navigate(ResultRoute(doneId)) {
                                            popUpTo<ConversionRoute> { inclusive = true }
                                        }
                                    }
                                },
                                onCancel = { viewModel.cancelConversion() },
                                onViewResult = { tid -> navController.navigate(ResultRoute(tid)) },
                                onBackHome = {
                                    navController.navigate(HomeRoute) {
                                        popUpTo(HomeRoute) { inclusive = false }
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }

                        composable<ResultRoute> { entry ->
                            val route = entry.toRoute<ResultRoute>()
                            var task by remember(route.taskId) { mutableStateOf<ConversionTaskEntity?>(null) }
                            LaunchedEffect(route.taskId) {
                                task = viewModel.loadTask(route.taskId)
                            }
                            val audit = remember(task) { viewModel.parseAudit(task) }
                            ResultScreen(
                                task = task,
                                audit = audit,
                                onOpenLibrary = { navController.navigate(LibraryBrowserRoute(route.taskId)) },
                                onViewIndex = { navController.navigate(MarkdownReaderRoute(route.taskId, "INDEX.md")) },
                                onOpenReport = { navController.navigate(MarkdownReaderRoute(route.taskId, "CONVERSION_REPORT.md")) },
                                onSearchDocs = { navController.navigate(SearchRoute(route.taskId)) },
                                onExportZip = {
                                    pendingZipExportTaskId = route.taskId
                                    createZipLauncher.launch("${task?.libraryTitle ?: "biblioteca"}_markdown.zip")
                                },
                                onShareResult = { viewModel.shareTaskResult(route.taskId) },
                                onStartNew = {
                                    navController.navigate(HomeRoute) {
                                        popUpTo(HomeRoute) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable<LibraryBrowserRoute> { entry ->
                            val route = entry.toRoute<LibraryBrowserRoute>()
                            var entries by remember(route.taskId, route.subDir) { mutableStateOf(emptyList<LibraryBrowserEntry>()) }
                            LaunchedEffect(route.taskId, route.subDir) {
                                entries = viewModel.listLibraryEntries(route.taskId, route.subDir)
                            }
                            LibraryBrowserScreen(
                                taskId = route.taskId,
                                currentSubDir = route.subDir,
                                entries = entries,
                                onNavigateSubDir = { sub ->
                                    navController.navigate(LibraryBrowserRoute(route.taskId, sub))
                                },
                                onOpenMarkdownFile = { rel ->
                                    navController.navigate(MarkdownReaderRoute(route.taskId, rel))
                                },
                                onOpenSearch = {
                                    navController.navigate(SearchRoute(route.taskId))
                                },
                                onRebuildSearchIndex = {
                                    viewModel.rebuildSearchIndex(route.taskId)
                                }
                            )
                        }

                        composable<MarkdownReaderRoute> { entry ->
                            val route = entry.toRoute<MarkdownReaderRoute>()
                            var docContent by remember(route.taskId, route.relativePath) { mutableStateOf("Carregando...") }
                            var redirectTarget by remember(route.taskId, route.relativePath) { mutableStateOf<String?>(null) }

                            LaunchedEffect(route.taskId, route.relativePath) {
                                val (text, redir) = viewModel.readMarkdownDocument(route.taskId, route.relativePath)
                                docContent = text
                                redirectTarget = redir
                            }

                            MarkdownReaderScreen(
                                taskId = route.taskId,
                                relativePath = route.relativePath,
                                rawMarkdown = docContent,
                                resolvedRedirectTarget = redirectTarget,
                                onNavigateBack = { navController.popBackStack() },
                                onOpenRelativeMarkdown = { nextRel ->
                                    navController.navigate(MarkdownReaderRoute(route.taskId, nextRel))
                                }
                            )
                        }

                        composable<SearchRoute> { entry ->
                            val route = entry.toRoute<SearchRoute>()
                            var searchAllLibraries by remember(route.taskId) {
                                mutableStateOf(route.taskId == null)
                            }
                            val effectiveTaskId = if (searchAllLibraries) null else route.taskId

                            LaunchedEffect(effectiveTaskId) {
                                viewModel.runSearch(searchQuery, effectiveTaskId)
                            }
                            SearchScreen(
                                query = searchQuery,
                                results = searchResults,
                                categories = searchCategories,
                                selectedCategory = selectedSearchCategory,
                                sortMode = searchSortMode,
                                hasLibraryScope = route.taskId != null,
                                searchAllLibraries = searchAllLibraries,
                                onToggleSearchScope = {
                                    val nextAll = !searchAllLibraries
                                    searchAllLibraries = nextAll
                                    viewModel.runSearch(searchQuery, if (nextAll) null else route.taskId)
                                },
                                onQueryChange = { q -> viewModel.runSearch(q, effectiveTaskId) },
                                onSelectCategory = { cat -> viewModel.setSearchCategory(cat, effectiveTaskId) },
                                onToggleSort = {
                                    val next = if (searchSortMode == SearchSortMode.RELEVANCE) {
                                        SearchSortMode.ALPHABETICAL
                                    } else {
                                        SearchSortMode.RELEVANCE
                                    }
                                    viewModel.setSearchSort(next, effectiveTaskId)
                                },
                                onOpenHit = { hit ->
                                    navController.navigate(MarkdownReaderRoute(hit.taskId, hit.markdownRelPath))
                                }
                            )
                        }

                        composable<HistoryRoute> {
                            HistoryScreen(
                                tasks = historyTasks,
                                onResumeTask = { task ->
                                    viewModel.resumeTask(
                                        task = task,
                                        onNavigateToProgress = { navController.navigate(ConversionRoute) },
                                        onCompleted = { doneId ->
                                            navController.navigate(ResultRoute(doneId)) {
                                                popUpTo<ConversionRoute> { inclusive = true }
                                            }
                                        }
                                    )
                                },
                                onBrowseLibrary = { tid -> navController.navigate(LibraryBrowserRoute(tid)) },
                                onOpenResult = { tid -> navController.navigate(ResultRoute(tid)) },
                                onExportZip = { tid ->
                                    pendingZipExportTaskId = tid
                                    createZipLauncher.launch("biblioteca_markdown.zip")
                                },
                                onDeleteTask = { task -> viewModel.deleteTask(task) }
                            )
                        }
                    }
                }
            }
        }
    }
}
