package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.metadata.MetadataManager
import com.example.model.ConversionConfig
import com.example.model.ExportMode
import com.example.model.ZimInspection
import com.example.ui.theme.JetBrainsMonoFontFamily

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InspectScreen(
    inspection: ZimInspection,
    config: ConversionConfig,
    onSelectExportMode: (ExportMode) -> Unit,
    onOpenConfig: () -> Unit,
    onStartConversion: () -> Unit
) {
    val spaceTight = inspection.estimatedMarkdownBytes > inspection.availableStorageBytes

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
                    text = "Análise Preliminar do ZIM",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Inspeção somente leitura concluída via ${inspection.engineUsed}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = inspection.metadata.title ?: inspection.fileName,
                            style = MaterialTheme.typography.titleLarge
                        )
                        inspection.metadata.description?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        InspectRow("Arquivo", inspection.fileName)
                        InspectRow("Caminho acessível", inspection.accessiblePath)
                        InspectRow("Tamanho original", MetadataManager.formatSize(inspection.fileSizeBytes))
                        InspectRow("Idioma", inspection.metadata.language ?: "Não especificado")
                        InspectRow("Publicador", inspection.metadata.publisher ?: "Não especificado")
                        InspectRow("Criador", inspection.metadata.creator ?: "Não especificado")
                        InspectRow("Data", inspection.metadata.date ?: "Não especificada")
                        InspectRow("UUID", inspection.metadata.uuid.ifBlank { "Não informado" })
                        InspectRow("Página principal", inspection.metadata.mainPagePath ?: "Não definida")
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Inventário e Integridade", style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StatPill("Entradas", inspection.totalEntries.toString(), Modifier.weight(1f))
                            StatPill("Artigos", inspection.articleCount.toString(), Modifier.weight(1f))
                            StatPill("Redirects", inspection.redirectCount.toString(), Modifier.weight(1f))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StatPill("Textuais", inspection.textualResourceCount.toString(), Modifier.weight(1f))
                            StatPill("Mídias (Excluídas)", inspection.nonTextualResourceCount.toString(), Modifier.weight(1f))
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        InspectRow("Índice de títulos", if (inspection.hasTitleIndex) "Presente" else "Ausente")
                        InspectRow("Índice de pesquisa", if (inspection.hasFulltextIndex) "Presente" else "Ausente")
                        InspectRow("Namespaces", inspection.namespacesFound.joinToString(", ").ifEmpty { "Padrão" })
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (inspection.structuralIntegrityValid) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (inspection.structuralIntegrityValid) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = if (inspection.structuralIntegrityValid) {
                                    "Integridade estrutural verificada"
                                } else {
                                    "Aviso: inconsistência estrutural detectada no cabeçalho/checksum"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (spaceTight) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Estimativa de Saída Textual",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Espaço textual estimado: ~${MetadataManager.formatSize(inspection.estimatedMarkdownBytes)} (estimativa preliminar; o tamanho real depende da densidade de tags HTML).",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Espaço disponível no dispositivo: ${MetadataManager.formatSize(inspection.availableStorageBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Modo de Exportação", style = MaterialTheme.typography.titleMedium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ExportModeChip("A: Biblioteca", ExportMode.ORGANIZED_LIBRARY, config.exportMode, onSelectExportMode)
                            ExportModeChip("B: MD Único", ExportMode.SINGLE_FILE, config.exportMode, onSelectExportMode)
                            ExportModeChip("C: Categorias", ExportMode.SPLIT_BY_CATEGORY, config.exportMode, onSelectExportMode)
                            ExportModeChip("D: Otimizado", ExportMode.OPTIMIZED_MINIMAL, config.exportMode, onSelectExportMode)
                            ExportModeChip("E: Personalizado", ExportMode.CUSTOM_SELECTIVE, config.exportMode, onSelectExportMode)
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenConfig,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("inspect_configure_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Configurar")
                    }
                    Button(
                        onClick = onStartConversion,
                        modifier = Modifier
                            .weight(1.3f)
                            .height(52.dp)
                            .testTag("start_conversion_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Iniciar Conversão")
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportModeChip(
    label: String,
    mode: ExportMode,
    selected: ExportMode,
    onSelect: (ExportMode) -> Unit
) {
    FilterChip(
        selected = selected == mode,
        onClick = { onSelect(mode) },
        label = { Text(label) }
    )
}

@Composable
private fun InspectRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = JetBrainsMonoFontFamily,
            modifier = Modifier.weight(0.58f)
        )
    }
}

@Composable
private fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = JetBrainsMonoFontFamily)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
