package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.links.LinkResolver
import com.example.markdown.SlugUtils
import com.example.metadata.MetadataManager
import com.example.ui.LibraryBrowserEntry
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Composable
fun LibraryBrowserScreen(
    taskId: String,
    currentSubDir: String,
    entries: List<LibraryBrowserEntry>,
    onNavigateSubDir: (String) -> Unit,
    onOpenMarkdownFile: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onRebuildSearchIndex: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Navegador da Biblioteca",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (currentSubDir.isBlank()) "/ (raiz)" else "/$currentSubDir",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onOpenSearch,
                    modifier = Modifier.testTag("browser_search_button")
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Pesquisar nesta biblioteca")
                }
            }

            val quickDocs = listOf(
                "INDEX.md",
                "HOME.md",
                "REDIRECTS.md",
                "LINKS.md",
                "EXCLUDED_CONTENT.md",
                "CONVERSION_REPORT.md",
                "METADATA.md"
            )
            val existingRootNames = remember(entries, currentSubDir) {
                if (currentSubDir.isBlank()) entries.filter { !it.isDirectory }.map { it.name }.toSet()
                else emptySet()
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (currentSubDir.isNotBlank()) {
                    AssistChip(
                        onClick = {
                            val parent = currentSubDir.substringBeforeLast('/', "")
                            onNavigateSubDir(parent)
                        },
                        label = { Text("Voltar pasta") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
                quickDocs.filter { currentSubDir.isNotBlank() || it in existingRootNames }.forEach { docName ->
                    AssistChip(
                        onClick = { onOpenMarkdownFile(docName) },
                        label = { Text(docName) }
                    )
                }
                AssistChip(
                    onClick = onRebuildSearchIndex,
                    label = { Text("Reconstruir FTS5") },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            if (entries.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Text(
                        text = "Nenhum arquivo Markdown encontrado neste diretório.",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(entries, key = { it.relativePath }) { entry ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (entry.isDirectory) {
                                        onNavigateSubDir(entry.relativePath)
                                    } else {
                                        onOpenMarkdownFile(entry.relativePath)
                                    }
                                }
                                .testTag("lib_entry_${entry.name}"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                    contentDescription = null,
                                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    if (!entry.isDirectory) {
                                        Text(
                                            text = MetadataManager.formatSize(entry.sizeBytes),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
}

@Composable
fun MarkdownReaderScreen(
    taskId: String,
    relativePath: String,
    rawMarkdown: String,
    resolvedRedirectTarget: String?,
    onNavigateBack: () -> Unit,
    onOpenRelativeMarkdown: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showMetadataDialog by remember { mutableStateOf(false) }
    var externalUrlPrompt by remember { mutableStateOf<String?>(null) }
    var showRawSource by remember { mutableStateOf(false) }
    var textScale by remember { mutableFloatStateOf(1.0f) }
    var copiedJustNow by remember { mutableStateOf(false) }

    LaunchedEffect(copiedJustNow) {
        if (copiedJustNow) {
            delay(1800L)
            copiedJustNow = false
        }
    }

    val cleanRelPath = remember(relativePath) { relativePath.substringBefore('#') }
    val requestedAnchor = remember(relativePath) { relativePath.substringAfter('#', "") }
    val (frontMatter, body) = remember(rawMarkdown) { splitFrontMatter(rawMarkdown) }
    val blocks by produceState<List<MdBlock>>(initialValue = emptyList(), key1 = body) {
        value = if (body.length < 32_000) {
            parseMarkdownBlocks(body)
        } else {
            withContext(Dispatchers.Default) { parseMarkdownBlocks(body) }
        }
    }
    val headingIndexMap = remember(blocks) { buildHeadingAnchorIndex(blocks) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(requestedAnchor, headingIndexMap) {
        if (requestedAnchor.isNotBlank()) {
            val targetIdx = resolveHeadingBlockIndex(requestedAnchor, headingIndexMap)
            if (targetIdx != null) {
                listState.scrollToItem(targetIdx)
            }
        }
    }

    // If user opened a redirect/alias document directly, expose an instant offline jump banner
    val redirectBannerTarget = resolvedRedirectTarget
        ?: frontMatter["redirect_target"]?.takeIf { it.isNotBlank() }?.let { rel ->
            val frag = rel.substringAfter('#', "")
            val cleanTarget = rel.substringBefore('#')
            val resolved = LinkResolver.resolveRelativeZimPath(cleanRelPath, cleanTarget)
            if (frag.isNotBlank()) "$resolved#$frag" else resolved
        }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                    Column {
                        Text(
                            text = frontMatter["title"] ?: relativePath.substringAfterLast('/'),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = frontMatter["source_path"] ?: relativePath,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showRawSource = !showRawSource },
                        modifier = Modifier.testTag("reader_toggle_raw_button")
                    ) {
                        Icon(
                            imageVector = if (showRawSource) Icons.AutoMirrored.Filled.Article else Icons.Default.Code,
                            contentDescription = if (showRawSource) "Modo leitura" else "Ver código .md"
                        )
                    }
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(rawMarkdown))
                            copiedJustNow = true
                        },
                        modifier = Modifier.testTag("reader_copy_button")
                    ) {
                        Icon(
                            imageVector = if (copiedJustNow) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Copiar conteúdo Markdown",
                            tint = if (copiedJustNow) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = { showMetadataDialog = true },
                        modifier = Modifier.testTag("reader_metadata_button")
                    ) {
                        Icon(Icons.Default.Info, contentDescription = "Consultar metadados")
                    }
                }
            }

            // Reader comfort & quick controls strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showRawSource) "Código-fonte Markdown (.md)" else "Leitura Offline",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = { textScale = (textScale - 0.1f).coerceAtLeast(0.8f) },
                        label = { Text("A-") }
                    )
                    AssistChip(
                        onClick = { textScale = (textScale + 0.1f).coerceAtMost(1.5f) },
                        label = { Text("A+") }
                    )
                }
            }

            if (redirectBannerTarget != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Alias/Redirecionamento offline disponível",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { onOpenRelativeMarkdown(redirectBannerTarget) },
                            modifier = Modifier.testTag("follow_redirect_button")
                        ) {
                            Text("Abrir destino")
                        }
                    }
                }
            }

            HorizontalDivider()

            if (showRawSource) {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = rawMarkdown,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = (13 * textScale).sp,
                                    lineHeight = (19 * textScale).sp
                                ),
                                fontFamily = JetBrainsMonoFontFamily,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(blocks) { block ->
                            MarkdownBlockView(
                                block = block,
                                currentPath = cleanRelPath,
                                textScale = textScale,
                                onAnchorClick = { anchor ->
                                    val targetIdx = resolveHeadingBlockIndex(anchor, headingIndexMap)
                                    if (targetIdx != null) {
                                        coroutineScope.launch { listState.animateScrollToItem(targetIdx) }
                                    }
                                },
                                onInternalLinkClick = { targetPath ->
                                    onOpenRelativeMarkdown(targetPath)
                                },
                                onExternalLinkClick = { url ->
                                    externalUrlPrompt = url
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showMetadataDialog) {
        AlertDialog(
            onDismissRequest = { showMetadataDialog = false },
            title = { Text("Metadados de Origem") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Arquivo: $relativePath", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMonoFontFamily)
                    HorizontalDivider()
                    if (frontMatter.isEmpty()) {
                        Text("Este documento não possui cabeçalho YAML.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        frontMatter.forEach { (k, v) ->
                            Text("$k: $v", style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMonoFontFamily)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMetadataDialog = false }) { Text("Fechar") }
            }
        )
    }

    externalUrlPrompt?.let { url ->
        AlertDialog(
            onDismissRequest = { externalUrlPrompt = null },
            title = { Text("Link Externo Preservado") },
            text = { Text("Este é um link externo preservado do ZIM original (o app não acessa a internet automaticamente):\n\n$url") },
            confirmButton = {
                TextButton(onClick = {
                    externalUrlPrompt = null
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                    }
                }) { Text("Abrir no navegador") }
            },
            dismissButton = {
                TextButton(onClick = { externalUrlPrompt = null }) { Text("Cancelar") }
            }
        )
    }
}

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class CodeBlock(val lang: String, val code: String) : MdBlock()
    data class ListItem(val bullet: String, val text: String) : MdBlock()
    data class Table(val rows: List<List<String>>) : MdBlock()
    data object Divider : MdBlock()
}

@Composable
private fun MarkdownBlockView(
    block: MdBlock,
    currentPath: String,
    textScale: Float,
    onAnchorClick: (String) -> Unit,
    onInternalLinkClick: (String) -> Unit,
    onExternalLinkClick: (String) -> Unit
) {
    when (block) {
        is MdBlock.Heading -> {
            val baseStyle = when (block.level) {
                1 -> MaterialTheme.typography.headlineMedium
                2 -> MaterialTheme.typography.headlineSmall
                3 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            }
            val style = baseStyle.copy(
                fontSize = baseStyle.fontSize * textScale,
                lineHeight = baseStyle.lineHeight * textScale
            )
            Text(
                text = renderInlineAnnotated(block.text, currentPath, onAnchorClick, onInternalLinkClick, onExternalLinkClick),
                style = style,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        is MdBlock.Paragraph -> {
            val base = MaterialTheme.typography.bodyLarge
            Text(
                text = renderInlineAnnotated(block.text, currentPath, onAnchorClick, onInternalLinkClick, onExternalLinkClick),
                style = base.copy(
                    fontSize = base.fontSize * textScale,
                    lineHeight = base.lineHeight * textScale
                )
            )
        }
        is MdBlock.Quote -> {
            val base = MaterialTheme.typography.bodyMedium
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = renderInlineAnnotated(block.text, currentPath, onAnchorClick, onInternalLinkClick, onExternalLinkClick),
                    style = base.copy(
                        fontSize = base.fontSize * textScale,
                        lineHeight = base.lineHeight * textScale
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        is MdBlock.CodeBlock -> {
            val base = MaterialTheme.typography.bodySmall
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = block.code,
                    style = base.copy(
                        fontSize = base.fontSize * textScale,
                        lineHeight = base.lineHeight * textScale
                    ),
                    fontFamily = JetBrainsMonoFontFamily,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                )
            }
        }
        is MdBlock.ListItem -> {
            val base = MaterialTheme.typography.bodyLarge
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = block.bullet,
                    style = base.copy(fontSize = base.fontSize * textScale),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = renderInlineAnnotated(block.text, currentPath, onAnchorClick, onInternalLinkClick, onExternalLinkClick),
                    style = base.copy(
                        fontSize = base.fontSize * textScale,
                        lineHeight = base.lineHeight * textScale
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        is MdBlock.Table -> {
            val base = MaterialTheme.typography.bodySmall
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                block.rows.forEachIndexed { rowIdx, cols ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        cols.forEach { cell ->
                            Text(
                                text = renderInlineAnnotated(cell, currentPath, onAnchorClick, onInternalLinkClick, onExternalLinkClick),
                                style = base.copy(fontSize = base.fontSize * textScale),
                                fontWeight = if (rowIdx == 0) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .widthIn(min = 90.dp, max = 260.dp)
                                    .padding(vertical = 4.dp)
                            )
                        }
                    }
                    if (rowIdx == 0) HorizontalDivider()
                }
            }
        }
        MdBlock.Divider -> HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
    }
}

@Composable
private fun renderInlineAnnotated(
    raw: String,
    currentPath: String,
    onAnchorClick: (String) -> Unit,
    onInternalLinkClick: (String) -> Unit,
    onExternalLinkClick: (String) -> Unit
): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val footnoteColor = MaterialTheme.colorScheme.secondary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val tokenRegex = Regex("\\[([^\\]]+)]\\(([^)]+)\\)|\\[\\^([^\\]]+)]|\\*\\*([^*]+)\\*\\*|~~([^~]+)~~|\\*([^*]+)\\*|`([^`]+)`")

    return buildAnnotatedString {
        var cursor = 0
        for (match in tokenRegex.findAll(raw)) {
            if (match.range.first > cursor) {
                append(raw.substring(cursor, match.range.first))
            }
            val g = match.groupValues
            when {
                g[1].isNotEmpty() -> {
                    val label = g[1]
                    val rawTarget = g[2].trim()
                    val target = if (rawTarget.contains(" \"")) {
                        rawTarget.substringBefore(" \"").trim()
                    } else if (rawTarget.contains(" '")) {
                        rawTarget.substringBefore(" '").trim()
                    } else {
                        rawTarget
                    }
                    val start = length
                    append(label)
                    addStyle(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium),
                        start,
                        length
                    )
                    addLink(
                        LinkAnnotation.Clickable(tag = target) {
                            if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) {
                                onExternalLinkClick(target)
                            } else if (target.startsWith("#")) {
                                onAnchorClick(target.removePrefix("#"))
                            } else {
                                val rawCleanRel = target.substringBefore('#')
                                val cleanRel = try {
                                    URLDecoder.decode(rawCleanRel, StandardCharsets.UTF_8.name())
                                } catch (_: Exception) {
                                    rawCleanRel
                                }
                                val fragment = target.substringAfter('#', "")
                                val resolved = LinkResolver.resolveRelativeZimPath(currentPath, cleanRel)
                                val withFragment = if (fragment.isNotBlank()) "$resolved#$fragment" else resolved
                                onInternalLinkClick(withFragment)
                            }
                        },
                        start,
                        length
                    )
                }
                g[3].isNotEmpty() -> {
                    val start = length
                    append("[${g[3]}]")
                    addStyle(SpanStyle(color = footnoteColor, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace), start, length)
                }
                g[4].isNotEmpty() -> {
                    val start = length
                    append(g[4])
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                }
                g[5].isNotEmpty() -> {
                    val start = length
                    append(g[5])
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, length)
                }
                g[6].isNotEmpty() -> {
                    val start = length
                    append(g[6])
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                }
                g[7].isNotEmpty() -> {
                    val start = length
                    append(g[7])
                    addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg), start, length)
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) {
            append(raw.substring(cursor))
        }
    }
}

private fun splitFrontMatter(md: String): Pair<Map<String, String>, String> {
    if (!md.startsWith("---\n")) return emptyMap<String, String>() to md
    val endIdx = md.indexOf("\n---\n", startIndex = 4)
    if (endIdx < 0) return emptyMap<String, String>() to md
    val header = md.substring(4, endIdx)
    val body = md.substring(endIdx + 5).trimStart()
    val map = LinkedHashMap<String, String>()
    for (line in header.lines()) {
        val colon = line.indexOf(':')
        if (colon > 0) {
            map[line.substring(0, colon).trim()] = line.substring(colon + 1).trim().removeSurrounding("\"")
        }
    }
    return map to body
}

private fun parseMarkdownBlocks(body: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = body.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> i++
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++
                out.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            }
            trimmed == "---" || trimmed == "***" -> {
                out.add(MdBlock.Divider)
                i++
            }
            trimmed.startsWith("#") && trimmed.indexOf(' ') in 1..6 -> {
                val spaceIdx = trimmed.indexOf(' ')
                out.add(MdBlock.Heading(spaceIdx, trimmed.substring(spaceIdx + 1).trim()))
                i++
            }
            trimmed.startsWith(">") -> {
                val qLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    qLines.add(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                out.add(MdBlock.Quote(qLines.joinToString("\n")))
            }
            trimmed.startsWith("|") && trimmed.endsWith("|") -> {
                val tableRows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val rowLine = lines[i].trim().trim('|')
                    val cells = rowLine.split('|').map { it.trim() }
                    val isSeparator = cells.all { it.matches(Regex(":?-{2,}:?")) }
                    if (!isSeparator) tableRows.add(cells)
                    i++
                }
                if (tableRows.isNotEmpty()) out.add(MdBlock.Table(tableRows))
            }
            trimmed.startsWith("- [ ] ") -> {
                out.add(MdBlock.ListItem("☐", trimmed.substring(6).trim()))
                i++
            }
            trimmed.startsWith("- [x] ", ignoreCase = true) -> {
                out.add(MdBlock.ListItem("☑", trimmed.substring(6).trim()))
                i++
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                out.add(MdBlock.ListItem("•", trimmed.substring(2).trim()))
                i++
            }
            trimmed.matches(Regex("^\\d+\\.\\s+.*")) -> {
                val dotIdx = trimmed.indexOf('.')
                out.add(MdBlock.ListItem(trimmed.substring(0, dotIdx + 1), trimmed.substring(dotIdx + 1).trim()))
                i++
            }
            else -> {
                val pLines = mutableListOf<String>()
                while (i < lines.size) {
                    val l = lines[i].trim()
                    if (l.isEmpty() || l.startsWith("#") || l.startsWith("```") || l.startsWith(">") ||
                        l.startsWith("- ") || l.startsWith("* ") || l.startsWith("|") || l == "---"
                    ) break
                    pLines.add(l)
                    i++
                }
                out.add(MdBlock.Paragraph(pLines.joinToString(" ")))
            }
        }
    }
    return out
}

private fun buildHeadingAnchorIndex(blocks: List<MdBlock>): Map<String, Int> {
    val index = LinkedHashMap<String, Int>()
    val seen = HashMap<String, Int>()
    blocks.forEachIndexed { idx, block ->
        if (block is MdBlock.Heading) {
            val base = SlugUtils.markdownAnchorSlug(block.text)
            val count = seen[base] ?: 0
            seen[base] = count + 1
            val key = if (count == 0) base else "$base-$count"
            index[key] = idx
        }
    }
    return index
}

private fun resolveHeadingBlockIndex(anchor: String, headingIndexMap: Map<String, Int>): Int? {
    val clean = anchor.trim().removePrefix("#").lowercase()
    return headingIndexMap[clean]
        ?: headingIndexMap[SlugUtils.markdownAnchorSlug(clean)]
}
