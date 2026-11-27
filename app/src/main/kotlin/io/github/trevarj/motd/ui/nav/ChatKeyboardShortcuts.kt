package io.github.trevarj.motd.ui.nav

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.dickord.DickordPortalState
import io.github.trevarj.motd.service.isUnreadChat
import kotlinx.coroutines.flow.first
import android.view.KeyEvent as AndroidKeyEvent
import io.github.trevarj.motd.ui.components.EmojiText as Text
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

internal typealias ChatKeyboardNavigate = (action: ChatKeyboardAction, fromHardware: Boolean) -> Boolean

internal const val CHAT_KEYBOARD_FOCUS = "chat_keyboard_focus"

/** Only a hardware-opened workspace gets a neutral receiver; focus chosen in either pane takes precedence. */
@Composable
internal fun Modifier.chatKeyboardFocusHandoff(navigate: ChatKeyboardNavigate?): Modifier {
    val entry = LocalLifecycleOwner.current as? NavBackStackEntry
    val handoff = remember(entry) { entry?.savedStateHandle?.remove<Boolean>(CHAT_KEYBOARD_FOCUS) == true }
    if (!handoff) return this
    val requester = remember { FocusRequester() }
    var focus by remember { mutableStateOf<FocusState?>(null) }
    val currentNavigate by rememberUpdatedState(navigate)
    LaunchedEffect(entry) {
        requireNotNull(entry).lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
        if (currentNavigate != null && focus?.hasFocus != true) requester.requestFocus()
    }
    return this
        .onKeyEvent { event ->
            val action = chatKeyboardAction(event)
            focus?.isFocused == true && action != null && currentNavigate?.invoke(action, true) == true
        }.focusRequester(requester)
        .onFocusChanged { focus = it }
        .focusable()
}

internal enum class ChatKeyboardAction(
    val label: Int,
    val step: Int,
    val unread: Boolean,
) {
    PREVIOUS(R.string.chat_keyboard_previous, -1, false),
    NEXT(R.string.chat_keyboard_next, 1, false),
    PREVIOUS_UNREAD(R.string.chat_keyboard_previous_unread, -1, true),
    NEXT_UNREAD(R.string.chat_keyboard_next_unread, 1, true),
}

internal fun chatKeyboardAction(event: KeyEvent): ChatKeyboardAction? {
    val key = event.nativeKeyEvent
    if (key.action != AndroidKeyEvent.ACTION_DOWN || key.repeatCount != 0) return null
    val unread = key.hasModifiers(AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_SHIFT_ON)
    if (!unread && !key.hasModifiers(AndroidKeyEvent.META_CTRL_ON)) return null
    return when (key.keyCode) {
        AndroidKeyEvent.KEYCODE_F7 -> if (unread) ChatKeyboardAction.PREVIOUS_UNREAD else ChatKeyboardAction.PREVIOUS
        AndroidKeyEvent.KEYCODE_F8 -> if (unread) ChatKeyboardAction.NEXT_UNREAD else ChatKeyboardAction.NEXT
        else -> null
    }
}

internal fun ChatKeyboardAction.target(
    portal: DickordPortalState,
    current: Long?,
): Long? {
    if (!portal.enabled || portal.loading || portal.showArchived) return null
    val group = portal.groups.firstOrNull { it.key == portal.selectedGroupKey } ?: return null
    return target(group.conversations.map { it.row }, current)
}

internal fun ChatKeyboardAction.target(
    rows: List<ChatListRow>,
    current: Long?,
    unreadOnly: Boolean = false,
): Long? {
    if (rows.isEmpty()) return null
    var index = rows.indexOfFirst { it.bufferId == current }
    if (index < 0 && step < 0) index = 0
    repeat(rows.size) {
        index = Math.floorMod(index + step, rows.size)
        val row = rows[index]
        if (row.bufferId != current && row.type != BufferType.SERVER && (!(unreadOnly || unread) || isUnreadChat(row))) return row.bufferId
    }
    return null
}

@Composable
internal fun ChatKeyboardShortcutsIcon() {
    Icon(Icons.Outlined.Terminal, contentDescription = null)
}

@Composable
internal fun ChatKeyboardShortcutsDialog(
    onAction: (ChatKeyboardAction) -> Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("chat_keyboard_dialog"),
        icon = { ChatKeyboardShortcutsIcon() },
        title = { Text(stringResource(R.string.chat_keyboard_shortcuts)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ChatKeyboardAction.entries.forEach { action ->
                    TextButton(
                        onClick = { if (onAction(action)) onDismiss() },
                        modifier = Modifier.fillMaxWidth().testTag("chat_keyboard_${action.name.lowercase()}"),
                    ) { Text(stringResource(action.label)) }
                }
                Text(stringResource(R.string.chat_keyboard_completion))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) } },
    )
}
