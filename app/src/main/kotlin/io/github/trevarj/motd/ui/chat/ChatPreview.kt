package io.github.trevarj.motd.ui.chat

import androidx.compose.runtime.Composable
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.irc.event.IrcClientState
import kotlinx.coroutines.flow.flowOf
import io.github.trevarj.motd.data.prefs.DEFAULT_FONT_SCALE_PERCENT

/**
 * Preview-only chat content: a scripted mix (groups, system pill, reply, reactions, inline image,
 * link preview, failed message) fed through fake [PagingData] with no ViewModel (WP7 acceptance).
 */
@Composable
fun ChatContentPreviewBody(
    conversationFontScalePercent: Int = DEFAULT_FONT_SCALE_PERCENT,
) {
    val now = System.currentTimeMillis()
    val messages = listOf(
        MessageEntity(
            id = 8, bufferId = 1, msgid = "m8", serverTime = now - 1_000,
            sender = "me", kind = MessageKind.PRIVMSG, text = "this one failed to send",
            isSelf = true, failed = true, dedupKey = "m8",
        ),
        MessageEntity(
            id = 7, bufferId = 1, msgid = "m7", serverTime = now - 20_000,
            sender = "me", kind = MessageKind.PRIVMSG, text = "on it, replying now",
            isSelf = true, replyToMsgid = "m2", dedupKey = "m7",
        ),
        MessageEntity(
            id = 6, bufferId = 1, msgid = "m6", serverTime = now - 40_000,
            sender = "bob", kind = MessageKind.PRIVMSG,
            text = "check this out https://example.com/article", dedupKey = "m6",
        ),
        MessageEntity(
            id = 5, bufferId = 1, msgid = "m5", serverTime = now - 60_000,
            sender = "bob", kind = MessageKind.PRIVMSG,
            text = "and a picture https://example.com/cat.png", dedupKey = "m5",
        ),
        MessageEntity(
            id = 4, bufferId = 1, msgid = "m4", serverTime = now - 90_000,
            sender = "carol", kind = MessageKind.JOIN, text = "carol joined", dedupKey = "m4",
        ),
        MessageEntity(
            id = 3, bufferId = 1, msgid = "m3", serverTime = now - 120_000,
            sender = "alice", kind = MessageKind.ACTION, text = "waves at everyone", dedupKey = "m3",
        ),
        MessageEntity(
            id = 2, bufferId = 1, msgid = "m2", serverTime = now - 150_000,
            sender = "alice", kind = MessageKind.PRIVMSG, text = "welcome to the channel!",
            dedupKey = "m2",
        ),
        MessageEntity(
            id = 1, bufferId = 1, msgid = "m1", serverTime = now - 152_000,
            sender = "alice", kind = MessageKind.PRIVMSG, text = "hey there", dedupKey = "m1",
        ),
    )
    val items = flowOf(PagingData.from(messages)).collectAsLazyPagingItems()

    ChatContent(
        state = ChatState(
            buffer = BufferEntity(
                id = 1, networkId = 1, name = "#kotlin", displayName = "#kotlin",
                type = BufferType.CHANNEL, readMarkerTime = now - 100_000,
            ),
            memberCount = 3,
            typingNicks = listOf("alice"),
            replyTo = null,
            connState = IrcClientState.Ready("me", emptySet(), emptyMap()),
        ),
        items = items,
        composerEnabled = true,
        onBack = {}, onOpenChannelInfo = {}, onOpenSearch = {}, onOpenImage = {},
        nickNormalizer = { it.lowercase() },
        onSubmit = {}, onTyping = {}, onSetReply = {}, onReact = { _, _ -> }, onRetry = {},
        memberNicks = listOf("alice", "bob", "carol"),
        loadPreview = { null },
        conversationFontScalePercent = conversationFontScalePercent,
    )
}
