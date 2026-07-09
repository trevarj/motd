package io.github.trevarj.motd.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
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
    autocomplete: (@Composable () -> Unit)? = null,
) {
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
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(placeholder) },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                val canSend = enabled && value.text.isNotBlank()
                IconButton(
                    onClick = onSend,
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
