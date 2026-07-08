package io.github.trevarj.motd.data.sync

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.UserEntity
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.service.IrcEventSink
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
        when (event) {
            is IrcEvent.Registered -> onRegistered(networkId, event.nick, event.isupport)
            is IrcEvent.ChatMessage -> onChat(networkId, event)
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
            is IrcEvent.ReadMarker -> onReadMarker(networkId, event)
            is IrcEvent.BouncerNetworkState -> onBouncerNetworkState(networkId, event)
            is IrcEvent.Invited,
            is IrcEvent.CapsChanged,
            is IrcEvent.Disconnected,
            is IrcEvent.ServerError,
            is IrcEvent.Raw,
            -> Unit // not persisted
        }
    }

    // -- chat / tags ---------------------------------------------------------

    private suspend fun onChat(networkId: Long, e: IrcEvent.ChatMessage) {
        val st = stateFor(networkId)
        val isDm = !isChannel(e.target, st)
        // For a DM the buffer is keyed by the OTHER party's nick, not our own.
        val bufferName = if (isDm) {
            if (e.isSelf) e.target else e.source.nick
        } else {
            e.target
        }
        val type = if (isDm) BufferType.QUERY else BufferType.CHANNEL
        val bufferId = ensureBuffer(networkId, bufferName, type, st)

        val hasMention = !e.isSelf && st.mentionRegex.containsMatchIn(e.text)
        val row = MessageEntity(
            bufferId = bufferId,
            msgid = e.ctx.msgid,
            serverTime = e.ctx.serverTime,
            sender = e.source.nick,
            senderAccount = e.ctx.account,
            kind = kindOf(e.kind),
            text = e.text,
            isSelf = e.isSelf,
            hasMention = hasMention,
            replyToMsgid = e.replyToMsgid,
            dedupKey = EchoDeduper.keyFor(e.ctx, e.source.nick, e.text),
        )

        // Labeled echo of a pending self-send: update the pending row in place instead of insert.
        val label = e.ctx.label
        if (e.isSelf && label != null) {
            val pending = messageDao.byPendingLabel(bufferId, label)
            if (pending != null) {
                messageDao.update(
                    pending.copy(
                        msgid = e.ctx.msgid,
                        serverTime = e.ctx.serverTime,
                        dedupKey = EchoDeduper.keyFor(e.ctx, e.source.nick, e.text),
                        pendingLabel = null,
                        failed = false,
                    ),
                )
                return
            }
        }

        val inserted = messageDao.insertAll(listOf(row)).single()
        if (inserted <= 0L) return // dedup no-op
        maybeNotify(networkId, bufferId, type, hasMention, e)
    }

    private suspend fun onTag(networkId: Long, e: IrcEvent.TagMessage) {
        val st = stateFor(networkId)
        val isDm = !isChannel(e.target, st)
        val bufferName = if (isDm) e.source.nick else e.target
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
            val bufferId = ensureBuffer(networkId, bufferName, type, st)
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
        // All events for one target, applied in a single Room transaction (idempotent by dedupKey).
        db.withTransaction {
            for (ev in batch.events) process(networkId, ev)
        }
    }

    // -- membership ----------------------------------------------------------

    private suspend fun onJoined(networkId: Long, e: IrcEvent.Joined) {
        val st = stateFor(networkId)
        val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
        if (e.isSelf) markJoined(bufferId, true)
        memberDao.upsert(MemberEntity(bufferId, e.nick))
        upsertUser(networkId, e.nick) { it.copy(account = e.account ?: it.account, realname = e.realname ?: it.realname) }
        insertSystem(bufferId, e.ctx, MessageKind.JOIN, e.nick, "${e.nick} joined")
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
        memberDao.replaceAll(bufferId, e.members.map { MemberEntity(bufferId, it.nick, it.prefixes) })
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
    }

    private suspend fun onBouncerNetworkState(networkId: Long, e: IrcEvent.BouncerNetworkState) {
        val root = networkDao.byId(networkId) ?: return
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
            networkDao.update(existing.copy(name = name, host = host, port = port, nick = nick))
        }
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
        return messageDao.insertAll(listOf(row)).single()
    }

    /** Mark a pending row failed if it is still pending after the echo timeout. */
    suspend fun failIfStillPending(bufferId: Long, label: String) {
        val pending = messageDao.byPendingLabel(bufferId, label) ?: return
        messageDao.update(pending.copy(failed = true))
    }

    // -- helpers ------------------------------------------------------------

    private fun isChannel(target: String, st: NetworkState): Boolean =
        target.isNotEmpty() && target[0] in CHANTYPES

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

    private suspend fun insertSystem(bufferId: Long, ctx: MessageContext, kind: MessageKind, sender: String, text: String) {
        val row = MessageEntity(
            bufferId = bufferId,
            msgid = ctx.msgid,
            serverTime = ctx.serverTime,
            sender = sender,
            kind = kind,
            text = text,
            dedupKey = EchoDeduper.keyFor(ctx.msgid, ctx.serverTime, sender, text),
        )
        messageDao.insertAll(listOf(row))
    }

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
        val existing = userDao.byNick(networkId, nick) ?: UserEntity(networkId = networkId, nick = nick)
        userDao.upsert(mutate(existing))
    }

    private suspend fun maybeNotify(networkId: Long, bufferId: Long, type: BufferType, hasMention: Boolean, e: IrcEvent.ChatMessage) {
        if (e.isSelf) return
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
    }
}

/**
 * Notification hook the [EventProcessor] fires for a persisted incoming ChatMessage that already
 * passed the (DM || hasMention) filter. The concrete impl (MotdNotifications, WP5) applies the
 * remaining suppression rules (muted buffer, foregrounded buffer) and posts MessagingStyle.
 */
interface MessageNotifier {
    fun onIncoming(networkId: Long, bufferId: Long, type: BufferType, hasMention: Boolean, message: IrcEvent.ChatMessage)

    /** No-op notifier for tests / headless contexts. */
    object Noop : MessageNotifier {
        override fun onIncoming(networkId: Long, bufferId: Long, type: BufferType, hasMention: Boolean, message: IrcEvent.ChatMessage) = Unit
    }
}
