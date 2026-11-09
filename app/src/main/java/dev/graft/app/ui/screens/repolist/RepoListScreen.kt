package dev.graft.app.ui.screens.repolist

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.graft.app.data.RepoStorage
import dev.graft.app.data.db.entity.RepoEntity
import dev.graft.app.ui.components.AppCard
import dev.graft.app.ui.components.RefreshBox
import dev.graft.app.ui.components.EmptyState
import dev.graft.app.ui.components.BranchChip
import dev.graft.app.ui.components.StatusPill
import dev.graft.app.ui.screens.clone.AddRepoSheet
import dev.graft.app.ui.theme.*
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RepoListScreen(
    onOpenRepo: (Long) -> Unit,
    onOpenCredentials: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: RepoListViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsState()
    val snack = remember { SnackbarHostState() }
    var repoPendingDelete by remember { mutableStateOf<RepoEntity?>(null) }
    var repoPendingGitHubDelete by remember { mutableStateOf<RepoEntity?>(null) }
    var repoPendingPullForce by remember { mutableStateOf<RepoEntity?>(null) }
    var repoPendingPushForce by remember { mutableStateOf<RepoEntity?>(null) }
    var repoPendingCredential by remember { mutableStateOf<RepoEntity?>(null) }
    var showCloneSheet by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var hasStorageAccess by remember { mutableStateOf(RepoStorage.hasStorageAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStorageAccess = RepoStorage.hasStorageAccess(context)
                if (hasStorageAccess) vm.scanForLocalRepos(silent = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let { snack.showSnackbar(it); vm.dismissSnackbar() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (showSearch) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = vm::setSearchQuery,
                            placeholder = { Text("Search repos…") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                cursorColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Repositories", style = MaterialTheme.typography.titleLarge)
                    }
                },
                navigationIcon = {
                    if (showSearch) {
                        IconButton(onClick = { showSearch = false; vm.setSearchQuery("") }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search")
                        }
                    }
                },
                actions = {
                    if (!showSearch) {
                        IconButton(onClick = { showSearch = true }) { Icon(Icons.Filled.Search, "Search") }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                DropdownMenuItem(text = { Text("Most recent") },
                                    onClick = { showSortMenu = false; vm.setSortMode(RepoSortMode.RECENT) })
                                DropdownMenuItem(text = { Text("Name (A–Z)") },
                                    onClick = { showSortMenu = false; vm.setSortMode(RepoSortMode.NAME) })
                                DropdownMenuItem(text = { Text("Most changes first") },
                                    onClick = { showSortMenu = false; vm.setSortMode(RepoSortMode.HAS_CHANGES) })
                            }
                        }
                        // Credentials and Settings are destinations (Home bottom bar / Settings),
                        // so the bar only carries actions that act on *this* list.
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface, navigationIconContentColor = MaterialTheme.colorScheme.onSurface, actionIconContentColor = MaterialTheme.colorScheme.onSurface),
            )
        },
        floatingActionButton = {
            RepoListFab(
                expanded = fabExpanded,
                onToggle = { fabExpanded = !fabExpanded },
                onCloneUrl = { fabExpanded = false; showCloneSheet = true },
                onDiscoverGitHub = { fabExpanded = false; onOpenDiscover() },
                onScanLocal = { fabExpanded = false; vm.scanForLocalRepos() },
            )
        },
        snackbarHost = { SnackbarHost(snack) { d -> Snackbar(d) } },
    ) { pad ->
        // Tapping anywhere on the list while the FAB menu is open collapses it first,
        // instead of leaving it open and letting the tap fall through to whatever's under it.
        Box(Modifier.fillMaxSize().let { if (fabExpanded) it.clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
        ) { fabExpanded = false } else it }) {
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (!hasStorageAccess) {
                StorageAccessBanner(
                    onGrant = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            context.startActivity(RepoStorage.allFilesAccessIntent(context))
                        }
                    },
                    onUseAppStorage = {
                        RepoStorage.setMode(context, RepoStorage.Mode.APP)
                        hasStorageAccess = RepoStorage.hasStorageAccess(context)
                        vm.scanForLocalRepos(silent = true)
                    },
                )
            }

            val visibleRepos = state.visibleRepos

            RefreshBox(
                refreshing = state.isRefreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                if (state.repos.isEmpty()) {
                    HomeEmptyState(onAddRepo = { showCloneSheet = true }, onDiscover = onOpenDiscover)
                } else if (visibleRepos.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No repos match \"${state.searchQuery}\"", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item(key = "summary") {
                            val needAttention = state.repos.count { r ->
                                r.lastError.isNotBlank() || (state.changeCounts[r.id] ?: 0) > 0
                            }
                            HomeSummary(total = state.repos.size, needAttention = needAttention)
                        }
                        items(visibleRepos, key = { it.id }) { repo ->
                            RepoCard(
                                repo = repo, isBusy = state.busyRepoId == repo.id,
                                changeCount = state.changeCounts[repo.id],
                                onTap = { onOpenRepo(repo.id) },
                                onLongPress = { repoPendingDelete = repo },
                                onPull = { vm.pull(repo) }, onPush = { vm.push(repo) },
                                onFetch = { vm.fetch(repo) },
                                onRequestPullForce = { repoPendingPullForce = repo },
                                onRequestPushForce = { repoPendingPushForce = repo },
                                onSetCredential = { repoPendingCredential = repo },
                                // Springy placement: repos entering, leaving or reordering settle into
                                // place with a little bounce instead of snapping.
                                itemModifier = Modifier.animateItem(
                                    fadeInSpec = tween(220),
                                    placementSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                                    fadeOutSpec = tween(150),
                                ),
                            )
                        }
                    }
                }
            }
        }
        }
    }

    repoPendingDelete?.let { repo ->
        AlertDialog(onDismissRequest = { repoPendingDelete = null },
            title = { Text("Remove ${repo.name}?") },
            text = { Text("Remove it from Graft. You can also delete the local files.") },
            confirmButton = { TextButton(onClick = { vm.deleteRepo(repo, true); repoPendingDelete = null }) { Text("Remove + Delete Files", color = MaterialTheme.colorScheme.error) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.deleteRepo(repo, false); repoPendingDelete = null }) { Text("Just Remove") }
                    if (repo.cloneUrl.contains("github.com")) {
                        TextButton(onClick = { repoPendingDelete = null; repoPendingGitHubDelete = repo }) {
                            Text("Delete on GitHub…", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { repoPendingDelete = null }) { Text("Cancel") }
                }
            })
    }

    repoPendingGitHubDelete?.let { repo ->
        dev.graft.app.ui.components.ConfirmDialog(
            "Delete ${repo.name} on GitHub?",
            "This permanently deletes the repo from GitHub itself — not just from this app. " +
                "There's no undo. The local files stay put; remove those separately if you want them gone too. " +
                "Needs a credential whose token has the \"delete_repo\" scope.",
            confirmLabel = "Delete on GitHub", danger = true,
            onConfirm = { vm.deleteRepoOnGitHub(repo, alsoDeleteFilesLocally = false); repoPendingGitHubDelete = null },
            onDismiss = { repoPendingGitHubDelete = null },
        )
    }

    repoPendingPullForce?.let { repo ->
        dev.graft.app.ui.components.ConfirmDialog(
            "Pull (Force) ${repo.name}?",
            "This hard-resets the branch to match the remote. Any local commits not on the remote will be lost. Cannot be undone.",
            confirmLabel = "Force Pull", danger = true,
            onConfirm = { vm.pullForce(repo); repoPendingPullForce = null },
            onDismiss = { repoPendingPullForce = null },
        )
    }
    repoPendingPushForce?.let { repo ->
        dev.graft.app.ui.components.ConfirmDialog(
            "Push (Force) ${repo.name}?",
            "This overwrites the remote branch with local history. If someone else pushed in the meantime, their commits will be lost.",
            confirmLabel = "Force Push", danger = true,
            onConfirm = { vm.pushForce(repo); repoPendingPushForce = null },
            onDismiss = { repoPendingPushForce = null },
        )
    }
    repoPendingCredential?.let { repo ->
        AlertDialog(
            onDismissRequest = { repoPendingCredential = null },
            title = { Text("Credential for ${repo.name}") },
            text = {
                if (state.credentials.isEmpty()) {
                    Text("You don't have any saved credentials yet. Add one on the Credentials screen, then come back here to attach it.")
                } else {
                    Column {
                        Text(
                            "Used for push/pull/fetch on this repo, and for its Actions runs if it's on GitHub.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        state.credentials.forEach { cred ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { vm.setCredential(repo, cred.id); repoPendingCredential = null }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = repo.credentialId == cred.id, onClick = { vm.setCredential(repo, cred.id); repoPendingCredential = null })
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(cred.name, style = MaterialTheme.typography.bodyMedium)
                                    if (cred.username.isNotBlank()) {
                                        Text(cred.username, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        if (repo.credentialId != 0L) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { vm.setCredential(repo, 0L); repoPendingCredential = null }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = false, onClick = { vm.setCredential(repo, 0L); repoPendingCredential = null })
                                Spacer(Modifier.width(8.dp))
                                Text("None", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (state.credentials.isEmpty()) {
                    TextButton(onClick = { repoPendingCredential = null; onOpenCredentials() }) { Text("Go to Credentials") }
                } else {
                    TextButton(onClick = { repoPendingCredential = null }) { Text("Done") }
                }
            },
            dismissButton = { TextButton(onClick = { repoPendingCredential = null }) { Text("Cancel") } },
        )
    }

    // Clone lives here as a bottom sheet, not a nav destination — adding a
    // repo never navigates away from the home screen.
    if (showCloneSheet) {
        AddRepoSheet(
            onDismiss = { showCloneSheet = false },
            onCloned = { showCloneSheet = false },
            onAddCredential = onOpenCredentials,
        )
    }
}

/**
 * Every way to add a repo, in one consistent place — replaces what used to be split across
 * a top-bar "+" icon (Clone) and a separate "..." overflow menu (Scan/Discover) mixed in
 * with unrelated navigation items (Credentials/Settings). A small vertical speed-dial: tap
 * the main FAB to reveal three labeled options, tap one to act (and auto-collapse), tap the
 * main FAB again (now an ✕) or tap anywhere else on the screen to dismiss without picking one.
 */
@Composable
private fun RepoListFab(
    expanded: Boolean, onToggle: () -> Unit,
    onCloneUrl: () -> Unit, onDiscoverGitHub: () -> Unit, onScanLocal: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.End) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FabMenuOption("Discover on GitHub", Icons.Filled.Explore, onDiscoverGitHub)
                FabMenuOption("Clone or create repo", Icons.Filled.Link, onCloneUrl)
                FabMenuOption("Scan for local repos", Icons.Filled.FolderSpecial, onScanLocal)
                Spacer(Modifier.height(4.dp))
            }
        }
        FloatingActionButton(
            onClick = onToggle,
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        ) {
            val rotation by animateFloatAsState(if (expanded) 45f else 0f, label = "fabRotation")
            Icon(Icons.Filled.Add, if (expanded) "Close" else "Add repo", modifier = Modifier.graphicsLayer { rotationZ = rotation })
        }
    }
}

@Composable
private fun FabMenuOption(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
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

@Composable
private fun StorageAccessBanner(onGrant: () -> Unit, onUseAppStorage: () -> Unit) {
    AppCard(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        accent = MaterialTheme.colorScheme.secondary,
        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Public folder needs access", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "You chose the public storage folder, which Android only allows with \"All files access\".",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onUseAppStorage, modifier = Modifier.weight(1f)) { Text("Use app storage") }
                Button(onClick = onGrant, modifier = Modifier.weight(1f)) { Text("Grant access") }
            }
        }
    }
}

@Composable
private fun HomeEmptyState(onAddRepo: () -> Unit, onDiscover: () -> Unit) {
    EmptyState(
        icon = Icons.Filled.FolderOff,
        title = "No repositories yet",
        message = "Clone one, create a new local repo, or browse your GitHub account.",
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDiscover) {
                    Icon(Icons.Filled.Explore, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Browse GitHub")
                }
                Button(onClick = onAddRepo) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add repo")
                }
            }
        },
    )
}

/** One-line overview above the list: how many repos, and how many need a look. */
@Composable
private fun HomeSummary(total: Int, needAttention: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "$total repo${if (total == 1) "" else "s"}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (needAttention > 0) {
            StatusPill(
                text = "$needAttention need attention",
                color = MaterialTheme.gitColors.modified,
                container = MaterialTheme.gitColors.modifiedContainer,
            )
        } else {
            StatusPill(
                text = "All clean",
                color = MaterialTheme.gitColors.added,
                container = MaterialTheme.gitColors.addedContainer,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepoCard(
    repo: RepoEntity, isBusy: Boolean, changeCount: Int?,
    onTap: () -> Unit, onLongPress: () -> Unit,
    onPull: () -> Unit, onPush: () -> Unit,
    onFetch: () -> Unit, onRequestPullForce: () -> Unit, onRequestPushForce: () -> Unit,
    onSetCredential: () -> Unit,
    itemModifier: Modifier = Modifier,
) {
    var showMore by remember { mutableStateOf(false) }
    val hasError = repo.lastError.isNotBlank()
    val hasCredential = repo.credentialId != 0L
    // The exact wording JGit throws for a private/auth-needed remote with no credential
    // attached — recognizing it means the error banner can offer the actual fix (attach a
    // credential) instead of just showing raw exception text someone has to go interpret.
    val needsCredential = hasError && repo.lastError.contains("no CredentialsProvider", ignoreCase = true)
    val accent = when {
        hasError -> MaterialTheme.colorScheme.error
        changeCount != null && changeCount > 0 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "repoCardScale",
    )

    AppCard(
        modifier = itemModifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onTap,
                onLongClick = onLongPress,
            ),
        accent = accent,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FolderOpen, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    repo.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // One glance, one word — the whole point of a repo list is scanning which
                // ones need attention without opening each one. A tiny badge riding on the
                // folder icon did the same job but easy to miss; a named status pill next to
                // the title can't be.
                RepoStatusPill(hasError = hasError, changeCount = changeCount)
                Box {
                    IconButton(onClick = { showMore = true }, Modifier.size(32.dp)) {
                        Icon(Icons.Filled.MoreVert, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        DropdownMenuItem(text = { Text("Fetch") }, onClick = { showMore = false; onFetch() },
                            leadingIcon = { Icon(Icons.Filled.Download, null) })
                        DropdownMenuItem(text = { Text("Pull (Force)") }, onClick = { showMore = false; onRequestPullForce() },
                            leadingIcon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) })
                        DropdownMenuItem(text = { Text("Push (Force)") }, onClick = { showMore = false; onRequestPushForce() },
                            leadingIcon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.secondary) })
                        DropdownMenuItem(
                            text = { Text(if (hasCredential) "Change credential…" else "Set credential…") },
                            onClick = { showMore = false; onSetCredential() },
                            leadingIcon = { Icon(Icons.Filled.Key, null) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            // A caption line built from icon+text pairs, not a run-on string glued together
            // with "·" — each fact gets its own small icon, so the line reads as a scan of
            // distinct attributes rather than one sentence you have to parse.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BranchChip(repo.branch.ifBlank { "—" })
                CaptionFact(Icons.Filled.Schedule, if (repo.lastSyncTime > 0) {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(repo.lastSyncTime))
                } else "Never synced")
                if (!hasCredential) CaptionFact(Icons.Filled.Key, "No credential", tint = MaterialTheme.colorScheme.secondary)
            }

            if (hasError) {
                Spacer(Modifier.height(8.dp))
                Text(repo.lastError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                if (needsCredential) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onSetCredential,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Icon(Icons.Filled.Key, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Attach a credential", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onPull, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                    if (isBusy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else { Icon(Icons.Filled.ArrowDownward, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Pull") }
                }
                Button(
                    onClick = onPush, enabled = !isBusy, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                ) {
                    if (isBusy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondary)
                    else { Icon(Icons.Filled.ArrowUpward, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Push") }
                }
            }
        }
    }
}

@Composable
private fun RepoStatusPill(hasError: Boolean, changeCount: Int?) {
    val git = MaterialTheme.gitColors
    when {
        hasError -> StatusPill("Error", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
        changeCount != null && changeCount > 0 -> StatusPill(
            "$changeCount change${if (changeCount == 1) "" else "s"}", git.modified, git.modifiedContainer,
        )
        else -> StatusPill("Clean", git.added, git.addedContainer)
    }
}

@Composable
private fun CaptionFact(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(11.dp), tint = tint)
        Spacer(Modifier.width(3.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}
