package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SheetState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.components.EmojiPickerPanel
import io.github.trevarj.motd.ui.components.ReactionChip
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import androidx.compose.foundation.lazy.items as lazyItems

/** Quick-reaction row shown at the top of the action sheet. */
val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢")

/**
 * Long-press action sheet: quick reactions and compact actions. More opens the native full picker.
 * Selecting an already-owned reaction toggles it off in the mutation layer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onMoreReactions: () -> Unit,
    reactionEnabled: (String) -> Boolean = { true },
    reactions: List<ReactionChip> = emptyList(),
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    onShare: () -> Unit,
    canRedact: Boolean = false,
    onRedact: () -> Unit = {},
    // SERVER buffers have no msgids/targets: reply + reactions are inert and hidden.
    isServerBuffer: Boolean = false,
    canPrepareThreadContext: Boolean = false,
    onPrepareThreadContext: () -> Unit = {},
    onTranslate: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
) {
    val hasReactions = reactions.isNotEmpty()
    var selectedTab by remember(hasReactions) { mutableIntStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Root tag disambiguates from NickActionSheet when both could be open.
        modifier = Modifier.testTag("message_action_sheet"),
    ) {
        SheetSystemBars()
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            if (hasReactions) {
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.testTag("message_action_tabs"),
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.chat_action_tab_actions)) },
                        modifier = Modifier.testTag("message_action_tab_actions"),
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.chat_action_tab_reactions)) },
                        modifier = Modifier.testTag("message_action_tab_reactions"),
                    )
                }
            }

            if (!hasReactions || selectedTab == 0) {
                if (!isServerBuffer) {
                    // Quick reactions + "more".
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        QUICK_REACTIONS.forEach { emoji ->
                            val enabled = reactionEnabled(emoji)
                            Box(
                                // >=48dp touch target.
                                modifier =
                                    Modifier
                                        .minimumInteractiveComponentSize()
                                        .alpha(if (enabled) 1f else 0.38f)
                                        .clickable(enabled = enabled) { onReact(emoji) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = emoji, fontSize = 28.sp)
                            }
                        }
                        val moreLabel = stringResource(R.string.chat_action_more_reactions)
                        Box(
                            modifier =
                                Modifier
                                    .testTag("message_more_reactions")
                                    .minimumInteractiveComponentSize()
                                    .semantics { contentDescription = moreLabel }
                                    .clickable(role = Role.Button, onClick = onMoreReactions),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "＋",
                                fontSize = 24.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } // end !isServerBuffer (reactions hidden for SERVER buffers)
                BoxWithConstraints(
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth()
                            .testTag("message_action_actions")
                            .padding(horizontal = 8.dp),
                ) {
                    val cellWidth = maxWidth / 4
                    FlowRow(modifier = Modifier.fillMaxWidth(), maxItemsInEachRow = 4) {
                        // FlowRow probes weighted children's intrinsic widths before wrapping.
                        val actionModifier = Modifier.weight(1f).widthIn(max = cellWidth)
                        if (!isServerBuffer) {
                            ActionItem(
                                Icons.AutoMirrored.Filled.Reply,
                                stringResource(R.string.chat_action_reply),
                                onReply,
                                modifier = actionModifier.testTag("message_action_reply"),
                            )
                            if (canPrepareThreadContext) {
                                ActionItem(
                                    Icons.Outlined.AutoAwesome,
                                    stringResource(R.string.agent_context_thread),
                                    onPrepareThreadContext,
                                    modifier = actionModifier.testTag("message_prepare_thread_context"),
                                )
                            }
                        }
                        onTranslate?.let {
                            ActionItem(
                                Icons.Outlined.Translate,
                                stringResource(R.string.chat_action_translate),
                                it,
                                modifier = actionModifier.testTag("message_action_translate"),
                            )
                        }
                        ActionItem(
                            Icons.Filled.ContentCopy,
                            stringResource(R.string.chat_action_copy),
                            onCopy,
                            modifier = actionModifier.testTag("message_action_copy"),
                        )
                        onForward?.let {
                            ActionItem(
                                Icons.AutoMirrored.Filled.Send,
                                stringResource(R.string.chat_action_forward),
                                it,
                                modifier = actionModifier.testTag("message_action_forward"),
                            )
                        }
                        ActionItem(
                            Icons.Filled.Share,
                            stringResource(R.string.chat_action_share),
                            onShare,
                            modifier = actionModifier.testTag("message_action_share"),
                        )
                        ActionItem(
                            Icons.Filled.FormatQuote,
                            stringResource(R.string.chat_action_quote),
                            onQuote,
                            modifier = actionModifier.testTag("message_action_quote"),
                        )
                        if (canRedact) {
                            ActionItem(
                                Icons.Filled.DeleteOutline,
                                stringResource(R.string.chat_action_delete_message),
                                onRedact,
                                modifier = actionModifier.testTag("message_redact"),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        // Weighted blanks keep a partial last row in quarter-width cells.
                        val actionCount =
                            3 + (if (!isServerBuffer) 1 else 0) +
                                (if (!isServerBuffer && canPrepareThreadContext) 1 else 0) +
                                (if (onTranslate != null) 1 else 0) +
                                (if (onForward != null) 1 else 0) +
                                (if (canRedact) 1 else 0)
                        repeat((4 - actionCount % 4) % 4) { Spacer(actionModifier) }
                    }
                }
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .testTag("message_reaction_reactors"),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    lazyItems(reactions, key = { it.emoji }) { reaction ->
                        val displayNames = reaction.reactorDisplayNames.joinToString(", ")
                        val description =
                            stringResource(
                                R.string.chat_reaction_reactors_description,
                                reaction.emoji,
                                displayNames,
                            )
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("message_reaction_reactors_${reaction.emoji}")
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = description
                                    }.padding(horizontal = 24.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = reaction.emoji, fontSize = 28.sp)
                            Text(
                                text = displayNames,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Full native picker; selection permissions can differ for adding and removing the same emoji. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageReactionPicker(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    reactionEnabled: (String) -> Boolean,
) {
    var unavailable by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("message_reaction_picker"),
    ) {
        SheetSystemBars()
        Column(Modifier.fillMaxSize().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.chat_action_more_reactions),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (unavailable) {
                Text(
                    text = stringResource(R.string.chat_reaction_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    modifier =
                        Modifier
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                            .testTag("message_reaction_unavailable")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            EmojiPickerPanel(
                onPick = { emoji ->
                    if (reactionEnabled(emoji)) {
                        onDismiss()
                        onReact(emoji)
                    } else {
                        unavailable = true
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
                tagPrefix = "message_reaction",
            )
        }
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    TextButton(
        onClick = onClick,
        modifier =
            modifier.heightIn(min = 48.dp).clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                this.onClick {
                    onClick()
                    true
                }
            },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
