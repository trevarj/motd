package io.github.trevarj.motd.agentwire

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.irc.agentwire.AGENTWIRE_TAG
import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.agentwireMissingCaps
import io.github.trevarj.motd.irc.agentwire.parseAgentwireTopic
import io.github.trevarj.motd.irc.agentwire.readablePreview
import io.github.trevarj.motd.irc.agentwire.sendAgentwire
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.SequencedIrcEvent
import io.github.trevarj.motd.irc.client.canSendClientTag
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.nav.ChatRoute
import java.util.UUID
import javax.inject.Inject
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

private const val AGENTWIRE_INITIAL_HISTORY_SIZE = 20
private const val AGENTWIRE_HISTORY_PAGE_SIZE = 50
private const val AGENTWIRE_SYNC_RETRY_INITIAL_MS = 1_000L
private const val AGENTWIRE_SYNC_RETRY_MAX_MS = 10_000L

internal fun acceptsAgentwireEpoch(envelope: AgentwireEnvelope, currentEpoch: String?): Boolean =
    envelope.kind == "agent.hello" || envelope.history == true || currentEpoch == null ||
        envelope.epoch == currentEpoch

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
internal fun agentwireExpansionNeedsLoad(state: AgentwireUiState, path: String): Boolean =
    path !in state.workspaceChildren || path !in state.loadedSessionDirectories

/**
 * Timeline-derived UI data, recomputed only where the timeline itself was replaced. Grouping in
 * composition would rerun on every unrelated state change; the identity check keeps the ordinary
 * envelope (status, pages, queue) free.
 */
internal fun AgentwireUiState.withDerived(previous: AgentwireUiState): AgentwireUiState =
    if (timeline === previous.timeline) this else copy(timelineEntries = agentwireTimelineGroups(timeline))

internal suspend fun retryAgentwireSync(
    isReady: () -> Boolean,
    issue: suspend (String) -> Unit,
    nextId: () -> String = { UUID.randomUUID().toString() },
    pause: suspend (Long) -> Unit = { delay(it) },
) {
    var retryDelay = AGENTWIRE_SYNC_RETRY_INITIAL_MS
    while (!isReady()) {
        issue(nextId())
        if (isReady()) return
        pause(retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(AGENTWIRE_SYNC_RETRY_MAX_MS)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class AgentwireViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val prefs: AgentwirePrefs,
    private val buffers: BufferRepository,
    private val connections: ConnectionManager,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ChatRoute>()
    private val instance = UUID.randomUUID().toString()
    private val session = AgentwireSessionOrchestrator()
    private val _state = MutableStateFlow(AgentwireUiState())
    val state: StateFlow<AgentwireUiState> = _state.asStateFlow()
    private val log = AgentwireLogStore()
    // The log holds thousands of payloads; only its revision travels through the UI state stream.
    private val _logRevision = MutableStateFlow(0L)
    val logRevision: StateFlow<Long> = _logRevision.asStateFlow()
    private var sessionJob: Job? = null
    private var syncJob: Job? = null
    private var client: IrcClient? = null
    private val autoReviewConfirmedSessions = HashSet<String>()

    init {
        viewModelScope.launch {
            _state.map { it.channel }.distinctUntilChanged()
                .flatMapLatest { channel ->
                    if (channel.isBlank()) flowOf(emptyList()) else prefs.recentSessions(channel)
                }
                .collect { recents -> _state.update { it.copy(recentSessions = recents) } }
        }
        viewModelScope.launch {
            combine(
                prefs.enabled,
                buffers.observeBuffer(route.bufferId),
                connections.connectionStates,
            ) { enabled, buffer, states -> Triple(enabled, buffer, buffer?.let { states[it.networkId] }) }
                .collect { (enabled, buffer, connection) ->
                    val topic = buffer?.topic?.let(::parseAgentwireTopic)
                    val ready = connection as? IrcClientState.Ready
                    val missing = ready?.let {
                        agentwireMissingCaps(it.caps) + if (canSendClientTag(it.caps, it.isupport, AGENTWIRE_TAG)) {
                            emptySet()
                        } else {
                            setOf("CLIENTTAGDENY:$AGENTWIRE_TAG")
                        }
                    }.orEmpty()
                    val gate = when {
                        !enabled || buffer?.type != BufferType.CHANNEL || topic == null -> AgentwireGate.ORDINARY
                        ready != null && missing.isNotEmpty() -> AgentwireGate.BLOCKED
                        else -> AgentwireGate.ACTIVE
                    }
                    val identityChanged = _state.value.channel != buffer?.displayName.orEmpty() ||
                        _state.value.controllerAccount != topic?.account ||
                        _state.value.backendAccount != topic?.agentAccount
                    _state.update {
                        it.copy(
                            gate = gate,
                            channel = buffer?.displayName.orEmpty(),
                            title = topic?.title ?: buffer?.displayName ?: "Agentwire",
                            controllerAccount = topic?.account,
                            backendAccount = topic?.agentAccount,
                            backend = topic?.backend,
                            missingCaps = missing,
                            connected = ready != null,
                        )
                    }
                    val nextClient = buffer?.let { connections.clientFor(it.networkId) }
                    if (gate == AgentwireGate.ACTIVE && ready != null && nextClient != null && (nextClient !== client || identityChanged)) {
                        startSession(nextClient)
                    } else if (gate != AgentwireGate.ACTIVE || ready == null) {
                        stopSession(disconnected = client != null)
                    }
                }
        }
    }

    /** Snapshot for the log sheet, read on open and whenever [logRevision] advances. */
    fun logEntries(): List<AgentwireLogEntry> = log.entries()

    fun viewTranscript() = _state.update { it.copy(transcriptOverride = true) }
    fun returnToHarness() = _state.update { it.copy(transcriptOverride = false) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun submit(content: String) {
        if (content.isBlank()) return
        val kind = if (_state.value.busy && _state.value.settings["delivery"] == "steer") "turn.steer" else "turn.prompt"
        val data = buildJsonObject { put("content", content) }
        val localId = UUID.randomUUID().toString()
        _state.update { state ->
            state.copy(timeline = state.timeline + AgentwireTimelineItem(
                localId, "user.prompt", System.currentTimeMillis(), state.activeSid, state.currentTid,
                if (kind == "turn.steer") "Steer" else "You", content,
                backendItemId = localId,
            )).withDerived(state)
        }
        sendAction(kind, data = data, sid = _state.value.activeSid, id = localId)
    }

    fun cancelTurn() = sendAction("turn.cancel", sid = _state.value.activeSid, tid = _state.value.currentTid)
    fun clearQueue() = sendAction("queue.clear", sid = _state.value.activeSid)
    fun editQueue(iid: String, content: String) = sendAction(
        "queue.edit", data = buildJsonObject { put("content", content) }, sid = _state.value.activeSid, iid = iid,
    )
    fun moveQueue(iid: String, position: Int) = sendAction(
        "queue.move", data = buildJsonObject { put("position", position) }, sid = _state.value.activeSid, iid = iid,
    )
    fun deleteQueue(iid: String) = sendAction("queue.delete", sid = _state.value.activeSid, iid = iid)
    fun listWorkspaces(parent: String? = null) = sendAction(
        "workspace.list.request", data = parent?.let { buildJsonObject { put("parent", it) } },
    )
    fun listSessions(
        cwd: String? = null,
        cursor: String? = null,
        live: Boolean = cwd == null,
    ) = sendAction(
        "session.list.request", data = buildJsonObject {
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
    fun toggleWorkspaceExpansion(path: String, hasChildren: Boolean = true) {
        if (path in _state.value.expandedDirectories) {
            _state.update { it.copy(expandedDirectories = it.expandedDirectories - path) }
        } else if (agentwireExpansionNeedsLoad(_state.value, path)) {
            expandWorkspace(path, hasChildren)
        } else {
            _state.update { it.copy(expandedDirectories = it.expandedDirectories + path) }
        }
    }
    fun expandWorkspace(path: String, hasChildren: Boolean = true) {
        _state.update { it.copy(expandedDirectories = it.expandedDirectories + path) }
        if (hasChildren) listWorkspaces(path)
        listSessions(path, live = false)
    }
    fun createSession(cwd: String) = sendAction("session.create", buildJsonObject { put("cwd", cwd) })
    fun attachSession(sid: String, cwd: String? = null) = sendAction(
        "session.attach", cwd?.let { buildJsonObject { put("cwd", it) } }, sid = sid,
    )
    fun detachSession() = sendAction("session.detach", sid = _state.value.activeSid)
    fun renameSession(sid: String, title: String) = sendAction(
        "session.rename", buildJsonObject { put("title", title) }, sid = sid,
    )
    fun forkSession(sid: String) = sendAction("session.fork", sid = sid)
    fun archiveSession(sid: String, archived: Boolean) = sendAction(
        if (archived) "session.archive" else "session.unarchive", sid = sid,
    )
    fun updateSettings(values: Map<String, String>) = sendAction(
        "settings.update", JsonObject(values.mapValues { JsonPrimitive(it.value) }), sid = _state.value.activeSid,
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
    fun respondApproval(rid: String, allow: Boolean) = sendAction(
        "request.respond", buildJsonObject { put("allow", allow) }, sid = _state.value.activeSid, rid = rid,
    )
    fun respondQuestions(rid: String, answers: List<JsonElement>) = sendAction(
        "request.respond", buildJsonObject { put("answers", JsonArray(answers)) }, sid = _state.value.activeSid, rid = rid,
    )
    fun skipRequest(rid: String) = sendAction("request.skip", sid = _state.value.activeSid, rid = rid)
    fun loadOlderHistory() {
        viewModelScope.launch { requestHistory(initial = false) }
    }

    private fun startSession(next: IrcClient) {
        stopSession(disconnected = client != null)
        client = next
        session.reset()
        clearLog()
        _state.value = session.beginSync(_state.value).withDerived(_state.value)
        sessionJob = viewModelScope.launch {
            launch { next.sequencedEvents.collect(::ingest) }
            // Let the hot-flow collector attach before sync.request is emitted.
            delay(1)
            startSyncRetry()
        }
    }

    private fun startSyncRetry() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            session.retryUntilReady(
                state = { _state.value },
                issue = { id -> sendActionInternal("sync.request", id = id) },
            )
        }
    }

    private fun stopSession(disconnected: Boolean) {
        sessionJob?.cancel()
        sessionJob = null
        syncJob?.cancel()
        syncJob = null
        client = null
        session.reset()
        if (disconnected) {
            val uncertain = _state.value.actionStatus.mapValues { (_, status) ->
                if (status == "sent" || status == "accepted") "outcome unknown" else status
            }
            _state.update { it.copy(syncing = false, epoch = null, botAccount = null, actionStatus = uncertain) }
        }
    }

    private suspend fun ingest(event: SequencedIrcEvent) {
        val result = session.ingest(_state.value, event)
        if (result is AgentwireDeliveryCoordinator.Result.Rejected) {
            _state.value = result.state.withDerived(_state.value)
            return
        }
        if (result is AgentwireDeliveryCoordinator.Result.ResyncRequired) {
            clearLog()
            _state.value = result.state.withDerived(_state.value)
            startSyncRetry()
            return
        }
        if (result !is AgentwireDeliveryCoordinator.Result.Updated) return
        val envelope = result.envelope
        // Captured from the envelope, so the log keeps what the timeline strips, history included.
        if (log.capture(envelope)) _logRevision.value += 1
        val previousSid = _state.value.activeSid
        _state.value = result.state.withDerived(_state.value)
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
        val title = (state.liveSessions + state.workspaceSessions.values.flatten())
            .firstOrNull { it.id == sid }?.title ?: sid.take(12)
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
        val data = buildJsonObject {
            if (!initial) {
                current.historyCursor?.let { put("cursor", it) }
                current.historyBeforeAt?.let { put("beforeAt", it) }
            }
            put("limit", if (initial) AGENTWIRE_INITIAL_HISTORY_SIZE else AGENTWIRE_HISTORY_PAGE_SIZE)
        }
        val sent = sendActionInternal(
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
    ): String? {
        val current = _state.value
        if (kind != "sync.request" && kind !in current.actions) return null
        val activeClient = client ?: return null
        val envelope = AgentwireEnvelope(
            kind = kind, type = "action", id = id, at = System.currentTimeMillis(), instance = instance,
            epoch = if (kind == "sync.request") null else current.epoch ?: return null,
            device = prefs.deviceId(), sid = sid, tid = tid, iid = iid, rid = rid, data = data,
        )
        val sent = runCatching {
            activeClient.sendAgentwire(current.channel, envelope, envelope.readablePreview())
        }.getOrElse {
            _state.update { state -> state.copy(error = it.message ?: "Unable to send Agentwire action") }
            false
        }
        if (sent && kind != "sync.request") {
            _state.update { it.copy(actionStatus = it.actionStatus + (id to "sent")) }
        }
        return id.takeIf { sent }
    }
}
