package dev.graft.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.graft.app.data.RepoStatusStore

/**
 * The app shell: one [Scaffold] whose bottom bar is chosen by *where you are*.
 *
 * - On a Home tab (Repos / Discover / Settings) -> the Home bar.
 * - On a repo tab (Overview / Changes / History / Files / Tools) -> the Repo bar, with a live
 *   badge on Changes.
 * - On any detail screen (diff, editor, branches...) -> no bar; the screen owns the whole display.
 */
@Composable
fun GraftApp() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val currentRepoId = entry?.arguments?.getLong(Routes.ARG_REPO_ID, -1L)?.takeIf { it >= 0 }

    val homeTab = HomeTab.entries.firstOrNull { it.route == route }
    val repoTab = RepoTab.entries.firstOrNull { it.route == route }

    Scaffold(
        // Inner screens each own a Scaffold and handle the status bar themselves; the shell only
        // reserves room for its bar. Without this, system-bar insets get applied twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            when {
                homeTab != null -> HomeBar(selected = homeTab, onSelect = { nav.navigateHomeTab(it) })
                repoTab != null && currentRepoId != null -> RepoBar(
                    repoId = currentRepoId,
                    selected = repoTab,
                    onSelect = { nav.navigateRepoTab(currentRepoId, it) },
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
            AppNavHost(nav)
        }
    }
}

@Composable
private fun HomeBar(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    NavigationBar {
        HomeTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, null) },
                label = { Text(tab.label) },
                colors = barItemColors(),
            )
        }
    }
}

@Composable
private fun RepoBar(repoId: Long, selected: RepoTab, onSelect: (RepoTab) -> Unit) {
    val counts by RepoStatusStore.changeCounts.collectAsState()
    val changeCount = counts[repoId] ?: 0
    NavigationBar {
        RepoTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = {
                    if (tab == RepoTab.CHANGES && changeCount > 0) {
                        BadgedBox(badge = { Badge { Text(if (changeCount > 99) "99+" else changeCount.toString()) } }) {
                            Icon(tab.icon, null)
                        }
                    } else {
                        Icon(tab.icon, null)
                    }
                },
                label = { Text(tab.label) },
                colors = barItemColors(),
            )
        }
    }
}

@Composable
private fun barItemColors() = NavigationBarItemDefaults.colors(
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.primary,
)

/** Switches Home tab, keeping each tab's own state (Discover's search, Settings' scroll). */
internal fun NavHostController.navigateHomeTab(tab: HomeTab) {
    navigate(tab.route) {
        popUpTo(Routes.REPOS) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Switches repo tab. Deliberately *no* save/restoreState: the nav library would restore a saved tab
 * entry together with its old arguments, so after Home -> repo A -> Home -> repo B you could get
 * repo A's Changes tab back inside repo B. State survives tab switches a better way - the tab
 * ViewModels are scoped to the repo graph's own back-stack entry (see [AppNavHost]).
 */
internal fun NavHostController.navigateRepoTab(repoId: Long, tab: RepoTab) {
    navigate(tab.routeFor(repoId)) {
        popUpTo(Routes.OVERVIEW) { inclusive = false }
        launchSingleTop = true
    }
}
