package io.github.trevarj.motd.irc.ext

import io.github.trevarj.motd.irc.proto.IrcMessage
import java.util.Base64

/** Builds soju `WEBPUSH` command lines (plans/03 soju.im/webpush). */
internal object WebPushCommands {
    private val b64url = Base64.getUrlEncoder().withoutPadding()

    /**
     * WEBPUSH REGISTER <endpoint> <keys> where <keys> is tag-escaped
     * `p256dh=<b64url>;auth=<b64url>` (our ECDH public key + 16-byte auth secret).
     */
    fun register(endpoint: String, p256dh: ByteArray, auth: ByteArray): IrcMessage {
        val keys = renderAttrString(
            linkedMapOf(
                "p256dh" to b64url.encodeToString(p256dh),
                "auth" to b64url.encodeToString(auth),
            ),
        )
        return IrcMessage(command = "WEBPUSH", params = listOf("REGISTER", endpoint, keys))
    }

    fun unregister(endpoint: String): IrcMessage =
        IrcMessage(command = "WEBPUSH", params = listOf("UNREGISTER", endpoint))
}
