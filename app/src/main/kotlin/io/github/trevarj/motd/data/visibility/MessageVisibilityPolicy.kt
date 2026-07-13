package io.github.trevarj.motd.data.visibility

import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.normalizeNick

val JOIN_PART_QUIT_KINDS: Set<MessageKind> =
    setOf(MessageKind.JOIN, MessageKind.PART, MessageKind.QUIT)

val CONVERSATION_KINDS: Set<MessageKind> =
    setOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION)

data class MessageVisibilitySpec(
    val showJoinPartQuit: Boolean = true,
    val fools: Set<String> = emptySet(),
    val foolsMode: FoolsMode = FoolsMode.COLLAPSE,
) {
    companion object {
        fun from(settings: Settings): MessageVisibilitySpec = MessageVisibilitySpec(
            showJoinPartQuit = settings.showJoinPartQuit,
            fools = settings.fools,
            foolsMode = settings.foolsMode,
        )
    }
}

/** One policy for every consumer that decides whether a stored message is meaningful. */
class MessageVisibilityPolicy(
    private val spec: MessageVisibilitySpec,
) {
    fun isFool(message: MessageEntity): Boolean =
        message.kind in CONVERSATION_KINDS &&
            !message.isSelf &&
            normalizeNick(message.sender) in spec.fools

    /** Rows physically presented by the timeline. Collapse retains its expandable placeholder. */
    fun timeline(message: MessageEntity): Boolean =
        (spec.showJoinPartQuit || message.kind !in JOIN_PART_QUIT_KINDS) &&
            !(spec.foolsMode == FoolsMode.HIDE && isFool(message))

    /** Preview and activity use the same eligibility; fools never reorder the chat list. */
    fun preview(message: MessageEntity): Boolean =
        message.kind !in JOIN_PART_QUIT_KINDS && !isFool(message)

    fun activity(message: MessageEntity): Boolean = preview(message)

    /** Visible unread and mention counts include only other users' meaningful chat rows. */
    fun visibleUnread(message: MessageEntity): Boolean =
        message.kind in CONVERSATION_KINDS && !message.isSelf && !isFool(message)

    /** Hide removes fool results; Collapse keeps them so the target can be expanded on open. */
    fun search(message: MessageEntity): Boolean =
        message.kind in CONVERSATION_KINDS &&
            !(spec.foolsMode == FoolsMode.HIDE && isFool(message))

    /** Anchors never attach to ignored activity, including a collapsed fool placeholder. */
    fun anchor(message: MessageEntity): Boolean = timeline(message) && !isFool(message)

    /** Ignored raw tails are already settled when the newest meaningful row is at the viewport. */
    fun effectiveBottom(message: MessageEntity): Boolean = anchor(message)
}
