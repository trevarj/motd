package io.github.trevarj.motd.irc.agentwire

import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.MultilineSendPlan
import io.github.trevarj.motd.irc.client.canSendClientTag
import io.github.trevarj.motd.irc.client.multilineLimits
import io.github.trevarj.motd.irc.client.planChatMessage
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Build and atomically send one validated Agentwire envelope. */
suspend fun IrcClient.sendAgentwire(
    target: String,
    envelope: AgentwireEnvelope,
    preview: String? = null,
): Boolean {
    val ready = state.value as? IrcClientState.Ready ?: return false
    if (agentwireMissingCaps(ready.caps).isNotEmpty()) return false
    if (!canSendClientTag(ready.caps, ready.isupport, AGENTWIRE_TAG)) return false
    val previewBytes = preview?.toByteArray(Charsets.UTF_8)?.size ?: 0
    require(previewBytes <= 4_096) { "Agentwire preview exceeds 4096 UTF-8 bytes" }
    envelope.data?.get("content")?.let { content ->
        val text =
            (content as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: throw IllegalArgumentException("Agentwire content must be a string")
        require(text.toByteArray(Charsets.UTF_8).size <= AGENTWIRE_MAX_CONTENT_BYTES) {
            "Agentwire content exceeds 64 KiB"
        }
    }

    val values = fragmentAgentwireEnvelope(envelope)
    val messages = ArrayList<IrcMessage>()
    values.forEachIndexed { index, value ->
        if (index == 0 && preview != null) {
            val plan =
                planChatMessage(
                    target = target,
                    text = preview,
                    replyToMsgid = null,
                    label = null,
                    multilineLimits = multilineLimits(ready.caps),
                    protocolTags = mapOf(AGENTWIRE_TAG to value),
                ) ?: return false
            when (plan) {
                is MultilineSendPlan.Single -> {
                    if (plan.message.params.any { value -> value.any { it == '\r' || it == '\n' } }) return false
                    // A single IRC message must retain room for command, target, separators, and CRLF.
                    if (previewBytes > 400) return false
                    messages += plan.message
                }

                is MultilineSendPlan.Batch -> {
                    messages += plan.opening
                    messages += plan.components
                    messages += plan.closing
                }
            }
        } else {
            messages +=
                IrcMessage(
                    tags = mapOf(AGENTWIRE_TAG to value),
                    command = "TAGMSG",
                    params = listOf(target),
                )
        }
    }
    return sendAtomicallyIfConnected(messages)
}

fun AgentwireEnvelope.readablePreview(): String? {
    if (kind != "turn.prompt" && kind != "turn.steer") return null
    val content = data?.get("content").stringContent() ?: return null
    // The full prompt travels in the structured envelope; previews must fit one IRC line.
    return if (content.toByteArray(Charsets.UTF_8).size <= 400) {
        content
    } else {
        "Agentwire prompt (preview omitted; full text in the Agentwire payload)."
    }
}

private fun kotlinx.serialization.json.JsonElement?.stringContent(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
