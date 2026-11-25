package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ConversionTaskEntity
import com.example.metadata.MetadataManager
import com.example.model.AuditSummary
import com.example.model.ConversionProgress
import com.example.model.ConversionTaskStatus
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.util.Locale

@Composable
fun ConversionScreen(
    progress: ConversionProgress,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onViewResult: (String) -> Unit,
    onBackHome: () -> Unit
) {
    val pct = (progress.progressFraction * 100f).toInt().coerceIn(0, 100)
    val isRunning = progress.status == ConversionTaskStatus.RUNNING || progress.status == ConversionTaskStatus.INSPECTING
    val isPaused = progress.status == ConversionTaskStatus.PAUSED
    val isCompleted = progress.status == ConversionTaskStatus.COMPLETED
    val isFailed = progress.status == ConversionTaskStatus.FAILED

    val statusBadgeColor by animateColorAsState(
        targetValue = when (progress.status) {
            ConversionTaskStatus.RUNNING, ConversionTaskStatus.INSPECTING -> MaterialTheme.colorScheme.primaryContainer
            ConversionTaskStatus.PAUSED -> MaterialTheme.colorScheme.secondaryContainer
            ConversionTaskStatus.COMPLETED -> MaterialTheme.colorScheme.tertiaryContainer
            ConversionTaskStatus.FAILED, ConversionTaskStatus.CANCELLED -> MaterialTheme.colorScheme.errorContainer
        },
        label = "statusBadgeColor"
    )
    val statusBadgeTextColor = when (progress.status) {
        ConversionTaskStatus.RUNNING, ConversionTaskStatus.INSPECTING -> MaterialTheme.colorScheme.onPrimaryContainer
        ConversionTaskStatus.PAUSED -> MaterialTheme.colorScheme.onSecondaryContainer
        ConversionTaskStatus.COMPLETED -> MaterialTheme.colorScheme.onTertiaryContainer
        ConversionTaskStatus.FAILED, ConversionTaskStatus.CANCELLED -> MaterialTheme.colorScheme.onErrorContainer
    }
    val statusLabel = when (progress.status) {
        ConversionTaskStatus.INSPECTING -> "INSPECIONANDO"
        ConversionTaskStatus.RUNNING -> "CONVERTENDO"
        ConversionTaskStatus.PAUSED -> "PAUSADO"
        ConversionTaskStatus.COMPLETED -> "CONCLUÍDO"
        ConversionTaskStatus.FAILED -> "FALHOU"
        ConversionTaskStatus.CANCELLED -> "CANCELADO"
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val isWide = maxWidth >= 600.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 920.dp)
                .testTag("conversion_dashboard_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Dashboard Header & Status Pill
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Painel de Conversão ZIM",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Pipeline offline somente texto (.MD)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        color = statusBadgeColor,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.testTag("conversion_status_badge")
                    ) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = statusBadgeTextColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // 2. 3-Phase Pipeline Stepper
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PhaseStepChip(
                        stepNumber = 1,
                        label = "Inventário",
                        active = progress.phaseIndex == 1 && !isCompleted,
                        done = progress.phaseIndex > 1 || isCompleted,
                        modifier = Modifier.weight(1f)
                    )
                    PhaseStepChip(
                        stepNumber = 2,
                        label = "Conversão",
                        active = progress.phaseIndex == 2 && !isCompleted,
                        done = progress.phaseIndex > 2 || isCompleted,
                        modifier = Modifier.weight(1f)
                    )
                    PhaseStepChip(
                        stepNumber = 3,
                        label = "Índices & ZIP",
                        active = progress.phaseIndex == 3 && !isCompleted,
                        done = isCompleted,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 3. Primary LinearProgressIndicator & Real-Time Article Ticker Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Progresso da Conversão",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${progress.processedEntries} / ${progress.totalEntries} itens",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.testTag("conversion_counts_text")
                                )
                            }
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.displaySmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("conversion_percent_text")
                            )
                        }

                        LinearProgressIndicator(
                            progress = { progress.progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .testTag("conversion_progress_bar"),
                            strokeCap = StrokeCap.Round,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )

                        // Real-time status text + currently parsing article info
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = progress.phaseLabel.ifBlank { "Inicializando leitor ZIM..." },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("conversion_status_text")
                                )
                                if (progress.currentEntryTitle.isNotBlank() || progress.currentEntryPath.isNotBlank()) {
                                    Text(
                                        text = buildString {
                                            if (progress.currentEntryTitle.isNotBlank()) {
                                                append("Artigo atual: ").append(progress.currentEntryTitle)
                                            }
                                            if (progress.currentEntryPath.isNotBlank()) {
                                                if (isNotEmpty()) append(" • ")
                                                append(progress.currentEntryPath)
                                            }
                                            if (progress.currentEntryMime.isNotBlank()) {
                                                append(" [").append(progress.currentEntryMime).append("]")
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("conversion_current_article_text")
                                    )
                                }
                            }
                        }

                        // Elapsed time, throughput, and ETA strip
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Timer,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Decorrido: ${formatDurationShort(progress.elapsedMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val speedStr = if (progress.articlesPerSecond > 0.0) {
                                    String.format(Locale.US, "%.1f art/s", progress.articlesPerSecond)
                                } else {
                                    "— art/s"
                                }
                                val etaStr = progress.estimatedRemainingMs?.let { " • ETA ${formatDurationShort(it)}" }.orEmpty()
                                Text(
                                    text = "$speedStr$etaStr",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (isFailed && !progress.errorMessage.isNullOrBlank()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "Erro na conversão",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = progress.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // 4. Primary Action Controls (visible immediately below progress card)
            item {
                when {
                    isRunning -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onPause,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("pause_conversion_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = "Pausar")
                                Spacer(Modifier.width(6.dp))
                                Text("Pausar")
                            }
                            OutlinedButton(
                                onClick = onCancel,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("cancel_conversion_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Cancel, contentDescription = "Cancelar")
                                Spacer(Modifier.width(6.dp))
                                Text("Cancelar")
                            }
                        }
                    }

                    isPaused -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onResume,
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(50.dp)
                                    .testTag("resume_conversion_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Retomar")
                                Spacer(Modifier.width(6.dp))
                                Text("Retomar Conversão")
                            }
                            OutlinedButton(
                                onClick = onBackHome,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("conversion_back_home_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Início")
                            }
                        }
                    }

                    isCompleted -> {
                        Button(
                            onClick = { onViewResult(progress.taskId) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("conversion_open_result_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Assessment, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Abrir Resultado e Auditoria")
                        }
                    }

                    else -> {
                        OutlinedButton(
                            onClick = onBackHome,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("conversion_back_home_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Voltar ao Início")
                        }
                    }
                }
            }

            // 5. Real-Time KPI Dashboard Grid
            item {
                val tiles = listOf(
                    KpiItem(
                        label = "Artigos convertidos",
                        value = if (progress.totalArticles > 0) "${progress.articlesConverted}/${progress.totalArticles}" else progress.articlesConverted.toString(),
                        icon = Icons.AutoMirrored.Filled.Article
                    ),
                    KpiItem(
                        label = "Redirecionamentos",
                        value = "${progress.redirectsResolved}/${progress.redirectsIdentified}",
                        icon = Icons.AutoMirrored.Filled.AltRoute
                    ),
                    KpiItem(
                        label = "Links válidos / inválidos",
                        value = "${progress.linksConverted} / ${progress.linksInvalid}",
                        icon = Icons.Default.Link
                    ),
                    KpiItem(
                        label = "Mídias bloqueadas",
                        value = "${progress.mediaExcluded} (0 exp.)",
                        icon = Icons.Default.Block
                    ),
                    KpiItem(
                        label = "Arquivos .MD criados",
                        value = progress.markdownFilesCreated.toString(),
                        icon = Icons.Default.Description
                    ),
                    KpiItem(
                        label = "Espaço gravado (.MD)",
                        value = MetadataManager.formatSize(progress.outputBytesWritten),
                        icon = Icons.Default.Storage
                    )
                )

                val columns = if (isWide) 3 else 2
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    tiles.chunked(columns).forEach { rowTiles ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowTiles.forEach { item ->
                                MetricTile(
                                    label = item.label,
                                    value = item.value,
                                    icon = item.icon,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(columns - rowTiles.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // 6. Real-Time Monospace Event Stream
            item {
                Text(
                    text = "Registro de Eventos em Tempo Real",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (progress.recentEvents.isEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Aguardando eventos do motor de conversão...",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            } else {
                items(progress.recentEvents.asReversed()) { ev ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "[${ev.level}] ${ev.message}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = when (ev.level) {
                                "ERROR" -> MaterialTheme.colorScheme.error
                                "WARN" -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

private data class KpiItem(
    val label: String,
    val value: String,
    val icon: ImageVector
)

@Composable
private fun PhaseStepChip(
    stepNumber: Int,
    label: String,
    active: Boolean,
    done: Boolean,
    modifier: Modifier = Modifier
) {
    val containerColor = when {
        done -> MaterialTheme.colorScheme.tertiaryContainer
        active -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = when {
        done -> MaterialTheme.colorScheme.onTertiaryContainer
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier,
        color = containerColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(contentColor.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(13.dp)
                    )
                } else {
                    Text(
                        text = stepNumber.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active || done) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatDurationShort(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return if (mins > 0) {
        String.format(Locale.US, "%dm %02ds", mins, secs)
    } else {
        String.format(Locale.US, "%.1fs", ms / 1000.0)
    }
}

@Composable
fun ResultScreen(
    task: ConversionTaskEntity?,
    audit: AuditSummary?,
    onOpenLibrary: () -> Unit,
    onViewIndex: () -> Unit,
    onOpenReport: () -> Unit,
    onSearchDocs: () -> Unit,
    onExportZip: () -> Unit,
    onShareResult: () -> Unit,
    onStartNew: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "Resultado e Auditoria",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = task?.libraryTitle ?: "Biblioteca Markdown",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (audit != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (audit.integrityPassed) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = if (audit.integrityPassed) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp)
                            )
                            Column {
                                Text(
                                    text = if (audit.integrityPassed) {
                                        "Auditoria Aprovada — 100% Markdown (.md)"
                                    } else {
                                        "Auditoria com Alertas"
                                    },
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${audit.markdownFilesCreated} arquivos .md • ${audit.nonMarkdownFilesInOutput} arquivos não-MD • Original ZIM intocado",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Conversão Textual vs Exclusão de Mídias", style = MaterialTheme.typography.titleMedium)
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            AuditRow("Convertidos com sucesso", audit.textualConvertedSuccess.toString())
                            AuditRow("Convertidos parcialmente", audit.textualConvertedPartial.toString())
                            AuditRow("Mídias excluídas intencionalmente", audit.excludedMediaCount.toString())
                            AuditRow("Redirecionamentos resolvidos", "${audit.redirectsResolved}/${audit.redirectsTotal} (cadeias: ${audit.redirectChainsResolved})")
                            AuditRow("Ciclos detectados", audit.redirectCyclesDetected.toString())
                            AuditRow("Links internos + âncoras", "${audit.internalLinksConverted + audit.anchorsPreserved}")
                            AuditRow("Links de mídia limpos (texto mantido)", audit.mediaLinksStripped.toString())
                            AuditRow("Links externos preservados", audit.externalLinksPreserved.toString())
                            AuditRow("Falhas de leitura/conversão/gravação", "${audit.readFailures + audit.conversionFailures + audit.writeFailures}")
                            AuditRow("Tamanho total da saída textual", MetadataManager.formatSize(audit.outputTotalBytes))
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onOpenLibrary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("result_open_library_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Abrir Biblioteca no Navegador Offline")
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onViewIndex,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("result_view_index_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Ver Índice", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = onOpenReport,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("result_open_report_button")
                        ) {
                            Icon(Icons.Default.Assessment, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Relatório", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onSearchDocs,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("result_search_button")
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pesquisar", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = onExportZip,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("result_export_zip_button")
                        ) {
                            Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Exportar ZIP", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onShareResult,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("result_share_button")
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Compartilhar", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = onStartNew,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("result_new_conversion_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Nova Conversão", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AuditRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMonoFontFamily)
    }
}
