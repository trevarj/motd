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
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.LITTERBOX_EXPIRIES
import io.github.trevarj.motd.attachment.MAX_CUSTOM_LIMIT_BYTES
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.forBackend
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
    LaunchedEffect(config.customEndpoint) {
        customEndpoint = config.customEndpoint
    }

    SettingsGroup(title = stringResource(io.github.trevarj.motd.R.string.settings_upload_destination)) {
        Column(Modifier.selectableGroup()) {
            AttachmentBackend.entries.forEach { backend ->
                RadioRow(
                    label = backend.label,
                    subtitle = backendDescription(backend),
                    selected = config.backend == backend,
                    enabled = true,
                    onClick = { viewModel.update { it.forBackend(backend) } },
                )
            }
        }
    }

    if (config.backend == AttachmentBackend.TERMBIN) {
        UploadWarning(stringResource(io.github.trevarj.motd.R.string.settings_upload_termbin_warning))
    }
    if (config.backend == AttachmentBackend.CUSTOM_0X0) {
        SettingsGroup(title = stringResource(io.github.trevarj.motd.R.string.settings_upload_endpoint)) {
            val endpointError = customEndpoint.isNotBlank() && validateEndpoint(customEndpoint) == null
            OutlinedTextField(
                value = customEndpoint,
                onValueChange = { value ->
                    customEndpoint = value
                    validateEndpoint(value)?.let { endpoint ->
                        viewModel.update { it.copy(endpoint = endpoint, customEndpoint = endpoint) }
                    }
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

    when (config.backend) {
        AttachmentBackend.CRAFTERBIN, AttachmentBackend.ZERO_X_ZERO, AttachmentBackend.CUSTOM_0X0 ->
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
        AttachmentBackend.LITTERBOX -> SettingsGroup(
            title = stringResource(io.github.trevarj.motd.R.string.settings_upload_privacy),
        ) {
            Column(Modifier.selectableGroup()) {
                LITTERBOX_EXPIRIES.forEach { expiry ->
                    RadioRow(
                        label = litterboxExpiryLabel(expiry),
                        subtitle = stringResource(io.github.trevarj.motd.R.string.settings_upload_litterbox_expiry_desc),
                        selected = config.litterboxExpiry == expiry,
                        enabled = true,
                        onClick = { viewModel.update { it.copy(litterboxExpiry = expiry) } },
                    )
                }
            }
        }
        AttachmentBackend.UGUU -> UploadWarning(
            stringResource(io.github.trevarj.motd.R.string.settings_upload_uguu_warning),
            caution = false,
        )
        AttachmentBackend.CNET -> UploadWarning(
            stringResource(io.github.trevarj.motd.R.string.settings_upload_cnet_warning),
            caution = false,
        )
        AttachmentBackend.CATBOX -> UploadWarning(stringResource(io.github.trevarj.motd.R.string.settings_upload_catbox_warning))
        AttachmentBackend.TERMBIN -> Unit
    }

    SettingsGroup(title = stringResource(io.github.trevarj.motd.R.string.settings_upload_limits)) {
        val maximumMiB = uploadLimitMaximumMiB(config.backend)
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
private fun UploadWarning(message: String, caution: Boolean = true) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        color = if (caution) colors.errorContainer else colors.surfaceContainerHigh,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    ) {
        Text(
            message,
            color = if (caution) colors.onErrorContainer else colors.onSurface,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

internal fun uploadLimitMaximumMiB(backend: AttachmentBackend): Long =
    if (backend == AttachmentBackend.CUSTOM_0X0) MAX_CUSTOM_LIMIT_BYTES / MIB
    else DEFAULT_PUBLIC_LIMIT_BYTES / MIB

internal fun backendDescription(backend: AttachmentBackend): String = when (backend) {
    AttachmentBackend.CRAFTERBIN -> "Files, photos, and text • configurable expiry"
    AttachmentBackend.ZERO_X_ZERO -> "Files, photos, and text • public service"
    AttachmentBackend.CUSTOM_0X0 -> "Your own HTTPS 0x0-compatible endpoint"
    AttachmentBackend.CNET -> "Files, photos, and text • rolling 180 days • deletable"
    AttachmentBackend.UGUU -> "Files, photos, and text • 3 hours"
    AttachmentBackend.LITTERBOX -> "Files, photos, and text • 1–72 hours"
    AttachmentBackend.CATBOX -> "Files, photos, and text • long-lived"
    AttachmentBackend.TERMBIN -> "Text only • unencrypted TCP"
}

internal fun litterboxExpiryLabel(expiry: String): String = when (expiry) {
    "1h" -> "1 hour"
    "12h" -> "12 hours"
    "24h" -> "24 hours"
    "72h" -> "72 hours"
    else -> expiry
}

private const val MIB = 1024L * 1024L
