package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.DEFAULT_PUBLIC_LIMIT_BYTES
import io.github.trevarj.motd.attachment.EndpointPreset
import io.github.trevarj.motd.attachment.MAX_CUSTOM_LIMIT_BYTES
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
fun UploadsSettingsContent(viewModel: AttachmentSettingsViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    var customEndpoint by rememberSaveable { mutableStateOf("") }
    var selectedPreset by rememberSaveable { mutableStateOf(endpointPreset(config.endpoint)) }
    LaunchedEffect(config.endpoint) {
        val persistedPreset = endpointPreset(config.endpoint)
        selectedPreset = persistedPreset
        if (persistedPreset == EndpointPreset.CUSTOM) customEndpoint = config.endpoint
    }

    SettingsGroup(title = stringResource(io.github.trevarj.motd.R.string.settings_upload_destination)) {
        Column(Modifier.selectableGroup()) {
            RadioRow(
                label = stringResource(io.github.trevarj.motd.R.string.settings_upload_0x0),
                subtitle = stringResource(io.github.trevarj.motd.R.string.settings_upload_0x0_desc),
                selected = config.protocol == PasteProtocol.MULTIPART_0X0,
                enabled = true,
                onClick = { viewModel.update { it.copy(protocol = PasteProtocol.MULTIPART_0X0) } },
            )
            RadioRow(
                label = stringResource(io.github.trevarj.motd.R.string.settings_upload_termbin),
                subtitle = stringResource(io.github.trevarj.motd.R.string.settings_upload_termbin_desc),
                selected = config.protocol == PasteProtocol.TERMBIN,
                enabled = true,
                onClick = { viewModel.update { it.copy(protocol = PasteProtocol.TERMBIN) } },
            )
        }
    }

    if (config.protocol == PasteProtocol.TERMBIN) {
        UploadWarning(stringResource(io.github.trevarj.motd.R.string.settings_upload_termbin_warning))
    } else {
        SettingsGroup(title = stringResource(io.github.trevarj.motd.R.string.settings_upload_endpoint)) {
            Column(Modifier.selectableGroup()) {
                EndpointPreset.entries.forEach { option ->
                    val endpoint = option.endpoint
                    RadioRow(
                        label = when (option) {
                            EndpointPreset.CRAFTERBIN -> "CrafterBin"
                            EndpointPreset.ZERO_X_ZERO -> "0x0.st"
                            EndpointPreset.CUSTOM -> stringResource(io.github.trevarj.motd.R.string.settings_upload_custom)
                        },
                        subtitle = endpoint,
                        selected = selectedPreset == option,
                        enabled = true,
                        onClick = {
                            selectedPreset = option
                            if (endpoint != null) viewModel.update { it.copy(endpoint = endpoint) }
                            else if (customEndpoint.isNotBlank()) {
                                validateEndpoint(customEndpoint)?.let { valid -> viewModel.update { it.copy(endpoint = valid) } }
                            }
                        },
                    )
                }
            }
            if (selectedPreset == EndpointPreset.CUSTOM) {
                val endpointError = customEndpoint.isNotBlank() && validateEndpoint(customEndpoint) == null
                OutlinedTextField(
                    value = customEndpoint,
                    onValueChange = { value ->
                        customEndpoint = value
                        validateEndpoint(value)?.let { endpoint -> viewModel.update { it.copy(endpoint = endpoint) } }
                    },
                    label = { Text(stringResource(io.github.trevarj.motd.R.string.settings_upload_custom_url)) },
                    isError = endpointError,
                    supportingText = {
                        Text(stringResource(
                            if (endpointError) io.github.trevarj.motd.R.string.settings_upload_custom_error
                            else io.github.trevarj.motd.R.string.settings_upload_custom_desc,
                        ))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("settings_upload_custom_endpoint"),
                )
            }
        }

        SettingsGroup(title = stringResource(io.github.trevarj.motd.R.string.settings_upload_privacy)) {
            SwitchRow(
                title = stringResource(io.github.trevarj.motd.R.string.settings_upload_secret),
                subtitle = stringResource(io.github.trevarj.motd.R.string.settings_upload_secret_desc),
                checked = config.secretUrl,
                onCheckedChange = { value -> viewModel.update { it.copy(secretUrl = value) } },
                switchTag = "settings_upload_secret",
            )
            OutlinedTextField(
                value = config.expiry.orEmpty(),
                onValueChange = { value -> viewModel.update { it.copy(expiry = value.ifBlank { null }) } },
                label = { Text(stringResource(io.github.trevarj.motd.R.string.settings_upload_expiry)) },
                supportingText = { Text(stringResource(io.github.trevarj.motd.R.string.settings_upload_expiry_desc)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    SettingsGroup(title = stringResource(io.github.trevarj.motd.R.string.settings_upload_limits)) {
        val maximumMiB = uploadLimitMaximumMiB(config.endpoint)
        OutlinedTextField(
            value = (config.sizeLimitBytes / MIB).toString(),
            onValueChange = { value ->
                value.toLongOrNull()?.coerceIn(1, maximumMiB)?.let { mib ->
                    viewModel.update { it.copy(sizeLimitBytes = mib * MIB) }
                }
            },
            label = { Text(stringResource(io.github.trevarj.motd.R.string.settings_upload_limit)) },
            supportingText = { Text(stringResource(io.github.trevarj.motd.R.string.settings_upload_limit_desc, maximumMiB)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Composable
private fun UploadWarning(message: String) {
    androidx.compose.material3.Surface(
        color = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    ) {
        Text(
            message,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

internal fun endpointPreset(endpoint: String): EndpointPreset =
    EndpointPreset.entries.firstOrNull { it.endpoint == endpoint } ?: EndpointPreset.CUSTOM

internal fun uploadLimitMaximumMiB(endpoint: String): Long =
    if (endpointPreset(endpoint) == EndpointPreset.CUSTOM) MAX_CUSTOM_LIMIT_BYTES / MIB
    else DEFAULT_PUBLIC_LIMIT_BYTES / MIB

private const val MIB = 1024L * 1024L
