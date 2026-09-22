package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
enum class ChatSoundVoice(
    val assetId: String,
) {
    SOFT_GLASS("soft-glass"),
    TERMINAL_TICK("terminal-tick"),
    ARCADE_PLUCK("arcade-pluck"),
    SYNTH_16_BIT("16bit-synth"),
}

@Serializable
enum class ChatSoundTone { WARM, BALANCED, BRIGHT }

@Serializable
enum class ChatSoundVariation { MUSICAL, NATURAL, FIXED }

@Serializable
enum class ChatSoundMelody { HOMECOMING, CLIMB, RELAY, BEACON, VICTORY }

@Serializable
data class ChatSoundCueConfig(
    val voice: ChatSoundVoice = ChatSoundVoice.SOFT_GLASS,
    val enabled: Boolean = true,
    val volume: Int = 100,
    val pitch: Int = 0,
    val tone: ChatSoundTone = ChatSoundTone.BALANCED,
)

@Serializable
data class ChatSoundConfig(
    val version: Int = 1,
    val masterVolume: Int = 70,
    val variation: ChatSoundVariation = ChatSoundVariation.MUSICAL,
    val send: ChatSoundCueConfig = ChatSoundCueConfig(),
    val receive: ChatSoundCueConfig = ChatSoundCueConfig(),
    val receiveMelody: ChatSoundMelody = ChatSoundMelody.HOMECOMING,
)

internal fun ChatSoundConfig.normalized(): ChatSoundConfig =
    copy(
        version = 1,
        masterVolume = masterVolume.coerceIn(0, 100),
        send = send.copy(volume = send.volume.coerceIn(0, 100), pitch = send.pitch.coerceIn(-4, 4)),
        receive = receive.copy(volume = receive.volume.coerceIn(0, 100), pitch = receive.pitch.coerceIn(-4, 4)),
    )

private val Context.chatSoundDataStore by preferencesDataStore("chat_sounds")
private val CHAT_SOUND_CONFIG = stringPreferencesKey("config_v1")

/** Independent from the legacy master switch so upgrading never changes its enabled state. */
@Singleton
class ChatSoundPrefs
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val store = context.chatSoundDataStore
        private val json =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
                coerceInputValues = true
            }

        val config: Flow<ChatSoundConfig> =
            store.data.map { prefs ->
                prefs[CHAT_SOUND_CONFIG]?.let(::decode)?.getOrNull()?.normalized() ?: ChatSoundConfig()
            }

        suspend fun replace(config: ChatSoundConfig) {
            store.edit { it[CHAT_SOUND_CONFIG] = json.encodeToString(config.normalized()) }
        }

        suspend fun update(transform: (ChatSoundConfig) -> ChatSoundConfig) {
            store.edit { prefs ->
                val current = prefs[CHAT_SOUND_CONFIG]?.let(::decode)?.getOrNull()?.normalized() ?: ChatSoundConfig()
                prefs[CHAT_SOUND_CONFIG] = json.encodeToString(transform(current).normalized())
            }
        }

        private fun decode(raw: String): Result<ChatSoundConfig> = runCatching { json.decodeFromString<ChatSoundConfig>(raw) }
    }
