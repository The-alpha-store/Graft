package dev.graft.app.ui.screens.console

import android.app.Application
import dev.graft.app.ui.theme.gitColors
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import dev.graft.app.ui.components.ScreenTopBar
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.graft.app.App
import dev.graft.app.data.GitIdentityPrefs
import dev.graft.app.data.ConsolePrefs
import dev.graft.app.data.github.WorkflowTemplateCatalog
import dev.graft.app.data.github.githubFullNameFromUrl
import dev.graft.app.git.CloudRunner
import dev.graft.app.git.ConsoleRouter
import dev.graft.app.git.ShellResult
import dev.graft.app.git.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import dev.graft.app.git.GitConsoleInterpreter
import dev.graft.app.git.GitEngine
import dev.graft.app.git.GitResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConsoleLine(
    val command: String,
    val output: String,
    val isError: Boolean,
    /** True while a long-running command (shell / cloud) is still producing its status text. */
    val isLive: Boolean = false,
)

/** A command waiting for the person's yes/no before it goes to the cloud. */
data class PendingCloud(val rawInput: String, val command: String, val commit: Boolean, val reason: String?, val warning: String)

data class ConsoleUiState(
    val repoName: String = "",
    val history: List<ConsoleLine> = emptyList(),
    val commandHistory: List<String> = emptyList(),
    val isRunning: Boolean = false,
    /** Shell and cloud runs can be stopped; a git command can't be interrupted mid-JGit-call. */
    val canStop: Boolean = false,
    val pendingDangerCommand: String? = null,
    val pendingDangerWarning: String? = null,
    val pendingCloud: PendingCloud? = null,
)

/**
 * One prompt for everything. [ConsoleRouter] decides where a line goes:
 *
 * - **git** commands run on the in-app git engine ([GitConsoleInterpreter] / [GitConsoleExtras]);
 * - anything else is tried on the **device shell** ([ShellRunner]) in the repo folder;
 * - if the device doesn't have the program (`cargo`, `npm`, `gradle`...), it offers once to run the command
 *   on a **GitHub Actions runner** ([CloudRunner]) and brings the output - and any files it changed - back.
 */
class GitConsoleViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as App
    private val repoRepo = appRef.repoRepository
    private val credRepo = appRef.credentialRepository

    private val _state = MutableStateFlow(ConsoleUiState())
    val state: StateFlow<ConsoleUiState> = _state.asStateFlow()

    private var repoId: Long = -1
    private var runJob: Job? = null

    fun load(id: Long) {
        repoId = id
        viewModelScope.launch {
            val repo = repoRepo.getById(id) ?: return@launch
            _state.value = _state.value.copy(repoName = repo.name)
        }
    }

    /** First pass: classify, and confirm anything destructive before it runs. */
    fun submit(rawCommand: String) {
        if (rawCommand.isBlank() || _state.value.isRunning) return
        val route = ConsoleRouter.classify(rawCommand)
        val warning = when (route) {
            is ConsoleRouter.Route.Git -> GitConsoleInterpreter.dangerWarning(rawCommand)
            is ConsoleRouter.Route.Shell -> ConsoleRouter.shellDanger(route.raw)
            else -> null
        }
        if (warning != null) {
            _state.value = _state.value.copy(pendingDangerCommand = rawCommand, pendingDangerWarning = warning)
        } else {
            dispatch(rawCommand, route)
        }
    }

    fun confirmPendingDanger() {
        val cmd = _state.value.pendingDangerCommand ?: return
        _state.value = _state.value.copy(pendingDangerCommand = null, pendingDangerWarning = null)
        dispatch(cmd, ConsoleRouter.classify(cmd))
    }

    fun dismissPendingDanger() {
        _state.value = _state.value.copy(pendingDangerCommand = null, pendingDangerWarning = null)
    }

    private fun dispatch(raw: String, route: ConsoleRouter.Route) {
        _state.value = _state.value.copy(commandHistory = _state.value.commandHistory + raw)
        when (route) {
            ConsoleRouter.Route.Clear -> clear()
            is ConsoleRouter.Route.Git -> runGit(raw)
            is ConsoleRouter.Route.Shell -> runShell(raw, route.raw)
            is ConsoleRouter.Route.Cloud -> prepareCloud(raw, route.command, route.commit, reason = null)
            ConsoleRouter.Route.CloudSetup -> runCloudSetup(raw)
        }
    }

    fun stop() { runJob?.cancel() }

    // ------------------------------------------------------------------------------------- git

    private fun runGit(rawCommand: String) {
        runJob = viewModelScope.launch {
            _state.value = _state.value.copy(isRunning = true)
            val repo = repoRepo.getById(repoId)
            if (repo == null) {
                appendLine(rawCommand, "error: repo not found", true)
                _state.value = _state.value.copy(isRunning = false)
                return@launch
            }
            when (val opened = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> appendLine(rawCommand, "error: ${opened.message}", true)
                is GitResult.Success -> {
                    val git = opened.data
                    val credential = if (repo.credentialId != 0L) credRepo.getById(repo.credentialId) else null
                    val name = GitIdentityPrefs.currentName(appRef)
                    val email = GitIdentityPrefs.currentEmail(appRef)
                    val output = GitConsoleInterpreter.run(git, credential, name, email, rawCommand)
                    git.close()
                    appendLine(rawCommand, output, output.startsWith("error") || output.startsWith("fatal"))
                }
            }
            _state.value = _state.value.copy(isRunning = false)
        }
    }

    // ------------------------------------------------------------------------------------ shell

    private fun runShell(rawInput: String, command: String) {
        runJob = viewModelScope.launch {
            _state.value = _state.value.copy(isRunning = true, canStop = true)
            try {
                val repo = repoRepo.getById(repoId)
                if (repo == null) {
                    appendLine(rawInput, "error: repo not found", true)
                    return@launch
                }
                val result = ShellRunner.run(command, File(repo.fullSavePath))
                when {
                    result.commandNotFound -> {
                        val reason = "`${ConsoleRouter.programOf(command)}` isn't available on this device."
                        if (ConsolePrefs.autoCloud(appRef)) {
                            _state.value = _state.value.copy(isRunning = false, canStop = false)
                            prepareCloud(rawInput, command, commit = true, reason = reason, skipConfirm = true)
                        } else {
                            _state.value = _state.value.copy(isRunning = false, canStop = false)
                            prepareCloud(rawInput, command, commit = true, reason = reason)
                        }
                    }
                    else -> appendLine(rawInput, formatShell(result), isError = !result.succeeded)
                }
            } catch (e: CancellationException) {
                appendLine(rawInput, "^C", true)
                throw e
            } finally {
                _state.value = _state.value.copy(isRunning = false, canStop = false)
            }
        }
    }

    private fun formatShell(r: ShellResult): String {
        val body = r.output.ifBlank { if (r.succeeded) "(no output)" else "" }
        val tail = when {
            r.timedOut -> "\n[timed out after 120 s]"
            r.exitCode != 0 -> "\n[exit ${r.exitCode}]"
            else -> ""
        }
        return (body + tail).trimStart('\n')
    }

    // ------------------------------------------------------------------------------------ cloud

    /**
     * Checks everything the cloud run needs (a GitHub remote, a token, the runner workflow in the repo),
     * works out what the runner *won't* see (unpushed / uncommitted work), then asks - once.
     */
    private fun prepareCloud(raw: String, command: String, commit: Boolean, reason: String?, skipConfirm: Boolean = false) {
        if (command.isBlank()) {
            appendLine(raw, "usage: cloud <command>   e.g. cloud cargo fmt --all", true)
            return
        }
        viewModelScope.launch {
            val repo = repoRepo.getById(repoId)
            if (repo == null) { appendLine(raw, "error: repo not found", true); return@launch }
            val fullName = githubFullNameFromUrl(repo.cloneUrl)
            if (fullName == null) {
                appendLine(raw, (reason?.plus(" ") ?: "") + "Running it in the cloud needs a GitHub remote, and this repo's isn't one.", true)
                return@launch
            }
            val credential = if (repo.credentialId != 0L) credRepo.getById(repo.credentialId) else null
            if (credential == null) {
                appendLine(raw, (reason?.plus(" ") ?: "") + "To run it in the cloud, attach a credential with a GitHub token to this repo (Settings → Credentials).", true)
                return@launch
            }
            if (!File(repo.fullSavePath, CloudRunner.WORKFLOW_PATH).exists()) {
                appendLine(raw, (reason?.plus(" ") ?: "") + "To run it in the cloud, install the runner once: type `cloud setup`, then add, commit and push the new file.", true)
                return@launch
            }

            // What will the runner NOT see? It checks out what's pushed.
            var ahead = 0
            var dirty = false
            when (val opened = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Success -> {
                    val git = opened.data
                    ahead = GitEngine.getAheadBehind(git).getOrNull()?.first ?: 0
                    dirty = GitEngine.getStatus(git).getOrNull().orEmpty().isNotEmpty()
                    git.close()
                }
                is GitResult.Error -> Unit
            }
            val warning = buildString {
                append("Runs on a GitHub-hosted Ubuntu runner (it uses your Actions minutes).")
                if (commit) append("\n• Files the command changes are committed and pushed to this branch, then pulled here.")
                else append("\n• Check-only: nothing is committed.")
                if (ahead > 0) append("\n• $ahead unpushed commit${if (ahead == 1) "" else "s"} won't be included - push first.")
                if (dirty) append("\n• Uncommitted changes here won't be included.")
            }
            val pending = PendingCloud(raw, command, commit, reason, warning)
            if (skipConfirm) runCloud(pending) else _state.value = _state.value.copy(pendingCloud = pending)
        }
    }

    fun confirmCloud(always: Boolean) {
        val pending = _state.value.pendingCloud ?: return
        _state.value = _state.value.copy(pendingCloud = null)
        if (always) ConsolePrefs.setAutoCloud(appRef, true)
        runCloud(pending)
    }

    fun dismissCloud() {
        val pending = _state.value.pendingCloud ?: return
        _state.value = _state.value.copy(pendingCloud = null)
        appendLine(pending.rawInput, (pending.reason?.plus(" ") ?: "") + "Not run.", true)
    }

    private fun runCloud(pending: PendingCloud) {
        runJob = viewModelScope.launch {
            _state.value = _state.value.copy(isRunning = true, canStop = true)
            val index = beginLive(pending.rawInput, "⏳ Starting…")
            try {
                val repo = repoRepo.getById(repoId) ?: run { setLine(index, "error: repo not found", true, false); return@launch }
                val fullName = githubFullNameFromUrl(repo.cloneUrl) ?: run { setLine(index, "error: not a GitHub repo", true, false); return@launch }
                val credential = if (repo.credentialId != 0L) credRepo.getById(repo.credentialId) else null
                val token = credential?.token ?: run { setLine(index, "error: no token on this repo's credential", true, false); return@launch }
                val branch = when (val opened = GitEngine.openRepo(repo.fullSavePath)) {
                    is GitResult.Success -> GitEngine.getCurrentBranch(opened.data).getOrNull().also { opened.data.close() }
                    is GitResult.Error -> null
                }.orEmpty().ifBlank { repo.branch }

                val result = CloudRunner.run(fullName, token, branch, pending.command, pending.commit) { status ->
                    setLine(index, "⏳ $status", false, true)
                }
                var text = result.text
                if (result.ok && pending.commit) text += "\n\n" + pullAfterCloud(repo)
                if (result.url != null) text += "\n\n" + result.url
                setLine(index, text, isError = !result.ok, live = false)
            } catch (e: CancellationException) {
                setLine(index, "^C  Stopped. The run on GitHub was cancelled.", true, false)
                throw e
            } finally {
                _state.value = _state.value.copy(isRunning = false, canStop = false)
            }
        }
    }

    /** After a cloud run that may have pushed changes: bring them here if it's safe to. */
    private suspend fun pullAfterCloud(repo: dev.graft.app.data.db.entity.RepoEntity): String {
        val opened = GitEngine.openRepo(repo.fullSavePath)
        if (opened !is GitResult.Success) return "↳ couldn't open the repo to pull the result."
        val git = opened.data
        try {
            val dirty = GitEngine.getStatus(git).getOrNull().orEmpty().isNotEmpty()
            if (dirty) return "↳ Changes (if any) were pushed to GitHub. You have local edits, so commit or stash them, then run `pull`."
            val credential = if (repo.credentialId != 0L) credRepo.getById(repo.credentialId) else null
            val out = GitConsoleInterpreter.run(
                git, credential, GitIdentityPrefs.currentName(appRef), GitIdentityPrefs.currentEmail(appRef), "pull",
            )
            return "↳ pull: $out"
        } finally {
            git.close()
        }
    }

    private fun runCloudSetup(raw: String) {
        viewModelScope.launch {
            val repo = repoRepo.getById(repoId)
            val template = WorkflowTemplateCatalog.byFileName(CloudRunner.WORKFLOW_PATH)
            if (repo == null || template == null) { appendLine(raw, "error: couldn't set up the runner", true); return@launch }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(repo.fullSavePath, CloudRunner.WORKFLOW_PATH)
                    file.parentFile?.mkdirs()
                    file.writeText(template.yamlContent)
                }
            }
            if (result.isSuccess) {
                appendLine(
                    raw,
                    "Created ${CloudRunner.WORKFLOW_PATH}\n\nNow publish it (to the default branch):\n  add .github\n  commit -m \"Add Graft runner\"\n  push\n\n" +
                        "After that, just type commands - anything this device can't run (cargo, npm, gradle, python...) offers to run in the cloud.",
                    false,
                )
            } else {
                appendLine(raw, "error: couldn't write the workflow: ${result.exceptionOrNull()?.message}", true)
            }
        }
    }

    // ------------------------------------------------------------------------------- transcript

    private fun appendLine(command: String, output: String, isError: Boolean) {
        _state.value = _state.value.copy(history = _state.value.history + ConsoleLine(command, output, isError))
    }

    private fun beginLive(command: String, text: String): Int {
        val history = _state.value.history + ConsoleLine(command, text, false, isLive = true)
        _state.value = _state.value.copy(history = history)
        return history.lastIndex
    }

    private fun setLine(index: Int, output: String, isError: Boolean, live: Boolean) {
        val history = _state.value.history.toMutableList()
        if (index in history.indices) {
            history[index] = history[index].copy(output = output, isError = isError, isLive = live)
            _state.value = _state.value.copy(history = history)
        }
    }

    fun clear() {
        _state.value = _state.value.copy(history = emptyList())
    }
}

private val EXAMPLE_COMMANDS = listOf(
    "status", "diff", "diff --cached", "log --oneline -10", "branch -a",
    "add .", "commit -m \"message\"", "push", "pull", "fetch",
    "stash", "stash list", "stash pop", "tag", "remote -v",
    "show", "reflog", "blame README.md", "shortlog -sn", "ls -la", "grep -rn TODO .", "cargo fmt --all",
)

private class QuickCommand(val label: String, val command: String, val runNow: Boolean)

private val QUICK_COMMANDS = listOf(
    QuickCommand("status", "status", runNow = true),
    QuickCommand("log", "log --oneline -10", runNow = true),
    QuickCommand("diff", "diff", runNow = true),
    QuickCommand("branch", "branch -a", runNow = true),
    QuickCommand("stash list", "stash list", runNow = true),
    QuickCommand("show", "show", runNow = true),
    QuickCommand("reflog", "reflog", runNow = true),
    QuickCommand("ls", "ls -la", runNow = true),
    QuickCommand("add .", "add .", runNow = false),
    QuickCommand("commit", "commit -m \"\"", runNow = false),
    QuickCommand("pull", "pull", runNow = false),
    QuickCommand("push", "push", runNow = false),
    QuickCommand("cargo fmt", "cargo fmt --all", runNow = false),
    QuickCommand("cloud setup", "cloud setup", runNow = false),
)

/** The whole session as plain text, the way it would look in a terminal. */
private fun transcript(history: List<ConsoleLine>): String =
    history.joinToString("\n\n") { "$ ${it.command}\n${it.output}".trimEnd() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitConsoleScreen(repoId: Long, onBack: () -> Unit, vm: GitConsoleViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var historyIndex by remember { mutableStateOf(-1) }
    var showHelp by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    LaunchedEffect(repoId) { vm.load(repoId) }
    LaunchedEffect(state.history.size) {
        if (state.history.isNotEmpty()) scope.launch { listState.animateScrollToItem(state.history.size - 1) }
    }

    fun submit() {
        if (input.isBlank()) return
        vm.submit(input)
        input = ""
        historyIndex = -1
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = "Console",
                subtitle = state.repoName,
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(transcript(state.history)))
                            android.widget.Toast.makeText(context, "Transcript copied", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        enabled = state.history.isNotEmpty(),
                    ) { Icon(Icons.Filled.ContentCopy, "Copy transcript") }
                    IconButton(onClick = { showHelp = true }) { Icon(Icons.Filled.HelpOutline, "What's supported") }
                    IconButton(onClick = vm::clear, enabled = state.history.isNotEmpty() && !state.isRunning) { Icon(Icons.Filled.Delete, "Clear") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
            if (state.history.isEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.Terminal, null, Modifier.size(44.dp), tint = MaterialTheme.gitColors.added)
                    Spacer(Modifier.height(14.dp))
                    Text("Git console", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Real git syntax, run against this repo by the same engine the rest of the app uses.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    Text("Try one", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EXAMPLE_COMMANDS.forEach { ExampleChip(it) { input = it } }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.history) { line -> ConsoleLineView(line) }
                    if (state.isRunning) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.gitColors.added)
                                Spacer(Modifier.width(8.dp))
                                Text("running\u2026", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
            // One-tap shortcuts. Read-only commands run immediately; ones that change something are
            // only *filled in*, so a stray tap never commits or pushes.
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                QUICK_COMMANDS.forEach { quick ->
                    ExampleChip(quick.label) {
                        if (state.isRunning) return@ExampleChip
                        if (quick.runNow) vm.submit(quick.command) else input = quick.command
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().imePadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (state.commandHistory.isEmpty()) return@IconButton
                        val newIndex = (historyIndex + 1).coerceAtMost(state.commandHistory.size - 1)
                        historyIndex = newIndex
                        input = state.commandHistory[state.commandHistory.size - 1 - newIndex]
                    },
                    enabled = state.commandHistory.isNotEmpty(),
                ) { Icon(Icons.Filled.KeyboardArrowUp, "Previous command", tint = MaterialTheme.colorScheme.onSurfaceVariant) }

                Text("$", fontFamily = FontFamily.Monospace, color = MaterialTheme.gitColors.added, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp))

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("any command — git status, ls, cargo fmt --all", fontFamily = FontFamily.Monospace, maxLines = 1) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                )
                Spacer(Modifier.width(6.dp))
                if (state.canStop) {
                    // Shell and cloud runs can be interrupted (a cloud stop also cancels the run on GitHub).
                    IconButton(onClick = vm::stop) { Icon(Icons.Filled.Close, "Stop", tint = MaterialTheme.colorScheme.error) }
                } else {
                    IconButton(onClick = { submit() }, enabled = input.isNotBlank() && !state.isRunning) {
                        Icon(Icons.Filled.Send, "Run", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    state.pendingDangerCommand?.let { cmd ->
        AlertDialog(
            onDismissRequest = vm::dismissPendingDanger,
            title = { Text("Run \"$cmd\"?") },
            text = { Text(state.pendingDangerWarning ?: "") },
            confirmButton = { TextButton(onClick = vm::confirmPendingDanger) { Text("Run anyway", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = vm::dismissPendingDanger) { Text("Cancel") } },
        )
    }

    state.pendingCloud?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::dismissCloud,
            title = { Text("Run in the cloud?") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    if (pending.reason != null) {
                        Text(pending.reason, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        pending.command,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.gitColors.added,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(pending.warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmCloud(always = false) }) { Text("Run in cloud") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.confirmCloud(always = true) }) { Text("Always") }
                    TextButton(onClick = vm::dismissCloud) { Text("Cancel") }
                }
            },
        )
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("What can I type?") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    HelpSection(
                        "Just type",
                        "One prompt for everything - no modes to pick. Git commands run on the app's git engine; anything else is tried on the device's own shell, in this repo's folder.",
                    )
                    HelpSection(
                        "Git",
                        "status, diff, log, show, blame, branch, add, restore, rm, mv, commit, push, pull, fetch, switch, checkout, merge, rebase, cherry-pick, stash, reset, tag, remote, clean, reflog, ls-files, config, describe, rev-parse, shortlog, gc. Shortcuts: st, co, br, ci, lg, last, unstage. \"git\" in front is optional.",
                    )
                    HelpSection(
                        "Device shell",
                        "ls, cat, head, tail, grep, find, sed, wc, sort, cp, mv, rm, mkdir, touch, diff… whatever Android ships. Put ! in front (e.g. !rm notes.txt) to force the shell where a git command has the same name.",
                    )
                    HelpSection(
                        "Cloud",
                        "A phone can't run cargo, npm, gradle, python… so when you type one, the console offers to run it on a GitHub Actions runner and brings back the output - and any files it changed (like cargo fmt --all). One-time setup: type  cloud setup , then add, commit and push the new file. \"Always\" skips the question next time. Needs a GitHub token with the workflow scope.",
                    )
                    HelpSection(
                        "Careful",
                        "reset --hard, clean, push --force, branch -D and rm -r always ask first. Stop (✕) cancels a shell or cloud command; a cloud stop also cancels the run on GitHub.",
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Got it") } },
        )
    }
}

@Composable
private fun HelpSection(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ExampleChip(command: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            "$ $command", fontFamily = FontFamily.Monospace, color = MaterialTheme.gitColors.added,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ConsoleLineView(line: ConsoleLine) {
    Column {
        Row {
            Text("$ ", fontFamily = FontFamily.Monospace, color = MaterialTheme.gitColors.added, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(line.command, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
        if (line.output.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            // Selectable: long-press to pick out a hash, a path or an error line.
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    line.output,
                    fontFamily = FontFamily.Monospace,
                    color = if (line.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
