package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.service.ChannelWatchDuration
import io.github.trevarj.motd.service.ChannelWatchState
import io.github.trevarj.motd.service.NotificationMode
import io.github.trevarj.motd.service.NotificationScope
import io.github.trevarj.motd.service.NotificationSettingsState
import io.github.trevarj.motd.service.isForever
import io.github.trevarj.motd.ui.settings.RadioRow
import io.github.trevarj.motd.ui.settings.SectionHeader
import io.github.trevarj.motd.ui.settings.SettingsActionRow
import io.github.trevarj.motd.ui.theme.SheetSystemBars

/** Policy and the independent watch for one canonical channel; defaults are an uneditable loading state. */
data class ChannelNotificationPresentation(
    val mode: NotificationMode = NotificationMode.OFF,
    val source: NotificationScope = NotificationScope.GLOBAL,
    val channelOverride: NotificationMode? = null,
    val parentMode: NotificationMode = NotificationMode.OFF,
    val watch: ChannelWatchState? = null,
    val minutesLeft: Int? = null,
    val muted: Boolean = false,
    val loading: Boolean = true,
    val available: Boolean = false,
)

fun deriveChannelNotificationPresentation(
    settingsState: NotificationSettingsState,
    networkId: Long,
    bufferId: Long,
    muted: Boolean,
    nowMillis: Long,
): ChannelNotificationPresentation {
    val config = (settingsState as? NotificationSettingsState.Ready)?.config
    val channelOverride = config?.channels?.get(bufferId)
    val serverOverride = config?.servers?.get(networkId)
    val parentMode = serverOverride ?: config?.global ?: NotificationMode.OFF
    val watch =
        config
            ?.watches
            ?.get(bufferId)
            ?.takeIf { it == Long.MAX_VALUE || it > nowMillis }
            ?.let { ChannelWatchState(bufferId, it) }
    return ChannelNotificationPresentation(
        mode = channelOverride ?: parentMode,
        source =
            when {
                channelOverride != null -> NotificationScope.CHANNEL
                serverOverride != null -> NotificationScope.SERVER
                else -> NotificationScope.GLOBAL
            },
        channelOverride = channelOverride,
        parentMode = parentMode,
        watch = watch,
        minutesLeft =
            watch?.takeUnless { it.isForever }?.let {
                ((it.expiresAt - nowMillis - 1) / 60_000 + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            },
        muted = muted,
        loading = settingsState == NotificationSettingsState.Loading,
        available = config != null,
    )
}

@Composable
fun NotificationMode.label(channel: Boolean = false): String =
    stringResource(
        when (this) {
            NotificationMode.ALL -> R.string.notification_mode_all
            NotificationMode.MENTIONS -> if (channel) R.string.channelinfo_notify_mentions else R.string.notification_mode_mentions
            NotificationMode.OFF -> R.string.notification_mode_off
        },
    )

@Composable
fun NotificationScope.label(): String =
    stringResource(
        when (this) {
            NotificationScope.GLOBAL -> R.string.notification_source_global
            NotificationScope.SERVER -> R.string.notification_source_server
            NotificationScope.CHANNEL -> R.string.notification_source_channel
        },
    )

@Composable
fun ChannelNotificationPresentation.summary(): String {
    if (loading) return stringResource(R.string.notification_settings_loading)
    if (!available) return stringResource(R.string.notification_settings_unavailable_short)
    val policy = stringResource(R.string.notification_policy_summary, mode.label(channel = true), source.label())
    val watchSummary =
        when {
            watch == null -> null
            watch.isForever -> stringResource(R.string.notification_watch_forever)
            else -> stringResource(R.string.notification_watch_remaining, checkNotNull(minutesLeft))
        }
    val muteSummary = if (muted) stringResource(R.string.notification_chat_muted) else null
    return listOfNotNull(policy, watchSummary, muteSummary).joinToString("\n")
}

/** Shared by the overview and channel quick actions; selections never change the presentation locally. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelNotificationSheet(
    presentation: ChannelNotificationPresentation,
    onMode: (NotificationMode?) -> Unit,
    onStart: (ChannelWatchDuration) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    tagPrefix: String,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier =
            Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("${tagPrefix}_notify_dialog"),
    ) {
        SheetSystemBars()
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(stringResource(R.string.channelinfo_notifications))
            if (presentation.loading) {
                CircularProgressIndicator(Modifier.testTag("${tagPrefix}_notify_loading"))
                Text(stringResource(R.string.notification_settings_loading))
            } else {
                if (presentation.available) {
                    Text(presentation.summary())
                } else {
                    Text(stringResource(R.string.notification_channel_unavailable))
                }
                SectionHeader(stringResource(R.string.notification_channel_policy))
                Column(Modifier.selectableGroup()) {
                    RadioRow(
                        label =
                            if (presentation.available) {
                                stringResource(R.string.notification_use_server, presentation.parentMode.label(channel = true))
                            } else {
                                stringResource(R.string.notification_use_server_unavailable)
                            },
                        selected = presentation.available && presentation.channelOverride == null,
                        enabled = presentation.available,
                        onClick = {
                            onMode(null)
                            onDismiss()
                        },
                        modifier = Modifier.testTag("${tagPrefix}_mode_inherit"),
                    )
                    NotificationMode.entries.forEach { mode ->
                        RadioRow(
                            label = mode.label(channel = true),
                            selected = presentation.available && presentation.channelOverride == mode,
                            enabled = presentation.available,
                            onClick = {
                                onMode(mode)
                                onDismiss()
                            },
                            modifier = Modifier.testTag("${tagPrefix}_mode_${mode.name.lowercase()}"),
                        )
                    }
                }
                SectionHeader(stringResource(R.string.notification_watch_override))
                Text(stringResource(R.string.notification_watch_explanation))
                ChannelWatchDuration.entries.forEach { duration ->
                    SettingsActionRow(
                        title = duration.label(),
                        enabled = presentation.available,
                        modifier = Modifier.testTag("${tagPrefix}_watch_${duration.tag}"),
                        onClick = {
                            onStart(duration)
                            onDismiss()
                        },
                    )
                }
                if (presentation.watch != null) {
                    SettingsActionRow(
                        title = stringResource(R.string.chat_watch_stop),
                        enabled = presentation.available,
                        modifier = Modifier.testTag("${tagPrefix}_watch_stop"),
                        onClick = {
                            onStop()
                            onDismiss()
                        },
                    )
                }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}
