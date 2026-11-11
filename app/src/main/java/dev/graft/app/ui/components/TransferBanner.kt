package dev.graft.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.graft.app.transfer.TransferState
import dev.graft.app.transfer.TransferTask
import dev.graft.app.transfer.statusLine

/**
 * The progress bar every transfer surface shares. Indeterminate while queued or until there's a real
 * fraction; otherwise it *animates* toward each new value, because progress arrives in bursts and a bar
 * that jumps in steps looks stuck-then-lurching. One implementation keeps the banner, the Transfers
 * list and the Home cards moving identically.
 */
@Composable
fun TransferProgressBar(
    task: TransferTask,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    if (task.indeterminate || task.state != TransferState.RUNNING) {
        LinearProgressIndicator(modifier = modifier, color = color, trackColor = trackColor)
    } else {
        val animated by animateFloatAsState(
            targetValue = task.progress,
            animationSpec = tween(durationMillis = 300),
            label = "transferProgress",
        )
        LinearProgressIndicator(progress = { animated }, modifier = modifier, color = color, trackColor = trackColor)
    }
}

/**
 * The slim "something is downloading" strip above the bottom bar - the equivalent of the download
 * bubble in a browser. Visible on every screen while a transfer is active, it names what's running,
 * shows live progress, and opens the full Transfers list on tap.
 */
@Composable
fun TransferBanner(
    tasks: List<TransferTask>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = tasks.filter { it.state.isActive }
    AnimatedVisibility(visible = active.isNotEmpty(), modifier = modifier) {
        // While the list animates out it's empty; keep drawing the last task rather than crashing.
        val lead = active.firstOrNull { it.state == TransferState.RUNNING } ?: active.firstOrNull() ?: return@AnimatedVisibility
        val scheme = MaterialTheme.colorScheme
        Surface(onClick = onClick, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = scheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (active.size == 1) "${lead.kind.doing} ${lead.title}" else "${active.size} transfers running",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            lead.statusLine(),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!lead.indeterminate && lead.state == TransferState.RUNNING) {
                        Text("${(lead.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                    Icon(Icons.Filled.ChevronRight, null)
                }
                TransferProgressBar(
                    task = lead,
                    modifier = Modifier.fillMaxWidth(),
                    color = scheme.primary,
                    trackColor = scheme.primaryContainer,
                )
            }
        }
    }
}
