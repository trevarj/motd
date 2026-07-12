package io.github.trevarj.motd.attachment

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val Context.attachmentDataStore by preferencesDataStore("attachments")
private val CONFIG = stringPreferencesKey("config_v1")
private val HISTORY = stringPreferencesKey("history_v1")

@Singleton
class AttachmentPrefsImpl @Inject constructor(
    @ApplicationContext context: Context,
) : AttachmentPrefs {
    private val store = context.attachmentDataStore
    private val json = Json { ignoreUnknownKeys = true }

    override val config: Flow<PasteBackendConfig> = store.data.map { prefs ->
        prefs[CONFIG]?.let(::decodeConfig) ?: PasteBackendConfig()
    }
    override val recentUploads: Flow<List<UploadRecord>> = store.data.map { prefs ->
        prefs[HISTORY]?.let(::decodeHistory).orEmpty()
    }

    override suspend fun setConfig(config: PasteBackendConfig) {
        store.edit { it[CONFIG] = encodeConfig(normalizedConfig(config)) }
    }

    override suspend fun addUpload(record: UploadRecord) {
        store.edit { prefs ->
            val records = (prefs[HISTORY]?.let(::decodeHistory).orEmpty())
                .filterNot { it.url == record.url }.let { listOf(record) + it }.take(MAX_UPLOAD_HISTORY)
            prefs[HISTORY] = encodeHistory(records)
        }
    }

    override suspend fun removeUpload(url: String) {
        store.edit { prefs -> prefs[HISTORY] = encodeHistory(prefs[HISTORY]?.let(::decodeHistory).orEmpty().filterNot { it.url == url }) }
    }

    private fun encodeConfig(c: PasteBackendConfig) = buildJsonObject {
        put("protocol", c.protocol.name); put("endpoint", c.endpoint); put("expiry", c.expiry)
        put("secret", c.secretUrl); put("limit", c.sizeLimitBytes)
    }.toString()

    private fun decodeConfig(raw: String) = runCatching {
        val o = json.parseToJsonElement(raw).jsonObject
        normalizedConfig(PasteBackendConfig(
            protocol = o["protocol"]?.jsonPrimitive?.content?.let(PasteProtocol::valueOf) ?: PasteProtocol.MULTIPART_0X0,
            endpoint = o["endpoint"]?.jsonPrimitive?.content ?: EndpointPreset.CRAFTERBIN.endpoint!!,
            expiry = o["expiry"]?.jsonPrimitive?.contentOrNull,
            secretUrl = o["secret"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            sizeLimitBytes = o["limit"]?.jsonPrimitive?.longOrNull ?: DEFAULT_PUBLIC_LIMIT_BYTES,
        ))
    }.getOrDefault(PasteBackendConfig())

    private fun encodeHistory(records: List<UploadRecord>) = buildJsonArray {
        records.forEach { r -> add(buildJsonObject {
            put("url", r.url); put("backend", r.backend.name); put("name", r.displayName)
            put("mime", r.mimeType); put("size", r.sizeBytes); put("at", r.uploadedAt)
            put("token", r.deletionToken); put("endpoint", r.endpoint)
        }) }
    }.toString()

    private fun decodeHistory(raw: String): List<UploadRecord> = runCatching {
        json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val o = element.jsonObject
            val url = o["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            UploadRecord(url, PasteProtocol.valueOf(o["backend"]!!.jsonPrimitive.content),
                o["name"]?.jsonPrimitive?.content ?: url, o["mime"]?.jsonPrimitive?.contentOrNull,
                o["size"]?.jsonPrimitive?.longOrNull, o["at"]?.jsonPrimitive?.longOrNull ?: 0,
                o["token"]?.jsonPrimitive?.contentOrNull, o["endpoint"]?.jsonPrimitive?.contentOrNull)
        }.take(MAX_UPLOAD_HISTORY)
    }.getOrDefault(emptyList())
}
