package dev.graft.app.ui.screens.clone

import androidx.compose.foundation.clickable
import dev.graft.app.ui.theme.gitColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.graft.app.ui.components.AppCard

/**
 * "Add a repository", shown as a bottom sheet over the repo list so adding a repo never navigates
 * away from Home. Two modes share one form: **Clone** an existing remote, or **Create** a new
 * local repository (git init, optionally with a README and first commit).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRepoSheet(
    onDismiss: () -> Unit,
    onCloned: () -> Unit,
    onAddCredential: () -> Unit,
    viewModel: CloneViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var credentialMenuExpanded by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    var clipboardSuggestion by remember { mutableStateOf<String?>(null) }
    var suggestionDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val clipText = clipboard.getText()?.text?.trim()
        if (!clipText.isNullOrBlank() && looksLikeGitUrl(clipText) && state.url.isBlank()) {
            clipboardSuggestion = clipText
        }
    }

    LaunchedEffect(state.done) {
        if (state.done) {
            onCloned()
            // The ViewModel outlives this sheet (it's scoped to the screen behind it,
            // not to `if (showCloneSheet)`), so `done` must be cleared here — otherwise
            // the next time this sheet opens, this effect fires immediately on first
            // composition with the stale `done = true` from the previous successful
            // clone and closes the sheet before it can animate in.
            viewModel.resetForm()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Add repository",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            TabRow(
                selectedTabIndex = if (state.mode == AddRepoMode.CLONE) 0 else 1,
                containerColor = Color.Transparent,
            ) {
                Tab(
                    selected = state.mode == AddRepoMode.CLONE,
                    onClick = { viewModel.onModeChanged(AddRepoMode.CLONE) },
                    text = { Text("Clone") },
                    icon = { Icon(Icons.Filled.Download, null) },
                )
                Tab(
                    selected = state.mode == AddRepoMode.CREATE,
                    onClick = { viewModel.onModeChanged(AddRepoMode.CREATE) },
                    text = { Text("Create new") },
                    icon = { Icon(Icons.Filled.CreateNewFolder, null) },
                )
            }

            val isClone = state.mode == AddRepoMode.CLONE

            if (isClone && clipboardSuggestion != null && !suggestionDismissed) {
                AppCard(
                    accent = MaterialTheme.gitColors.added,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.onUrlChanged(clipboardSuggestion!!)
                            suggestionDismissed = true
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Use URL from clipboard?", style = MaterialTheme.typography.labelLarge)
                            Text(
                                clipboardSuggestion!!, maxLines = 1,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { suggestionDismissed = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (isClone) {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = viewModel::onUrlChanged,
                    label = { Text("Repository URL") },
                    placeholder = { Text("https://github.com/you/repo.git") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = state.repoName,
                onValueChange = viewModel::onRepoNameChanged,
                label = { Text(if (isClone) "Folder name" else "Repository name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.branch,
                onValueChange = viewModel::onBranchChanged,
                label = { Text(if (isClone) "Branch (optional)" else "Initial branch") },
                placeholder = { Text("main") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (isClone) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Latest commit only", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Much faster and smaller - skips the project's history",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.shallow, onCheckedChange = viewModel::onShallowChanged)
                }
            }

            if (!isClone) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Add README and first commit", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Gives the repo a real HEAD so you can branch right away",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.createInitialCommit, onCheckedChange = viewModel::onInitialCommitChanged)
                }
            }

            if (isClone) Column {
                Text(
                    "Credential",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))

                if (state.credentials.isEmpty()) {
                    AppCard(
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onAddCredential),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("No saved credentials — tap to add one")
                        }
                    }
                } else {
                    ExposedDropdownMenuBox(
                        expanded = credentialMenuExpanded,
                        onExpandedChange = { credentialMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = state.credentials.firstOrNull { it.id == state.selectedCredentialId }?.name
                                ?: "None (public repo)",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Use credential") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = credentialMenuExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        DropdownMenu(
                            expanded = credentialMenuExpanded,
                            onDismissRequest = { credentialMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("None (public repo)") },
                                onClick = {
                                    viewModel.onCredentialSelected(null)
                                    credentialMenuExpanded = false
                                }
                            )
                            state.credentials.forEach { cred ->
                                DropdownMenuItem(
                                    text = { Text(cred.name) },
                                    onClick = {
                                        viewModel.onCredentialSelected(cred.id)
                                        credentialMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            state.errorMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = if (isClone) viewModel::startClone else viewModel::startCreate,
                enabled = !state.isCloning,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isCloning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(if (isClone) "Starting…" else "Creating…")
                } else {
                    Text(if (isClone) "Clone" else "Create repository")
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Deliberately permissive — false positives just show a dismissible suggestion banner,
 * false negatives mean no banner at all, so erring toward "looks plausible" costs a lot
 * less than an over-strict regex that misses real URLs from less common git hosts. */
private fun looksLikeGitUrl(text: String): Boolean {
    if (text.contains(' ') || text.contains('\n')) return false
    val lower = text.lowercase()
    return lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("git@") ||
        lower.endsWith(".git")
}
