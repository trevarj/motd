package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.components.EmojiText as Text

@Composable
internal fun IrcLinkDialog(
    viewModel: IrcLinkViewModel,
    onSetup: (IrcChannelLink) -> Unit,
    onOpen: (Long) -> Unit,
) {
    val review by viewModel.review.collectAsStateWithLifecycle()
    val link = review.link ?: return
    LaunchedEffect(review.joinedId) {
        viewModel.consumeJoined()?.let(onOpen)
    }
    val selected = review.accounts.firstOrNull { it.id == review.selectedId }
    val unmatched = !review.loading && review.accounts.isEmpty()
    val host = if (':' in link.host) "[${link.host}]" else link.host
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(R.string.irc_link_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(link.channel, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.irc_link_endpoint,
                        "$host:${link.port}",
                        stringResource(if (link.tls) R.string.irc_link_tls else R.string.irc_link_plaintext),
                    ),
                )
                if (review.loading && review.error == null) Text(stringResource(R.string.irc_link_checking))
                if (unmatched) Text(stringResource(R.string.irc_link_no_match))
                Column(Modifier.selectableGroup()) {
                    for (account in review.accounts) {
                        val label =
                            if (account.saslUser.isNullOrBlank()) {
                                stringResource(R.string.irc_link_account, account.name, account.nick, account.username)
                            } else {
                                stringResource(R.string.irc_link_account_login, account.name, account.nick, account.username, account.saslUser)
                            }
                        if (review.accounts.size == 1) {
                            Text(label)
                        } else {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = account.id == selected?.id,
                                        enabled = review.working == null,
                                        role = Role.RadioButton,
                                        onClick = { viewModel.selectAccount(account.id) },
                                    ).heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = account.id == selected?.id, onClick = null)
                                Text(label, Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
                when (review.working) {
                    IrcLinkAction.CONNECT_AND_JOIN -> Text(stringResource(R.string.irc_link_connecting))
                    IrcLinkAction.JOIN -> Text(stringResource(R.string.irc_link_joining))
                    else -> Unit
                }
                review.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val action =
                when (selected?.action) {
                    IrcLinkAction.OPEN -> stringResource(R.string.irc_link_open, link.channel)
                    IrcLinkAction.JOIN -> stringResource(R.string.irc_link_join, link.channel)
                    IrcLinkAction.CONNECT_AND_JOIN -> stringResource(R.string.irc_link_connect)
                    null -> if (unmatched) stringResource(R.string.irc_link_setup) else null
                }
            if (action != null) {
                TextButton(
                    enabled = review.working == null && review.joinedId == null,
                    onClick = {
                        if (unmatched) {
                            viewModel.dismiss()
                            onSetup(link)
                        } else {
                            viewModel.confirm()
                        }
                    },
                ) { Text(action) }
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismiss) {
                Text(stringResource(if (review.working == null) R.string.action_cancel else R.string.network_activity_close))
            }
        },
    )
}
