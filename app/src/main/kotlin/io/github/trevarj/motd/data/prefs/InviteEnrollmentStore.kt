package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.inviteEnrollmentDataStore by preferencesDataStore("invite_enrollment")
private val CHANNEL_KEYS = stringPreferencesKey("channel_keys_v1")
private val ACCOUNT_DRAFTS = stringPreferencesKey("account_drafts_v1")
private val ACCOUNT_REMINDERS = stringPreferencesKey("account_reminders_v1")
private val PROVISIONAL_NETWORKS = stringPreferencesKey("provisional_networks_v1")
private val IMPORTED_CERT_PINS = stringPreferencesKey("imported_cert_pins_v1")
private val CHANNEL_KEY_BACKUPS = stringPreferencesKey("channel_key_backups_v1")

@Serializable
enum class AccountEnrollmentProvider { IRCV3, LIBERA, OFTC }

@Serializable
enum class AccountEnrollmentPhase { PREPARED, AWAITING_VERIFICATION, ACTIVATING }

@Serializable
data class AccountEnrollmentDraft(
    val networkId: Long,
    val provider: AccountEnrollmentProvider,
    val account: String,
    val email: String? = null,
    val password: String,
    val phase: AccountEnrollmentPhase = AccountEnrollmentPhase.PREPARED,
    val verificationUrl: String? = null,
)

@Serializable
private data class StoredChannelKey(
    val networkId: Long,
    val channel: String,
    val key: String,
)

@Serializable
private data class StoredChannelKeyBackup(
    val networkId: Long,
    val channel: String,
    val prior: String? = null,
)

interface InviteEnrollmentCleanup {
    suspend fun clearNetwork(networkId: Long)
}

interface AccountReminderStore {
    val accountReminders: Flow<Set<Long>>

    suspend fun setAccountReminder(
        networkId: Long,
        enabled: Boolean,
    )
}

object NoopAccountReminderStore : AccountReminderStore {
    override val accountReminders: Flow<Set<Long>> = kotlinx.coroutines.flow.flowOf(emptySet())

    override suspend fun setAccountReminder(
        networkId: Long,
        enabled: Boolean,
    ) = Unit
}

object NoopInviteEnrollmentCleanup : InviteEnrollmentCleanup {
    override suspend fun clearNetwork(networkId: Long) = Unit
}

/** App-private transient enrollment state. Activated account credentials move into NetworkEntity. */
@Singleton
class InviteEnrollmentStore
    private constructor(
        private val store: DataStore<Preferences>,
    ) : InviteEnrollmentCleanup,
        AccountReminderStore {
        @Inject
        constructor(
            @ApplicationContext context: Context,
        ) : this(context.inviteEnrollmentDataStore)

        internal companion object {
            fun fromDataStore(store: DataStore<Preferences>) = InviteEnrollmentStore(store)
        }

        private val json = Json { ignoreUnknownKeys = true }

        suspend fun channelKey(
            networkId: Long,
            normalizedChannel: String,
        ): String? = channelKeys()[keyId(networkId, normalizedChannel)]?.key

        suspend fun putChannelKey(
            networkId: Long,
            normalizedChannel: String,
            key: String?,
        ) {
            store.edit { prefs ->
                val keys = decodeChannelKeys(prefs[CHANNEL_KEYS]).toMutableMap()
                val id = keyId(networkId, normalizedChannel)
                if (key.isNullOrEmpty()) keys.remove(id) else keys[id] = StoredChannelKey(networkId, normalizedChannel, key)
                prefs[CHANNEL_KEYS] = json.encodeToString(keys.values.toList())
            }
        }

        suspend fun prepareChannelKeyBackup(
            networkId: Long,
            normalizedChannel: String,
        ) {
            store.edit { prefs ->
                val backups = decodeChannelKeyBackups(prefs[CHANNEL_KEY_BACKUPS]).toMutableMap()
                if (networkId !in backups) {
                    val prior = decodeChannelKeys(prefs[CHANNEL_KEYS])[keyId(networkId, normalizedChannel)]?.key
                    backups[networkId] = StoredChannelKeyBackup(networkId, normalizedChannel, prior)
                    prefs[CHANNEL_KEY_BACKUPS] = json.encodeToString(backups.values.toList())
                }
            }
        }

        suspend fun restoreChannelKeyBackup(networkId: Long) {
            store.edit { prefs ->
                val backups = decodeChannelKeyBackups(prefs[CHANNEL_KEY_BACKUPS]).toMutableMap()
                val backup = backups.remove(networkId) ?: return@edit
                val keys = decodeChannelKeys(prefs[CHANNEL_KEYS]).toMutableMap()
                val id = keyId(networkId, backup.channel)
                if (backup.prior == null) keys.remove(id) else keys[id] = StoredChannelKey(networkId, backup.channel, backup.prior)
                prefs[CHANNEL_KEYS] = json.encodeToString(keys.values.toList())
                prefs[CHANNEL_KEY_BACKUPS] = json.encodeToString(backups.values.toList())
            }
        }

        suspend fun commitInvite(
            networkId: Long,
            enableAccountReminder: Boolean,
        ) {
            store.edit { prefs ->
                prefs[PROVISIONAL_NETWORKS] = (decodeReminderIds(prefs[PROVISIONAL_NETWORKS]) - networkId).sorted().joinToString(",")
                prefs[IMPORTED_CERT_PINS] = (decodeReminderIds(prefs[IMPORTED_CERT_PINS]) - networkId).sorted().joinToString(",")
                val backups = decodeChannelKeyBackups(prefs[CHANNEL_KEY_BACKUPS]).toMutableMap()
                if (backups.remove(networkId) != null) prefs[CHANNEL_KEY_BACKUPS] = json.encodeToString(backups.values.toList())
                if (enableAccountReminder) {
                    prefs[ACCOUNT_REMINDERS] = (decodeReminderIds(prefs[ACCOUNT_REMINDERS]) + networkId).sorted().joinToString(",")
                }
            }
        }

        suspend fun accountDraft(networkId: Long): AccountEnrollmentDraft? = accountDrafts()[networkId]

        suspend fun putAccountDraft(draft: AccountEnrollmentDraft) {
            store.edit { prefs ->
                val drafts = decodeDrafts(prefs[ACCOUNT_DRAFTS]).toMutableMap()
                drafts[draft.networkId] = draft
                prefs[ACCOUNT_DRAFTS] = json.encodeToString(drafts.values.toList())
            }
        }

        suspend fun clearAccountDraft(networkId: Long) {
            store.edit { prefs ->
                val drafts = decodeDrafts(prefs[ACCOUNT_DRAFTS]).toMutableMap()
                drafts.remove(networkId)
                prefs[ACCOUNT_DRAFTS] = json.encodeToString(drafts.values.toList())
            }
        }

        override val accountReminders: Flow<Set<Long>> =
            store.data.map { prefs -> decodeReminderIds(prefs[ACCOUNT_REMINDERS]) }

        suspend fun setProvisionalNetwork(
            networkId: Long,
            provisional: Boolean,
        ) {
            store.edit { prefs ->
                val ids = decodeReminderIds(prefs[PROVISIONAL_NETWORKS]).toMutableSet()
                if (provisional) ids += networkId else ids -= networkId
                prefs[PROVISIONAL_NETWORKS] = ids.sorted().joinToString(",")
            }
        }

        suspend fun isProvisionalNetwork(networkId: Long): Boolean = networkId in store.data.map { decodeReminderIds(it[PROVISIONAL_NETWORKS]) }.first()

        suspend fun setImportedCertPin(
            networkId: Long,
            imported: Boolean,
        ) {
            store.edit { prefs ->
                val ids = decodeReminderIds(prefs[IMPORTED_CERT_PINS]).toMutableSet()
                if (imported) ids += networkId else ids -= networkId
                prefs[IMPORTED_CERT_PINS] = ids.sorted().joinToString(",")
            }
        }

        suspend fun hasImportedCertPin(networkId: Long): Boolean = networkId in store.data.map { decodeReminderIds(it[IMPORTED_CERT_PINS]) }.first()

        override suspend fun setAccountReminder(
            networkId: Long,
            enabled: Boolean,
        ) {
            store.edit { prefs ->
                val ids = decodeReminderIds(prefs[ACCOUNT_REMINDERS]).toMutableSet()
                if (enabled) ids += networkId else ids -= networkId
                prefs[ACCOUNT_REMINDERS] = ids.sorted().joinToString(",")
            }
        }

        override suspend fun clearNetwork(networkId: Long) {
            store.edit { prefs ->
                prefs[CHANNEL_KEYS] = json.encodeToString(decodeChannelKeys(prefs[CHANNEL_KEYS]).values.filterNot { it.networkId == networkId })
                prefs[ACCOUNT_DRAFTS] = json.encodeToString(decodeDrafts(prefs[ACCOUNT_DRAFTS]).values.filterNot { it.networkId == networkId })
                prefs[ACCOUNT_REMINDERS] = decodeReminderIds(prefs[ACCOUNT_REMINDERS]).filterNot { it == networkId }.sorted().joinToString(",")
                prefs[PROVISIONAL_NETWORKS] = decodeReminderIds(prefs[PROVISIONAL_NETWORKS]).filterNot { it == networkId }.sorted().joinToString(",")
                prefs[IMPORTED_CERT_PINS] = decodeReminderIds(prefs[IMPORTED_CERT_PINS]).filterNot { it == networkId }.sorted().joinToString(",")
                prefs[CHANNEL_KEY_BACKUPS] = json.encodeToString(decodeChannelKeyBackups(prefs[CHANNEL_KEY_BACKUPS]).values.filterNot { it.networkId == networkId })
            }
        }

        private suspend fun channelKeys(): Map<String, StoredChannelKey> = store.data.map { decodeChannelKeys(it[CHANNEL_KEYS]) }.first()

        private suspend fun accountDrafts(): Map<Long, AccountEnrollmentDraft> = store.data.map { decodeDrafts(it[ACCOUNT_DRAFTS]) }.first()

        private fun decodeChannelKeys(raw: String?): Map<String, StoredChannelKey> =
            runCatching { json.decodeFromString<List<StoredChannelKey>>(raw.orEmpty()) }
                .getOrDefault(emptyList())
                .associateBy { keyId(it.networkId, it.channel) }

        private fun decodeChannelKeyBackups(raw: String?): Map<Long, StoredChannelKeyBackup> =
            runCatching { json.decodeFromString<List<StoredChannelKeyBackup>>(raw.orEmpty()) }
                .getOrDefault(emptyList())
                .associateBy(StoredChannelKeyBackup::networkId)

        private fun decodeDrafts(raw: String?): Map<Long, AccountEnrollmentDraft> =
            runCatching { json.decodeFromString<List<AccountEnrollmentDraft>>(raw.orEmpty()) }
                .getOrDefault(emptyList())
                .associateBy(AccountEnrollmentDraft::networkId)

        private fun decodeReminderIds(raw: String?): Set<Long> =
            raw
                .orEmpty()
                .split(',')
                .mapNotNull(String::toLongOrNull)
                .toSet()

        private fun keyId(
            networkId: Long,
            channel: String,
        ) = "$networkId\n$channel"
    }
