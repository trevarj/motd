package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.LITTERBOX_EXPIRIES
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.audio.VoiceConfig
import io.github.trevarj.motd.audio.VoicePrefs
import io.github.trevarj.motd.avatar.AvatarConfig
import io.github.trevarj.motd.avatar.AvatarController
import io.github.trevarj.motd.avatar.AvatarPrefs
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.ui.nav.SettingsTarget
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SecuritySettingsUiState(
    val settings: Settings = Settings(),
    val contentPreviews: ContentPreviewConfig = ContentPreviewConfig(),
    val voice: VoiceConfig = VoiceConfig(),
    val avatars: AvatarConfig = AvatarConfig(),
    val uploads: PasteBackendConfig = PasteBackendConfig(),
)

@HiltViewModel
class SecuritySettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val contentPreviewPrefs: ContentPreviewPrefs,
        private val voicePrefs: VoicePrefs,
        private val avatarPrefs: AvatarPrefs,
        private val avatarController: AvatarController,
        private val attachmentPrefs: AttachmentPrefs,
    ) : ViewModel() {
        val state =
            combine(
                settingsRepository.settings,
                contentPreviewPrefs.config,
                voicePrefs.config,
                avatarPrefs.config,
                attachmentPrefs.config,
                ::SecuritySettingsUiState,
            ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecuritySettingsUiState())

        fun setSendTypingIndicators(value: Boolean) = launch { settingsRepository.setSendTypingIndicators(value) }

        fun setShowTypingIndicators(value: Boolean) = launch { settingsRepository.setShowTypingIndicators(value) }

        fun setShowImages(value: Boolean) = launch { contentPreviewPrefs.setShowImages(value) }

        fun setShowLinkPreviews(value: Boolean) = launch { contentPreviewPrefs.setShowLinkPreviews(value) }

        fun setAutoLoadOnUnmetered(value: Boolean) = launch { contentPreviewPrefs.setAutoLoadOnUnmetered(value) }

        fun setAutoLoadOnMetered(value: Boolean) = launch { contentPreviewPrefs.setAutoLoadOnMetered(value) }

        fun setShowSharedAvatars(value: Boolean) = launch { avatarController.setShowSharedAvatars(value) }

        fun setVoiceEncryptionDefault(value: Boolean) = launch { voicePrefs.setEncryptionDefault(value) }

        fun updateUploads(transform: (PasteBackendConfig) -> PasteBackendConfig) = launch { attachmentPrefs.updateConfig(transform) }

        private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }
    }

@Composable
fun SecuritySettingsScreen(
    onBack: () -> Unit,
    onOpenDirectConnections: () -> Unit,
    onOpenUploads: () -> Unit,
    target: SettingsTarget? = null,
    viewModel: SecuritySettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(
        title = stringResource(R.string.settings_security),
        onBack = onBack,
        modifier = Modifier.testTag("screen_security_settings"),
    ) {
        SecuritySettingsContent(
            state = state,
            target = target,
            onOpenDirectConnections = onOpenDirectConnections,
            onOpenUploads = onOpenUploads,
            onSendTypingIndicators = viewModel::setSendTypingIndicators,
            onShowTypingIndicators = viewModel::setShowTypingIndicators,
            onShowImages = viewModel::setShowImages,
            onShowLinkPreviews = viewModel::setShowLinkPreviews,
            onAutoLoadOnUnmetered = viewModel::setAutoLoadOnUnmetered,
            onAutoLoadOnMetered = viewModel::setAutoLoadOnMetered,
            onShowSharedAvatars = viewModel::setShowSharedAvatars,
            onVoiceEncryptionDefault = viewModel::setVoiceEncryptionDefault,
            onUpdateUploads = viewModel::updateUploads,
        )
    }
}

@Composable
fun SecuritySettingsContent(
    state: SecuritySettingsUiState,
    target: SettingsTarget? = null,
    onOpenDirectConnections: () -> Unit,
    onOpenUploads: () -> Unit,
    onSendTypingIndicators: (Boolean) -> Unit,
    onShowTypingIndicators: (Boolean) -> Unit,
    onShowImages: (Boolean) -> Unit,
    onShowLinkPreviews: (Boolean) -> Unit,
    onAutoLoadOnUnmetered: (Boolean) -> Unit,
    onAutoLoadOnMetered: (Boolean) -> Unit,
    onShowSharedAvatars: (Boolean) -> Unit,
    onVoiceEncryptionDefault: (Boolean) -> Unit,
    onUpdateUploads: ((PasteBackendConfig) -> PasteBackendConfig) -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_typing_section)) {
        SwitchRow(
            title = stringResource(R.string.settings_send_typing_indicators),
            subtitle = stringResource(R.string.settings_send_typing_indicators_desc),
            checked = state.settings.sendTypingIndicators,
            onCheckedChange = onSendTypingIndicators,
            switchTag = "settings_switch_send_typing_indicators",
            requestedTarget = target?.name,
            targetName = SettingsTarget.SEND_TYPING.name,
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SwitchRow(
            title = stringResource(R.string.settings_show_typing_indicators),
            subtitle = stringResource(R.string.settings_show_typing_indicators_desc),
            checked = state.settings.showTypingIndicators,
            onCheckedChange = onShowTypingIndicators,
            switchTag = "settings_switch_show_typing_indicators",
            requestedTarget = target?.name,
            targetName = SettingsTarget.SHOW_TYPING.name,
        )
    }
    SettingsGroup(title = stringResource(R.string.settings_remote_content_section)) {
        SwitchRow(
            title = stringResource(R.string.settings_show_images),
            subtitle = stringResource(R.string.settings_show_images_desc),
            checked = state.contentPreviews.showImages,
            onCheckedChange = onShowImages,
            switchTag = "settings_switch_show_images",
            requestedTarget = target?.name,
            targetName = SettingsTarget.IMAGES.name,
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SwitchRow(
            title = stringResource(R.string.settings_auto_media_unmetered),
            subtitle = stringResource(R.string.settings_auto_media_unmetered_desc),
            checked = state.contentPreviews.autoLoadOnUnmetered,
            onCheckedChange = onAutoLoadOnUnmetered,
            switchTag = "settings_switch_auto_media_unmetered",
            enabled = state.contentPreviews.showImages,
            disabledExplanation = stringResource(R.string.settings_media_disabled_explanation),
            requestedTarget = target?.name,
            targetName = SettingsTarget.MEDIA_UNMETERED.name,
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SwitchRow(
            title = stringResource(R.string.settings_auto_media_metered),
            subtitle = stringResource(R.string.settings_auto_media_metered_desc),
            checked = state.contentPreviews.autoLoadOnMetered,
            onCheckedChange = onAutoLoadOnMetered,
            switchTag = "settings_switch_auto_media_metered",
            enabled = state.contentPreviews.showImages,
            disabledExplanation = stringResource(R.string.settings_media_disabled_explanation),
            requestedTarget = target?.name,
            targetName = SettingsTarget.MEDIA_METERED.name,
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SwitchRow(
            title = stringResource(R.string.settings_show_link_previews),
            subtitle = stringResource(R.string.settings_show_link_previews_desc),
            checked = state.contentPreviews.showLinkPreviews,
            onCheckedChange = onShowLinkPreviews,
            switchTag = "settings_switch_show_link_previews",
            requestedTarget = target?.name,
            targetName = SettingsTarget.LINK_PREVIEWS.name,
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SwitchRow(
            title = stringResource(R.string.settings_show_shared_avatars),
            subtitle = stringResource(R.string.settings_show_shared_avatars_desc),
            checked = state.avatars.showSharedAvatars,
            onCheckedChange = onShowSharedAvatars,
            switchTag = "settings_switch_show_shared_avatars",
            requestedTarget = target?.name,
            targetName = SettingsTarget.SHARED_AVATARS.name,
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SwitchRow(
            title = stringResource(R.string.settings_voice_encryption),
            subtitle = stringResource(R.string.settings_voice_encryption_desc),
            checked = state.voice.encryptionDefault,
            onCheckedChange = onVoiceEncryptionDefault,
            switchTag = "settings_switch_voice_encryption",
            requestedTarget = target?.name,
            targetName = SettingsTarget.VOICE_ENCRYPTION.name,
        )
    }
    SettingsGroup(title = stringResource(R.string.settings_transfers_section)) {
        UploadPrivacyControls(
            config = state.uploads,
            target = target,
            onUpdate = onUpdateUploads,
            onOpenUploads = onOpenUploads,
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SettingsNavigationRow(
            icon = Icons.Outlined.AttachFile,
            title = stringResource(R.string.settings_direct_connections),
            summary = stringResource(R.string.settings_direct_connections_summary),
            modifier = Modifier.testTag("settings_direct_connections"),
            requestedTarget = target?.name,
            targetName = SettingsTarget.DIRECT_CONNECTIONS.name,
            onClick = onOpenDirectConnections,
        )
    }
}

@Composable
internal fun UploadPrivacyControls(
    config: PasteBackendConfig,
    target: SettingsTarget?,
    onUpdate: ((PasteBackendConfig) -> PasteBackendConfig) -> Unit,
    onOpenUploads: () -> Unit,
) {
    if (!backendHasPrivacyControls(config.backend)) {
        SettingsNavigationRow(
            title = stringResource(R.string.settings_upload_privacy),
            summary = stringResource(R.string.settings_upload_privacy_unavailable),
            modifier = Modifier.testTag("settings_upload_privacy_unavailable"),
            requestedTarget = target?.name,
            targetName = SettingsTarget.UPLOAD_PRIVACY.name,
            onClick = onOpenUploads,
        )
        return
    }
    SettingsTarget(target?.name, SettingsTarget.UPLOAD_PRIVACY.name) { targetModifier ->
        Column(modifier = targetModifier) {
            Text(
                text = stringResource(R.string.settings_upload_privacy),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            when (config.backend) {
                AttachmentBackend.CRAFTERBIN, AttachmentBackend.ZERO_X_ZERO, AttachmentBackend.CUSTOM_0X0 -> {
                    SwitchRow(
                        title = stringResource(R.string.settings_upload_secret),
                        subtitle = stringResource(R.string.settings_upload_secret_desc),
                        checked = config.secretUrl,
                        onCheckedChange = { value -> onUpdate { it.copy(secretUrl = value) } },
                        switchTag = "settings_upload_secret",
                    )
                    val expiryDays = uploadExpiryWholeDays(config.expiry)
                    OutlinedTextField(
                        value = expiryDays?.let { pluralStringResource(R.plurals.settings_upload_expiry_days, it, it) } ?: config.expiry.orEmpty(),
                        onValueChange = { value -> onUpdate { it.copy(expiry = uploadExpiryHours(value)) } },
                        label = { Text(stringResource(R.string.settings_upload_expiry)) },
                        supportingText = { Text(stringResource(R.string.settings_upload_expiry_desc)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("settings_upload_expiry"),
                    )
                }

                AttachmentBackend.LITTERBOX -> {
                    Column(Modifier.selectableGroup()) {
                        LITTERBOX_EXPIRIES.forEach { expiry ->
                            RadioRow(
                                label = litterboxExpiryLabel(expiry),
                                subtitle = stringResource(R.string.settings_upload_litterbox_expiry_desc),
                                selected = config.litterboxExpiry == expiry,
                                enabled = true,
                                onClick = { onUpdate { it.copy(litterboxExpiry = expiry) } },
                            )
                        }
                    }
                }

                else -> {
                    // Providers without privacy controls link back to Uploads above.
                }
            }
        }
    }
}
