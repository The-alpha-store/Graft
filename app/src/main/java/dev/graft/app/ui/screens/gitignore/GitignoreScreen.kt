package dev.graft.app.ui.screens.gitignore

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.graft.app.App
import dev.graft.app.git.GitEngine
import dev.graft.app.git.GitResult
import dev.graft.app.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.Git

data class GitignoreUiState(
    val content: String = "", val isLoading: Boolean = true,
    val isSaving: Boolean = false, val message: String? = null,
)

/** A canned .gitignore block for a common project type — deliberately short lists of the
 * highest-value patterns, not exhaustive github/gitignore-style dumps, since those balloon
 * a small repo's .gitignore for stuff that will never actually appear in this project. */
data class GitignoreTemplate(val label: String, val rules: String)

val GITIGNORE_TEMPLATES = listOf(
    GitignoreTemplate(
        "Android",
        "*.apk\n*.aab\n*.ap_\n*.dex\nbuild/\n.gradle/\nlocal.properties\n.cxx/\ncaptures/\n*.jks\n*.keystore",
    ),
    GitignoreTemplate(
        "Kotlin / Java",
        "*.class\n*.jar\n*.war\nbuild/\ntarget/\nout/\n.gradle/\nhs_err_pid*",
    ),
    GitignoreTemplate(
        "Node",
        "node_modules/\nnpm-debug.log*\nyarn-debug.log*\nyarn-error.log*\ndist/\n.env",
    ),
    GitignoreTemplate(
        "Python",
        "__pycache__/\n*.py[cod]\n*.egg-info/\n.venv/\nvenv/\n.env\ndist/\nbuild/",
    ),
    GitignoreTemplate(
        "OS junk (macOS / Windows)",
        ".DS_Store\nThumbs.db\ndesktop.ini\n\$RECYCLE.BIN/",
    ),
    GitignoreTemplate(
        "IDE",
        ".idea/\n*.iml\n.vscode/\n*.swp",
    ),
)

class GitignoreViewModel(app: Application) : AndroidViewModel(app) {
    private val repoRepo = (app as App).repoRepository
    private val _state = MutableStateFlow(GitignoreUiState())
    val state: StateFlow<GitignoreUiState> = _state.asStateFlow()
    private var git: Git? = null

    fun load(repoId: Long) {
        viewModelScope.launch {
            val repo = repoRepo.getById(repoId) ?: return@launch
            when (val gr = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> _state.value = _state.value.copy(isLoading = false, message = gr.message)
                is GitResult.Success -> {
                    git = gr.data
                    when (val r = GitEngine.readGitignore(gr.data)) {
                        is GitResult.Success -> _state.value = _state.value.copy(content = r.data, isLoading = false)
                        is GitResult.Error -> _state.value = _state.value.copy(isLoading = false, message = r.message)
                    }
                }
            }
        }
    }

    fun onContentChanged(v: String) { _state.value = _state.value.copy(content = v) }

    /** Appends a canned template's rules to whatever is already in the editor, with a
     * blank-line separator so it reads as a distinct block rather than merging into
     * existing lines. Doesn't touch git — this is purely local text-editor state until Save. */
    fun insertTemplate(template: GitignoreTemplate) {
        val current = _state.value.content
        val separator = if (current.isBlank()) "" else "\n\n"
        _state.value = _state.value.copy(content = current + separator + template.rules)
    }

    fun save() {
        val g = git ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true)
            when (val r = GitEngine.writeGitignore(g, _state.value.content)) {
                is GitResult.Success -> _state.value = _state.value.copy(isSaving = false, message = ".gitignore saved")
                is GitResult.Error -> _state.value = _state.value.copy(isSaving = false, message = r.message)
            }
        }
    }

    fun dismiss() { _state.value = _state.value.copy(message = null) }
    override fun onCleared() { super.onCleared(); git?.close() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitignoreScreen(repoId: Long, onBack: () -> Unit, vm: GitignoreViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    var showTemplateMenu by remember { mutableStateOf(false) }
    LaunchedEffect(repoId) { vm.load(repoId) }
    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); vm.dismiss() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(".gitignore", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { showTemplateMenu = true }) {
                            Icon(Icons.Filled.PlaylistAdd, "Insert template")
                        }
                        DropdownMenu(expanded = showTemplateMenu, onDismissRequest = { showTemplateMenu = false }) {
                            GITIGNORE_TEMPLATES.forEach { template ->
                                DropdownMenuItem(
                                    text = { Text(template.label) },
                                    onClick = { showTemplateMenu = false; vm.insertTemplate(template) },
                                )
                            }
                        }
                    }
                    IconButton(onClick = vm::save, enabled = !state.isSaving) {
                        if (state.isSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
                        else Icon(Icons.Filled.Save, "Save")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface, navigationIconContentColor = MaterialTheme.colorScheme.onSurface, actionIconContentColor = MaterialTheme.colorScheme.onSurface),
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(Modifier.fillMaxSize().padding(pad)) {
                Text("Edit patterns — one per line. Lines starting with # are comments.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                OutlinedTextField(
                    value = state.content,
                    onValueChange = vm::onContentChanged,
                    modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp, vertical = 4.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("# e.g.\n*.log\nbuild/\n.DS_Store", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
        }
    }
}
