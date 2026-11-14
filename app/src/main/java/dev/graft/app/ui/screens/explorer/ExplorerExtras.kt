package dev.graft.app.ui.screens.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.graft.app.ui.theme.gitColors
import dev.graft.app.ui.theme.hsl
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.graft.app.git.FileNode
import dev.graft.app.git.GitFileStatus
import dev.graft.app.transfer.formatBytes
import dev.graft.app.ui.theme.code
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * "Find a file by name" - the whole repo, not just the folders you've expanded. Replaces the tree
 * while open; results are flat rows (file name first, its folder underneath) and tapping one opens it.
 */
@Composable
internal fun FileFilterPanel(
    query: String,
    results: List<FileNode>,
    isFiltering: Boolean,
    onQueryChange: (String) -> Unit,
    onOpen: (FileNode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Find a file by name") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close search") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).focusRequester(focus),
        )
        when {
            query.isBlank() -> Hint("Type part of a file name. Searches the whole repository.")
            isFiltering && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                CircularProgressIndicator(Modifier.padding(top = 32.dp))
            }
            results.isEmpty() -> Hint("No files match \"${query.trim()}\"")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                items(results, key = { it.relativePath }) { node ->
                    val folder = node.relativePath.substringBeforeLast('/', missingDelimiterValue = "")
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(node) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Description, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(node.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (folder.isNotEmpty()) {
                                Text(
                                    folder, style = MaterialTheme.code.tiny, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(formatBytes(node.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

private data class FolderStats(val files: Int, val bytes: Long)

/** Name, type, location, size (folders: recursive), modified time, and git status of one item. */
@Composable
internal fun PropertiesDialog(
    repoRoot: File,
    node: FileNode,
    gitStatus: GitFileStatus?,
    onDismiss: () -> Unit,
) {
    val target = remember(node.relativePath) { File(repoRoot, node.relativePath) }
    val stats by produceState<FolderStats?>(initialValue = null, node.relativePath) {
        value = if (node.isDirectory) {
            withContext(Dispatchers.IO) {
                var files = 0
                var bytes = 0L
                target.walkTopDown().onEnter { it == target || it.name != ".git" }.forEach {
                    if (it.isFile) { files++; bytes += it.length() }
                }
                FolderStats(files, bytes)
            }
        } else null
    }
    val modified = remember(target) {
        val millis = target.lastModified()
        if (millis > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis)) else "Unknown"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PropertyRow("Type", if (node.isDirectory) "Folder" else "File")
                PropertyRow("Path", node.relativePath, mono = true)
                PropertyRow("Location", target.absolutePath, mono = true)
                PropertyRow(
                    "Size",
                    if (node.isDirectory) {
                        stats?.let { "${formatBytes(it.bytes)} · ${it.files} file${if (it.files == 1) "" else "s"}" } ?: "Calculating…"
                    } else formatBytes(target.length()),
                )
                PropertyRow("Modified", modified)
                if (gitStatus != null) {
                    PropertyRow("Git status", gitStatus.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PropertyRow(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, style = if (mono) MaterialTheme.code.small else MaterialTheme.typography.bodyMedium)
    }
}


/**
 * The little colored square in front of a file name - its extension in a tinted tile (`KT`, `YML`,
 * `MD`...), the way the original app drew its tree. The tint is a hue per file family at a lightness
 * chosen for the current theme, so it stays legible in both light and dark.
 */
@Composable
internal fun ExtensionBadge(fileName: String, modifier: Modifier = Modifier) {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val dark = MaterialTheme.gitColors.isDark
    val hue: Float? = when (ext) {
        "kt", "kts" -> 262f
        "java" -> 28f
        "yml", "yaml" -> 190f
        "xml", "json" -> 175f
        "md", "txt" -> 145f
        "gradle", "properties", "toml" -> 40f
        "sh", "bat" -> 210f
        "png", "jpg", "jpeg", "webp", "svg", "gif" -> 300f
        else -> null
    }
    val tint = if (hue != null) hsl(hue, 0.65f, if (dark) 0.68f else 0.36f) else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (ext.length in 1..4) ext.uppercase() else "F",
            style = MaterialTheme.code.tiny.copy(fontSize = 8.sp),
            fontWeight = FontWeight.Bold,
            color = tint,
            maxLines = 1,
        )
    }
}

/** Shortcuts pinned above the tree: the repo's `.gitignore`, its CI, and whole-repo file search. */
@Composable
internal fun ExplorerActionHeader(
    onGitignore: () -> Unit,
    onActions: () -> Unit,
    onFind: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(onClick = onGitignore, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
            Icon(Icons.Filled.Edit, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(".gitignore", style = MaterialTheme.typography.labelLarge)
        }
        OutlinedButton(onClick = onActions, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
            Icon(Icons.Filled.PlayCircleOutline, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("CI Actions", style = MaterialTheme.typography.labelLarge)
        }
        OutlinedButton(onClick = onFind, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
            Icon(Icons.Filled.Search, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Find file", style = MaterialTheme.typography.labelLarge)
        }
    }
}
