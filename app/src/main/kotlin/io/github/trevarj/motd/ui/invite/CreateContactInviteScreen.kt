package io.github.trevarj.motd.ui.invite

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.invite.brandedInviteQrBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CreateContactInviteScreen(
    preferredNetworkId: Long?,
    onBack: () -> Unit,
    viewModel: CreateContactInviteViewModel = hiltViewModel(),
) {
    LaunchedEffect(preferredNetworkId) { viewModel.init(preferredNetworkId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CreateContactInviteContent(
        state = state,
        onBack = onBack,
        onSelectNetwork = viewModel::selectNetwork,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateContactInviteContent(
    state: CreateContactInviteUiState,
    onBack: () -> Unit,
    onSelectNetwork: (Long) -> Unit,
) {
    val context = LocalContext.current
    val invite = state.invite
    val shareTitle = stringResource(R.string.contact_invite_share_qr)
    val copyLabel = stringResource(R.string.invite_copy_uri)
    val qrAccent = MaterialTheme.colorScheme.primary.toArgb()
    val qrOnAccent = MaterialTheme.colorScheme.onPrimary.toArgb()
    val qrDescription =
        invite?.let { stringResource(R.string.contact_invite_qr_description, it.contactNick, it.networkName) }.orEmpty()
    val qr by
        produceState<Bitmap?>(null, state.qrText, invite?.contactNick, qrAccent, qrOnAccent) {
            val text = state.qrText
            val nick = invite?.contactNick
            value =
                if (text != null && nick != null) {
                    withContext(Dispatchers.Default) {
                        brandedInviteQrBitmap(context, text, nick, accent = qrAccent, onAccent = qrOnAccent)
                    }
                } else {
                    null
                }
        }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contact_invite_create_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.onboarding_back))
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val qrSize = minOf(maxWidth - 32.dp, maxHeight * 0.46f)
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    state.loading -> {
                        CircularProgressIndicator()
                    }

                    state.networks.isEmpty() -> {
                        Text(
                            stringResource(R.string.contact_invite_no_connected_networks),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("contact_invite_no_networks"),
                        )
                    }

                    else -> {
                        ContactInviteNetworkSelector(
                            networks = state.networks,
                            selectedNetworkId = state.selectedNetworkId,
                            onSelectNetwork = onSelectNetwork,
                        )
                        val selected = state.networks.firstOrNull { it.id == state.selectedNetworkId }
                        selected?.let {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    it.nick,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("contact_invite_nick"),
                                )
                                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    it.name,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("contact_invite_network"),
                                )
                            }
                        }
                        if (invite?.tls == false) {
                            Text(stringResource(R.string.invite_create_plaintext_warning), color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            stringResource(R.string.contact_invite_create_explanation),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                        if (invite == null && state.error == null) {
                            CircularProgressIndicator(modifier = Modifier.testTag("contact_invite_qr_loading"))
                        }
                        qr?.let { bitmap ->
                            ContactInviteQr(
                                bitmap = bitmap,
                                description = qrDescription,
                                modifier = Modifier.width(qrSize),
                            )
                        }
                        if (state.qrText != null) {
                            Text(
                                stringResource(R.string.contact_invite_scan_compact),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().testTag("contact_invite_scan_help"),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { qr?.let { bitmap -> scope.launch { shareInviteQr(context, bitmap, shareTitle) } } },
                                    modifier = Modifier.testTag("contact_invite_share"),
                                ) { Text(stringResource(R.string.contact_invite_share_qr)) }
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(copyLabel, state.qrText))
                                    },
                                    modifier = Modifier.testTag("contact_invite_copy"),
                                ) { Text(stringResource(R.string.invite_copy_uri)) }
                            }
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

private suspend fun shareInviteQr(
    context: Context,
    bitmap: Bitmap,
    title: String,
) {
    val uri =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "invite-qr").apply(File::mkdirs)
            val file = File(directory, "motd-contact-invite.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
        }
    val share =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(share, title))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactInviteNetworkSelector(
    networks: List<ContactInviteNetwork>,
    selectedNetworkId: Long?,
    onSelectNetwork: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = networks.firstOrNull { it.id == selectedNetworkId } ?: networks.first()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected.name,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.contact_invite_network_label)) },
            leadingIcon = { Icon(Icons.Outlined.Public, contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .semantics {
                        role = Role.Button
                        stateDescription = selected.name
                    }.testTag("contact_invite_network_selector"),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            networks.forEach { network ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.contact_invite_network_option, network.name, network.nick)) },
                    trailingIcon = {
                        if (network.id == selected.id) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = {
                        onSelectNetwork(network.id)
                        expanded = false
                    },
                    modifier = Modifier.testTag("contact_invite_network_option_${network.id}"),
                )
            }
        }
    }
}
