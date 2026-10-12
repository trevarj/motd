package io.github.trevarj.motd.ui.invite

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.invite.brandedInviteQrBitmap
import io.github.trevarj.motd.ui.settings.PasswordField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CreateInviteScreen(
    bufferId: Long,
    onBack: () -> Unit,
    viewModel: CreateInviteViewModel = hiltViewModel(),
) {
    LaunchedEffect(bufferId) { viewModel.init(bufferId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CreateInviteContent(
        state = state,
        onBack = onBack,
        onKeyChange = viewModel::editChannelKey,
        onConfirmKey = viewModel::confirmChannelKey,
        onRemoveKey = viewModel::removeChannelKey,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateInviteContent(
    state: CreateInviteUiState,
    onBack: () -> Unit,
    onKeyChange: (String) -> Unit,
    onConfirmKey: () -> Unit,
    onRemoveKey: () -> Unit,
) {
    val context = LocalContext.current
    val qrDescription = state.invite?.let { stringResource(R.string.invite_qr_description, it.channel) }.orEmpty()
    val shareBody = state.qrText?.let { stringResource(R.string.invite_share_body, it) }
    val shareTitle = stringResource(R.string.invite_share)
    val copyLabel = stringResource(R.string.invite_copy_uri)
    val qrAccent = MaterialTheme.colorScheme.primary.toArgb()
    val qrOnAccent = MaterialTheme.colorScheme.onPrimary.toArgb()
    var showKeyWarning by remember { mutableStateOf(false) }
    var showChannelKey by rememberSaveable { mutableStateOf(false) }
    val qr by
        produceState<android.graphics.Bitmap?>(null, state.qrText, state.invite?.channel, qrAccent, qrOnAccent) {
            val text = state.qrText
            val channel = state.invite?.channel
            value =
                if (text != null && channel != null) {
                    withContext(Dispatchers.Default) { brandedInviteQrBitmap(context, text, channel, accent = qrAccent, onAccent = qrOnAccent) }
                } else {
                    null
                }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.invite_create_title)) },
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
                    .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                state.loading -> {
                    CircularProgressIndicator()
                }

                state.error != null && state.invite == null -> {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                }

                else -> {
                    val invite = state.invite ?: return@Column
                    Text(invite.channel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.invite_on_network, invite.networkName), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!invite.tls) {
                        Text(stringResource(R.string.invite_create_plaintext_warning), color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.invite_create_guest_warning), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (showChannelKey || state.channelKey.isNotBlank()) {
                        PasswordField(
                            value = state.channelKey,
                            onValueChange = onKeyChange,
                            label = stringResource(R.string.invite_channel_key_optional),
                            modifier = Modifier.fillMaxWidth().testTag("invite_create_key"),
                        )
                    } else {
                        TextButton(
                            onClick = { showChannelKey = true },
                            modifier = Modifier.align(Alignment.Start).testTag("invite_add_key"),
                        ) {
                            Text(stringResource(R.string.invite_add_channel_key))
                        }
                    }
                    if (state.channelKey.isNotBlank() && !state.includeKeyConfirmed) {
                        Button(onClick = { showKeyWarning = true }, modifier = Modifier.testTag("invite_confirm_key")) {
                            Text(stringResource(R.string.invite_include_key))
                        }
                    }
                    qr?.let { bitmap ->
                        ChannelInviteQr(
                            bitmap = bitmap,
                            description = qrDescription,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (state.qrText != null) {
                        Text(
                            stringResource(R.string.invite_how_it_works),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        NumberedInstruction(1, stringResource(R.string.invite_step_scan))
                        NumberedInstruction(2, stringResource(R.string.invite_step_rescan))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareBody),
                                            shareTitle,
                                        ),
                                    )
                                },
                                modifier = Modifier.testTag("invite_share"),
                            ) { Text(stringResource(R.string.invite_share)) }
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(copyLabel, state.qrText))
                                },
                                modifier = Modifier.testTag("invite_copy"),
                            ) { Text(stringResource(R.string.invite_copy_uri)) }
                        }
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text(stringResource(R.string.invite_policy_warning), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showKeyWarning) {
        AlertDialog(
            onDismissRequest = { showKeyWarning = false },
            title = { Text(stringResource(R.string.invite_key_warning_title)) },
            text = { Text(stringResource(R.string.invite_key_warning_message)) },
            confirmButton = {
                Button(onClick = {
                    showKeyWarning = false
                    onConfirmKey()
                }) { Text(stringResource(R.string.invite_include_key)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showKeyWarning = false
                    showChannelKey = false
                    onRemoveKey()
                }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}

@Composable
internal fun ContactInviteQr(
    bitmap: android.graphics.Bitmap,
    description: String,
    modifier: Modifier = Modifier,
) {
    InviteQrCard(bitmap, description, "contact_invite_qr", modifier)
}

@Composable
private fun ChannelInviteQr(
    bitmap: android.graphics.Bitmap,
    description: String,
    modifier: Modifier = Modifier,
) {
    InviteQrCard(bitmap, description, "invite_qr", modifier)
}

@Composable
private fun InviteQrCard(
    bitmap: android.graphics.Bitmap,
    description: String,
    qrTag: String,
    modifier: Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 4.dp,
        modifier = modifier.sizeIn(maxWidth = 420.dp),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = description,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                    .padding(12.dp)
                    .testTag(qrTag),
        )
    }
}

@Composable
internal fun NumberedInstruction(
    number: Int,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("$number.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(text, modifier = Modifier.weight(1f))
    }
}
