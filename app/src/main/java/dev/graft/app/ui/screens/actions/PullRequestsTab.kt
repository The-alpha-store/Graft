package dev.graft.app.ui.screens.actions

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.graft.app.data.github.PullRequest
import dev.graft.app.ui.components.AppCard
import dev.graft.app.ui.components.EmptyState
import dev.graft.app.ui.components.LoadingState
import dev.graft.app.ui.components.RefreshBox
import dev.graft.app.ui.components.StatusPill
import dev.graft.app.ui.theme.code
import dev.graft.app.ui.theme.gitColors

private enum class PrFilter(val label: String) {
    OPEN("Open"), MERGED("Merged"), CLOSED("Closed"), ALL("All");

    fun matches(pr: PullRequest): Boolean = when (this) {
        OPEN -> pr.label == "Open" || pr.label == "Draft"
        MERGED -> pr.merged
        CLOSED -> pr.label == "Closed"
        ALL -> true
    }
}

/**
 * A read-only look at the repo's pull requests: status, who opened it, which branch into which, and
 * how recently it moved. Tapping one opens it on GitHub, where review and merging live - this tab is
 * for answering "what's open, and is it mine to look at?" without leaving the app.
 */
@Composable
internal fun PullRequestsTab(
    state: ActionsUiState,
    onLoad: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
) {
    LaunchedEffect(state.fullName, state.hasToken) { onLoad() }
    var filter by rememberSaveable { mutableStateOf(PrFilter.OPEN) }
    val shown = remember(state.pullRequests, filter) { state.pullRequests.filter { filter.matches(it) } }

    when {
        state.fullName == null -> EmptyState(
            icon = Icons.Filled.Info,
            title = "Not a GitHub repo",
            message = "Pull requests come from GitHub, and this repo's remote isn't one.",
        )
        !state.hasToken -> EmptyState(
            icon = Icons.Filled.Info,
            title = "Connect your GitHub account",
            message = "Attach a credential with a GitHub token to this repo (Settings → Credentials) to see its pull requests.",
        )
        state.prLoading && state.pullRequests.isEmpty() -> LoadingState()
        else -> RefreshBox(refreshing = state.prLoading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrFilter.values().forEach { f ->
                        val count = if (f == PrFilter.ALL) 0 else state.pullRequests.count { f.matches(it) }
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(if (count > 0) "${f.label} $count" else f.label) },
                        )
                    }
                }
                if (shown.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.MergeType,
                        title = if (state.pullRequests.isEmpty()) "No pull requests" else "No ${filter.label.lowercase()} pull requests",
                        message = if (state.pullRequests.isEmpty()) "Nothing has been opened against this repo yet." else "Try another filter.",
                    )
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(shown, key = { it.number }) { pr -> PullRequestCard(pr, onOpen) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PullRequestCard(pr: PullRequest, onOpen: (String) -> Unit) {
    val git = MaterialTheme.gitColors
    val scheme = MaterialTheme.colorScheme
    val tint: Color = when (pr.label) {
        "Open" -> git.added
        "Draft" -> scheme.onSurfaceVariant
        "Merged" -> git.conflict          // the "merge" hue GitHub also uses for merged PRs
        else -> scheme.error
    }
    AppCard(Modifier.fillMaxWidth(), accent = tint, onClick = if (pr.htmlUrl.isNotBlank()) ({ onOpen(pr.htmlUrl) }) else null) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MergeType, null, Modifier.size(18.dp), tint = tint)
                Spacer(Modifier.width(8.dp))
                Text(
                    "#${pr.number}",
                    style = MaterialTheme.code.small,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(pr.label, tint, tint.copy(alpha = 0.14f))
            }
            Spacer(Modifier.height(6.dp))
            Text(pr.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(
                "${pr.headBranch} → ${pr.baseBranch}",
                style = MaterialTheme.code.tiny,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${pr.author.ifBlank { "unknown" }} · ${relativeTime(pr.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}
