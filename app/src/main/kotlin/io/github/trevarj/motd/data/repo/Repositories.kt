package io.github.trevarj.motd.data.repo

import androidx.paging.PagingData
import androidx.paging.RemoteMediator
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.prefs.LayoutDensity
import kotlinx.coroutines.flow.Flow

interface NetworkRepository {
    fun observeNetworks(): Flow<List<NetworkEntity>>
    suspend fun addNetwork(n: NetworkEntity): Long
    suspend fun updateNetwork(n: NetworkEntity)
    suspend fun deleteNetwork(id: Long)

    // Round 5 (plans/16): point reads for the network-management screens.
    /** Point read (drives NetworkSettings/Bouncer screens; delegates to NetworkDao.byId). */
    suspend fun networkById(id: Long): NetworkEntity?

    /** Local BOUNCER_CHILD mirrors of a soju root (delegates to NetworkDao.childrenOf). */
    suspend fun childrenOf(rootId: Long): List<NetworkEntity>
}

interface BufferRepository {
    fun observeChatList(): Flow<List<ChatListRow>>
    fun observeBuffer(id: Long): Flow<BufferEntity?>
    fun observeMembers(bufferId: Long): Flow<List<MemberEntity>>
    suspend fun setPinned(id: Long, pinned: Boolean)
    suspend fun setMuted(id: Long, muted: Boolean)
    /** Persists a nullable per-conversation override; false means the requested room disappeared. */
    suspend fun setLayoutDensityOverride(id: Long, layout: LayoutDensity?): Boolean
    /** Remove local content. QUERY identity/cursor state remains as a hidden reconnect tombstone;
     *  the parting of a joined CHANNEL is handled upstream by the caller (ChatListViewModel). */
    suspend fun deleteBuffer(id: Long)
    // NOTE: mark-read goes through ConnectionManager.markRead (single entry point): it advances the
    // exact local tuple and selects an authoritative timestamp for wire MARKREAD when supported.
}

interface MessageRepository {
    /** Each visibility spec creates a distinct, positionally correct Pager generation. */
    fun messages(bufferId: Long, visibility: MessageVisibilitySpec): Flow<PagingData<MessageEntity>>
    fun reactions(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>>
    /** Canonical event-id lookup used by notification and restored-scroll anchors. */
    suspend fun byId(id: Long): MessageEntity? = null
    /** Resolve durable losing room redirects before validating an event-scoped deep link. */
    suspend fun canonicalRoomId(bufferId: Long): Long = bufferId
    suspend fun byMsgid(bufferId: Long, msgid: String): MessageEntity?
    /** Reactive reply-target lookup; emits again when echo/history supplies the referenced msgid. */
    fun observeByMsgid(bufferId: Long, msgid: String): Flow<MessageEntity?>
    /**
     * Suspend until the local row [id] carries a durable server msgid, or [timeoutMs] elapses.
     * Returns the msgid, or null on timeout / missing row. Used to defer a reaction tapped on a
     * still-pending own message until its echo lands (plans/15 reactions).
     */
    suspend fun awaitMsgid(id: Long, timeoutMs: Long): String?
    suspend fun countNewerThan(
        bufferId: Long,
        serverTime: Long,
        id: Long,
        visibility: MessageVisibilitySpec,
    ): Int
    /** Delete a locally-stored failed row by id, repairing any exact local read anchor. */
    suspend fun deleteMessage(id: Long)
}

/** WP4 injects this to build its Pager; WP1 stub-binds a no-op (immediate endOfPagination),
 *  WP5 provides the real CHATHISTORY-backed implementation, WP10 rebinds. */
@OptIn(androidx.paging.ExperimentalPagingApi::class) // RemoteMediator is experimental; annotation does not alter the frozen signature
fun interface ChatHistoryMediatorFactory {
    fun create(bufferId: Long): RemoteMediator<Int, MessageEntity>
}

interface SearchRepository {
    fun search(query: String, bufferId: Long?): Flow<List<SearchHit>>
}

/**
 * A completed preview lookup kept in the process-lifetime cache. A nullable [preview] distinguishes
 * a known negative result from a cache miss, represented by a null [cachedPreview] return value.
 */
data class CachedLinkPreview(val preview: LinkPreview?)

/** OG-tag link preview; in-memory LRU + shared fetch on miss. Null if unfetchable/not HTML. */
interface LinkPreviewRepository {
    /** Returns a completed positive or negative result without starting work. */
    fun cachedPreview(url: String): CachedLinkPreview? = null

    suspend fun preview(url: String): LinkPreview?
}

data class LinkPreview(val url: String, val title: String?, val description: String?, val imageUrl: String?, val siteName: String?)
