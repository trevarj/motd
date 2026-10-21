package io.github.trevarj.motd.gesture.radial

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.gesture.GestureNode
import io.github.trevarj.motd.gesture.GestureProviderKind
import io.github.trevarj.motd.ui.theme.SheetSystemBars

private fun inspectionEntry(node: GestureNode): RadialEntry =
    radialEntryOf(
        node = node,
        children =
            when (node) {
                is GestureNode.Submenu -> {
                    node.children.map(::inspectionEntry)
                }

                is GestureNode.Provider -> {
                    if (node.kind == GestureProviderKind.UNKNOWN) {
                        emptyList()
                    } else {
                        List(node.clampedLimit) { index ->
                            RadialEntry("${node.id}-example-$index", "Example ${index + 1}", node.icon)
                        }
                    }
                }

                else -> {
                    emptyList()
                }
            },
        away = false,
        backLabel = node.label,
    )

/** The production ring and geometry, with only local inspection/navigation and fictional providers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GestureMenuPreview(
    root: GestureNode.Submenu,
    onDismiss: () -> Unit,
) {
    val entry = remember(root) { inspectionEntry(root) }
    val metrics = rememberRadialMetrics()
    val more = stringResource(R.string.action_more)
    val density = LocalDensity.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("gesture_menu_preview")) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.settings_gesture_preview_scope))
            if (entry.children.isEmpty()) Text(stringResource(R.string.settings_gesture_preview_empty))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // The drawing grows with the viewport and native label height; never shrink sample text.
                val height =
                    maxWidth + with(density) {
                        MaterialTheme.typography.labelMedium.lineHeight
                            .toDp()
                    } * 4
                val size = Size(constraints.maxWidth.toFloat(), with(density) { height.toPx() })
                val center = Offset(metrics.deadzoneRadius, size.height / 2)
                var menu by remember(entry, size, metrics, more) { mutableStateOf(openRadialMenu(entry, center, size, metrics, more)) }
                Column {
                    Box(Modifier.fillMaxWidth().height(height)) {
                        RadialMenu(menu, metrics, MaterialTheme.colorScheme.primary)
                    }
                    if (menu.rings.size > 1) TextButton(onClick = { menu = menu.copy(rings = menu.rings.dropLast(1)) }) { Text(stringResource(R.string.onboarding_back)) }
                    menu.focusedEntry?.let { Text(stringResource(R.string.settings_gesture_preview_inspecting, it.label)) }
                    menu.active.entries.forEachIndexed { index, child ->
                        TextButton(onClick = {
                            val radius = if (child.children.isNotEmpty()) metrics.descendRadius + 1f else (metrics.bandInnerRadius + metrics.bandOuterRadius) / 2
                            menu = onRadialPointer(menu, sliceAnchor(menu.active.center, menu.active.arc, index, radius), size, metrics, more).state
                        }, modifier = Modifier.fillMaxWidth().testTag("gesture_preview_inspect_${child.id}")) { Text(child.label.ifBlank { stringResource(R.string.gesture_editor_unknown) }) }
                    }
                }
            }
        }
    }
}
