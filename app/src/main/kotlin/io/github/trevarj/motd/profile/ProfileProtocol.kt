package io.github.trevarj.motd.profile

import io.github.trevarj.motd.avatar.METADATA_CAP
import io.github.trevarj.motd.avatar.METADATA_V3_CAP
import io.github.trevarj.motd.avatar.MetadataValueEvent
import io.github.trevarj.motd.avatar.isMetadataTarget
import io.github.trevarj.motd.avatar.parseMetadataValue
import io.github.trevarj.motd.avatar.validateAvatarUrl
import io.github.trevarj.motd.irc.proto.IrcMessage
import java.net.URI

// Preference order for subscriptions (the separate avatar coordinator always goes first).
enum class ProfileKey(
    val wireName: String,
) {
    AVATAR("avatar"),
    DISPLAY_NAME("display-name"),
    STATUS("status"),
    PRONOUNS("pronouns"),
    BOT("bot"),
    COLOR("color"),
    HOMEPAGE("homepage"),
}

sealed interface ProfileTarget {
    data class Self(
        val networkId: Long,
    ) : ProfileTarget

    data class Channel(
        val bufferId: Long,
    ) : ProfileTarget
}

enum class ProfileAccess { OFFLINE, UNSUPPORTED, READ_ONLY, WRITABLE }

data class ProfileEditorState(
    val identity: String,
    val networkId: Long?,
    val access: ProfileAccess,
    val values: Map<ProfileKey, String>,
    val networkName: String? = null,
    val sojuFileHostUrl: String? = null,
    val readable: Boolean = access == ProfileAccess.READ_ONLY || access == ProfileAccess.WRITABLE,
    internal val normalizedIdentity: String = identity,
)

enum class ProfileWriteResult { CONFIRMED, UNCONFIRMED, REJECTED, INVALID, READ_ONLY, OFFLINE, UNSUPPORTED }

internal val channelProfileKeys = listOf(ProfileKey.AVATAR, ProfileKey.DISPLAY_NAME)
private val color = Regex("#[0-9A-Fa-f]{6}")
internal val profileKeysByName = ProfileKey.entries.associateBy(ProfileKey::wireName)

internal fun validProfileValue(
    key: ProfileKey,
    value: String,
): String? {
    if (value.any(Char::isISOControl) || !Charsets.UTF_8.newEncoder().canEncode(value)) return null
    return when (key) {
        ProfileKey.AVATAR -> {
            validateAvatarUrl(value)
        }

        ProfileKey.HOMEPAGE -> {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            value.takeIf {
                (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
                    !uri.host.isNullOrEmpty() && uri.userInfo == null && (uri.port == -1 || uri.port in 1..65535)
            }
        }

        ProfileKey.COLOR -> {
            value.takeIf(color::matches)
        }

        else -> {
            value
        }
    }
}

internal data class ProfileValue(
    val target: String,
    val key: ProfileKey,
    val value: String?,
)

/** Accept numerics addressed to the live self nick at read time, never to a user's supplied target. */
internal fun profileValue(
    message: IrcMessage,
    version: String,
    selfNick: String,
    normalize: (String) -> String,
): ProfileValue? = profileWireValue(message, version, selfNick, normalize)?.validated(version)

internal fun ProfileValue.validated(version: String): ProfileValue? {
    val raw = value ?: return this
    if (version == METADATA_V3_CAP && raw.isEmpty()) return null
    val typed = validProfileValue(key, raw) ?: return null
    return if (typed == raw) this else copy(value = typed)
}

internal fun profileWireValue(
    message: IrcMessage,
    version: String,
    selfNick: String,
    normalize: (String) -> String,
): ProfileValue? {
    val wireNick = message.selfNickAtRead ?: selfNick
    when (message.command) {
        "761", "766" -> {
            if (message.params.size != (if (message.command == "761") 5 else 4) ||
                message.source?.user != null || message.source?.host != null ||
                (
                    normalize(message.params[0]) != normalize(wireNick) &&
                        !(version == METADATA_V3_CAP && message.params[0] == "*")
                )
            ) {
                return null
            }
        }

        "METADATA" -> {
            if (version != METADATA_CAP || message.params.size !in 3..4) return null
        }

        else -> {
            return null
        }
    }
    val value =
        if (message.command == "METADATA" && message.params.size == 3) {
            MetadataValueEvent(message.params[0], message.params[1], null)
        } else {
            parseMetadataValue(message) ?: return null
        }
    if (!isMetadataTarget(value.target) || value.target == "*ALL") return null
    val key = profileKeysByName[value.key] ?: return null
    return ProfileValue(if (value.target == "*") wireNick else value.target, key, value.value)
}

internal fun metadataSet(
    target: String,
    key: ProfileKey,
    value: String?,
) = IrcMessage(command = "METADATA", params = listOfNotNull(target, "SET", key.wireName, value))

internal fun profileWireFits(message: IrcMessage): Boolean =
    try {
        message.serialize()
        true
    } catch (_: IllegalArgumentException) {
        false
    }

internal fun isProfileSetFailure(
    message: IrcMessage,
    key: ProfileKey,
    target: String,
    self: Boolean,
    normalize: (String) -> String,
): Boolean {
    val args = message.params
    if (message.command != "FAIL" || args.firstOrNull() != "METADATA" ||
        message.source?.user != null || message.source?.host != null
    ) {
        return false
    }

    fun isTarget(value: String?) = (value == "*" && self) || value != null && normalize(value) == normalize(target)
    return when (args.getOrNull(1)) {
        "KEY_NO_PERMISSION", "RATE_LIMITED" -> isTarget(args.getOrNull(2)) && args.getOrNull(3) == key.wireName
        "KEY_INVALID", "INVALID_KEY", "VALUE_INVALID", "INVALID_VALUE", "KEY_NOT_SET" -> args.getOrNull(2) == key.wireName
        "INVALID_TARGET", "TARGET_INVALID", "LIMIT_REACHED" -> isTarget(args.getOrNull(2))
        "INVALID_PARAMS" -> args.getOrNull(2) == "SET"
        else -> false
    }
}
