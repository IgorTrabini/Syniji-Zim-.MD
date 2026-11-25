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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ArchivePackageFormat
import com.example.model.ConversionConfig
import com.example.model.ExportMode
import com.example.model.MarkdownDialect
import com.example.model.MediaMarkerMode
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfigScreen(
    config: ConversionConfig,
    hasActiveInspection: Boolean,
    onUpdate: ((ConversionConfig) -> ConversionConfig) -> Unit,
    onPickDestinationFolder: () -> Unit,
    onSaveDefaults: () -> Unit,
    onResetDefaults: () -> Unit,
    onStartConversion: () -> Unit
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
                    text = "Configurações de Conversão",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Personalize formato, preservação semântica, exclusão de mídias, redirecionamentos, índices e desempenho.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 1. Formato e Modo
            item {
                ConfigSectionCard("1. Formato e Modalidade") {
                    Text("Dialeto Markdown", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = config.dialect == MarkdownDialect.GFM,
                            onClick = { onUpdate { it.copy(dialect = MarkdownDialect.GFM) } },
                            label = { Text("GitHub Flavored (GFM)") }
                        )
                        FilterChip(
                            selected = config.dialect == MarkdownDialect.COMMONMARK,
                            onClick = { onUpdate { it.copy(dialect = MarkdownDialect.COMMONMARK) } },
                            label = { Text("CommonMark") }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Modo de Exportação", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExportMode.entries.forEach { mode ->
                            val label = when (mode) {
                                ExportMode.ORGANIZED_LIBRARY -> "A: Biblioteca"
                                ExportMode.SINGLE_FILE -> "B: MD Único"
                                ExportMode.SPLIT_BY_CATEGORY -> "C: Categorias"
                                ExportMode.OPTIMIZED_MINIMAL -> "D: Otimizado"
                                ExportMode.CUSTOM_SELECTIVE -> "E: Personalizado"
                            }
                            FilterChip(
                                selected = config.exportMode == mode,
                                onClick = { onUpdate { it.copy(exportMode = mode) } },
                                label = { Text(label) }
                            )
                        }
                    }
                    if (config.exportMode == ExportMode.CUSTOM_SELECTIVE) {
                        OutlinedTextField(
                            value = config.selectedNamespaces.joinToString(","),
                            onValueChange = { raw ->
                                val set = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                                onUpdate { it.copy(selectedNamespaces = set) }
                            },
                            label = { Text("Namespaces permitidos (ex: A,C)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = config.selectedPathPrefixes.joinToString(","),
                            onValueChange = { raw ->
                                val set = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                                onUpdate { it.copy(selectedPathPrefixes = set) }
                            },
                            label = { Text("Prefixos de caminho (ex: A/Historia,A/Ciencia)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = config.maxArticlesLimit?.toString().orEmpty(),
                            onValueChange = { raw ->
                                val parsed = raw.filter { it.isDigit() }.toIntOrNull()?.takeIf { it > 0 }
                                onUpdate { it.copy(maxArticlesLimit = parsed) }
                            },
                            label = { Text("Limite máximo de artigos (opcional)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }

            // 2. Conteúdo
            item {
                ConfigSectionCard("2. Conversão de Conteúdo") {
                    ConfigToggle("Converter HTML", config.convertHtml) { v -> onUpdate { it.copy(convertHtml = v) } }
                    ConfigToggle("Converter XHTML", config.convertXhtml) { v -> onUpdate { it.copy(convertXhtml = v) } }
                    ConfigToggle("Converter TXT simples", config.convertPlainText) { v -> onUpdate { it.copy(convertPlainText = v) } }
                    ConfigToggle("Converter XML textual", config.convertXmlText) { v -> onUpdate { it.copy(convertXmlText = v) } }
                    ConfigToggle("Extrair camada textual selecionável de PDFs", config.extractPdfText) { v -> onUpdate { it.copy(extractPdfText = v) } }
                    ConfigToggle("Preservar títulos (H1)", config.preserveHeadings) { v -> onUpdate { it.copy(preserveHeadings = v) } }
                    ConfigToggle("Preservar subtítulos (H2-H6)", config.preserveSubheadings) { v -> onUpdate { it.copy(preserveSubheadings = v) } }
                    ConfigToggle("Preservar notas de rodapé", config.preserveFootnotes) { v -> onUpdate { it.copy(preserveFootnotes = v) } }
                    ConfigToggle("Preservar tabelas", config.preserveTables) { v -> onUpdate { it.copy(preserveTables = v) } }
                    ConfigToggle("Preservar fórmulas matemáticas", config.preserveFormulas) { v -> onUpdate { it.copy(preserveFormulas = v) } }
                    ConfigToggle("Preservar blocos de código", config.preserveCodeBlocks) { v -> onUpdate { it.copy(preserveCodeBlocks = v) } }
                    ConfigToggle("Preservar referências", config.preserveReferences) { v -> onUpdate { it.copy(preserveReferences = v) } }
                    ConfigToggle("Preservar listas e citações", config.preserveLists && config.preserveBlockquotes) { v ->
                        onUpdate { it.copy(preserveLists = v, preserveBlockquotes = v) }
                    }
                }
            }

            // 3. Exclusão de Mídias
            item {
                ConfigSectionCard("3. Exclusão de Mídias (Somente Texto)") {
                    Text(
                        text = "Regra absoluta ativa: fotos, vídeos, áudios, SVG, fontes, CSS e JS nunca são exportados.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    ConfigToggle("Preservar texto ALT de imagens", config.preserveAltText) { v -> onUpdate { it.copy(preserveAltText = v) } }
                    ConfigToggle("Preservar legendas e descrições", config.preserveCaptions && config.preserveTextDescriptions) { v ->
                        onUpdate { it.copy(preserveCaptions = v, preserveTextDescriptions = v) }
                    }
                    ConfigToggle("Preservar transcrições existentes", config.preserveTranscripts) { v -> onUpdate { it.copy(preserveTranscripts = v) } }
                    ConfigToggle("Registrar mídias excluídas em EXCLUDED_CONTENT.md", config.logExcludedMediaInReport) { v ->
                        onUpdate { it.copy(logExcludedMediaInReport = v) }
                    }
                    Text("Estilo de marcador de mídia no artigo", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = config.mediaMarkerMode == MediaMarkerMode.BLOCKQUOTE_WITH_METADATA,
                            onClick = { onUpdate { it.copy(mediaMarkerMode = MediaMarkerMode.BLOCKQUOTE_WITH_METADATA) } },
                            label = { Text("Citação [Mídia não exportada]") }
                        )
                        FilterChip(
                            selected = config.mediaMarkerMode == MediaMarkerMode.REMOVE_COMPLETELY,
                            onClick = { onUpdate { it.copy(mediaMarkerMode = MediaMarkerMode.REMOVE_COMPLETELY) } },
                            label = { Text("Remover marcadores") }
                        )
                    }
                }
            }

            // 4. Organização e Índices
            item {
                ConfigSectionCard("4. Organização e Índices") {
                    ConfigToggle("Preservar hierarquia de diretórios original", config.preserveOriginalHierarchy) { v ->
                        onUpdate { it.copy(preserveOriginalHierarchy = v) }
                    }
                    ConfigToggle("Preservar ordem original das entradas no índice", config.preserveOriginalOrder) { v ->
                        onUpdate { it.copy(preserveOriginalOrder = v) }
                    }
                    ConfigToggle("Preservar caminhos e nomes originais nos metadados", config.preserveOriginalPaths && config.preserveOriginalNamesInMetadata) { v ->
                        onUpdate { it.copy(preserveOriginalPaths = v, preserveOriginalNamesInMetadata = v) }
                    }
                    ConfigToggle("Organizar por categorias identificadas", config.organizeByCategories) { v ->
                        onUpdate { it.copy(organizeByCategories = v) }
                    }
                    ConfigToggle("Gerar HOME.md e INDEX.md", config.generateHomePage && config.generateGeneralIndex) { v ->
                        onUpdate { it.copy(generateHomePage = v, generateGeneralIndex = v) }
                    }
                    ConfigToggle("Gerar índices por título, categoria e caminho", config.generateAlphabeticalIndex && config.generateCategoryIndex && config.generatePathIndex) { v ->
                        onUpdate { it.copy(generateAlphabeticalIndex = v, generateCategoryIndex = v, generatePathIndex = v) }
                    }
                    ConfigToggle("Gerar índice de relacionamentos", config.generateRelationshipIndex) { v ->
                        onUpdate { it.copy(generateRelationshipIndex = v) }
                    }
                }
            }

            // 5. Redirecionamentos e Links
            item {
                ConfigSectionCard("5. Redirecionamentos e Links") {
                    ConfigToggle("Preservar todos os aliases", config.preserveAllAliases) { v -> onUpdate { it.copy(preserveAllAliases = v) } }
                    ConfigToggle("Criar arquivo .md individual para cada alias", config.generateIndividualRedirectFiles) { v ->
                        onUpdate { it.copy(generateIndividualRedirectFiles = v) }
                    }
                    ConfigToggle("Resolver cadeias e detectar ciclos", config.resolveRedirectChains && config.detectRedirectCycles) { v ->
                        onUpdate { it.copy(resolveRedirectChains = v, detectRedirectCycles = v) }
                    }
                    ConfigToggle("Detectar destinos inválidos em aliases", config.detectInvalidTargets) { v ->
                        onUpdate { it.copy(detectInvalidTargets = v) }
                    }
                    ConfigToggle("Gerar relatório REDIRECTS.md", config.generateRedirectsReport) { v ->
                        onUpdate { it.copy(generateRedirectsReport = v) }
                    }
                    ConfigToggle("Converter links internos relativos", config.convertInternalLinks) { v -> onUpdate { it.copy(convertInternalLinks = v) } }
                    ConfigToggle("Preservar links externos e âncoras", config.preserveExternalLinks && config.preserveAnchors) { v ->
                        onUpdate { it.copy(preserveExternalLinks = v, preserveAnchors = v) }
                    }
                    ConfigToggle("Remover destino de links de mídia mantendo o texto", config.stripMediaLinkTargetsKeepText) { v ->
                        onUpdate { it.copy(stripMediaLinkTargetsKeepText = v) }
                    }
                    ConfigToggle("Registrar links quebrados no relatório LINKS.md", config.identifyBrokenLinks) { v ->
                        onUpdate { it.copy(identifyBrokenLinks = v) }
                    }
                }
            }

            // 6. Exportação e Empacotamento
            item {
                ConfigSectionCard("6. Exportação e Destino") {
                    OutlinedTextField(
                        value = config.customLibraryName,
                        onValueChange = { v -> onUpdate { it.copy(customLibraryName = v) } },
                        label = { Text("Nome personalizado da biblioteca (opcional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = config.customArticlesSubdir,
                            onValueChange = { v -> onUpdate { it.copy(customArticlesSubdir = v) } },
                            label = { Text("Subpasta artigos") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = config.customRedirectsSubdir,
                            onValueChange = { v -> onUpdate { it.copy(customRedirectsSubdir = v) } },
                            label = { Text("Subpasta redirects") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    OutlinedTextField(
                        value = config.customIndexesSubdir,
                        onValueChange = { v -> onUpdate { it.copy(customIndexesSubdir = v) } },
                        label = { Text("Subpasta índices") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text("Empacotamento adicional (.md apenas)", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = config.packageFormat == ArchivePackageFormat.FOLDER_ONLY,
                            onClick = { onUpdate { it.copy(packageFormat = ArchivePackageFormat.FOLDER_ONLY) } },
                            label = { Text("Pasta local") }
                        )
                        FilterChip(
                            selected = config.packageFormat == ArchivePackageFormat.ZIP,
                            onClick = { onUpdate { it.copy(packageFormat = ArchivePackageFormat.ZIP) } },
                            label = { Text("ZIP") }
                        )
                        FilterChip(
                            selected = config.packageFormat == ArchivePackageFormat.ZIP64,
                            onClick = { onUpdate { it.copy(packageFormat = ArchivePackageFormat.ZIP64) } },
                            label = { Text("ZIP64") }
                        )
                    }
                    OutlinedButton(
                        onClick = onPickDestinationFolder,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("pick_destination_folder_button")
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (config.customDestinationTreeUri != null) {
                                "Pasta externa vinculada (alterar)"
                            } else {
                                "Vincular pasta de destino (SAF)"
                            }
                        )
                    }
                }
            }

            // 7. Desempenho e Retomada
            item {
                ConfigSectionCard("7. Desempenho e Retomada") {
                    Text("Workers paralelos de conversão: ${config.parallelismWorkers}", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = config.parallelismWorkers.toFloat(),
                        onValueChange = { v -> onUpdate { it.copy(parallelismWorkers = v.roundToInt().coerceIn(1, 8)) } },
                        valueRange = 1f..8f,
                        steps = 6
                    )
                    Text("Limite de cache de memória: ${config.memoryLimitMb} MB", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = config.memoryLimitMb.toFloat(),
                        onValueChange = { v -> onUpdate { it.copy(memoryLimitMb = v.roundToInt()) } },
                        valueRange = 16f..256f
                    )
                    Text("Buffer de I/O em disco: ${config.ioBufferSizeKb} KB", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = config.ioBufferSizeKb.toFloat(),
                        onValueChange = { v -> onUpdate { it.copy(ioBufferSizeKb = v.roundToInt().coerceIn(16, 256)) } },
                        valueRange = 16f..256f
                    )
                    Text("Lote de gravação/checkpoint: ${config.writeBatchSize} artigos", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = config.writeBatchSize.toFloat(),
                        onValueChange = { v -> onUpdate { it.copy(writeBatchSize = v.roundToInt()) } },
                        valueRange = 10f..100f
                    )
                    ConfigToggle("Retomada automática em caso de interrupção", config.autoResumeEnabled) { v ->
                        onUpdate { it.copy(autoResumeEnabled = v) }
                    }
                    ConfigToggle("Executar auditoria de integridade ao concluir", config.runIntegrityAudit) { v ->
                        onUpdate { it.copy(runIntegrityAudit = v) }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onResetDefaults,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("reset_config_button")
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Padrões")
                    }
                    OutlinedButton(
                        onClick = onSaveDefaults,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("save_config_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Salvar")
                    }
                }
                if (hasActiveInspection) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onStartConversion,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("config_start_conversion_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Iniciar Conversão com estas Configurações")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ConfigToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
