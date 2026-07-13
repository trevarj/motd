package io.github.trevarj.motd.avatar

import kotlinx.coroutines.flow.Flow

sealed interface SelfAvatarSetting {
    data object Unmanaged : SelfAvatarSetting
    data object ExplicitlyCleared : SelfAvatarSetting
    data class Set(val url: String) : SelfAvatarSetting
}

data class AvatarConfig(
    val showSharedAvatars: Boolean = true,
)

data class AvatarRecord(
    val networkId: Long,
    val identity: String,
    val nick: String,
    val account: String?,
    val url: String,
    val updatedAt: Long,
)

interface AvatarPrefs {
    val config: Flow<AvatarConfig>
    fun selfSetting(networkId: Long): Flow<SelfAvatarSetting>
    suspend fun setShowSharedAvatars(show: Boolean)
    suspend fun setSelfSetting(networkId: Long, setting: SelfAvatarSetting)
}

interface AvatarStore {
    val records: Flow<List<AvatarRecord>>
    suspend fun upsert(networkId: Long, nick: String, account: String?, url: String)
    suspend fun remove(networkId: Long, nick: String, account: String? = null)
    suspend fun rename(networkId: Long, oldNick: String, newNick: String, account: String?)
    suspend fun clearNetwork(networkId: Long)
    suspend fun clearAll()
}

fun validateAvatarUrl(raw: String): String? {
    val value = raw.trim()
    val probe = value.replace("{size}", "64")
    val uri = runCatching { java.net.URI(probe) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
    if (uri.userInfo != null) return null
    return value
}

fun expandAvatarUrl(url: String, sizePx: Int): String? =
    validateAvatarUrl(url)?.replace("{size}", sizePx.coerceIn(16, 512).toString())

fun avatarIdentity(nick: String, account: String?): String =
    account?.takeUnless { it == "*" }?.let { "account:${it.lowercase()}" }
        ?: "nick:${canonicalAvatarNick(nick)}"

/** Default IRC casemapping fallback; account identity takes precedence whenever available. */
fun canonicalAvatarNick(nick: String): String = buildString(nick.length) {
    for (char in nick.trim().lowercase()) append(
        when (char) {
            '{' -> '['
            '}' -> ']'
            '|' -> '\\'
            '~' -> '^'
            else -> char
        },
    )
}
