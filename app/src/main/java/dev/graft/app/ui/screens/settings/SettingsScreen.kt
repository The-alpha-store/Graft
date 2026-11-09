package dev.graft.app.ui.screens.settings

import android.Manifest
import androidx.compose.ui.graphics.Color
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.graft.app.data.AppearancePrefs
import dev.graft.app.ui.components.relativeTimeMillis
import dev.graft.app.data.db.entity.SyncLogStatus
import dev.graft.app.data.RepoStorage
import dev.graft.app.ui.components.GroupPosition
import dev.graft.app.ui.components.GroupedListItem
import dev.graft.app.ui.components.GroupedListSection
import dev.graft.app.ui.components.SettingsRow
import dev.graft.app.ui.components.groupPositionFor
import dev.graft.app.ui.theme.*
import dev.graft.app.sync.SyncPrefs


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    onOpenApkDownloads: () -> Unit = {},
    onOpenCredentials: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }

    var notificationsDenied by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsDenied = !granted }

    var showAppearanceSheet by remember { mutableStateOf(false) }
    var showIdentityDialog by remember { mutableStateOf(false) }
    var showSyncIntervalDialog by remember { mutableStateOf(false) }
    var showAutomationDialog by remember { mutableStateOf(false) }
    var showStorageDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); vm.dismissMessage() } }

    Scaffold(
        topBar = {
            dev.graft.app.ui.components.ScreenTopBar(title = "Settings", onBack = onBack)
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            GroupedListSection("Accounts") {
                GroupedListItem(GroupPosition.ONLY) {
                    SettingsRow(
                        icon = Icons.Filled.Key,
                        title = "Credentials",
                        subtitle = "Personal access tokens for push, pull and the GitHub API",
                        onClick = onOpenCredentials,
                    )
                }
            }

            GroupedListSection("Appearance") {
                GroupedListItem(GroupPosition.ONLY) {
                    SettingsRow(
                        icon = Icons.Filled.Palette,
                        title = "Appearance",
                        subtitle = "Theme, color palette \u0026 text size",
                        onClick = { showAppearanceSheet = true },
                    )
                }
            }

            GroupedListSection("Git identity") {
                GroupedListItem(GroupPosition.ONLY) {
                    SettingsRow(
                        icon = Icons.Filled.Person,
                        title = state.authorName,
                        subtitle = state.authorEmail,
                        onClick = { showIdentityDialog = true },
                    )
                }
            }

            GroupedListSection("Background sync") {
                val rows = if (state.backgroundSyncEnabled) 2 else 1
                var idx = 0
                GroupedListItem(groupPositionFor(idx++, rows)) {
                    SettingsRow(
                        icon = Icons.Filled.Sync,
                        title = "Background sync",
                        subtitle = if (state.backgroundSyncEnabled) "Checking every ${formatInterval(state.intervalMinutes)}" else "Off",
                        trailing = {
                            Switch(
                                checked = state.backgroundSyncEnabled,
                                onCheckedChange = { enabled ->
                                    vm.setBackgroundSyncEnabled(enabled)
                                    // POST_NOTIFICATIONS is a runtime permission on API 33+ —
                                    // this is the only place in the app with an Activity to
                                    // prompt from, since SyncWorker runs headless. Harmless
                                    // when already granted (the launcher just no-ops).
                                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        val hasPermission = ContextCompat.checkSelfPermission(
                                            context, Manifest.permission.POST_NOTIFICATIONS,
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (!hasPermission) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                            )
                        },
                    )
                }
                if (state.backgroundSyncEnabled) {
                    GroupedListItem(groupPositionFor(idx, rows)) {
                        SettingsRow(
                            icon = Icons.Filled.Timer,
                            title = "Check every",
                            subtitle = formatInterval(state.intervalMinutes),
                            onClick = { showSyncIntervalDialog = true },
                        )
                    }
                }
            }
            if (notificationsDenied) {
                Text(
                    "Notifications are off, so background sync will still run but won't alert you when it finds new commits.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            if (state.syncEvents.isNotEmpty()) {
                GroupedListSection("Recent sync activity") {
                    state.syncEvents.forEachIndexed { index, event ->
                        GroupedListItem(groupPositionFor(index, state.syncEvents.size)) {
                            SettingsRow(
                                icon = if (event.status == SyncLogStatus.FAILED) Icons.Filled.ErrorOutline else Icons.Filled.Sync,
                                iconTint = if (event.status == SyncLogStatus.FAILED) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                                title = event.repoName,
                                subtitle = "${event.message} · ${relativeTimeMillis(event.timestamp)}",
                            )
                        }
                    }
                }
                TextButton(onClick = vm::clearSyncHistory) { Text("Clear sync history") }
            }

            GroupedListSection("Automation") {
                GroupedListItem(GroupPosition.ONLY) {
                    SettingsRow(
                        icon = Icons.Filled.Bolt,
                        title = "Auto-commit \u0026 push",
                        subtitle = if (state.automatedRepoIds.isEmpty()) "Off" else "${state.automatedRepoIds.size} repo(s) selected",
                        onClick = { showAutomationDialog = true },
                    )
                }
            }

            GroupedListSection("Storage") {
                GroupedListItem(GroupPosition.TOP) {
                    SettingsRow(
                        icon = Icons.Filled.Folder,
                        title = if (state.storageMode == RepoStorage.Mode.APP) "App storage" else "Public folder",
                        subtitle = state.storageRootPath,
                        onClick = { showStorageDialog = true },
                    )
                }
                GroupedListItem(GroupPosition.MIDDLE) {
                    SettingsRow(
                        icon = Icons.Filled.Android,
                        title = "Downloaded APKs",
                        subtitle = if (state.isCalculatingCache) "Calculating…" else formatBytes(state.apkCacheBytes),
                        onClick = onOpenApkDownloads,
                    )
                }
                GroupedListItem(GroupPosition.BOTTOM) {
                    SettingsRow(
                        icon = Icons.Filled.DeleteSweep,
                        title = "Clear APK cache",
                        subtitle = "Removes every downloaded build — re-download anytime from Actions",
                        trailing = {
                            TextButton(onClick = vm::clearApkCache, enabled = state.apkCacheBytes > 0) { Text("Clear") }
                        },
                    )
                }
            }

            GroupedListSection("About") {
                GroupedListItem(GroupPosition.ONLY) {
                    val versionName = remember {
                        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
                    }
                    SettingsRow(icon = Icons.Filled.Info, title = "Graft", subtitle = "Version $versionName")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showAppearanceSheet) {
        ModalBottomSheet(onDismissRequest = { showAppearanceSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            AppearanceSheetContent(context)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showStorageDialog) {
        StorageModeDialog(
            current = state.storageMode,
            onSelect = { mode ->
                vm.setStorageMode(mode)
                if (mode == RepoStorage.Mode.PUBLIC && !RepoStorage.hasPublicAccess(context) &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ) {
                    context.startActivity(RepoStorage.allFilesAccessIntent(context))
                }
                showStorageDialog = false
            },
            onDismiss = { showStorageDialog = false },
        )
    }

    if (showIdentityDialog) {
        GitIdentityDialog(state = state, onSave = vm::setGitIdentity, onDismiss = { showIdentityDialog = false })
    }

    if (showSyncIntervalDialog) {
        SyncIntervalDialog(
            currentMinutes = state.intervalMinutes,
            onSave = { vm.setIntervalMinutes(it); showSyncIntervalDialog = false },
            onDismiss = { showSyncIntervalDialog = false },
        )
    }

    if (showAutomationDialog) {
        AutomationDialog(
            repos = state.allRepos,
            selectedIds = state.automatedRepoIds,
            onSave = { vm.setAutomatedRepoIds(it); showAutomationDialog = false },
            onDismiss = { showAutomationDialog = false },
        )
    }
}

@Composable
private fun AutomationDialog(
    repos: List<dev.graft.app.data.db.entity.RepoEntity>,
    selectedIds: Set<Long>,
    onSave: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var working by remember(selectedIds) { mutableStateOf(selectedIds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-commit \u0026 push") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.WarningAmber, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "During background sync, any selected repo with local changes gets everything staged, committed, and pushed automatically — with nobody reviewing the diff first. Only enable this for repos where that's genuinely fine, like scratch notes or generated output.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(14.dp))
                if (repos.isEmpty()) {
                    Text("No repos tracked yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    repos.forEach { repo ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                working = if (repo.id in working) working - repo.id else working + repo.id
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = repo.id in working, onCheckedChange = {
                                working = if (it) working + repo.id else working - repo.id
                            })
                            Spacer(Modifier.width(8.dp))
                            Text(repo.name, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(working) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatInterval(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "${m}m"
        m == 0L -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

/**
 * A real time picker instead of a fixed list of whole-hour options — drag the slider for any
 * value, or tap a preset to jump straight to it. Range is capped to what's actually
 * achievable: [SyncPrefs.MIN_INTERVAL_MINUTES] (15 min) is WorkManager's own hard floor for
 * periodic background work, not a limit chosen here, and [SyncPrefs.MAX_INTERVAL_MINUTES]
 * (2 hours) is where checking more often stops being worth the battery/data cost for a
 * background *fetch*.
 */
@Composable
private fun SyncIntervalDialog(currentMinutes: Long, onSave: (Long) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf(currentMinutes) }
    val presets = listOf(15L, 30L, 45L, 60L, 90L, 120L)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check every") },
        text = {
            Column {
                Text(
                    formatInterval(minutes),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(12.dp))
                Slider(
                    value = minutes.toFloat(),
                    onValueChange = { minutes = it.toLong() },
                    valueRange = SyncPrefs.MIN_INTERVAL_MINUTES.toFloat()..SyncPrefs.MAX_INTERVAL_MINUTES.toFloat(),
                    steps = (SyncPrefs.MAX_INTERVAL_MINUTES - SyncPrefs.MIN_INTERVAL_MINUTES - 1).toInt(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatInterval(SyncPrefs.MIN_INTERVAL_MINUTES), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatInterval(SyncPrefs.MAX_INTERVAL_MINUTES), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presets.forEach { preset ->
                        FilterChip(selected = minutes == preset, onClick = { minutes = preset }, label = { Text(formatInterval(preset)) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "15 minutes is Android's own floor for background work — nothing checks more often than that, even a system app.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(minutes) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

@Composable
private fun GitIdentityDialog(state: SettingsUiState, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(state.authorName) }
    var email by remember { mutableStateOf(state.authorEmail) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Git identity") },
        text = {
            Column {
                Text(
                    "The name and email attached to every commit you make in this app.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, email); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Everything appearance-related lives in one sheet — theme mode, wallpaper-based color,
 * editor text size, and the accent color palette picker — rather than four separate rows on
 * the main Settings list. Matches the reference app's own "Appearance" sheet pattern: one
 * entry point on the list, a focused sheet for the actual choices.
 */
@Composable
private fun StorageModeDialog(current: RepoStorage.Mode, onSelect: (RepoStorage.Mode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Storage location") },
        text = {
            Column {
                Text(
                    "Applies to new clones, the local-repo scan and downloaded APKs. Repos already on disk stay where they are.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                StorageOption(
                    selected = current == RepoStorage.Mode.APP,
                    title = "App storage",
                    body = "Works immediately, no permission. Files live in the app's own folder.",
                    onClick = { onSelect(RepoStorage.Mode.APP) },
                )
                StorageOption(
                    selected = current == RepoStorage.Mode.PUBLIC,
                    title = "Public folder",
                    body = "Visible to file managers, Termux and USB. Needs Android's \"All files access\".",
                    onClick = { onSelect(RepoStorage.Mode.PUBLIC) },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun StorageOption(selected: Boolean, title: String, body: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppearanceSheetContent(context: android.content.Context) {
    val themeMode by AppearancePrefs.themeMode.collectAsState()
    val dynamicColor by AppearancePrefs.dynamicColor.collectAsState()
    val editorTextSize by AppearancePrefs.editorTextSize.collectAsState()
    val accent by AppearancePrefs.accent.collectAsState()
    val selectedPaletteId = accent.presetId
    val dynamicActive = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var showCustomDialog by remember { mutableStateOf(false) }
    var customHexInput by remember { mutableStateOf(accent.customHex ?: "") }
    var customHexError by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))

        Text("Theme", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppearancePrefs.ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = themeMode == mode,
                    onClick = { AppearancePrefs.setThemeMode(context, mode) },
                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wallpaper-based color")
                    Text(
                        "Derive colors from your wallpaper instead of the palette below",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = dynamicColor, onCheckedChange = { AppearancePrefs.setDynamicColor(context, it) })
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Editor text size", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppearancePrefs.EditorTextSize.entries.forEach { size ->
                FilterChip(
                    selected = editorTextSize == size,
                    onClick = { AppearancePrefs.setEditorTextSize(context, size) },
                    label = { Text(size.label) },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Color palette", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Sets the app's accent colors. Git status colors (added, modified, deleted) never change.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (dynamicActive) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WarningAmber, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(4.dp))
                Text("Turn off wallpaper-based color above to use a palette", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (dynamicActive) 0.38f else 1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            dev.graft.app.ui.theme.AccentPalettes.presets.forEach { preset ->
                PaletteSwatch(
                    color = preset.primary, label = preset.label, selected = selectedPaletteId == preset.id,
                    onClick = { if (!dynamicActive) AppearancePrefs.setAccentPreset(context, preset.id) },
                )
            }
            CustomSwatch(
                selected = selectedPaletteId == dev.graft.app.ui.theme.AccentPalettes.CUSTOM_ID,
                currentHex = accent.customHex,
                onClick = { if (!dynamicActive) showCustomDialog = true },
            )
        }
    }

    if (showCustomDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Custom color") },
            text = {
                Column {
                    Text(
                        "Enter a hex color — a matching secondary and tertiary accent are generated from it automatically.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = customHexInput,
                        onValueChange = { customHexInput = it; customHexError = false },
                        label = { Text("Hex color") },
                        placeholder = { Text("#7C3AED") },
                        singleLine = true,
                        isError = customHexError,
                        supportingText = if (customHexError) { { Text("Enter a valid 6-digit hex color, e.g. #7C3AED") } } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val applied = AppearancePrefs.setCustomAccent(context, customHexInput)
                    if (applied) showCustomDialog = false else customHexError = true
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showCustomDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PaletteSwatch(color: androidx.compose.ui.graphics.Color, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(color)
                .then(
                    if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, androidx.compose.foundation.shape.CircleShape)
                    else Modifier
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, null, tint = contrastingIconColor(color), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun CustomSwatch(selected: Boolean, currentHex: String?, onClick: () -> Unit) {
    val swatchColor = currentHex?.let { dev.graft.app.ui.theme.parseHexColorOrNull(it) } ?: MaterialTheme.colorScheme.surfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(swatchColor)
                .border(
                    if (selected) 2.5.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    androidx.compose.foundation.shape.CircleShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (selected) Icons.Filled.Check else Icons.Filled.Edit,
                null,
                tint = if (currentHex != null) contrastingIconColor(swatchColor) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("Custom", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Picks black or white for icon contrast against an arbitrary swatch color — simple
 *  perceptual-luminance threshold. */
@Composable
private fun contrastingIconColor(background: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
    val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > 0.6f) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White
}
