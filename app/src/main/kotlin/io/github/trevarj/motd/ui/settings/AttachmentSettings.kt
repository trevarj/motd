package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.DEFAULT_PUBLIC_LIMIT_BYTES
import io.github.trevarj.motd.attachment.EndpointPreset
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.PasteProtocol
import io.github.trevarj.motd.attachment.validateEndpoint
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AttachmentSettingsViewModel @Inject constructor(private val prefs: AttachmentPrefs) : ViewModel() {
    val config = prefs.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PasteBackendConfig())
    fun update(transform: (PasteBackendConfig) -> PasteBackendConfig) = viewModelScope.launch { prefs.setConfig(transform(config.value)) }
}

@Composable
fun AttachmentSettingsSection(viewModel: AttachmentSettingsViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    var customEndpoint by rememberSaveable { mutableStateOf("") }
    Column(Modifier.selectableGroup()) {
        SectionHeader("Uploads")
        Text("Default backend", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        RadioRow(label = "0x0-compatible", subtitle = "Encrypted HTTPS transport; files and text", selected = config.protocol == PasteProtocol.MULTIPART_0X0, enabled = true, onClick = {
            viewModel.update { it.copy(protocol = PasteProtocol.MULTIPART_0X0) }
        })
        RadioRow(label = "Termbin", subtitle = "Unencrypted public text-only service", selected = config.protocol == PasteProtocol.TERMBIN, enabled = true, onClick = {
            viewModel.update { it.copy(protocol = PasteProtocol.TERMBIN) }
        })
        if (config.protocol == PasteProtocol.MULTIPART_0X0) {
            Text("Endpoint", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            EndpointPreset.entries.filter { it.endpoint != null }.forEach { preset ->
                RadioRow(label = if (preset == EndpointPreset.CRAFTERBIN) "CrafterBin" else "0x0.st", subtitle = preset.endpoint!!,
                    selected = config.endpoint == preset.endpoint, enabled = true,
                    onClick = { viewModel.update { it.copy(endpoint = preset.endpoint) } })
            }
            OutlinedTextField(
                value = customEndpoint,
                onValueChange = { value ->
                    customEndpoint = value
                    validateEndpoint(value)?.let { endpoint -> viewModel.update { it.copy(endpoint = endpoint) } }
                },
                label = { Text("Custom HTTPS URL") },
                modifier = Modifier.padding(16.dp).testTag("settings_upload_custom_endpoint"),
                supportingText = { Text("Custom endpoints may use limits up to 512 MiB") },
            )
            ListItem(
                headlineContent = { Text("Secret URL") }, supportingContent = { Text("Ask the backend not to list the upload") },
                trailingContent = { Switch(config.secretUrl, { value -> viewModel.update { it.copy(secretUrl = value) } }) },
            )
            OutlinedTextField(config.expiry.orEmpty(), { value -> viewModel.update { it.copy(expiry = value.ifBlank { null }) } },
                label = { Text("Default expiry (for example 7d)") }, modifier = Modifier.padding(horizontal = 16.dp))
        }
        OutlinedTextField((config.sizeLimitBytes / (1024 * 1024)).toString(), { value -> value.toLongOrNull()?.let { mib ->
            viewModel.update { it.copy(sizeLimitBytes = mib * 1024 * 1024) }
        } }, label = { Text("Upload limit (MiB)") }, modifier = Modifier.padding(16.dp),
            supportingText = { Text("Public-service maximum: ${DEFAULT_PUBLIC_LIMIT_BYTES / (1024 * 1024)} MiB") })
    }
}
