package dev.graft.app.ui.screens.actions

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.graft.app.App
import dev.graft.app.data.github.WorkflowTemplate
import dev.graft.app.data.github.WorkflowTemplateCatalog
import dev.graft.app.ui.components.AppCard
import dev.graft.app.ui.components.SectionHeader
import dev.graft.app.ui.components.StatusPill
import dev.graft.app.ui.theme.code
import dev.graft.app.ui.theme.gitColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class TemplatesUiState(
    val repoPath: String? = null,
    /** Repo-relative paths of workflow files already present, e.g. `.github/workflows/ci.yml`. */
    val installed: Set<String> = emptySet(),
    val message: String? = null,
)

/**
 * Installs [WorkflowTemplate]s into a repo's working tree. It only *writes the file* - committing
 * and pushing it goes through the normal Changes flow, so the user sees exactly what will be
 * committed and nothing is pushed behind their back.
 */
class WorkflowTemplatesViewModel(app: Application) : AndroidViewModel(app) {
    private val repoRepo = (app as App).repoRepository
    private val _state = MutableStateFlow(TemplatesUiState())
    val state: StateFlow<TemplatesUiState> = _state.asStateFlow()

    fun load(repoId: Long) {
        viewModelScope.launch {
            val repo = repoRepo.getById(repoId) ?: return@launch
            _state.value = _state.value.copy(repoPath = repo.fullSavePath)
            refreshInstalled()
        }
    }

    private suspend fun refreshInstalled() {
        val root = _state.value.repoPath ?: return
        val found = withContext(Dispatchers.IO) {
            val dir = File(root, ".github/workflows")
            dir.listFiles { f -> f.isFile && (f.name.endsWith(".yml") || f.name.endsWith(".yaml")) }
                ?.map { ".github/workflows/${it.name}" }
                ?.toSet()
                .orEmpty()
        }
        _state.value = _state.value.copy(installed = found)
    }

    fun install(template: WorkflowTemplate) {
        val root = _state.value.repoPath ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(root, template.fileName)
                    target.parentFile?.mkdirs()
                    target.writeText(template.yamlContent)
                }
            }
            _state.value = _state.value.copy(
                message = if (result.isSuccess) {
                    "Added ${template.fileName}. Review and commit it from the Changes tab."
                } else {
                    "Couldn't write the file: ${result.exceptionOrNull()?.message}"
                },
            )
            refreshInstalled()
        }
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
}

@Composable
fun WorkflowTemplatesTab(
    repoId: Long,
    snack: SnackbarHostState,
    vm: WorkflowTemplatesViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(repoId) { vm.load(repoId) }
    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); vm.dismissMessage() } }

    val byCategory = remember { WorkflowTemplateCatalog.templates.groupBy { it.category } }
    var pendingOverwrite by remember { mutableStateOf<WorkflowTemplate?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "intro") {
            Text(
                "Add a ready-made workflow to this repo. It's written to .github/workflows/ — then commit and push it from the Changes tab.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        byCategory.forEach { (category, templates) ->
            item(key = "h-$category") { SectionHeader(category) }
            items(templates, key = { it.id }) { template ->
                TemplateCard(
                    template = template,
                    installed = template.fileName in state.installed,
                    onInstall = {
                        if (template.fileName in state.installed) pendingOverwrite = template else vm.install(template)
                    },
                )
            }
        }
    }

    pendingOverwrite?.let { template ->
        dev.graft.app.ui.components.ConfirmDialog(
            title = "Replace ${template.fileName.substringAfterLast('/')}?",
            body = "A workflow with this name already exists in the repo. Replacing it overwrites your version with the template.",
            confirmLabel = "Replace",
            danger = true,
            onConfirm = { vm.install(template); pendingOverwrite = null },
            onDismiss = { pendingOverwrite = null },
        )
    }
}

@Composable
private fun TemplateCard(template: WorkflowTemplate, installed: Boolean, onInstall: () -> Unit) {
    var showYaml by remember { mutableStateOf(false) }
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(template.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (installed) {
                    StatusPill("Installed", MaterialTheme.gitColors.added, MaterialTheme.gitColors.addedContainer)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                template.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(template.fileName, style = MaterialTheme.code.tiny, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (installed) {
                    OutlinedButton(onClick = onInstall) { Text("Replace") }
                } else {
                    Button(onClick = onInstall) {
                        Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add to repo")
                    }
                }
                TextButton(onClick = { showYaml = !showYaml }) {
                    Text(if (showYaml) "Hide YAML" else "Preview")
                }
            }

            if (showYaml) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        template.yamlContent,
                        style = MaterialTheme.code.tiny,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                    )
                }
            }
        }
    }
}
