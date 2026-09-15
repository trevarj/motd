package io.github.trevarj.motd.service

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Offered watch durations; [millis] null means forever. [tag] is the stable UI test-tag suffix. */
enum class ChannelWatchDuration(
    val millis: Long?,
    val tag: String,
) {
    MIN_15(15 * 60 * 1000L, "15"),
    MIN_30(30 * 60 * 1000L, "30"),
    MIN_60(60 * 60 * 1000L, "60"),
    FOREVER(null, "forever"),
}

data class ChannelWatchState(
    val bufferId: Long,
    val expiresAt: Long,
)

/** A forever watch is stored as a sentinel expiry and never runs an expiry timer. */
val ChannelWatchState.isForever: Boolean get() = expiresAt == Long.MAX_VALUE

enum class NotificationMode { ALL, MENTIONS, OFF }

enum class NotificationScope { GLOBAL, SERVER, CHANNEL }

@Serializable
data class NotificationConfig(
    val global: NotificationMode = NotificationMode.MENTIONS,
    val servers: Map<Long, NotificationMode> = emptyMap(),
    val channels: Map<Long, NotificationMode> = emptyMap(),
    val watches: Map<Long, Long> = emptyMap(),
)

data class ResolvedNotificationSettings(
    val mode: NotificationMode,
    val source: NotificationScope,
    val watch: ChannelWatchState?,
)

sealed interface NotificationSettingsState {
    data object Loading : NotificationSettingsState

    data class Ready(
        val config: NotificationConfig,
    ) : NotificationSettingsState

    data object Unavailable : NotificationSettingsState
}

interface NotificationSettings {
    val state: StateFlow<NotificationSettingsState>

    suspend fun resolve(
        networkId: Long,
        bufferId: Long,
    ): ResolvedNotificationSettings?

    suspend fun retryLoad(): Boolean

    suspend fun setGlobal(mode: NotificationMode): Boolean

    suspend fun setServer(
        networkId: Long,
        mode: NotificationMode?,
    ): Boolean

    suspend fun setChannel(
        bufferId: Long,
        mode: NotificationMode?,
    ): Boolean

    /** [durationMs] null watches forever. */
    suspend fun startWatch(
        bufferId: Long,
        durationMs: Long?,
    ): Boolean

    suspend fun stopWatch(bufferId: Long): Boolean

    object Noop : NotificationSettings {
        override val state: StateFlow<NotificationSettingsState> = MutableStateFlow(NotificationSettingsState.Ready(NotificationConfig()))

        override suspend fun resolve(
            networkId: Long,
            bufferId: Long,
        ) = ResolvedNotificationSettings(NotificationMode.MENTIONS, NotificationScope.GLOBAL, null)

        override suspend fun retryLoad(): Boolean = false

        override suspend fun setGlobal(mode: NotificationMode): Boolean = false

        override suspend fun setServer(
            networkId: Long,
            mode: NotificationMode?,
        ): Boolean = false

        override suspend fun setChannel(
            bufferId: Long,
            mode: NotificationMode?,
        ): Boolean = false

        override suspend fun startWatch(
            bufferId: Long,
            durationMs: Long?,
        ): Boolean = false

        override suspend fun stopWatch(bufferId: Long): Boolean = false
    }
}

/** Internal commit-boundary signal; notification policy remains on [NotificationSettings]. */
internal interface NotificationRoomMergeListener {
    suspend fun onRoomsMerged(
        winnerId: Long,
        loserId: Long,
    )

    object Noop : NotificationRoomMergeListener {
        override suspend fun onRoomsMerged(
            winnerId: Long,
            loserId: Long,
        ) = Unit
    }
}

private val Context.notificationSettingsDataStore by preferencesDataStore("channel_watch")
private val CONFIG = stringPreferencesKey("config_v1")
private val BUFFER_ID = longPreferencesKey("buffer_id")
private val EXPIRES_AT = longPreferencesKey("expires_at")
private val notificationJson = Json { ignoreUnknownKeys = true }

@Singleton
internal class NotificationSettingsImpl(
    private val scope: CoroutineScope,
    private val clock: AppClock,
    private val onExpired: suspend (Long) -> Unit,
    private val load: suspend () -> Preferences = { emptyPreferences() },
    private val save: suspend (NotificationConfig) -> Unit = {},
    private val resolveBufferId: suspend (Long) -> Long? = { it },
    private val observeBufferId: (Long) -> Flow<Long?> = { flowOf(it) },
    private val serverExists: suspend (Long) -> Boolean = { true },
    private val observeServerIds: Flow<Set<Long>> = emptyFlow(),
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
) : NotificationSettings,
    NotificationRoomMergeListener {
    @Inject
    constructor(
        @ApplicationScope scope: CoroutineScope,
        @ApplicationContext context: Context,
        clock: AppClock,
        notifications: MotdNotifications,
        bufferDao: BufferDao,
        networkDao: NetworkDao,
        diagnostics: DiagnosticLogger,
    ) : this(
        scope = scope,
        clock = clock,
        onExpired = { notifications.watchEnded(it) },
        load = { context.notificationSettingsDataStore.data.first() },
        save = { config ->
            context.notificationSettingsDataStore.edit { prefs ->
                prefs[CONFIG] = notificationJson.encodeToString(config)
                prefs.remove(BUFFER_ID)
                prefs.remove(EXPIRES_AT)
            }
        },
        resolveBufferId = { id ->
            bufferDao.canonicalId(id)?.let { canonicalId ->
                bufferDao.observeById(canonicalId)?.takeIf { it.type == BufferType.CHANNEL }?.id
            }
        },
        observeBufferId = { id -> bufferDao.observe(id).map { it?.takeIf { room -> room.type == BufferType.CHANNEL }?.id } },
        serverExists = { networkDao.byId(it) != null },
        observeServerIds = networkDao.observeAll().map { networks -> networks.mapTo(mutableSetOf()) { it.id } },
        diagnostics = diagnostics,
    )

    private val _state = MutableStateFlow<NotificationSettingsState>(NotificationSettingsState.Loading)
    override val state: StateFlow<NotificationSettingsState> = _state.asStateFlow()
    private val lock = Mutex()
    private val timers = mutableMapOf<Long, Job>()
    private val observers = mutableMapOf<Long, Job>()
    private val roomAliases = mutableMapOf<Long, Long>()

    @Volatile
    private var resolvedConfig: NotificationConfig? = null

    // Failed expiry saves are retried by the next mutation/reconciliation/load, never by a busy timer.
    private val silentExpiries = mutableSetOf<ChannelWatchState>()

    init {
        scope.launch {
            lock.withLock { loadLocked() }
            observeServerIds.distinctUntilChanged().collect { ids ->
                lock.withLock {
                    val config = readyConfig() ?: return@withLock
                    if (config.servers.keys.any { it !in ids }) reconcileLocked()
                }
            }
        }
    }

    override suspend fun resolve(
        networkId: Long,
        bufferId: Long,
    ): ResolvedNotificationSettings? {
        val current = state.value
        val loaded = if (current == NotificationSettingsState.Loading) state.first { it != NotificationSettingsState.Loading } else current
        if (loaded !is NotificationSettingsState.Ready) return null
        val config = resolvedConfig ?: return null
        val channelMode = config.channels[bufferId]
        val serverMode = config.servers[networkId]
        return ResolvedNotificationSettings(
            mode = channelMode ?: serverMode ?: config.global,
            source =
                when {
                    channelMode != null -> NotificationScope.CHANNEL
                    serverMode != null -> NotificationScope.SERVER
                    else -> NotificationScope.GLOBAL
                },
            watch = config.watches[bufferId]?.takeIf { it == Long.MAX_VALUE || it > clock.nowMillis() }?.let { ChannelWatchState(bufferId, it) },
        )
    }

    override suspend fun onRoomsMerged(
        winnerId: Long,
        loserId: Long,
    ) {
        state.first { it != NotificationSettingsState.Loading }
        lock.withLock {
            recordRoomAliasLocked(loserId, winnerId)
            reconcileLocked()
        }
    }

    private fun recordRoomAliasLocked(
        loserId: Long,
        winnerId: Long,
    ) {
        val target = roomAliases[winnerId] ?: winnerId
        if (target == loserId) return
        roomAliases[loserId] = target
        roomAliases.replaceAll { _, id -> if (id == loserId) target else id }
        refreshResolvedConfigLocked()
    }

    private fun refreshResolvedConfigLocked(config: NotificationConfig? = readyConfig()) {
        resolvedConfig =
            config?.let {
                if (roomAliases.isEmpty()) {
                    it
                } else {
                    rekeyConfig(it, (it.channels.keys + it.watches.keys).associateWith { id -> roomAliases[id] ?: id })
                }
            }
    }

    override suspend fun retryLoad(): Boolean =
        lock.withLock {
            if (_state.value != NotificationSettingsState.Unavailable) return@withLock false
            _state.value = NotificationSettingsState.Loading
            loadLocked()
        }

    override suspend fun setGlobal(mode: NotificationMode): Boolean = mutate { it.copy(global = mode) }

    override suspend fun setServer(
        networkId: Long,
        mode: NotificationMode?,
    ): Boolean =
        mutate { config ->
            if (!serverExists(networkId)) return@mutate null
            config.copy(servers = if (mode == null) config.servers - networkId else config.servers + (networkId to mode))
        }

    override suspend fun setChannel(
        bufferId: Long,
        mode: NotificationMode?,
    ): Boolean =
        mutate { config ->
            val id = resolveBufferId(bufferId) ?: return@mutate null
            config.copy(channels = if (mode == null) config.channels - id else config.channels + (id to mode))
        }

    override suspend fun startWatch(
        bufferId: Long,
        durationMs: Long?,
    ): Boolean =
        mutate { config ->
            val id = resolveBufferId(bufferId) ?: return@mutate null
            val expiresAt = if (durationMs == null) Long.MAX_VALUE else Math.addExact(clock.nowMillis(), durationMs)
            config.copy(watches = config.watches + (id to expiresAt))
        }

    override suspend fun stopWatch(bufferId: Long): Boolean =
        mutate { config ->
            val id = resolveBufferId(bufferId) ?: return@mutate null
            config.copy(watches = config.watches - id)
        }

    private fun readyConfig(): NotificationConfig? = (_state.value as? NotificationSettingsState.Ready)?.config

    private suspend fun mutate(change: suspend (NotificationConfig) -> NotificationConfig?): Boolean {
        state.first { it != NotificationSettingsState.Loading }
        return lock.withLock {
            val current = readyConfig() ?: return@withLock false
            try {
                val next = change(canonicalizeLocked(current)) ?: return@withLock false
                saveLocked(next)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordFailure("mutation_failed", error)
                false
            }
        }
    }

    private suspend fun loadLocked(): Boolean {
        try {
            val prefs = load()
            val encoded = prefs[CONFIG]
            val config =
                if (encoded != null) {
                    try {
                        notificationJson.decodeFromString<NotificationConfig>(encoded)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        recordFailure("config_repaired", error)
                        NotificationConfig()
                    }
                } else {
                    val id = prefs[BUFFER_ID]
                    val expiresAt = prefs[EXPIRES_AT]
                    NotificationConfig(watches = if (id != null && expiresAt != null) mapOf(id to expiresAt) else emptyMap())
                }
            // Even empty/default data must be saved before Ready: this retires the legacy keys once.
            if (saveLocked(canonicalizeLocked(config))) return true
        } catch (cancelled: CancellationException) {
            _state.value = NotificationSettingsState.Unavailable
            throw cancelled
        } catch (error: Exception) {
            recordFailure("load_failed", error)
        }
        _state.value = NotificationSettingsState.Unavailable
        return false
    }

    private suspend fun canonicalizeLocked(config: NotificationConfig): NotificationConfig {
        val ids = (config.channels.keys + config.watches.keys + silentExpiries.map { it.bufferId }).associateWith { resolveBufferId(it) }
        val silent = silentExpiries.mapNotNull { watch -> ids[watch.bufferId]?.let { watch.copy(bufferId = it) } }
        silentExpiries.clear()
        silentExpiries.addAll(silent)
        return rekeyConfig(config, ids).copy(servers = config.servers.filterKeys { serverExists(it) })
    }

    private fun rekeyConfig(
        config: NotificationConfig,
        ids: Map<Long, Long?>,
    ): NotificationConfig {
        val channels = mutableMapOf<Long, NotificationMode>()
        // Room coalescence is winner-first: the lower original room ID supplies a conflicting rule.
        for ((originalId, mode) in config.channels.toSortedMap()) {
            val id = ids[originalId] ?: continue
            channels.putIfAbsent(id, mode)
        }
        val watches = mutableMapOf<Long, Long>()
        for ((originalId, deadline) in config.watches) {
            val id = ids[originalId] ?: continue
            watches[id] = maxOf(watches[id] ?: Long.MIN_VALUE, deadline)
        }
        return config.copy(
            channels = channels,
            watches = watches,
        )
    }

    private suspend fun reconcileLocked() {
        val current = readyConfig() ?: return
        try {
            val next = canonicalizeLocked(current)
            if (next != current || next.watches.values.any { it != Long.MAX_VALUE && it <= clock.nowMillis() }) {
                saveLocked(next)
            } else {
                publishLocked(next)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordFailure("reconciliation_failed", error)
        }
    }

    private suspend fun saveLocked(config: NotificationConfig): Boolean {
        val now = clock.nowMillis()
        val expired = config.watches.filterValues { it != Long.MAX_VALUE && it <= now }.map { ChannelWatchState(it.key, it.value) }
        val next = if (expired.isEmpty()) config else config.copy(watches = config.watches - expired.map { it.bufferId }.toSet())
        try {
            save(next)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            silentExpiries.addAll(expired)
            recordFailure("save_failed", error)
            return false
        }
        val alerts = expired.filter { it !in silentExpiries }
        silentExpiries.clear()
        publishLocked(next)
        for (watch in alerts) {
            scope.launch {
                try {
                    onExpired(watch.bufferId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    recordFailure("expiry_alert_failed", error)
                }
            }
        }
        return true
    }

    private fun publishLocked(next: NotificationConfig) {
        val previous = readyConfig()
        timers.entries.removeAll { (id, job) ->
            val deadline = next.watches[id]
            val remove = deadline == null || deadline != previous?.watches?.get(id)
            if (remove) job.cancel()
            remove
        }
        val configuredIds = next.channels.keys + next.watches.keys
        roomAliases.keys.retainAll(configuredIds)
        refreshResolvedConfigLocked(next)
        observers.entries.removeAll { (id, job) ->
            val remove = id !in configuredIds
            if (remove) job.cancel()
            remove
        }
        _state.value = NotificationSettingsState.Ready(next)
        for ((id, deadline) in next.watches) {
            if (deadline == Long.MAX_VALUE || id in timers) continue
            timers[id] =
                scope.launch {
                    delay((deadline - clock.nowMillis()).coerceAtLeast(0L))
                    lock.withLock {
                        if (readyConfig()?.watches?.get(id) != deadline) return@withLock
                        timers.remove(id)
                        reconcileLocked()
                    }
                }
        }
        for (id in configuredIds) {
            if (id in observers) continue
            observers[id] =
                scope.launch {
                    var observedId: Long? = id
                    observeBufferId(id).distinctUntilChanged().collect { canonicalId ->
                        if (canonicalId == observedId) return@collect
                        observedId = canonicalId
                        lock.withLock {
                            if (canonicalId != null && canonicalId != id) {
                                recordRoomAliasLocked(id, canonicalId)
                            } else {
                                roomAliases.remove(id)
                                refreshResolvedConfigLocked()
                            }
                            val config = readyConfig() ?: return@withLock
                            if (id in config.channels || id in config.watches) reconcileLocked()
                        }
                    }
                }
        }
    }

    private fun recordFailure(
        event: String,
        error: Exception,
    ) {
        diagnostics.record("notification_settings", event) { mapOf("error" to error::class.simpleName) }
    }
}
