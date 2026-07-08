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
}

interface BufferRepository {
    fun observeChatList(): Flow<List<ChatListRow>>
    fun observeBuffer(id: Long): Flow<BufferEntity?>
    fun observeMembers(bufferId: Long): Flow<List<MemberEntity>>
    suspend fun setPinned(id: Long, pinned: Boolean)
    suspend fun setMuted(id: Long, muted: Boolean)
    // NOTE: mark-read goes through ConnectionManager.markRead (single entry point) —
    // it advances Room via BufferDao.advanceReadMarker AND sends MARKREAD when supported.
}

interface MessageRepository {
    /** Paging 3 stream wired to ChatHistoryRemoteMediator (WP5 supplies mediator via factory). */
    fun messages(bufferId: Long): Flow<PagingData<MessageEntity>>
    fun reactions(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>>
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
