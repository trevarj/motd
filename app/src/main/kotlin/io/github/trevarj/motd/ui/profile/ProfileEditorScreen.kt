package io.github.trevarj.motd.ui.profile

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.profile.ProfileAccess
import io.github.trevarj.motd.profile.ProfileEditorState
import io.github.trevarj.motd.profile.ProfileKey
import io.github.trevarj.motd.profile.ProfileTarget
import io.github.trevarj.motd.profile.ProfileWriteResult
import io.github.trevarj.motd.ui.chat.AttachmentSheets
import io.github.trevarj.motd.ui.chat.parseColorHex
import io.github.trevarj.motd.ui.components.ExplicitAvatarUrlPreview
import io.github.trevarj.motd.ui.settings.SettingsGroup
import io.github.trevarj.motd.ui.settings.SettingsNavigationRow
import io.github.trevarj.motd.ui.settings.SettingsScaffold
import io.github.trevarj.motd.ui.settings.SettingsValueRow
import io.github.trevarj.motd.ui.theme.readableOutlinedTextFieldColors
import io.github.trevarj.motd.ui.components.EmojiOutlinedTextField as OutlinedTextField
import io.github.trevarj.motd.ui.components.EmojiText as Text

private val selfFields = listOf(ProfileKey.AVATAR, ProfileKey.BOT, ProfileKey.COLOR, ProfileKey.DISPLAY_NAME, ProfileKey.HOMEPAGE, ProfileKey.PRONOUNS, ProfileKey.STATUS)
private val channelFields = listOf(ProfileKey.AVATAR, ProfileKey.DISPLAY_NAME)

@Composable
internal fun ProfileEditorScreen(
    target: ProfileTarget,
    onBack: () -> Unit,
    onOpenNetworkSettings: (Long) -> Unit,
    viewModel: ProfileEditorViewModel = hiltViewModel(),
) {
    val editor = remember(viewModel, target) { viewModel.editor(target) }
    val state by editor.collectAsStateWithLifecycle(initialValue = null)
    val writeState by viewModel.writeState.collectAsStateWithLifecycle()
    val identity = state?.identity
    val readable = state?.readable == true
    LaunchedEffect(viewModel, target, identity, readable) {
        if (readable) viewModel.refresh(target)
    }
    ProfileEditorContent(
        target = target,
        state = state,
        writeState = writeState,
        onBack = onBack,
        onOpenNetworkSettings = onOpenNetworkSettings,
        onClearResult = viewModel::clearResult,
        onWrite = { field, value -> viewModel.write(target, field, value) },
    )
}

@Composable
internal fun ProfileEditorContent(
    target: ProfileTarget,
    state: ProfileEditorState?,
    writeState: ProfileWriteState,
    onBack: () -> Unit,
    onOpenNetworkSettings: (Long) -> Unit,
    onClearResult: () -> Unit,
    onWrite: (ProfileKey, String?) -> Unit,
) {
    val isChannel = target is ProfileTarget.Channel
    val fields = if (isChannel) channelFields else selfFields
    var fieldName by rememberSaveable(target) { mutableStateOf<String?>(null) }
    val field = fields.find { it.name == fieldName }
    var draft by rememberSaveable(target, fieldName) { mutableStateOf<String?>(null) }
    var uploadOpen by rememberSaveable(target, fieldName) { mutableStateOf(false) }
    LaunchedEffect(writeState.result) {
        if (writeState.result == ProfileWriteResult.CONFIRMED) draft = null
    }
    val requestBack = {
        if (field == null) {
            onBack()
        } else if (!writeState.pending) {
            fieldName = null
            onClearResult()
        }
    }
    BackHandler(enabled = field != null, onBack = requestBack)
    val title = field?.label ?: if (isChannel) R.string.profile_channel_title else R.string.profile_user_title
    val valueChanged: (String) -> Unit = {
        draft = it
        onClearResult()
    }

    key(field) {
        SettingsScaffold(
            title = stringResource(title),
            onBack = requestBack,
            modifier = Modifier.testTag(if (isChannel) "screen_channel_profile" else "screen_user_profile"),
        ) {
            if (state == null) {
                Text(stringResource(R.string.profile_editor_loading))
            } else {
                ProfileScope(state, isChannel)
                if (field == null) {
                    SettingsGroup {
                        fields.forEach { key ->
                            SettingsNavigationRow(
                                title = stringResource(key.label),
                                summary = serverValue(state, key),
                                modifier = Modifier.testTag("profile_field_${key.wireName}"),
                                onClick = { fieldName = key.name },
                            )
                        }
                    }
                } else {
                    ProfileFieldEditor(
                        state = state,
                        field = field,
                        isChannel = isChannel,
                        draft = draft,
                        writeState = writeState,
                        onDraftChange = valueChanged,
                        onOpenUpload = { uploadOpen = true },
                        onWrite = { onWrite(field, it) },
                    )
                }
            }
        }
    }
    if (uploadOpen && field == ProfileKey.AVATAR && state != null) {
        val onUrl: (String) -> Unit = {
            valueChanged(it)
            uploadOpen = false
        }
        AttachmentSheets(
            open = true,
            currentDraft = "",
            networkId = state.networkId,
            sojuFileHostUrl = state.sojuFileHostUrl,
            imageOnly = true,
            onDismiss = { uploadOpen = false },
            onInsertUrl = onUrl,
            onReplaceDraft = onUrl,
            onOpenNetworkSettings = onOpenNetworkSettings,
        )
    }
}

@Composable
private fun ProfileScope(
    state: ProfileEditorState,
    isChannel: Boolean,
) {
    if (state.identity.isNotEmpty()) Text(stringResource(R.string.profile_editor_identity, state.identity), style = MaterialTheme.typography.titleMedium)
    state.networkName?.let { Text(stringResource(R.string.profile_editor_network, it), style = MaterialTheme.typography.bodyMedium) }
    Text(
        stringResource(if (isChannel) R.string.profile_editor_channel_scope else R.string.profile_editor_user_scope),
        style = MaterialTheme.typography.bodyMedium,
    )
    val notice =
        when (state.access) {
            ProfileAccess.OFFLINE -> R.string.profile_editor_offline
            ProfileAccess.UNSUPPORTED -> R.string.profile_editor_unsupported
            ProfileAccess.READ_ONLY -> R.string.profile_editor_read_only
            ProfileAccess.WRITABLE -> null
        }
    notice?.let { Text(stringResource(it), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}

@Composable
private fun ProfileFieldEditor(
    state: ProfileEditorState,
    field: ProfileKey,
    isChannel: Boolean,
    draft: String?,
    writeState: ProfileWriteState,
    onDraftChange: (String) -> Unit,
    onOpenUpload: () -> Unit,
    onWrite: (String?) -> Unit,
) {
    val value = draft ?: state.values[field].orEmpty()
    val writable = state.access == ProfileAccess.WRITABLE
    val enabled = writable && !writeState.pending
    val focus = LocalFocusManager.current
    SettingsGroup {
        SettingsValueRow(
            title = stringResource(R.string.profile_editor_current_value),
            value = serverValue(state, field),
            modifier = Modifier.testTag("profile_server_value"),
            compactValue = false,
        )
    }
    if (writable || draft != null) {
        val hint = field.hint
        val color = if (field == ProfileKey.COLOR) parseColorHex(value) else null
        OutlinedTextField(
            value = value,
            onValueChange = onDraftChange,
            enabled = enabled,
            singleLine = true,
            isError = writeState.result == ProfileWriteResult.INVALID,
            colors = readableOutlinedTextFieldColors(),
            label = { Text(stringResource(if (field == ProfileKey.AVATAR) R.string.network_settings_avatar_url else field.label)) },
            supportingText = hint?.let { { Text(stringResource(it)) } },
            trailingIcon = color?.let { { Box(Modifier.size(24.dp).background(Color(it), CircleShape)) } },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = if (field == ProfileKey.AVATAR || field == ProfileKey.HOMEPAGE) KeyboardType.Uri else KeyboardType.Text,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
            modifier = Modifier.fillMaxWidth().testTag("profile_value"),
        )
    }
    if (field == ProfileKey.AVATAR) {
        if (value.isNotBlank()) {
            ExplicitAvatarUrlPreview(value, state.networkId, state.identity, isChannel)
        }
        if (writable && state.networkId != null) {
            OutlinedButton(onClick = onOpenUpload, enabled = enabled) { Text(stringResource(R.string.avatar_upload)) }
            Text(stringResource(R.string.profile_editor_upload_help), style = MaterialTheme.typography.bodySmall)
        }
        if (!isChannel) Text(stringResource(R.string.profile_editor_managed_avatar_help), style = MaterialTheme.typography.bodySmall)
    }
    val result = writeState.result
    val message = if (writeState.pending) R.string.profile_editor_sending else result?.message
    message?.let {
        Text(
            stringResource(it),
            color = if (result == null || result == ProfileWriteResult.CONFIRMED) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (writable) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    focus.clearFocus()
                    onWrite(value)
                },
                enabled = enabled && value.isNotBlank(),
                modifier = Modifier.testTag("profile_publish"),
            ) { Text(stringResource(R.string.profile_editor_publish)) }
            OutlinedButton(
                onClick = {
                    focus.clearFocus()
                    onWrite(null)
                },
                enabled = enabled,
                modifier = Modifier.testTag("profile_remove"),
            ) { Text(stringResource(R.string.profile_editor_remove)) }
        }
        Text(stringResource(R.string.profile_editor_edit_help), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun serverValue(
    state: ProfileEditorState,
    field: ProfileKey,
): String =
    state.values[field] ?: stringResource(
        if (state.access == ProfileAccess.OFFLINE || state.access == ProfileAccess.UNSUPPORTED) {
            R.string.profile_editor_unavailable_value
        } else {
            R.string.profile_editor_not_set
        },
    )

private val ProfileKey.label: Int
    @StringRes get() =
        when (this) {
            ProfileKey.AVATAR -> R.string.profile_editor_avatar
            ProfileKey.BOT -> R.string.profile_editor_bot
            ProfileKey.COLOR -> R.string.profile_editor_color
            ProfileKey.DISPLAY_NAME -> R.string.profile_editor_display_name
            ProfileKey.HOMEPAGE -> R.string.profile_editor_homepage
            ProfileKey.PRONOUNS -> R.string.profile_editor_pronouns
            ProfileKey.STATUS -> R.string.profile_editor_status
        }

private val ProfileKey.hint: Int?
    get() =
        when (this) {
            ProfileKey.AVATAR -> R.string.network_settings_avatar_url_desc
            ProfileKey.BOT -> R.string.profile_editor_bot_help
            ProfileKey.COLOR -> R.string.profile_editor_color_help
            ProfileKey.HOMEPAGE -> R.string.profile_editor_homepage_help
            else -> null
        }

private val ProfileWriteResult.message: Int
    @StringRes get() =
        when (this) {
            ProfileWriteResult.CONFIRMED -> R.string.profile_editor_confirmed
            ProfileWriteResult.UNCONFIRMED -> R.string.profile_editor_unconfirmed
            ProfileWriteResult.REJECTED -> R.string.profile_editor_rejected
            ProfileWriteResult.INVALID -> R.string.profile_editor_invalid
            ProfileWriteResult.READ_ONLY -> R.string.profile_editor_no_access
            ProfileWriteResult.OFFLINE -> R.string.profile_editor_offline
            ProfileWriteResult.UNSUPPORTED -> R.string.profile_editor_unsupported
        }
