package dev.graft.app.ui.screens.actions

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.filled.Language
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.graft.app.App
import dev.graft.app.data.ApkInstaller
import dev.graft.app.data.github.GitHubApi
import dev.graft.app.data.github.GitHubResult
import dev.graft.app.data.github.WorkflowArtifact
import dev.graft.app.data.github.WorkflowJob
import dev.graft.app.data.github.WorkflowRun
import dev.graft.app.data.github.WorkflowStep
import dev.graft.app.data.github.githubFullNameFromUrl
import dev.graft.app.ui.components.AppCard
import dev.graft.app.ui.theme.code
import dev.graft.app.ui.components.StatusPill
import dev.graft.app.ui.components.EmptyState
import dev.graft.app.ui.components.BranchChip
import dev.graft.app.ui.components.RepoTitleBlock
import dev.graft.app.ui.components.RefreshBox
import dev.graft.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ActionsUiState(
    val repoName: String = "",
    val branch: String = "",
    val fullName: String? = null,
    val hasToken: Boolean = false,
    val runs: List<WorkflowRun> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val message: String? = null,
    val expandedRunId: Long? = null,
    val jobsByRun: Map<Long, List<WorkflowJob>> = emptyMap(),
    val loadingJobsForRun: Long? = null,
    val expandedLogJobId: Long? = null,
    val logsByJob: Map<Long, String> = emptyMap(),
    val loadingLogsForJob: Long? = null,
    val busyRunId: Long? = null,
    val artifactsByRun: Map<Long, List<WorkflowArtifact>> = emptyMap(),
    val loadingArtifactsForRun: Long? = null,
    val installingArtifactId: Long? = null,
)

class ActionsViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as App
    private val repoRepo = appRef.repoRepository
    private val credRepo = appRef.credentialRepository

    private val _state = MutableStateFlow(ActionsUiState())
    val state: StateFlow<ActionsUiState> = _state.asStateFlow()

    private var fullName: String? = null
    private var token: String? = null

    fun load(repoId: Long) {
        viewModelScope.launch {
            val repo = repoRepo.getById(repoId) ?: return@launch
            fullName = githubFullNameFromUrl(repo.cloneUrl)
            token = if (repo.credentialId != 0L) credRepo.getById(repo.credentialId)?.token else null
            _state.value = _state.value.copy(
                repoName = repo.name,
                branch = repo.branch,
                fullName = fullName,
                hasToken = !token.isNullOrBlank(),
                isLoading = false,
            )
            if (fullName != null && !token.isNullOrBlank()) refreshRuns()
        }
    }

    fun refreshRuns(silent: Boolean = false) {
        val fn = fullName ?: return
        val tk = token ?: return
        viewModelScope.launch {
            if (!silent) _state.value = _state.value.copy(isRefreshing = true)
            when (val r = GitHubApi.listWorkflowRuns(fn, tk)) {
                is GitHubResult.Error ->
                    _state.value = _state.value.copy(isRefreshing = false, message = if (silent) null else "GitHub: ${r.message}")
                is GitHubResult.Success ->
                    _state.value = _state.value.copy(runs = r.data, isRefreshing = false)
            }
        }
    }

    fun toggleRun(runId: Long) {
        val wasExpanded = _state.value.expandedRunId == runId
        _state.value = _state.value.copy(expandedRunId = if (wasExpanded) null else runId)
        if (!wasExpanded) {
            if (runId !in _state.value.jobsByRun) loadJobs(runId)
            if (runId !in _state.value.artifactsByRun) loadArtifacts(runId)
        }
    }

    private fun loadArtifacts(runId: Long) {
        val fn = fullName ?: return
        val tk = token ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingArtifactsForRun = runId)
            when (val r = GitHubApi.listRunArtifacts(fn, runId, tk)) {
                is GitHubResult.Error ->
                    // Quiet failure here — a repo without upload-artifact steps is the
                    // common case, not an error worth a snackbar every time a run opens.
                    _state.value = _state.value.copy(loadingArtifactsForRun = null)
                is GitHubResult.Success ->
                    _state.value = _state.value.copy(
                        loadingArtifactsForRun = null,
                        artifactsByRun = _state.value.artifactsByRun + (runId to r.data),
                    )
            }
        }
    }

    /**
     * Downloads the artifact's zip, extracts the APK inside it, and hands back the install
     * Intent for the screen to launch — actually starting an Activity from a ViewModel isn't
     * possible/appropriate, so this stays a pure suspend function and [ActionsScreen] fires
     * the Intent from the result.
     */
    fun installArtifact(context: Context, runId: Long, artifact: WorkflowArtifact, onIntentReady: (android.content.Intent) -> Unit) {
        val fn = fullName ?: return
        val tk = token ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(installingArtifactId = artifact.id)
            when (val dl = GitHubApi.downloadArtifactZip(fn, artifact.id, tk)) {
                is GitHubResult.Error ->
                    _state.value = _state.value.copy(installingArtifactId = null, message = dl.message)
                is GitHubResult.Success -> {
                    when (val extracted = ApkInstaller.extractAndBuildInstallIntent(context, dl.data, artifact.name, _state.value.repoName)) {
                        is ApkInstaller.Result.Ready -> {
                            _state.value = _state.value.copy(installingArtifactId = null)
                            onIntentReady(extracted.installIntent)
                        }
                        ApkInstaller.Result.NoApkInArchive ->
                            _state.value = _state.value.copy(
                                installingArtifactId = null,
                                message = "\"${artifact.name}\" doesn't contain an APK",
                            )
                        is ApkInstaller.Result.Failed ->
                            _state.value = _state.value.copy(installingArtifactId = null, message = extracted.message)
                    }
                }
            }
        }
    }

    private fun loadJobs(runId: Long) {
        val fn = fullName ?: return
        val tk = token ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingJobsForRun = runId)
            when (val r = GitHubApi.listJobsForRun(fn, runId, tk)) {
                is GitHubResult.Error ->
                    _state.value = _state.value.copy(loadingJobsForRun = null, message = "GitHub: ${r.message}")
                is GitHubResult.Success ->
                    _state.value = _state.value.copy(
                        loadingJobsForRun = null,
                        jobsByRun = _state.value.jobsByRun + (runId to r.data),
                    )
            }
        }
    }

    fun toggleLogs(jobId: Long) {
        val wasExpanded = _state.value.expandedLogJobId == jobId
        _state.value = _state.value.copy(expandedLogJobId = if (wasExpanded) null else jobId)
        if (!wasExpanded && jobId !in _state.value.logsByJob) loadLogs(jobId)
    }

    private fun loadLogs(jobId: Long) {
        val fn = fullName ?: return
        val tk = token ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingLogsForJob = jobId)
            when (val r = GitHubApi.getJobLogs(fn, jobId, tk)) {
                is GitHubResult.Error ->
                    _state.value = _state.value.copy(loadingLogsForJob = null, message = "GitHub: ${r.message}")
                is GitHubResult.Success ->
                    _state.value = _state.value.copy(
                        loadingLogsForJob = null,
                        // Cap what we hold/display — full CI logs can run into the megabytes,
                        // and the tail is what has the actual failure almost always anyway.
                        logsByJob = _state.value.logsByJob + (jobId to r.data.takeLast(20_000)),
                    )
            }
        }
    }

    fun rerun(runId: Long) = runAction(runId) { fn, tk -> GitHubApi.rerunWorkflow(fn, runId, tk) }
    fun rerunFailedJobs(runId: Long) = runAction(runId) { fn, tk -> GitHubApi.rerunFailedJobs(fn, runId, tk) }
    fun cancel(runId: Long) = runAction(runId) { fn, tk -> GitHubApi.cancelWorkflowRun(fn, runId, tk) }

    private fun runAction(runId: Long, block: suspend (String, String) -> GitHubResult<Unit>) {
        val fn = fullName ?: return
        val tk = token ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busyRunId = runId)
            when (val r = block(fn, tk)) {
                is GitHubResult.Error -> _state.value = _state.value.copy(busyRunId = null, message = "GitHub: ${r.message}")
                is GitHubResult.Success -> {
                    _state.value = _state.value.copy(busyRunId = null, message = "Done")
                    refreshRuns(silent = true)
                }
            }
        }
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsScreen(repoId: Long, onBack: () -> Unit, vm: ActionsViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(repoId) { vm.load(repoId) }
    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); vm.dismissMessage() } }

    // Polls the whole time this screen is open — deliberately NOT conditional on "is
    // anything still running": a workflow that starts *after* the list has settled (someone
    // else pushes, a scheduled run fires, etc.) should still show up without a manual pull
    // to refresh. Stops automatically when the composable leaves composition (screen closed).
    LaunchedEffect(Unit) {
        while (true) {
            delay(12_000)
            vm.refreshRuns(silent = true)
        }
    }

    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { RepoTitleBlock(state.repoName.ifBlank { "Actions" }, state.branch) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to repos") } },
                actions = {
                    // GitHub's own Actions UI shows things this screen deliberately doesn't try
                    // to replicate — full raw logs, artifact browsing beyond APKs, re-run-with-
                    // debug-logging, matrix job breakdowns. One tap out to the real thing instead
                    // of half-reimplementing all of that here.
                    state.fullName?.let { fullName ->
                        val context = LocalContext.current
                        IconButton(onClick = {
                            openUrl(context, "https://github.com/$fullName/actions")
                        }) {
                            Icon(Icons.Filled.Language, "View on GitHub")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Runs") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Templates") })
            }
            if (tab == 0) {
                RefreshBox(
                    refreshing = state.isRefreshing,
                    onRefresh = { vm.refreshRuns() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                Column(Modifier.fillMaxSize()) {

            when {
                state.fullName == null -> EmptyNote(
                    "Not a GitHub repo",
                    "This repo's remote doesn't look like a GitHub repo, so there's no Actions data to show. You can still add workflow files from the Templates tab.",
                )
                !state.hasToken -> EmptyNote(
                    "Connect your GitHub account",
                    "Attach a credential with a GitHub token to this repo (Settings → Credentials) to see its workflow runs.",
                )
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.runs.isEmpty() -> EmptyNote("No runs yet", "Push a commit, or add a workflow from the Templates tab, and runs will show up here.")
                else -> {
                    val context = LocalContext.current
                    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(RunFilter.ALL) }
                    val shown = remember(state.runs, filter) { state.runs.filter { filter.matches(it) } }
                    Column(Modifier.fillMaxSize()) {
                        RunFilterRow(filter = filter, runs = state.runs, onSelect = { filter = it })
                        if (shown.isEmpty()) {
                            EmptyNote("No ${filter.label.lowercase()} runs", "Nothing in the latest runs matches this filter.")
                        } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(shown, key = { it.id }) { run ->
                            RunCard(
                                run = run,
                                expanded = state.expandedRunId == run.id,
                                jobs = state.jobsByRun[run.id],
                                loadingJobs = state.loadingJobsForRun == run.id,
                                busy = state.busyRunId == run.id,
                                expandedLogJobId = state.expandedLogJobId,
                                logsByJob = state.logsByJob,
                                loadingLogsForJob = state.loadingLogsForJob,
                                artifacts = state.artifactsByRun[run.id],
                                loadingArtifacts = state.loadingArtifactsForRun == run.id,
                                installingArtifactId = state.installingArtifactId,
                                onToggle = { vm.toggleRun(run.id) },
                                onToggleLogs = { jobId -> vm.toggleLogs(jobId) },
                                onRerun = { vm.rerun(run.id) },
                                onRerunFailed = { vm.rerunFailedJobs(run.id) },
                                onCancel = { vm.cancel(run.id) },
                                onInstall = { artifact ->
                                    vm.installArtifact(context, run.id, artifact) { intent ->
                                        try {
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "No app found to install APKs", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                            )
                        }
                    }
                        }
                    }
                }
            }
        }
                }
            } else {
                WorkflowTemplatesTab(repoId = repoId, snack = snack)
            }
        }
    }
}

@Composable
private fun EmptyNote(title: String, text: String) {
    EmptyState(icon = Icons.Filled.Info, title = title, message = text)
}

@Composable
internal fun statusColor(status: String, conclusion: String?): Color = when {
    status != "completed" -> MaterialTheme.colorScheme.secondary
    conclusion == "success" -> MaterialTheme.gitColors.added
    conclusion == "failure" || conclusion == "timed_out" -> MaterialTheme.colorScheme.error
    conclusion == "cancelled" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun statusLabel(status: String, conclusion: String?): String = when {
    status == "queued" -> "Queued"
    status == "waiting" -> "Waiting"
    status == "in_progress" -> "Running"
    conclusion == "success" -> "Success"
    conclusion == "failure" -> "Failed"
    conclusion == "cancelled" -> "Cancelled"
    conclusion == "timed_out" -> "Timed out"
    conclusion == "action_required" -> "Action required"
    conclusion == "skipped" -> "Skipped"
    conclusion == null -> status.replaceFirstChar { it.uppercase() }
    else -> conclusion.replaceFirstChar { it.uppercase() }
}

@Composable
internal fun StatusIcon(status: String, conclusion: String?, color: Color, size: Dp = 18.dp) {
    val icon = when {
        status != "completed" -> Icons.Filled.HourglassTop
        conclusion == "success" -> Icons.Filled.CheckCircle
        conclusion == "failure" || conclusion == "timed_out" -> Icons.Filled.Error
        conclusion == "cancelled" -> Icons.Filled.RemoveCircleOutline
        else -> Icons.Filled.PlayCircleOutline
    }
    Icon(icon, null, tint = color, modifier = Modifier.size(size))
}

/** Relative-ish, no-dependency time label ("just now", "5m ago", "yesterday"...) from an
 *  ISO-8601 UTC timestamp — good enough for a run list without pulling in a date library. */
internal fun relativeTime(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        val instant = java.time.Instant.parse(iso)
        val seconds = java.time.Duration.between(instant, java.time.Instant.now()).seconds
        when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3600}h ago"
            seconds < 172_800 -> "yesterday"
            else -> "${seconds / 86_400}d ago"
        }
    } catch (e: Exception) {
        ""
    }
}

/** Opens a URL in whatever browser the device has, failing quietly with a toast rather than
 *  crashing on the rare device with no browser at all (some locked-down work profiles). */
private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "No browser found to open this link", android.widget.Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun RunCard(
    run: WorkflowRun,
    expanded: Boolean,
    jobs: List<WorkflowJob>?,
    loadingJobs: Boolean,
    busy: Boolean,
    expandedLogJobId: Long?,
    logsByJob: Map<Long, String>,
    loadingLogsForJob: Long?,
    artifacts: List<WorkflowArtifact>?,
    loadingArtifacts: Boolean,
    installingArtifactId: Long?,
    onToggle: () -> Unit,
    onToggleLogs: (Long) -> Unit,
    onRerun: () -> Unit,
    onRerunFailed: () -> Unit,
    onCancel: () -> Unit,
    onInstall: (WorkflowArtifact) -> Unit,
) {
    val color = statusColor(run.status, run.conclusion)
    val label = statusLabel(run.status, run.conclusion)
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "runChevron")

    AppCard(modifier = Modifier.fillMaxWidth(), accent = color) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ExpandMore, if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
                )
                Spacer(Modifier.width(6.dp))
                StatusIcon(run.status, run.conclusion, color)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(run.displayTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BranchChip(run.headBranch, Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${run.headSha.take(7)} · ${relativeTime(run.updatedAt)}",
                            style = MaterialTheme.code.tiny,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                StatusPill(label, color, color.copy(alpha = 0.14f))
                if (run.htmlUrl.isNotBlank()) {
                    val context = LocalContext.current
                    IconButton(onClick = { openUrl(context, run.htmlUrl) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Language, "View on GitHub", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (run.isActive) {
                            OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
                        } else {
                            OutlinedButton(onClick = onRerun, enabled = !busy) { Text("Re-run") }
                            if (run.conclusion == "failure") {
                                OutlinedButton(onClick = onRerunFailed, enabled = !busy) { Text("Re-run failed jobs") }
                            }
                        }
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }

                    if (loadingJobs) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        jobs?.forEach { job ->
                            JobRow(
                                job = job,
                                logExpanded = expandedLogJobId == job.id,
                                log = logsByJob[job.id],
                                loadingLog = loadingLogsForJob == job.id,
                                onToggleLogs = { onToggleLogs(job.id) },
                            )
                        }
                    }

                    // Only shown once there's something to show — a run whose workflow
                    // never calls upload-artifact (most CI-only workflows) just omits this
                    // section entirely rather than showing an empty "Build outputs" header.
                    if (loadingArtifacts) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else if (!artifacts.isNullOrEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(
                                "Build outputs",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            artifacts.forEach { artifact ->
                                ArtifactRow(
                                    artifact = artifact,
                                    installing = installingArtifactId == artifact.id,
                                    onInstall = { onInstall(artifact) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtifactRow(artifact: WorkflowArtifact, installing: Boolean, onInstall: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (artifact.expired) Icons.Filled.HourglassEmpty else Icons.Filled.GetApp,
            null,
            tint = if (artifact.expired) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(artifact.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(
                if (artifact.expired) "Expired" else formatArtifactSize(artifact.sizeInBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            installing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            artifact.expired -> Unit // no action offered — GitHub has already deleted the archive
            else -> TextButton(onClick = onInstall) { Text("Install") }
        }
    }
}

private fun formatArtifactSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

@Composable
private fun JobRow(job: WorkflowJob, logExpanded: Boolean, log: String?, loadingLog: Boolean, onToggleLogs: () -> Unit) {
    val color = statusColor(job.status, job.conclusion)
    val label = statusLabel(job.status, job.conclusion)
    val canViewLogs = job.status == "completed"

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (canViewLogs) Modifier.clickable(onClick = onToggleLogs) else Modifier)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIcon(job.status, job.conclusion, color, size = 14.dp)
            Spacer(Modifier.width(8.dp))
            Text(job.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
            Text(label, color = color, fontSize = 10.sp)
            if (canViewLogs) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (logExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, "View logs",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp),
                )
            }
        }
        if (job.steps.isNotEmpty()) {
            Column(Modifier.padding(start = 22.dp, bottom = 4.dp)) {
                job.steps.forEach { step -> StepRow(step) }
            }
        }
        AnimatedVisibility(
            visible = logExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            LogPanel(log, loadingLog)
        }
    }
}

@Composable
private fun StepRow(step: WorkflowStep) {
    val color = statusColor(step.status, step.conclusion)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusIcon(step.status, step.conclusion, color, size = 11.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            step.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LogPanel(log: String?, loading: Boolean) {
    val clipboard = LocalClipboardManager.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            } else if (log.isNullOrBlank()) {
                Text("No log output", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SelectionContainer {
                    Text(
                        log,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { clipboard.setText(AnnotatedString(log)) }) { Text("Copy full log") }
            }
        }
    }
}


private val FAILED_CONCLUSIONS = setOf("failure", "timed_out", "startup_failure", "action_required")

private enum class RunFilter(val label: String) {
    ALL("All"), RUNNING("Running"), FAILED("Failed"), PASSED("Passed");

    fun matches(run: WorkflowRun): Boolean = when (this) {
        ALL -> true
        RUNNING -> run.isActive
        FAILED -> run.conclusion in FAILED_CONCLUSIONS
        PASSED -> run.conclusion == "success"
    }
}

/** Filter chips with live counts, so "3 failed" is visible before you tap anything. */
@Composable
private fun RunFilterRow(filter: RunFilter, runs: List<WorkflowRun>, onSelect: (RunFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RunFilter.values().forEach { f ->
            val count = if (f == RunFilter.ALL) 0 else runs.count { f.matches(it) }
            FilterChip(
                selected = filter == f,
                onClick = { onSelect(f) },
                label = { Text(if (count > 0) "${f.label} $count" else f.label) },
            )
        }
    }
}
