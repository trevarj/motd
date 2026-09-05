package io.github.trevarj.motd.service

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.di.ApplicationScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

const val CHANNEL_WATCH_15_MS = 15 * 60 * 1000L
const val CHANNEL_WATCH_30_MS = 30 * 60 * 1000L
const val CHANNEL_WATCH_60_MS = 60 * 60 * 1000L

data class ChannelWatchState(
    val bufferId: Long,
    val expiresAt: Long,
)

/** One channel at a time: notify on every live PRIVMSG/ACTION until [expiresAt]. */
interface ChannelWatch {
    val state: StateFlow<ChannelWatchState?>

    fun isActive(bufferId: Long): Boolean

    suspend fun start(
        bufferId: Long,
        durationMs: Long,
    )

    suspend fun stop()

    object Noop : ChannelWatch {
        override val state: StateFlow<ChannelWatchState?> = MutableStateFlow(null)

        override fun isActive(bufferId: Long): Boolean = false

        override suspend fun start(
            bufferId: Long,
            durationMs: Long,
        ) = Unit

        override suspend fun stop() = Unit
    }
}

private val Context.channelWatchDataStore by preferencesDataStore("channel_watch")
private val BUFFER_ID = longPreferencesKey("buffer_id")
private val EXPIRES_AT = longPreferencesKey("expires_at")

@Singleton
class ChannelWatchImpl(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val onExpired: suspend (Long) -> Unit,
    private val load: suspend () -> ChannelWatchState? = { null },
    private val save: suspend (ChannelWatchState?) -> Unit = {},
) : ChannelWatch {
    @Inject
    constructor(
        @ApplicationScope scope: CoroutineScope,
        @ApplicationContext context: Context,
        notifications: MotdNotifications,
    ) : this(
        scope = scope,
        clock = { System.currentTimeMillis() },
        onExpired = { notifications.watchEnded(it) },
        load = {
            val prefs = context.channelWatchDataStore.data.first()
            val bufferId = prefs[BUFFER_ID]
            val expiresAt = prefs[EXPIRES_AT]
            if (bufferId == null || expiresAt == null) {
                null
            } else {
                ChannelWatchState(bufferId, expiresAt)
            }
        },
        save = { state ->
            context.channelWatchDataStore.edit { prefs ->
                if (state == null) {
                    prefs.remove(BUFFER_ID)
                    prefs.remove(EXPIRES_AT)
                } else {
                    prefs[BUFFER_ID] = state.bufferId
                    prefs[EXPIRES_AT] = state.expiresAt
                }
            }
        },
    )

    private val _state = MutableStateFlow<ChannelWatchState?>(null)
    override val state: StateFlow<ChannelWatchState?> = _state.asStateFlow()
    private val lock = Mutex()
    private val restored = CompletableDeferred<Unit>()
    private var timer: Job? = null

    init {
        scope.launch {
            try {
                lock.withLock {
                    val saved = load() ?: return@withLock
                    if (clock() >= saved.expiresAt) {
                        save(null)
                        onExpired(saved.bufferId)
                    } else {
                        armLocked(saved)
                    }
                }
            } finally {
                restored.complete(Unit)
            }
        }
    }

    override fun isActive(bufferId: Long): Boolean {
        val current = _state.value ?: return false
        return current.bufferId == bufferId && clock() < current.expiresAt
    }

    override suspend fun start(
        bufferId: Long,
        durationMs: Long,
    ) {
        restored.await()
        lock.withLock {
            val next = ChannelWatchState(bufferId, clock() + durationMs)
            save(next)
            armLocked(next)
        }
    }

    override suspend fun stop() {
        restored.await()
        lock.withLock {
            timer?.cancel()
            timer = null
            _state.value = null
            save(null)
        }
    }

    private fun armLocked(next: ChannelWatchState) {
        timer?.cancel()
        _state.value = next
        val wait = (next.expiresAt - clock()).coerceAtLeast(0L)
        timer =
            scope.launch {
                delay(wait)
                lock.withLock {
                    if (_state.value != next) return@withLock
                    timer = null
                    _state.value = null
                    save(null)
                    onExpired(next.bufferId)
                }
            }
    }
}
