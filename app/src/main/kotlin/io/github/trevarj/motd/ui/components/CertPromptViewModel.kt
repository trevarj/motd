package io.github.trevarj.motd.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bridges the Hilt-provided [ConnectionManager]'s TOFU cert prompts to the global dialog host in
 * MainActivity (plans/12). Kept minimal: it just exposes the prompt flow and forwards trust/dismiss.
 */
@HiltViewModel
class CertPromptViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
) : ViewModel() {
    val certPrompts: StateFlow<List<CertPrompt>> = connectionManager.certPrompts

    fun trust(prompt: CertPrompt) {
        viewModelScope.launch { connectionManager.trustCert(prompt) }
    }

    fun dismiss(prompt: CertPrompt) {
        connectionManager.dismissCertPrompt(prompt)
    }
}
