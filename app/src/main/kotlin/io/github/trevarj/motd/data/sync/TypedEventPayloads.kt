package io.github.trevarj.motd.data.sync

import java.util.Base64

/** Versioned durable payload for [io.github.trevarj.motd.data.db.MessageKind.INVITE]. */
data class InvitePayloadV1(val inviter: String, val target: String, val channel: String) {
    fun encode(): String = "$VERSION:${encodeField(inviter)}:${encodeField(target)}:${encodeField(channel)}"

    companion object {
        /** Unknown versions and incomplete payloads deliberately degrade to rendered system text. */
        fun decode(value: String?): InvitePayloadV1? = runCatching {
            val parts = value?.split(':') ?: return null
            if (parts.size != 4 || parts[0] != VERSION) return null
            val inviter = String(DECODER.decode(parts[1]), Charsets.UTF_8)
            val target = String(DECODER.decode(parts[2]), Charsets.UTF_8)
            val channel = String(DECODER.decode(parts[3]), Charsets.UTF_8)
            if (target.isBlank() || channel.isBlank()) return null
            InvitePayloadV1(inviter, target, channel)
        }.getOrNull()

        private const val VERSION = "invite-v1"
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()
        private fun encodeField(value: String): String =
            ENCODER.encodeToString(value.toByteArray(Charsets.UTF_8))
    }
}

/** Versioned durable payload for collapsed NETSPLIT/NETJOIN timeline events. */
data class NetworkBatchPayloadV1(val serverA: String, val serverB: String, val nicks: List<String>) {
    fun encode(): String = listOf(
        VERSION,
        encodeField(serverA),
        encodeField(serverB),
        nicks.joinToString(".") { encodeField(it) },
    ).joinToString(":")

    companion object {
        fun decode(value: String?): NetworkBatchPayloadV1? = runCatching {
            val parts = value?.split(':') ?: return null
            if (parts.size != 4 || parts[0] != VERSION) return null
            val serverA = decodeField(parts[1])
            val serverB = decodeField(parts[2])
            if (serverA.isBlank() || serverB.isBlank()) return null
            val nicks = if (parts[3].isBlank()) emptyList() else parts[3].split('.').map(::decodeField)
            if (nicks.any(String::isBlank)) return null
            NetworkBatchPayloadV1(serverA, serverB, nicks)
        }.getOrNull()

        private const val VERSION = "network-v1"
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()
        private fun encodeField(value: String) = ENCODER.encodeToString(value.toByteArray(Charsets.UTF_8))
        private fun decodeField(value: String) = String(DECODER.decode(value), Charsets.UTF_8)
    }
}
