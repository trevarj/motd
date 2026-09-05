package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import io.github.trevarj.motd.R
import io.github.trevarj.motd.service.ChannelWatchDuration

/** Watch-duration picker shared by channel info and the chat overflow; [tagPrefix] scopes the tags. */
@Composable
fun ChannelWatchDialog(
    watchActive: Boolean,
    onStart: (ChannelWatchDuration) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    tagPrefix: String,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // A dialog window does not inherit the root's tag mapping, so opt it in here.
        modifier =
            Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("${tagPrefix}_notify_dialog"),
        title = { Text(stringResource(R.string.channelinfo_notifications)) },
        text = {
            Column {
                ChannelWatchDuration.entries.forEach { duration ->
                    ListItem(
                        headlineContent = { Text(duration.label()) },
                        leadingContent = { Icon(duration.icon, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier =
                            Modifier
                                .testTag("${tagPrefix}_watch_${duration.tag}")
                                .clickable {
                                    onStart(duration)
                                    onDismiss()
                                },
                    )
                }
                if (watchActive) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.chat_watch_stop)) },
                        leadingContent = { Icon(Icons.Outlined.NotificationsOff, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier =
                            Modifier
                                .testTag("${tagPrefix}_watch_stop")
                                .clickable {
                                    onStop()
                                    onDismiss()
                                },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
