package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.LocalEmojiRenderer

/** Keep Material's host animations, timeout and accessibility; replace only its internal text. */
@Composable
fun EmojiSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    snackbar: @Composable (SnackbarData) -> Unit = { EmojiSnackbar(it) },
) {
    SnackbarHost(hostState = hostState, modifier = modifier, snackbar = snackbar)
}

@Composable
fun EmojiSnackbar(
    snackbarData: SnackbarData,
    modifier: Modifier = Modifier,
    actionOnNewLine: Boolean = false,
    shape: Shape = SnackbarDefaults.shape,
    containerColor: Color = SnackbarDefaults.color,
    contentColor: Color = SnackbarDefaults.contentColor,
    actionColor: Color = SnackbarDefaults.actionColor,
    actionContentColor: Color = SnackbarDefaults.actionContentColor,
    dismissActionContentColor: Color = SnackbarDefaults.dismissActionContentColor,
) {
    if (LocalEmojiRenderer.current == null) {
        Snackbar(
            snackbarData = snackbarData,
            modifier = modifier,
            actionOnNewLine = actionOnNewLine,
            shape = shape,
            containerColor = containerColor,
            contentColor = contentColor,
            actionColor = actionColor,
            actionContentColor = actionContentColor,
            dismissActionContentColor = dismissActionContentColor,
        )
        return
    }
    val actionLabel = snackbarData.visuals.actionLabel
    val action: (@Composable () -> Unit)? =
        if (actionLabel == null) {
            null
        } else {
            @Composable {
                TextButton(
                    onClick = { snackbarData.performAction() },
                    colors = ButtonDefaults.textButtonColors(contentColor = actionColor),
                ) { EmojiText(actionLabel) }
            }
        }
    val dismissAction: (@Composable () -> Unit)? =
        if (!snackbarData.visuals.withDismissAction) {
            null
        } else {
            @Composable {
                IconButton(onClick = { snackbarData.dismiss() }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_dismiss))
                }
            }
        }
    Snackbar(
        modifier = modifier.padding(12.dp),
        action = action,
        dismissAction = dismissAction,
        actionOnNewLine = actionOnNewLine,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        actionContentColor = actionContentColor,
        dismissActionContentColor = dismissActionContentColor,
    ) { EmojiText(snackbarData.visuals.message) }
}
