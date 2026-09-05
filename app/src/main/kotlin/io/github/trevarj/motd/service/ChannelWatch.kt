package io.github.trevarj.motd.service

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.di.AppClock
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

/** One channel at a time: notify on every live PRIVMSG/ACTION until [expiresAt]. */
interface ChannelWatch {
    val state: StateFlow<ChannelWatchState?>

    fun isActive(bufferId: Long): Boolean

    /** [durationMs] null watches forever. */
    suspend fun start(
        bufferId: Long,
        durationMs: Long?,
    )

    suspend fun stop()

    object Noop : ChannelWatch {
        override val state: StateFlow<ChannelWatchState?> = MutableStateFlow(null)

        override fun isActive(bufferId: Long): Boolean = false

        override suspend fun start(
            bufferId: Long,
            durationMs: Long?,
        ) = Unit

        override suspend fun stop() = Unit
    }
}

private val Context.channelWatchDataStore by preferencesDataStore("channel_watch")
private val BUFFER_ID = longPreferencesKey("buffer_id")
private val EXPIRES_AT = longPreferencesKey("expires_at")

@Singleton
class ChannelWatchImpl internal constructor(
    private val scope: CoroutineScope,
    private val clock: AppClock,
    private val onExpired: suspend (Long) -> Unit,
    private val load: suspend () -> ChannelWatchState? = { null },
    private val save: suspend (ChannelWatchState?) -> Unit = {},
) : ChannelWatch {
    @Inject
    constructor(
        @ApplicationScope scope: CoroutineScope,
        @ApplicationContext context: Context,
        clock: AppClock,
        notifications: MotdNotifications,
    ) : this(
        scope = scope,
        clock = clock,
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
                    if (clock.nowMillis() >= saved.expiresAt) {
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
        return current.bufferId == bufferId && clock.nowMillis() < current.expiresAt
    }

    override suspend fun start(
        bufferId: Long,
        durationMs: Long?,
    ) {
        restored.await()
        lock.withLock {
            val expiresAt = if (durationMs == null) Long.MAX_VALUE else clock.nowMillis() + durationMs
            val next = ChannelWatchState(bufferId, expiresAt)
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
        timer = null
        _state.value = next
        if (next.isForever) return
        val wait = (next.expiresAt - clock.nowMillis()).coerceAtLeast(0L)
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
