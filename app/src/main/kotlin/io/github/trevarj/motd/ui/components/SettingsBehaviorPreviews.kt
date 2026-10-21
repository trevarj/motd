package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.service.prepareReplyDelivery
import io.github.trevarj.motd.ui.chat.FoolCollapseChip
import io.github.trevarj.motd.ui.chat.FoolPlaceholder

/** Fixed illustrative outcomes, not a second Smart-presence predicate. Checked against the real SQL. */
internal val presenceExample =
    listOf(
        MessageEntity(id = 1, bufferId = -1, serverTime = 1_704_110_040_000, sender = "Alex", normalizedActor = "alex", kind = MessageKind.PRIVMSG, text = "Hello everyone", dedupKey = "example-speech"),
        MessageEntity(id = 2, bufferId = -1, serverTime = 1_704_110_100_000, sender = "Robin", normalizedActor = "robin", kind = MessageKind.JOIN, text = "Robin joined", dedupKey = "example-silent"),
        MessageEntity(id = 3, bufferId = -1, serverTime = 1_704_110_160_000, sender = "Alex", normalizedActor = "alex", kind = MessageKind.QUIT, text = "Alex left", dedupKey = "example-recent"),
        MessageEntity(id = 4, bufferId = -1, serverTime = 1_704_110_220_000, sender = "You", normalizedActor = "you", kind = MessageKind.JOIN, text = "You joined", isSelf = true, dedupKey = "example-own"),
        MessageEntity(id = 5, bufferId = -1, serverTime = 1_704_110_280_000, sender = "", kind = MessageKind.NETSPLIT, text = "A network split occurred", dedupKey = "example-aggregate"),
    )

private val smartPresenceExample = listOf(presenceExample[0], presenceExample[2], presenceExample[3], presenceExample[4])
private val hiddenPresenceExample = listOf(presenceExample[0])

internal fun presenceExampleRows(mode: PresenceMode): List<MessageEntity> =
    when (mode) {
        PresenceMode.ALL -> presenceExample
        PresenceMode.SMART -> smartPresenceExample
        PresenceMode.HIDDEN -> hiddenPresenceExample
    }

@Composable
internal fun LocalBehaviorExample(
    tag: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val avatars = remember { RemoteAvatarState() }
    CompositionLocalProvider(LocalRemoteAvatars provides avatars, LocalAutomaticRemoteMedia provides false, LocalMessageActionsEnabled provides false) {
        Column(modifier.padding(vertical = 8.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
internal fun PresenceExample(mode: PresenceMode) {
    LocalBehaviorExample("presence_example_${mode.name.lowercase()}") {
        Text(stringResource(R.string.settings_presence_example_context), style = MaterialTheme.typography.bodySmall)
        for (row in presenceExampleRows(mode)) {
            if (row.kind == MessageKind.PRIVMSG) {
                MessageBubble(row.sender, row.text, row.serverTime, false, row.kind, true)
            } else {
                SystemEventPill(row.text, 1, { listOf(row.text) }, row.id)
            }
        }
    }
}

@Composable
internal fun FoolVisibilityExample(mode: FoolsMode) {
    var expanded by remember(mode) { mutableStateOf(false) }
    LocalBehaviorExample("fool_example_${mode.name.lowercase()}") {
        when (mode) {
            FoolsMode.DIMMED -> {
                MessageBubble("Alex", stringResource(R.string.settings_chat_preview_message), presenceExample[0].serverTime, false, MessageKind.PRIVMSG, true, dimmed = true)
            }

            FoolsMode.COLLAPSE -> {
                if (expanded) {
                    FoolCollapseChip("Alex", "fool_example_collapse_again", modifier = Modifier.heightIn(min = 48.dp)) { expanded = false }
                    MessageBubble("Alex", stringResource(R.string.settings_chat_preview_message), presenceExample[0].serverTime, false, MessageKind.PRIVMSG, true)
                } else {
                    FoolPlaceholder("Alex", Modifier.heightIn(min = 48.dp).testTag("fool_example_placeholder"), onExpand = { expanded = true })
                }
            }

            FoolsMode.HIDE -> {
                Text(stringResource(R.string.settings_example_hidden_row))
            }
        }
    }
}

@Composable
internal fun ReplyPrefixExample(
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val body = stringResource(R.string.settings_chat_preview_reply)
    val delivered = remember(body, enabled) { prepareReplyDelivery(body, "example-parent", "Alex", BufferType.CHANNEL, enabled, replyTagAllowed = true) }
    val parentText = stringResource(R.string.settings_chat_preview_message)
    val parent = remember(parentText) { ReplyPreviewData("Alex", parentText) }
    LocalBehaviorExample("reply_prefix_example", modifier) {
        MessageBubble("You", delivered.text, presenceExample[0].serverTime, true, MessageKind.PRIVMSG, true, reply = parent)
        Text(stringResource(R.string.settings_reply_example_fallback), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun DeletedMessageExample(
    show: Boolean,
    modifier: Modifier = Modifier,
) {
    val tombstone = stringResource(R.string.settings_deleted_example_text)
    val deleted = remember(tombstone) { presenceExample[0].copy(kind = MessageKind.REDACTED, text = tombstone) }
    val policy = remember(show) { MessageVisibilityPolicy(MessageVisibilitySpec(showRedactedMessages = show)) }
    LocalBehaviorExample("deleted_message_example", modifier) {
        if (policy.timeline(deleted)) {
            MessageBubble(deleted.sender, deleted.text, deleted.serverTime, false, deleted.kind, true)
        } else {
            Text(stringResource(R.string.settings_example_hidden_row))
        }
    }
}
