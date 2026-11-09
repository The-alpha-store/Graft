package dev.graft.app.ui.screens.credential

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.graft.app.App
import dev.graft.app.data.repository.DecryptedCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CredentialUiState(
    val credentials: List<DecryptedCredential> = emptyList(),
    val editing: DecryptedCredential? = null, // null + showEditor=true => "new"
    val showEditor: Boolean = false
)

class CredentialViewModel(application: Application) : AndroidViewModel(application) {
    private val credentialRepository = (application as App).credentialRepository

    private val editing = MutableStateFlow<DecryptedCredential?>(null)
    private val showEditor = MutableStateFlow(false)

    val uiState: StateFlow<CredentialUiState> = combine(
        credentialRepository.allCredentials,
        editing,
        showEditor
    ) { creds, editingValue, show ->
        CredentialUiState(credentials = creds, editing = editingValue, showEditor = show)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CredentialUiState()
    )

    fun startAdding() {
        editing.value = null
        showEditor.value = true
    }

    fun startEditing(credential: DecryptedCredential) {
        editing.value = credential
        showEditor.value = true
    }

    fun dismissEditor() {
        showEditor.value = false
        editing.value = null
    }

    fun save(name: String, username: String, token: String) {
        viewModelScope.launch {
            val existing = editing.value
            if (existing != null) {
                credentialRepository.updateCredential(existing.id, name, username, token)
            } else {
                credentialRepository.addCredential(name, username, token)
            }
            dismissEditor()
        }
    }

    fun delete(credential: DecryptedCredential) {
        viewModelScope.launch {
            credentialRepository.deleteCredential(credential)
        }
    }
}
