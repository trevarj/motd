package io.github.trevarj.motd.avatar

import io.github.trevarj.motd.irc.proto.IrcMessage

const val METADATA_CAP = "draft/metadata-2"
const val AVATAR_KEY = "avatar"

data class MetadataCapabilityLimits(
    val maxSubscriptions: Int? = null,
    val maxKeys: Int? = null,
    val maxValueBytes: Int? = null,
)

/** Parse only limits defined by the metadata draft; absent limits remain unrestricted. */
fun metadataCapabilityLimits(caps: Set<String>): MetadataCapabilityLimits? {
    val advertised =
        caps.firstOrNull { it == METADATA_CAP || it.startsWith("$METADATA_CAP=") }
            ?: return null
    if ('=' !in advertised) return MetadataCapabilityLimits()
    val values =
        advertised.substringAfter('=').split(',').associate { token ->
            token.substringBefore('=') to token.substringAfter('=', missingDelimiterValue = "")
        }

    fun numeric(name: String): Int? {
        if (name !in values) return null
        return values.getValue(name).toIntOrNull()?.coerceAtLeast(0) ?: 0
    }
    return MetadataCapabilityLimits(
        maxSubscriptions = numeric("max-subs"),
        maxKeys = numeric("max-keys"),
        maxValueBytes = numeric("max-value-bytes"),
    )
}

fun supportsMetadataSubscription(caps: Set<String>): Boolean = metadataCapabilityLimits(caps)?.let { it.maxSubscriptions != 0 } == true

fun supportsAvatarMutation(caps: Set<String>): Boolean = metadataCapabilityLimits(caps)?.let { it.maxKeys != 0 } == true

fun supportsAvatarPublishing(
    caps: Set<String>,
    url: String? = null,
): Boolean {
    val limits = metadataCapabilityLimits(caps) ?: return false
    if (!supportsAvatarMutation(caps)) return false
    val requiredBytes = (url ?: MINIMUM_AVATAR_URL).encodeToByteArray().size
    return limits.maxValueBytes?.let { it >= requiredBytes } != false
}

data class MetadataValueEvent(
    val target: String,
    val key: String,
    val value: String?,
)

sealed interface AvatarMetadataEvent {
    data class Changed(
        val target: String,
        val url: String,
    ) : AvatarMetadataEvent

    data class Removed(
        val target: String,
    ) : AvatarMetadataEvent

    data class SyncLater(
        val target: String,
        val retryAfterSeconds: Long,
    ) : AvatarMetadataEvent
}

fun subscribeMetadataMessage(key: String) = IrcMessage(command = "METADATA", params = listOf("*", "SUB", key))

fun unsubscribeMetadataMessage(key: String) = IrcMessage(command = "METADATA", params = listOf("*", "UNSUB", key))

fun syncMetadataMessage(target: String) = IrcMessage(command = "METADATA", params = listOf(target, "SYNC"))

fun publishAvatarMessage(url: String?) = metadataAvatarMessage("*", url)

fun channelAvatarMessage(
    target: String,
    url: String?,
) = metadataAvatarMessage(target, url)

private fun metadataAvatarMessage(
    target: String,
    url: String?,
) = IrcMessage(
    command = "METADATA",
    params = if (url == null) listOf(target, "SET", AVATAR_KEY) else listOf(target, "SET", AVATAR_KEY, url),
)

fun avatarMetadataRejected(response: List<IrcMessage>): Boolean =
    response.any { message ->
        message.command == "FAIL" || message.command == "ERROR" || message.command.toIntOrNull() in 764..772
    }

fun parseMetadataValue(message: IrcMessage): MetadataValueEvent? =
    when (message.command) {
        "METADATA" -> {
            MetadataValueEvent(
                target = message.params.getOrNull(0) ?: return null,
                key = message.params.getOrNull(1) ?: return null,
                value = message.params.getOrNull(3) ?: return null,
            )
        }

        "761" -> {
            MetadataValueEvent(
                target = message.params.getOrNull(1) ?: return null,
                key = message.params.getOrNull(2) ?: return null,
                value = message.params.getOrNull(4) ?: return null,
            )
        }

        "766" -> {
            message.params.getOrNull(3) ?: return null
            MetadataValueEvent(
                target = message.params.getOrNull(1) ?: return null,
                key = message.params.getOrNull(2) ?: return null,
                value = null,
            )
        }

        else -> {
            null
        }
    }

fun parseAvatarMetadata(message: IrcMessage): AvatarMetadataEvent? {
    if (message.command == "774") {
        return AvatarMetadataEvent.SyncLater(
            target = message.params.getOrNull(1) ?: return null,
            retryAfterSeconds =
                message.params
                    .getOrNull(2)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0) ?: 0,
        )
    }
    val metadata = parseMetadataValue(message)?.takeIf { it.key == AVATAR_KEY } ?: return null
    return metadata.value?.let(::validateAvatarUrl)?.let {
        AvatarMetadataEvent.Changed(metadata.target, it)
    } ?: AvatarMetadataEvent.Removed(metadata.target)
}

private const val MINIMUM_AVATAR_URL = "https://a.b"
