package dev.graft.app.ui.screens.conflicts

import androidx.compose.foundation.layout.*
import dev.graft.app.ui.theme.gitColors
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.graft.app.ui.components.AppCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(
    repoId: Long,
    onBack: () -> Unit,
    onEditFile: (path: String) -> Unit,
    vm: ConflictsViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsState()
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(repoId) { vm.load(repoId) }
    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); vm.dismissMessage() } }
    LaunchedEffect(state.done) { if (state.done) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Resolve Conflicts", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            if (state.isLoading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (state.op) {
                                ConflictOp.MERGE -> "Merge in progress — ${state.conflictedPaths.size} file(s) need resolving"
                                ConflictOp.REBASE -> "Rebase in progress — ${state.conflictedPaths.size} file(s) need resolving"
                                ConflictOp.UNKNOWN -> "${state.conflictedPaths.size} file(s) conflicted"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (state.conflictedPaths.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.gitColors.added, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("All conflicts resolved")
                            Text("Tap Continue below to finish", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.conflictedPaths, key = { it }) { path ->
                            ConflictRow(
                                path = path, isWorking = state.isWorking,
                                onUseOurs = { vm.useOurs(path) },
                                onUseTheirs = { vm.useTheirs(path) },
                                onEditManually = { onEditFile(path) },
                                onMarkResolved = { vm.markResolved(path) },
                            )
                        }
                    }
                }

                if (state.op == ConflictOp.MERGE) {
                    OutlinedTextField(
                        value = state.commitMessage,
                        onValueChange = vm::setCommitMessage,
                        label = { Text("Merge commit message (optional)") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        singleLine = true,
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = vm::abortOperation,
                        enabled = !state.isWorking,
                        modifier = Modifier.weight(1f),
                    ) { Text("Abort", color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = vm::continueOperation,
                        enabled = !state.isWorking && state.conflictedPaths.isEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.isWorking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Text("Continue")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictRow(
    path: String, isWorking: Boolean,
    onUseOurs: () -> Unit, onUseTheirs: () -> Unit,
    onEditManually: () -> Unit, onMarkResolved: () -> Unit,
) {
    AppCard(
        accent = MaterialTheme.colorScheme.error,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onUseOurs, enabled = !isWorking) { Text("Use Ours") }
                OutlinedButton(onClick = onUseTheirs, enabled = !isWorking) { Text("Use Theirs") }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onEditManually, enabled = !isWorking) {
                    Icon(Icons.Filled.Edit, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Edit Manually")
                }
                TextButton(onClick = onMarkResolved, enabled = !isWorking) { Text("Mark Resolved") }
            }
        }
    }
}
