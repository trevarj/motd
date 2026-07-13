package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferType

internal data class ReplyDelivery(
    val text: String,
    val wireReplyToMsgid: String?,
)

/** Resolve semantic-tag and visible-prefix delivery without changing the local reply relationship. */
internal fun prepareReplyDelivery(
    text: String,
    replyToMsgid: String?,
    parentSender: String?,
    bufferType: BufferType,
    visibleChannelPrefix: Boolean,
    replyTagAllowed: Boolean,
): ReplyDelivery {
    if (replyToMsgid == null) return ReplyDelivery(text, null)
    val needsPrefix = parentSender != null && (
        !replyTagAllowed || (visibleChannelPrefix && bufferType == BufferType.CHANNEL)
    )
    val prefix = parentSender?.let { "$it: " }
    val deliveredText = if (needsPrefix && prefix != null && !text.startsWith(prefix)) prefix + text else text
    return ReplyDelivery(
        text = deliveredText,
        wireReplyToMsgid = replyToMsgid.takeIf { replyTagAllowed },
    )
}
