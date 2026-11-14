package dev.graft.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import dev.graft.app.ui.screens.actions.ActionsScreen
import dev.graft.app.ui.screens.apkexplorer.ApkExplorerScreen
import dev.graft.app.ui.screens.blame.BlameScreen
import dev.graft.app.ui.screens.branches.BranchesScreen
import dev.graft.app.ui.screens.changes.ChangesScreen
import dev.graft.app.ui.screens.conflicts.ConflictsScreen
import dev.graft.app.ui.screens.console.GitConsoleScreen
import dev.graft.app.ui.screens.credential.CredentialScreen
import dev.graft.app.ui.screens.diff.DiffScreen
import dev.graft.app.ui.screens.discover.DiscoverScreen
import dev.graft.app.ui.screens.editor.FileEditorScreen
import dev.graft.app.ui.screens.explorer.FileExplorerScreen
import dev.graft.app.ui.screens.gitignore.GitignoreScreen
import dev.graft.app.ui.screens.log.LogScreen
import dev.graft.app.ui.screens.more.MoreScreen
import dev.graft.app.ui.screens.overview.OverviewScreen
import dev.graft.app.ui.screens.remote.RemoteScreen
import dev.graft.app.ui.screens.repolist.RepoListScreen
import dev.graft.app.ui.screens.search.SearchScreen
import dev.graft.app.ui.screens.settings.SettingsScreen
import dev.graft.app.ui.screens.stash.StashScreen
import dev.graft.app.ui.screens.tags.TagsScreen
import dev.graft.app.ui.screens.transfers.TransfersScreen
import java.net.URLDecoder

private const val SLIDE_MS = 240
private const val FADE_MS = 160

private val repoIdArg = navArgument(Routes.ARG_REPO_ID) { type = NavType.LongType }
private val pathArg = navArgument(Routes.ARG_PATH) { type = NavType.StringType }

/** 0 = a Home tab, 1 = a repo tab, null = anything else (detail screens, credentials...). */
private fun scopeOf(route: String?): Int? = when {
    HomeTab.entries.any { it.route == route } -> 0
    RepoTab.entries.any { it.route == route } -> 1
    else -> null
}

/**
 * True when both ends of a transition are tabs of the *same* bar - sibling tabs have no
 * forward/back relationship, so they cross-fade; everything else (opening a repo, pushing a detail
 * screen) slides in the direction of travel.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean {
    val from = scopeOf(initialState.destination.route)
    val to = scopeOf(targetState.destination.route)
    return from != null && from == to
}

private fun NavBackStackEntry.repoId(): Long = arguments?.getLong(Routes.ARG_REPO_ID) ?: -1L

private fun NavBackStackEntry.decodedPath(): String =
    URLDecoder.decode(arguments?.getString(Routes.ARG_PATH).orEmpty(), "UTF-8")

/** Leaves the repo scope for Home - also when there's no Home beneath (deep link / notification). */
private fun NavHostController.leaveRepo() {
    if (!popBackStack(Routes.REPOS, inclusive = false)) {
        navigate(Routes.REPOS) { popUpTo(graph.id) { inclusive = true } }
    }
}

/**
 * The navigation graph.
 *
 * Each repo is its own nested graph (`repo/{repoId}`). The five tab ViewModels are scoped to that
 * graph's back-stack entry rather than to the individual tab: switching tabs keeps a half-typed
 * commit message and loaded status alive, and leaving the repo discards all of it - so repo A's
 * state can never leak into repo B.
 */
@Composable
fun AppNavHost(nav: NavHostController) {
    NavHost(
        navController = nav,
        startDestination = Routes.REPOS,
        enterTransition = {
            if (isTabSwitch()) fadeIn(tween(FADE_MS))
            else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(SLIDE_MS))
        },
        exitTransition = {
            if (isTabSwitch()) fadeOut(tween(FADE_MS))
            else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(SLIDE_MS))
        },
        popEnterTransition = {
            if (isTabSwitch()) fadeIn(tween(FADE_MS))
            else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(SLIDE_MS))
        },
        popExitTransition = {
            if (isTabSwitch()) fadeOut(tween(FADE_MS))
            else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(SLIDE_MS))
        },
    ) {
        // ---------------------------------------------------------------- Home scope
        composable(Routes.REPOS) {
            RepoListScreen(
                onOpenRepo = { id -> nav.navigate(Routes.overview(id)) },
                onOpenCredentials = { nav.navigate(Routes.CREDENTIALS) },
                onOpenDiscover = { nav.navigateHomeTab(HomeTab.DISCOVER) },
                onOpenSettings = { nav.navigateHomeTab(HomeTab.SETTINGS) },
                onOpenTransfers = { nav.navigate(Routes.TRANSFERS) },
            )
        }
        composable(Routes.DISCOVER) { DiscoverScreen() }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenApkDownloads = { nav.navigate(Routes.APK_DOWNLOADS) },
                onOpenCredentials = { nav.navigate(Routes.CREDENTIALS) },
            )
        }
        composable(Routes.CREDENTIALS) { CredentialScreen(onBack = { nav.popBackStack() }) }
        composable(
            Routes.TRANSFERS,
            deepLinks = listOf(navDeepLink { uriPattern = "graft://transfers" }),
        ) {
            TransfersScreen(
                onBack = { if (!nav.popBackStack()) nav.navigate(Routes.REPOS) },
                onOpenRepo = { id -> nav.navigate(Routes.overview(id)) },
            )
        }
        composable(Routes.APK_DOWNLOADS) { ApkExplorerScreen(onBack = { nav.popBackStack() }) }

        // ---------------------------------------------------------------- Repo scope
        navigation(
            startDestination = Routes.OVERVIEW,
            route = Routes.REPO_GRAPH,
            arguments = listOf(repoIdArg),
        ) {
            // ---- tabs ----
            composable(
                Routes.OVERVIEW,
                arguments = listOf(repoIdArg),
                deepLinks = listOf(navDeepLink { uriPattern = "graft://repo/{repoId}" }),
            ) { entry ->
                val id = entry.repoId()
                val owner = remember(entry) { nav.getBackStackEntry(Routes.REPO_GRAPH) }
                OverviewScreen(
                    repoId = id,
                    onBack = { nav.leaveRepo() },
                    onOpenChanges = { nav.navigateRepoTab(id, RepoTab.CHANGES) },
                    onOpenHistory = { nav.navigateRepoTab(id, RepoTab.HISTORY) },
                    onOpenBranches = { nav.navigate(Routes.branches(id)) },
                    onOpenConflicts = { nav.navigate(Routes.conflicts(id)) },
                    onOpenCommit = { sha -> nav.navigate(Routes.commitDiff(id, sha)) },
                    vm = viewModel(owner),
                )
            }
            composable(Routes.CHANGES, arguments = listOf(repoIdArg)) { entry ->
                val id = entry.repoId()
                val owner = remember(entry) { nav.getBackStackEntry(Routes.REPO_GRAPH) }
                ChangesScreen(
                    repoId = id,
                    onBack = { nav.navigateRepoTab(id, RepoTab.OVERVIEW) },
                    onOpenConflicts = { nav.navigate(Routes.conflicts(id)) },
                    onOpenActions = { nav.navigate(Routes.actions(id)) },
                    onOpenDiff = { path, staged -> nav.navigate(Routes.diff(id, path, staged)) },
                    vm = viewModel(owner),
                )
            }
            composable(Routes.HISTORY, arguments = listOf(repoIdArg)) { entry ->
                val id = entry.repoId()
                val owner = remember(entry) { nav.getBackStackEntry(Routes.REPO_GRAPH) }
                LogScreen(
                    repoId = id,
                    onBack = { nav.navigateRepoTab(id, RepoTab.OVERVIEW) },
                    onOpenDiff = { repoId, sha -> nav.navigate(Routes.commitDiff(repoId, sha)) },
                    vm = viewModel(owner),
                )
            }
            composable(Routes.FILES, arguments = listOf(repoIdArg)) { entry ->
                val id = entry.repoId()
                val owner = remember(entry) { nav.getBackStackEntry(Routes.REPO_GRAPH) }
                FileExplorerScreen(
                    repoId = id,
                    onBack = { nav.navigateRepoTab(id, RepoTab.OVERVIEW) },
                    onOpenFile = { filePath -> nav.navigate(Routes.editor(id, filePath)) },
                    onOpenBlame = { filePath -> nav.navigate(Routes.blame(id, filePath)) },
                    onOpenSearch = { nav.navigate(Routes.search(id)) },
                    onOpenGitignore = { nav.navigate(Routes.gitignore(id)) },
                    onOpenActions = { nav.navigate(Routes.actions(id)) },
                    vm = viewModel(owner),
                )
            }
            composable(Routes.TOOLS, arguments = listOf(repoIdArg)) { entry ->
                val id = entry.repoId()
                MoreScreen(
                    repoId = id,
                    onBack = { nav.navigateRepoTab(id, RepoTab.OVERVIEW) },
                    onOpenBranches = { nav.navigate(Routes.branches(id)) },
                    onOpenStash = { nav.navigate(Routes.stash(id)) },
                    onOpenRemote = { nav.navigate(Routes.remote(id)) },
                    onOpenTags = { nav.navigate(Routes.tags(id)) },
                    onOpenGitignore = { nav.navigate(Routes.gitignore(id)) },
                    onOpenConflicts = { nav.navigate(Routes.conflicts(id)) },
                    onOpenActions = { nav.navigate(Routes.actions(id)) },
                    onOpenConsole = { nav.navigate(Routes.console(id)) },
                )
            }

            // ---- detail screens (bottom bar hidden) ----
            composable(Routes.BRANCHES, arguments = listOf(repoIdArg)) { entry ->
                BranchesScreen(repoId = entry.repoId(), onBack = { nav.popBackStack() })
            }
            composable(Routes.STASH, arguments = listOf(repoIdArg)) { entry ->
                StashScreen(repoId = entry.repoId(), onBack = { nav.popBackStack() })
            }
            composable(Routes.REMOTE, arguments = listOf(repoIdArg)) { entry ->
                RemoteScreen(repoId = entry.repoId(), onBack = { nav.popBackStack() })
            }
            composable(Routes.TAGS, arguments = listOf(repoIdArg)) { entry ->
                TagsScreen(repoId = entry.repoId(), onBack = { nav.popBackStack() })
            }
            composable(Routes.GITIGNORE, arguments = listOf(repoIdArg)) { entry ->
                GitignoreScreen(repoId = entry.repoId(), onBack = { nav.popBackStack() })
            }
            composable(Routes.CONSOLE, arguments = listOf(repoIdArg)) { entry ->
                GitConsoleScreen(repoId = entry.repoId(), onBack = { nav.popBackStack() })
            }
            composable(Routes.CONFLICTS, arguments = listOf(repoIdArg)) { entry ->
                val id = entry.repoId()
                ConflictsScreen(
                    repoId = id,
                    onBack = { nav.popBackStack() },
                    onEditFile = { path -> nav.navigate(Routes.editor(id, path)) },
                )
            }
            composable(
                Routes.ACTIONS,
                arguments = listOf(repoIdArg),
                deepLinks = listOf(navDeepLink { uriPattern = "graft://repo/{repoId}/actions" }),
            ) { entry ->
                ActionsScreen(repoId = entry.repoId(), onBack = { nav.popBackStack() })
            }
            composable(Routes.SEARCH, arguments = listOf(repoIdArg)) { entry ->
                SearchScreen(
                    repoId = entry.repoId(),
                    onBack = { nav.popBackStack() },
                    onOpenResult = { resultRepoId, path, line -> nav.navigate(Routes.editor(resultRepoId, path, line)) },
                )
            }
            composable(
                Routes.DIFF,
                arguments = listOf(
                    repoIdArg, pathArg,
                    navArgument(Routes.ARG_STAGED) { type = NavType.StringType },
                ),
            ) { entry ->
                DiffScreen(
                    repoId = entry.repoId(),
                    encodedPath = entry.arguments?.getString(Routes.ARG_PATH).orEmpty(),
                    stagedOrCommit = entry.arguments?.getString(Routes.ARG_STAGED) ?: "false",
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.EDITOR,
                arguments = listOf(
                    repoIdArg, pathArg,
                    navArgument(Routes.ARG_LINE) { type = NavType.IntType; defaultValue = -1 },
                ),
            ) { entry ->
                val line = entry.arguments?.getInt(Routes.ARG_LINE) ?: -1
                FileEditorScreen(
                    repoId = entry.repoId(),
                    relativePath = entry.decodedPath(),
                    onBack = { nav.popBackStack() },
                    initialLine = if (line >= 1) line else null,
                )
            }
            composable(Routes.BLAME, arguments = listOf(repoIdArg, pathArg)) { entry ->
                val id = entry.repoId()
                BlameScreen(
                    repoId = id,
                    path = entry.decodedPath(),
                    onBack = { nav.popBackStack() },
                    onOpenCommit = { sha -> nav.navigate(Routes.commitDiff(id, sha)) },
                )
            }
        }
    }
}
