package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.trevarj.motd.R

/** Quick-reaction row shown at the top of the action sheet (plans/07). */
val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢")

/**
 * ~64 common emoji for the "more" grid. No external emoji-picker dependency (plans/07).
 */
val EMOJI_GRID = listOf(
    "👍", "👎", "❤️", "🔥", "😂", "🤣", "😊", "😍",
    "😎", "😭", "😢", "😮", "😯", "😳", "🥳", "🤔",
    "🙄", "😴", "😅", "😇", "🙃", "😉", "😜", "🤯",
    "🤗", "🤝", "🙏", "👏", "🙌", "💪", "✌️", "🤞",
    "👌", "👀", "💯", "✨", "⭐", "🎉", "🎊", "🚀",
    "💥", "⚡", "☀️", "🌈", "🌙", "❄️", "☕", "🍺",
    "🍕", "🎂", "🐶", "🐱", "🦊", "🐢", "🦄", "🐝",
    "💀", "👻", "🤖", "👽", "💩", "❓", "❗", "✅",
)

/**
 * Long-press action sheet: quick-reaction row + Reply/Copy/Quote actions, expandable to the full
 * emoji grid. Reactions are add-only (plans/07). Callbacks dismiss the sheet at the call site.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    // SERVER buffers have no msgids/targets: reply + reactions are inert and hidden (plans/16 §5.6).
    isServerBuffer: Boolean = false,
) {
    var showGrid by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            if (!isServerBuffer) {
            // Quick reactions + "more".
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QUICK_REACTIONS.forEach { emoji ->
                    Box(
                        // >=48dp touch target (plans/15 #24).
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clickable { onReact(emoji) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = emoji, fontSize = 28.sp)
                    }
                }
                val moreLabel = stringResource(R.string.chat_action_more_reactions)
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        // Expander a11y: label + expanded/collapsed state (plans/15 #31).
                        .semantics {
                            role = Role.Button
                            contentDescription = moreLabel
                            stateDescription = if (showGrid) "Expanded" else "Collapsed"
                        }
                        .clickable { showGrid = !showGrid },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "＋",
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (showGrid) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .padding(horizontal = 12.dp),
                ) {
                    items(EMOJI_GRID) { emoji ->
                        Box(
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clickable { onReact(emoji) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = emoji, fontSize = 24.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            ActionItem(Icons.AutoMirrored.Filled.Reply, stringResource(R.string.chat_action_reply), onReply)
            } // end !isServerBuffer (reactions + reply hidden for SERVER buffers)
            ActionItem(Icons.Filled.ContentCopy, stringResource(R.string.chat_action_copy), onCopy)
            ActionItem(Icons.Filled.FormatQuote, stringResource(R.string.chat_action_quote), onQuote)
        }
    }
}

@Composable
private fun ActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 20.dp),
        )
    }
}
