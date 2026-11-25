package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ConversionTaskEntity
import com.example.metadata.MetadataManager
import com.example.model.ConversionTaskStatus
import com.example.search.SearchHit
import com.example.search.SearchSortMode
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SearchScreen(
    query: String,
    results: List<SearchHit>,
    categories: List<String>,
    selectedCategory: String?,
    sortMode: SearchSortMode,
    hasLibraryScope: Boolean = false,
    searchAllLibraries: Boolean = false,
    onToggleSearchScope: () -> Unit = {},
    onQueryChange: (String) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onToggleSort: () -> Unit,
    onOpenHit: (SearchHit) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Pesquisa Offline (FTS5)",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${results.size} resultado(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (hasLibraryScope) {
                        FilterChip(
                            selected = !searchAllLibraries,
                            onClick = onToggleSearchScope,
                            label = { Text(if (searchAllLibraries) "Todas" else "Biblioteca atual", maxLines = 1) },
                            modifier = Modifier.testTag("search_scope_chip")
                        )
                    }
                    AssistChip(
                        onClick = onToggleSort,
                        label = { Text(if (sortMode == SearchSortMode.RELEVANCE) "Relevância" else "A–Z") },
                        leadingIcon = { Icon(Icons.Default.SortByAlpha, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Limpar pesquisa")
                        }
                    }
                },
                placeholder = { Text("Título, trecho, alias, caminho ou categoria...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("offline_search_input"),
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            if (categories.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { onSelectCategory(null) },
                        label = { Text("Todas") }
                    )
                    categories.take(20).forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { onSelectCategory(if (selectedCategory == cat) null else cat) },
                            label = { Text(cat) }
                        )
                    }
                }
            }

            if (results.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Text(
                        text = if (query.isBlank()) {
                            "Converta um arquivo ZIM ou digite para pesquisar instantaneamente nos documentos Markdown locais."
                        } else {
                            "Nenhum documento encontrado para \"$query\"."
                        },
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(results, key = { "${it.taskId}:${it.markdownRelPath}" }) { hit ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenHit(hit) }
                                .testTag("search_hit_${hit.title}"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = hit.originalPath,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (hit.aliases.isNotEmpty()) {
                                    Text(
                                        text = "Aliases: ${hit.aliases.take(4).joinToString(", ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                if (hit.snippet.isNotBlank()) {
                                    Text(
                                        text = hit.snippet,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(
    tasks: List<ConversionTaskEntity>,
    onResumeTask: (ConversionTaskEntity) -> Unit,
    onBrowseLibrary: (String) -> Unit,
    onOpenResult: (String) -> Unit,
    onExportZip: (String) -> Unit,
    onDeleteTask: (ConversionTaskEntity) -> Unit
) {
    var pendingDeleteTask by remember { mutableStateOf<ConversionTaskEntity?>(null) }
    val dateFormatter = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    pendingDeleteTask?.let { taskToDelete ->
        AlertDialog(
            onDismissRequest = { pendingDeleteTask = null },
            title = { Text("Excluir biblioteca?") },
            text = {
                Text(
                    "Isso removerá os arquivos Markdown gerados e o índice FTS5 de \"${taskToDelete.libraryTitle}\" do armazenamento interno. O arquivo .zim original não será afetado."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteTask = null
                        onDeleteTask(taskToDelete)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Excluir")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteTask = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Histórico e Tarefas",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (tasks.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Text(
                            text = "Nenhuma tarefa registrada ainda.",
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(tasks, key = { it.taskId }) { task ->
                    val completed = task.status == ConversionTaskStatus.COMPLETED.name
                    val (pillContainer, pillContent, statusLabel) = when (task.status) {
                        ConversionTaskStatus.COMPLETED.name -> Triple(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.onTertiaryContainer,
                            "Concluída"
                        )
                        ConversionTaskStatus.PAUSED.name -> Triple(
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer,
                            "Pausada"
                        )
                        ConversionTaskStatus.RUNNING.name -> Triple(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                            "Em andamento"
                        )
                        ConversionTaskStatus.CANCELLED.name -> Triple(
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.onErrorContainer,
                            "Cancelada"
                        )
                        else -> Triple(
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.onErrorContainer,
                            "Falha"
                        )
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = task.libraryTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${task.sourceFileName} • ${MetadataManager.formatSize(task.sourceSizeBytes)} • ${dateFormatter.format(Date(task.updatedAt))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = pillContainer,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = statusLabel,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = pillContent
                                        )
                                    }
                                    IconButton(onClick = { pendingDeleteTask = task }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Excluir biblioteca")
                                    }
                                }
                            }
                            Text(
                                text = "${task.articlesConverted} artigos • ${task.redirectsCount} redirects • ${task.mediaExcludedCount} mídias excluídas",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!completed) {
                                    OutlinedButton(
                                        onClick = { onResumeTask(task) },
                                        modifier = Modifier.height(40.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Retomar", maxLines = 1)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { onBrowseLibrary(task.taskId) },
                                        modifier = Modifier.height(40.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Abrir", maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = { onOpenResult(task.taskId) },
                                        modifier = Modifier.height(40.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Text("Auditoria", maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = { onExportZip(task.taskId) },
                                        modifier = Modifier.height(40.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("ZIP", maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
