package io.github.trevarj.motd.attachment

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private val Context.attachmentDataStore by preferencesDataStore("attachments")
private val CONFIG = stringPreferencesKey("config_v1")
private val HISTORY = stringPreferencesKey("history_v1")

@Singleton
class AttachmentPrefsImpl
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AttachmentPrefs {
        private val store = context.attachmentDataStore
        private val json = Json { ignoreUnknownKeys = true }

        override val config: Flow<PasteBackendConfig> =
            store.data.map { prefs ->
                prefs[CONFIG]?.let(::decodeConfig) ?: PasteBackendConfig()
            }
        override val recentUploads: Flow<List<UploadRecord>> =
            store.data.map { prefs ->
                prefs[HISTORY]?.let(::decodeHistory).orEmpty()
            }

        override suspend fun setConfig(config: PasteBackendConfig) {
            store.edit { it[CONFIG] = encodeConfig(normalizedConfig(config)) }
        }

        override suspend fun updateConfig(transform: (PasteBackendConfig) -> PasteBackendConfig) {
            store.edit { prefs ->
                val current = prefs[CONFIG]?.let(::decodeConfig) ?: PasteBackendConfig()
                prefs[CONFIG] = encodeConfig(normalizedConfig(transform(current)))
            }
        }

        override suspend fun addUpload(record: UploadRecord) {
            store.edit { prefs ->
                val records = prefs[HISTORY]?.let(::decodeHistory) ?: mutableListOf()
                val index = records.indexOfFirst { it.url == record.url }
                val favorite = if (index < 0) record.favorite else records.removeAt(index).favorite
                records.add(0, if (record.favorite == favorite) record else record.copy(favorite = favorite))
                if (!favorite) records.trimRecents()
                prefs[HISTORY] = encodeHistory(records)
            }
        }

        override suspend fun setUploadFavorite(
            url: String,
            favorite: Boolean,
        ) {
            store.edit { prefs ->
                val records = prefs[HISTORY]?.let(::decodeHistory) ?: return@edit
                val index = records.indexOfFirst { it.url == url }
                if (index < 0 || records[index].favorite == favorite) return@edit
                records[index] = records[index].copy(favorite = favorite)
                if (!favorite) records.trimRecents()
                prefs[HISTORY] = encodeHistory(records)
            }
        }

        override suspend fun updateUploadPreview(
            record: UploadRecord,
            preview: LocalUploadPreview,
        ) {
            val receiptId = record.receiptId ?: return
            store.edit { prefs ->
                val records = prefs[HISTORY]?.let(::decodeHistory) ?: return@edit
                val index = records.indexOfFirst { it.url == record.url && it.receiptId == receiptId }
                if (index == -1) return@edit
                records[index] = records[index].copy(preview = preview)
                prefs[HISTORY] = encodeHistory(records)
            }
        }

        override suspend fun removeUpload(url: String) {
            store.edit { prefs ->
                val records = prefs[HISTORY]?.let(::decodeHistory) ?: return@edit
                if (records.removeAll { it.url == url }) prefs[HISTORY] = encodeHistory(records)
            }
        }

        override suspend fun clearUploadHistory() {
            store.edit { prefs ->
                val records = prefs[HISTORY]?.let(::decodeHistory) ?: return@edit
                records.removeAll { !it.favorite }
                if (records.isEmpty()) prefs.remove(HISTORY) else prefs[HISTORY] = encodeHistory(records)
            }
        }

        private fun encodeConfig(c: PasteBackendConfig) =
            buildJsonObject {
                put("backend", c.backend.name)
                put("protocol", c.protocol.name)
                put("endpoint", c.endpoint)
                put("customEndpoint", c.customEndpoint)
                put("expiry", c.expiry)
                put("litterboxExpiry", c.litterboxExpiry)
                put("secret", c.secretUrl)
                put("limit", c.sizeLimitBytes)
                put("username", c.username)
                put("password", c.password)
                put("stripMetadata", c.stripMetadata)
                put("stripHostedImageMetadata", c.stripHostedImageMetadata)
            }.toString()

        private fun decodeConfig(raw: String) =
            runCatching {
                val o = json.parseToJsonElement(raw).jsonObject
                val endpoint = o["endpoint"]?.jsonPrimitive?.content ?: EndpointPreset.CRAFTERBIN.endpoint!!
                val backend =
                    o["backend"]?.jsonPrimitive?.content?.let { value ->
                        if (value == "MICROBIN") {
                            AttachmentBackend.CUSTOM_0X0
                        } else {
                            runCatching { AttachmentBackend.valueOf(value) }.getOrNull()
                        }
                    } ?: legacyAttachmentBackend(o["protocol"]?.jsonPrimitive?.content, endpoint)
                val stripMetadata = (o["stripMetadata"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                normalizedConfig(
                    PasteBackendConfig(
                        backend = backend,
                        endpoint = endpoint,
                        customEndpoint = o["customEndpoint"]?.jsonPrimitive?.content ?: endpoint,
                        expiry = o["expiry"]?.jsonPrimitive?.contentOrNull,
                        litterboxExpiry =
                            o["litterboxExpiry"]?.jsonPrimitive?.content
                                ?: DEFAULT_LITTERBOX_EXPIRY,
                        secretUrl = o["secret"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                        sizeLimitBytes = o["limit"]?.jsonPrimitive?.longOrNull ?: DEFAULT_PUBLIC_LIMIT_BYTES,
                        username = o["username"]?.jsonPrimitive?.content.orEmpty(),
                        password = o["password"]?.jsonPrimitive?.content.orEmpty(),
                        stripMetadata = stripMetadata ?: true,
                        stripHostedImageMetadata =
                            when (val value = o["stripHostedImageMetadata"]) {
                                JsonNull -> null
                                is JsonPrimitive -> value.takeUnless { it.isString }?.booleanOrNull ?: stripMetadata
                                else -> stripMetadata
                            },
                    ),
                )
            }.getOrDefault(PasteBackendConfig())

        private fun encodeHistory(records: List<UploadRecord>) =
            buildJsonArray {
                records.forEach { r ->
                    add(
                        buildJsonObject {
                            put("url", r.url)
                            put("backend", r.backend.name)
                            put("name", r.displayName)
                            put("mime", r.mimeType)
                            put("size", r.sizeBytes)
                            put("at", r.uploadedAt)
                            put("token", r.deletionToken)
                            put("endpoint", r.endpoint)
                            r.receiptId?.let { put("receiptId", it) }
                            if (r.favorite) put("favorite", true)
                            r.preview?.takeIf { it.isBounded() }?.let {
                                put("preview", json.encodeToJsonElement(LocalUploadPreview.serializer(), it))
                            }
                        },
                    )
                }
            }.toString()

        private fun decodeHistory(raw: String): MutableList<UploadRecord>? {
            val elements = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return null
            val byUrl = linkedMapOf<String, UploadRecord>()
            for (element in elements) {
                val row = element as? JsonObject ?: continue
                val url = (row["url"] as? JsonPrimitive)?.contentOrNull ?: continue
                val favorite = (row["favorite"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
                val newest = byUrl[url]
                // Keep the newest receipt, but do not lose a star from an older duplicate.
                if (newest != null) {
                    if (favorite && !newest.favorite) byUrl[url] = newest.copy(favorite = true)
                    continue
                }
                val endpoint = (row["endpoint"] as? JsonPrimitive)?.contentOrNull
                val backendValue = (row["backend"] as? JsonPrimitive)?.contentOrNull
                val backend =
                    backendValue?.let { runCatching { AttachmentBackend.valueOf(it) }.getOrNull() }
                        ?: legacyAttachmentBackend(backendValue, endpoint)
                byUrl[url] =
                    UploadRecord(
                        url,
                        backend,
                        (row["name"] as? JsonPrimitive)?.contentOrNull ?: url,
                        (row["mime"] as? JsonPrimitive)?.contentOrNull,
                        (row["size"] as? JsonPrimitive)?.longOrNull,
                        (row["at"] as? JsonPrimitive)?.longOrNull ?: 0,
                        (row["token"] as? JsonPrimitive)?.contentOrNull,
                        endpoint,
                        row["preview"]?.let { encoded ->
                            runCatching { json.decodeFromJsonElement(LocalUploadPreview.serializer(), encoded) }
                                .getOrNull()
                                ?.takeIf { it.isBounded() }
                        },
                        receiptId = (row["receiptId"] as? JsonPrimitive)?.contentOrNull,
                        favorite = favorite,
                    )
            }
            if (elements.isNotEmpty() && byUrl.isEmpty()) return null
            return byUrl.values.toMutableList().apply { trimRecents() }
        }

        private fun MutableList<UploadRecord>.trimRecents() {
            var recents = 0
            removeAll { !it.favorite && ++recents > MAX_UPLOAD_HISTORY }
        }
    }

internal fun legacyAttachmentBackend(
    protocol: String?,
    endpoint: String?,
): AttachmentBackend =
    when (protocol) {
        PasteProtocol.TERMBIN.name -> {
            AttachmentBackend.TERMBIN
        }

        else -> {
            when (endpoint?.trimEnd('/')) {
                EndpointPreset.CRAFTERBIN.endpoint -> AttachmentBackend.CRAFTERBIN
                EndpointPreset.ZERO_X_ZERO.endpoint -> AttachmentBackend.ZERO_X_ZERO
                else -> AttachmentBackend.CUSTOM_0X0
            }
        }
    }
