package io.github.trevarj.motd.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.sync.IncomingMessageReader
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.AppVisibility
import io.github.trevarj.motd.service.ForegroundBufferTracker
import io.github.trevarj.motd.service.isFoolForChatSound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ReadAloudController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: MotdDatabase,
        private val prefs: ReadAloudPrefs,
        private val foreground: ForegroundBufferTracker,
        private val visibility: AppVisibility,
        settings: SettingsRepository,
        private val attachmentPlayer: AudioPlaybackController,
        private val activity: AudioActivityTracker,
        private val synthesizer: ReadAloudSynthesizer,
        private val output: ReadAloudOutput,
        @ApplicationScope private val scope: CoroutineScope,
    ) : IncomingMessageReader {
        private val directory = File(context.noBackupFilesDir, "read-aloud-cache").apply { mkdirs() }
        private val lock = Any()
        private val entries = mutableListOf<MessageEntity>()
        private var cursor = 0

        @Volatile private var generation = 0L
        private var work: Job? = null
        private var configurationCutoverPending = false
        private var optionsWork: Job? = null
        private var room: BufferEntity? = null
        private var activeRules = IrcIdentityRules()
        private val _state = MutableStateFlow(ReadAloudState())
        val state: StateFlow<ReadAloudState> = _state.asStateFlow()
        val voices = synthesizer.voices
        private val _config = MutableStateFlow(ReadAloudSelection())
        val config: StateFlow<ReadAloudSelection> = _config.asStateFlow()
        private val selectionReady = MutableStateFlow(false)
        private val preferencesReady = CompletableDeferred<Unit>()
        private val settingsState = settings.settings.stateIn(scope, SharingStarted.Eagerly, null)
        private var noisyRegistered = false
        private val noisy =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                        synchronized(lock) {
                            if (_state.value.enabled || _state.value.previewing) clear("Reading stopped because headphones were disconnected.")
                        }
                    }
                }
            }

        init {
            // Only this process owns these disposable files; never restore speech after a process death.
            directory.listFiles()?.forEach(File::delete)
            output.onInterrupted = { reason -> synchronized(lock) { clear(reason) } }
            output.onStarted = {
                synchronized(lock) {
                    val value = _state.value
                    if ((value.enabled || value.previewing) && !value.paused) _state.value = value.copy(status = ReadAloudStatus.PLAYING)
                }
            }
            activity.stopIncomingReading = ::stop
            scope.launch(Dispatchers.Main.immediate) {
                try {
                    prefs.systemConfig.distinctUntilChanged().collect { options ->
                        synchronized(lock) {
                            if (selectionReady.value && options != _config.value.options) {
                                selectionReady.value = false
                                invalidateConfiguration()
                            }
                            _config.value = ReadAloudSelection(options)
                            selectionReady.value = true
                            preferencesReady.complete(Unit)
                            if (configurationCutoverPending) {
                                configurationCutoverPending = false
                                if (_state.value.enabled) restart()
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    preferencesReady.completeExceptionally(error)
                    synchronized(lock) { fail(generation, error) }
                }
            }
            scope.launch(Dispatchers.Main.immediate) {
                combine(foreground.foregroundBufferId, visibility.onScreen, attachmentPlayer.state, activity.recording) { _, _, _, _ -> Unit }.collect {
                    synchronized(lock) {
                        val selected = _state.value.roomId
                        if ((_state.value.enabled || _state.value.previewing) &&
                            (!audioEligible() || (selected != null && foreground.foregroundBufferId.value != selected))
                        ) {
                            clear()
                        }
                    }
                }
            }
            scope.launch(Dispatchers.Main.immediate) {
                _state
                    .map { it.roomId }
                    .distinctUntilChanged()
                    .flatMapLatest { id ->
                        if (id == null) {
                            flowOf<Pair<Long?, BufferEntity?>>(null to null)
                        } else {
                            db.bufferDao().observe(id).map { current -> id to current }
                        }
                    }.collect { (observedId, current) ->
                        synchronized(lock) {
                            // flatMapLatest is buffered: an old null/room must not cancel a newer session.
                            if (observedId != _state.value.roomId) return@synchronized
                            room = current
                            if (_state.value.enabled && !eligibleRoom(current, observedId)) clear("Reading stopped because this conversation is unavailable or muted.")
                        }
                    }
            }
            scope.launch(Dispatchers.Main.immediate) {
                settingsState.filter { it != null }.collect {
                    val candidate = synchronized(lock) { generation to _state.value.current }
                    val message = candidate.second ?: return@collect
                    if (!eligible(message)) {
                        synchronized(lock) {
                            if (generation == candidate.first && _state.value.current?.id == message.id) {
                                clear("Reading stopped because this sender is hidden from audio.")
                            }
                        }
                    }
                }
            }
        }

        fun setEnabled(
            roomId: Long,
            enabled: Boolean,
        ) = synchronized(lock) {
            if (!enabled) {
                if (_state.value.roomId == roomId) clear()
            } else if (foreground.foregroundBufferId.value == roomId && audioEligible()) {
                clear()
                _state.value = ReadAloudState(roomId = roomId, enabled = true, status = ReadAloudStatus.PREPARING)
                monitorHeadphones()
                restart()
            } else if (foreground.foregroundBufferId.value == roomId && visibility.onScreen.value) {
                clear("Stop recording or other audio before reading incoming messages.")
                _state.value = _state.value.copy(roomId = roomId)
            }
        }

        override fun onIncoming(message: MessageEntity) =
            synchronized(lock) {
                if (!_state.value.enabled || _state.value.previewing || message.bufferId != _state.value.roomId ||
                    message.isSelf || (message.kind != MessageKind.PRIVMSG && message.kind != MessageKind.ACTION) ||
                    !audioEligible() || foreground.foregroundBufferId.value != message.bufferId || entries.any { it.id == message.id }
                ) {
                    return@synchronized
                }
                if (room?.let { !eligibleRoom(it, message.bufferId) } == true) return@synchronized
                // Sender policy needs this room's current IRC rules, loaded by the pre-synthesis check.
                entries += message
                trimSession()
                publish()
                if (work?.isActive != true) restart()
            }

        /** A visible-row tap authorizes only this message, never surrounding history. */
        fun readMessage(message: MessageEntity) =
            synchronized(lock) {
                val state = _state.value
                if (!state.enabled || state.previewing || state.roomId != message.bufferId ||
                    foreground.foregroundBufferId.value != message.bufferId || !audioEligible() ||
                    (message.kind != MessageKind.PRIVMSG && message.kind != MessageKind.NOTICE && message.kind != MessageKind.ACTION) ||
                    readAloudBody(message.text).isBlank() || (room != null && !eligibleCached(message))
                ) {
                    return@synchronized
                }
                _state.value = state.copy(paused = false, error = null, status = ReadAloudStatus.PREPARING)
                restart(message)
            }

        private suspend fun selectMessage(
            message: MessageEntity,
            token: Long,
        ): Boolean {
            val retained = synchronized(lock) { entries.toList() }
            val refreshed = HashMap<Long, MessageEntity>(retained.size + 1)
            val selected =
                db.withTransaction {
                    for (entry in retained) {
                        refreshed[entry.id] = db.messageDao().byCanonicalId(entry.id) ?: entry
                    }
                    val selected = db.messageDao().byCanonicalId(message.id) ?: message
                    refreshed[message.id] = selected
                    for (alias in db.canonicalTimelineDao().losingEventIds(selected.id)) {
                        refreshed[alias] = selected
                    }
                    selected
                }
            if (selected.bufferId != message.bufferId ||
                (selected.kind != MessageKind.PRIVMSG && selected.kind != MessageKind.NOTICE && selected.kind != MessageKind.ACTION) ||
                readAloudBody(selected.text).isBlank() || !eligible(selected)
            ) {
                return false
            }
            return synchronized(lock) {
                if (!current(token, message.bufferId) || !_state.value.enabled || _state.value.previewing) return@synchronized false
                // Merge only retained identities; arrivals during the lookup keep their place and snapshot.
                val seen = HashSet<Long>(entries.size)
                var index = 0
                while (index < entries.size) {
                    val entry = refreshed[entries[index].id] ?: entries[index]
                    if (seen.add(entry.id)) {
                        entries[index] = entry
                        index++
                    } else {
                        entries.removeAt(index)
                    }
                }
                val queued = entries.indexOfFirst { it.id == selected.id }
                if (queued >= 0) {
                    entries[queued] = selected
                    cursor = queued
                } else {
                    // ponytail: linear insertion into the bounded 50-message session; preserve arrival order.
                    val next =
                        entries.indexOfFirst {
                            it.serverTime > selected.serverTime ||
                                (
                                    it.serverTime == selected.serverTime &&
                                        (
                                            it.timelineOrder > selected.timelineOrder ||
                                                (it.timelineOrder == selected.timelineOrder && it.id > selected.id)
                                        )
                                )
                        }
                    cursor = if (next < 0) entries.size else next
                    entries.add(cursor, selected)
                }
                trimSession()
                publish()
                true
            }
        }

        private fun trimSession() {
            if (entries.size <= SESSION_LIMIT) return
            if (cursor > 0) {
                entries.removeAt(0)
                cursor--
            } else {
                // Keep the selected utterance stable; drop the oldest pending item.
                entries.removeAt(1)
                _state.value = _state.value.copy(skipped = _state.value.skipped + 1)
            }
        }

        fun previous() =
            synchronized(lock) {
                if (_state.value.canPrevious) move(cursor - 1)
            }

        fun skip() =
            synchronized(lock) {
                if (_state.value.canSkip) move(cursor + 1)
            }

        fun latest() =
            synchronized(lock) {
                if (_state.value.canLatest) move(entries.lastIndex)
            }

        fun togglePaused() =
            synchronized(lock) {
                if (!_state.value.enabled) return@synchronized
                val paused = !_state.value.paused
                _state.value = _state.value.copy(paused = paused, error = null, status = if (paused) ReadAloudStatus.PAUSED else ReadAloudStatus.PREPARING)
                if (paused) {
                    output.pause()
                } else {
                    output.resume()
                    if (work?.isActive != true) restart()
                }
            }

        /** Synchronous audio cutover on the main thread, called before recorder.start(). */
        fun stop() = synchronized(lock) { clear() }

        fun openVoiceOptions() {
            optionsWork?.cancel()
            optionsWork =
                scope.launch(Dispatchers.Main.immediate) {
                    try {
                        preferencesReady.await()
                        selectionReady.first { it }
                        synthesizer.loadVoices(config.value)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The backend publishes its visible discovery error; no installation/cloud fallback.
                    }
                }
        }

        fun saveVoiceOptions(value: ReadAloudSelection) {
            scope.launch(Dispatchers.Main.immediate) {
                try {
                    preferencesReady.await()
                    check(selectionReady.value && value.savedOptions == config.value.options) {
                        "Voice options changed. Open them again."
                    }
                    prefs.replaceSystem(value.options, value.savedOptions)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    synchronized(lock) {
                        _state.value = _state.value.copy(error = error.message ?: "Could not save voice options.", status = ReadAloudStatus.ERROR)
                    }
                }
            }
        }

        /** Stop/cancel before replacement dispatch; keep an opted-in session's cursor and pause. */
        private fun invalidateConfiguration() {
            configurationCutoverPending = true
            generation++
            work?.cancel()
            optionsWork?.cancel()
            synthesizer.cancel()
            output.stop()
            if (_state.value.previewing) {
                clear()
            } else if (_state.value.enabled) {
                _state.value = _state.value.copy(error = null, status = if (_state.value.paused) ReadAloudStatus.PAUSED else ReadAloudStatus.PREPARING)
            }
        }

        fun preview(
            roomId: Long?,
            value: ReadAloudSelection,
        ) = startPreview(roomId, value)

        fun stopPreview() = synchronized(lock) { if (_state.value.previewing) clear() }

        @OptIn(DelicateCoroutinesApi::class)
        private fun startPreview(
            roomId: Long?,
            value: ReadAloudSelection,
        ) = synchronized(lock) {
            if ((roomId != null && foreground.foregroundBufferId.value != roomId) || !audioEligible() ||
                !selectionReady.value || value.savedOptions != config.value.options
            ) {
                return@synchronized
            }
            clear()
            _state.value = ReadAloudState(roomId = roomId, previewing = true, status = ReadAloudStatus.PREPARING, preview = "Hello. This is your selected reading voice.")
            monitorHeadphones()
            val token = ++generation
            val previous = work
            work =
                scope.launch(Dispatchers.Main, start = CoroutineStart.ATOMIC) {
                    drainPrevious(previous)
                    val file =
                        try {
                            File.createTempFile("preview-", ".wav", directory)
                        } catch (error: Exception) {
                            fail(token, error)
                            return@launch
                        }
                    try {
                        synthesizer.synthesize("Hello. This is your selected reading voice.", value.normalized(), file)
                        if (!current(token, roomId)) return@launch
                        setStatus(token, ReadAloudStatus.PREPARING)
                        output.play(file) { current(token, roomId) }
                        synchronized(lock) { if (generation == token) clear() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        fail(token, error)
                    } finally {
                        file.delete()
                    }
                }
        }

        private fun move(position: Int) {
            cursor = position.coerceIn(0, entries.size)
            _state.value = _state.value.copy(error = null)
            publish()
            restart()
        }

        @OptIn(DelicateCoroutinesApi::class)
        private fun restart(selectedMessage: MessageEntity? = null) {
            val previous = work
            val token = ++generation
            previous?.cancel()
            synthesizer.cancel()
            output.stop()
            // Always dispatch: admission must never execute synthesis inline on an IRC collector.
            work =
                scope.launch(Dispatchers.Main, start = CoroutineStart.ATOMIC) {
                    drainPrevious(previous)
                    try {
                        val id = synchronized(lock) { _state.value.roomId } ?: return@launch
                        val resolved = db.bufferDao().observeById(id)
                        synchronized(lock) {
                            if (generation != token || _state.value.roomId != id) return@launch
                            room = resolved
                        }
                        check(eligibleRoom(resolved, id)) { "This conversation is unavailable or muted." }
                        activeRules = db.networkIdentityDao().byNetwork(checkNotNull(resolved).networkId)?.identityRules ?: IrcIdentityRules()
                        preferencesReady.await()
                        selectionReady.first { it }
                        if (selectedMessage != null && !selectMessage(selectedMessage, token)) {
                            synchronized(lock) {
                                if (current(token, id)) {
                                    work = null
                                    setStatus(token, ReadAloudStatus.WAITING)
                                }
                            }
                            return@launch
                        }
                        val selection = config.value
                        synthesizer.loadVoices(selection)
                        while (current(token, id)) {
                            awaitResumed(token)
                            val message =
                                synchronized(lock) {
                                    if (generation != token) return@launch
                                    entries.getOrNull(cursor).also {
                                        if (it == null) {
                                            // Admission must see an idle worker before WAITING can wake another arrival.
                                            work = null
                                            setStatus(token, ReadAloudStatus.WAITING)
                                        }
                                    }
                                } ?: return@launch
                            if (!eligible(message)) {
                                synchronized(lock) {
                                    if (generation == token) {
                                        entries.removeAt(cursor)
                                        publish()
                                    }
                                }
                                continue
                            }
                            setStatus(token, ReadAloudStatus.PREPARING)
                            val file = File.createTempFile("message-", ".wav", directory)
                            try {
                                synthesizer.synthesize(readAloudUtterance(message), selection, file)
                                awaitResumed(token)
                                if (!current(token, id)) return@launch
                                if (!eligible(message)) {
                                    synchronized(lock) {
                                        if (generation == token) {
                                            entries.removeAt(cursor)
                                            publish()
                                        }
                                    }
                                    continue
                                }
                                awaitResumed(token)
                                if (!current(token, id)) return@launch
                                setStatus(token, ReadAloudStatus.PREPARING)
                                output.play(file) { current(token, id) && eligibleCached(message) }
                            } finally {
                                file.delete()
                            }
                            awaitResumed(token)
                            setStatus(token, ReadAloudStatus.GAP)
                            delay(selection.options.gapMs.toLong())
                            awaitResumed(token)
                            synchronized(lock) {
                                if (generation == token) {
                                    cursor++
                                    publish()
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        fail(token, error)
                    }
                }
        }

        private suspend fun drainPrevious(previous: Job?) {
            // Atomic Main dispatch must reach this drain even when cancelled before execution.
            // Retain the predecessor until writes end; ensureActive then prevents cancelled synthesis.
            if (previous?.isCompleted == false) withContext(NonCancellable) { previous.join() }
            currentCoroutineContext().ensureActive()
        }

        private suspend fun awaitResumed(token: Long) {
            state.first { generation != token || !it.paused }
        }

        private suspend fun eligible(message: MessageEntity): Boolean {
            val currentRoom = db.bufferDao().observeById(message.bufferId)
            val rules = currentRoom?.let { db.networkIdentityDao().byNetwork(it.networkId)?.identityRules ?: IrcIdentityRules() } ?: return false
            activeRules = rules
            val settings = settingsState.filter { it != null }.first()!!
            return eligibleRoom(currentRoom, message.bufferId) && !isFoolForChatSound(settings.fools, rules, message.senderAccount, message.normalizedActor) &&
                foreground.foregroundBufferId.value == message.bufferId && audioEligible()
        }

        private fun eligibleCached(message: MessageEntity): Boolean =
            eligibleRoom(room, message.bufferId) &&
                foreground.foregroundBufferId.value == message.bufferId && audioEligible() &&
                settingsState.value?.let { !isFoolForChatSound(it.fools, activeRules, message.senderAccount, message.normalizedActor) } == true

        private fun eligibleRoom(
            value: BufferEntity?,
            id: Long?,
        ): Boolean =
            value != null && value.id == id && !value.muted && !value.dismissed &&
                value.pendingCloseAt == null && (value.type == BufferType.CHANNEL || value.type == BufferType.QUERY)

        private fun audioEligible(): Boolean =
            visibility.onScreen.value && !activity.recording.value &&
                !attachmentPlayer.state.value.playing && !attachmentPlayer.state.value.loading

        private fun current(
            token: Long,
            roomId: Long?,
        ): Boolean =
            synchronized(lock) {
                generation == token && _state.value.roomId == roomId && (roomId == null || foreground.foregroundBufferId.value == roomId) &&
                    audioEligible() && selectionReady.value
            }

        private fun setStatus(
            token: Long,
            status: ReadAloudStatus,
        ) = synchronized(lock) {
            if (generation == token) _state.value = _state.value.copy(status = if (_state.value.paused) ReadAloudStatus.PAUSED else status)
        }

        private fun fail(
            token: Long,
            error: Exception,
        ) = synchronized(lock) {
            if (generation == token) {
                synthesizer.cancel()
                output.stop()
                _state.value = _state.value.copy(paused = true, status = ReadAloudStatus.ERROR, error = error.message ?: "Reading failed. Choose an installed offline voice and try again.")
            }
        }

        private fun publish() {
            val current = entries.getOrNull(cursor) ?: entries.lastOrNull()
            _state.value =
                _state.value.copy(
                    current = current,
                    preview = if (current === _state.value.current) _state.value.preview else current?.let { readAloudBody(it.text) }.orEmpty(),
                    position = if (entries.isEmpty()) 0 else (cursor + 1).coerceAtMost(entries.size),
                    total = entries.size,
                    pending = (entries.size - cursor - 1).coerceAtLeast(0),
                    canPrevious = cursor > 0,
                    canSkip = cursor < entries.size,
                    canLatest = cursor < entries.lastIndex,
                )
        }

        private fun monitorHeadphones() {
            if (!noisyRegistered) {
                // Monitor the whole opted-in session, including synthesis and waiting, not only playback.
                ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
                noisyRegistered = true
            }
        }

        private fun clear(error: String? = null) {
            generation++
            work?.cancel()
            optionsWork?.cancel()
            optionsWork = null
            synthesizer.cancel()
            output.stop()
            if (noisyRegistered) {
                context.unregisterReceiver(noisy)
                noisyRegistered = false
            }
            entries.clear()
            cursor = 0
            room = null
            _state.value =
                ReadAloudState(
                    roomId = _state.value.roomId.takeIf { error != null },
                    error = error,
                    status = if (error != null) ReadAloudStatus.ERROR else ReadAloudStatus.WAITING,
                )
        }

        companion object {
            const val SESSION_LIMIT = 50
        }
    }
