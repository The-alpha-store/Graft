package dev.graft.app.ui.screens.explorer

import android.app.Application
import dev.graft.app.ui.theme.gitColors
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.graft.app.App
import dev.graft.app.data.ExplorerPrefs
import dev.graft.app.data.ExplorerSortMode
import dev.graft.app.data.db.entity.RepoEntity
import dev.graft.app.git.FileNode
import dev.graft.app.git.GitEngine
import dev.graft.app.git.GitFileStatus
import dev.graft.app.git.GitResult
import dev.graft.app.transfer.awaitResult
import dev.graft.app.transfer.formatBytes
import dev.graft.app.ui.components.RepoTitleBlock
import dev.graft.app.ui.components.ConfirmDialog
import dev.graft.app.ui.components.SingleInputDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One row as actually rendered in the flattened tree. [node]'s `relativePath` is always the
 *  real on-disk path to operate on (stage/delete/rename/expand); `node.name` may be a
 *  slash-joined chain like "app/src/main" when [ExplorerPrefs.compactFolders] merged a run of
 *  single-child folders into one row — see [FileExplorerViewModel.listChildren]. */
data class TreeRow(
    val node: FileNode,
    val depth: Int,
    val isExpanded: Boolean,
    val gitStatus: GitFileStatus?,
    val hasDirtyDescendant: Boolean,
    val isCompactedChain: Boolean,
)

data class ExplorerUiState(
    val repo: RepoEntity? = null,
    val childrenCache: Map<String, List<FileNode>> = emptyMap(), // "" = root
    val expandedPaths: Set<String> = emptySet(),
    val gitStatusByPath: Map<String, GitFileStatus> = emptyMap(),
    val visibleRows: List<TreeRow> = emptyList(),
    val showHiddenFiles: Boolean = false,
    val compactFolders: Boolean = true,
    val sortMode: ExplorerSortMode = ExplorerSortMode.NAME,
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val message: String? = null,
    /** "Find file by name" - searches the whole repo, not just expanded folders. */
    val filterQuery: String = "",
    val filterResults: List<FileNode> = emptyList(),
    val isFiltering: Boolean = false,
)

class FileExplorerViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as App
    private val repoRepo = appRef.repoRepository
    private val credRepo = appRef.credentialRepository

    private val _uiState = MutableStateFlow(ExplorerUiState())
    val uiState: StateFlow<ExplorerUiState> = _uiState.asStateFlow()

    private var filterJob: kotlinx.coroutines.Job? = null

    /**
     * Debounced whole-repo filename search. Walks the working tree lazily (skipping `.git`, and
     * dot-folders unless hidden files are shown) and stops at [MAX_FILTER_RESULTS], so even a huge
     * repo answers quickly and never builds a giant list.
     */
    fun setFilter(query: String) {
        _uiState.value = _uiState.value.copy(filterQuery = query)
        filterJob?.cancel()
        val repo = _uiState.value.repo
        if (query.isBlank() || repo == null) {
            _uiState.value = _uiState.value.copy(filterResults = emptyList(), isFiltering = false)
            return
        }
        filterJob = viewModelScope.launch {
            kotlinx.coroutines.delay(180)
            _uiState.value = _uiState.value.copy(isFiltering = true)
            val root = File(repo.fullSavePath)
            val needle = query.trim().lowercase()
            val showHidden = _uiState.value.showHiddenFiles
            val results = withContext(Dispatchers.IO) {
                root.walkTopDown()
                    .onEnter { dir -> dir == root || (dir.name != ".git" && (showHidden || !dir.name.startsWith("."))) }
                    .filter { it.isFile && it.name.lowercase().contains(needle) }
                    .take(MAX_FILTER_RESULTS)
                    .map { f ->
                        FileNode(
                            name = f.name,
                            relativePath = f.relativeTo(root).invariantSeparatorsPath,
                            isDirectory = false,
                            sizeBytes = f.length(),
                        )
                    }
                    .toList()
            }
            if (_uiState.value.filterQuery == query) {
                _uiState.value = _uiState.value.copy(filterResults = results, isFiltering = false)
            }
        }
    }

    private companion object {
        const val MAX_FILTER_RESULTS = 300
    }

    /** [revealPath] (optional) auto-expands the ancestor chain of a specific file so it's
     *  visible on first load — used by the `graft://repo/{id}` deep link. Best-effort:
     *  if compact-folder chains don't line up with the raw path segments, this simply expands
     *  as far as it cleanly can rather than guessing. */
    fun load(repoId: Long, revealPath: String = "") {
        viewModelScope.launch {
            val repo = repoRepo.getById(repoId) ?: return@launch
            _uiState.value = _uiState.value.copy(
                repo = repo, isLoading = true,
                showHiddenFiles = ExplorerPrefs.showHiddenFiles(appRef),
                compactFolders = ExplorerPrefs.compactFolders(appRef),
                sortMode = ExplorerPrefs.sortMode(appRef),
            )
            refresh()
            if (revealPath.isNotBlank()) revealAncestors(revealPath)
        }
    }

    private fun currentDir(targetPath: String = ""): File? = _uiState.value.repo?.let {
        if (targetPath.isBlank()) File(it.fullSavePath) else File(it.fullSavePath, targetPath)
    }

    /** Re-lists the root and every currently-expanded folder, then re-scans git status —
     *  called after any mutation and by pull-to-refresh. Expand state survives a refresh (the
     *  tree doesn't collapse just because something changed), the same way a code editor's
     *  file tree keeps folders open across a background re-index. */
    private suspend fun refresh() {
        val repo = _uiState.value.repo ?: return
        val newCache = mutableMapOf<String, List<FileNode>>()
        newCache[""] = listChildren(repo, "")
        for (path in _uiState.value.expandedPaths) {
            newCache[path] = listChildren(repo, path)
        }
        val statusMap = scanGitStatus(repo)
        _uiState.value = _uiState.value.copy(childrenCache = newCache, gitStatusByPath = statusMap, isLoading = false)
        recomputeVisibleRows()
    }

    fun toggleExpand(node: FileNode) {
        if (!node.isDirectory) return
        val path = node.relativePath
        val expanded = _uiState.value.expandedPaths
        if (path in expanded) {
            _uiState.value = _uiState.value.copy(expandedPaths = expanded - path)
            recomputeVisibleRows()
            return
        }
        viewModelScope.launch {
            val repo = _uiState.value.repo ?: return@launch
            if (path !in _uiState.value.childrenCache) {
                val kids = listChildren(repo, path)
                _uiState.value = _uiState.value.copy(childrenCache = _uiState.value.childrenCache + (path to kids))
            }
            _uiState.value = _uiState.value.copy(expandedPaths = _uiState.value.expandedPaths + path)
            recomputeVisibleRows()
        }
    }

    fun collapseAll() {
        _uiState.value = _uiState.value.copy(expandedPaths = emptySet())
        recomputeVisibleRows()
    }

    private suspend fun revealAncestors(path: String) {
        val repo = _uiState.value.repo ?: return
        val segments = path.split("/").dropLast(1)
        var current = ""
        for (seg in segments) {
            current = if (current.isBlank()) seg else "$current/$seg"
            if (current !in _uiState.value.childrenCache) {
                val kids = listChildren(repo, current)
                _uiState.value = _uiState.value.copy(childrenCache = _uiState.value.childrenCache + (current to kids))
            }
            _uiState.value = _uiState.value.copy(expandedPaths = _uiState.value.expandedPaths + current)
        }
        recomputeVisibleRows()
    }

    /** Lists one directory's immediate children for tree display — hidden-file filtering,
     *  sort order, and single-child folder-chain compaction all happen here, in one place,
     *  so [refresh] and [toggleExpand] can't drift out of sync on how a folder gets listed. */
    private suspend fun listChildren(repo: RepoEntity, path: String): List<FileNode> = withContext(Dispatchers.IO) {
        val showHidden = _uiState.value.showHiddenFiles
        val compact = _uiState.value.compactFolders
        val sortMode = _uiState.value.sortMode

        val dir = if (path.isBlank()) File(repo.fullSavePath) else File(repo.fullSavePath, path)
        val raw = dir.listFiles()?.toList() ?: emptyList()
        val filtered = raw.filter { f ->
            (f.name != ".git" || path.isNotBlank()) && (showHidden || !f.name.startsWith("."))
        }
        val sorted = sortEntries(filtered, sortMode)

        sorted.map { f ->
            val relPath = if (path.isBlank()) f.name else "$path/${f.name}"
            if (f.isDirectory && compact) {
                val (finalPath, chainNames) = compactChain(f, relPath, showHidden)
                FileNode(name = chainNames.joinToString("/"), relativePath = finalPath, isDirectory = true, sizeBytes = 0L)
            } else {
                FileNode(name = f.name, relativePath = relPath, isDirectory = f.isDirectory, sizeBytes = if (f.isFile) f.length() else 0L)
            }
        }
    }

    /** Walks forward through a chain of folders that each contain exactly one subfolder,
     *  merging their names ("app" -> "app/src" -> "app/src/main") until hitting a folder with
     *  zero, one file, or 2+ children — the point where showing them as separate rows again
     *  actually carries information. Same convention as a desktop IDE's "compact folders." */
    private fun compactChain(startDir: File, startRelPath: String, showHidden: Boolean): Pair<String, List<String>> {
        var dir = startDir
        var relPath = startRelPath
        val names = mutableListOf(startDir.name)
        while (true) {
            val kids = dir.listFiles()?.filter { showHidden || !it.name.startsWith(".") } ?: emptyList()
            val onlyChild = kids.singleOrNull() ?: break
            if (!onlyChild.isDirectory) break
            dir = onlyChild
            relPath = "$relPath/${onlyChild.name}"
            names.add(onlyChild.name)
        }
        return relPath to names
    }

    private fun sortEntries(files: List<File>, mode: ExplorerSortMode): List<File> {
        val (dirs, plain) = files.partition { it.isDirectory }
        val comparator: Comparator<File> = when (mode) {
            ExplorerSortMode.NAME -> compareBy { it.name.lowercase() }
            ExplorerSortMode.SIZE -> compareByDescending { it.length() }
            ExplorerSortMode.DATE -> compareByDescending { it.lastModified() }
        }
        // Folders always sort before files regardless of mode — sorting a folder "by size"
        // doesn't mean much (it'd need a recursive size scan), so size/date sorting only
        // really applies within each group.
        return dirs.sortedWith(comparator) + plain.sortedWith(comparator)
    }

    private suspend fun scanGitStatus(repo: RepoEntity): Map<String, GitFileStatus> {
        return when (val opened = GitEngine.openRepo(repo.fullSavePath)) {
            is GitResult.Error -> emptyMap()
            is GitResult.Success -> {
                val git = opened.data
                val map = when (val r = GitEngine.getStatus(git)) {
                    is GitResult.Success -> r.data.associate { it.path to it.status }
                    is GitResult.Error -> emptyMap()
                }
                git.close()
                map
            }
        }
    }

    private fun recomputeVisibleRows() {
        val s = _uiState.value
        val dirtyDirs = mutableSetOf<String>()
        for (p in s.gitStatusByPath.keys) {
            var idx = p.lastIndexOf('/')
            while (idx > 0) {
                dirtyDirs.add(p.substring(0, idx))
                idx = p.lastIndexOf('/', idx - 1)
            }
        }
        val rows = mutableListOf<TreeRow>()
        fun walk(children: List<FileNode>, depth: Int) {
            for (child in children) {
                val isExpanded = child.isDirectory && child.relativePath in s.expandedPaths
                rows += TreeRow(
                    node = child, depth = depth, isExpanded = isExpanded,
                    gitStatus = s.gitStatusByPath[child.relativePath],
                    hasDirtyDescendant = child.relativePath in dirtyDirs,
                    isCompactedChain = child.isDirectory && child.name.contains('/'),
                )
                if (isExpanded) walk(s.childrenCache[child.relativePath] ?: emptyList(), depth + 1)
            }
        }
        walk(s.childrenCache[""] ?: emptyList(), 0)
        _uiState.value = s.copy(visibleRows = rows)
    }

    fun setShowHiddenFiles(value: Boolean) {
        ExplorerPrefs.setShowHiddenFiles(appRef, value)
        _uiState.value = _uiState.value.copy(showHiddenFiles = value)
        viewModelScope.launch { refresh() }
    }

    fun setCompactFolders(value: Boolean) {
        ExplorerPrefs.setCompactFolders(appRef, value)
        _uiState.value = _uiState.value.copy(compactFolders = value)
        viewModelScope.launch { refresh() }
    }

    fun setSortMode(mode: ExplorerSortMode) {
        ExplorerPrefs.setSortMode(appRef, mode)
        _uiState.value = _uiState.value.copy(sortMode = mode)
        viewModelScope.launch { refresh() }
    }

    fun rename(node: FileNode, newName: String) {
        val repo = _uiState.value.repo ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val old = File(repo.fullSavePath, node.relativePath)
                val new = File(old.parentFile, newName)
                old.renameTo(new)
            }
            refresh()
        }
    }

    /** Stages the removal via `git rm` (for anything git already tracks — a plain filesystem
     *  delete followed by a later "Stage" tap used to silently do nothing there, since
     *  AddCommand only ever adds content, never removes an index entry), AND always makes
     *  sure the file/folder is actually gone from disk afterward regardless of whether git
     *  rm did anything. That second part matters for a file or folder that was only just
     *  created and never staged at all — `git rm` has nothing to remove from an index entry
     *  that was never there, so relying on it alone silently failed to delete anything for a
     *  brand-new mistake, which is exactly the case someone deleting it is most likely in. */
    fun delete(node: FileNode) {
        val repo = _uiState.value.repo ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            when (val opened = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> _uiState.value = _uiState.value.copy(isBusy = false, message = opened.message)
                is GitResult.Success -> {
                    val git = opened.data
                    val staged = GitEngine.removeFile(git, node.relativePath) is GitResult.Success
                    git.close()
                    val removedFromDisk = withContext(Dispatchers.IO) { deleteFromDisk(repo, node) }
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        message = when {
                            removedFromDisk && staged -> "Deleted ${node.name} — staged, ready to commit"
                            removedFromDisk -> "Deleted ${node.name}" // wasn't tracked by git yet, nothing to stage
                            else -> "Couldn't delete ${node.name}"
                        },
                    )
                    refresh()
                }
            }
        }
    }

    /** @return true if [node] is gone from disk afterward, whether it needed deleting or
     *  was already gone (e.g. `git rm` already removed a tracked file as a side effect). */
    private fun deleteFromDisk(repo: RepoEntity, node: FileNode): Boolean {
        val diskFile = File(repo.fullSavePath, node.relativePath)
        if (!diskFile.exists()) return true
        return if (node.isDirectory) diskFile.deleteRecursively() else diskFile.delete()
    }

    /** Stages every selected file/folder in one repo open/close pair — same underlying
     *  per-path `git add` as the single-file flow, just looped, so a long-press selection of
     *  a dozen files doesn't mean a dozen separate JGit repo opens. */
    fun bulkStage(nodes: List<FileNode>) {
        val repo = _uiState.value.repo ?: return
        if (nodes.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            when (val opened = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> _uiState.value = _uiState.value.copy(isBusy = false, message = opened.message)
                is GitResult.Success -> {
                    val git = opened.data
                    var failed = 0
                    for (node in nodes) {
                        if (GitEngine.stageFile(git, node.relativePath) is GitResult.Error) failed++
                    }
                    git.close()
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        message = if (failed == 0) "Staged ${nodes.size} item(s)" else "Staged ${nodes.size - failed} of ${nodes.size} — $failed failed",
                    )
                    refresh()
                }
            }
        }
    }

    /** Same fix as [delete], looped — stages whatever git already tracks, then always
     *  confirms every selected item is actually gone from disk regardless. */
    fun bulkDelete(nodes: List<FileNode>) {
        val repo = _uiState.value.repo ?: return
        if (nodes.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            when (val opened = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> _uiState.value = _uiState.value.copy(isBusy = false, message = opened.message)
                is GitResult.Success -> {
                    val git = opened.data
                    for (node in nodes) GitEngine.removeFile(git, node.relativePath)
                    git.close()
                    var failed = 0
                    withContext(Dispatchers.IO) {
                        for (node in nodes) if (!deleteFromDisk(repo, node)) failed++
                    }
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        message = if (failed == 0) "Deleted ${nodes.size} item(s) — staged, ready to commit" else "Deleted ${nodes.size - failed} of ${nodes.size} — $failed failed",
                    )
                    refresh()
                }
            }
        }
    }

    /** New file/folder/import all target the repo root — there's no more "current folder"
     *  concept now that the whole tree is one always-expandable view instead of navigating
     *  screen-to-screen. Trade-off worth knowing: creating something inside a deeply nested
     *  folder means creating it at the root and dragging it in a file manager, or renaming it
     *  with the folder prefix in its name. */
    /** [targetPath] is the folder to create inside — blank means the repo root. Passed
     *  explicitly by the caller (either "" from the root FAB, or a specific folder's own
     *  relativePath from that folder's "New file/folder here" menu item), since the tree has
     *  no single "current folder" the way the old screen-per-folder navigation did. */
    fun createFile(name: String, targetPath: String = "") {
        val dir = currentDir(targetPath) ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    dir.mkdirs()
                    val f = File(dir, name)
                    if (f.exists()) return@withContext "A file named \"$name\" already exists"
                    f.createNewFile()
                    null
                } catch (e: Exception) {
                    e.message ?: "Couldn't create file"
                }
            }
            _uiState.value = _uiState.value.copy(message = result ?: "Created $name")
            if (result == null && targetPath.isNotBlank()) _uiState.value = _uiState.value.copy(expandedPaths = _uiState.value.expandedPaths + targetPath)
            refresh()
        }
    }

    fun createFolder(name: String, targetPath: String = "") {
        val dir = currentDir(targetPath) ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                dir.mkdirs()
                val f = File(dir, name)
                if (f.exists()) "A folder named \"$name\" already exists"
                else if (f.mkdirs()) null
                else "Couldn't create folder"
            }
            _uiState.value = _uiState.value.copy(
                message = result ?: "Created $name — note: git won't track an empty folder until it has a file in it",
            )
            if (result == null && targetPath.isNotBlank()) _uiState.value = _uiState.value.copy(expandedPaths = _uiState.value.expandedPaths + targetPath)
            refresh()
        }
    }

    /** Copies one or more picked files (from the system file manager / photos app / etc,
     * via SAF) into [targetPath] (blank = repo root), preserving their original filenames. */
    fun importFiles(context: Context, uris: List<Uri>, targetPath: String = "") {
        val dir = currentDir(targetPath) ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            val imported = withContext(Dispatchers.IO) {
                dir.mkdirs()
                var count = 0
                for (uri in uris) {
                    try {
                        val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "imported_file"
                        val dest = File(dir, name)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                        count++
                    } catch (e: Exception) {
                        // Best-effort: skip a file that failed to copy rather than aborting
                        // the whole batch, since one bad URI shouldn't block the rest.
                    }
                }
                count
            }
            _uiState.value = _uiState.value.copy(isBusy = false, message = "Imported $imported file(s)")
            if (targetPath.isNotBlank()) _uiState.value = _uiState.value.copy(expandedPaths = _uiState.value.expandedPaths + targetPath)
            refresh()
        }
    }

    /** Recursively copies an entire picked folder (via SAF's "open document tree" picker)
     * into a new subfolder inside [targetPath] (blank = repo root), named after the picked folder. */
    fun importFolder(context: Context, treeUri: Uri, targetPath: String = "") {
        val dir = currentDir(targetPath) ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            val result = withContext(Dispatchers.IO) {
                try {
                    dir.mkdirs()
                    val pickedRoot = DocumentFile.fromTreeUri(context, treeUri)
                        ?: return@withContext "Couldn't open that folder"
                    val destRoot = File(dir, pickedRoot.name ?: "imported_folder")
                    if (destRoot.exists()) return@withContext "A folder named \"${destRoot.name}\" already exists"
                    val fileCount = copyDocumentTree(context, pickedRoot, destRoot)
                    "Imported ${destRoot.name} ($fileCount file(s))"
                } catch (e: Exception) {
                    e.message ?: "Import failed"
                }
            }
            _uiState.value = _uiState.value.copy(isBusy = false, message = result)
            if (targetPath.isNotBlank()) _uiState.value = _uiState.value.copy(expandedPaths = _uiState.value.expandedPaths + targetPath)
            refresh()
        }
    }

    private fun copyDocumentTree(context: Context, source: DocumentFile, destDir: File): Int {
        destDir.mkdirs()
        var count = 0
        for (child in source.listFiles()) {
            if (child.isDirectory) {
                count += copyDocumentTree(context, child, File(destDir, child.name ?: "folder"))
            } else {
                val name = child.name ?: continue
                val destFile = File(destDir, name)
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                count++
            }
        }
        return count
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }

    fun push() {
        val repo = _uiState.value.repo ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            val credential = if (repo.credentialId != 0L) credRepo.getById(repo.credentialId) else null
            val manager = getApplication<dev.graft.app.App>().transferManager
            // A background transfer: visible in the Transfers list, survives leaving this screen.
            val outcome = manager.enqueue(
                kind = dev.graft.app.transfer.TransferKind.PUSH, repoId = repo.id,
                title = repo.name, watchDir = java.io.File(repo.fullSavePath),
            ) { reporter ->
                manager.withGit(repo.fullSavePath) { git ->
                    GitEngine.push(git, credential = credential, monitor = reporter.monitor)
                }
            }.awaitResult()
            when (outcome) {
                is GitResult.Error -> {
                    repoRepo.markError(repo.id, outcome.message)
                    _uiState.value = _uiState.value.copy(isBusy = false, message = outcome.message)
                }
                is GitResult.Success -> {
                    repoRepo.markSyncSuccess(repo.id)
                    _uiState.value = _uiState.value.copy(isBusy = false, message = "Pushed")
                }
            }
        }
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

private val TREE_INDENT = 18.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    repoId: Long,
    revealPath: String = "",
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenBlame: (String) -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenGitignore: () -> Unit = {},
    onOpenActions: () -> Unit = {},
    vm: FileExplorerViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsState()
    val snack = remember { SnackbarHostState() }
    val context = LocalContext.current
    var nodePendingRename by remember { mutableStateOf<FileNode?>(null) }
    var nodePendingDelete by remember { mutableStateOf<FileNode?>(null) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    // Which folder "New file/folder here" and Import should create inside — "" means the
    // repo root. Set right before showing a creation dialog or launching an import picker,
    // from either the root FAB (always "") or a specific folder row's own "..." menu.
    var createTargetPath by remember { mutableStateOf("") }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var nodeForProperties by remember { mutableStateOf<FileNode?>(null) }
    BackHandler(enabled = showFilter) { showFilter = false; vm.setFilter("") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val copyScope = androidx.compose.runtime.rememberCoroutineScope()
    val selectionMode = selectedPaths.isNotEmpty()

    val importFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) vm.importFiles(context, uris, createTargetPath) }

    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { vm.importFolder(context, it, createTargetPath) } }

    LaunchedEffect(repoId) { vm.load(repoId, revealPath) }
    LaunchedEffect(state.message) {
        state.message?.let { snack.showSnackbar(it); vm.dismissMessage() }
    }
    // Selection only makes sense against rows still on screen — if the tree refreshes out
    // from under an active selection (rename/delete elsewhere, a pull, etc.) just drop
    // whatever's no longer visible rather than risk acting on stale paths.
    LaunchedEffect(state.visibleRows) {
        val currentPaths = state.visibleRows.map { it.node.relativePath }.toSet()
        if (selectedPaths.any { it !in currentPaths }) selectedPaths = selectedPaths.intersect(currentPaths)
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedPaths.size} selected", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { selectedPaths = emptySet() }) { Icon(Icons.Filled.Close, "Cancel selection") }
                    },
                    actions = {
                        val selectedNodes = state.visibleRows.map { it.node }.filter { it.relativePath in selectedPaths }
                        IconButton(onClick = { vm.bulkStage(selectedNodes); selectedPaths = emptySet() }, enabled = !state.isBusy) {
                            Icon(Icons.Filled.AddCircleOutline, "Stage selected")
                        }
                        IconButton(onClick = { showBulkDeleteConfirm = true }, enabled = !state.isBusy) {
                            Icon(Icons.Filled.Delete, "Delete selected", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { RepoTitleBlock(state.repo?.name ?: "Files", state.repo?.branch) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = onOpenSearch, enabled = !state.isBusy) {
                            Icon(Icons.Filled.Search, "Search this repo")
                        }
                        IconButton(onClick = vm::push, enabled = !state.isBusy) {
                            if (state.isBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Filled.ArrowUpward, "Push")
                        }
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) { Icon(Icons.Filled.MoreVert, "Tree options") }
                            DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Show hidden files") },
                                    trailingIcon = { Checkbox(checked = state.showHiddenFiles, onCheckedChange = null) },
                                    onClick = { vm.setShowHiddenFiles(!state.showHiddenFiles) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Compact folders") },
                                    trailingIcon = { Checkbox(checked = state.compactFolders, onCheckedChange = null) },
                                    onClick = { vm.setCompactFolders(!state.compactFolders) },
                                )
                                HorizontalDivider()
                                SortModeItem("Sort by name", ExplorerSortMode.NAME, state.sortMode) { vm.setSortMode(it); showOverflowMenu = false }
                                SortModeItem("Sort by size", ExplorerSortMode.SIZE, state.sortMode) { vm.setSortMode(it); showOverflowMenu = false }
                                SortModeItem("Sort by date", ExplorerSortMode.DATE, state.sortMode) { vm.setSortMode(it); showOverflowMenu = false }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Find file by name") },
                                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                                    onClick = { showOverflowMenu = false; showFilter = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Collapse all") },
                                    leadingIcon = { Icon(Icons.Filled.UnfoldLess, null) },
                                    onClick = { vm.collapseAll(); showOverflowMenu = false },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                FileExplorerFab(
                    expanded = fabExpanded,
                    onToggle = { fabExpanded = !fabExpanded },
                    onNewFile = { fabExpanded = false; createTargetPath = ""; showNewFileDialog = true },
                    onNewFolder = { fabExpanded = false; createTargetPath = ""; showNewFolderDialog = true },
                    onImportFiles = { fabExpanded = false; createTargetPath = ""; importFilesLauncher.launch(arrayOf("*/*")) },
                    onImportFolder = { fabExpanded = false; createTargetPath = ""; importFolderLauncher.launch(null) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snack) { d -> Snackbar(d) } },
    ) { pad ->
        Box(
            Modifier.fillMaxSize().let {
                if (fabExpanded) it.clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { fabExpanded = false } else it
            },
        ) {
            when {
                showFilter -> FileFilterPanel(
                    query = state.filterQuery,
                    results = state.filterResults,
                    isFiltering = state.isFiltering,
                    onQueryChange = vm::setFilter,
                    onOpen = { node -> onOpenFile(node.relativePath) },
                    onClose = { showFilter = false; vm.setFilter("") },
                    modifier = Modifier.fillMaxSize().padding(pad),
                )
                state.isLoading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.visibleRows.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Empty folder", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Tap + to create a file, or import from your file manager",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> Column(Modifier.fillMaxSize().padding(pad)) {
                ExplorerActionHeader(
                    onGitignore = onOpenGitignore,
                    onActions = onOpenActions,
                    onFind = { showFilter = true },
                )
                LazyColumn(
                    // Side margins make each row a card floating inside the page, as in the original.
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 96.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(state.visibleRows, key = { it.node.relativePath }) { row ->
                        TreeRowItem(
                            row = row,
                            selectionMode = selectionMode,
                            selected = row.node.relativePath in selectedPaths,
                            onClick = {
                                when {
                                    selectionMode -> selectedPaths = if (row.node.relativePath in selectedPaths) selectedPaths - row.node.relativePath else selectedPaths + row.node.relativePath
                                    row.node.isDirectory -> vm.toggleExpand(row.node)
                                    else -> onOpenFile(row.node.relativePath)
                                }
                            },
                            onLongClick = { selectedPaths = selectedPaths + row.node.relativePath },
                            onRename = { nodePendingRename = row.node },
                            onDelete = { nodePendingDelete = row.node },
                            onBlame = { onOpenBlame(row.node.relativePath) },
                            onCopyPath = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(row.node.relativePath))
                                copyScope.launch { snack.showSnackbar("Copied ${row.node.relativePath}") }
                            },
                            onProperties = { nodeForProperties = row.node },
                            onNewFileHere = { createTargetPath = row.node.relativePath; showNewFileDialog = true },
                            onNewFolderHere = { createTargetPath = row.node.relativePath; showNewFolderDialog = true },
                            onImportFilesHere = { createTargetPath = row.node.relativePath; importFilesLauncher.launch(arrayOf("*/*")) },
                        )
                    }
                }
                }
            }
        }
    }

    if (showBulkDeleteConfirm) {
        val selectedNodes = state.visibleRows.map { it.node }.filter { it.relativePath in selectedPaths }
        ConfirmDialog(
            title = "Delete ${selectedNodes.size} item(s)?",
            body = "This stages the removal of everything selected — commit and push to remove it from the remote too. Can't be undone locally.",
            confirmLabel = "Delete",
            danger = true,
            onDismiss = { showBulkDeleteConfirm = false },
            onConfirm = { vm.bulkDelete(selectedNodes); showBulkDeleteConfirm = false; selectedPaths = emptySet() },
        )
    }

    nodeForProperties?.let { node ->
        state.repo?.let { repo ->
            PropertiesDialog(
                repoRoot = File(repo.fullSavePath),
                node = node,
                gitStatus = state.gitStatusByPath[node.relativePath],
                onDismiss = { nodeForProperties = null },
            )
        }
    }

    if (showNewFileDialog) {
        SingleInputDialog("New File", "File name", "",
            onConfirm = { vm.createFile(it, createTargetPath); showNewFileDialog = false }, onDismiss = { showNewFileDialog = false })
    }
    if (showNewFolderDialog) {
        SingleInputDialog("New Folder", "Folder name", "",
            onConfirm = { vm.createFolder(it, createTargetPath); showNewFolderDialog = false }, onDismiss = { showNewFolderDialog = false })
    }
    nodePendingRename?.let { node ->
        SingleInputDialog("Rename", "New name", node.name.substringAfterLast('/'),
            onConfirm = { vm.rename(node, it); nodePendingRename = null }, onDismiss = { nodePendingRename = null })
    }
    nodePendingDelete?.let { node ->
        ConfirmDialog(
            title = "Delete ${node.name}?",
            body = if (node.isDirectory) "Deletes this folder and everything inside it, and stages the removal."
                   else "Stages the removal — commit and push to remove it from the remote too.",
            confirmLabel = "Delete", danger = true,
            onDismiss = { nodePendingDelete = null },
            onConfirm = { vm.delete(node); nodePendingDelete = null },
        )
    }
}

@Composable
private fun SortModeItem(label: String, mode: ExplorerSortMode, current: ExplorerSortMode, onSelect: (ExplorerSortMode) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            Icon(
                if (current == mode) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                null, tint = if (current == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = { onSelect(mode) },
    )
}

/**
 * One row in the flattened tree. Deliberately flat (no per-row card/border) rather than the
 * AppCard-per-item treatment used elsewhere in the app — a dense tree with rounded card
 * chrome around every single row, several levels deep, stops reading as a tree at all. The
 * screen's own background carries the "glass" identity; individual rows are plain, VS
 * Code-style list items with indentation guides doing the visual structuring instead.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TreeRowItem(
    row: TreeRow,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onBlame: () -> Unit,
    onCopyPath: () -> Unit,
    onProperties: () -> Unit,
    onNewFileHere: () -> Unit,
    onNewFolderHere: () -> Unit,
    onImportFilesHere: () -> Unit,
) {
    val node = row.node
    var showMenu by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    val statusColor = when (row.gitStatus) {
        GitFileStatus.ADDED -> MaterialTheme.gitColors.added
        GitFileStatus.MODIFIED, GitFileStatus.RENAMED, GitFileStatus.TYPE_CHANGED -> MaterialTheme.gitColors.modified
        GitFileStatus.DELETED, GitFileStatus.CONFLICTED -> MaterialTheme.colorScheme.error
        null -> null
    }
    val statusLetter = when (row.gitStatus) {
        GitFileStatus.ADDED -> "A"
        GitFileStatus.MODIFIED -> "M"
        GitFileStatus.DELETED -> "D"
        GitFileStatus.RENAMED -> "R"
        GitFileStatus.TYPE_CHANGED -> "T"
        GitFileStatus.CONFLICTED -> "!"
        null -> null
    }

    Row(
        Modifier
            .fillMaxWidth()
            // Indent first, *then* clip/background: the card itself steps in with depth.
            .padding(start = (row.depth * 14).dp, top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .padding(start = 6.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            if (node.isDirectory) {
                val rotation by androidx.compose.animation.core.animateFloatAsState(if (row.isExpanded) 90f else 0f, label = "chevron")
                Icon(
                    Icons.Filled.ChevronRight, null,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(2.dp))
        }

        if (node.isDirectory) {
            Icon(
                if (row.isExpanded) Icons.Filled.FolderOpen else Icons.Filled.Folder, null,
                Modifier.size(24.dp), tint = MaterialTheme.colorScheme.secondary,
            )
        } else {
            ExtensionBadge(node.name)
        }
        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Text(
                node.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = statusColor ?: MaterialTheme.colorScheme.onSurface,
                fontWeight = if (node.isDirectory || (row.hasDirtyDescendant && node.isDirectory)) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (!node.isDirectory && node.sizeBytes > 0) {
                Text(
                    formatBytes(node.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            statusLetter != null -> Text(
                statusLetter, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                color = statusColor ?: MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp),
            )
            row.hasDirtyDescendant && node.isDirectory -> Box(
                Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.gitColors.added).padding(end = 4.dp),
            )
        }

        // Compacted chain rows ("app/src/main") skip the per-row menu — renaming/deleting a
        // merged multi-folder row is ambiguous (which segment?), so only the leaf folders you
        // reach by expanding further, and plain files, get this menu.
        if (!selectionMode && !node.isDirectory) {
            IconButton(onClick = onClick, Modifier.size(30.dp)) {
                Icon(Icons.Filled.Edit, "Open in editor", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (!selectionMode && !row.isCompactedChain) {
            Box {
                IconButton(onClick = { showMenu = true }, Modifier.size(30.dp)) {
                    Icon(Icons.Filled.MoreVert, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (node.isDirectory) {
                        DropdownMenuItem(text = { Text("New file here") }, onClick = { showMenu = false; onNewFileHere() },
                            leadingIcon = { Icon(Icons.Filled.NoteAdd, null) })
                        DropdownMenuItem(text = { Text("New folder here") }, onClick = { showMenu = false; onNewFolderHere() },
                            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) })
                        DropdownMenuItem(text = { Text("Import files here") }, onClick = { showMenu = false; onImportFilesHere() },
                            leadingIcon = { Icon(Icons.Filled.UploadFile, null) })
                        HorizontalDivider()
                    }
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { showMenu = false; onRename() },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) })
                    if (!node.isDirectory) {
                        DropdownMenuItem(text = { Text("Blame") }, onClick = { showMenu = false; onBlame() },
                            leadingIcon = { Icon(Icons.Filled.History, null) })
                    }
                    DropdownMenuItem(text = { Text("Copy path") }, onClick = { showMenu = false; onCopyPath() },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) })
                    DropdownMenuItem(text = { Text("Properties") }, onClick = { showMenu = false; onProperties() },
                        leadingIcon = { Icon(Icons.Filled.Info, null) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { showMenu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) })
                }
            }
        }
    }
}

/**
 * Every way to add something to this repo, in one place — replaces what used to be a
 * dropdown mixing file creation with file import. Same speed-dial pattern as the repo list's
 * FAB: tap to reveal four labeled options, tap one to act.
 */
@Composable
private fun FileExplorerFab(
    expanded: Boolean, onToggle: () -> Unit,
    onNewFile: () -> Unit, onNewFolder: () -> Unit, onImportFiles: () -> Unit, onImportFolder: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.End) {
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(expandFrom = Alignment.Bottom),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExplorerFabOption("Import folder", Icons.Filled.DriveFolderUpload, onImportFolder)
                ExplorerFabOption("Import files", Icons.Filled.UploadFile, onImportFiles)
                ExplorerFabOption("New folder", Icons.Filled.CreateNewFolder, onNewFolder)
                ExplorerFabOption("New file", Icons.Filled.NoteAdd, onNewFile)
                Spacer(Modifier.height(4.dp))
            }
        }
        FloatingActionButton(
            onClick = onToggle,
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        ) {
            val rotation by androidx.compose.animation.core.animateFloatAsState(if (expanded) 45f else 0f, label = "fabRotation")
            Icon(Icons.Filled.Add, if (expanded) "Close" else "Add", modifier = Modifier.graphicsLayer { rotationZ = rotation })
        }
    }
}

@Composable
private fun ExplorerFabOption(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            tonalElevation = 3.dp, shadowElevation = 2.dp,
            shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.clickable(onClick = onClick),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) { Icon(icon, label, modifier = Modifier.size(20.dp)) }
    }
}
