package dev.graft.app.ui.screens.overview

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import dev.graft.app.ui.components.TransferProgressBar
import dev.graft.app.transfer.statusLine
import dev.graft.app.transfer.TransferTask
import dev.graft.app.transfer.TransferState
import dev.graft.app.transfer.TransferKind
import dev.graft.app.App
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.graft.app.data.db.entity.SyncLogEntity
import dev.graft.app.data.db.entity.SyncLogStatus
import dev.graft.app.git.CommitInfo
import dev.graft.app.ui.components.AppCard
import dev.graft.app.ui.components.BranchChip
import dev.graft.app.ui.components.ErrorState
import dev.graft.app.ui.components.LoadingState
import dev.graft.app.ui.components.RefreshBox
import dev.graft.app.ui.components.RepoTitleBlock
import dev.graft.app.ui.components.ScreenTopBar
import dev.graft.app.ui.components.SectionHeader
import dev.graft.app.ui.components.StatusPill
import dev.graft.app.ui.components.relativeTimeMillis
import dev.graft.app.ui.theme.code
import dev.graft.app.ui.theme.gitColors

/**
 * The repo's landing tab: where it stands right now (clean? ahead? conflicted?), the last few
 * commits, and what background sync has been doing. Everything here is a *summary* with a way to
 * jump to the screen that acts on it - the verbs live elsewhere.
 */
@Composable
fun OverviewScreen(
    repoId: Long,
    onBack: () -> Unit,
    onOpenChanges: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBranches: () -> Unit,
    onOpenConflicts: () -> Unit,
    onOpenCommit: (String) -> Unit,
    vm: OverviewViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsState()
    val appContext = LocalContext.current.applicationContext
    val transferManager = remember { (appContext as App).transferManager }
    val transfers by transferManager.tasks.collectAsState()
    val activeTransfer = transfers.firstOrNull { it.repoId == repoId && it.state.isActive }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); vm.dismissMessage() } }

    LaunchedEffect(repoId) { vm.load(repoId) }

    // Come back from Changes/Branches/etc. and the numbers here should already be right.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                titleContent = { RepoTitleBlock(name = state.repo?.name ?: "Repository", branch = state.branch) },
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        val repo = state.repo
        when {
            repo == null && state.error != null -> ErrorState(
                message = state.error.orEmpty(), modifier = Modifier.padding(pad), onRetry = vm::refresh,
            )
            repo == null -> LoadingState(Modifier.padding(pad))
            else -> RefreshBox(
                refreshing = state.isLoading,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize().padding(pad),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "status") {
                        StatusHero(
                            state = state,
                            onReview = onOpenChanges,
                            onResolve = onOpenConflicts,
                        )
                    }
                    if (!repo.cloneUrl.isNullOrBlank()) item(key = "sync") {
                        SyncCard(
                            state = state,
                            active = activeTransfer,
                            onFetch = { vm.runTransfer(TransferKind.FETCH) },
                            onPull = { vm.runTransfer(TransferKind.PULL) },
                            onPush = { vm.runTransfer(TransferKind.PUSH) },
                            onCancel = { activeTransfer?.let { transferManager.cancel(it.id) } },
                        )
                    }
                    item(key = "info") {
                        InfoCard(
                            state = state,
                            onOpenBranches = onOpenBranches,
                        )
                    }

                    item(key = "commits-header") {
                        SectionHeader(
                            "Recent commits",
                            trailing = { TextButton(onClick = onOpenHistory) { Text("See all") } },
                        )
                    }
                    if (state.recentCommits.isEmpty()) {
                        item(key = "commits-empty") {
                            Text(
                                if (state.isLoading) "Loading…" else "No commits yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    } else {
                        items(state.recentCommits, key = { "c-" + it.sha }) { commit ->
                            CommitRow(commit, onClick = { onOpenCommit(commit.sha) })
                        }
                    }

                    item(key = "sync-header") { SectionHeader("Sync activity") }
                    if (state.syncEvents.isEmpty()) {
                        item(key = "sync-empty") {
                            Text(
                                "Background sync hasn't run for this repo yet. Enable it in Settings.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    } else {
                        items(state.syncEvents, key = { "s-" + it.id }) { event -> SyncEventRow(event) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHero(state: OverviewUiState, onReview: () -> Unit, onResolve: () -> Unit) {
    val git = MaterialTheme.gitColors
    val scheme = MaterialTheme.colorScheme

    val hasError = !state.repo?.lastError.isNullOrBlank()
    val (icon, tint, headline) = when {
        state.hasConflicts -> Triple(Icons.Filled.MergeType, git.conflict, "Merge conflicts to resolve")
        state.changeCount > 0 -> Triple(
            Icons.Filled.Edit, git.modified,
            "${state.changeCount} uncommitted change${if (state.changeCount == 1) "" else "s"}",
        )
        hasError -> Triple(Icons.Filled.ErrorOutline, scheme.error, "Last sync failed")
        else -> Triple(Icons.Filled.CheckCircle, git.added, "Working tree clean")
    }

    val tracking = when {
        !state.hasUpstream -> "No upstream branch set"
        state.ahead == 0 && state.behind == 0 -> "Up to date with remote"
        else -> buildList {
            if (state.ahead > 0) add("${state.ahead} ahead")
            if (state.behind > 0) add("${state.behind} behind")
        }.joinToString(" · ")
    }

    AppCard(Modifier.fillMaxWidth(), accent = tint) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(headline, style = MaterialTheme.typography.titleMedium)
                    Text(
                        tracking,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hasError) {
                Spacer(Modifier.height(8.dp))
                Text(
                    state.repo?.lastError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.hasConflicts) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onResolve) { Text("Resolve conflicts") }
            } else if (state.changeCount > 0) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onReview) {
                    Text("Review & commit")
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoCard(state: OverviewUiState, onOpenBranches: () -> Unit) {
    val repo = state.repo ?: return
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Branch",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(72.dp),
                )
                BranchChip(state.branch.ifBlank { "—" }, onClick = onOpenBranches)
            }
            InfoLine("Remote", repo.cloneUrl.ifBlank { "Local only (no remote)" })
            InfoLine("Path", repo.fullSavePath)
            InfoLine("Last sync", if (repo.lastSyncTime > 0) relativeTimeMillis(repo.lastSyncTime) else "Never", mono = false)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, mono: Boolean = true) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = if (mono) MaterialTheme.code.small else MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CommitRow(commit: CommitInfo, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                text = commit.shortSha,
                color = MaterialTheme.colorScheme.primary,
                container = MaterialTheme.colorScheme.primaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(commit.message, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${commit.authorName} · ${relativeTimeMillis(commit.authorTime)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SyncEventRow(event: SyncLogEntity) {
    val git = MaterialTheme.gitColors
    val scheme = MaterialTheme.colorScheme
    val (icon: ImageVector, tint: Color) = when (event.status) {
        SyncLogStatus.FAILED -> Icons.Filled.ErrorOutline to scheme.error
        SyncLogStatus.NEW_COMMITS -> Icons.Filled.Download to scheme.secondary
        SyncLogStatus.AUTO_COMMIT -> Icons.Filled.Edit to git.modified
        SyncLogStatus.SUCCESS -> Icons.Filled.Sync to git.added
        else -> Icons.Filled.CheckCircle to scheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            event.message,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.Schedule, null, Modifier.size(12.dp), tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(3.dp))
        Text(
            relativeTimeMillis(event.timestamp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurfaceVariant,
        )
    }
}


/**
 * Fetch / Pull / Push where you can see the state they act on. Whichever has something pending is
 * emphasised (Pull when you're behind, Push when you're ahead) so the next step is the obvious one;
 * while a transfer for this repo runs, the buttons give way to its live progress.
 */
@Composable
private fun SyncCard(
    state: OverviewUiState,
    active: TransferTask?,
    onFetch: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onCancel: () -> Unit,
) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            if (active != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${active.kind.doing}… ${active.statusLine()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!active.indeterminate && active.state == TransferState.RUNNING) {
                        Text("${(active.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Cancel") }
                }
                TransferProgressBar(active, Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onFetch, modifier = Modifier.weight(1f)) { Text("Fetch") }
                    if (state.behind > 0) {
                        Button(onClick = onPull, modifier = Modifier.weight(1f)) { Text("Pull ↓${state.behind}") }
                    } else {
                        OutlinedButton(onClick = onPull, modifier = Modifier.weight(1f)) { Text("Pull") }
                    }
                    if (state.ahead > 0) {
                        Button(
                            onClick = onPush,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary,
                            ),
                        ) { Text("Push ↑${state.ahead}") }
                    } else {
                        OutlinedButton(onClick = onPush, modifier = Modifier.weight(1f)) { Text("Push") }
                    }
                }
            }
        }
    }
}
