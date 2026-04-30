package com.calmpad.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import android.graphics.pdf.PdfDocument
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.calmpad.data.Converters
import com.calmpad.data.ImageStorage
import com.calmpad.data.NoteEntity
import com.calmpad.data.SectionEntity
import com.calmpad.ui.theme.*
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

// ── Text Formatting Helpers ──

/** Parse content into segments: (isImage, value) pairs. For images, value = filename. */
private fun parseContentSegments(text: String, pattern: Regex): List<Pair<Boolean, String>> {
    val segments = mutableListOf<Pair<Boolean, String>>()
    var lastEnd = 0
    for (match in pattern.findAll(text)) {
        if (match.range.first > lastEnd) {
            segments.add(false to text.substring(lastEnd, match.range.first))
        }
        segments.add(true to match.groupValues[1])
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) {
        segments.add(false to text.substring(lastEnd))
    }
    if (segments.isEmpty()) {
        segments.add(false to "")
    }
    return segments
}

private fun wrapSelection(tfv: TextFieldValue, prefix: String, suffix: String = prefix): TextFieldValue {
    val sel = tfv.selection
    val text = tfv.text
    return if (sel.collapsed) {
        // No selection — insert markers at cursor
        val newText = text.substring(0, sel.start) + prefix + suffix + text.substring(sel.start)
        TextFieldValue(newText, TextRange(sel.start + prefix.length))
    } else {
        // Wrap selected text
        val selected = text.substring(sel.min, sel.max)
        val newText = text.substring(0, sel.min) + prefix + selected + suffix + text.substring(sel.max)
        TextFieldValue(newText, TextRange(sel.min + prefix.length, sel.max + prefix.length))
    }
}

private fun insertAtCursor(tfv: TextFieldValue, insert: String): TextFieldValue {
    val pos = tfv.selection.min
    val newText = tfv.text.substring(0, pos) + insert + tfv.text.substring(pos)
    return TextFieldValue(newText, TextRange(pos + insert.length))
}

private fun prefixLine(tfv: TextFieldValue, prefix: String): TextFieldValue {
    val text = tfv.text
    val pos = tfv.selection.min
    val lineStart = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)) + 1
    val newText = text.substring(0, lineStart) + prefix + text.substring(lineStart)
    return TextFieldValue(newText, TextRange(pos + prefix.length))
}

private fun formattedAnnotatedString(
    rawText: String,
    baseColor: Color,
    highlightColor: Color,
): AnnotatedString {
    val builder = AnnotatedString.Builder(rawText)

    fun hideRange(start: Int, end: Int) {
        if (start < end) {
            builder.addStyle(
                SpanStyle(color = Color.Transparent, fontSize = 0.1.sp),
                start,
                end
            )
        }
    }

    // Headings (# / ##) at line start
    val headingRegex = Regex("(?m)^(##?)\\s+(.+)$")
    headingRegex.findAll(rawText).forEach { match ->
        val prefix = match.groups[1] ?: return@forEach
        val body = match.groups[2] ?: return@forEach
        hideRange(prefix.range.first, body.range.first)
        val fontSize = if (prefix.value == "#") 30.sp else 24.sp
        builder.addStyle(
            SpanStyle(
                fontWeight = FontWeight.Bold,
                fontSize = fontSize,
                color = baseColor
            ),
            body.range.first,
            body.range.last + 1
        )
    }

    // Bold **text**
    Regex("\\*\\*(.+?)\\*\\*").findAll(rawText).forEach { match ->
        val start = match.range.first
        val endExclusive = match.range.last + 1
        val contentStart = start + 2
        val contentEnd = endExclusive - 2
        hideRange(start, contentStart)
        hideRange(contentEnd, endExclusive)
        if (contentStart < contentEnd) {
            builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor), contentStart, contentEnd)
        }
    }

    // Underline __text__
    Regex("__(.+?)__").findAll(rawText).forEach { match ->
        val start = match.range.first
        val endExclusive = match.range.last + 1
        val contentStart = start + 2
        val contentEnd = endExclusive - 2
        hideRange(start, contentStart)
        hideRange(contentEnd, endExclusive)
        if (contentStart < contentEnd) {
            builder.addStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = baseColor), contentStart, contentEnd)
        }
    }

    // Highlight ==text==
    Regex("==(.+?)==").findAll(rawText).forEach { match ->
        val start = match.range.first
        val endExclusive = match.range.last + 1
        val contentStart = start + 2
        val contentEnd = endExclusive - 2
        hideRange(start, contentStart)
        hideRange(contentEnd, endExclusive)
        if (contentStart < contentEnd) {
            builder.addStyle(SpanStyle(background = highlightColor, color = baseColor), contentStart, contentEnd)
        }
    }

    // Italic *text* (avoid **bold** tokens)
    Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)").findAll(rawText).forEach { match ->
        val start = match.range.first
        val endExclusive = match.range.last + 1
        val contentStart = start + 1
        val contentEnd = endExclusive - 1
        hideRange(start, contentStart)
        hideRange(contentEnd, endExclusive)
        if (contentStart < contentEnd) {
            builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor), contentStart, contentEnd)
        }
    }

    return builder.toAnnotatedString()
}

@Composable
fun MainScreen(viewModel: CalmPadViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = appColorScheme(state.theme)
    val context = LocalContext.current
    val activeNote = state.notes.find { it.id == state.activeNoteId }
    val isEditMode = activeNote != null && !state.showSidebar

    LaunchedEffect(isEditMode, state.showToolsPanel) {
        if (!isEditMode && state.showToolsPanel) {
            viewModel.dismissToolsPanel()
        }
    }

    // Back button: tools panel → close sidebar overlay → deselect note → exit
    BackHandler(enabled = state.showToolsPanel || state.activeNoteId != null) {
        when {
            state.showToolsPanel -> viewModel.dismissToolsPanel()
            state.showSidebar -> viewModel.setSidebarVisible(false)
            else -> viewModel.deselectNote()
        }
    }

    // Shared content TextFieldValue state (hoisted here so toolbars can modify it)
    var contentTfv by remember(activeNote?.id) {
        mutableStateOf(TextFieldValue(activeNote?.content ?: ""))
    }
    val updateContent: (TextFieldValue) -> Unit = { newTfv ->
        contentTfv = newTfv
        viewModel.updateNoteContent(newTfv.text)
    }

    // Formatting lambdas
    val applyBold = { updateContent(wrapSelection(contentTfv, "**")) }
    val applyItalic = { updateContent(wrapSelection(contentTfv, "*")) }
    val applyUnderline = { updateContent(wrapSelection(contentTfv, "__")) }
    val applyHighlight = { updateContent(wrapSelection(contentTfv, "==")) }
    val insertCheckbox = { updateContent(insertAtCursor(contentTfv, "☐ ")) }
    val insertBullet = { updateContent(prefixLine(contentTfv, "• ")) }
    val insertH1 = { updateContent(prefixLine(contentTfv, "# ")) }
    val insertH2 = { updateContent(prefixLine(contentTfv, "## ")) }
    val insertTable = {
        updateContent(insertAtCursor(contentTfv, "\n| Header 1 | Header 2 |\n|----------|----------|\n| Data 1   | Data 2   |\n"))
    }

    // File launchers
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch {
                val jsonStr = viewModel.exportBackupJson()
                context.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(jsonStr.toByteArray())
                }
                Toast.makeText(context, "Backup exported", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { input ->
                val jsonStr = input.bufferedReader().readText()
                viewModel.importBackup(jsonStr) { success ->
                    Toast.makeText(
                        context,
                        if (success) "Backup imported successfully" else "Import failed: invalid file",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // Image picker
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val filename = ImageStorage.saveImage(context, it)
            if (filename != null) {
                updateContent(insertAtCursor(contentTfv, "\n{{img:$filename}}\n"))
            }
        }
    }
    val pickImage = { imagePicker.launch("image/*") }

    // Voice typing — uses system SpeechRecognizer (same engine as keyboard mic)
    var isListening by remember { mutableStateOf(false) }
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isListening = false
        val matches = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val transcript = matches?.firstOrNull()
        if (!transcript.isNullOrBlank()) {
            // Insert with a space before if cursor isn't at start of line
            val pos = contentTfv.selection.min
            val text = contentTfv.text
            val prefix = if (pos > 0 && text.getOrNull(pos - 1)?.let { it != '\n' && it != ' ' } == true) " " else ""
            updateContent(insertAtCursor(contentTfv, "$prefix$transcript"))
        }
    }
    val startVoiceTyping = {
        isListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to add text to your note")
        }
        speechLauncher.launch(intent)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .systemBarsPadding()
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            NoteEditorPane(
                state = state,
                colors = colors,
                viewModel = viewModel,
                contentTfv = contentTfv,
                onContentChange = updateContent,
                onMenuClick = { viewModel.toggleSidebar() },
                onExport = {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    exportLauncher.launch("calmpad-backup-$date.json")
                },
                onPrintNote = { printNote(context, viewModel) },
                onPrintNotebook = { printNotebook(context, viewModel) },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Sidebar overlay
        AnimatedVisibility(
            visible = state.showSidebar,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { viewModel.setSidebarVisible(false) }
                )
                SidebarPanel(
                    state = state,
                    colors = colors,
                    viewModel = viewModel,
                    onExport = {
                        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        exportLauncher.launch("calmpad-backup-$date.json")
                    },
                    onImport = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*")) },
                    onPrintNotebook = { printNotebook(context, viewModel) },
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.85f)
                )
            }
        }

        // Mobile bottom toolbar
        if (isEditMode) {
            AnimatedVisibility(
                visible = !state.showToolsPanel,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                BottomToolbar(
                    colors = colors,
                    isListening = isListening,
                    onMenuClick = { viewModel.toggleSidebar() },
                    onBold = applyBold,
                    onCheckbox = insertCheckbox,
                    onImage = pickImage,
                    onList = insertBullet,
                    onVoice = startVoiceTyping,
                    onMore = { viewModel.toggleToolsPanel() }
                )
            }
        }

        // Tools panel (expanded bottom sheet)
        if (isEditMode) {
            AnimatedVisibility(
                visible = state.showToolsPanel,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                ToolsPanel(
                    state = state,
                    colors = colors,
                    onDismiss = { viewModel.dismissToolsPanel() },
                    onH1 = { insertH1(); viewModel.dismissToolsPanel() },
                    onH2 = { insertH2(); viewModel.dismissToolsPanel() },
                    onTable = { insertTable(); viewModel.dismissToolsPanel() },
                    onHighlight = { applyHighlight(); viewModel.dismissToolsPanel() },
                    onItalic = { applyItalic(); viewModel.dismissToolsPanel() },
                    onUnderline = { applyUnderline(); viewModel.dismissToolsPanel() },
                    onFontToggle = {
                        viewModel.setFont(if (state.fontFamily == "sans") "serif" else "sans")
                        viewModel.dismissToolsPanel()
                    },
                    onThemeToggle = { viewModel.cycleTheme() },
                    onPrint = { printNote(context, viewModel); viewModel.dismissToolsPanel() }
                )
            }
        }
    }
}

// ── Sidebar ──

@Composable
private fun SidebarPanel(
    state: CalmPadState,
    colors: CalmPadColorScheme,
    viewModel: CalmPadViewModel,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onPrintNotebook: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showNewSectionDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .background(colors.surface)
            .clickable(enabled = false) {} // prevent click-through
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(CalmPadColors.Purple),
                    contentAlignment = Alignment.Center
                ) {
                    Text("N", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, fontSize = 16.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text("CalmPad", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.onBackground)
            }
            Row {
                IconButton(onClick = { showNewSectionDialog = true }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New Section", tint = colors.onSurface, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { viewModel.createNote() }) {
                    Icon(Icons.Default.Add, contentDescription = "New Page", tint = colors.onSurface, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { viewModel.setSidebarVisible(false) }) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.onSurface, modifier = Modifier.size(20.dp))
                }
            }
        }

        // Search
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            var searchText by remember { mutableStateOf(state.searchQuery) }
            BasicTextField(
                value = searchText,
                onValueChange = { searchText = it; viewModel.updateSearch(it) },
                textStyle = TextStyle(color = colors.onSurface, fontSize = 14.sp),
                singleLine = true,
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    if (searchText.isEmpty()) {
                        Text("Search notes...", color = colors.muted, fontSize = 14.sp)
                    }
                    innerTextField()
                }
            )
        }

        Spacer(Modifier.height(8.dp))

        // Section tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background.copy(alpha = 0.5f))
                .horizontalScroll(rememberScrollState())
                .padding(4.dp),
        ) {
            state.sections.forEach { section ->
                val isActive = section.id == state.activeSectionId
                TextButton(
                    onClick = { viewModel.switchSection(section.id) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (isActive) colors.onBackground else colors.muted
                    ),
                    modifier = Modifier.padding(horizontal = 2.dp)
                ) {
                    Text(
                        section.name,
                        fontSize = 12.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        HorizontalDivider(color = colors.border)

        // Note list
        val orderedNotes = remember(state.notes, state.sections, state.activeSectionId, state.searchQuery) {
            if (state.searchQuery.isNotBlank()) {
                state.notes.sortedByDescending { it.updatedAt }
            } else {
                val section = state.sections.find { it.id == state.activeSectionId }
                if (section != null) {
                    val converters = Converters()
                    val order = converters.parseNoteOrder(section.noteOrder)
                    val orderMap = order.withIndex().associate { (i, id) -> id to i }
                    state.notes
                        .filter { it.sectionId == state.activeSectionId }
                        .sortedWith(compareBy { orderMap[it.id] ?: Int.MAX_VALUE })
                } else {
                    state.notes.sortedByDescending { it.updatedAt }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (orderedNotes.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Empty Section", color = colors.muted, fontSize = 12.sp)
                    }
                }
            }
            itemsIndexed(orderedNotes, key = { _, note -> note.id }) { index, note ->
                NoteListItem(
                    note = note,
                    isActive = note.id == state.activeNoteId,
                    colors = colors,
                    canDrag = state.searchQuery.isBlank(),
                    onSelect = { viewModel.selectNote(note.id) },
                    onDelete = { showDeleteConfirm = note.id },
                    onMoveUp = if (index > 0) {{ viewModel.moveNote(index, index - 1) }} else null,
                    onMoveDown = if (index < orderedNotes.size - 1) {{ viewModel.moveNote(index, index + 1) }} else null,
                )
            }
        }

        // Footer
        HorizontalDivider(color = colors.border)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onExport, contentPadding = PaddingValues(4.dp)) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(12.dp), tint = colors.muted)
                    Spacer(Modifier.width(4.dp))
                    Text("Backup", fontSize = 10.sp, color = colors.muted)
                }
                TextButton(onClick = onImport, contentPadding = PaddingValues(4.dp)) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(12.dp), tint = colors.muted)
                    Spacer(Modifier.width(4.dp))
                    Text("Import", fontSize = 10.sp, color = colors.muted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPrintNotebook, contentPadding = PaddingValues(4.dp)) {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(12.dp), tint = colors.accent)
                    Spacer(Modifier.width(4.dp))
                    Text("PDF Notebook", fontSize = 10.sp, color = colors.accent)
                }
                if (state.activeSectionId != "default") {
                    TextButton(
                        onClick = { showDeleteConfirm = "section:${state.activeSectionId}" },
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text("Del Sec", fontSize = 10.sp, color = Color(0xFFEF4444))
                    }
                }
            }
        }
    }

    // New section dialog
    if (showNewSectionDialog) {
        var sectionName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewSectionDialog = false },
            title = { Text("New Section") },
            text = {
                OutlinedTextField(
                    value = sectionName,
                    onValueChange = { sectionName = it },
                    label = { Text("Section Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (sectionName.isNotBlank()) {
                        viewModel.createSection(sectionName.trim())
                        showNewSectionDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewSectionDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Delete confirmation dialog
    showDeleteConfirm?.let { target ->
        val isSection = target.startsWith("section:")
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(if (isSection) "Delete Section?" else "Delete Note?") },
            text = { Text(if (isSection) "This will delete the section and all its notes." else "This note will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    if (isSection) {
                        viewModel.deleteSection(target.removePrefix("section:"))
                    } else {
                        viewModel.deleteNote(target)
                    }
                    showDeleteConfirm = null
                }) { Text("Delete", color = Color(0xFFEF4444)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
            }
        )
    }
}

// ── Note List Item ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteListItem(
    note: NoteEntity,
    isActive: Boolean,
    colors: CalmPadColorScheme,
    canDrag: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    var showActions by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isActive) colors.background
                else Color.Transparent
            )
            .then(
                if (isActive) Modifier.border(1.dp, colors.border, RoundedCornerShape(8.dp))
                else Modifier
            )
            .combinedClickable(
                onClick = onSelect,
                onLongClick = { if (canDrag) showActions = !showActions }
            )
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (canDrag) {
                    Icon(
                        Icons.Default.DragIndicator,
                        contentDescription = "Drag",
                        tint = colors.muted.copy(alpha = 0.3f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    note.title.ifBlank { "Untitled Page" },
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = if (isActive) colors.onBackground else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isActive) {
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                }
            }
        }
        Text(
            note.content.take(50),
            fontSize = 12.sp,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = if (canDrag) 22.dp else 0.dp, top = 4.dp)
        )

        // Reorder actions
        AnimatedVisibility(visible = showActions && canDrag) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                onMoveUp?.let {
                    OutlinedButton(onClick = { it(); showActions = false }, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Up", fontSize = 11.sp)
                    }
                }
                onMoveDown?.let {
                    OutlinedButton(onClick = { it(); showActions = false }, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Down", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ── Note Editor ──

@Composable
private fun NoteEditorPane(
    state: CalmPadState,
    colors: CalmPadColorScheme,
    viewModel: CalmPadViewModel,
    contentTfv: TextFieldValue,
    onContentChange: (TextFieldValue) -> Unit,
    onMenuClick: () -> Unit,
    onExport: () -> Unit,
    onPrintNote: () -> Unit,
    onPrintNotebook: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeNote = state.notes.find { it.id == state.activeNoteId }
    val context = LocalContext.current
    val fontFamily = when (state.fontFamily) {
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
    val contentVisualTransformation = remember(colors.onSurface, colors.accent) {
        VisualTransformation { original ->
            TransformedText(
                formattedAnnotatedString(
                    rawText = original.text,
                    baseColor = colors.onSurface,
                    highlightColor = colors.accent.copy(alpha = 0.25f)
                ),
                OffsetMapping.Identity
            )
        }
    }

    Column(modifier = modifier) {
        // Status bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = colors.muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val statusIcon = when (state.saveStatus) {
                    SaveStatus.SAVED -> Icons.Default.Check
                    SaveStatus.SAVING -> Icons.Default.Sync
                    SaveStatus.IDLE -> Icons.Default.Remove
                }
                val statusColor = when (state.saveStatus) {
                    SaveStatus.SAVED -> Color(0xFF22C55E)
                    SaveStatus.SAVING -> colors.muted
                    SaveStatus.IDLE -> colors.muted.copy(alpha = 0.5f)
                }
                Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(14.dp))
                Text(
                    state.saveStatus.name.lowercase(),
                    fontSize = 11.sp,
                    color = statusColor,
                    letterSpacing = 1.sp
                )
            }
        }

        if (activeNote != null) {
            // Title
            var titleText by remember(activeNote.id) { mutableStateOf(activeNote.title) }
            BasicTextField(
                value = titleText,
                onValueChange = { titleText = it; viewModel.updateNoteTitle(it) },
                textStyle = TextStyle(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground,
                    fontFamily = fontFamily
                ),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                decorationBox = { innerTextField ->
                    if (titleText.isEmpty()) {
                        Text(
                            "Page Title",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.muted.copy(alpha = 0.4f)
                        )
                    }
                    innerTextField()
                }
            )

            // Content — mixed text + images editor
            val imgPattern = remember { Regex("""\{\{img:(.+?)\}\}""") }
            val segments = remember(contentTfv.text) {
                parseContentSegments(contentTfv.text, imgPattern)
            }
            val hasImages = segments.any { it.first }
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 80.dp)
                    .verticalScroll(scrollState)
            ) {
                if (!hasImages) {
                    // No images — use single editable field for best UX
                    BasicTextField(
                        value = contentTfv,
                        onValueChange = onContentChange,
                        textStyle = TextStyle(
                            fontSize = 18.sp,
                            color = colors.onSurface,
                            lineHeight = 28.sp,
                            fontFamily = fontFamily
                        ),
                        cursorBrush = SolidColor(colors.accent),
                        visualTransformation = contentVisualTransformation,
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            if (contentTfv.text.isEmpty()) {
                                Text(
                                    "Start writing...",
                                    fontSize = 18.sp,
                                    color = colors.muted.copy(alpha = 0.4f)
                                )
                            }
                            innerTextField()
                        }
                    )
                } else {
                    // Mixed content — render text chunks and images
                    segments.forEachIndexed { index, (isImage, value) ->
                        if (isImage) {
                            val imageFile = ImageStorage.getImageFile(context, value)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(imageFile)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Embedded image",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                // Delete button
                                IconButton(
                                    onClick = {
                                        val marker = "{{img:$value}}"
                                        val newText = contentTfv.text.replace("\n$marker\n", "\n")
                                            .replace("\n$marker", "")
                                            .replace("$marker\n", "")
                                            .replace(marker, "")
                                        onContentChange(TextFieldValue(newText, TextRange(newText.length.coerceAtMost(contentTfv.selection.start))))
                                        ImageStorage.deleteImage(context, value)
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(32.dp)
                                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove image",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else {
                            // Editable text segment
                            var segText by remember(contentTfv.text, index) { mutableStateOf(value) }
                            BasicTextField(
                                value = segText,
                                onValueChange = { newSegText ->
                                    segText = newSegText
                                    // Reconstruct full content from segments
                                    val rebuilt = buildString {
                                        segments.forEachIndexed { i, (isImg, v) ->
                                            if (i == index) append(newSegText)
                                            else if (isImg) append("{{img:$v}}")
                                            else append(v)
                                        }
                                    }
                                    onContentChange(TextFieldValue(rebuilt, TextRange(rebuilt.length.coerceAtMost(rebuilt.length))))
                                },
                                textStyle = TextStyle(
                                    fontSize = 18.sp,
                                    color = colors.onSurface,
                                    lineHeight = 28.sp,
                                    fontFamily = fontFamily
                                ),
                                cursorBrush = SolidColor(colors.accent),
                                visualTransformation = contentVisualTransformation,
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { innerTextField ->
                                    if (segText.isEmpty() && index == 0 && segments.size <= 2) {
                                        Text(
                                            "Start writing...",
                                            fontSize = 18.sp,
                                            color = colors.muted.copy(alpha = 0.4f)
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        tint = colors.accent.copy(alpha = 0.3f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No Page Selected", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = colors.muted)
                    Spacer(Modifier.height(8.dp))
                    Text("Select a page from the sidebar or create a new one.", fontSize = 14.sp, color = colors.muted.copy(alpha = 0.6f))
                }
            }
        }
    }
}

// ── Bottom Toolbar ──

@Composable
private fun BottomToolbar(
    colors: CalmPadColorScheme,
    isListening: Boolean,
    onMenuClick: () -> Unit,
    onBold: () -> Unit,
    onCheckbox: () -> Unit,
    onImage: () -> Unit,
    onList: () -> Unit,
    onVoice: () -> Unit,
    onMore: () -> Unit,
) {
    // Pulsing animation for the mic button while listening
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val micAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_alpha"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.background,
        shadowElevation = 8.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, contentDescription = "Sidebar", tint = colors.muted, modifier = Modifier.size(24.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onCheckbox) {
                    Icon(Icons.Default.CheckBox, contentDescription = "Checkbox", tint = colors.accent, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onBold) {
                    Icon(Icons.Default.FormatBold, contentDescription = "Bold", tint = colors.onSurface, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onImage) {
                    Icon(Icons.Default.Image, contentDescription = "Image", tint = colors.onSurface, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onList) {
                    Icon(Icons.Default.List, contentDescription = "List", tint = colors.onSurface, modifier = Modifier.size(22.dp))
                }
                // Mic / voice typing button
                IconButton(
                    onClick = onVoice,
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = if (isListening) colors.accent.copy(alpha = 0.15f) else Color.Transparent,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isListening) "Stop listening" else "Voice type",
                        tint = if (isListening) colors.accent.copy(alpha = micAlpha) else colors.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            IconButton(onClick = onMore) {
                Icon(Icons.Default.MoreVert, contentDescription = "More Tools", tint = colors.muted, modifier = Modifier.size(24.dp))
            }
        }
    }
}

// ── Tools Panel ──

@Composable
private fun ToolsPanel(
    state: CalmPadState,
    colors: CalmPadColorScheme,
    onDismiss: () -> Unit,
    onH1: () -> Unit,
    onH2: () -> Unit,
    onTable: () -> Unit,
    onHighlight: () -> Unit,
    onItalic: () -> Unit,
    onUnderline: () -> Unit,
    onFontToggle: () -> Unit,
    onThemeToggle: () -> Unit,
    onPrint: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.background,
        shadowElevation = 16.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "TOOLS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = colors.muted,
                    letterSpacing = 2.sp
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close", tint = colors.onSurface)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Tool buttons grid (4 columns)
            val tools = listOf(
                Triple("H1", Icons.Default.LooksOne, onH1),
                Triple("H2", Icons.Default.LooksTwo, onH2),
                Triple("Table", Icons.Default.TableChart, onTable),
                Triple("High", Icons.Default.Highlight, onHighlight),
                Triple("Italic", Icons.Default.FormatItalic, onItalic),
                Triple("Under", Icons.Default.FormatUnderlined, onUnderline),
                Triple("Font", Icons.Default.TextFields, onFontToggle),
                Triple("Theme", Icons.Default.WbSunny, onThemeToggle),
                Triple("PDF", Icons.Default.Print, onPrint),
            )

            val rows = tools.chunked(4)
            rows.forEach { rowTools ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowTools.forEach { (label, icon, onClick) ->
                        val isHighlight = label == "High"
                        Surface(
                            onClick = onClick,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1.5f),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isHighlight) Color(0xFFFEFCE8) else colors.surface,
                            contentColor = if (isHighlight) Color(0xFF92400E) else colors.onSurface,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.height(4.dp))
                                Text(label, fontSize = 10.sp)
                            }
                        }
                    }
                    // Fill remaining space if less than 4 items
                    repeat(4 - rowTools.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ── Print Helpers ──

private fun printNote(context: Context, viewModel: CalmPadViewModel) {
    val noteData = viewModel.getPrintContentForNote() ?: return
    val (title, content) = noteData
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    val adapter = TextPrintAdapter(context, title, content)
    printManager.print("CalmPad - $title", adapter, PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setMinMargins(PrintAttributes.Margins(50, 50, 50, 50))
        .build())
}

private fun printNotebook(context: Context, viewModel: CalmPadViewModel) {
    val section = viewModel.getActiveSection() ?: return
    val notes = viewModel.getPrintContentForNotebook()
    if (notes.isEmpty()) return
    val fullContent = notes.joinToString("\n\n━━━━━━━━━━━━━━━━━━━━\n\n") { (title, content) ->
        "▌ $title\n\n$content"
    }
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    val adapter = TextPrintAdapter(context, section.name, fullContent)
    printManager.print("CalmPad - ${section.name}", adapter, PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setMinMargins(PrintAttributes.Margins(50, 50, 50, 50))
        .build())
}

private class TextPrintAdapter(
    private val context: Context,
    private val title: String,
    private val content: String,
) : PrintDocumentAdapter() {

    private val imgPattern = Regex("""\{\{img:(.+?)\}\}""")

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        val info = PrintDocumentInfo.Builder(title)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out android.print.PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        val pdfDoc = PdfDocument()
        val pageWidth = 595 // A4 in points
        val pageHeight = 842
        val margin = 50

        val textPaint = TextPaint().apply {
            textSize = 14f
            isAntiAlias = true
        }

        val titlePaint = TextPaint().apply {
            textSize = 24f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val usableWidth = pageWidth - 2 * margin
        var yPosition = margin.toFloat()
        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = pdfDoc.startPage(pageInfo)
        var canvas = page.canvas

        // Helper to start a new page
        fun newPage() {
            pdfDoc.finishPage(page)
            pageNumber++
            pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            page = pdfDoc.startPage(pageInfo)
            canvas = page.canvas
            yPosition = margin.toFloat()
        }

        // Draw title
        val titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, usableWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.2f)
            .build()
        canvas.save()
        canvas.translate(margin.toFloat(), yPosition)
        titleLayout.draw(canvas)
        canvas.restore()
        yPosition += titleLayout.height + 20

        // Parse content into text and image segments
        val segments = mutableListOf<Pair<Boolean, String>>() // (isImage, value)
        var lastEnd = 0
        for (match in imgPattern.findAll(content)) {
            if (match.range.first > lastEnd) {
                segments.add(false to content.substring(lastEnd, match.range.first))
            }
            segments.add(true to match.groupValues[1])
            lastEnd = match.range.last + 1
        }
        if (lastEnd < content.length) {
            segments.add(false to content.substring(lastEnd))
        }

        for ((isImage, value) in segments) {
            if (isImage) {
                // Draw image
                val imageFile = ImageStorage.getImageFile(context, value)
                if (imageFile.exists()) {
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath, opts)
                    val origW = opts.outWidth
                    val origH = opts.outHeight
                    if (origW > 0 && origH > 0) {
                        // Scale to fit usable width, cap height at half page
                        val scale = (usableWidth.toFloat() / origW).coerceAtMost(1f)
                        val drawW = (origW * scale).toInt()
                        val drawH = (origH * scale).toInt().coerceAtMost((pageHeight - 2 * margin) / 2)

                        // Need new page?
                        if (yPosition + drawH + 16 > pageHeight - margin) {
                            newPage()
                        }

                        // Decode scaled bitmap
                        val sampleSize = (origW / drawW).coerceAtLeast(1)
                        val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath, decodeOpts)
                        if (bitmap != null) {
                            val destRect = android.graphics.RectF(
                                margin.toFloat(),
                                yPosition + 8f,
                                margin.toFloat() + drawW,
                                yPosition + 8f + drawH
                            )
                            canvas.drawBitmap(bitmap, null, destRect, null)
                            bitmap.recycle()
                            yPosition += drawH + 16
                        }
                    }
                }
            } else {
                // Draw text segment line by line
                val textToDraw = value.trimEnd()
                if (textToDraw.isEmpty()) continue
                val segLayout = StaticLayout.Builder.obtain(textToDraw, 0, textToDraw.length, textPaint, usableWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.5f)
                    .build()

                for (i in 0 until segLayout.lineCount) {
                    val lineTop = segLayout.getLineTop(i).toFloat()
                    val lineBottom = segLayout.getLineBottom(i).toFloat()
                    val lineHeight = lineBottom - lineTop

                    if (yPosition + lineHeight > pageHeight - margin) {
                        newPage()
                    }

                    canvas.save()
                    canvas.translate(margin.toFloat(), yPosition - lineTop)
                    // Only draw the current line by clipping
                    canvas.clipRect(0f, lineTop, usableWidth.toFloat(), lineBottom)
                    segLayout.draw(canvas)
                    canvas.restore()
                    yPosition += lineHeight
                }
            }
        }

        pdfDoc.finishPage(page)

        try {
            pdfDoc.writeTo(FileOutputStream(destination.fileDescriptor))
            callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        } finally {
            pdfDoc.close()
        }
    }
}
