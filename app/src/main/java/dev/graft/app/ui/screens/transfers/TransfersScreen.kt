package dev.graft.app.ui.screens.transfers

import android.app.Application
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.graft.app.App
import dev.graft.app.transfer.TransferKind
import dev.graft.app.transfer.TransferState
import dev.graft.app.transfer.TransferTask
import dev.graft.app.transfer.statusLine
import dev.graft.app.ui.components.AppCard
import dev.graft.app.ui.components.EmptyState
import dev.graft.app.ui.components.ScreenTopBar
import dev.graft.app.ui.components.SectionHeader
import dev.graft.app.ui.components.StatusPill
import dev.graft.app.ui.components.TransferProgressBar
import dev.graft.app.ui.components.relativeTimeMillis
import dev.graft.app.ui.theme.gitColors

class TransfersViewModel(app: Application) : AndroidViewModel(app) {
    private val manager = (app as App).transferManager
    val tasks = manager.tasks
    fun cancel(id: Long) = manager.cancel(id)
    fun retry(id: Long) = manager.retry(id)
    fun remove(id: Long) = manager.remove(id)
    fun clearFinished() = manager.clearFinished()
}

/**
 * Every clone, pull and push - running, waiting and finished - in one list, like a browser's
 * downloads page. Active rows show live progress, size, speed and time left; they can be cancelled
 * from here or from the notification, and a failed one can be retried in one tap.
 */
@Composable
fun TransfersScreen(
    onBack: () -> Unit,
    onOpenRepo: (Long) -> Unit,
    vm: TransfersViewModel = viewModel(),
) {
    val tasks by vm.tasks.collectAsState()
    val active = tasks.filter { it.state.isActive }
    val finished = tasks.filter { it.state.isFinished }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = "Transfers",
                onBack = onBack,
                actions = {
                    if (finished.isNotEmpty()) {
                        IconButton(onClick = vm::clearFinished) { Icon(Icons.Filled.DeleteSweep, "Clear finished") }
                    }
                },
            )
        },
    ) { pad ->
        if (tasks.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Download,
                title = "No transfers yet",
                message = "Clones, pulls and pushes appear here with live progress, and keep running in the background.",
                modifier = Modifier.padding(pad),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (active.isNotEmpty()) {
                    item(key = "h-active") { SectionHeader("In progress") }
                    items(active, key = { "t-${it.id}" }) { task ->
                        TransferRow(task, vm, onOpenRepo)
                    }
                }
                if (finished.isNotEmpty()) {
                    item(key = "h-done") { SectionHeader("Finished") }
                    items(finished, key = { "t-${it.id}" }) { task ->
                        TransferRow(task, vm, onOpenRepo)
                    }
                }
            }
        }
    }
}

private fun iconFor(kind: TransferKind): ImageVector = when (kind) {
    TransferKind.CLONE -> Icons.Filled.Download
    TransferKind.PULL -> Icons.Filled.ArrowDownward
    TransferKind.PUSH -> Icons.Filled.Upload
    TransferKind.FETCH -> Icons.Filled.Refresh
    TransferKind.SYNC -> Icons.Filled.Sync
}

@Composable
private fun TransferRow(task: TransferTask, vm: TransfersViewModel, onOpenRepo: (Long) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val git = MaterialTheme.gitColors
    val tint = when (task.state) {
        TransferState.QUEUED, TransferState.RUNNING -> scheme.primary
        TransferState.SUCCEEDED -> git.added
        TransferState.FAILED -> scheme.error
        TransferState.CANCELLED -> scheme.onSurfaceVariant
    }
    val openTarget = if (task.state == TransferState.SUCCEEDED) {
        if (task.openRepoId > 0) task.openRepoId else task.repoId
    } else 0L

    AppCard(
        Modifier.fillMaxWidth(),
        onClick = if (openTarget > 0) ({ onOpenRepo(openTarget) }) else null,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(iconFor(task.kind), null, tint = tint, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            task.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusPill(task.kind.label, tint, tint.copy(alpha = 0.14f))
                    }
                    Text(
                        task.statusLine(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (task.state == TransferState.FAILED) scheme.error else scheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (task.state.isFinished && task.finishedAt > 0) {
                        Text(
                            relativeTimeMillis(task.finishedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                when (task.state) {
                    TransferState.QUEUED, TransferState.RUNNING ->
                        IconButton(onClick = { vm.cancel(task.id) }) { Icon(Icons.Filled.Close, "Cancel") }
                    TransferState.FAILED, TransferState.CANCELLED -> {
                        IconButton(onClick = { vm.retry(task.id) }) { Icon(Icons.Filled.Refresh, "Try again") }
                        IconButton(onClick = { vm.remove(task.id) }) { Icon(Icons.Filled.Close, "Remove") }
                    }
                    TransferState.SUCCEEDED ->
                        IconButton(onClick = { vm.remove(task.id) }) { Icon(Icons.Filled.Close, "Remove") }
                }
            }

            if (task.state.isActive) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TransferProgressBar(task, Modifier.weight(1f))
                    if (task.state == TransferState.RUNNING && !task.indeterminate) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${(task.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
