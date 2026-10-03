package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.DccDirection
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.DccTransferProtocol
import io.github.trevarj.motd.data.db.DccTransferState
import io.github.trevarj.motd.dcc.DccTransferController
import io.github.trevarj.motd.ui.components.DccDownloadMenu
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Composable
fun DirectConnectionsScreen(
    onBack: () -> Unit = {},
    viewModel: DirectConnectionsViewModel = hiltViewModel(),
) {
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    DirectConnectionsContent(
        transfers = transfers,
        onBack = onBack,
        onRemove = viewModel::remove,
        onSaveToDownloads = viewModel::saveToDownloads,
    )
}

@Composable
fun DirectConnectionsContent(
    transfers: List<DccTransferEntity>,
    onBack: () -> Unit,
    onRemove: (Long) -> Unit,
    onSaveToDownloads: suspend (Long) -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.settings_direct_connections), onBack = onBack) {
        Column(Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_direct_connections_disclosure)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_direct_connections_disclosure_desc))
                },
                modifier = Modifier.testTag("settings_direct_connections_disclosure"),
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_direct_connections_mode)) },
                supportingContent = { Text(stringResource(R.string.settings_direct_connections_mode_desc)) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                text = stringResource(R.string.settings_direct_connections_recent),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            if (transfers.isEmpty()) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_direct_connections_empty)) },
                    supportingContent = { Text(stringResource(R.string.settings_direct_connections_empty_desc)) },
                )
            } else {
                transfers.forEach { transfer ->
                    DirectConnectionTransferRow(transfer, onRemove, onSaveToDownloads)
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun DirectConnectionTransferRow(
    transfer: DccTransferEntity,
    onRemove: (Long) -> Unit,
    onSaveToDownloads: suspend (Long) -> Unit,
) {
    val direction =
        if (transfer.direction == DccDirection.INCOMING) {
            stringResource(R.string.dcc_direction_incoming)
        } else {
            stringResource(R.string.dcc_direction_outgoing)
        }
    val protocol =
        stringResource(
            if (transfer.protocol == DccTransferProtocol.SEND) R.string.dcc_protocol_send else R.string.dcc_protocol_ssend,
        )
    val state =
        stringResource(
            when (transfer.state) {
                DccTransferState.OFFERED -> R.string.dcc_state_offered
                DccTransferState.ACCEPTING -> R.string.dcc_state_accepting
                DccTransferState.ACTIVE -> R.string.dcc_state_active
                DccTransferState.PARTIAL -> R.string.dcc_state_partial
                DccTransferState.COMPLETED -> R.string.dcc_state_completed
                DccTransferState.FAILED -> R.string.dcc_state_failed
                DccTransferState.REJECTED -> R.string.dcc_state_rejected
                DccTransferState.EXPIRED -> R.string.dcc_state_expired
                DccTransferState.REMOVED -> R.string.dcc_state_removed
            },
        )
    ListItem(
        headlineContent = { Text(transfer.displayFilename) },
        supportingContent = {
            Text(stringResource(R.string.settings_direct_transfer_summary, direction, protocol, state))
        },
        trailingContent = {
            Row {
                if (transfer.state in TERMINAL_TRANSFER_STATES) {
                    TextButton(onClick = { onRemove(transfer.id) }) {
                        Text(stringResource(R.string.dcc_remove_record))
                    }
                }
                DccDownloadMenu(transfer, onSaveToDownloads)
            }
        },
        modifier = Modifier.testTag("settings_direct_transfer_${transfer.id}"),
    )
}

@HiltViewModel
class DirectConnectionsViewModel
    @Inject
    constructor(
        private val controller: DccTransferController,
    ) : ViewModel() {
        val transfers: StateFlow<List<DccTransferEntity>> =
            controller
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun remove(transferId: Long) {
            viewModelScope.launch { controller.removeRecord(transferId) }
        }

        suspend fun saveToDownloads(transferId: Long) {
            controller.saveToDownloads(transferId)
        }
    }

private val TERMINAL_TRANSFER_STATES =
    setOf(
        DccTransferState.COMPLETED,
        DccTransferState.FAILED,
        DccTransferState.REJECTED,
        DccTransferState.EXPIRED,
        DccTransferState.REMOVED,
    )
