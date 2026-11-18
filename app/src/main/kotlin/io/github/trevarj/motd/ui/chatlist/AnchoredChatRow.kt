package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.UiIcon
import kotlin.math.abs

internal fun moveAnchoredChat(
    ids: List<Long>,
    id: Long,
    delta: Int,
): List<Long> {
    val from = ids.indexOf(id)
    if (from < 0 || ids.isEmpty()) return ids
    val to = (from.toLong() + delta).coerceIn(0L, ids.lastIndex.toLong()).toInt()
    if (from == to) return ids
    return ids.toMutableList().apply { add(to, removeAt(from)) }
}

/** The handle alone owns vertical dragging; the chat keeps its normal tap, hold and swipe. */
@Composable
internal fun AnchoredChatRow(
    bufferId: Long,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    enabled: Boolean,
    onMove: (Int) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var heightPx by remember { mutableIntStateOf(1) }
    val move by rememberUpdatedState(onMove)
    val commit by rememberUpdatedState(onCommit)
    val up by rememberUpdatedState(canMoveUp)
    val down by rememberUpdatedState(canMoveDown)
    val label = stringResource(R.string.reorder_anchor)
    val upLabel = stringResource(R.string.move_anchor_up)
    val downLabel = stringResource(R.string.move_anchor_down)
    Row(modifier.onSizeChanged { heightPx = it.height.coerceAtLeast(1) }.testTag("chatlist_anchored_$bufferId"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { content() }
        Box(
            Modifier
                .size(48.dp)
                .testTag("chatlist_anchor_handle_$bufferId")
                .semantics {
                    contentDescription = label
                    customActions =
                        if (!enabled) {
                            emptyList()
                        } else {
                            buildList {
                                if (canMoveUp) {
                                    add(
                                        CustomAccessibilityAction(upLabel) {
                                            move(-1)
                                            commit()
                                            true
                                        },
                                    )
                                }
                                if (canMoveDown) {
                                    add(
                                        CustomAccessibilityAction(downLabel) {
                                            move(1)
                                            commit()
                                            true
                                        },
                                    )
                                }
                            }
                        }
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    var distance = 0f
                    var moved = false

                    fun finish() {
                        if (moved) commit()
                        distance = 0f
                        moved = false
                    }
                    detectVerticalDragGestures(
                        onDragStart = {
                            distance = 0f
                            moved = false
                        },
                        onDragEnd = { finish() },
                        onDragCancel = { finish() },
                    ) { change, amount ->
                        change.consume()
                        distance += amount
                        while (abs(distance) >= heightPx) {
                            val direction = if (distance > 0) 1 else -1
                            if ((direction < 0 && !up) || (direction > 0 && !down)) {
                                distance = 0f
                                break
                            }
                            move(direction)
                            moved = true
                            distance -= direction * heightPx
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { UiIcon(Icons.Filled.DragHandle, contentDescription = null) }
    }
}
