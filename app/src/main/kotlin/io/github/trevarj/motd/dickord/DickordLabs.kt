package io.github.trevarj.motd.dickord

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.avatar.validateAvatarUrl
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.utf8Size
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dickordLabsDataStore by preferencesDataStore("dickord_labs")
private val ENABLED = booleanPreferencesKey("enabled_v1")
private const val CHANNEL_PREFIX = "#discord."
private const val RELAY_SUFFIX = "/discord"
internal const val DICKORD_AVATAR_TAG = "+dickord/avatar"
internal const val DICKORD_CHANNEL_TAG = "+dickord/channel"
internal const val DICKORD_CHANNEL_REQUEST_TAG = "+dickord/channel-request"
private const val DICKORD_CONTROL_CHANNEL = "#discord.control"
private const val DICKORD_CHANNEL_VERSION = 1
private const val MAX_DISCORD_NAME_CODE_POINTS = 100
private const val MAX_ICON_URL_BYTES = 512
private const val MAX_DESCRIPTOR_JSON_BYTES = 2048
private val dickordChannelJson = Json { ignoreUnknownKeys = false }

internal suspend fun ConnectionManager.requestDickordChannelSnapshot(networkId: Long): Boolean {
    val client = clientFor(networkId) ?: return false
    val ready = client.state.value as? IrcClientState.Ready ?: return false
    if ("message-tags" !in ready.caps) return false
    return try {
        client.sendIfConnected(
            IrcMessage(
                tags = mapOf(DICKORD_CHANNEL_REQUEST_TAG to "1"),
                command = "TAGMSG",
                params = listOf(DICKORD_CONTROL_CHANNEL),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

@Singleton
open class DickordLabsPrefs
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val store = context.dickordLabsDataStore
        open val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: false }

        open suspend fun setEnabled(enabled: Boolean) {
            store.edit { it[ENABLED] = enabled }
        }
    }

internal val LocalDickordLabsEnabled = staticCompositionLocalOf { false }

@Serializable
data class DickordChannelDescriptor(
    val v: Int,
    @SerialName("guild_id") val guildId: String?,
    @SerialName("guild_name") val guildName: String?,
    @SerialName("channel_id") val channelId: String,
    @SerialName("channel_type") val channelType: Int,
    @SerialName("parent_id") val parentId: String?,
    @SerialName("channel_name") val channelName: String? = null,
    @SerialName("guild_icon_url") val guildIconUrl: String? = null,
    @SerialName("channel_icon_url") val channelIconUrl: String? = null,
)

internal fun decodeDickordChannelDescriptor(raw: String?): DickordChannelDescriptor? {
    if (raw == null || raw.length > MAX_DESCRIPTOR_JSON_BYTES || raw.utf8Size() > MAX_DESCRIPTOR_JSON_BYTES) return null
    val descriptor =
        runCatching {
            dickordChannelJson.decodeFromString<DickordChannelDescriptor>(raw)
        }.getOrNull() ?: return null
    if (
        descriptor.v != DICKORD_CHANNEL_VERSION ||
        !descriptor.channelId.isDiscordSnowflake() ||
        !isSupportedDiscordChannelType(descriptor.channelType) ||
        descriptor.parentId?.isDiscordSnowflake() == false ||
        (descriptor.parentId != null && !isDiscordThread(descriptor.channelType)) ||
        descriptor.channelName?.isValidDiscordName() == false ||
        descriptor.guildIconUrl?.isValidDickordIconUrl() == false ||
        descriptor.channelIconUrl?.isValidDickordIconUrl() == false
    ) {
        return null
    }
    val directMessage = isDiscordDirectMessage(descriptor.channelType)
    return descriptor.takeIf {
        if (directMessage) {
            descriptor.guildId == null &&
                descriptor.guildName == null &&
                descriptor.guildIconUrl == null &&
                (descriptor.channelType == 1 || descriptor.channelIconUrl == null)
        } else {
            descriptor.guildId?.isDiscordSnowflake() == true &&
                descriptor.guildName?.isValidDiscordName() == true &&
                descriptor.channelIconUrl == null
        }
    }
}

private fun String.isValidDiscordName(): Boolean = isNotBlank() && codePointCount(0, length) <= MAX_DISCORD_NAME_CODE_POINTS && none { it.isISOControl() }

private fun String.isValidDickordIconUrl(): Boolean = utf8Size() <= MAX_ICON_URL_BYTES && validateAvatarUrl(this) != null

private fun String.isDiscordSnowflake(): Boolean =
    length in 1..20 &&
        first() in '1'..'9' &&
        all { it in '0'..'9' } &&
        toULongOrNull() != null

private fun isSupportedDiscordChannelType(type: Int): Boolean =
    when (type) {
        0, 1, 2, 3, 5, 10, 11, 12, 13, 15, 16, 17, 18 -> true
        else -> false
    }

internal fun isDiscordDirectMessage(type: Int): Boolean = type == 1 || type == 3 || type == 18

private fun isDiscordThread(type: Int): Boolean = type == 10 || type == 11 || type == 12

internal fun isDickordChannel(name: String): Boolean = name.startsWith(CHANNEL_PREFIX, ignoreCase = true)

internal fun isDickordPortalConversation(
    type: BufferType,
    rawName: String,
): Boolean =
    type == BufferType.CHANNEL &&
        isDickordChannel(rawName) &&
        !rawName.equals(DICKORD_CONTROL_CHANNEL, ignoreCase = true)

internal fun isDickordRelayNick(nick: String): Boolean = nick.endsWith(RELAY_SUFFIX)

internal fun dickordChannelLabel(
    name: String,
    enabled: Boolean,
): String = if (enabled && isDickordChannel(name)) "#${name.drop(CHANNEL_PREFIX.length)}" else name

internal fun dickordNickLabel(
    nick: String,
    enabled: Boolean,
): String = if (enabled && isDickordRelayNick(nick)) nick.dropLast(RELAY_SUFFIX.length) else nick
