package io.github.trevarj.motd.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.attachment.AVAILABLE_ATTACHMENT_BACKENDS
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.LITTERBOX_EXPIRIES
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.backendMaxBytes
import io.github.trevarj.motd.attachment.forBackend
import io.github.trevarj.motd.attachment.validateEndpoint
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.MotdMotion
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AttachmentSettingsViewModel
    @Inject
    constructor(
        private val prefs: AttachmentPrefs,
    ) : ViewModel() {
        val config = prefs.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PasteBackendConfig())

        fun update(transform: (PasteBackendConfig) -> PasteBackendConfig) = viewModelScope.launch { prefs.updateConfig(transform) }
    }

@Composable
fun UploadsSettingsScreen(
    onBack: () -> Unit,
    target: SettingsTarget? = null,
    viewModel: AttachmentSettingsViewModel = hiltViewModel(),
) {
    SettingsScaffold(
        title = stringResource(R.string.settings_uploads),
        onBack = onBack,
        modifier = Modifier.testTag("screen_uploads_settings"),
    ) {
        UploadsSettingsContent(target = target, viewModel = viewModel)
    }
}

@Composable
fun UploadsSettingsContent(
    target: SettingsTarget? = null,
    viewModel: AttachmentSettingsViewModel = hiltViewModel(),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    var customEndpoint by rememberSaveable { mutableStateOf("") }
    var providerSheet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(config.customEndpoint) {
        customEndpoint = config.customEndpoint
    }

    val connectionFallsBackToProvider =
        target == SettingsTarget.UPLOAD_CONNECTION && config.backend != AttachmentBackend.CUSTOM_0X0
    val providerTarget =
        if (target == SettingsTarget.UPLOADS || connectionFallsBackToProvider) {
            SettingsTarget.UPLOAD_PROVIDER.name
        } else {
            target?.name
        }
    SettingsGroup(title = stringResource(R.string.settings_upload_destination)) {
        SettingsTarget(
            providerTarget,
            SettingsTarget.UPLOAD_PROVIDER.name,
        ) { modifier ->
            SettingsNavigationRow(
                title = stringResource(R.string.settings_upload_provider),
                summary = backendDescription(config.backend),
                value = attachmentBackendLabel(config.backend),
                modifier = modifier.testTag("settings_upload_provider"),
                onClick = { providerSheet = true },
            )
        }
    }

    // One animated region hosts every backend-specific block (warning, endpoint, options) so a
    // radio pick crossfades and eases the height once instead of snapping three conditionals.
    AnimatedContent(
        targetState = config.backend,
        transitionSpec = {
            (fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut))
                .using(SizeTransform(sizeAnimationSpec = { _, _ -> MotdMotion.contentSize }))
        },
        label = "upload_backend_options",
    ) { backend ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (backend == AttachmentBackend.TERMBIN) {
                UploadWarning(stringResource(io.github.trevarj.motd.R.string.settings_upload_termbin_warning))
            }
            if (backend == AttachmentBackend.CUSTOM_0X0) {
                SettingsTarget(target?.name, SettingsTarget.UPLOAD_CONNECTION.name) { targetModifier ->
                    SettingsGroup(
                        title = stringResource(R.string.settings_upload_endpoint),
                        modifier = targetModifier,
                    ) {
                        var usernameDraft by remember { mutableStateOf<String?>(null) }
                        var passwordDraft by remember { mutableStateOf<String?>(null) }
                        val endpointError = customEndpoint.isNotBlank() && validateEndpoint(customEndpoint) == null
                        OutlinedTextField(
                            value = customEndpoint,
                            onValueChange = { value ->
                                customEndpoint = value
                                if (value.isBlank()) {
                                    usernameDraft = ""
                                    passwordDraft = ""
                                    viewModel.update {
                                        it.copy(endpoint = "", customEndpoint = "", username = "", password = "")
                                    }
                                } else {
                                    validateEndpoint(value)?.let { endpoint ->
                                        val authorityChanged =
                                            config.endpoint.isNotBlank() &&
                                                !sameUploadAuthority(config.endpoint, endpoint)
                                        if (authorityChanged) {
                                            usernameDraft = ""
                                            passwordDraft = ""
                                        }
                                        viewModel.update {
                                            it.copy(
                                                endpoint = endpoint,
                                                customEndpoint = endpoint,
                                                username = if (authorityChanged) "" else it.username,
                                                password = if (authorityChanged) "" else it.password,
                                            )
                                        }
                                    }
                                }
                            },
                            label = { Text(stringResource(io.github.trevarj.motd.R.string.settings_upload_custom_url)) },
                            isError = endpointError,
                            supportingText = {
                                Text(
                                    stringResource(
                                        if (endpointError) {
                                            io.github.trevarj.motd.R.string.settings_upload_custom_error
                                        } else {
                                            io.github.trevarj.motd.R.string.settings_upload_custom_desc
                                        },
                                    ),
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .testTag("settings_upload_custom_endpoint"),
                        )
                        OutlinedTextField(
                            value = usernameDraft ?: config.username,
                            onValueChange = { value ->
                                usernameDraft = value
                                viewModel.update { it.copy(username = value) }
                            },
                            label = { Text(stringResource(io.github.trevarj.motd.R.string.settings_upload_custom_username)) },
                            singleLine = true,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .testTag("settings_upload_custom_username"),
                        )
                        OutlinedTextField(
                            value = passwordDraft ?: config.password,
                            onValueChange = { value ->
                                passwordDraft = value
                                viewModel.update { it.copy(password = value) }
                            },
                            label = { Text(stringResource(io.github.trevarj.motd.R.string.settings_upload_custom_password)) },
                            visualTransformation =
                                androidx.compose.ui.text.input
                                    .PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .testTag("settings_upload_custom_password"),
                        )
                    }
                }
            }

            when (backend) {
                AttachmentBackend.CRAFTERBIN,
                AttachmentBackend.ZERO_X_ZERO,
                AttachmentBackend.CUSTOM_0X0,
                AttachmentBackend.LITTERBOX,
                -> {
                    // Privacy and expiry controls are shown on Security.
                }

                AttachmentBackend.UGUU -> {
                    UploadWarning(
                        stringResource(io.github.trevarj.motd.R.string.settings_upload_uguu_warning),
                        caution = false,
                    )
                }

                AttachmentBackend.X0_AT -> {
                    UploadWarning(
                        stringResource(io.github.trevarj.motd.R.string.settings_upload_x0at_warning),
                        caution = false,
                    )
                }

                AttachmentBackend.CNET -> {
                    UploadWarning(
                        stringResource(io.github.trevarj.motd.R.string.settings_upload_cnet_warning),
                        caution = false,
                    )
                }

                AttachmentBackend.CATBOX -> {
                    UploadWarning(stringResource(io.github.trevarj.motd.R.string.settings_upload_catbox_warning))
                }

                AttachmentBackend.SOJU_FILEHOST -> {
                    UploadWarning(
                        stringResource(io.github.trevarj.motd.R.string.settings_upload_soju_warning),
                        caution = false,
                    )
                }

                AttachmentBackend.TERMBIN -> {}
            }
        }
    }

    SettingsTarget(target?.name, SettingsTarget.UPLOAD_LIMIT.name) { targetModifier ->
        SettingsGroup(title = stringResource(R.string.settings_upload_limits)) {
            val maximumMiB = uploadLimitMaximumMiB(config.backend)
            OutlinedTextField(
                value = (config.sizeLimitBytes / MIB).toString(),
                onValueChange = { value ->
                    value.toLongOrNull()?.coerceIn(1, maximumMiB)?.let { mib ->
                        viewModel.update { it.copy(sizeLimitBytes = mib * MIB) }
                    }
                },
                label = { Text(stringResource(R.string.settings_upload_limit)) },
                supportingText = { Text(stringResource(R.string.settings_upload_limit_desc, maximumMiB)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = targetModifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
    if (providerSheet) {
        SingleChoiceSheet(
            title = stringResource(R.string.settings_upload_provider),
            selected = config.backend,
            options =
                AVAILABLE_ATTACHMENT_BACKENDS.map { backend ->
                    ChoiceOption(
                        value = backend,
                        label = attachmentBackendLabel(backend),
                        summary = backendDescription(backend),
                        tag = "settings_upload_backend_${backend.name.lowercase()}",
                    )
                },
            onSelect = { backend -> viewModel.update { it.forBackend(backend) } },
            onDismiss = { providerSheet = false },
            tag = "settings_upload_provider_sheet",
        )
    }
}

@Composable
private fun UploadWarning(
    message: String,
    caution: Boolean = true,
) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        color = if (caution) colors.errorContainer else colors.surfaceContainerHigh,
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(16.dp),
    ) {
        Text(
            message,
            color = if (caution) colors.onErrorContainer else colors.onSurface,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

internal fun sameUploadAuthority(
    first: String,
    second: String,
): Boolean =
    runCatching {
        val a = java.net.URI(first)
        val b = java.net.URI(second)
        val aPort = if (a.port >= 0) a.port else 443
        val bPort = if (b.port >= 0) b.port else 443
        a.scheme.equals("https", ignoreCase = true) &&
            b.scheme.equals("https", ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            aPort == bPort
    }.getOrDefault(false)

internal fun uploadExpiryWholeDays(hours: String?): Int? =
    hours
        ?.toLongOrNull()
        ?.takeIf { it > 0L && it % 24L == 0L && it / 24L <= Int.MAX_VALUE.toLong() }
        ?.div(24L)
        ?.toInt()

internal fun uploadExpiryHours(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val days =
        Regex("""(\d+)\s+days?""", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
    return days?.let { runCatching { Math.multiplyExact(it, 24L).toString() }.getOrNull() } ?: trimmed
}

internal fun uploadLimitMaximumMiB(backend: AttachmentBackend): Long = backendMaxBytes(backend) / MIB

internal fun backendHasPrivacyControls(backend: AttachmentBackend): Boolean =
    backend in
        setOf(
            AttachmentBackend.CRAFTERBIN,
            AttachmentBackend.ZERO_X_ZERO,
            AttachmentBackend.CUSTOM_0X0,
            AttachmentBackend.LITTERBOX,
        )

@Composable
internal fun attachmentBackendLabel(backend: AttachmentBackend): String =
    stringResource(
        when (backend) {
            AttachmentBackend.CRAFTERBIN -> R.string.upload_backend_crafterbin
            AttachmentBackend.ZERO_X_ZERO -> R.string.upload_backend_zero_x_zero
            AttachmentBackend.X0_AT -> R.string.upload_backend_x0_at
            AttachmentBackend.CUSTOM_0X0 -> R.string.upload_backend_custom
            AttachmentBackend.CNET -> R.string.upload_backend_cnet
            AttachmentBackend.UGUU -> R.string.upload_backend_uguu
            AttachmentBackend.LITTERBOX -> R.string.upload_backend_litterbox
            AttachmentBackend.CATBOX -> R.string.upload_backend_catbox
            AttachmentBackend.SOJU_FILEHOST -> R.string.upload_backend_soju
            AttachmentBackend.TERMBIN -> R.string.upload_backend_termbin
        },
    )

@Composable
internal fun backendDescription(backend: AttachmentBackend): String =
    stringResource(
        when (backend) {
            AttachmentBackend.CRAFTERBIN -> R.string.upload_backend_crafterbin_desc
            AttachmentBackend.ZERO_X_ZERO -> R.string.upload_backend_zero_x_zero_desc
            AttachmentBackend.X0_AT -> R.string.upload_backend_x0_at_desc
            AttachmentBackend.CUSTOM_0X0 -> R.string.upload_backend_custom_desc
            AttachmentBackend.CNET -> R.string.upload_backend_cnet_desc
            AttachmentBackend.UGUU -> R.string.upload_backend_uguu_desc
            AttachmentBackend.LITTERBOX -> R.string.upload_backend_litterbox_desc
            AttachmentBackend.CATBOX -> R.string.upload_backend_catbox_desc
            AttachmentBackend.SOJU_FILEHOST -> R.string.upload_backend_soju_desc
            AttachmentBackend.TERMBIN -> R.string.upload_backend_termbin_desc
        },
    )

@Composable
internal fun litterboxExpiryLabel(expiry: String): String =
    when (expiry) {
        "1h" -> pluralStringResource(R.plurals.settings_upload_expiry_hours, 1, 1)
        "12h" -> pluralStringResource(R.plurals.settings_upload_expiry_hours, 12, 12)
        "24h" -> pluralStringResource(R.plurals.settings_upload_expiry_hours, 24, 24)
        "72h" -> pluralStringResource(R.plurals.settings_upload_expiry_hours, 72, 72)
        else -> expiry
    }

private const val MIB = 1024L * 1024L
