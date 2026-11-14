package dev.graft.app.ui.screens.editor

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.graft.app.data.AppearancePrefs
import dev.graft.app.ui.components.ScreenTopBar
import dev.graft.app.ui.screens.explorer.ExtensionBadge
import dev.graft.app.ui.theme.code
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.graft.app.App
import dev.graft.app.data.db.entity.RepoEntity
import dev.graft.app.git.GitEngine
import dev.graft.app.git.GitResult
import dev.graft.app.transfer.awaitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Files bigger than this open in a "too large to edit" notice instead of loading into the text field. */
private const val MAX_EDITABLE_BYTES = 2 * 1024 * 1024 // 2 MB

data class EditorUiState(
    val repo: RepoEntity? = null,
    val relativePath: String = "",
    val text: TextFieldValue = TextFieldValue(""),
    val isLoading: Boolean = true,
    val isBinaryOrTooLarge: Boolean = false,
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val showPreview: Boolean = false,
    val findQuery: String = "",
    val findMatches: List<IntRange> = emptyList(),
    val findMatchIndex: Int = -1,
    val findMatchCase: Boolean = false,
)

class FileEditorViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as App
    private val repoRepo = appRef.repoRepository
    private val credRepo = appRef.credentialRepository

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    // Undo/redo history. Kept outside EditorUiState (not just the two booleans
    // that are) since these hold the actual snapshots, which are throwaway
    // playback data rather than state the UI renders directly.
    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()
    private var lastEditAtMs = 0L
    private val maxHistory = 100
    /** Edits within this window of each other are treated as one continuous typing
     *  burst and coalesced into a single undo step — otherwise every keystroke would
     *  push its own entry and undo would feel like "delete one character" instead of
     *  "undo what I just typed." */
    private val coalesceWindowMs = 700L

    fun load(repoId: Long, relativePath: String) {
        undoStack.clear()
        redoStack.clear()
        lastEditAtMs = 0L
        viewModelScope.launch {
            val repo = repoRepo.getById(repoId) ?: return@launch
            _uiState.value = _uiState.value.copy(repo = repo, relativePath = relativePath, isLoading = true)

            val file = File(repo.fullSavePath, relativePath)
            val result = withContext(Dispatchers.IO) {
                if (!file.exists() || file.length() > MAX_EDITABLE_BYTES) return@withContext null
                val bytes = file.readBytes()
                // Crude but effective binary check: a NUL byte essentially never
                // shows up in real text files, but is common in binary formats.
                if (bytes.contains(0)) return@withContext null
                bytes.toString(Charsets.UTF_8)
            }

            if (result == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, isBinaryOrTooLarge = true)
            } else {
                _uiState.value = _uiState.value.copy(
                    text = TextFieldValue(result),
                    isLoading = false,
                    isBinaryOrTooLarge = false,
                )
            }
        }
    }

    fun onTextChanged(newValue: TextFieldValue) {
        val current = _uiState.value.text
        if (newValue.text != current.text) {
            val now = System.currentTimeMillis()
            if (now - lastEditAtMs > coalesceWindowMs) {
                undoStack.addLast(current)
                if (undoStack.size > maxHistory) undoStack.removeFirst()
                redoStack.clear()
            }
            lastEditAtMs = now
            _uiState.value = _uiState.value.copy(
                text = newValue, isDirty = true,
                canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty(),
            )
        } else {
            // Selection/cursor-only change (e.g. tapping around, or "go to line") —
            // not an edit, so it shouldn't touch undo history.
            _uiState.value = _uiState.value.copy(text = newValue)
        }
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_uiState.value.text)
        lastEditAtMs = 0L // next keystroke after an undo starts a fresh checkpoint
        _uiState.value = _uiState.value.copy(
            text = prev, isDirty = true,
            canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty(),
        )
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_uiState.value.text)
        lastEditAtMs = 0L
        _uiState.value = _uiState.value.copy(
            text = next, isDirty = true,
            canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty(),
        )
    }

    /** Moves the cursor without recording an undo step — used by "Go to line." */
    fun moveCursorTo(offset: Int) {
        val current = _uiState.value.text
        _uiState.value = _uiState.value.copy(text = current.copy(selection = TextRange(offset, offset)))
    }

    /** Selects [start] until [end] — used by find-in-file to highlight the current match,
     *  same idea as [moveCursorTo] but a range instead of a bare cursor position. */
    fun selectRange(start: Int, end: Int) {
        val current = _uiState.value.text
        val clampedStart = start.coerceIn(0, current.text.length)
        val clampedEnd = end.coerceIn(0, current.text.length)
        _uiState.value = _uiState.value.copy(text = current.copy(selection = TextRange(clampedStart, clampedEnd)))
    }

    fun togglePreview() {
        _uiState.value = _uiState.value.copy(showPreview = !_uiState.value.showPreview)
    }

    fun selectAll() {
        val current = _uiState.value.text
        _uiState.value = _uiState.value.copy(
            text = current.copy(selection = TextRange(0, current.text.length))
        )
    }

    /** Runs a line tool (duplicate, indent, comment...) as ONE undo step. */
    fun applyEdit(transform: (TextFieldValue) -> TextFieldValue) {
        val current = _uiState.value.text
        val result = transform(current)
        if (result.text == current.text) {
            _uiState.value = _uiState.value.copy(text = result)   // selection-only change
        } else {
            commitDiscreteEdit(result)
        }
    }

    /** @param onSaved runs only if the write succeeded (used by "Save & close"). */
    fun save(onSaved: (() -> Unit)? = null) {
        val repo = _uiState.value.repo ?: return
        val relativePath = _uiState.value.relativePath
        val content = _uiState.value.text.text

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val result = runCatching {
                withContext(Dispatchers.IO) { File(repo.fullSavePath, relativePath).writeText(content) }
            }
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(isSaving = false, isDirty = false, message = "Saved")
                onSaved?.invoke()
            } else {
                // Disk full, file became read-only, folder deleted underneath us... don't crash the editor.
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    message = "Couldn't save: ${result.exceptionOrNull()?.message ?: "unknown error"}",
                )
            }
        }
    }

    /** Save, then stage + commit + push this one file — the quick path from "edited a file" to "pushed it". */
    fun saveCommitAndPush(commitMessage: String) {
        val repo = _uiState.value.repo ?: return
        val relativePath = _uiState.value.relativePath
        val content = _uiState.value.text.text
        val authorName = dev.graft.app.data.GitIdentityPrefs.currentName(appRef)
        val authorEmail = dev.graft.app.data.GitIdentityPrefs.currentEmail(appRef)

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            withContext(Dispatchers.IO) {
                File(repo.fullSavePath, relativePath).writeText(content)
            }

            when (val openResult = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> {
                    _uiState.value = _uiState.value.copy(isSaving = false, isDirty = false, message = "Saved, but couldn't open repo: ${openResult.message}")
                }
                is GitResult.Success -> {
                    val git = openResult.data
                    val stageResult = GitEngine.stageFile(git, relativePath)
                    if (stageResult is GitResult.Error) {
                        _uiState.value = _uiState.value.copy(isSaving = false, isDirty = false, message = "Saved, but staging failed: ${stageResult.message}")
                        git.close()
                        return@launch
                    }

                    val commitResult = GitEngine.commit(git, commitMessage, authorName, authorEmail)
                    if (commitResult is GitResult.Error) {
                        _uiState.value = _uiState.value.copy(isSaving = false, isDirty = false, message = "Saved + staged, but commit failed: ${commitResult.message}")
                        git.close()
                        return@launch
                    }

                    git.close() // the push below opens its own handle
                    val credential = if (repo.credentialId != 0L) credRepo.getById(repo.credentialId) else null
                    val manager = appRef.transferManager
                    val pushOutcome = manager.enqueue(
                        kind = dev.graft.app.transfer.TransferKind.PUSH, repoId = repo.id,
                        title = repo.name, watchDir = File(repo.fullSavePath),
                    ) { reporter ->
                        manager.withGit(repo.fullSavePath) { pg ->
                            GitEngine.push(pg, credential = credential, monitor = reporter.monitor)
                        }
                    }.awaitResult()
                    when (pushOutcome) {
                        is GitResult.Error -> {
                            repoRepo.markError(repo.id, pushOutcome.message)
                            _uiState.value = _uiState.value.copy(isSaving = false, isDirty = false, message = "Committed, but push failed: ${pushOutcome.message}")
                        }
                        is GitResult.Success -> {
                            repoRepo.markSyncSuccess(repo.id)
                            _uiState.value = _uiState.value.copy(isSaving = false, isDirty = false, message = "Saved, committed, and pushed")
                        }
                    }
                }
            }
        }
    }

    /** Find-in-file — distinct from the repo-wide Search screen, which answers "which file has
     *  this" by scanning every file on disk. This only ever looks inside the buffer already
     *  open here, and (unlike repo-wide search) updates live as the text underneath it changes. */
    fun setFindQuery(query: String) {
        val matches = computeMatches(_uiState.value.text.text, query, _uiState.value.findMatchCase)
        _uiState.value = _uiState.value.copy(findQuery = query, findMatches = matches, findMatchIndex = if (matches.isEmpty()) -1 else 0)
    }

    private fun computeMatches(text: String, query: String, matchCase: Boolean): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        return buildList {
            var idx = text.indexOf(query, 0, ignoreCase = !matchCase)
            while (idx >= 0) {
                add(idx until (idx + query.length))
                idx = text.indexOf(query, idx + query.length, ignoreCase = !matchCase)
            }
        }
    }

    fun toggleFindMatchCase() {
        val s = _uiState.value
        val matchCase = !s.findMatchCase
        val matches = computeMatches(s.text.text, s.findQuery, matchCase)
        _uiState.value = s.copy(
            findMatchCase = matchCase, findMatches = matches,
            findMatchIndex = if (matches.isEmpty()) -1 else 0,
        )
    }

    /** One undoable step: a find/replace is a single edit to the user, however many spots it touches. */
    private fun commitDiscreteEdit(newValue: TextFieldValue) {
        undoStack.addLast(_uiState.value.text)
        if (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
        lastEditAtMs = 0L
        _uiState.value = _uiState.value.copy(text = newValue, isDirty = true, canUndo = true, canRedo = false)
    }

    /** Replaces the match the cursor is on, then lands on the next one. */
    fun replaceCurrent(replacement: String) {
        val s = _uiState.value
        val match = s.findMatches.getOrNull(s.findMatchIndex) ?: return
        val text = s.text.text
        val newText = text.substring(0, match.first) + replacement + text.substring(match.last + 1)
        val cursor = match.first + replacement.length
        commitDiscreteEdit(TextFieldValue(newText, TextRange(cursor, cursor)))
        val matches = computeMatches(newText, s.findQuery, s.findMatchCase)
        _uiState.value = _uiState.value.copy(
            findMatches = matches,
            findMatchIndex = if (matches.isEmpty()) -1 else s.findMatchIndex.coerceAtMost(matches.size - 1),
        )
    }

    /** Replaces every match in one go (and one undo step). */
    fun replaceAll(replacement: String) {
        val s = _uiState.value
        if (s.findQuery.isEmpty() || s.findMatches.isEmpty()) return
        val count = s.findMatches.size
        val newText = s.text.text.replace(s.findQuery, replacement, ignoreCase = !s.findMatchCase)
        commitDiscreteEdit(TextFieldValue(newText, TextRange(0, 0)))
        val matches = computeMatches(newText, s.findQuery, s.findMatchCase)
        _uiState.value = _uiState.value.copy(
            findMatches = matches,
            findMatchIndex = if (matches.isEmpty()) -1 else 0,
            message = "Replaced $count occurrence${if (count == 1) "" else "s"}",
        )
    }

    fun findNext() {
        val s = _uiState.value
        if (s.findMatches.isEmpty()) return
        _uiState.value = s.copy(findMatchIndex = (s.findMatchIndex + 1) % s.findMatches.size)
    }

    fun findPrevious() {
        val s = _uiState.value
        if (s.findMatches.isEmpty()) return
        _uiState.value = s.copy(findMatchIndex = (s.findMatchIndex - 1 + s.findMatches.size) % s.findMatches.size)
    }

    fun closeFind() {
        _uiState.value = _uiState.value.copy(findQuery = "", findMatches = emptyList(), findMatchIndex = -1)
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditorScreen(
    repoId: Long,
    relativePath: String,
    onBack: () -> Unit,
    initialLine: Int? = null,
    vm: FileEditorViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsState()
    val snack = remember { SnackbarHostState() }
    var showPushDialog by remember { mutableStateOf(false) }
    var showGoToLine by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    val language = remember(relativePath) { languageForPath(relativePath) }
    val isMarkdown = language == CodeLanguage.MARKDOWN
    val editorScrollState = rememberScrollState()
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    // Captured here (composable scope) rather than read inside the LaunchedEffect blocks
    // below, since currentEditorLineHeight() is itself @Composable and those blocks aren't —
    // this keeps the jump-to-line math in sync with whatever text size Settings has set.
    val lineHeight = currentEditorLineHeight()

    LaunchedEffect(repoId, relativePath) { vm.load(repoId, relativePath) }
    LaunchedEffect(state.message) {
        state.message?.let { snack.showSnackbar(it); vm.dismissMessage() }
    }

    // Arriving from Search's "jump straight to the matched line" — fires once the file
    // has actually finished loading into the text field, so there's real content/line
    // count to scroll against. Guarded on isBinaryOrTooLarge too since a line jump into
    // a file that couldn't be opened as text wouldn't mean anything.
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading && !state.isBinaryOrTooLarge && initialLine != null && initialLine >= 1) {
            val offset = offsetForLineCol(state.text.text, initialLine, 1)
            vm.moveCursorTo(offset)
            val targetPx = with(density) { (lineHeight.toPx() * (initialLine - 1) - 80).coerceAtLeast(0f) }
            editorScrollState.animateScrollTo(targetPx.toInt())
        }
    }

    val (currentLine, currentCol) = remember(state.text.selection.start, state.text.text) {
        lineColForOffset(state.text.text, state.text.selection.start)
    }

    suspend fun scrollToLine(line: Int) {
        val targetPx = with(density) { (lineHeight.toPx() * (line - 1) - 80).coerceAtLeast(0f) }
        editorScrollState.animateScrollTo(targetPx.toInt())
    }

    // Jumps to and selects whichever match is current whenever the find query or the
    // selected match index changes — Next/Previous just move findMatchIndex, this is what
    // actually moves the cursor/scroll position in response.
    LaunchedEffect(state.findMatchIndex, state.findQuery) {
        val match = state.findMatches.getOrNull(state.findMatchIndex) ?: return@LaunchedEffect
        vm.selectRange(match.first, match.last + 1)
        val (line, _) = lineColForOffset(state.text.text, match.first)
        scrollToLine(line)
    }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val wordWrap by AppearancePrefs.editorWordWrap.collectAsState()
    val showLineNumbers by AppearancePrefs.editorLineNumbers.collectAsState()
    val textSize by AppearancePrefs.editorTextSize.collectAsState()
    val widthDp = LocalConfiguration.current.screenWidthDp
    // Three layouts: a phone (everything non-essential in the overflow so the filename gets room),
    // a large phone / small tablet (find joins the bar), and a tablet (push too, Markdown goes split).
    val wide = widthDp >= 600
    val inlineFind = widthDp >= 420
    val canEdit = !state.isBinaryOrTooLarge && !state.isLoading
    val commentPrefix = remember(language) { lineCommentPrefix(language) }
    val fileName = relativePath.substringAfterLast('/')
    var showDiscard by remember { mutableStateOf(false) }
    val lineTotal = remember(state.text.text) { state.text.text.count { it == '\n' } + 1 }

    // Leaving with unsaved edits asks first - the system back gesture, the arrow, all the same.
    BackHandler(enabled = state.isDirty) { showDiscard = true }
    val requestBack: () -> Unit = {
        if (state.isDirty) {
            showDiscard = true
        } else {
            onBack()
        }
    }

    val sizes = AppearancePrefs.EditorTextSize.values()

    Scaffold(
        modifier = Modifier.onPreviewKeyEvent { e ->
            // Hardware-keyboard shortcuts (Chromebooks, tablets with a keyboard, Samsung DeX).
            if (canEdit && e.type == KeyEventType.KeyDown && (e.isCtrlPressed || e.isMetaPressed)) {
                when (e.key) {
                    Key.S -> { if (state.isDirty) vm.save(); true }
                    Key.F -> { showFind = true; true }
                    Key.G -> { showGoToLine = true; true }
                    Key.D -> { vm.applyEdit(::duplicateLine); true }
                    Key.Z -> { if (e.isShiftPressed) vm.redo() else vm.undo(); true }
                    Key.Y -> { vm.redo(); true }
                    Key.Slash -> { commentPrefix?.let { p -> vm.applyEdit { toggleLineComment(it, p) } }; true }
                    else -> false
                }
            } else false
        },
        topBar = {
            Column {
                ScreenTopBar(
                    onBack = { requestBack() },
                    titleContent = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            if (canEdit) {
                                ExtensionBadge(fileName)
                                Spacer(Modifier.width(10.dp))
                            }
                            Column {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text(
                                        fileName,
                                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (state.isDirty) {
                                        Spacer(Modifier.width(8.dp))
                                        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.secondary, androidx.compose.foundation.shape.CircleShape))
                                    }
                                }
                                val folder = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
                                Text(
                                    if (folder.isEmpty()) "repository root" else folder,
                                    style = MaterialTheme.code.tiny,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    actions = {
                        if (canEdit) {
                            if (inlineFind) {
                                IconButton(onClick = { showFind = true }) { Icon(Icons.Filled.Search, "Find in file") }
                            }
                            IconButton(onClick = vm::undo, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                            IconButton(onClick = vm::redo, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                            if (wide) {
                                IconButton(onClick = { showPushDialog = true }, enabled = !state.isSaving) {
                                    Icon(Icons.Filled.ArrowUpward, "Save, commit & push")
                                }
                            }
                            IconButton(onClick = { vm.save() }, enabled = state.isDirty && !state.isSaving) {
                                if (state.isSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Icon(
                                    Icons.Filled.Save, "Save",
                                    tint = if (state.isDirty) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                )
                            }
                            Box {
                                IconButton(onClick = { showOverflow = true }) { Icon(Icons.Filled.MoreVert, "More") }
                                DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                                    if (!wide) {
                                        DropdownMenuItem(
                                            text = { Text("Save, commit & push…") },
                                            leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                                            enabled = !state.isSaving,
                                            onClick = { showOverflow = false; showPushDialog = true },
                                        )
                                    }
                                    if (!inlineFind) {
                                        DropdownMenuItem(
                                            text = { Text("Find & replace") },
                                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                                            onClick = { showOverflow = false; showFind = true },
                                        )
                                    }
                                    if (isMarkdown && !wide) {
                                        DropdownMenuItem(
                                            text = { Text(if (state.showPreview) "Edit" else "Preview") },
                                            leadingIcon = { Icon(if (state.showPreview) Icons.Filled.Edit else Icons.Filled.Visibility, null) },
                                            onClick = { showOverflow = false; vm.togglePreview() },
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Go to line…") },
                                        leadingIcon = { Icon(Icons.Filled.MyLocation, null) },
                                        onClick = { showOverflow = false; showGoToLine = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Select all") },
                                        leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                                        onClick = { showOverflow = false; vm.selectAll() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Copy all") },
                                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                        onClick = {
                                            showOverflow = false
                                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.text.text))
                                            coroutineScope.launch { snack.showSnackbar("Copied $lineTotal lines") }
                                        },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Duplicate line") },
                                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                        onClick = { showOverflow = false; vm.applyEdit(::duplicateLine) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete line") },
                                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                        onClick = { showOverflow = false; vm.applyEdit(::deleteLine) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Indent") },
                                        onClick = { showOverflow = false; vm.applyEdit(::indentLines) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Outdent") },
                                        onClick = { showOverflow = false; vm.applyEdit(::outdentLines) },
                                    )
                                    if (commentPrefix != null) {
                                        DropdownMenuItem(
                                            text = { Text("Toggle comment") },
                                            onClick = { showOverflow = false; vm.applyEdit { toggleLineComment(it, commentPrefix) } },
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Word wrap") },
                                        trailingIcon = { Checkbox(checked = wordWrap, onCheckedChange = null) },
                                        onClick = { AppearancePrefs.setEditorWordWrap(context, !wordWrap) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Line numbers") },
                                        trailingIcon = { Checkbox(checked = showLineNumbers && !wordWrap, onCheckedChange = null) },
                                        enabled = !wordWrap,
                                        onClick = { AppearancePrefs.setEditorLineNumbers(context, !showLineNumbers) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Larger text") },
                                        enabled = textSize.ordinal < sizes.lastIndex,
                                        onClick = { AppearancePrefs.setEditorTextSize(context, sizes[textSize.ordinal + 1]) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Smaller text") },
                                        enabled = textSize.ordinal > 0,
                                        onClick = { AppearancePrefs.setEditorTextSize(context, sizes[textSize.ordinal - 1]) },
                                    )
                                }
                            }
                        }
                    },
                )
                if (showFind) {
                    FindBar(
                        query = state.findQuery,
                        matchCount = state.findMatches.size,
                        matchIndex = state.findMatchIndex,
                        onQueryChange = vm::setFindQuery,
                        matchCase = state.findMatchCase,
                        onToggleMatchCase = vm::toggleFindMatchCase,
                        onReplace = vm::replaceCurrent,
                        onReplaceAll = vm::replaceAll,
                        onNext = vm::findNext,
                        onPrevious = vm::findPrevious,
                        onClose = { showFind = false; vm.closeFind() },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) { d -> Snackbar(d) } },
    ) { pad ->
        val editor: @Composable () -> Unit = {
            CodeEditorField(
                value = state.text,
                onValueChange = vm::onTextChanged,
                language = language,
                verticalScrollState = editorScrollState,
                wordWrap = wordWrap,
                showLineNumbers = showLineNumbers,
                modifier = Modifier.fillMaxSize().imePadding(),
                status = {
                    EditorStatusBar(
                        line = currentLine, col = currentCol,
                        selected = state.text.selection.length,
                        lines = lineTotal, chars = state.text.text.length,
                        language = languageLabel(language), wrap = wordWrap,
                    )
                },
            )
        }
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(androidx.compose.ui.Alignment.Center))
                state.isBinaryOrTooLarge -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Can't open this file here", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "It's either a binary file or larger than 2 MB — this built-in editor is for text/source files.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Room for both: edit on the left, live-rendered preview on the right.
                isMarkdown && wide -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { editor() }
                    VerticalDivider()
                    MarkdownPreview(state.text.text, Modifier.weight(1f).fillMaxHeight())
                }
                isMarkdown && state.showPreview -> MarkdownPreview(state.text.text, Modifier.fillMaxSize())
                else -> editor()
            }
        }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Unsaved changes") },
            text = { Text("Save your changes to $fileName before leaving?") },
            confirmButton = { TextButton(onClick = { showDiscard = false; vm.save(onSaved = onBack) }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDiscard = false; onBack() }) {
                        Text("Discard", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { showDiscard = false }) { Text("Keep editing") }
                }
            },
        )
    }

    if (showGoToLine) {
        var input by remember { mutableStateOf("") }
        val maxLine = remember(state.text.text) { state.text.text.count { it == '\n' } + 1 }
        AlertDialog(
            onDismissRequest = { showGoToLine = false },
            title = { Text("Go to line") },
            text = {
                Column {
                    Text(
                        "Line, or line:column — e.g. \"156\" or \"156:13\" (1\u2013$maxLine)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.filter { c -> c.isDigit() || c == ':' } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parts = input.split(":")
                    val line = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(1, maxLine)
                    val col = parts.getOrNull(1)?.toIntOrNull() ?: 1
                    if (line != null) {
                        val offset = offsetForLineCol(state.text.text, line, col)
                        vm.moveCursorTo(offset)
                        coroutineScope.launch { scrollToLine(line) }
                    }
                    showGoToLine = false
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { showGoToLine = false }) { Text("Cancel") } },
        )
    }

    if (showPushDialog) {
        var commitMessage by remember { mutableStateOf("Update ${relativePath.substringAfterLast('/')}") }
        AlertDialog(
            onDismissRequest = { showPushDialog = false },
            title = { Text("Save, commit & push") },
            text = {
                OutlinedTextField(
                    value = commitMessage,
                    onValueChange = { commitMessage = it },
                    label = { Text("Commit message") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPushDialog = false
                    vm.saveCommitAndPush(commitMessage)
                }) { Text("Push") }
            },
            dismissButton = { TextButton(onClick = { showPushDialog = false }) { Text("Cancel") } },
        )
    }
}

/** Find-in-file bar — sits under the top bar rather than a dialog, since you typically want
 *  to keep tapping Next/Previous while glancing at the highlighted match in the editor below,
 *  which a modal dialog would cover up. */
@Composable
private fun FindBar(
    query: String, matchCount: Int, matchIndex: Int,
    onQueryChange: (String) -> Unit,
    matchCase: Boolean, onToggleMatchCase: () -> Unit,
    onReplace: (String) -> Unit, onReplaceAll: (String) -> Unit,
    onNext: () -> Unit, onPrevious: () -> Unit, onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    var replaceOpen by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf("") }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    placeholder = { Text("Find in file") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onNext = { onNext() }),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (query.isBlank()) "" else if (matchCount == 0) "0/0" else "${matchIndex + 1}/$matchCount",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 36.dp),
                )
                IconButton(onClick = onPrevious, enabled = matchCount > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Previous match") }
                IconButton(onClick = onNext, enabled = matchCount > 0) { Icon(Icons.Filled.KeyboardArrowDown, "Next match") }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close find") }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                FilterChip(selected = matchCase, onClick = onToggleMatchCase, label = { Text("Aa") })
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { replaceOpen = !replaceOpen }) {
                    Text(if (replaceOpen) "Hide replace" else "Replace…")
                }
            }
            if (replaceOpen) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Replace with") },
                        singleLine = true,
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { onReplace(replacement) }, enabled = matchCount > 0) { Text("Replace") }
                    TextButton(onClick = { onReplaceAll(replacement) }, enabled = matchCount > 0) { Text("All") }
                }
            }
        }
    }
}


/** One thin line under the text: where the caret is, how big the file is, what language it's read as. */
@Composable
private fun EditorStatusBar(
    line: Int, col: Int, selected: Int, lines: Int, chars: Int, language: String, wrap: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            "Ln $line, Col $col" + if (selected > 0) "  ($selected selected)" else "",
            style = MaterialTheme.code.tiny,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "$lines lines · $chars chars · UTF-8 · $language" + if (wrap) " · wrap" else "",
            style = MaterialTheme.code.tiny,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
