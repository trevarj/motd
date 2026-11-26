package io.github.trevarj.motd.avatar

import io.github.trevarj.motd.irc.proto.IrcMessage

const val METADATA_CAP = "draft/metadata-2"
internal const val METADATA_V3_CAP = "draft/metadata-3"
const val AVATAR_KEY = "avatar"

data class MetadataCapabilityLimits(
    val maxSubscriptions: Int? = null,
    val maxKeys: Int? = null,
    val maxValueBytes: Int? = null,
    val maxKeyBytes: Int? = null,
)

internal fun metadataVersion(caps: Set<String>): String? =
    when {
        caps.any { it.substringBefore('=') == "batch" } && caps.any { it.substringBefore('=') == METADATA_V3_CAP } -> METADATA_V3_CAP
        caps.any { it.substringBefore('=') == METADATA_CAP } -> METADATA_CAP
        else -> null
    }

/** Parse only limits defined by the metadata draft; absent limits remain unrestricted. */
fun metadataCapabilityLimits(caps: Set<String>): MetadataCapabilityLimits? {
    val version = metadataVersion(caps) ?: return null
    val advertised = caps.first { it.substringBefore('=') == version }
    if ('=' !in advertised) return MetadataCapabilityLimits()
    val values =
        advertised.substringAfter('=').split(',').associate { token ->
            token.substringBefore('=') to token.substringAfter('=', missingDelimiterValue = "")
        }

    fun numeric(name: String): Int? {
        val raw = values[name] ?: return null
        return raw.toIntOrNull()?.coerceAtLeast(0)
            ?: if (version == METADATA_V3_CAP && raw.isUnsignedDecimal()) Int.MAX_VALUE else 0
    }
    return MetadataCapabilityLimits(
        maxSubscriptions = numeric("max-subs"),
        maxKeys = numeric("max-keys"),
        maxValueBytes = numeric("max-value-bytes"),
        maxKeyBytes = if (version == METADATA_V3_CAP) numeric("max-key-bytes") else null,
    )
}

fun supportsMetadataSubscription(caps: Set<String>): Boolean = metadataCapabilityLimits(caps)?.let { it.maxSubscriptions != 0 } == true

fun supportsAvatarMutation(caps: Set<String>): Boolean =
    metadataCapabilityLimits(caps)?.let {
        it.allowsAvatarKey && (metadataVersion(caps) == METADATA_V3_CAP || it.maxKeys != 0)
    } == true

private val MetadataCapabilityLimits.allowsAvatarKey: Boolean
    get() = maxKeyBytes?.let { it >= AVATAR_KEY.length } != false

fun supportsAvatarPublishing(
    caps: Set<String>,
    url: String? = null,
): Boolean = supportsAvatarPublishing(caps, url, self = true)

internal fun supportsAvatarPublishing(
    caps: Set<String>,
    url: String?,
    self: Boolean,
): Boolean {
    val limits = metadataCapabilityLimits(caps) ?: return false
    if (!limits.allowsAvatarKey || (limits.maxKeys == 0 && (self || metadataVersion(caps) == METADATA_CAP))) return false
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
        message.command == "FAIL" || message.command == "ERROR" ||
            (message.command != "766" && message.command.toIntOrNull() in 764..772)
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

fun parseAvatarMetadata(message: IrcMessage): AvatarMetadataEvent? = parseAvatarMetadata(message, v3 = false)

internal fun parseAvatarMetadata(
    message: IrcMessage,
    v3: Boolean,
    selfNick: String? = null,
): AvatarMetadataEvent? {
    if (message.command == "774") {
        val target = message.params.getOrNull(1) ?: return null
        if (!isMetadataTarget(target)) return null
        val delay =
            if (v3) {
                if (message.params.size != 4) return null
                metadataRetrySeconds(message.params[2]) ?: return null
            } else {
                message.params
                    .getOrNull(2)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0) ?: 0
            }
        return AvatarMetadataEvent.SyncLater(target, delay)
    }
    if (v3) {
        when (message.command) {
            "761" -> if (message.params.size != 5) return null
            "766" -> if (message.params.size != 4) return null
            else -> return null
        }
        if (message.params[1] == "*ALL" || !isMetadataTarget(message.params[1])) return null
    }
    val metadata = parseMetadataValue(message)?.takeIf { it.key == AVATAR_KEY } ?: return null
    val target = if (v3 && metadata.target == "*") selfNick ?: return null else metadata.target
    return metadata.value?.let(::validateAvatarUrl)?.let {
        AvatarMetadataEvent.Changed(target, it)
    } ?: AvatarMetadataEvent.Removed(target)
}

internal fun isMetadataTarget(target: String): Boolean =
    target.isNotEmpty() && target.first() != ':' && target.none { it <= ' ' || it == ',' } &&
        (!target.startsWith('*') || target == "*" || target == "*ALL")

internal fun metadataRetrySeconds(raw: String): Long? = raw.takeIf(String::isUnsignedDecimal)?.toLongOrNull()

private fun String.isUnsignedDecimal(): Boolean = isNotEmpty() && all { it in '0'..'9' }

private const val MINIMUM_AVATAR_URL = "https://a.b"
