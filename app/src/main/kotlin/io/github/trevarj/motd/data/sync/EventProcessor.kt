package io.github.trevarj.motd.data.sync

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.UserEntity
import io.github.trevarj.motd.bouncer.redactBouncerServCommand
import io.github.trevarj.motd.bouncer.redactBouncerServReply
import io.github.trevarj.motd.diagnostics.AutoFollowTrace
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.replyReference
import io.github.trevarj.motd.irc.proto.unreactionValue
import io.github.trevarj.motd.service.IrcEventSink
import io.github.trevarj.motd.service.RoomReactionMutationStore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * The sole IRC→Room writer (plans/04 mapping table). Implements [IrcEventSink]: every per-network
 * collector, the catch-up path, the RemoteMediator, the pending-send insert, and the push path
 * all funnel through [process]. Never writes state from anywhere else.
 *
 * Per-network mutable helpers (self nick, mention regex, Isupport-normalizer) are kept in a small
 * [NetworkState] cache keyed by network id and rebuilt on Registered / NickChanged.
 */
@Singleton
class EventProcessor @Inject constructor(
    private val db: MotdDatabase,
    private val typing: TypingTrackerImpl,
    private val notifier: MessageNotifier,
    private val chatSoundPlayer: ChatSoundPlayer = ChatSoundPlayer.Noop,
    private val bufferStore: BufferStore = BufferStore(db),
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
) : IrcEventSink {

    private val networkDao get() = db.networkDao()
    private val bufferDao get() = db.bufferDao()
    private val messageDao get() = db.messageDao()
    private val memberDao get() = db.memberDao()
    private val reactionDao get() = db.reactionDao()
    private val userDao get() = db.userDao()
    private val reactionMutations = RoomReactionMutationStore(db)
    private val sequencer = NetworkEventSequencer()

    private val states = ConcurrentHashMap<Long, NetworkState>()
    private val rosterSnapshots = ConcurrentHashMap<RosterKey, MutableList<RosterDelta>>()

    private data class RosterKey(val networkId: Long, val bufferId: Long)

    private sealed interface RosterDelta {
        data class Upsert(val nick: String) : RosterDelta
        data class Remove(val nick: String) : RosterDelta
        data class Rename(val from: String, val to: String) : RosterDelta
        data class DeferredQuit(val event: IrcEvent.Quit) : RosterDelta
        data class DeferredNick(val event: IrcEvent.NickChanged) : RosterDelta
        data class Prefix(val nick: String, val prefix: Char, val adding: Boolean) : RosterDelta
    }

    private data class DeferredRosterPresentation(
        val ctx: MessageContext,
        val kind: MessageKind,
        val sender: String,
        val text: String,
    )

    private data class RosterReplay(
        val members: List<MemberEntity>,
        val presentations: List<DeferredRosterPresentation>,
    )

    /** Per-network state for self-nick tracking, mention regex and case normalization. */
    private class NetworkState(
        @Volatile var selfNick: String,
        @Volatile var caseMapping: String,
        @Volatile var prefixModes: Map<Char, Char> = emptyMap(),
        @Volatile var chanModes: List<Set<Char>> = emptyList(),
    ) {
        @Volatile var mentionRegex: Regex = buildMentionRegex(selfNick)

        fun setNick(nick: String) {
            selfNick = nick
            mentionRegex = buildMentionRegex(nick)
        }

        /** RFC1459-ish case-insensitive normalization for buffer/member keys. */
        fun normalize(name: String): String {
            val map = when (caseMapping.lowercase()) {
                "ascii" -> { c: Char -> if (c in 'A'..'Z') c + 32 else c }
                else -> { c: Char ->
                    // rfc1459: additionally []\~ fold to {}|^
                    when (c) {
                        in 'A'..'Z' -> c + 32
                        '[' -> '{'
                        ']' -> '}'
                        '\\' -> '|'
                        '~' -> '^'
                        else -> c
                    }
                }
            }
            return buildString(name.length) { for (ch in name) append(map(ch)) }
        }

        companion object {
            fun buildMentionRegex(nick: String): Regex =
                Regex("(?<![\\w])" + Regex.escape(nick) + "(?![\\w])", RegexOption.IGNORE_CASE)
        }
    }

    private suspend fun stateFor(networkId: Long): NetworkState =
        states.getOrPut(networkId) {
            val n = networkDao.byId(networkId)
            NetworkState(selfNick = n?.nick ?: "", caseMapping = "rfc1459")
        }

    /** Test/setup seam; production registration enters through [process]. */
    internal suspend fun onRegistered(networkId: Long, nick: String, isupport: Map<String, String>) {
        sequencer.withNetwork(networkId) { applyRegistered(networkId, nick, isupport) }
    }

    private fun applyRegistered(networkId: Long, nick: String, isupport: Map<String, String>) {
        val cm = isupport["CASEMAPPING"] ?: "rfc1459"
        states[networkId] = NetworkState(
            selfNick = nick,
            caseMapping = cm,
            prefixModes = parsePrefixModes(isupport["PREFIX"]),
            chanModes = isupport["CHANMODES"]?.split(',')?.map(String::toSet).orEmpty(),
        )
    }

    override suspend fun process(networkId: Long, event: IrcEvent) {
        sequencer.withNetwork(networkId) {
            processEvent(networkId, event, EventOrigin.LIVE)
        }
    }

    override suspend fun processPush(networkId: Long, event: IrcEvent) {
        sequencer.withNetwork(networkId) {
            processEvent(networkId, event, EventOrigin.PUSH)
        }
    }

    /** Persist one event according to its provenance and, for history, its enclosing target. */
    private suspend fun processEvent(
        networkId: Long,
        event: IrcEvent,
        origin: EventOrigin,
        historyTarget: String? = null,
    ) {
        diagnostics.record("event_processor", "event_received") {
            mapOf(
                "network_id" to networkId,
                "origin" to origin.name,
                "type" to event::class.simpleName,
            )
        }
        if (!origin.accepts(event)) {
            diagnostics.record("event_processor", "event_ignored") {
                mapOf("network_id" to networkId, "origin" to origin.name, "type" to event::class.simpleName)
            }
            return
        }
        when (event) {
            is IrcEvent.Registered -> if (origin.mutatesSessionState) {
                applyRegistered(networkId, event.nick, event.isupport)
            }
            is IrcEvent.ChatMessage -> onChat(networkId, event, origin)
            is IrcEvent.TagMessage -> onTag(networkId, event, origin)
            is IrcEvent.HistoryBatch -> onHistoryBatch(networkId, event)
            is IrcEvent.NetworkBatch -> onNetworkBatch(networkId, event, origin, historyTarget)
            is IrcEvent.Joined -> if (origin == EventOrigin.LIVE) onJoined(networkId, event) else if (origin == EventOrigin.HISTORY) onHistoricalJoined(networkId, event)
            is IrcEvent.Parted -> if (origin == EventOrigin.LIVE) onParted(networkId, event) else if (origin == EventOrigin.HISTORY) onHistoricalParted(networkId, event)
            is IrcEvent.Quit -> if (origin == EventOrigin.LIVE) onQuit(networkId, event) else if (origin == EventOrigin.HISTORY) onHistoricalQuit(networkId, event, historyTarget)
            is IrcEvent.Kicked -> if (origin == EventOrigin.LIVE) onKicked(networkId, event) else if (origin == EventOrigin.HISTORY) onHistoricalKicked(networkId, event)
            is IrcEvent.NickChanged -> if (origin == EventOrigin.LIVE) onNickChanged(networkId, event) else if (origin == EventOrigin.HISTORY) onHistoricalNickChanged(networkId, event, historyTarget)
            is IrcEvent.NamesStarted -> if (origin.mutatesSessionState) onNamesStarted(networkId, event)
            is IrcEvent.Names -> if (origin.mutatesSessionState) onNames(networkId, event)
            is IrcEvent.TopicChanged -> when (origin) {
                EventOrigin.LIVE -> onTopicChanged(networkId, event)
                EventOrigin.HISTORY -> onHistoricalTopicChanged(networkId, event)
                EventOrigin.PUSH -> Unit
            }
            is IrcEvent.ModeChanged -> when (origin) {
                EventOrigin.LIVE -> onModeChanged(networkId, event)
                EventOrigin.HISTORY -> onHistoricalModeChanged(networkId, event)
                EventOrigin.PUSH -> Unit
            }
            is IrcEvent.AwayChanged -> if (origin.mutatesSessionState) upsertUser(networkId, event.nick) { it.copy(away = event.awayMessage != null) }
            is IrcEvent.AccountChanged -> if (origin.mutatesSessionState) upsertUser(networkId, event.nick) { it.copy(account = event.account) }
            is IrcEvent.HostChanged -> if (origin.mutatesSessionState) upsertUser(networkId, event.nick) { it.copy(hostmask = "${event.newUser}@${event.newHost}") }
            is IrcEvent.RealnameChanged -> if (origin.mutatesSessionState) upsertUser(networkId, event.nick) { it.copy(realname = event.realname) }
            is IrcEvent.WhoxRow -> if (origin.mutatesSessionState) onWhoxRow(networkId, event)
            is IrcEvent.WhoxComplete -> Unit
            is IrcEvent.MonitorOnline -> if (origin.mutatesSessionState) onMonitorOnline(networkId, event)
            is IrcEvent.MonitorOffline,
            is IrcEvent.MonitorList,
            is IrcEvent.MonitorListEnd,
            -> Unit
            is IrcEvent.MonitorLimitExceeded -> if (origin.mutatesSessionState) onMonitorLimitExceeded(networkId, event)
            is IrcEvent.Invited -> onInvited(networkId, event, origin)
            is IrcEvent.ReadMarker -> if (origin.mutatesSessionState) onReadMarker(networkId, event)
            is IrcEvent.BouncerNetworkState -> if (origin.mutatesSessionState) onBouncerNetworkState(networkId, event)
            is IrcEvent.Disconnected -> if (origin.mutatesSessionState) onDisconnected(networkId, event)
            is IrcEvent.ServerError -> if (origin.mutatesSessionState) onServerError(networkId, event)
            is IrcEvent.Raw -> onRaw(networkId, event, origin)
            is IrcEvent.CapsChanged,
            -> Unit // not persisted
        }
    }

    // -- chat / tags ---------------------------------------------------------

    private suspend fun onChat(networkId: Long, e: IrcEvent.ChatMessage, origin: EventOrigin) {
        val st = stateFor(networkId)
        val isDm = !isChannel(e.target, st)
        // Server-sourced NOTICEs (empty source, or a source that looks like a host) go to the
        // SERVER buffer instead of spawning a junk QUERY buffer (plans/16 §5.6.1, Confirmed #5).
        // Channel NOTICEs are unaffected; NickServ/ChanServ (no dot) keep their query buffers.
        if (isDm && e.kind == IrcEvent.ChatKind.NOTICE && isServerSource(e.source.nick)) {
            val serverBufferId = ensureServerBuffer(networkId, st)
            if (origin == EventOrigin.PUSH && e.ctx.msgid == null) {
                // Preserve the universal transient-push rule even for the early server-NOTICE
                // routing path. SERVER notification policy intentionally suppresses the alert.
                maybeNotify(networkId, serverBufferId, BufferType.SERVER, false, e)
                return
            }
            insertSystem(serverBufferId, e.ctx, MessageKind.NOTICE, e.source.nick, e.text)
            return
        }
        // For a DM the buffer is keyed by the OTHER party's nick, not our own.
        val bufferName = if (isDm) {
            if (e.isSelf) e.target else e.source.nick
        } else {
            e.target
        }
        val type = if (isDm) BufferType.QUERY else BufferType.CHANNEL
        val bufferId = ensureBuffer(networkId, bufferName, type, st)
        val isBouncerServQuery = isDm && bufferName.equals("BouncerServ", ignoreCase = true)
        val storedText = when {
            !isBouncerServQuery -> e.text
            e.isSelf -> redactBouncerServCommand(e.text)
            else -> redactBouncerServReply(e.text)
        }
        val isRootServiceReply = isBouncerServQuery && !e.isSelf &&
            e.kind == IrcEvent.ChatKind.PRIVMSG && networkDao.byId(networkId)?.role == NetworkRole.BOUNCER_ROOT

        val replyReference = e.replyToMsgid
        val replyMentionsSelf = if (!e.isSelf && replyReference != null) {
            messageDao.byMsgid(bufferId, replyReference)?.let { parent ->
                parent.isSelf || st.normalize(parent.sender) == st.normalize(st.selfNick)
            } == true
        } else {
            false
        }
        val hasMention = !e.isSelf && !isRootServiceReply &&
            (replyMentionsSelf || st.mentionRegex.containsMatchIn(storedText))
        val identitySender = st.normalize(e.source.nick)

        traceMessageDecision("message_classified", networkId, bufferId, e, origin) {
            mapOf(
                "buffer_type" to type.name,
                "mention" to hasMention,
                "root_service" to isRootServiceReply,
            )
        }

        // Soju delays WebPush while its normal produce path synchronously appends the upstream line
        // to the configured message store. A msgid-less push is therefore transient delivery input,
        // not a second durable message identity: create the buffer so the notification deep link
        // and ConnectionManager's open-target catch-up include it, notify immediately, and let the
        // mandatory reconnect CHATHISTORY pass insert the canonical msgid row.
        // Msgid-less LIVE lines remain legitimate IRC messages and continue through persistence.
        if (origin == EventOrigin.PUSH && e.ctx.msgid == null) {
            traceMessageDecision("push_transient", networkId, bufferId, e, origin)
            maybeNotify(
                networkId,
                bufferId,
                type,
                hasMention,
                e.copy(text = storedText),
            )
            return
        }

        val row = MessageEntity(
            bufferId = bufferId,
            msgid = e.ctx.msgid,
            serverTime = e.ctx.serverTime,
            sender = e.source.nick,
            senderAccount = e.ctx.account,
            kind = kindOf(e.kind),
            text = storedText,
            isSelf = e.isSelf,
            hasMention = hasMention,
            replyToMsgid = e.replyToMsgid,
            dedupKey = EchoDeduper.keyFor(e.ctx, identitySender, storedText),
        )

        // Some bouncers omit draft/msgid on the live delivery but attach it when replaying the
        // same line through CHATHISTORY. Those two representations otherwise derive different
        // dedup keys (fallback fingerprint versus msgid), producing a duplicate exactly at the
        // reconnect boundary. Promote the already-stored fingerprint row to the durable msgid
        // before the normal insert path. The exact serverTime/sender/text fingerprint keeps
        // genuinely repeated messages distinct.
        val incomingMsgid = e.ctx.msgid
        if (incomingMsgid != null && messageDao.byMsgid(bufferId, incomingMsgid) == null) {
            val fallbackKey = EchoDeduper.keyFor(null, e.ctx.serverTime, identitySender, storedText)
            val fingerprintMatch = messageDao.byDedupKey(bufferId, fallbackKey)
            if (fingerprintMatch != null && fingerprintMatch.msgid == null) {
                val promoted = fingerprintMatch.copy(msgid = incomingMsgid, dedupKey = incomingMsgid)
                messageDao.update(promoted)
                traceMessageWrite("room_msgid_promote", promoted, e.ctx.batchId != null)
                return
            }
        }

        // A bouncer can replay a msgid-less representation after PUSH has already persisted its
        // durable msgid-bearing copy. The same can happen when the live socket receives the
        // upstream line after history. Retain the durable row and discard only an unambiguous
        // duplicate. Account/reply are enrichments rather than identity because optional tags can
        // differ between deliveries. Msgid-less PUSH returned above without writing.
        if (incomingMsgid == null && !e.isSelf && origin != EventOrigin.PUSH) {
            // A live delivery can be timestamped slightly differently from its durable push/history
            // representation, but HISTORY is itself the durable timeline. Fuzzy matching there can
            // collapse two legitimate identical messages sent seconds apart.
            val matchWindowMs = if (origin == EventOrigin.HISTORY) 0L else INCOMING_DELIVERY_MATCH_WINDOW_MS
            var candidates = messageDao.findDurableIncomingCandidates(
                bufferId = bufferId,
                sender = e.source.nick,
                kind = row.kind,
                text = storedText,
                lo = e.ctx.serverTime - matchWindowMs,
                hi = e.ctx.serverTime + matchWindowMs,
            )
            if (candidates.isEmpty()) {
                candidates = messageDao.findDurableIncomingCandidatesByText(
                    bufferId = bufferId,
                    kind = row.kind,
                    text = storedText,
                    lo = e.ctx.serverTime - matchWindowMs,
                    hi = e.ctx.serverTime + matchWindowMs,
                ).filter { st.normalize(it.sender) == identitySender }.take(2)
            }
            if (candidates.size == 1) {
                val durable = candidates.single()
                val reconciled = durable.copy(
                    senderAccount = durable.senderAccount ?: row.senderAccount,
                    replyToMsgid = durable.replyToMsgid ?: row.replyToMsgid,
                    hasMention = durable.hasMention || row.hasMention,
                )
                if (reconciled != durable) messageDao.update(reconciled)
                traceMessageWrite(
                    "room_incoming_duplicate",
                    reconciled,
                    fromHistory = false,
                )
                // Preserve the live notification decision while giving it the durable identity.
                // History never notifies; a transient push was already delivered above.
                if (origin.notifies) {
                    maybeNotify(
                        networkId,
                        bufferId,
                        type,
                        reconciled.hasMention,
                        e.copy(
                            ctx = e.ctx.copy(
                                msgid = reconciled.msgid,
                                serverTime = reconciled.serverTime,
                                account = reconciled.senderAccount,
                            ),
                            replyToMsgid = reconciled.replyToMsgid,
                        ),
                    )
                }
                return
            }
        }

        // Own message dedup (plans/03 echo degradation, plans/04 echo flow). A self-send surfaces
        // as exactly ONE row across all three server-capability scenarios:
        //  (a) echo-message + labeled-response: labeled echo updates the pending row in place;
        //  (b) echo-message only: no label to correlate, so the heuristic collapses the echo into
        //      the most recent matching local row (pending or already-confirmed-local);
        //  (c) neither: ConnectionManager's local insert is the only row (no echo ever arrives).
        // Later CHATHISTORY replays carry the confirmed msgid → dedupKey matches → INSERT IGNORE.
        if (e.isSelf) {
            // Idempotent replay short-circuit: a self message whose msgid already exists (e.g. a
            // CHATHISTORY replay of an already-confirmed row) is a no-op. Checked first with the
            // plain suspend DAO so the raw-query heuristic below never runs inside the history
            // transaction for the common replay case (transaction-thread safe).
            if (incomingMsgid != null && messageDao.byMsgid(bufferId, incomingMsgid) != null) {
                traceMessageDecision("room_existing_msgid", networkId, bufferId, e, origin)
                return
            }
            // (a) Labeled echo of a pending self-send: update the pending row in place.
            val label = e.ctx.label
            if (label != null) {
                val pending = messageDao.byPendingLabel(bufferId, label)
                if (pending != null) {
                    val confirmed = pending.copy(
                        msgid = e.ctx.msgid,
                        serverTime = e.ctx.serverTime,
                        dedupKey = EchoDeduper.keyFor(e.ctx, identitySender, storedText),
                        pendingLabel = null,
                        failed = false,
                    )
                    messageDao.update(confirmed)
                    traceMessageWrite("room_echo_update", confirmed, e.ctx.batchId != null)
                    return
                }
            }
            // (b) No labeled correlation: the incoming self ChatMessage may be the echo of a row we
            // already inserted locally (a still-pending row, or a confirmed-local row from the
            // no-labeled-response send path whose dedupKey is a sha1 over our LOCAL clock and thus
            // will never match the server echo's key). Match to the newest local self row for this
            // buffer with the same text inside the echo window and collapse into it, promoting the
            // real msgid/serverTime when the echo carries one. If none matches this is a genuinely
            // new self message (e.g. sent from another client) → fall through to a normal insert.
            val candidate = findSelfEchoCandidate(bufferId, storedText, e.ctx.serverTime)
            if (candidate != null) {
                // Only rewrite the dedupKey/time forward when the echo brings a real msgid; a
                // bare echo (no msgid) keeps the existing row so a later msgid-bearing CHATHISTORY
                // replay still dedups against it (its key was our local sha1 either way). Promoting
                // the dedupKey to the msgid cannot collide on the UNIQUE(bufferId, dedupKey) index:
                // the idempotent short-circuit above already returned if that msgid existed.
                val echoMsgid = e.ctx.msgid
                if (echoMsgid != null) {
                    val confirmed = candidate.copy(
                        msgid = echoMsgid,
                        serverTime = e.ctx.serverTime,
                        dedupKey = echoMsgid,
                        pendingLabel = null,
                        failed = false,
                    )
                    messageDao.update(confirmed)
                    traceMessageWrite("room_echo_update", confirmed, e.ctx.batchId != null)
                } else if (candidate.pendingLabel != null || candidate.failed) {
                    // Confirm a still-pending/failed row even without a msgid so the UI stops
                    // showing "sending…"/retry; keep its local dedupKey.
                    val confirmed = candidate.copy(pendingLabel = null, failed = false)
                    messageDao.update(confirmed)
                    traceMessageWrite("room_echo_update", confirmed, e.ctx.batchId != null)
                }
                return
            }
            // (c) Last-resort msgid promotion (goguma keys everything on draft/msgid). A self message
            // that arrives WITH a real msgid but missed the time-window heuristic above is very likely
            // the durable identity for a row we confirmed earlier from a BARE echo (no msgid) — that
            // row kept a local sha1/pending dedupKey and still has msgid = null. A reconnect
            // CHATHISTORY replay of that message lands here minutes/hours later, well outside the echo
            // window, so without this it would insert a SECOND row (the remaining double-send). Collapse
            // it onto the newest msgid-less self row of identical text, promoting the durable msgid so a
            // further replay short-circuits via byMsgid / the UNIQUE(bufferId, msgid) index. A genuinely
            // distinct second self-send is unaffected: its own echo already stamped it with its own
            // msgid, so it is never returned as a msgid-less candidate.
            if (incomingMsgid != null) {
                val orphan = messageDao.findSelfMsgidlessCandidate(bufferId, storedText)
                if (orphan != null) {
                    val confirmed = orphan.copy(
                        msgid = incomingMsgid,
                        dedupKey = incomingMsgid,
                        pendingLabel = null,
                        failed = false,
                    )
                    messageDao.update(confirmed)
                    traceMessageWrite("room_echo_update", confirmed, e.ctx.batchId != null)
                    return
                }
            }
        }

        val inserted = messageDao.insertAll(listOf(row)).single()
        if (inserted <= 0L) {
            traceMessageDecision("room_insert_ignored", networkId, bufferId, e, origin)
            return // dedup no-op
        }
        traceMessageWrite("room_insert", row.copy(id = inserted), e.ctx.batchId != null)
        if (isRootServiceReply && origin == EventOrigin.LIVE) {
            bufferDao.advanceReadMarker(bufferId, e.ctx.serverTime)
            return
        }
        if (origin == EventOrigin.LIVE && !e.isSelf) {
            chatSoundPlayer.onIncoming(bufferId, type, e)
        }
        if (origin.notifies) maybeNotify(networkId, bufferId, type, hasMention, e)
    }

    private suspend fun onTag(networkId: Long, e: IrcEvent.TagMessage, origin: EventOrigin) {
        val st = stateFor(networkId)
        val isDm = !isChannel(e.target, st)
        val sourceIsSelf = st.normalize(e.source.nick) == st.normalize(st.selfNick)
        val bufferName = if (isDm) {
            if (sourceIsSelf) e.target else e.source.nick
        } else {
            e.target
        }
        val type = if (isDm) BufferType.QUERY else BufferType.CHANNEL
        // typing: routed to tracker, never persisted.
        if (origin == EventOrigin.LIVE) e.typing?.let { typingState ->
            val bufferId = ensureBuffer(networkId, bufferName, type, st)
            typing.onTyping(bufferId, e.source.nick, typingState)
        }
        // react: upsert reaction row (keyed by target msgid).
        val emoji = e.reactEmoji
        val targetMsgid = e.reactTargetMsgid
        if (emoji != null && targetMsgid != null) {
            val bufferId = bufferDao.byName(networkId, st.normalize(bufferName))?.id ?: return
            // Keep an orphan temporarily when the target's echo/history row is still in flight.
            // Reaction queries are scoped to visible msgids, so it becomes visible atomically when
            // the parent arrives and remains inert if the reference never resolves.
            // Room's uniqueness constraint retains the historical raw-sender key for compatibility,
            // so remove any IRC-casefolded equivalent before inserting the display spelling from
            // this event. This reconciles optimistic "me" with an echo such as "Me" without
            // throwing away the casing users see in the timeline.
            reactionDao.observeForBuffer(bufferId).first()
                .filter { it.targetMsgid == targetMsgid && st.normalize(it.sender) == st.normalize(e.source.nick) }
                .forEach { reactionMutations.remove(it) }
            reactionDao.upsert(
                ReactionEntity(
                    bufferId = bufferId,
                    targetMsgid = targetMsgid,
                    sender = e.source.nick,
                    emoji = emoji,
                    serverTime = e.ctx.serverTime,
                ),
            )
        }
    }

    private suspend fun onHistoryBatch(networkId: Long, batch: IrcEvent.HistoryBatch) {
        // All events for one target are applied in a single Room transaction (idempotent by
        // dedupKey). They are historical replay, never live arrivals: persist them without posting
        // notifications even when a previously-missing row is a DM or mention.
        diagnostics.record("history", "batch_started") {
            mapOf(
                "network_id" to networkId,
                "target_fp" to diagnostics.fingerprint(batch.target),
                "events" to batch.events.size,
            )
        }
        db.withTransaction {
            for (ev in batch.events) processEvent(networkId, ev, EventOrigin.HISTORY, batch.target)
        }
        diagnostics.record("history", "batch_finished") {
            mapOf(
                "network_id" to networkId,
                "target_fp" to diagnostics.fingerprint(batch.target),
                "events" to batch.events.size,
            )
        }
    }

    private suspend fun onNetworkBatch(
        networkId: Long,
        batch: IrcEvent.NetworkBatch,
        origin: EventOrigin,
        historyTarget: String?,
    ) {
        if (batch.events.isEmpty()) return
        if (batch.kind == IrcEvent.NetworkBatchKind.NETSPLIT && batch.events.any { it !is IrcEvent.Quit }) return
        if (batch.kind == IrcEvent.NetworkBatchKind.NETJOIN && batch.events.any { it !is IrcEvent.Joined }) return
        if (origin == EventOrigin.HISTORY) {
            val target = batch.target ?: historyTarget ?: return
            val st = stateFor(networkId)
            val bufferId = ensureBuffer(networkId, target, BufferType.CHANNEL, st)
            val children = batch.events.map { child ->
                when (child) {
                    is IrcEvent.Quit -> child.nick to child.ctx
                    is IrcEvent.Joined -> child.nick to child.ctx
                    else -> error("validated network batch child")
                }
            }
            insertNetworkBatch(bufferId, batch, children, st)
            return
        }
        if (origin != EventOrigin.LIVE) return
        val st = stateFor(networkId)
        val affected = LinkedHashMap<Long, MutableList<Pair<String, MessageContext>>>()
        db.withTransaction {
            when (batch.kind) {
                IrcEvent.NetworkBatchKind.NETSPLIT -> batch.events.forEach { child ->
                    val quit = child as IrcEvent.Quit
                    val targetBufferId = batch.target?.let { target ->
                        bufferDao.byName(networkId, st.normalize(target))?.id
                    }
                    (buffersOfNick(networkId, quit.nick) + listOfNotNull(targetBufferId)).distinct().forEach { bufferId ->
                        memberDao.remove(bufferId, quit.nick)
                        journal(networkId, bufferId, RosterDelta.Remove(quit.nick))
                        affected.getOrPut(bufferId) { mutableListOf() } += quit.nick to quit.ctx
                    }
                }
                IrcEvent.NetworkBatchKind.NETJOIN -> batch.events.forEach { child ->
                    val join = child as IrcEvent.Joined
                    val buffer = bufferDao.byName(networkId, st.normalize(join.channel))
                        ?: return@forEach
                    memberDao.upsert(MemberEntity(buffer.id, join.nick))
                    journal(networkId, buffer.id, RosterDelta.Upsert(join.nick))
                    upsertUser(networkId, join.nick) {
                        it.copy(
                            account = join.account ?: it.account,
                            realname = join.realname ?: it.realname,
                        )
                    }
                    affected.getOrPut(buffer.id) { mutableListOf() } += join.nick to join.ctx
                }
            }
            affected.forEach { (bufferId, children) ->
                insertNetworkBatch(bufferId, batch, children, st)
            }
        }
    }

    private suspend fun insertNetworkBatch(
        bufferId: Long,
        batch: IrcEvent.NetworkBatch,
        children: List<Pair<String, MessageContext>>,
        st: NetworkState,
    ) {
        if (children.isEmpty()) return
        val buffer = bufferDao.observeById(bufferId) ?: return
        val nicks = children.map { it.first }
        val identities = children.map { (nick, ctx) ->
            ctx.msgid ?: "${st.normalize(nick)}@${ctx.serverTime / NETWORK_BATCH_DEDUP_WINDOW_MS}"
        }
        val pair = listOf(batch.serverA.lowercase(), batch.serverB.lowercase()).sorted().joinToString("|")
        val kind = if (batch.kind == IrcEvent.NetworkBatchKind.NETSPLIT) {
            MessageKind.NETSPLIT
        } else {
            MessageKind.NETJOIN
        }
        val eventKey = "network:${kind.name.lowercase()}:$pair:${buffer.name}:" +
            EchoDeduper.keyFor(null, 0, pair, identities.joinToString("|"))
        val verb = if (kind == MessageKind.NETSPLIT) "split" else "rejoined"
        val text = "${nicks.size} ${if (nicks.size == 1) "user" else "users"} $verb " +
            "(${batch.serverA} ↔ ${batch.serverB})"
        val row = MessageEntity(
            bufferId = bufferId,
            serverTime = children.maxOf { it.second.serverTime },
            sender = "",
            kind = kind,
            text = text,
            dedupKey = eventKey,
            eventKey = eventKey,
            eventPayload = NetworkBatchPayloadV1(batch.serverA, batch.serverB, nicks).encode(),
        )
        val inserted = messageDao.insertAll(listOf(row)).single()
        if (inserted > 0) traceMessageWrite("room_insert", row.copy(id = inserted), !children.any { it.second.batchId == null })
    }

    // -- invitations --------------------------------------------------------

    private suspend fun onInvited(networkId: Long, e: IrcEvent.Invited, origin: EventOrigin) {
        val st = stateFor(networkId)
        val selfInvite = st.normalize(e.nick) == st.normalize(st.selfNick)
        val validChannel = isChannel(e.channel, st)
        val existingChannel = if (validChannel) {
            bufferDao.byName(networkId, st.normalize(e.channel))
        } else {
            null
        }
        val bufferId = when {
            selfInvite && validChannel -> ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
            !selfInvite && existingChannel != null -> existingChannel.id
            else -> ensureServerBuffer(networkId, st)
        }
        val historical = origin == EventOrigin.HISTORY || e.ctx.batchId != null
        val actionable = selfInvite && validChannel && !historical
        val state = when {
            historical -> InviteState.HISTORICAL
            actionable -> InviteState.PENDING
            else -> InviteState.HISTORICAL
        }
        val payload = InvitePayloadV1(e.by, e.nick, e.channel)
        val eventKey = invitationEventKey(networkId, bufferId, e, st)
        val text = when {
            selfInvite && validChannel -> "${e.by.ifBlank { "Someone" }} invited you to ${e.channel}"
            validChannel -> "${e.by.ifBlank { "Someone" }} invited ${e.nick} to ${e.channel}"
            else -> "Received an invalid invitation for ${e.channel.ifBlank { "an unknown channel" }}"
        }
        val row = MessageEntity(
            bufferId = bufferId,
            msgid = e.ctx.msgid,
            serverTime = e.ctx.serverTime,
            sender = e.by,
            kind = MessageKind.INVITE,
            text = text,
            dedupKey = eventKey,
            eventKey = eventKey,
            eventPayload = payload.encode(),
            inviteState = state,
        )
        val inserted = messageDao.insertAll(listOf(row)).single()
        if (inserted <= 0L) return
        traceMessageWrite("room_insert", row.copy(id = inserted), historical)
        if (actionable) notifier.onInvitation(networkId, bufferId, inserted)
    }

    private fun invitationEventKey(
        networkId: Long,
        bufferId: Long,
        e: IrcEvent.Invited,
        st: NetworkState,
    ): String {
        e.ctx.msgid?.let { return "invite:msgid:$it" }
        val bucket = e.ctx.serverTime / INVITE_DEDUP_WINDOW_MS
        val identity = listOf(
            networkId.toString(),
            bufferId.toString(),
            st.normalize(e.by),
            st.normalize(e.nick),
            st.normalize(e.channel),
        ).joinToString("|")
        return "invite:fallback:" + EchoDeduper.keyFor(null, bucket, identity, "INVITE")
    }

    // -- membership ----------------------------------------------------------

    private suspend fun onJoined(networkId: Long, e: IrcEvent.Joined) {
        val st = stateFor(networkId)
        val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
        if (e.isSelf) markJoined(bufferId, true)
        memberDao.upsert(MemberEntity(bufferId, e.nick))
        if (e.ctx.batchId == null) journal(networkId, bufferId, RosterDelta.Upsert(e.nick))
        upsertUser(networkId, e.nick) { it.copy(account = e.account ?: it.account, realname = e.realname ?: it.realname) }
        if (e.isSelf) {
            if (e.ctx.batchId == null) {
                val resolvedInviteIds = messageDao.actionableInviteIds(bufferId)
                if (resolvedInviteIds.isNotEmpty()) {
                    messageDao.markInvitesJoined(bufferId)
                    resolvedInviteIds.forEach { notifier.onInvitationResolved(it) }
                }
            }
            // Bug: our own JOIN was re-inserted every time the buffer was (re)opened — CHATHISTORY
            // event-playback and each live re-JOIN produced a fresh "you joined" row because the
            // default hash key folds in the volatile serverTime. Make the self-join idempotent per
            // buffer: a stable dedupKey ("selfjoin:<bufferId>") collapses all self-joins to this
            // buffer (live re-joins AND playbacks) into a single system row via INSERT IGNORE.
            insertSystem(bufferId, e.ctx, MessageKind.JOIN, e.nick, "${e.nick} joined", dedupKey = "selfjoin:$bufferId")
        } else {
            insertSystem(bufferId, e.ctx, MessageKind.JOIN, e.nick, "${e.nick} joined")
        }
    }

    private suspend fun onHistoricalJoined(networkId: Long, e: IrcEvent.Joined) {
        val st = stateFor(networkId)
        val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
        val dedupKey = if (e.isSelf) "selfjoin:$bufferId" else null
        insertSystem(bufferId, e.ctx, MessageKind.JOIN, e.nick, "${e.nick} joined", dedupKey)
    }

    private suspend fun onParted(networkId: Long, e: IrcEvent.Parted) {
        val st = stateFor(networkId)
        val bufferId = bufferDao.byName(networkId, st.normalize(e.channel))?.id ?: return
        memberDao.remove(bufferId, e.nick)
        if (e.isSelf) {
            rosterSnapshots.remove(RosterKey(networkId, bufferId))
            memberDao.clear(bufferId)
            markJoined(bufferId, false)
        } else if (e.ctx.batchId == null) {
            journal(networkId, bufferId, RosterDelta.Remove(e.nick))
        }
        insertSystem(bufferId, e.ctx, MessageKind.PART, e.nick, "${e.nick} left" + (e.reason?.let { " ($it)" } ?: ""))
    }

    private suspend fun onHistoricalParted(networkId: Long, e: IrcEvent.Parted) {
        val st = stateFor(networkId)
        val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
        insertSystem(bufferId, e.ctx, MessageKind.PART, e.nick, "${e.nick} left" + (e.reason?.let { " ($it)" } ?: ""))
    }

    private suspend fun onQuit(networkId: Long, e: IrcEvent.Quit) {
        // Fan out to every buffer the nick was a member of.
        val buffers = buffersOfNick(networkId, e.nick)
        for (bufferId in buffers) {
            memberDao.remove(bufferId, e.nick)
            if (e.ctx.batchId == null) journal(networkId, bufferId, RosterDelta.Remove(e.nick))
            insertSystem(bufferId, e.ctx, MessageKind.QUIT, e.nick, "${e.nick} quit" + (e.reason?.let { " ($it)" } ?: ""))
        }
        if (e.ctx.batchId == null) {
            journalAcrossActiveSnapshots(networkId, buffers.toSet(), RosterDelta.DeferredQuit(e))
        }
    }

    private suspend fun onHistoricalQuit(networkId: Long, e: IrcEvent.Quit, target: String?) {
        val bufferId = historicalTargetBuffer(networkId, target) ?: return
        insertSystem(bufferId, e.ctx, MessageKind.QUIT, e.nick, "${e.nick} quit" + (e.reason?.let { " ($it)" } ?: ""))
    }

    private suspend fun onKicked(networkId: Long, e: IrcEvent.Kicked) {
        val st = stateFor(networkId)
        val bufferId = bufferDao.byName(networkId, st.normalize(e.channel))?.id ?: return
        memberDao.remove(bufferId, e.nick)
        if (e.isSelf) {
            rosterSnapshots.remove(RosterKey(networkId, bufferId))
            memberDao.clear(bufferId)
            markJoined(bufferId, false)
        } else if (e.ctx.batchId == null) {
            journal(networkId, bufferId, RosterDelta.Remove(e.nick))
        }
        insertSystem(bufferId, e.ctx, MessageKind.KICK, e.by, "${e.nick} was kicked by ${e.by}" + (e.reason?.let { " ($it)" } ?: ""))
    }

    private suspend fun onHistoricalKicked(networkId: Long, e: IrcEvent.Kicked) {
        val st = stateFor(networkId)
        val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
        insertSystem(bufferId, e.ctx, MessageKind.KICK, e.by, "${e.nick} was kicked by ${e.by}" + (e.reason?.let { " ($it)" } ?: ""))
    }

    private suspend fun onNickChanged(networkId: Long, e: IrcEvent.NickChanged) {
        val st = stateFor(networkId)
        if (e.isSelf) st.setNick(e.to)
        // Rename member rows across every buffer that had the old nick.
        val buffers = buffersOfNick(networkId, e.from)
        for (bufferId in buffers) {
            memberDao.remove(bufferId, e.from)
            memberDao.upsert(MemberEntity(bufferId, e.to))
            if (e.ctx.batchId == null) {
                journal(networkId, bufferId, RosterDelta.Rename(e.from, e.to))
            }
            insertSystem(bufferId, e.ctx, MessageKind.NICK, e.from, "${e.from} is now known as ${e.to}")
        }
        if (e.ctx.batchId == null) {
            journalAcrossActiveSnapshots(networkId, buffers.toSet(), RosterDelta.DeferredNick(e))
        }
    }

    private suspend fun onHistoricalNickChanged(networkId: Long, e: IrcEvent.NickChanged, target: String?) {
        val bufferId = historicalTargetBuffer(networkId, target) ?: return
        insertSystem(bufferId, e.ctx, MessageKind.NICK, e.from, "${e.from} is now known as ${e.to}")
    }

    private suspend fun onNamesStarted(networkId: Long, e: IrcEvent.NamesStarted) {
        val st = stateFor(networkId)
        val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
        rosterSnapshots.putIfAbsent(RosterKey(networkId, bufferId), mutableListOf())
    }

    private suspend fun onNames(networkId: Long, e: IrcEvent.Names) {
        val st = stateFor(networkId)
        val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
        val deltas = rosterSnapshots.remove(RosterKey(networkId, bufferId)).orEmpty()
        val replay = replayRosterDeltas(
            bufferId,
            e.members.map { MemberEntity(bufferId, it.nick, it.prefixes) },
            deltas,
            st,
        )
        db.withTransaction {
            memberDao.replaceAll(bufferId, replay.members)
            e.members.forEach { member ->
                val username = member.username
                val host = member.host
                if (username != null && host != null) {
                    upsertUser(networkId, member.nick) {
                        it.copy(username = username, hostmask = "$username@$host")
                    }
                }
            }
            replay.presentations.forEach { presentation ->
                insertSystem(
                    bufferId,
                    presentation.ctx,
                    presentation.kind,
                    presentation.sender,
                    presentation.text,
                )
            }
        }
    }

    private suspend fun onWhoxRow(networkId: Long, row: IrcEvent.WhoxRow) {
        upsertUser(networkId, row.nick) { existing ->
            val hostmask = if (row.username != null && row.host != null) {
                "${row.username}@${row.host}"
            } else {
                existing.hostmask
            }
            existing.copy(
                username = row.username ?: existing.username,
                hostmask = hostmask,
                account = row.account,
                away = row.flags?.let { 'G' in it } ?: existing.away,
                realname = row.realname?.takeIf(String::isNotBlank) ?: existing.realname,
            )
        }
    }

    private suspend fun onMonitorOnline(networkId: Long, event: IrcEvent.MonitorOnline) {
        event.identities.forEach { identity ->
            val username = identity.user
            val host = identity.host
            if (username != null && host != null) {
                upsertUser(networkId, identity.nick) {
                    it.copy(username = username, hostmask = "$username@$host")
                }
            }
        }
    }

    private suspend fun onMonitorLimitExceeded(networkId: Long, event: IrcEvent.MonitorLimitExceeded) {
        val st = stateFor(networkId)
        val bufferId = ensureServerBuffer(networkId, st)
        val targets = event.targets.joinToString(",")
        val text = buildString {
            append("MONITOR limit exceeded")
            event.limit?.let { append(" (").append(it).append(')') }
            if (targets.isNotEmpty()) append(": ").append(targets)
            if (event.text.isNotBlank()) append(" — ").append(event.text)
        }
        insertSystem(bufferId, serverCtx(), MessageKind.ERROR, "", text)
    }

    private suspend fun onTopicChanged(networkId: Long, e: IrcEvent.TopicChanged) {
        val st = stateFor(networkId)
        val buffer = bufferDao.byName(networkId, st.normalize(e.channel))
            ?: ensureBufferEntity(networkId, e.channel, BufferType.CHANNEL, st)
        bufferDao.setTopic(buffer.id, e.topic, e.setBy)
        insertSystem(buffer.id, e.ctx, MessageKind.TOPIC, e.setBy ?: "", "topic: ${e.topic}")
    }

    private suspend fun onHistoricalTopicChanged(networkId: Long, e: IrcEvent.TopicChanged) {
        val st = stateFor(networkId)
        val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
        insertSystem(bufferId, e.ctx, MessageKind.TOPIC, e.setBy ?: "", "topic: ${e.topic}")
    }

    private suspend fun onModeChanged(networkId: Long, e: IrcEvent.ModeChanged) {
        val st = stateFor(networkId)
        if (!isChannel(e.target, st)) return
        val bufferId = bufferDao.byName(networkId, st.normalize(e.target))?.id ?: return
        applyPrefixModes(networkId, bufferId, e, st)
        insertSystem(bufferId, e.ctx, MessageKind.MODE, "", "mode ${e.modes} ${e.args.joinToString(" ")}".trim())
    }

    private suspend fun onHistoricalModeChanged(networkId: Long, e: IrcEvent.ModeChanged) {
        val st = stateFor(networkId)
        if (!isChannel(e.target, st)) return
        val bufferId = ensureBuffer(networkId, e.target, BufferType.CHANNEL, st)
        insertSystem(bufferId, e.ctx, MessageKind.MODE, "", "mode ${e.modes} ${e.args.joinToString(" ")}".trim())
    }

    private fun journal(networkId: Long, bufferId: Long, delta: RosterDelta) {
        rosterSnapshots[RosterKey(networkId, bufferId)]?.add(delta)
    }

    private fun journalAcrossActiveSnapshots(
        networkId: Long,
        alreadyPresented: Set<Long>,
        delta: RosterDelta,
    ) {
        rosterSnapshots.forEach { (key, journal) ->
            if (key.networkId == networkId && key.bufferId !in alreadyPresented) journal.add(delta)
        }
    }

    suspend fun cancelRosterSnapshot(networkId: Long, bufferId: Long) {
        sequencer.withNetwork(networkId) {
            rosterSnapshots.remove(RosterKey(networkId, bufferId))
        }
    }

    private fun replayRosterDeltas(
        bufferId: Long,
        snapshot: List<MemberEntity>,
        deltas: List<RosterDelta>,
        st: NetworkState,
    ): RosterReplay {
        val members = LinkedHashMap<String, MemberEntity>()
        val presentations = mutableListOf<DeferredRosterPresentation>()
        snapshot.forEach { members[st.normalize(it.nick)] = it }
        deltas.forEach { delta ->
            when (delta) {
                is RosterDelta.Upsert -> members.putIfAbsent(
                    st.normalize(delta.nick),
                    MemberEntity(bufferId, delta.nick),
                )
                is RosterDelta.Remove -> members.remove(st.normalize(delta.nick))
                is RosterDelta.Rename -> {
                    val old = members.remove(st.normalize(delta.from))
                    if (old != null) members[st.normalize(delta.to)] = old.copy(nick = delta.to)
                }
                is RosterDelta.DeferredQuit -> {
                    val event = delta.event
                    if (members.remove(st.normalize(event.nick)) != null) {
                        presentations += DeferredRosterPresentation(
                            event.ctx,
                            MessageKind.QUIT,
                            event.nick,
                            "${event.nick} quit" + (event.reason?.let { " ($it)" } ?: ""),
                        )
                    }
                }
                is RosterDelta.DeferredNick -> {
                    val event = delta.event
                    val old = members.remove(st.normalize(event.from))
                    if (old != null) {
                        members[st.normalize(event.to)] = old.copy(nick = event.to)
                        presentations += DeferredRosterPresentation(
                            event.ctx,
                            MessageKind.NICK,
                            event.from,
                            "${event.from} is now known as ${event.to}",
                        )
                    }
                }
                is RosterDelta.Prefix -> {
                    val key = st.normalize(delta.nick)
                    val member = members[key] ?: return@forEach
                    members[key] = member.copy(
                        prefixes = updatePrefixes(member.prefixes, delta.prefix, delta.adding, st),
                    )
                }
            }
        }
        return RosterReplay(members.values.toList(), presentations)
    }

    private suspend fun applyPrefixModes(
        networkId: Long,
        bufferId: Long,
        event: IrcEvent.ModeChanged,
        st: NetworkState,
    ) {
        var adding = true
        var argIndex = 0
        for (mode in event.modes) {
            when (mode) {
                '+' -> adding = true
                '-' -> adding = false
                else -> {
                    val prefix = st.prefixModes[mode]
                    val consumesArg = prefix != null || modeConsumesArgument(mode, adding, st.chanModes)
                    val argument = if (consumesArg) event.args.getOrNull(argIndex++) else null
                    if (prefix != null && argument != null) {
                        val member = memberDao.allNow(bufferId).firstOrNull {
                            st.normalize(it.nick) == st.normalize(argument)
                        }
                        if (member != null) {
                            memberDao.upsert(
                                member.copy(prefixes = updatePrefixes(member.prefixes, prefix, adding, st)),
                            )
                        }
                        if (event.ctx.batchId == null) {
                            journal(networkId, bufferId, RosterDelta.Prefix(argument, prefix, adding))
                        }
                    }
                }
            }
        }
    }

    private fun updatePrefixes(current: String, prefix: Char, adding: Boolean, st: NetworkState): String {
        val updated = if (adding) current.toSet() + prefix else current.toSet() - prefix
        val order = st.prefixModes.values.toList()
        return updated.sortedBy { order.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }
            .joinToString("")
    }

    private fun modeConsumesArgument(mode: Char, adding: Boolean, chanModes: List<Set<Char>>): Boolean =
        mode in chanModes.getOrNull(0).orEmpty() ||
            mode in chanModes.getOrNull(1).orEmpty() ||
            (adding && mode in chanModes.getOrNull(2).orEmpty())

    // -- sync ---------------------------------------------------------------

    private suspend fun onReadMarker(networkId: Long, e: IrcEvent.ReadMarker) {
        val ts = e.timestamp ?: return
        val st = stateFor(networkId)
        val bufferId = bufferDao.byName(networkId, st.normalize(e.target))?.id ?: return
        bufferDao.advanceReadMarker(bufferId, ts)
        notifier.onRead(bufferId, ts)
        AutoFollowTrace.record("wire_markread_in", bufferId) { "marker=$ts" }
    }

    private suspend fun onBouncerNetworkState(networkId: Long, e: IrcEvent.BouncerNetworkState) {
        val root = networkDao.byId(networkId) ?: return
        // Only the bouncer ROOT connection materializes child networks. A bound child is scoped to
        // a single upstream network, but its soju connection still receives BOUNCER NETWORK
        // notifications; handling them here would spawn duplicate children parented to the child
        // itself, which cannot resolve a valid root to bind through and fail SASL 904 (#40).
        if (root.role != NetworkRole.BOUNCER_ROOT) return
        val existing = networkDao.childrenOf(root.id).firstOrNull { it.bouncerNetId == e.netId }
        // "*" attrs (empty map) signals deletion of the child network.
        if (e.attrs.isEmpty() && existing != null) {
            networkDao.deleteLocalTree(existing.id)
            return
        }
        if (e.attrs.isEmpty()) return
        val name = e.attrs["name"] ?: e.netId
        val host = e.attrs["host"] ?: root.host
        val port = e.attrs["port"]?.toIntOrNull() ?: root.port
        val nick = e.attrs["nickname"] ?: root.nick
        if (existing == null) {
            networkDao.insert(
                root.copy(
                    id = 0,
                    name = name,
                    role = NetworkRole.BOUNCER_CHILD,
                    parentId = root.id,
                    bouncerNetId = e.netId,
                    host = host,
                    port = port,
                    nick = nick,
                ),
            )
        } else {
            // Preserve the row's current name on update: it may be a user-set alias, and the
            // bouncer name is only authoritative when the child is first created above. Soju may
            // send a partial NETWORK notification; absent attrs mean "unchanged", not "use the
            // root defaults". Replacing child host/port/nick with root values changes its
            // connection fingerprint and restarts an otherwise healthy bound actor.
            networkDao.updateBouncerConnection(
                existing.id,
                e.attrs["host"] ?: existing.host,
                e.attrs["port"]?.toIntOrNull() ?: existing.port,
                e.attrs["nickname"] ?: existing.nick,
            )
        }
    }

    // -- server buffer (plans/16 §5.6) --------------------------------------

    /** ServerError → SERVER buffer, kind ERROR. The event carries no ctx, so use the wall clock. */
    private suspend fun onServerError(networkId: Long, e: IrcEvent.ServerError) {
        val st = stateFor(networkId)
        if (e.code in JOIN_ERROR_NUMERICS) {
            val channel = e.params.firstOrNull { isChannel(it, st) }
            val inviteBufferId = channel?.let { bufferDao.byName(networkId, st.normalize(it))?.id }
            if (inviteBufferId != null) {
                messageDao.failJoiningInvites(inviteBufferId, e.text.ifBlank { e.code })
            }
        }
        val bufferId = ensureServerBuffer(networkId, st)
        val text = "${e.code} ${e.text}".trim()
        insertSystem(bufferId, serverCtx(), MessageKind.ERROR, "", text)
    }

    /** Whitelisted informational numerics → SERVER buffer, kind SERVER_INFO (our nick dropped). */
    private suspend fun onRaw(networkId: Long, e: IrcEvent.Raw, origin: EventOrigin) {
        if (removeReaction(networkId, e.message)) return
        if (origin != EventOrigin.LIVE) return
        if (e.message.command !in SERVER_INFO_NUMERICS) return
        val st = stateFor(networkId)
        val bufferId = ensureServerBuffer(networkId, st)
        // params[0] is our nick for these numerics; drop it and join the rest as the info line.
        val text = e.message.params.drop(1).joinToString(" ").trim()
        insertSystem(bufferId, serverCtx(), MessageKind.SERVER_INFO, "", text)
    }

    /** Consume Raw `draft/unreact` at the sole reaction-persistence boundary. */
    private suspend fun removeReaction(networkId: Long, message: io.github.trevarj.motd.irc.proto.IrcMessage): Boolean {
        if (message.command != "TAGMSG") return false
        val emoji = message.unreactionValue() ?: return false
        val targetMsgid = message.replyReference() ?: return true
        val source = message.source?.nick ?: return true
        val target = message.params.firstOrNull() ?: return true
        val st = stateFor(networkId)
        val isDm = !isChannel(target, st)
        val sourceIsSelf = st.normalize(source) == st.normalize(st.selfNick)
        val bufferName = if (isDm) {
            if (sourceIsSelf) target else source
        } else {
            target
        }
        val bufferId = bufferDao.byName(networkId, st.normalize(bufferName))?.id ?: return true
        reactionDao.observeForBuffer(bufferId).first()
            .filter { it.targetMsgid == targetMsgid && it.emoji == emoji && st.normalize(it.sender) == st.normalize(source) }
            .forEach { reactionMutations.remove(it) }
        return true
    }

    /** Disconnected marker → SERVER buffer for cheap in-history reconnect visibility. */
    private suspend fun onDisconnected(networkId: Long, e: IrcEvent.Disconnected) {
        rosterSnapshots.keys.removeAll { it.networkId == networkId }
        messageDao.failJoiningInvitesForNetwork(networkId, e.reason ?: "disconnected")
        val st = stateFor(networkId)
        val bufferId = ensureServerBuffer(networkId, st)
        val text = "disconnected" + (e.reason?.let { ": $it" } ?: "")
        insertSystem(bufferId, serverCtx(), MessageKind.SERVER_INFO, "", text)
    }

    /** A ctx for server-buffer rows: no msgid/label, server time = now (the events carry none). */
    private fun serverCtx(): MessageContext =
        MessageContext(msgid = null, serverTime = System.currentTimeMillis(), account = null, batchId = null, label = null)

    /** Find-or-create the per-network SERVER buffer (name "*"); mirrors ConnectionManager's. */
    private suspend fun ensureServerBuffer(networkId: Long, st: NetworkState): Long {
        bufferDao.byName(networkId, "*")?.let { return it.id }
        val displayName = networkDao.byId(networkId)?.name ?: "Server"
        return bufferStore.getOrCreate(networkId, "*", displayName, BufferType.SERVER).id
    }

    // -- pending-send insert path (delegated by ConnectionManagerImpl.sendMessage) --

    /**
     * Insert a locally-sent pending row so the "sole writer" rule holds. Returns the row id.
     * The echo (labeled ChatMessage) later updates it in place; a 30s timeout marks it failed.
     */
    suspend fun insertPending(bufferId: Long, label: String, sender: String, text: String, replyToMsgid: String?, kind: MessageKind): Long {
        val networkId = requireNotNull(bufferDao.observeById(bufferId)?.networkId) { "missing buffer $bufferId" }
        return sequencer.withNetwork(networkId) {
            val now = System.currentTimeMillis()
            val row = MessageEntity(
                bufferId = bufferId,
                msgid = null,
                serverTime = now,
                sender = sender,
                kind = kind,
                text = text,
                isSelf = true,
                hasMention = false,
                replyToMsgid = replyToMsgid,
                pendingLabel = label,
                dedupKey = EchoDeduper.pendingKey(label),
            )
            val inserted = messageDao.insertAll(listOf(row)).single()
            if (inserted > 0L) {
                traceMessageWrite("room_pending_insert", row.copy(id = inserted), fromHistory = false)
            }
            inserted
        }
    }

    /** Mark a pending row failed if it is still pending after the echo timeout. */
    suspend fun failIfStillPending(bufferId: Long, label: String) {
        val networkId = bufferDao.observeById(bufferId)?.networkId ?: return
        sequencer.withNetwork(networkId) {
            if (messageDao.failIfStillPending(bufferId, label) > 0) {
                messageDao.byPendingLabel(bufferId, label)?.let { failed ->
                    traceMessageWrite("room_pending_failed", failed, fromHistory = false)
                }
            }
        }
    }

    suspend fun evictNetwork(networkId: Long) {
        sequencer.withNetwork(networkId) {
            states.remove(networkId)
            rosterSnapshots.keys.removeAll { it.networkId == networkId }
        }
        sequencer.evict(networkId)
    }

    suspend fun shutdown() {
        sequencer.clear()
        states.clear()
        rosterSnapshots.clear()
    }

    internal fun sequencerSize(): Int = sequencer.size()

    // -- helpers ------------------------------------------------------------

    private fun isChannel(target: String, st: NetworkState): Boolean =
        target.isNotEmpty() && target[0] in CHANTYPES

    /**
     * True when a NOTICE source looks like a server, not a user (Confirmed decision #5): an empty
     * source, or one containing '.' (a hostname). RFC nicks cannot contain '.', so NickServ/ChanServ
     * stay user queries while `*.libera.chat` routes to the SERVER buffer.
     */
    private fun isServerSource(nick: String): Boolean = nick.isEmpty() || '.' in nick

    private suspend fun ensureBuffer(networkId: Long, name: String, type: BufferType, st: NetworkState): Long =
        ensureBufferEntity(networkId, name, type, st).id

    private suspend fun ensureBufferEntity(networkId: Long, name: String, type: BufferType, st: NetworkState): BufferEntity {
        val norm = st.normalize(name)
        bufferDao.byName(networkId, norm)?.let { return it }
        return bufferStore.getOrCreate(networkId, norm, name, type)
    }

    private suspend fun historicalTargetBuffer(networkId: Long, target: String?): Long? {
        if (target == null) return null
        val st = stateFor(networkId)
        val type = if (isChannel(target, st)) BufferType.CHANNEL else BufferType.QUERY
        return ensureBuffer(networkId, target, type, st)
    }

    private suspend fun markJoined(bufferId: Long, joined: Boolean) {
        val b = bufferDao.observeById(bufferId) ?: return
        if (b.joined != joined) bufferDao.setJoined(bufferId, joined)
    }

    private suspend fun insertSystem(
        bufferId: Long,
        ctx: MessageContext,
        kind: MessageKind,
        sender: String,
        text: String,
        // Override for idempotent system rows (e.g. self-join) that must collapse across replays
        // regardless of serverTime. Falls back to msgid ?: sha1(serverTime|sender|text).
        dedupKey: String? = null,
    ) {
        val row = MessageEntity(
            bufferId = bufferId,
            msgid = ctx.msgid,
            serverTime = ctx.serverTime,
            sender = sender,
            kind = kind,
            text = text,
            dedupKey = dedupKey ?: EchoDeduper.keyFor(ctx.msgid, ctx.serverTime, sender, text),
        )
        val inserted = messageDao.insertAll(listOf(row)).single()
        if (inserted > 0L) traceMessageWrite("room_insert", row.copy(id = inserted), ctx.batchId != null)
    }

    private fun traceMessageWrite(event: String, row: MessageEntity, fromHistory: Boolean) {
        AutoFollowTrace.record(event, row.bufferId) {
            "row=${row.id} kind=${row.kind.name} self=${row.isSelf} history=$fromHistory " +
                "server_time=${row.serverTime} pending=${row.pendingLabel != null} failed=${row.failed}"
        }
        diagnostics.record("room", event) {
            mapOf(
                "buffer_id" to row.bufferId,
                "row_id" to row.id,
                "msgid_fp" to diagnostics.fingerprint(row.msgid),
                "dedup_fp" to diagnostics.fingerprint(row.dedupKey),
                "sender_fp" to diagnostics.fingerprint(row.sender),
                "body_fp" to diagnostics.fingerprint(row.text),
                "kind" to row.kind.name,
                "self" to row.isSelf,
                "history" to fromHistory,
                "server_time" to row.serverTime,
                "pending" to (row.pendingLabel != null),
                "failed" to row.failed,
            )
        }
    }

    private fun traceMessageDecision(
        event: String,
        networkId: Long,
        bufferId: Long,
        message: IrcEvent.ChatMessage,
        origin: EventOrigin,
        extra: () -> Map<String, Any?> = { emptyMap() },
    ) {
        diagnostics.record("messages", event) {
            mapOf(
                "network_id" to networkId,
                "buffer_id" to bufferId,
                "origin" to origin.name,
                "msgid_fp" to diagnostics.fingerprint(message.ctx.msgid),
                "sender_fp" to diagnostics.fingerprint(message.source.nick),
                "body_fp" to diagnostics.fingerprint(message.text),
                "kind" to message.kind.name,
                "self" to message.isSelf,
                "server_time" to message.ctx.serverTime,
                "batch" to (message.ctx.batchId != null),
            ) + extra()
        }
    }

    /**
     * Echo-dedup heuristic (plans/03) for own messages arriving without a labeled correlation: the
     * newest self row in [bufferId] with identical [text] that is EITHER still pending (a local send
     * awaiting its echo — collapses regardless of time, since its device-clock serverTime cannot be
     * compared to the server's echo time under clock skew) OR a confirmed row whose serverTime is
     * within [ECHO_MATCH_WINDOW_MS] of [echoTime] (bounded so an old identical self message is not
     * matched). Pending/failed rows are preferred. Returns null when nothing matches.
     */
    private suspend fun findSelfEchoCandidate(bufferId: Long, text: String, echoTime: Long): MessageEntity? =
        // Delegates to a suspend @Query: Room runs it off the main thread (the events collector runs
        // on Dispatchers.Main) and handles it correctly inside the HistoryBatch withTransaction too —
        // the previous raw db.query() ran synchronously on the caller's thread and crashed on send.
        messageDao.findSelfEchoCandidate(
            bufferId,
            text,
            echoTime - ECHO_MATCH_WINDOW_MS,
            echoTime + ECHO_MATCH_WINDOW_MS,
        )

    /** Buffer ids where [nick] is currently a member on [networkId] (for quit/nick fan-out). */
    private suspend fun buffersOfNick(networkId: Long, nick: String): List<Long> =
        memberDao.bufferIdsForNick(networkId, nick)

    private suspend fun upsertUser(networkId: Long, nick: String, mutate: (UserEntity) -> UserEntity) {
        val normalized = stateFor(networkId).normalize(nick)
        val existing = userDao.byNick(networkId, normalized)
            ?: UserEntity(networkId = networkId, nick = normalized)
        userDao.upsert(mutate(existing))
    }

    private suspend fun maybeNotify(networkId: Long, bufferId: Long, type: BufferType, hasMention: Boolean, e: IrcEvent.ChatMessage) {
        if (e.isSelf) return
        // Never raise a notification for a SERVER buffer: a MOTD line containing the user's nick
        // must not fire a mention (plans/16 §5.6.5).
        if (type == BufferType.SERVER) return
        if (type != BufferType.QUERY && !hasMention) return
        notifier.onIncoming(networkId, bufferId, type, hasMention, e)
    }

    private fun kindOf(k: IrcEvent.ChatKind): MessageKind = when (k) {
        IrcEvent.ChatKind.PRIVMSG -> MessageKind.PRIVMSG
        IrcEvent.ChatKind.NOTICE -> MessageKind.NOTICE
        IrcEvent.ChatKind.ACTION -> MessageKind.ACTION
    }

    private companion object {
        // Isupport CHANTYPES default; DM detection uses the first-char rule.
        const val CHANTYPES = "#&"

        // Echo-match window (plans/03): an own message arriving without a labeled correlation is
        // matched to a local row whose serverTime is within this many ms. Symmetric because the
        // local clock (send time) and the server clock (echo time) can differ in either direction.
        const val ECHO_MATCH_WINDOW_MS = 30_000L
        const val INCOMING_DELIVERY_MATCH_WINDOW_MS = 2_000L
        const val INVITE_DEDUP_WINDOW_MS = 30_000L
        const val NETWORK_BATCH_DEDUP_WINDOW_MS = 30_000L

        /**
         * Informational numerics persisted to the SERVER buffer as SERVER_INFO (plans/16 §5.6.3):
         * welcome (001..004), lusers (251..255, 265, 266), MOTD (375, 372, 376), away toggled
         * (305, 306), RPL_AWAY (301), and the WHOIS set (311, 312, 317, 318, 319, 330, 338) as a
         * fallback surface when labeled-response is missing. LIST numerics (321/322/323) are
         * deliberately excluded so a browse never floods the buffer.
         */
        val SERVER_INFO_NUMERICS: Set<String> = setOf(
            "001", "002", "003", "004",
            "251", "252", "253", "254", "255", "265", "266",
            "375", "372", "376",
            "305", "306", "301",
            "311", "312", "317", "318", "319", "330", "338",
        )

        val JOIN_ERROR_NUMERICS: Set<String> = setOf(
            "403", "405", "471", "473", "474", "475", "476",
        )
    }
}

private fun parsePrefixModes(value: String?): Map<Char, Char> {
    val raw = value ?: return emptyMap()
    val close = raw.indexOf(')')
    if (!raw.startsWith('(') || close <= 1) return emptyMap()
    val modes = raw.substring(1, close)
    val prefixes = raw.substring(close + 1)
    if (modes.length != prefixes.length) return emptyMap()
    return modes.indices.associate { modes[it] to prefixes[it] }
}

/**
 * Notification hook the [EventProcessor] fires for a persisted incoming ChatMessage that already
 * passed the (DM || hasMention) filter. The concrete impl (MotdNotifications, WP5) applies the
 * remaining suppression rules (muted buffer, foregrounded buffer) and posts MessagingStyle.
 */
interface MessageNotifier {
    // suspend so implementations read Room / DataStore with plain suspend calls (which dispatch
    // off the main thread). The events collector runs on Dispatchers.Main, so a blocking read here
    // (runBlocking { suspend Room query }) deadlocks/crashes the main thread — same class of bug as
    // the findSelfEchoCandidate fix. Callers are already in suspend context.
    suspend fun onIncoming(networkId: Long, bufferId: Long, type: BufferType, hasMention: Boolean, message: IrcEvent.ChatMessage)

    /** A local or synchronized marker advanced through [upToTime]. */
    suspend fun onRead(bufferId: Long, upToTime: Long) = Unit

    /** A newly persisted, live, actionable invitation. */
    suspend fun onInvitation(networkId: Long, bufferId: Long, messageId: Long) = Unit

    /** Cancel notification state after Join/Dismiss resolves an invitation. */
    suspend fun onInvitationResolved(messageId: Long) = Unit

    /** No-op notifier for tests / headless contexts. */
    object Noop : MessageNotifier {
        override suspend fun onIncoming(networkId: Long, bufferId: Long, type: BufferType, hasMention: Boolean, message: IrcEvent.ChatMessage) = Unit
    }
}
