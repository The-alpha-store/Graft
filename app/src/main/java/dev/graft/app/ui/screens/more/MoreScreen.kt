package dev.graft.app.ui.screens.more

import androidx.compose.animation.core.animateFloatAsState
import dev.graft.app.ui.theme.gitColors
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.graft.app.App
import dev.graft.app.ui.components.AppCard
import dev.graft.app.ui.components.RepoTitleBlock

private data class ToolTile(val label: String, val icon: ImageVector, val onClick: () -> Unit, val tint: Color? = null)
private data class ToolCategory(val title: String, val tools: List<ToolTile>)

/**
 * The "Tools" tab — a single, predictable home for every repo-scoped git
 * action (branches, tags, stash, remotes, files, conflicts, .gitignore).
 *
 * Previously this screen duplicated navigation that also lived in the
 * Changes screen's icon row *and* its overflow sheet, and mixed in
 * app-level destinations (Discover/Credentials/Settings) that already live
 * on the Repos tab. Now there's exactly one path to each tool, this screen
 * only holds things that act on the *current repo*, and a header always
 * shows which repo that is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    repoId: Long?,
    onBack: () -> Unit,
    onOpenBranches: () -> Unit,
    onOpenStash: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenGitignore: () -> Unit,
    onOpenConflicts: () -> Unit,
    onOpenActions: () -> Unit,
    onOpenConsole: () -> Unit,
) {
    val context = LocalContext.current
    val repoInfo by produceState<Pair<String, String>?>(initialValue = null, repoId) {
        value = if (repoId == null) null else {
            val app = context.applicationContext as App
            val repo = app.repoRepository.getById(repoId)
            repo?.let { it.name to it.branch }
        }
    }

    val git = MaterialTheme.gitColors
    val categories = listOf(
        ToolCategory(
            title = "Branches & history",
            tools = listOf(
                ToolTile("Branches", Icons.Filled.AccountTree, onOpenBranches),
                ToolTile("Tags", Icons.Filled.Label, onOpenTags),
                ToolTile("Remotes", Icons.Filled.Cloud, onOpenRemote, tint = MaterialTheme.colorScheme.secondary),
            ),
        ),
        ToolCategory(
            title = "Working tree",
            tools = listOf(
                ToolTile("Stash", Icons.Filled.Inventory2, onOpenStash),
                ToolTile("Conflicts", Icons.Filled.Warning, onOpenConflicts, tint = git.conflict),
                ToolTile(".gitignore", Icons.Filled.Block, onOpenGitignore),
            ),
        ),
        ToolCategory(
            title = "Automation",
            tools = listOf(
                ToolTile("Actions", Icons.Filled.PlayCircleOutline, onOpenActions, tint = MaterialTheme.colorScheme.secondary),
                ToolTile("Console", Icons.Filled.Terminal, onOpenConsole),
            ),
        ),
    )

    Scaffold(
        topBar = {
            dev.graft.app.ui.components.ScreenTopBar(
                titleContent = {
                    val info = repoInfo
                    if (info != null) RepoTitleBlock(info.first, info.second)
                    else Text("Tools", style = MaterialTheme.typography.titleLarge)
                },
                onBack = onBack,
            )
        },
    ) { pad ->
        if (repoId == null) {
            NoRepoSelected(Modifier.padding(pad))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(pad),
            ) {
                categories.forEach { category ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        dev.graft.app.ui.components.SectionHeader(category.title)
                    }
                    items(category.tools) { tool -> ToolCard(tool) }
                }
            }
        }
    }
}

@Composable
private fun NoRepoSelected(modifier: Modifier = Modifier) {
    dev.graft.app.ui.components.EmptyState(
        icon = Icons.Filled.FolderOff,
        title = "No repo selected",
        message = "Pick a repo from Home to see its tools here.",
        modifier = modifier,
    )
}

@Composable
private fun ToolCard(tool: ToolTile) {
    val tint = tool.tint ?: MaterialTheme.colorScheme.primary
    AppCard(Modifier.fillMaxWidth(), onClick = tool.onClick) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tool.icon, null, tint = tint, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                tool.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}
