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
    /** Remove a buffer and all of its content (messages/members/reactions). Destructive: the
     *  parting of a joined CHANNEL is handled upstream by the caller (ChatListViewModel). */
    suspend fun deleteBuffer(id: Long)
    // NOTE: mark-read goes through ConnectionManager.markRead (single entry point) —
    // it advances Room via BufferDao.advanceReadMarker AND sends MARKREAD when supported.
}

interface MessageRepository {
    /** Paging 3 stream wired to ChatHistoryRemoteMediator (WP5 supplies mediator via factory). */
    fun messages(bufferId: Long): Flow<PagingData<MessageEntity>>
    fun reactions(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>>
    /** Buffer-scoped reactions (no per-msgid IN list); callers filter to the visible window
     *  in memory to avoid SQLite's bind-variable overflow on large windows (plans/15 #5, #18). */
    fun reactionsForBuffer(bufferId: Long): Flow<List<ReactionEntity>>
    suspend fun byMsgid(bufferId: Long, msgid: String): MessageEntity?
    suspend fun countNewerThan(bufferId: Long, serverTime: Long, id: Long): Int
    /** Oldest non-self message time past [after], or null; anchors the unread divider/badge. */
    suspend fun firstUnreadOtherTime(bufferId: Long, after: Long): Long?
    /** Delete a locally-stored row by id (failed-echo cleanup on retry/delete, plans/15 #10). */
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

/** OG-tag link preview; in-memory LRU + fetch on miss. Null if unfetchable/not HTML. */
interface LinkPreviewRepository {
    suspend fun preview(url: String): LinkPreview?
}

data class LinkPreview(val url: String, val title: String?, val description: String?, val imageUrl: String?, val siteName: String?)
