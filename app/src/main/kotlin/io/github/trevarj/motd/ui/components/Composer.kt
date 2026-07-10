package io.github.trevarj.motd.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.chat.EMOJI_GRID
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Reply target shown in the composer's reply-preview bar. */
data class ComposerReply(val sender: String, val text: String)

/**
 * Message composer: optional autocomplete panel + reply bar stacked above a multiline field and a
 * send button. Stateless — text is a hoisted [TextFieldValue] (cursor-aware for autocomplete). Send
 * is disabled on blank input or when [enabled] is false (disconnected). See plans/07.
 *
 * The autocomplete/command panel is passed as a slot ([autocomplete]) so the screen owns matching.
 */
@Composable
fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    reply: ComposerReply? = null,
    onCancelReply: () -> Unit = {},
    // Placeholder text; SERVER buffers pass a "Send a command…" hint (plans/16 §5.6).
    placeholder: String = stringResource(R.string.chat_composer_placeholder),
    // When false the emoji (Mood) button is hidden per the user's setting; picker stays collapsed.
    showEmojiButton: Boolean = true,
    autocomplete: (@Composable () -> Unit)? = null,
) {
    // Inline emoji picker toggle; collapses on send so the panel never sticks around after a message.
    var showEmojiPicker by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column {
            autocomplete?.invoke()

            AnimatedVisibility(visible = reply != null) {
                reply?.let { ReplyBar(it, onCancelReply) }
            }

            Row(
                // Trimmed vertical padding keeps the input area compact without cramping the field.
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (showEmojiButton) {
                    IconButton(
                        onClick = { showEmojiPicker = !showEmojiPicker },
                        modifier = Modifier.testTag("chat_composer_emoji").padding(end = 4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Mood,
                            contentDescription = stringResource(
                                if (showEmojiPicker) R.string.chat_composer_emoji_close
                                else R.string.chat_composer_emoji,
                            ),
                            tint = if (showEmojiPicker) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    // Stable handle: the visible placeholder differs across SERVER buffers.
                    modifier = Modifier.weight(1f).testTag("chat_composer_field"),
                    placeholder = { Text(placeholder) },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                    // Sentence capitalization: the IME uppercases the first letter of each sentence
                    // (bug #10). Autocomplete/commands are unaffected — a leading "/" isn't a letter.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default,
                    ),
                )
                val canSend = enabled && value.text.isNotBlank()
                IconButton(
                    onClick = {
                        showEmojiPicker = false
                        onSend()
                    },
                    enabled = canSend,
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.chat_composer_send),
                        tint = if (canSend) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }

            // Expandable emoji grid; reuses the reaction picker's curated EMOJI_GRID (no new dep).
            AnimatedVisibility(visible = showEmojiPicker) {
                EmojiPickerPanel(onPick = { emoji -> onValueChange(insertAtCursor(value, emoji)) })
            }
        }
    }
}

/**
 * Insert [insertion] into [value] at the cursor (replacing any selection) and place the cursor
 * right after it. Preserves the composer's [TextFieldValue] contract so the draft/autocomplete
 * cursor logic keeps working.
 */
fun insertAtCursor(value: TextFieldValue, insertion: String): TextFieldValue {
    val start = value.selection.min
    val end = value.selection.max
    val text = value.text.replaceRange(start, end, insertion)
    val cursor = start + insertion.length
    return TextFieldValue(text = text, selection = TextRange(cursor))
}

/** Scrollable emoji grid reusing the reaction EMOJI_GRID; taps insert at the composer cursor. */
@Composable
private fun EmojiPickerPanel(onPick: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        modifier = Modifier
            .testTag("chat_composer_emoji_grid")
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        items(EMOJI_GRID) { emoji ->
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .clickable { onPick(emoji) },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = emoji, fontSize = 24.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ReplyBar(reply: ComposerReply, onCancel: () -> Unit) {
    val accent = LocalNickColors.current.nick(reply.sender, MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .padding(vertical = 2.dp)
                .background(accent),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
            Text(
                text = stringResource(R.string.chat_composer_replying_to, reply.sender),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            Text(
                text = reply.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.chat_composer_cancel_reply))
        }
    }
}

@Preview
@Composable
private fun ComposerPreview() {
    MotdTheme {
        Composer(
            value = TextFieldValue("hello there"),
            onValueChange = {},
            onSend = {},
            enabled = true,
        )
    }
}

@Preview
@Composable
private fun EmojiPickerPanelPreview() {
    MotdTheme {
        EmojiPickerPanel(onPick = {})
    }
}

@Preview
@Composable
private fun ComposerReplyPreview() {
    MotdTheme {
        Composer(
            value = TextFieldValue(""),
            onValueChange = {},
            onSend = {},
            enabled = true,
            reply = ComposerReply("alice", "welcome to the channel!"),
        )
    }
}
