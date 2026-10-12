package io.github.trevarj.motd.ui.invite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.invite.JoinInviteV1
import io.github.trevarj.motd.invite.JoinInviteV2
import io.github.trevarj.motd.service.ChannelJoinRejectionKind
import io.github.trevarj.motd.ui.settings.PasswordField

@Composable
fun JoinInviteScreen(
    payload: String,
    accountSetupComplete: Boolean = false,
    onAccountSetupCompleteHandled: () -> Unit = {},
    onBack: () -> Unit,
    onOpenBuffer: (Long) -> Unit,
    onOpenAccountSetup: (Long, String?) -> Unit,
    viewModel: JoinInviteViewModel = hiltViewModel(),
) {
    LaunchedEffect(payload) { viewModel.init(payload) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(accountSetupComplete) {
        if (accountSetupComplete) {
            onAccountSetupCompleteHandled()
            viewModel.retry()
            viewModel.connect()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is JoinInviteEvent.OpenBuffer -> onOpenBuffer(event.bufferId)
                is JoinInviteEvent.OpenAccountSetup -> onOpenAccountSetup(event.networkId, event.channel)
            }
        }
    }
    JoinInviteContent(
        state = state,
        onBack = { viewModel.cancel(onBack) },
        onContinue = viewModel::continueToIdentity,
        onNickChange = viewModel::editNick,
        onConnect = viewModel::connect,
        onRetry = viewModel::retry,
        onChannelKeyChange = viewModel::editChannelKey,
        onSetupAccount = viewModel::setupAccount,
        onConfirmPlaintext = viewModel::confirmPlaintext,
        onDismissPlaintext = viewModel::dismissPlaintextWarning,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinInviteContent(
    state: JoinInviteUiState,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onNickChange: (String) -> Unit,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
    onChannelKeyChange: (String) -> Unit = {},
    onSetupAccount: () -> Unit,
    onConfirmPlaintext: () -> Unit,
    onDismissPlaintext: () -> Unit,
) {
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.invite_join_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.onboarding_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val invite = state.invite
            val channelInvite = invite as? JoinInviteV1
            val contactInvite = invite as? JoinInviteV2
            when (state.phase) {
                JoinInvitePhase.REVIEW -> {
                    if (invite != null) {
                        Text(stringResource(R.string.invite_step, 1, 2), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(if (contactInvite != null) R.string.contact_invite_review_title else R.string.invite_review_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            contactInvite?.contactNick ?: channelInvite?.channel.orEmpty(),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag(if (contactInvite != null) "contact_invite_review_nick" else "invite_review_channel"),
                        )
                        Text(stringResource(R.string.invite_on_network, invite.networkName), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (channelInvite?.channelKey != null) {
                            Text(stringResource(R.string.invite_contains_key), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!invite.tls) {
                            Text(stringResource(R.string.invite_create_plaintext_warning), color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(
                            onClick = { detailsExpanded = !detailsExpanded },
                            modifier = Modifier.testTag("invite_details_toggle"),
                        ) {
                            Text(stringResource(if (detailsExpanded) R.string.invite_hide_details else R.string.invite_show_details))
                            Icon(
                                if (detailsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                            )
                        }
                        if (detailsExpanded) {
                            InviteDetails(invite.networkName, invite.host, invite.port, invite.tls, channelInvite?.channelKey != null)
                        }
                        if (contactInvite != null) {
                            Text(
                                stringResource(R.string.contact_invite_review_explanation, contactInvite.contactNick),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("contact_invite_review_copy"),
                            )
                        }
                        Text(
                            stringResource(R.string.invite_review_warning),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("invite_review_warning"),
                        )
                        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().testTag("invite_review_continue")) {
                            Text(stringResource(R.string.invite_review_continue))
                        }
                    } else {
                        Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    }
                }

                JoinInvitePhase.IDENTITY -> {
                    Text(stringResource(R.string.invite_step, 2, 2), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.invite_identity_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.invite_identity_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = state.nick,
                        onValueChange = onNickChange,
                        label = { Text(stringResource(R.string.onboarding_field_nick)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("invite_nick"),
                    )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().testTag("invite_connect")) {
                        Text(stringResource(R.string.invite_connect))
                    }
                }

                JoinInvitePhase.CONNECTING, JoinInvitePhase.JOINING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text(
                            when {
                                state.phase == JoinInvitePhase.CONNECTING -> {
                                    stringResource(
                                        R.string.invite_connecting,
                                        contactInvite?.networkName ?: channelInvite?.channel.orEmpty(),
                                    )
                                }

                                contactInvite != null -> {
                                    stringResource(R.string.contact_invite_opening, contactInvite.contactNick)
                                }

                                else -> {
                                    stringResource(R.string.invite_joining, channelInvite?.channel.orEmpty())
                                }
                            },
                        )
                    }
                    state.actualNick?.let { Text(stringResource(R.string.invite_connected_as, it)) }
                }

                JoinInvitePhase.READY -> {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.invite_ready), style = MaterialTheme.typography.headlineSmall)
                }

                JoinInvitePhase.FAILED -> {
                    Text(stringResource(R.string.invite_failed), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                    Text(state.error.orEmpty())
                    state.actualNick?.let { nick ->
                        if (state.rejectionKind == ChannelJoinRejectionKind.INVITE_ONLY) {
                            Text(stringResource(R.string.invite_ask_sender, nick))
                        }
                    }
                    if (state.rejectionKind == ChannelJoinRejectionKind.BAD_KEY && channelInvite != null) {
                        PasswordField(
                            value = channelInvite.channelKey.orEmpty(),
                            onValueChange = onChannelKeyChange,
                            label = stringResource(R.string.invite_channel_key_optional),
                            modifier = Modifier.fillMaxWidth().testTag("invite_retry_key"),
                        )
                    }
                    if (state.rejectionKind == ChannelJoinRejectionKind.ACCOUNT_REQUIRED && channelInvite != null) {
                        if (state.accountSetupAvailable) {
                            Button(onClick = onSetupAccount, modifier = Modifier.fillMaxWidth().testTag("invite_setup_account")) {
                                Text(stringResource(R.string.account_setup_title))
                            }
                        } else {
                            Text(stringResource(R.string.invite_account_preprovisioned))
                        }
                    }
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("invite_retry")) {
                        Text(stringResource(R.string.onboarding_connect_retry))
                    }
                }
            }
        }
    }

    if (state.showPlaintextWarning) {
        AlertDialog(
            onDismissRequest = onDismissPlaintext,
            title = { Text(stringResource(R.string.add_network_plaintext_title)) },
            text = { Text(stringResource(R.string.add_network_plaintext_message)) },
            confirmButton = {
                Button(onClick = onConfirmPlaintext) { Text(stringResource(R.string.add_network_plaintext_continue)) }
            },
            dismissButton = { TextButton(onClick = onDismissPlaintext) { Text(stringResource(R.string.dialog_cancel)) } },
        )
    }
}

@Composable
private fun InviteDetails(
    network: String,
    host: String,
    port: Int,
    tls: Boolean,
    hasKey: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(start = 12.dp).testTag("invite_details")) {
        InviteDetailRow(stringResource(R.string.invite_detail_network), network)
        InviteDetailRow(stringResource(R.string.invite_detail_server), host)
        InviteDetailRow(stringResource(R.string.invite_detail_port), port.toString())
        InviteDetailRow(
            stringResource(R.string.invite_detail_security),
            stringResource(if (tls) R.string.invite_detail_tls else R.string.invite_detail_plaintext),
        )
        if (hasKey) InviteDetailRow(stringResource(R.string.invite_detail_access), stringResource(R.string.invite_detail_key_included))
    }
}

@Composable
private fun InviteDetailRow(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}
