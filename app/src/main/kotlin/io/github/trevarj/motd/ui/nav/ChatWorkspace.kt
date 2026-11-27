package io.github.trevarj.motd.ui.nav

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.components.EmptyState

internal const val CHAT_WORKSPACE_BREAKPOINT_DP = 720f
internal const val CHAT_WORKSPACE_RESIZABLE_DP = 840f
internal const val CHAT_LIST_MIN_DP = 280f
internal const val CHAT_LIST_INITIAL_DP = 320f
internal const val CHAT_LIST_MAX_DP = 420f
internal const val CHAT_DETAIL_MIN_DP = 420f
internal const val CHAT_PANE_DIVIDER_DP = 12f

internal enum class ChatWorkspaceMode { SINGLE, DUAL_FIXED, DUAL_RESIZABLE }

internal data class ChatWorkspacePolicy(
    val mode: ChatWorkspaceMode,
    val listWidthDp: Float,
)

internal fun chatWorkspacePolicy(
    availableWidthDp: Float,
    requestedListWidthDp: Float = CHAT_LIST_INITIAL_DP,
): ChatWorkspacePolicy {
    if (availableWidthDp < CHAT_WORKSPACE_BREAKPOINT_DP) {
        return ChatWorkspacePolicy(ChatWorkspaceMode.SINGLE, availableWidthDp.coerceAtLeast(0f))
    }
    if (availableWidthDp < CHAT_WORKSPACE_RESIZABLE_DP) {
        return ChatWorkspacePolicy(ChatWorkspaceMode.DUAL_FIXED, CHAT_LIST_MIN_DP)
    }
    val maximumListWidth = maximumChatListWidthDp(availableWidthDp)
    return ChatWorkspacePolicy(
        ChatWorkspaceMode.DUAL_RESIZABLE,
        requestedListWidthDp.coerceIn(CHAT_LIST_MIN_DP, maximumListWidth),
    )
}

private fun maximumChatListWidthDp(availableWidthDp: Float): Float =
    minOf(
        CHAT_LIST_MAX_DP,
        availableWidthDp - CHAT_DETAIL_MIN_DP - CHAT_PANE_DIVIDER_DP,
    ).coerceAtLeast(CHAT_LIST_MIN_DP)

/** List/detail shell whose fit decision is based on the pane's actual safe content width. */
@Composable
internal fun ChatWorkspace(
    listWidth: MutableFloatState,
    listPane: @Composable (twoPane: Boolean) -> Unit,
    detailPane: (@Composable (showBack: Boolean) -> Unit)? = null,
    onKeyboardNavigate: ChatKeyboardNavigate? = null,
) {
    BoxWithConstraints(
        modifier =
            Modifier
                .chatKeyboardFocusHandoff(onKeyboardNavigate)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        val availableWidth = maxWidth
        var requestedListWidthDp by listWidth
        val policy = chatWorkspacePolicy(availableWidth.value, requestedListWidthDp)
        val twoPane = policy.mode != ChatWorkspaceMode.SINGLE
        if (!twoPane && detailPane != null) {
            detailPane(true)
            return@BoxWithConstraints
        }

        Row(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = if (twoPane) Modifier.width(policy.listWidthDp.dp).fillMaxHeight() else Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                // Keep the list at the same composition location across width changes, including restore.
                listPane(twoPane)
            }
            if (!twoPane) return@Row

            val layoutDirection = LocalLayoutDirection.current
            val resizeDescription = stringResource(R.string.chat_workspace_resize)
            Box(
                modifier =
                    Modifier
                        .width(CHAT_PANE_DIVIDER_DP.dp)
                        .fillMaxHeight()
                        .then(
                            if (policy.mode == ChatWorkspaceMode.DUAL_RESIZABLE) {
                                Modifier
                                    .semantics { contentDescription = resizeDescription }
                                    .pointerInput(availableWidth, layoutDirection) {
                                        val maximumListWidth = maximumChatListWidthDp(availableWidth.value)
                                        val direction = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            val visibleWidth =
                                                requestedListWidthDp.coerceIn(CHAT_LIST_MIN_DP, maximumListWidth)
                                            val resizedWidth =
                                                (visibleWidth + dragAmount.x.toDp().value * direction)
                                                    .coerceIn(CHAT_LIST_MIN_DP, maximumListWidth)
                                            if (resizedWidth != visibleWidth) requestedListWidthDp = resizedWidth
                                        }
                                    }
                            } else {
                                Modifier
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                color = MaterialTheme.colorScheme.background,
            ) {
                if (detailPane == null) {
                    EmptyState(
                        icon = Icons.Outlined.Forum,
                        title = stringResource(R.string.chat_workspace_empty_title),
                        message = stringResource(R.string.chat_workspace_empty_message),
                    )
                } else {
                    detailPane(false)
                }
            }
        }
    }
}
