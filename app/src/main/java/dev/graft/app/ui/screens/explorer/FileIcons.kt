package dev.graft.app.ui.screens.explorer

import androidx.compose.material.icons.Icons
import dev.graft.app.ui.theme.gitColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.graft.app.git.FileNode

/**
 * A distinct icon (and tint) per file type instead of one generic file icon for everything —
 * the same "recognize a file by its icon before you even read the name" convention every
 * proper code editor's file tree uses. Coarser than a full per-extension icon font (this app
 * doesn't ship one), but covers the file types that actually show up in a typical repo.
 */
@Composable
fun fileIconFor(node: FileNode): Pair<ImageVector, androidx.compose.ui.graphics.Color> = fileIconFor(node.name, node.isDirectory)

/** Same mapping as the [FileNode] overload, usable anywhere only a filename is at hand (e.g.
 *  the file editor's top bar, which only ever has a path — not a full tree [FileNode]). */
@Composable
fun fileIconFor(fileName: String, isDirectory: Boolean = false): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    if (isDirectory) return Icons.Filled.Folder to MaterialTheme.colorScheme.primary

    val name = fileName.substringAfterLast('/').lowercase()
    val ext = name.substringAfterLast('.', missingDelimiterValue = "")

    return when {
        name == "readme.md" || name == "readme" -> Icons.Filled.Info to MaterialTheme.colorScheme.secondary
        name == ".gitignore" || name == ".gitattributes" -> Icons.Filled.VisibilityOff to MaterialTheme.colorScheme.onSurfaceVariant
        name == "dockerfile" -> Icons.Filled.Build to MaterialTheme.colorScheme.primary
        ext in setOf("kt", "kts", "java") -> Icons.Filled.Code to MaterialTheme.colorScheme.primary
        ext in setOf("js", "jsx", "ts", "tsx") -> Icons.Filled.Javascript to MaterialTheme.colorScheme.secondary
        ext == "py" -> Icons.Filled.Terminal to MaterialTheme.gitColors.added
        ext in setOf("json") -> Icons.Filled.DataObject to MaterialTheme.colorScheme.secondary
        ext in setOf("yml", "yaml", "toml") -> Icons.Filled.Settings to MaterialTheme.colorScheme.onSurfaceVariant
        ext in setOf("xml", "html", "htm") -> Icons.Filled.Code to MaterialTheme.colorScheme.primary
        ext in setOf("md", "markdown") -> Icons.Filled.Description to MaterialTheme.colorScheme.onSurfaceVariant
        ext in setOf("sql", "db", "sqlite") -> Icons.Filled.Storage to MaterialTheme.gitColors.added
        ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp") -> Icons.Filled.Image to MaterialTheme.colorScheme.secondary
        ext in setOf("gradle", "properties", "env") -> Icons.Filled.Build to MaterialTheme.colorScheme.onSurfaceVariant
        ext in setOf("zip", "tar", "gz", "jar", "apk", "aar") -> Icons.Filled.FolderZip to MaterialTheme.colorScheme.onSurfaceVariant
        ext in setOf("sh", "bash", "zsh") -> Icons.Filled.Terminal to MaterialTheme.colorScheme.onSurfaceVariant
        ext == "txt" -> Icons.Filled.Description to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.Filled.InsertDriveFile to MaterialTheme.colorScheme.onSurfaceVariant
    }
}
