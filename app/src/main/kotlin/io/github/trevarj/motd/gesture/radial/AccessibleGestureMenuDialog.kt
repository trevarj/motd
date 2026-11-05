package io.github.trevarj.motd.gesture.radial

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.gesture.GestureAction
import io.github.trevarj.motd.gesture.vector
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

/** Test tag for the list form of the gesture menu. */
const val GESTURE_MENU_A11Y_DIALOG_TAG = "gesture_menu_a11y_dialog"

/**
 * The gesture menu as a nested list, for when touch exploration is on.
 *
 * It walks exactly the tree the ring would have shown — same resolved providers, same labels, same
 * actions — so the accessible path is the same menu rather than a reduced stand-in. Descending is a
 * tap instead of a drag and backing out is a button instead of a return to the centre, which is the
 * only part of the interaction a screen reader cannot express.
 */
@Composable
internal fun AccessibleGestureMenuDialog(
    root: RadialEntry,
    onExecute: (GestureAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember(root) { mutableStateOf(listOf(root)) }
    val current = path.last()
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        titleContentColor = MaterialTheme.colorScheme.primary,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(GESTURE_MENU_A11Y_DIALOG_TAG),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (path.size > 1) {
                    IconButton(onClick = { path = path.dropLast(1) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.gesture_menu_a11y_up),
                        )
                    }
                }
                Text(current.label.ifBlank { stringResource(R.string.gesture_menu_a11y_title) })
            }
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(current.children, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.label) },
                        leadingContent = { Icon(entry.icon.vector, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            if (entry.children.isNotEmpty()) {
                                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier =
                            Modifier
                                .clickable {
                                    val action = entry.action
                                    when {
                                        entry.children.isNotEmpty() -> {
                                            path = path + entry
                                        }

                                        action != null -> {
                                            onExecute(action)
                                            onDismiss()
                                        }

                                        // An empty provider ring: nothing to open and nothing to run.
                                        else -> {}
                                    }
                                }.testTag(gestureMenuSliceTag(entry.id)),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.gesture_menu_a11y_close)) }
        },
    )
}
