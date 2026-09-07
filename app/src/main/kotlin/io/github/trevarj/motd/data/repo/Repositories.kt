package io.github.trevarj.motd.data.repo

import androidx.paging.PagingData
import androidx.paging.RemoteMediator
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.InvitationEventRow
import io.github.trevarj.motd.data.db.JoinedChannelRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MonitorQueryRow
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkBufferToolRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkIgnoreEntity
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.history.TimelineSeam
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

interface NetworkRepository {
    fun observeNetworks(): Flow<List<NetworkEntity>>

    suspend fun addNetwork(n: NetworkEntity): Long

    suspend fun updateNetwork(n: NetworkEntity)

    suspend fun deleteNetwork(id: Long)

    /**
     * Persist the user's manual drawer order. [orderedIds] is the complete flattened order (each
     * bouncer root immediately followed by its children); ids the caller does not know about keep
     * their relative order at the end. This is user-preference state, not IRC-derived state, so it
     * is written here rather than through EventProcessor.
     */
    suspend fun reorderNetworks(orderedIds: List<Long>)

    // Round 5: point reads for the network-management screens.

    /** Point read (drives NetworkSettings/Bouncer screens; delegates to NetworkDao.byId). */
    suspend fun networkById(id: Long): NetworkEntity?

    /** Local BOUNCER_CHILD mirrors of a soju root (delegates to NetworkDao.childrenOf). */
    suspend fun childrenOf(rootId: Long): List<NetworkEntity>
}

interface NetworkIgnoreRepository {
    fun observeIgnores(networkId: Long): Flow<List<NetworkIgnoreEntity>>

    fun observeBuffers(networkId: Long): Flow<List<NetworkBufferToolRow>>

    suspend fun addIgnore(
        networkId: Long,
        pattern: String,
    ): Result<Unit>

    suspend fun setIgnoreEnabled(
        id: Long,
        enabled: Boolean,
    )

    suspend fun deleteIgnore(id: Long)

    /** Non-null when unmuting hid a backlog; see BufferDao.setMuted. */
    suspend fun setMuted(
        bufferId: Long,
        muted: Boolean,
    ): MuteBacklogSuppression?

    /** Undo the backlog a previous [setMuted] hid. */
    suspend fun restoreMuteBacklog(suppression: MuteBacklogSuppression) = Unit
}

object NoopNetworkIgnoreRepository : NetworkIgnoreRepository {
    override fun observeIgnores(networkId: Long): Flow<List<NetworkIgnoreEntity>> = flowOf(emptyList())

    override fun observeBuffers(networkId: Long): Flow<List<NetworkBufferToolRow>> = flowOf(emptyList())

    override suspend fun addIgnore(
        networkId: Long,
        pattern: String,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun setIgnoreEnabled(
        id: Long,
        enabled: Boolean,
    ) = Unit

    override suspend fun deleteIgnore(id: Long) = Unit

    override suspend fun setMuted(
        bufferId: Long,
        muted: Boolean,
    ): MuteBacklogSuppression? = null
}

interface BufferRepository {
    fun observeChatList(): Flow<List<ChatListRow>>

    /**
     * Existing QUERY conversations, newest activity first once ordered by the caller.
     *
     * Same projection MONITOR reconciliation uses, exposed above Room for the gesture menu's
     * "recent DMs" ring: it carries identity and recency only, which is all a DM entry needs.
     */
    fun observeQueryConversations(): Flow<List<MonitorQueryRow>> = flowOf(emptyList())

    /** Bounded local nickname matches for starting a QUERY; lightweight fakes stay source-compatible. */
    fun observeNickSuggestions(
        networkId: Long,
        prefix: String,
        selfNick: String,
        limit: Int = 10,
    ): Flow<List<String>> = flowOf(emptyList())

    /**
     * Winner id for [id] after durable room redirects, or null when the room is gone.
     *
     * A stored id can outlive the row it names — a merged QUERY leaves a redirect behind — so any
     * id that was persisted elsewhere (a gesture menu action, a notification) has to be resolved
     * before it is opened or written to. The default keeps lightweight fakes source-compatible.
     */
    suspend fun canonicalBufferId(id: Long): Long? = id

    /** Canonical live invitation events, including resolved rows retained for user context. */
    fun observeInvitations(): Flow<List<InvitationEventRow>> = flowOf(emptyList())

    /** Normalized CHANNEL names confirmed by EventProcessor self-JOIN persistence. */
    fun observeJoinedChannelNames(networkId: Long): Flow<Set<String>> = flowOf(emptySet())

    /** Joined channel picker rows, scoped to one IRC network. */
    fun observeJoinedChannels(networkId: Long): Flow<List<JoinedChannelRow>> = flowOf(emptyList())

    /** Joined channel produced by EventProcessor after an authoritative self-JOIN. */
    suspend fun joinedBufferId(
        networkId: Long,
        normalizedChannel: String,
    ): Long? = null

    fun observeBuffer(id: Long): Flow<BufferEntity?>

    fun observeMembers(bufferId: Long): Flow<List<MemberEntity>>

    /** Nick-only member projection, cheap enough to observe from the moment a conversation opens. */
    fun observeMemberNicks(bufferId: Long): Flow<List<String>> = observeMembers(bufferId).map { members -> members.map(MemberEntity::nick) }

    /** Per-nick last-spoke time in a channel (PRIVMSG/NOTICE/ACTION, isSelf=0). Empty when unavailable. */
    fun observeLastSpokeByNick(bufferId: Long): Flow<Map<String, Long>> = flowOf(emptyMap())

    suspend fun setPinned(
        id: Long,
        pinned: Boolean,
    )

    /** Non-null when unmuting hid a backlog; see BufferDao.setMuted. */
    suspend fun setMuted(
        id: Long,
        muted: Boolean,
    ): MuteBacklogSuppression?

    /** Undo the backlog a previous [setMuted] hid. */
    suspend fun restoreMuteBacklog(suppression: MuteBacklogSuppression) = Unit

    /** Hide or restore a durable CHANNEL/QUERY without altering its IRC membership or history. */
    suspend fun setArchived(
        id: Long,
        archived: Boolean,
    ) = Unit

    /** Persists a nullable per-conversation override; false means the requested room disappeared. */
    suspend fun setLayoutDensityOverride(
        id: Long,
        layout: LayoutDensity?,
    ): Boolean

    /** Persists a nullable per-conversation presence override; false means the room disappeared. */
    suspend fun setPresenceModeOverride(
        id: Long,
        mode: PresenceMode?,
    ): Boolean

    /** Persists a validated conversation avatar model through durable room redirects. */
    suspend fun setAvatarOverride(
        id: Long,
        model: String?,
    ): Boolean = false

    /** Remove local content. QUERY identity/cursor state remains as a hidden reconnect tombstone;
     *  the parting of a joined CHANNEL is handled upstream by the caller (ChatListViewModel). */
    suspend fun deleteBuffer(id: Long)
    // NOTE: mark-read goes through ConnectionManager.markRead (single entry point): it advances the
    // exact local tuple and selects an authoritative timestamp for wire MARKREAD when supported.
}

interface MessageRepository {
    /** Each visibility spec creates a distinct, positionally correct Pager generation. */
    fun messages(
        bufferId: Long,
        visibility: MessageVisibilitySpec,
    ): Flow<PagingData<MessageEntity>>

    /**
     * As [messages], but seeds the Pager's first source load around [initialKey] (a 0-based
     * timeline offset into the same visibility query the PagingSource uses). Supplied for a
     * large open-at-first-unread entry whose anchor sits beyond the default newest load, so the
     * initial refresh materializes the entry row directly instead of forcing a scroll to an
     * unloaded placeholder at the older paging boundary (which would drive a boundary APPEND and
     * churn the generation before the row can compose). A null key preserves the newest-first load.
     */
    fun messages(
        bufferId: Long,
        visibility: MessageVisibilitySpec,
        initialKey: Int?,
    ): Flow<PagingData<MessageEntity>> = messages(bufferId, visibility)

    fun reactions(
        bufferId: Long,
        msgids: List<String>,
    ): Flow<List<ReactionEntity>>

    /** Canonical event-id lookup used by notification and restored-scroll anchors. */
    suspend fun byId(id: Long): MessageEntity? = null

    /** Resolve durable losing room redirects before validating an event-scoped deep link. */
    suspend fun canonicalRoomId(bufferId: Long): Long = bufferId

    suspend fun byMsgid(
        bufferId: Long,
        msgid: String,
    ): MessageEntity?

    /** Observe the canonical local parent, with room-scoped msgid fallback for missing history. */
    fun observeReplyTarget(
        bufferId: Long,
        eventId: Long?,
        msgid: String?,
    ): Flow<MessageEntity?>

    /**
     * Suspend until the local row [id] carries a durable server msgid, or [timeoutMs] elapses.
     * Returns the msgid, or null on timeout / missing row. Used to defer a reaction tapped on a
     * still-pending own message until its echo lands.
     */
    suspend fun awaitMsgid(
        id: Long,
        timeoutMs: Long,
    ): String?

    suspend fun countNewerThan(
        bufferId: Long,
        serverTime: Long,
        id: Long,
        visibility: MessageVisibilitySpec,
    ): Int

    /**
     * Seams for the room's stored history gaps, ordered oldest-first.
     *
     * A seam marks where a gap interrupts the stored stream. Whether a given seam ends up in a
     * rendered slot is decided per row by `seamAbove` against the materialized neighbors.
     *
     * [visibility] is the SAME spec the caller's [messages] stream runs, and is not optional: a
     * seam's position has to be expressed in the coordinate space of the list that is actually
     * presented, or a gap whose newer side is entirely filtered out lands above every visible row
     * and stops being both drawable and demandable.
     */
    fun observeTimelineSeams(
        bufferId: Long,
        visibility: MessageVisibilitySpec,
    ): Flow<List<TimelineSeam>> = flowOf(emptyList())

    /** Delete a locally-stored failed row by id, repairing any exact local read anchor. */
    suspend fun deleteMessage(id: Long)
}

/** Builds the history mediator with the exact visibility coordinate space used by its Pager. */
@OptIn(androidx.paging.ExperimentalPagingApi::class)
fun interface ChatHistoryMediatorFactory {
    fun create(
        bufferId: Long,
        visibility: MessageVisibilitySpec,
        identityRules: IrcIdentityRules,
    ): RemoteMediator<Int, MessageEntity>
}

/** Local FTS results plus the honesty metadata the screen must disclose. */
data class LocalSearchResult(
    val hits: List<SearchHit>,
    /** True when the raw FTS page hit the DAO's 200-row cap, measured BEFORE visibility filtering. */
    val truncated: Boolean,
)

/** What the searched corpus actually covers, per scope. */
sealed interface SearchCoverage {
    /** The all-buffers scope can only ever promise "whatever this device persisted". */
    data object DeviceOnly : SearchCoverage

    /** Every message this conversation ever had is on this device. */
    data object BufferComplete : SearchCoverage

    /** Known holes: [openGaps] recorded intervals, plus whether the oldest edge is reached. */
    data class BufferPartial(
        val openGaps: Int,
        val historyComplete: Boolean,
    ) : SearchCoverage
}

interface SearchRepository {
    fun search(
        query: String,
        bufferId: Long?,
    ): Flow<LocalSearchResult>

    /** Coverage for the given scope; null bufferId means the all-buffers scope. */
    fun coverage(bufferId: Long?): Flow<SearchCoverage>
}

/** Read-only cross-buffer conversation stream over the shared messages table, newest first. */
interface GlobalFeedRepository {
    fun globalFeed(spec: MessageVisibilitySpec): Flow<PagingData<SearchHit>>
}

/**
 * A completed preview lookup kept in the process-lifetime cache. A nullable [preview] distinguishes
 * a known negative result from a cache miss, represented by a null [cachedPreview] return value.
 */
data class CachedLinkPreview(
    val preview: LinkPreview?,
)

/**
 * Declared web or text link preview; in-memory LRU + shared fetch on miss. [networkId] identifies
 * the network the link was seen on so the fetch traverses that network's proxy route; an unknown
 * (null) identity fails closed with a retryable internal failure and fetches nothing.
 */
interface LinkPreviewRepository {
    /** Returns a completed positive or negative result without starting work. */
    fun cachedPreview(
        url: String,
        networkId: Long?,
    ): CachedLinkPreview? = null

    suspend fun preview(
        url: String,
        networkId: Long?,
    ): LinkPreview?
}

enum class LinkPreviewKind { WEB, VIDEO, FILE, TEXT, WIKIPEDIA }

data class LinkPreview(
    val url: String,
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val siteName: String?,
    val kind: LinkPreviewKind = LinkPreviewKind.WEB,
)
