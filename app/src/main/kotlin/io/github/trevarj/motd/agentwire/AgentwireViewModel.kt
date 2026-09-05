package io.github.trevarj.motd.agentwire

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.agentwire.AGENTWIRE_TAG
import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.AgentwireTopicParse
import io.github.trevarj.motd.irc.agentwire.agentwireMissingCaps
import io.github.trevarj.motd.irc.agentwire.parseAgentwireTopicResult
import io.github.trevarj.motd.irc.agentwire.readablePreview
import io.github.trevarj.motd.irc.agentwire.sendAgentwire
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.SequencedIrcEvent
import io.github.trevarj.motd.irc.client.canSendClientTag
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.nav.ChatRoute
import io.github.trevarj.motd.ui.share.PendingShare
import io.github.trevarj.motd.ui.share.PendingShareStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject

private const val AGENTWIRE_INITIAL_HISTORY_SIZE = 20
private const val AGENTWIRE_HISTORY_PAGE_SIZE = 50
private val AGENTWIRE_READ_ACTION_KINDS =
    setOf("sync.request", "workspace.list.request", "session.list.request", "history.request")

/**
 * Directories of a freshly arrived workspace page that the browser should open: the ones the user
 * had expanded, or every root until the user has expanded anything. Directories whose sessions are
 * already loaded are skipped so a repeated page does not re-request the same tree.
 */
internal fun agentwireDirectoriesToReopen(
    state: AgentwireUiState,
    parent: String?,
): List<AgentwireListItem> {
    val autoOpenRoots = parent == null && state.expandedDirectories.isEmpty()
    return state.workspaceChildren[parent.orEmpty()].orEmpty().filter { directory ->
        (autoOpenRoots || directory.id in state.expandedDirectories) &&
            directory.id !in state.loadedSessionDirectories
    }
}

/** Expanding a directory only costs a round trip while its children or sessions are missing. */
internal fun agentwireExpansionNeedsLoad(
    state: AgentwireUiState,
    path: String,
): Boolean = path !in state.workspaceChildren || path !in state.loadedSessionDirectories

internal data class AgentwireContextDestination(
    val networkId: Long,
    val target: String,
    val topic: String,
    val channel: String,
    val controllerAccount: String,
    val backendAccount: String,
    val backend: String,
    val sid: String,
    val epoch: String,
    val client: IrcClient,
)

internal data class AgentwireContextReview(
    val share: PendingShare.AgentContext,
    val destination: AgentwireContextDestination? = null,
    val sending: Boolean = false,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class AgentwireViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val prefs: AgentwirePrefs,
        private val buffers: BufferRepository,
        private val connections: ConnectionManager,
        private val diagnostics: DiagnosticLogger,
        private val pendingShares: PendingShareStore,
        clock: AppClock,
    ) : ViewModel() {
        private val route = savedStateHandle.toRoute<ChatRoute>()
        private val instance = UUID.randomUUID().toString()
        private val session = AgentwireSessionOrchestrator()
        private val budget = AgentwireSyncBudget(clock)
        private val _state = MutableStateFlow(AgentwireUiState())
        val state: StateFlow<AgentwireUiState> = _state.asStateFlow()
        private val log = AgentwireLogStore()

        // The log holds thousands of payloads; only its revision travels through the UI state stream.
        private val _logRevision = MutableStateFlow(0L)
        val logRevision: StateFlow<Long> = _logRevision.asStateFlow()
        private var sessionJob: Job? = null
        private var syncJob: Job? = null
        private var contextSendJob: Job? = null
        private var client: IrcClient? = null
        private var startedOnce = false
        private var sendFailureStage = "transport"
        private var sendErrorClass: String? = null
        private var untrustedRecorded = false
        private var joinTarget: Pair<Long, String>? = null
        private val autoReviewConfirmedSessions = HashSet<String>()
        private var contextBuffer: BufferEntity? = null
        private val assignedContext = pendingShares.agentContext(route.bufferId)
        private val _contextReview = MutableStateFlow(assignedContext.value?.let(::AgentwireContextReview))
        internal val contextReview: StateFlow<AgentwireContextReview?> = _contextReview.asStateFlow()

        init {
            viewModelScope.launch {
                assignedContext.collect { share ->
                    if (_contextReview.value?.share != share) {
                        val newlyAssigned = share != null && _contextReview.value == null
                        contextSendJob?.cancel()
                        _contextReview.value = share?.let(::AgentwireContextReview)
                        if (newlyAssigned) returnToHarness()
                    }
                }
            }
            // Recents are stored per channel, so the collector has to follow the channel the buffer
            // resolves to rather than being started once with a name we do not have yet.
            viewModelScope.launch {
                _state
                    .map { it.channel }
                    .distinctUntilChanged()
                    .flatMapLatest { channel ->
                        if (channel.isBlank()) flowOf(emptyList()) else prefs.recentSessions(channel)
                    }.collect { recents -> _state.update { it.copy(recentSessions = recents) } }
            }
            viewModelScope.launch {
                combine(
                    prefs.enabled,
                    buffers.observeBuffer(route.bufferId),
                    connections.connectionStates,
                ) { enabled, buffer, states -> Triple(enabled, buffer, buffer?.let { states[it.networkId] }) }
                    .collect { (enabled, buffer, connection) ->
                        val parse = buffer?.topic?.let(::parseAgentwireTopicResult)
                        val topic = (parse as? AgentwireTopicParse.Valid)?.topic
                        // Only a marker that is present and broken is reportable. A channel with no
                        // marker is an ordinary channel, not a failure, and must stay silent.
                        val defect = (parse as? AgentwireTopicParse.Invalid)?.takeIf { enabled }
                        val ready = connection as? IrcClientState.Ready
                        val missing =
                            ready
                                ?.let {
                                    agentwireMissingCaps(it.caps) +
                                        if (canSendClientTag(it.caps, it.isupport, AGENTWIRE_TAG)) {
                                            emptySet()
                                        } else {
                                            setOf("CLIENTTAGDENY:$AGENTWIRE_TAG")
                                        }
                                }.orEmpty()
                        val gate =
                            when {
                                !enabled || buffer?.type != BufferType.CHANNEL -> AgentwireGate.ORDINARY
                                defect != null -> AgentwireGate.INVALID_TOPIC
                                topic == null -> AgentwireGate.ORDINARY
                                ready != null && missing.isNotEmpty() -> AgentwireGate.BLOCKED
                                else -> AgentwireGate.ACTIVE
                            }
                        // Every gate transition is recorded, because the two outcomes that render as
                        // an ordinary channel leave no other trace of having been attempted.
                        if (gate != _state.value.gate) {
                            diagnostics.record(AGENTWIRE_DIAGNOSTIC_COMPONENT, "gate") {
                                buildMap {
                                    put("channel_fp", diagnostics.fingerprint(buffer?.displayName))
                                    put("gate", gate.name.lowercase())
                                    defect?.let {
                                        put("topic_defect", it.defect.name.lowercase())
                                        if (it.fields.isNotEmpty()) put("topic_fields", it.fields.joinToString(","))
                                    }
                                    // A usable marker the user cannot see because the lab is off is
                                    // the last silent outcome; naming it makes "nothing happened"
                                    // answerable from a diagnostic export alone.
                                    if (!enabled && parse is AgentwireTopicParse.Valid) put("lab_disabled", true)
                                }
                            }
                        }
                        val identityChanged =
                            _state.value.channel != buffer?.displayName.orEmpty() ||
                                _state.value.controllerAccount != topic?.account ||
                                _state.value.backendAccount != topic?.agentAccount ||
                                _state.value.backend != topic?.backend ||
                                joinTarget != buffer?.let { it.networkId to it.name }
                        if (
                            identityChanged || contextBuffer?.topic != buffer?.topic ||
                            gate != AgentwireGate.ACTIVE || ready == null || buffer?.joined != true
                        ) {
                            invalidateContextReview()
                        }
                        contextBuffer = buffer
                        _state.update {
                            it.copy(
                                gate = gate,
                                channel = buffer?.displayName.orEmpty(),
                                title = topic?.title ?: buffer?.displayName ?: "Agentwire",
                                controllerAccount = topic?.account,
                                backendAccount = topic?.agentAccount,
                                backend = topic?.backend,
                                topicDefect = defect,
                                missingCaps = missing,
                                connected = ready != null,
                            )
                        }
                        joinTarget = buffer?.let { it.networkId to it.name }
                        val nextClient = buffer?.let { connections.clientFor(it.networkId) }
                        when {
                            gate != AgentwireGate.ACTIVE || ready == null -> {
                                stopSession(disconnected = client != null)
                                _state.update { it.copy(sync = AgentwireSyncState.Idle) }
                            }

                            // Nothing the agent sends can reach a channel we are not in, so start no
                            // session and send nothing until the membership exists.
                            buffer?.joined != true -> {
                                stopSession(disconnected = client != null)
                                _state.update { it.copy(sync = AgentwireSyncState.NotJoined) }
                            }

                            nextClient == null -> {}

                            // A new client instance or a new agent identity always re-arms a full
                            // budget; anything else leaves a terminal failure sticky.
                            nextClient !== client || identityChanged -> {
                                startSession(nextClient, syncTrigger(identityChanged))
                            }

                            else -> {}
                        }
                    }
            }
        }

        /** Snapshot for the log sheet, read on open and whenever [logRevision] advances. */
        fun logEntries(): List<AgentwireLogEntry> = log.entries()

        fun viewTranscript() {
            invalidateContextReview()
            _state.update { it.copy(transcriptOverride = true) }
        }

        fun returnToHarness() = _state.update { it.copy(transcriptOverride = false) }

        fun clearError() = _state.update { it.copy(error = null) }

        /**
         * Joins the channel the agent publishes through. Delegated through [ConnectionManager]: the
         * ViewModel never talks to a connection directly. The combine collector starts the session on
         * its own once the join is confirmed.
         */
        fun joinAgentwireChannel() {
            val (networkId, channel) = joinTarget ?: return
            viewModelScope.launch {
                runCatching { connections.joinChannel(networkId, channel) }.onFailure { failure ->
                    _state.update { it.copy(error = failure.message ?: "Unable to join $channel") }
                }
            }
        }

        /** User-visible re-entry into sync: re-arms a full budget over the live client. */
        fun retrySync() {
            val active = client
            if (active == null) {
                // Nothing to talk to yet; the gate collector re-arms once the connection returns.
                _state.update { it.copy(sync = AgentwireSyncState.Idle) }
            } else {
                startSession(active, AgentwireSyncTrigger.RETRY)
            }
        }

        fun submit(
            content: String,
            onAccepted: () -> Unit = {},
        ) {
            if (content.isBlank()) return
            val current = _state.value
            clearError()
            val kind = if (current.busy && current.settings["delivery"] == "steer") "turn.steer" else "turn.prompt"
            viewModelScope.launch {
                val id = sendActionInternal(kind, buildJsonObject { put("content", content) }, sid = current.activeSid)
                if (id != null) {
                    appendSubmittedPrompt(id, content, current.activeSid, kind)
                    onAccepted()
                } else {
                    _state.update { it.copy(error = it.error ?: "Unable to send Agentwire prompt. Draft retained.") }
                }
            }
        }

        internal fun canReviewContext(): Boolean = contextDestination() != null

        internal fun reviewContext() {
            val review = _contextReview.value ?: return
            if (review.sending || review.share != assignedContext.value) return
            val destination = contextDestination() ?: return
            _contextReview.value = review.copy(destination = destination)
        }

        internal fun editContext(prompt: String): Boolean {
            val review = _contextReview.value ?: return false
            if (review.share != assignedContext.value) return false
            if (review.share.prompt == prompt) return true
            val replacement = review.share.copy(prompt = prompt)
            _contextReview.value = review.copy(share = replacement)
            if (!pendingShares.updateAgentContext(route.bufferId, review.share, replacement)) {
                contextSendJob?.cancel()
                _contextReview.value = assignedContext.value?.let(::AgentwireContextReview)
                return false
            }
            return true
        }

        internal fun keepContextForLater() {
            contextSendJob?.cancel()
            _contextReview.update { it?.copy(destination = null) }
        }

        internal fun discardContext() {
            val review = _contextReview.value ?: return
            if (review.sending) return
            if (pendingShares.clearAgentContext(route.bufferId, review.share)) _contextReview.value = null
        }

        internal fun submitContext() {
            val review = _contextReview.value ?: return
            val destination = review.destination ?: return
            if (review.sending || review.share.prompt.isBlank()) return
            clearError()
            _contextReview.value = review.copy(sending = true)
            contextSendJob =
                viewModelScope.launch {
                    try {
                        val id =
                            sendActionInternal(
                                "turn.prompt",
                                buildJsonObject { put("content", review.share.prompt) },
                                sid = destination.sid,
                                context = review,
                            )
                        if (id != null) {
                            appendSubmittedPrompt(id, review.share.prompt, destination.sid, "turn.prompt")
                            if (pendingShares.clearAgentContext(route.bufferId, review.share)) _contextReview.value = null
                        } else {
                            _state.update { it.copy(error = it.error ?: "Unable to send Agentwire context. Draft retained; edit or retry.") }
                        }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        _state.update { it.copy(error = failure.message ?: "Unable to send Agentwire context. Draft retained.") }
                    } finally {
                        contextSendJob = null
                        _contextReview.update { it?.copy(sending = false) }
                    }
                }
        }

        private fun appendSubmittedPrompt(
            id: String,
            content: String,
            sid: String?,
            kind: String,
        ) {
            _state.update { state ->
                if (state.activeSid != sid || state.timeline.any { it.id == id || it.backendItemId == id }) {
                    state
                } else {
                    state.copy(
                        timeline =
                            state.timeline +
                                AgentwireTimelineItem(
                                    id,
                                    "user.prompt",
                                    System.currentTimeMillis(),
                                    sid,
                                    state.currentTid,
                                    if (kind == "turn.steer") "Steer" else "You",
                                    content,
                                    backendItemId = id,
                                ),
                    )
                }
            }
        }

        private fun contextDestination(
            state: AgentwireUiState = _state.value,
            buffer: BufferEntity? = contextBuffer,
        ): AgentwireContextDestination? {
            if (
                state.gate != AgentwireGate.ACTIVE || state.transcriptOverride || !state.connected ||
                state.sync != AgentwireSyncState.Ready || "turn.prompt" !in state.actions ||
                buffer?.type != BufferType.CHANNEL || buffer.joined != true
            ) {
                return null
            }
            val topic = (buffer.topic?.let(::parseAgentwireTopicResult) as? AgentwireTopicParse.Valid)?.topic ?: return null
            if (
                state.channel != buffer.displayName || state.controllerAccount != topic.account ||
                state.backendAccount != topic.agentAccount || state.backend != topic.backend ||
                !topic.agentAccount.equals(state.botAccount, ignoreCase = true)
            ) {
                return null
            }
            val activeClient = client ?: return null
            if (connections.clientFor(buffer.networkId) !== activeClient) return null
            if (connections.connectionStates.value[buffer.networkId] !is IrcClientState.Ready) return null
            val ready = activeClient.state.value as? IrcClientState.Ready ?: return null
            if (
                agentwireMissingCaps(ready.caps).isNotEmpty() ||
                !canSendClientTag(ready.caps, ready.isupport, AGENTWIRE_TAG)
            ) {
                return null
            }
            return AgentwireContextDestination(
                buffer.networkId,
                buffer.name,
                buffer.topic,
                state.channel,
                topic.account,
                topic.agentAccount,
                topic.backend,
                state.activeSid ?: return null,
                state.epoch ?: return null,
                activeClient,
            )
        }

        private fun invalidateContextReview() {
            if (_contextReview.value?.destination == null) return
            keepContextForLater()
            _state.update { it.copy(error = "Destination or session changed. Context retained; select and review again.") }
        }

        fun cancelTurn() = sendAction("turn.cancel", sid = _state.value.activeSid, tid = _state.value.currentTid)

        fun clearQueue() = sendAction("queue.clear", sid = _state.value.activeSid)

        fun editQueue(
            iid: String,
            content: String,
        ) = sendAction(
            "queue.edit",
            data = buildJsonObject { put("content", content) },
            sid = _state.value.activeSid,
            iid = iid,
        )

        fun moveQueue(
            iid: String,
            position: Int,
        ) = sendAction(
            "queue.move",
            data = buildJsonObject { put("position", position) },
            sid = _state.value.activeSid,
            iid = iid,
        )

        fun deleteQueue(iid: String) = sendAction("queue.delete", sid = _state.value.activeSid, iid = iid)

        fun listWorkspaces(parent: String? = null) =
            sendAction(
                "workspace.list.request",
                data = parent?.let { buildJsonObject { put("parent", it) } },
            )

        fun listSessions(
            cwd: String? = null,
            cursor: String? = null,
            live: Boolean = cwd == null,
        ) = sendAction(
            "session.list.request",
            data =
                buildJsonObject {
                    put("scope", if (live) "live" else "workspace")
                    cwd?.let { put("cwd", it) }
                    cursor?.let { put("cursor", it) }
                },
        )

        /** Drops the cached tree and reloads it; expanded directories reopen as their pages arrive. */
        fun refreshSessionBrowser() {
            _state.update {
                it.copy(
                    workspaceChildren = emptyMap(),
                    liveSessions = emptyList(),
                    workspaceSessions = emptyMap(),
                    loadedSessionDirectories = emptySet(),
                )
            }
            listWorkspaces()
            listSessions(live = true)
        }

        fun toggleWorkspaceExpansion(
            path: String,
            hasChildren: Boolean = true,
        ) {
            if (path in _state.value.expandedDirectories) {
                _state.update { it.copy(expandedDirectories = it.expandedDirectories - path) }
            } else if (agentwireExpansionNeedsLoad(_state.value, path)) {
                expandWorkspace(path, hasChildren)
            } else {
                _state.update { it.copy(expandedDirectories = it.expandedDirectories + path) }
            }
        }

        fun expandWorkspace(
            path: String,
            hasChildren: Boolean = true,
        ) {
            _state.update { it.copy(expandedDirectories = it.expandedDirectories + path) }
            if (hasChildren) listWorkspaces(path)
            listSessions(path, live = false)
        }

        fun createSession(cwd: String) = sendAction("session.create", buildJsonObject { put("cwd", cwd) })

        fun closeSession() = sendAction("session.close", sid = _state.value.activeSid)

        fun attachSession(
            sid: String,
            cwd: String? = null,
        ) {
            invalidateContextReview()
            sendAction("session.attach", cwd?.let { buildJsonObject { put("cwd", it) } }, sid = sid)
        }

        fun detachSession() {
            invalidateContextReview()
            sendAction("session.detach", sid = _state.value.activeSid)
        }

        fun renameSession(
            sid: String,
            title: String,
        ) = sendAction(
            "session.rename",
            buildJsonObject { put("title", title) },
            sid = sid,
        )

        fun forkSession(sid: String) = sendAction("session.fork", sid = sid)

        fun archiveSession(
            sid: String,
            archived: Boolean,
        ) = sendAction(
            if (archived) "session.archive" else "session.unarchive",
            sid = sid,
        )

        fun updateSettings(values: Map<String, String>) =
            sendAction(
                "settings.update",
                JsonObject(values.mapValues { JsonPrimitive(it.value) }),
                sid = _state.value.activeSid,
            )

        fun enableAutoReview() {
            _state.value.activeSid?.let(autoReviewConfirmedSessions::add)
            _state.update { it.copy(autoReviewConfirmed = true) }
            updateSettings(mapOf("approvalReviewer" to "auto_review"))
        }

        fun disableAutoReview() {
            _state.value.activeSid?.let(autoReviewConfirmedSessions::remove)
            _state.update { it.copy(autoReviewConfirmed = false) }
            updateSettings(mapOf("approvalReviewer" to "manual"))
        }

        fun respondApproval(
            rid: String,
            allow: Boolean,
        ) = sendAction(
            "request.respond",
            buildJsonObject { put("allow", allow) },
            sid = _state.value.activeSid,
            rid = rid,
        )

        fun respondQuestions(
            rid: String,
            answers: List<JsonElement>,
        ) = sendAction(
            "request.respond",
            buildJsonObject { put("answers", JsonArray(answers)) },
            sid = _state.value.activeSid,
            rid = rid,
        )

        fun skipRequest(rid: String) = sendAction("request.skip", sid = _state.value.activeSid, rid = rid)

        fun loadOlderHistory() {
            viewModelScope.launch { requestHistory(initial = false) }
        }

        private fun syncTrigger(identityChanged: Boolean): AgentwireSyncTrigger =
            when {
                _state.value.sync is AgentwireSyncState.NotJoined -> AgentwireSyncTrigger.REJOIN
                !startedOnce -> AgentwireSyncTrigger.OPEN
                identityChanged -> AgentwireSyncTrigger.IDENTITY
                else -> AgentwireSyncTrigger.OPEN
            }

        private fun startSession(
            next: IrcClient,
            trigger: AgentwireSyncTrigger,
        ) {
            stopSession(disconnected = client != null)
            client = next
            startedOnce = true
            session.reset()
            clearLog()
            // Only a user-visible entry anchors the deadline; internal resyncs reuse it.
            budget.anchor()
            untrustedRecorded = false
            recordSyncStarted(trigger)
            _state.value =
                session
                    .beginSync(_state.value)
                    .copy(sync = AgentwireSyncState.Syncing(attempt = 0, startedAtMs = budget.startedAtMs))
            sessionJob =
                viewModelScope.launch {
                    launch { next.sequencedEvents.collect(::ingest) }
                    // Let the hot-flow collector attach before sync.request is emitted.
                    delay(1)
                    startSyncRetry()
                }
        }

        private fun startSyncRetry() {
            syncJob?.cancel()
            syncJob =
                viewModelScope.launch {
                    session.retryUntilReady(
                        budget = budget,
                        isReady = { _state.value.sync !is AgentwireSyncState.Syncing },
                        issue = ::sendSyncRequest,
                        onAttempt = { attempt ->
                            _state.update {
                                if (it.sync is AgentwireSyncState.Syncing) {
                                    it.copy(sync = AgentwireSyncState.Syncing(attempt, budget.startedAtMs))
                                } else {
                                    it
                                }
                            }
                        },
                        onTimeout = {
                            failSync(AgentwireSyncFailure.Timeout(budget.attempts, session.ignoreCounters()))
                        },
                        onSendFailed = {
                            failSync(AgentwireSyncFailure.SendFailed(sendFailureDetail(sendFailureStage)))
                        },
                    )
                }
        }

        private suspend fun sendSyncRequest(id: String): Boolean {
            sendErrorClass = null
            val sent = sendActionInternal("sync.request", id = id) != null
            if (!sent) {
                sendFailureStage = syncSendStage()
                diagnostics.record(AGENTWIRE_DIAGNOSTIC_COMPONENT, "send_failed") {
                    mapOf(
                        "channel_fp" to diagnostics.fingerprint(_state.value.channel),
                        "stage" to sendFailureStage,
                        "error_class" to (sendErrorClass ?: "none"),
                    )
                }
            }
            return sent
        }

        /** Classifies why the write never reached the wire, for both the copy and the journal. */
        private fun syncSendStage(): String {
            val ready = client?.state?.value as? IrcClientState.Ready ?: return "not_ready"
            return when {
                agentwireMissingCaps(ready.caps).isNotEmpty() -> "caps"
                !canSendClientTag(ready.caps, ready.isupport, AGENTWIRE_TAG) -> "client_tag"
                else -> "transport"
            }
        }

        /**
         * Records the terminal phase. Called from inside the retry job, which returns immediately
         * afterwards, so it must not cancel that job from underneath itself.
         */
        private fun failSync(failure: AgentwireSyncFailure) {
            recordSyncFailed(failure)
            invalidateContextReview()
            _state.update { it.copy(sync = AgentwireSyncState.Failed(failure)) }
        }

        private fun recordSyncStarted(trigger: AgentwireSyncTrigger) {
            diagnostics.record(AGENTWIRE_DIAGNOSTIC_COMPONENT, "sync_started") {
                mapOf(
                    "channel_fp" to diagnostics.fingerprint(_state.value.channel),
                    "attempt" to budget.attempts + 1,
                    "trigger" to trigger.wireName,
                )
            }
        }

        private fun recordSyncCompleted() {
            diagnostics.record(AGENTWIRE_DIAGNOSTIC_COMPONENT, "sync_completed") {
                mapOf(
                    "channel_fp" to diagnostics.fingerprint(_state.value.channel),
                    "attempts" to budget.attempts,
                    "elapsed_ms" to budget.elapsedMs(),
                )
            }
        }

        private fun recordSyncFailed(failure: AgentwireSyncFailure) {
            diagnostics.record(AGENTWIRE_DIAGNOSTIC_COMPONENT, "sync_failed") {
                // `reason` is a redacted field name in the journal, hence `end_reason`/`detail_class`.
                buildMap<String, Any?> {
                    put("channel_fp", diagnostics.fingerprint(_state.value.channel))
                    put("end_reason", failure.endReason())
                    put("attempts", budget.attempts)
                    put("elapsed_ms", budget.elapsedMs())
                    put("detail_class", detailClass(failure))
                    // One field per non-zero ignore counter: the evidence for a silent handshake.
                    putAll(session.ignoreCounters().diagnosticFields())
                }
            }
        }

        /** Once per handshake: the account is fingerprinted, and the running tally lands in sync_failed. */
        private fun recordUntrustedEvents(account: String) {
            if (untrustedRecorded) return
            untrustedRecorded = true
            diagnostics.record(AGENTWIRE_DIAGNOSTIC_COMPONENT, "untrusted_events") {
                mapOf(
                    "channel_fp" to diagnostics.fingerprint(_state.value.channel),
                    "account_fp" to diagnostics.fingerprint(account),
                    "count" to (session.ignoreCounters().counts[IgnoreReason.UNTRUSTED_ACCOUNT] ?: 1),
                )
            }
        }

        /** Never the message itself: only how the handshake ended. */
        private fun detailClass(failure: AgentwireSyncFailure): String =
            when (failure) {
                is AgentwireSyncFailure.Timeout -> "no_reply"
                is AgentwireSyncFailure.Rejected -> "bridge_reply"
                is AgentwireSyncFailure.ProtocolMismatch -> "envelope_validation"
                is AgentwireSyncFailure.SendFailed -> sendFailureStage
            }

        /** Ends the retry job from outside it, after a definitive wire answer. */
        private fun stopSyncRetry() {
            syncJob?.cancel()
            syncJob = null
        }

        private fun stopSession(disconnected: Boolean) {
            invalidateContextReview()
            sessionJob?.cancel()
            sessionJob = null
            syncJob?.cancel()
            syncJob = null
            client = null
            session.reset()
            if (disconnected) {
                val uncertain =
                    _state.value.actionStatus.mapValues { (_, status) ->
                        if (status == "sent" || status == "accepted") "outcome unknown" else status
                    }
                _state.update { it.copy(epoch = null, botAccount = null, actionStatus = uncertain) }
            }
        }

        private suspend fun ingest(event: SequencedIrcEvent) {
            val reviewedDestination = _contextReview.value?.destination
            val result = session.ingest(_state.value, event)
            if (result is AgentwireDeliveryCoordinator.Result.Rejected) {
                result.untrustedAccount?.let(::recordUntrustedEvents)
                _state.value = result.state
                if (reviewedDestination != null && contextDestination(result.state) != reviewedDestination) invalidateContextReview()
                return
            }
            if (result is AgentwireDeliveryCoordinator.Result.SyncRejected) {
                // A definitive wire-level "no" ends the attempt now; retrying an unacknowledged
                // request would only reproduce the same answer.
                stopSyncRetry()
                _state.value = result.state
                failSync(AgentwireSyncFailure.Rejected(result.detail))
                return
            }
            if (result is AgentwireDeliveryCoordinator.Result.ProtocolMismatch) {
                stopSyncRetry()
                _state.value = result.state
                failSync(AgentwireSyncFailure.ProtocolMismatch(result.detail))
                return
            }
            if (result is AgentwireDeliveryCoordinator.Result.ResyncRequired) {
                clearLog()
                invalidateContextReview()
                // Deliberately no budget.anchor(): an internal restart must not extend the deadline.
                _state.value =
                    result.state
                        .copy(sync = AgentwireSyncState.Syncing(budget.attempts, budget.startedAtMs))
                untrustedRecorded = false
                diagnostics.record(AGENTWIRE_DIAGNOSTIC_COMPONENT, "resync") {
                    mapOf(
                        "channel_fp" to diagnostics.fingerprint(_state.value.channel),
                        "trigger" to result.cause.wireName,
                        "sequence_delta" to result.sequenceDelta,
                    )
                }
                recordSyncStarted(
                    when (result.cause) {
                        AgentwireResyncCause.GAP -> AgentwireSyncTrigger.RESYNC_GAP
                        AgentwireResyncCause.FRAGMENT_EXPIRY -> AgentwireSyncTrigger.RESYNC_FRAGMENT
                        AgentwireResyncCause.EPOCH -> AgentwireSyncTrigger.RESYNC_EPOCH
                    },
                )
                startSyncRetry()
                return
            }
            if (result !is AgentwireDeliveryCoordinator.Result.Updated) return
            val envelope = result.envelope
            // Captured from the envelope, so the full payload outlives the timeline's cap and the
            // inline card's truncation. History backfill included.
            if (log.capture(envelope)) _logRevision.value += 1
            val previousSid = _state.value.activeSid
            _state.value =
                if (result.syncCompleted) {
                    stopSyncRetry()
                    recordSyncCompleted()
                    result.state.copy(sync = AgentwireSyncState.Ready)
                } else {
                    result.state
                }
            if (reviewedDestination != null && contextDestination() != reviewedDestination) invalidateContextReview()
            if (result.syncCompleted) listSessions(live = true)
            if (envelope.kind == "workspace.page") {
                agentwireDirectoriesToReopen(_state.value, envelope.data?.string("parent")).forEach {
                    expandWorkspace(it.id, it.raw.bool("hasChildren") ?: true)
                }
            }
            if (envelope.kind == "session.page") {
                val next = envelope.data?.string("next")
                if (next != null) {
                    val cwd = envelope.data?.string("cwd")
                    val live = envelope.data?.string("scope") == "live" || cwd == null
                    listSessions(cwd, next, live)
                }
            }
            val activeSid = _state.value.activeSid
            _state.update {
                it.copy(autoReviewConfirmed = activeSid != null && activeSid in autoReviewConfirmedSessions)
            }
            val current = _state.value
            if (current.activeSid != null && current.activeSid != previousSid) rememberAttachedSession(current)
            if (
                current.activeSid != null &&
                (current.activeSid != previousSid || (result.syncCompleted && current.historySid == null))
            ) {
                requestHistory(initial = true)
            }
        }

        private fun clearLog() {
            log.clear()
            _logRevision.value += 1
        }

        /** Records each newly bound session so the drawer can offer it again after detaching. */
        private suspend fun rememberAttachedSession(state: AgentwireUiState) {
            val sid = state.activeSid ?: return
            val title =
                (state.liveSessions + state.workspaceSessions.values.flatten())
                    .firstOrNull { it.id == sid }
                    ?.title ?: sid.take(12)
            prefs.addRecentSession(state.channel, sid, title, state.cwd, state.backend)
        }

        private suspend fun requestHistory(initial: Boolean) {
            val current = _state.value
            val sid = current.activeSid ?: return
            if (current.historyRequestId != null || current.historyLoading) return
            if (!initial && !current.olderHistoryAvailable) return
            val requestId = UUID.randomUUID().toString()
            _state.update { state ->
                if (state.activeSid != sid) {
                    state
                } else {
                    state.copy(
                        historyLoading = true,
                        historyRequestId = requestId,
                        historySid = sid,
                        historyStaged = emptyList(),
                    )
                }
            }
            val data =
                buildJsonObject {
                    if (!initial) {
                        current.historyCursor?.let { put("cursor", it) }
                        current.historyBeforeAt?.let { put("beforeAt", it) }
                    }
                    put("limit", if (initial) AGENTWIRE_INITIAL_HISTORY_SIZE else AGENTWIRE_HISTORY_PAGE_SIZE)
                }
            val sent =
                sendActionInternal(
                    "history.request",
                    data,
                    sid = sid,
                    id = requestId,
                )
            if (sent == null) {
                _state.update { state ->
                    if (state.historyRequestId == requestId) {
                        state.copy(
                            historyLoading = false,
                            historyRequestId = null,
                            historyStaged = emptyList(),
                        )
                    } else {
                        state
                    }
                }
            }
        }

        private fun sendAction(
            kind: String,
            data: JsonObject? = null,
            sid: String? = null,
            tid: String? = null,
            iid: String? = null,
            rid: String? = null,
            id: String = UUID.randomUUID().toString(),
        ) {
            viewModelScope.launch { sendActionInternal(kind, data, sid, tid, iid, rid, id) }
        }

        private suspend fun sendActionInternal(
            kind: String,
            data: JsonObject? = null,
            sid: String? = null,
            tid: String? = null,
            iid: String? = null,
            rid: String? = null,
            id: String = UUID.randomUUID().toString(),
            context: AgentwireContextReview? = null,
        ): String? {
            val device = prefs.deviceId()
            if (context != null) {
                val enabled = prefs.enabled.first()
                val buffer = buffers.observeBuffer(route.bufferId).first()
                if (
                    !enabled || context.destination == null || context.destination != contextDestination(buffer = buffer) ||
                    _contextReview.value?.destination != context.destination ||
                    assignedContext.value != context.share
                ) {
                    invalidateContextReview()
                    _state.update { it.copy(error = "Destination or session changed. Context retained; select and review again.") }
                    return null
                }
                if (context.share.prompt
                        .toByteArray(Charsets.UTF_8)
                        .size > AGENTWIRE_MAX_PROMPT_BYTES
                ) {
                    _state.update { it.copy(error = "Context exceeds 64 KiB of UTF-8 text. Edit it before sending; nothing was truncated.") }
                    return null
                }
            }
            val current = _state.value
            if (kind != "sync.request" && kind !in current.actions) return null
            val activeClient = client ?: return null
            val envelope =
                AgentwireEnvelope(
                    kind = kind,
                    type = "action",
                    id = id,
                    at = System.currentTimeMillis(),
                    instance = instance,
                    epoch = if (kind == "sync.request") null else current.epoch ?: return null,
                    device = device,
                    sid = sid,
                    tid = tid,
                    iid = iid,
                    rid = rid,
                    data = data,
                )
            val sent =
                runCatching {
                    activeClient.sendAgentwire(current.channel, envelope, envelope.readablePreview())
                }.getOrElse {
                    if (it is CancellationException) throw it
                    _state.update { state -> state.copy(error = it.message ?: "Unable to send Agentwire action") }
                    sendErrorClass = it::class.java.simpleName
                    false
                }
            if (sent && kind !in AGENTWIRE_READ_ACTION_KINDS) {
                _state.update { it.copy(actionStatus = it.actionStatus + (id to "sent")) }
            }
            return id.takeIf { sent }
        }
    }
