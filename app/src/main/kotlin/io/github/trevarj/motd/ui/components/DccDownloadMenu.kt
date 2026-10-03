package io.github.trevarj.motd.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.dcc.canSaveToDownloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DccDownloadMenu(
    transfer: DccTransferEntity,
    onSaveToDownloads: (suspend (Long) -> Unit)?,
) {
    if (onSaveToDownloads == null || !transfer.canSaveToDownloads()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val save by rememberUpdatedState(onSaveToDownloads)
    var expanded by remember(transfer.id) { mutableStateOf(false) }
    var saving by remember(transfer.id) { mutableStateOf(false) }

    fun saveFile() {
        if (saving) return
        saving = true
        scope.launch {
            try {
                save(transfer.id)
                Toast.makeText(context, R.string.dcc_saved_to_downloads, Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, R.string.dcc_save_to_downloads_failed, Toast.LENGTH_LONG).show()
            } finally {
                saving = false
            }
        }
    }

    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                saveFile()
            } else {
                Toast.makeText(context, R.string.dcc_downloads_permission_required, Toast.LENGTH_LONG).show()
            }
        }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = !saving,
            modifier = Modifier.testTag("dcc_download_menu_${transfer.id}"),
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.dcc_file_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.dcc_save_to_downloads)) },
                modifier = Modifier.testTag("dcc_save_to_downloads_${transfer.id}"),
                onClick = {
                    expanded = false
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        saveFile()
                    }
                },
            )
        }
    }
}
