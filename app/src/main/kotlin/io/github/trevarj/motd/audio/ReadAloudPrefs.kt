package io.github.trevarj.motd.audio

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
data class ReadAloudConfig(
    val voice: String? = null,
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val gapMs: Int = 350,
) {
    fun normalized(): ReadAloudConfig =
        copy(
            rate = rate.takeIf(Float::isFinite)?.coerceIn(.7f, 1.3f) ?: 1f,
            pitch = pitch.takeIf(Float::isFinite)?.coerceIn(.7f, 1.3f) ?: 1f,
            gapMs = gapMs.coerceIn(0, 1_000),
        )
}

private val Context.readAloudDataStore by preferencesDataStore("read_aloud")
private val CONFIG = stringPreferencesKey("config_v1")

/** Device-local voice choices only. The opt-in session is deliberately never persisted. */
@Singleton
class ReadAloudPrefs
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val store = context.readAloudDataStore
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        val systemConfig: Flow<ReadAloudConfig> = store.data.map { decode(it[CONFIG]) }

        suspend fun replaceSystem(
            config: ReadAloudConfig,
            expectedConfig: ReadAloudConfig? = null,
        ) {
            store.edit {
                check(expectedConfig == null || decode(it[CONFIG]) == expectedConfig) {
                    "Voice options changed. Open them again."
                }
                it[CONFIG] = json.encodeToString(config.normalized())
            }
        }

        private fun decode(raw: String?): ReadAloudConfig =
            raw
                ?.let { runCatching { json.decodeFromString<ReadAloudConfig>(it) }.getOrNull() }
                ?.normalized() ?: ReadAloudConfig()
    }
