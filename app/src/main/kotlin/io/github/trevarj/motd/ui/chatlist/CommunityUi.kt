package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R

@Composable
internal fun CommunityInvitation(onDisplayed: () -> Unit, onJoin: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("community_invitation").onGloballyPositioned {
        val bounds = it.boundsInWindow()
        if (bounds.width > 0 && bounds.height > 0) onDisplayed()
    }) {
        Text(stringResource(R.string.community_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.community_channel), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onJoin, modifier = Modifier.testTag("community_invitation_join")) { Text(stringResource(R.string.community_join)) }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("community_invitation_dismiss")) { Text(stringResource(R.string.community_dismiss)) }
        }
    }
}

@Composable
internal fun CommunityPicker(state: CommunityState, onChoose: (Long) -> Unit, onSettings: (Long) -> Unit, onSetup: () -> Unit, onCancel: () -> Unit) {
    if (!state.picker) return
    AlertDialog(
        modifier = Modifier.testTag("community_network_picker"),
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.community_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.community_choose))
                if (state.joining) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.community_joining))
                }
                state.error?.let { error ->
                    Text(
                        stringResource(when (error) {
                            CommunityError.CONNECTION -> R.string.community_connection_error
                            CommunityError.TIMEOUT -> R.string.community_timeout
                            CommunityError.REJECTED -> R.string.community_rejected
                            CommunityError.FAILED -> R.string.community_failed
                        }),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("community_join_error"),
                    )
                    state.rejectionReason?.let { Text(it) }
                }
                state.routes.forEach { route ->
                    TextButton(
                        enabled = !state.joining,
                        modifier = Modifier.fillMaxWidth().testTag("community_network_${route.network.id}"),
                        onClick = { if (route.ready || route.joinedBufferId != null) onChoose(route.network.id) else onSettings(route.network.id) },
                    ) {
                        Column {
                            Text(route.network.name)
                            Text(stringResource(when {
                                !route.ready -> R.string.community_connect
                                route.identified -> R.string.community_channel
                                else -> R.string.community_confirm_unknown
                            }))
                        }
                    }
                }
                TextButton(onClick = onSetup, enabled = !state.joining, modifier = Modifier.testTag("community_setup")) {
                    Text(stringResource(R.string.community_setup))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag("community_cancel")) { Text(stringResource(R.string.community_cancel)) } },
    )
}
