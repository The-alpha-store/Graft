package dev.graft.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.graphics.vector.ImageVector
import java.net.URLEncoder

/**
 * Every destination in the app, in one place.
 *
 * Navigation has two levels:
 *
 * 1. **Home scope** - [REPOS], [DISCOVER], [SETTINGS]: the app's top-level tabs, no repo selected.
 * 2. **Repo scope** - the `repo/{repoId}` graph: [OVERVIEW], [CHANGES], [HISTORY], [FILES], [TOOLS]
 *    as tabs, plus every detail screen (diff, branches, editor...) pushed above them.
 *
 * The repo id lives *in the route*, not in a side variable: a repo's screens can always be
 * re-created from their route alone (process death, deep links, notification taps).
 */
object Routes {
    // ---- Home scope ----
    const val REPOS = "repos"
    const val DISCOVER = "discover"
    const val SETTINGS = "settings"

    // ---- Standalone (no repo) ----
    const val CREDENTIALS = "credentials"
    const val APK_DOWNLOADS = "apk_downloads"
    const val TRANSFERS = "transfers"

    // ---- Repo scope: the graph and its tabs ----
    const val REPO_GRAPH = "repo/{repoId}"
    const val OVERVIEW = "repo/{repoId}/overview"
    const val CHANGES = "repo/{repoId}/changes"
    const val HISTORY = "repo/{repoId}/history"
    const val FILES = "repo/{repoId}/files"
    const val TOOLS = "repo/{repoId}/tools"

    // ---- Repo scope: detail screens (hide the bottom bar) ----
    const val BRANCHES = "repo/{repoId}/branches"
    const val STASH = "repo/{repoId}/stash"
    const val REMOTE = "repo/{repoId}/remote"
    const val TAGS = "repo/{repoId}/tags"
    const val GITIGNORE = "repo/{repoId}/gitignore"
    const val CONFLICTS = "repo/{repoId}/conflicts"
    const val ACTIONS = "repo/{repoId}/actions"
    const val CONSOLE = "repo/{repoId}/console"
    const val SEARCH = "repo/{repoId}/search"
    const val DIFF = "repo/{repoId}/diff/{encodedPath}/{staged}"
    const val EDITOR = "repo/{repoId}/editor/{encodedPath}?line={line}"
    const val BLAME = "repo/{repoId}/blame/{encodedPath}"

    const val ARG_REPO_ID = "repoId"
    const val ARG_PATH = "encodedPath"
    const val ARG_STAGED = "staged"
    const val ARG_LINE = "line"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun repoGraph(id: Long) = "repo/$id"
    fun overview(id: Long) = "repo/$id/overview"
    fun changes(id: Long) = "repo/$id/changes"
    fun history(id: Long) = "repo/$id/history"
    fun files(id: Long) = "repo/$id/files"
    fun tools(id: Long) = "repo/$id/tools"
    fun branches(id: Long) = "repo/$id/branches"
    fun stash(id: Long) = "repo/$id/stash"
    fun remote(id: Long) = "repo/$id/remote"
    fun tags(id: Long) = "repo/$id/tags"
    fun gitignore(id: Long) = "repo/$id/gitignore"
    fun conflicts(id: Long) = "repo/$id/conflicts"
    fun actions(id: Long) = "repo/$id/actions"
    fun console(id: Long) = "repo/$id/console"
    fun search(id: Long) = "repo/$id/search"

    /** [stagedOrCommit] is `"true"`, `"false"`, or `"commit"` (then [path] is a commit sha). */
    fun diff(id: Long, path: String, staged: Boolean) = "repo/$id/diff/${enc(path)}/$staged"
    fun commitDiff(id: Long, sha: String) = "repo/$id/diff/${enc(sha)}/commit"
    fun editor(id: Long, path: String, line: Int? = null) =
        "repo/$id/editor/${enc(path)}" + (line?.let { "?line=$it" } ?: "")
    fun blame(id: Long, path: String) = "repo/$id/blame/${enc(path)}"

    // graft:// deep links. Built as plain strings (no NavController needed) because
    // SyncNotifier makes one for a bare Intent, with no Compose or nav in scope at all.
    fun deepLinkRepo(id: Long) = "graft://repo/$id"
    fun deepLinkActions(id: Long) = "graft://repo/$id/actions"
}

/** The five tabs shown once a repo is open. */
enum class RepoTab(val route: String, val label: String, val icon: ImageVector) {
    OVERVIEW(Routes.OVERVIEW, "Overview", Icons.Filled.Dashboard),
    CHANGES(Routes.CHANGES, "Changes", Icons.Filled.Edit),
    HISTORY(Routes.HISTORY, "History", Icons.Filled.History),
    FILES(Routes.FILES, "Files", Icons.Filled.Folder),
    TOOLS(Routes.TOOLS, "Tools", Icons.Filled.Build);

    fun routeFor(repoId: Long): String = route.replace("{repoId}", repoId.toString())
}

/** The three tabs shown on the Home scope. */
enum class HomeTab(val route: String, val label: String, val icon: ImageVector) {
    REPOS(Routes.REPOS, "Repos", Icons.Filled.Storage),
    DISCOVER(Routes.DISCOVER, "Discover", Icons.Filled.Explore),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
}
