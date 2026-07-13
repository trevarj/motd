package io.github.trevarj.motd.avatar

import io.github.trevarj.motd.irc.proto.IrcMessage

const val AVATAR_CAP = "draft/metadata-2"
const val AVATAR_KEY = "avatar"

sealed interface AvatarMetadataEvent {
    data class Changed(val target: String, val url: String) : AvatarMetadataEvent
    data class Removed(val target: String) : AvatarMetadataEvent
    data class SyncLater(val target: String, val retryAfterSeconds: Long) : AvatarMetadataEvent
}

fun subscribeAvatarMessage() = IrcMessage(command = "METADATA", params = listOf("*", "SUB", AVATAR_KEY))
fun unsubscribeAvatarMessage() = IrcMessage(command = "METADATA", params = listOf("*", "UNSUB", AVATAR_KEY))
fun syncAvatarMessage(target: String) = IrcMessage(command = "METADATA", params = listOf(target, "SYNC"))
fun publishAvatarMessage(url: String?) = IrcMessage(
    command = "METADATA",
    params = if (url == null) listOf("*", "SET", AVATAR_KEY) else listOf("*", "SET", AVATAR_KEY, url),
)

fun parseAvatarMetadata(message: IrcMessage): AvatarMetadataEvent? {
    return when (message.command) {
    "METADATA" -> {
        val target = message.params.getOrNull(0) ?: return null
        if (message.params.getOrNull(1) != AVATAR_KEY) return null
        val value = message.params.getOrNull(3) ?: return null
        validateAvatarUrl(value)?.let { AvatarMetadataEvent.Changed(target, it) }
            ?: AvatarMetadataEvent.Removed(target)
    }
    "761" -> {
        val target = message.params.getOrNull(1) ?: return null
        if (message.params.getOrNull(2) != AVATAR_KEY) return null
        val value = message.params.getOrNull(4) ?: return null
        validateAvatarUrl(value)?.let { AvatarMetadataEvent.Changed(target, it) }
            ?: AvatarMetadataEvent.Removed(target)
    }
    "766" -> {
        val target = message.params.getOrNull(1) ?: return null
        if (message.params.getOrNull(2) != AVATAR_KEY) return null
        AvatarMetadataEvent.Removed(target)
    }
    "774" -> AvatarMetadataEvent.SyncLater(
        target = message.params.getOrNull(1) ?: return null,
        retryAfterSeconds = message.params.getOrNull(2)?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
    )
        else -> null
    }
}
