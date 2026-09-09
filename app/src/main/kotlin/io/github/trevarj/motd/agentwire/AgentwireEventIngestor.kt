package io.github.trevarj.motd.agentwire

import io.github.trevarj.motd.irc.agentwire.AGENTWIRE_TAG
import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.AgentwireReassembler
import io.github.trevarj.motd.irc.agentwire.AgentwireValue
import io.github.trevarj.motd.irc.agentwire.decodeAgentwireValue
import io.github.trevarj.motd.irc.client.SequencedIrcEvent
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.messageContextOrNull

/** Envelope validation failures inside one handshake before it is called a protocol mismatch. */
internal const val AGENTWIRE_PROTOCOL_FAILURE_LIMIT = 3

private const val AGENTWIRE_STALE_EPOCH = "stale or missing live epoch"

private fun AgentwireEnvelope.isStaleEpochFailure(): Boolean = kind == "action.failed" && data?.string("message") == AGENTWIRE_STALE_EPOCH

internal fun acceptsAgentwireEpoch(
    envelope: AgentwireEnvelope,
    currentEpoch: String?,
): Boolean =
    envelope.kind == "agent.hello" || envelope.history == true || currentEpoch == null ||
        envelope.epoch == currentEpoch

/**
 * The ViewModel's IRC-event boundary.  It admits backend state only from the account that the
 * channel topic provisions, never from the first account that answers a sync request.
 *
 * Every path that declines an event names itself with an [IgnoreReason]; the coordinator tallies
 * those so a handshake that ends in silence can still say what it saw.
 */
internal class AgentwireEventIngestor(
    private val reducer: AgentwireReducer = AgentwireReducer(),
    private val reassembler: AgentwireReassembler = AgentwireReassembler(),
) {
    sealed interface Result {
        data class Ignored(
            val why: IgnoreReason,
        ) : Result

        /** [protocolFailure] marks a malformed envelope, as opposed to an untrusted sender. */
        data class Rejected(
            val state: AgentwireUiState,
            val protocolFailure: Boolean,
            val detail: String,
            /** Present for an untrusted sender; fingerprinted, never logged raw. */
            val account: String? = null,
        ) : Result

        data object ReassemblyExpired : Result

        data class Applied(
            val state: AgentwireUiState,
            val envelope: AgentwireEnvelope,
        ) : Result
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
            return Result.Ignored(IgnoreReason.PLAYBACK)
        }
        val context = event.messageContextOrNull() ?: return Result.Ignored(IgnoreReason.NOT_PROTOCOL)
        val raw = context.clientTags[AGENTWIRE_TAG] ?: return Result.Ignored(IgnoreReason.NOT_PROTOCOL)
        val target =
            when (event) {
                is IrcEvent.ChatMessage -> event.target
                is IrcEvent.TagMessage -> event.target
                else -> return Result.Ignored(IgnoreReason.NOT_PROTOCOL)
            }
        if (!target.equals(state.channel, ignoreCase = true)) return Result.Ignored(IgnoreReason.TARGET_MISMATCH)
        val account =
            context.account?.takeUnless { it == "*" }
                ?: return Result.Ignored(IgnoreReason.MISSING_ACCOUNT)
        val trusted = state.backendAccount ?: return Result.Ignored(IgnoreReason.NO_TRUST_ANCHOR)
        if (!account.equals(trusted, ignoreCase = true)) {
            // Actions are authorized separately by controllerAccount. They never authenticate
            // events, unless the topic deliberately provisions that same account as the backend.
            if (account.equals(state.controllerAccount, ignoreCase = true)) {
                return Result.Ignored(IgnoreReason.CONTROLLER_EVENT)
            }
            return untrusted(state, account, trusted)
        }
        if (reassembler.expire()) return Result.ReassemblyExpired
        val decoded =
            decodeAgentwireValue(raw).getOrElse {
                return protocolFailure(state, "Invalid Agentwire message: ${it.message}")
            }
        val envelope =
            when (decoded) {
                is AgentwireValue.Envelope -> {
                    decoded.value
                }

                is AgentwireValue.Fragment -> {
                    reassembler.accept(decoded.value).getOrElse {
                        return protocolFailure(state, "Invalid Agentwire fragments: ${it.message}")
                    } ?: return Result.Ignored(IgnoreReason.FRAGMENT_PENDING)
                }
            }
        if (envelope.type != "event") return Result.Ignored(IgnoreReason.NOT_EVENT)
        val correlated = syncId != null && envelope.reply == syncId
        val pinned = state.botAccount
        val candidate =
            if (pinned == null) {
                when {
                    // Only a correlated hello may pin the backend identity for this epoch.
                    envelope.kind == "agent.hello" && correlated -> state.copy(botAccount = account)

                    // A correlated action.failed is the bridge's definitive answer to our own request.
                    // It carries no epoch and must stay visible, or a wire-level "no" is
                    // indistinguishable from silence.
                    envelope.kind == "action.failed" && correlated -> state

                    envelope.kind == "agent.hello" -> return Result.Ignored(IgnoreReason.STALE_REPLY)

                    else -> return Result.Ignored(IgnoreReason.UNCORRELATED_HELLO)
                }
            } else if (!account.equals(pinned, ignoreCase = true)) {
                return untrusted(state, account, pinned)
            } else {
                state
            }
        // A correlated sync failure and the bridge's explicit stale-epoch rejection must cross
        // the epoch gate: the former has no live epoch, and the latter carries its replacement.
        val epochExempt =
            (correlated && envelope.kind == "action.failed") || envelope.isStaleEpochFailure()
        if (!epochExempt && !acceptsAgentwireEpoch(envelope, candidate.epoch)) {
            return Result.Ignored(IgnoreReason.EPOCH_MISMATCH)
        }
        if (!accept(envelope)) return Result.Ignored(IgnoreReason.FILTERED)
        val reduced =
            runCatching { reducer.reduce(candidate, envelope) }.getOrElse {
                return protocolFailure(candidate, "Invalid Agentwire event data: ${it.message}")
            }
        return Result.Applied(reduced, envelope)
    }

    private fun untrusted(
        state: AgentwireUiState,
        account: String,
        trusted: String,
    ): Result.Rejected {
        // Shown to the user, never logged: the diagnostic journal only records a fingerprint.
        val detail = "Ignoring agent events from account $account. The channel topic trusts only agent=$trusted."
        return Result.Rejected(state.copy(error = detail), protocolFailure = false, detail = detail, account = account)
    }

    private fun protocolFailure(
        state: AgentwireUiState,
        detail: String,
    ): Result.Rejected = Result.Rejected(state.copy(error = detail), protocolFailure = true, detail = detail)
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
        data class Ignored(
            val why: IgnoreReason,
        ) : Result

        data class Updated(
            val state: AgentwireUiState,
            val envelope: AgentwireEnvelope,
            val syncCompleted: Boolean,
        ) : Result

        /** [untrustedAccount] is set when the sender is not the topic-provisioned backend. */
        data class Rejected(
            val state: AgentwireUiState,
            val untrustedAccount: String? = null,
        ) : Result

        /** The bridge answered the live sync id with `action.failed`. */
        data class SyncRejected(
            val state: AgentwireUiState,
            val detail: String,
        ) : Result

        /** Repeated envelope validation failures inside one handshake. */
        data class ProtocolMismatch(
            val state: AgentwireUiState,
            val detail: String,
        ) : Result

        data class ResyncRequired(
            val state: AgentwireUiState,
            val reason: String,
            val cause: AgentwireResyncCause,
            val sequenceDelta: Long?,
        ) : Result
    }

    private var lastSequence: Long? = null
    private var syncId: String? = null
    private var syncHello = false

    /**
     * The handshake gate lives here rather than in the UI state: the ViewModel owns the visible
     * phase, and the accept filter must not depend on a value the UI is free to rewrite.
     */
    private var awaitingSync = false
    private var protocolFailures = 0
    private val ignored = LinkedHashMap<IgnoreReason, Int>()

    val awaitingSyncNow: Boolean get() = awaitingSync

    fun reset() {
        lastSequence = null
        syncId = null
        syncHello = false
        awaitingSync = false
        protocolFailures = 0
        ignored.clear()
        ingestor.reset()
    }

    fun beginSync(
        state: AgentwireUiState,
        error: String? = null,
    ): AgentwireUiState {
        syncId = null
        syncHello = false
        awaitingSync = true
        protocolFailures = 0
        ignored.clear()
        ingestor.reset()
        return state.awaitingAgentwireSync(error)
    }

    fun syncRequested(id: String) {
        syncId = id
    }

    /** Every non-advancing delivery seen since the current handshake began. */
    fun ignoreCounters(): IgnoreCounters = IgnoreCounters(ignored.toMap())

    fun ingest(
        state: AgentwireUiState,
        delivered: SequencedIrcEvent,
    ): Result {
        val prior = lastSequence
        lastSequence = delivered.sequence
        // The sequence is the client's global observer counter, not an agentwire-only one, so any
        // DROP_OLDEST loss anywhere on the connection forces a resync here. Acceptable at the
        // observer's capacity of 4096, and the `resync` diagnostic now says how often it fires.
        if (prior != null && delivered.sequence != prior + 1) {
            return resync(
                state,
                "Agentwire event stream gap; resynchronizing",
                AgentwireResyncCause.GAP,
                delivered.sequence - prior,
            )
        }
        val result =
            ingestor.ingest(
                state = state,
                event = delivered.event,
                syncId = syncId,
                accept = { envelope ->
                    if (!awaitingSync) {
                        true
                    } else {
                        when (envelope.kind) {
                            "agent.hello" -> envelope.reply == syncId

                            "channel.snapshot" -> syncHello && envelope.reply == syncId && envelope.epoch == state.epoch

                            // A definitive failure answer to our own request must not be filtered out.
                            "action.failed" -> envelope.reply == syncId

                            else -> false
                        }
                    }
                },
            )
        return when (result) {
            is AgentwireEventIngestor.Result.Ignored -> {
                count(result.why)
                Result.Ignored(result.why)
            }

            AgentwireEventIngestor.Result.ReassemblyExpired -> {
                resync(
                    state,
                    "Agentwire fragment assembly expired; resynchronizing",
                    AgentwireResyncCause.FRAGMENT_EXPIRY,
                    null,
                )
            }

            is AgentwireEventIngestor.Result.Rejected -> {
                rejected(result)
            }

            is AgentwireEventIngestor.Result.Applied -> {
                applied(state, result)
            }
        }
    }

    private fun rejected(result: AgentwireEventIngestor.Result.Rejected): Result {
        if (!result.protocolFailure) {
            count(IgnoreReason.UNTRUSTED_ACCOUNT)
            return Result.Rejected(result.state, result.account)
        }
        protocolFailures += 1
        if (awaitingSync && protocolFailures >= AGENTWIRE_PROTOCOL_FAILURE_LIMIT) {
            awaitingSync = false
            return Result.ProtocolMismatch(result.state, result.detail)
        }
        return Result.Rejected(result.state)
    }

    private fun applied(
        state: AgentwireUiState,
        result: AgentwireEventIngestor.Result.Applied,
    ): Result {
        val envelope = result.envelope
        if (!awaitingSync && envelope.isStaleEpochFailure()) {
            return resync(
                result.state,
                "Agentwire epoch changed; resynchronizing",
                AgentwireResyncCause.EPOCH,
                null,
            )
        }
        val correlated = envelope.reply != null && envelope.reply == syncId
        if (awaitingSync && envelope.kind == "action.failed" && correlated) {
            awaitingSync = false
            val detail = result.state.error ?: "The bridge did not accept the sync request."
            return Result.SyncRejected(result.state, detail)
        }
        if (envelope.kind == "agent.hello" && correlated) syncHello = true
        val complete =
            awaitingSync && envelope.kind == "channel.snapshot" && correlated && syncHello &&
                envelope.epoch == result.state.epoch
        if (complete) awaitingSync = false
        return Result.Updated(result.state, envelope, complete)
    }

    private fun count(why: IgnoreReason) {
        ignored[why] = (ignored[why] ?: 0) + 1
    }

    private fun resync(
        state: AgentwireUiState,
        reason: String,
        cause: AgentwireResyncCause,
        sequenceDelta: Long?,
    ): Result.ResyncRequired = Result.ResyncRequired(beginSync(state, reason), reason, cause, sequenceDelta)
}

/** Owns Agentwire's sync correlation while the ViewModel owns the lifecycle of its retry job. */
internal class AgentwireSessionOrchestrator(
    private val delivery: AgentwireDeliveryCoordinator = AgentwireDeliveryCoordinator(),
) {
    fun reset() = delivery.reset()

    fun beginSync(
        state: AgentwireUiState,
        error: String? = null,
    ): AgentwireUiState = delivery.beginSync(state, error)

    fun syncRequested(id: String) = delivery.syncRequested(id)

    fun ignoreCounters(): IgnoreCounters = delivery.ignoreCounters()

    val awaitingSync: Boolean get() = delivery.awaitingSyncNow

    fun ingest(
        state: AgentwireUiState,
        event: SequencedIrcEvent,
    ): AgentwireDeliveryCoordinator.Result = delivery.ingest(state, event)

    suspend fun retryUntilReady(
        budget: AgentwireSyncBudget,
        isReady: () -> Boolean,
        issue: suspend (String) -> Boolean,
        onAttempt: (Int) -> Unit = {},
        onTimeout: suspend () -> Unit = {},
        onSendFailed: suspend (Int) -> Unit = {},
    ) {
        retryAgentwireSync(
            budget = budget,
            isReady = isReady,
            issue = { id ->
                // Record before sending so a fast matching reply is never discarded as stale.
                syncRequested(id)
                issue(id)
            },
            onAttempt = onAttempt,
            onTimeout = onTimeout,
            onSendFailed = onSendFailed,
        )
    }
}

/** Clears all data derived from an Agentwire epoch before a replacement snapshot is accepted. */
internal fun AgentwireUiState.awaitingAgentwireSync(error: String? = null): AgentwireUiState =
    copy(
        epoch = null,
        botAccount = null,
        activeSid = null,
        cwd = null,
        busy = false,
        currentTid = null,
        actions = emptySet(),
        capabilities = emptySet(),
        diagnosticsLoading = false,
        diagnosticsStale = diagnosticReport != null,
        diagnosticsError = null,
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
