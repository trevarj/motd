package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.irc.event.MessageContext
import java.security.MessageDigest

/**
 * Legacy display-row diagnostic keys and stable typed-event payload fingerprints.
 *
 *  - live/history message with `msgid` → the msgid.
 *  - no msgid → `sha1("<serverTime>|<sender>|<text>")` hex.
 *  - locally-sent pending message → `"pending:<label>"` until echo confirms.
 *
 * These values do not participate in chat-event identity. CanonicalTimelineStore enforces that
 * through persisted aliases; typed system events may use the stable hash as a TYPED_EVENT alias.
 */
internal object SemanticIdentity {
    /** Key for a live or history message. */
    fun keyFor(msgid: String?, serverTime: Long, sender: String, text: String): String =
        msgid ?: sha1Hex("$serverTime|$sender|$text")

    fun keyFor(ctx: MessageContext, sender: String, text: String): String =
        keyFor(ctx.msgid, ctx.serverTime, sender, text)

    /** Key for a locally-sent pending row awaiting its labeled echo. */
    fun pendingKey(label: String): String = "pending:$label"

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
