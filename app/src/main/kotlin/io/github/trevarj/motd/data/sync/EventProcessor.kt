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
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.service.IrcEventSink
import io.github.trevarj.motd.service.RoomReactionMutationStore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

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
) : IrcEventSink {

    private val networkDao get() = db.networkDao()
    private val bufferDao get() = db.bufferDao()
    private val messageDao get() = db.messageDao()
    private val memberDao get() = db.memberDao()
    private val reactionDao get() = db.reactionDao()
    private val userDao get() = db.userDao()
    private val reactionMutations = RoomReactionMutationStore(db)

    private val states = ConcurrentHashMap<Long, NetworkState>()

    /** Per-network state for self-nick tracking, mention regex and case normalization. */
    private class NetworkState(
        @Volatile var selfNick: String,
        @Volatile var caseMapping: String,
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

    /** Update cached self-nick + case mapping from a Registered/ISUPPORT snapshot. */
    fun onRegistered(networkId: Long, nick: String, isupport: Map<String, String>) {
        val cm = isupport["CASEMAPPING"] ?: "rfc1459"
        states[networkId] = NetworkState(selfNick = nick, caseMapping = cm)
    }

    override suspend fun process(networkId: Long, event: IrcEvent) {
        processEvent(networkId, event, notify = true)
    }

    /** Persist one event while carrying whether its provenance permits user notifications. */
    private suspend fun processEvent(networkId: Long, event: IrcEvent, notify: Boolean) {
        when (event) {
            is IrcEvent.Registered -> onRegistered(networkId, event.nick, event.isupport)
            is IrcEvent.ChatMessage -> onChat(networkId, event, notify)
            is IrcEvent.TagMessage -> onTag(networkId, event)
            is IrcEvent.HistoryBatch -> onHistoryBatch(networkId, event)
            is IrcEvent.Joined -> onJoined(networkId, event)
            is IrcEvent.Parted -> onParted(networkId, event)
            is IrcEvent.Quit -> onQuit(networkId, event)
            is IrcEvent.Kicked -> onKicked(networkId, event)
            is IrcEvent.NickChanged -> onNickChanged(networkId, event)
            is IrcEvent.Names -> onNames(networkId, event)
            is IrcEvent.TopicChanged -> onTopicChanged(networkId, event)
            is IrcEvent.ModeChanged -> onModeChanged(networkId, event)
            is IrcEvent.AwayChanged -> upsertUser(networkId, event.nick) { it.copy(away = event.awayMessage != null) }
            is IrcEvent.AccountChanged -> upsertUser(networkId, event.nick) { it.copy(account = event.account) }
            is IrcEvent.HostChanged -> upsertUser(networkId, event.nick) { it.copy(hostmask = "${event.newUser}@${event.newHost}") }
            is IrcEvent.RealnameChanged -> upsertUser(networkId, event.nick) { it.copy(realname = event.realname) }
            is IrcEvent.Invited -> onInvited(networkId, event, notify)
            is IrcEvent.ReadMarker -> onReadMarker(networkId, event)
            is IrcEvent.BouncerNetworkState -> onBouncerNetworkState(networkId, event)
            is IrcEvent.Disconnected -> onDisconnected(networkId, event)
            is IrcEvent.ServerError -> onServerError(networkId, event)
            is IrcEvent.Raw -> onRaw(networkId, event)
            is IrcEvent.CapsChanged,
            -> Unit // not persisted
        }
    }

    // -- chat / tags ---------------------------------------------------------

    private suspend fun onChat(networkId: Long, e: IrcEvent.ChatMessage, notify: Boolean) {
        val st = stateFor(networkId)
        val isDm = !isChannel(e.target, st)
        // Server-sourced NOTICEs (empty source, or a source that looks like a host) go to the
        // SERVER buffer instead of spawning a junk QUERY buffer (plans/16 §5.6.1, Confirmed #5).
        // Channel NOTICEs are unaffected; NickServ/ChanServ (no dot) keep their query buffers.
        if (isDm && e.kind == IrcEvent.ChatKind.NOTICE && isServerSource(e.source.nick)) {
            val serverBufferId = ensureServerBuffer(networkId, st)
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
            dedupKey = EchoDeduper.keyFor(e.ctx, e.source.nick, storedText),
        )

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
            val incomingMsgid = e.ctx.msgid
            if (incomingMsgid != null && messageDao.byMsgid(bufferId, incomingMsgid) != null) return
            // (a) Labeled echo of a pending self-send: update the pending row in place.
            val label = e.ctx.label
            if (label != null) {
                val pending = messageDao.byPendingLabel(bufferId, label)
                if (pending != null) {
                    val confirmed = pending.copy(
                        msgid = e.ctx.msgid,
                        serverTime = e.ctx.serverTime,
                        dedupKey = EchoDeduper.keyFor(e.ctx, e.source.nick, storedText),
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
        if (inserted <= 0L) return // dedup no-op
        traceMessageWrite("room_insert", row.copy(id = inserted), e.ctx.batchId != null)
        if (isRootServiceReply) {
            bufferDao.advanceReadMarker(bufferId, e.ctx.serverTime)
            return
        }
        if (notify) maybeNotify(networkId, bufferId, type, hasMention, e)
    }

    private suspend fun onTag(networkId: Long, e: IrcEvent.TagMessage) {
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
        e.typing?.let { typingState ->
            val bufferId = ensureBuffer(networkId, bufferName, type, st)
            typing.onTyping(bufferId, e.source.nick, typingState)
        }
        // react: upsert reaction row (keyed by target msgid).
        val emoji = e.reactEmoji
        val targetMsgid = e.reactTargetMsgid
        if (emoji != null && targetMsgid != null) {
            val bufferId = bufferDao.byName(networkId, st.normalize(bufferName))?.id ?: return
            if (messageDao.byMsgid(bufferId, targetMsgid) == null) return
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
        db.withTransaction {
            for (ev in batch.events) processEvent(networkId, ev, notify = false)
        }
    }

    // -- invitations --------------------------------------------------------

    private suspend fun onInvited(networkId: Long, e: IrcEvent.Invited, notify: Boolean) {
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
        val historical = !notify || e.ctx.batchId != null
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

    private suspend fun onParted(networkId: Long, e: IrcEvent.Parted) {
        val st = stateFor(networkId)
        val bufferId = bufferDao.byName(networkId, st.normalize(e.channel))?.id ?: return
        memberDao.remove(bufferId, e.nick)
        if (e.isSelf) markJoined(bufferId, false)
        insertSystem(bufferId, e.ctx, MessageKind.PART, e.nick, "${e.nick} left" + (e.reason?.let { " ($it)" } ?: ""))
    }

    private suspend fun onQuit(networkId: Long, e: IrcEvent.Quit) {
        // Fan out to every buffer the nick was a member of.
        val buffers = buffersOfNick(networkId, e.nick)
        for (bufferId in buffers) {
            memberDao.remove(bufferId, e.nick)
            insertSystem(bufferId, e.ctx, MessageKind.QUIT, e.nick, "${e.nick} quit" + (e.reason?.let { " ($it)" } ?: ""))
        }
    }

    private suspend fun onKicked(networkId: Long, e: IrcEvent.Kicked) {
        val st = stateFor(networkId)
        val bufferId = bufferDao.byName(networkId, st.normalize(e.channel))?.id ?: return
        memberDao.remove(bufferId, e.nick)
        if (e.isSelf) markJoined(bufferId, false)
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
            insertSystem(bufferId, e.ctx, MessageKind.NICK, e.from, "${e.from} is now known as ${e.to}")
        }
    }

    private suspend fun onNames(networkId: Long, e: IrcEvent.Names) {
        val st = stateFor(networkId)
        val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
        db.withTransaction {
            memberDao.replaceAll(bufferId, e.members.map { MemberEntity(bufferId, it.nick, it.prefixes) })
            e.members.forEach { member ->
                val username = member.username
                val host = member.host
                if (username != null && host != null) {
                    upsertUser(networkId, member.nick) {
                        it.copy(username = username, hostmask = "$username@$host")
                    }
                }
            }
        }
    }

    private suspend fun onTopicChanged(networkId: Long, e: IrcEvent.TopicChanged) {
        val st = stateFor(networkId)
        val buffer = bufferDao.byName(networkId, st.normalize(e.channel))
            ?: ensureBufferEntity(networkId, e.channel, BufferType.CHANNEL, st)
        bufferDao.update(buffer.copy(topic = e.topic, topicSetBy = e.setBy))
        insertSystem(buffer.id, e.ctx, MessageKind.TOPIC, e.setBy ?: "", "topic: ${e.topic}")
    }

    private suspend fun onModeChanged(networkId: Long, e: IrcEvent.ModeChanged) {
        val st = stateFor(networkId)
        if (!isChannel(e.target, st)) return
        val bufferId = bufferDao.byName(networkId, st.normalize(e.target))?.id ?: return
        insertSystem(bufferId, e.ctx, MessageKind.MODE, "", "mode ${e.modes} ${e.args.joinToString(" ")}".trim())
    }

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
            networkDao.delete(existing)
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
            networkDao.update(existing.copy(
                host = e.attrs["host"] ?: existing.host,
                port = e.attrs["port"]?.toIntOrNull() ?: existing.port,
                nick = e.attrs["nickname"] ?: existing.nick,
            ))
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
    private suspend fun onRaw(networkId: Long, e: IrcEvent.Raw) {
        if (removeReaction(networkId, e.message)) return
        if (e.message.command !in SERVER_INFO_NUMERICS) return
        val st = stateFor(networkId)
        val bufferId = ensureServerBuffer(networkId, st)
        // params[0] is our nick for these numerics; drop it and join the rest as the info line.
        val text = e.message.params.drop(1).joinToString(" ").trim()
        insertSystem(bufferId, serverCtx(), MessageKind.SERVER_INFO, "", text)
    }

    /** `draft/unreact` stays Raw to preserve the frozen event contract; consume it here. */
    private suspend fun removeReaction(networkId: Long, message: io.github.trevarj.motd.irc.proto.IrcMessage): Boolean {
        if (message.command != "TAGMSG") return false
        val emoji = message.tags["+draft/unreact"] ?: return false
        val targetMsgid = message.tags["+reply"] ?: message.tags["+draft/reply"] ?: return true
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
        if (messageDao.byMsgid(bufferId, targetMsgid) == null) return true
        val previous = reactionMutations.findOwn(bufferId, targetMsgid, source, st::normalize)
        if (previous?.emoji == emoji) reactionMutations.remove(previous)
        return true
    }

    /** Disconnected marker → SERVER buffer for cheap in-history reconnect visibility. */
    private suspend fun onDisconnected(networkId: Long, e: IrcEvent.Disconnected) {
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
        val entity = BufferEntity(
            networkId = networkId,
            name = "*",
            displayName = displayName,
            type = BufferType.SERVER,
        )
        return bufferDao.insert(entity)
    }

    // -- pending-send insert path (delegated by ConnectionManagerImpl.sendMessage) --

    /**
     * Insert a locally-sent pending row so the "sole writer" rule holds. Returns the row id.
     * The echo (labeled ChatMessage) later updates it in place; a 30s timeout marks it failed.
     */
    suspend fun insertPending(bufferId: Long, label: String, sender: String, text: String, replyToMsgid: String?, kind: MessageKind): Long {
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
        if (inserted > 0L) traceMessageWrite("room_pending_insert", row.copy(id = inserted), fromHistory = false)
        return inserted
    }

    /** Mark a pending row failed if it is still pending after the echo timeout. */
    suspend fun failIfStillPending(bufferId: Long, label: String) {
        val pending = messageDao.byPendingLabel(bufferId, label) ?: return
        val failed = pending.copy(failed = true)
        messageDao.update(failed)
        traceMessageWrite("room_pending_failed", failed, fromHistory = false)
    }

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
        val entity = BufferEntity(
            networkId = networkId,
            name = norm,
            displayName = name,
            type = type,
        )
        val id = bufferDao.insert(entity)
        return entity.copy(id = id)
    }

    private suspend fun markJoined(bufferId: Long, joined: Boolean) {
        val b = bufferDao.observeById(bufferId) ?: return
        if (b.joined != joined) bufferDao.update(b.copy(joined = joined))
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
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val q = androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT m.bufferId FROM members m JOIN buffers b ON b.id = m.bufferId " +
                    "WHERE b.networkId = ? AND m.nick = ?",
                arrayOf<Any>(networkId, nick),
            )
            db.query(q).use { cursor ->
                val out = ArrayList<Long>(cursor.count)
                while (cursor.moveToNext()) out.add(cursor.getLong(0))
                out
            }
        }

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
        const val INVITE_DEDUP_WINDOW_MS = 30_000L

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
