package io.github.trevarj.motd.agentwire

import io.github.trevarj.motd.irc.agentwire.AGENTWIRE_TAG
import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.AgentwireReassembler
import io.github.trevarj.motd.irc.agentwire.AgentwireValue
import io.github.trevarj.motd.irc.agentwire.decodeAgentwireValue
import io.github.trevarj.motd.irc.client.SequencedIrcEvent
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.messageContextOrNull

/**
 * The ViewModel's IRC-event boundary.  It admits backend state only from the account that the
 * channel topic provisions, never from the first account that answers a sync request.
 */
internal class AgentwireEventIngestor(
    private val reducer: AgentwireReducer = AgentwireReducer(),
    private val reassembler: AgentwireReassembler = AgentwireReassembler(),
) {
    sealed interface Result {
        data object Ignored : Result
        data class Rejected(val state: AgentwireUiState) : Result
        data object ReassemblyExpired : Result
        data class Applied(val state: AgentwireUiState, val envelope: AgentwireEnvelope) : Result
    }

    fun reset() {
        reducer.reset()
        reassembler.clear()
    }

    fun ingest(
        state: AgentwireUiState,
        event: IrcEvent,
        syncId: String?,
        accept: (AgentwireEnvelope) -> Boolean = { true },
    ): Result {
        if (event is IrcEvent.PlaybackBatch || event is IrcEvent.HistoryBatch || event is IrcEvent.ReplayBatch) {
            return Result.Ignored
        }
        val context = event.messageContextOrNull() ?: return Result.Ignored
        val raw = context.clientTags[AGENTWIRE_TAG] ?: return Result.Ignored
        val target = when (event) {
            is IrcEvent.ChatMessage -> event.target
            is IrcEvent.TagMessage -> event.target
            else -> return Result.Ignored
        }
        if (!target.equals(state.channel, ignoreCase = true)) return Result.Ignored
        val account = context.account?.takeUnless { it == "*" } ?: return Result.Ignored
        val trusted = state.backendAccount ?: return Result.Ignored
        if (!account.equals(trusted, ignoreCase = true)) {
            // Actions are authorized separately by controllerAccount. They never authenticate
            // events, unless the topic deliberately provisions that same account as the backend.
            if (account.equals(state.controllerAccount, ignoreCase = true)) return Result.Ignored
            return Result.Rejected(state.copy(error = "Rejected Agentwire event from untrusted account: $account"))
        }
        if (reassembler.expire()) return Result.ReassemblyExpired
        val decoded = decodeAgentwireValue(raw).getOrElse {
            return Result.Rejected(state.copy(error = "Invalid Agentwire message: ${it.message}"))
        }
        val envelope = when (decoded) {
            is AgentwireValue.Envelope -> decoded.value
            is AgentwireValue.Fragment -> reassembler.accept(decoded.value).getOrElse {
                return Result.Rejected(state.copy(error = "Invalid Agentwire fragments: ${it.message}"))
            } ?: return Result.Ignored
        }
        if (envelope.type != "event") return Result.Ignored
        val pinned = state.botAccount
        val candidate = if (pinned == null) {
            if (envelope.kind != "agent.hello" || envelope.reply != syncId) return Result.Ignored
            state.copy(botAccount = account)
        } else if (!account.equals(pinned, ignoreCase = true)) {
            return Result.Rejected(state.copy(error = "Rejected Agentwire event from untrusted account: $account"))
        } else {
            state
        }
        if (!acceptsAgentwireEpoch(envelope, candidate.epoch)) return Result.Ignored
        if (!accept(envelope)) return Result.Ignored
        return Result.Applied(reducer.reduce(candidate, envelope), envelope)
    }
}

/**
 * Keeps Agentwire's derived state aligned with the client's bounded observer stream. Any missing
 * sequence invalidates the current epoch: only a newly correlated hello and snapshot may restore
 * live reduction.
 */
internal class AgentwireDeliveryCoordinator(
    private val ingestor: AgentwireEventIngestor = AgentwireEventIngestor(),
) {
    sealed interface Result {
        data object Ignored : Result
        data class Updated(
            val state: AgentwireUiState,
            val envelope: AgentwireEnvelope,
            val syncCompleted: Boolean,
        ) : Result
        data class Rejected(val state: AgentwireUiState) : Result
        data class ResyncRequired(val state: AgentwireUiState, val reason: String) : Result
    }

    private var lastSequence: Long? = null
    private var syncId: String? = null
    private var syncHello = false

    fun reset() {
        lastSequence = null
        syncId = null
        syncHello = false
        ingestor.reset()
    }

    fun beginSync(state: AgentwireUiState, error: String? = null): AgentwireUiState {
        syncId = null
        syncHello = false
        ingestor.reset()
        return state.awaitingAgentwireSync(error)
    }

    fun syncRequested(id: String) {
        syncId = id
    }

    fun ingest(state: AgentwireUiState, delivered: SequencedIrcEvent): Result {
        val prior = lastSequence
        lastSequence = delivered.sequence
        if (prior != null && delivered.sequence != prior + 1) {
            return resync(state, "Agentwire event stream gap; resynchronizing")
        }
        val result = ingestor.ingest(
            state = state,
            event = delivered.event,
            syncId = syncId,
            accept = { envelope ->
                if (!state.syncing) {
                    true
                } else {
                    when (envelope.kind) {
                        "agent.hello" -> envelope.reply == syncId
                        "channel.snapshot" -> syncHello && envelope.reply == syncId && envelope.epoch == state.epoch
                        else -> false
                    }
                }
            },
        )
        return when (result) {
            AgentwireEventIngestor.Result.Ignored -> Result.Ignored
            AgentwireEventIngestor.Result.ReassemblyExpired -> resync(
                state,
                "Agentwire fragment assembly expired; resynchronizing",
            )
            is AgentwireEventIngestor.Result.Rejected -> Result.Rejected(result.state)
            is AgentwireEventIngestor.Result.Applied -> {
                val hello = result.envelope.kind == "agent.hello" && result.envelope.reply == syncId
                if (hello) syncHello = true
                val complete = state.syncing && result.envelope.kind == "channel.snapshot" &&
                    result.envelope.reply == syncId && syncHello &&
                    result.envelope.epoch == result.state.epoch
                Result.Updated(result.state, result.envelope, complete)
            }
        }
    }

    private fun resync(state: AgentwireUiState, reason: String): Result.ResyncRequired =
        Result.ResyncRequired(beginSync(state, reason), reason)
}

/** Owns Agentwire's sync correlation while the ViewModel owns the lifecycle of its retry job. */
internal class AgentwireSessionOrchestrator(
    private val delivery: AgentwireDeliveryCoordinator = AgentwireDeliveryCoordinator(),
) {
    fun reset() = delivery.reset()

    fun beginSync(state: AgentwireUiState, error: String? = null): AgentwireUiState =
        delivery.beginSync(state, error)

    fun syncRequested(id: String) = delivery.syncRequested(id)

    fun ingest(state: AgentwireUiState, event: SequencedIrcEvent): AgentwireDeliveryCoordinator.Result =
        delivery.ingest(state, event)

    suspend fun retryUntilReady(
        state: () -> AgentwireUiState,
        issue: suspend (String) -> Unit,
    ) {
        retryAgentwireSync(
            isReady = { !state().syncing },
            issue = { id ->
                // Record before sending so a fast matching reply is never discarded as stale.
                syncRequested(id)
                issue(id)
            },
        )
    }
}

/** Clears all data derived from an Agentwire epoch before a replacement snapshot is accepted. */
internal fun AgentwireUiState.awaitingAgentwireSync(error: String? = null): AgentwireUiState = copy(
    syncing = true,
    epoch = null,
    botAccount = null,
    activeSid = null,
    cwd = null,
    busy = false,
    currentTid = null,
    actions = emptySet(),
    supportedSettings = emptySet(),
    settings = emptyMap(),
    modelOptions = emptyList(),
    workspaceChildren = emptyMap(),
    liveSessions = emptyList(),
    workspaceSessions = emptyMap(),
    loadedSessionDirectories = emptySet(),
    queue = emptyList(),
    requests = emptyList(),
    timeline = emptyList(),
    sessionStatuses = emptyMap(),
    subagents = emptyList(),
    actionStatus = emptyMap(),
    historyLoading = false,
    historyPage = null,
    historyRequestId = null,
    historySid = null,
    historyCursor = null,
    historyStaged = emptyList(),
    historyBeforeAt = null,
    olderHistoryAvailable = false,
    error = error,
    autoReviewConfirmed = false,
)
