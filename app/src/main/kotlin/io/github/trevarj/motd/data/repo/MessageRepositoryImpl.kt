package io.github.trevarj.motd.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.ReactionDao
import io.github.trevarj.motd.data.db.ReactionEntity
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

// Paging 3 stream backed by the local pagingSource, with a RemoteMediator supplied per buffer
// by the injected factory (WP1 no-op / WP5 CHATHISTORY-backed). PagingConfig is the frozen
// contract: pageSize 50, prefetch 25, no placeholders.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageRepositoryImpl @Inject constructor(
    private val bufferDao: BufferDao,
    private val messageDao: MessageDao,
    private val reactionDao: ReactionDao,
    private val mediatorFactory: ChatHistoryMediatorFactory,
) : MessageRepository {
    @OptIn(ExperimentalPagingApi::class)
    override fun messages(bufferId: Long): Flow<PagingData<MessageEntity>> =
        canonicalRoomIdFlow(bufferId).flatMapLatest { roomId ->
            Pager(
                config = PagingConfig(
                    pageSize = 50,
                    prefetchDistance = 25,
                    enablePlaceholders = false,
                ),
                remoteMediator = mediatorFactory.create(roomId),
                pagingSourceFactory = { messageDao.pagingSource(roomId) },
            ).flow
        }

    // Kept for the frozen contract; scopes to a small, fixed msgid set (safe under 999 vars).
    override fun reactions(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>> =
        canonicalRoomIdFlow(bufferId).flatMapLatest { reactionDao.observeFor(it, msgids) }

    // Buffer-scoped observe: one stable query regardless of how far the user scrolls back, so the
    // per-msgid IN(...) list can never exceed SQLite's ~999 bind-variable cap (plans/15 #5). The
    // screen aggregates only the visible msgids from this stream.
    override fun reactionsForBuffer(bufferId: Long): Flow<List<ReactionEntity>> =
        canonicalRoomIdFlow(bufferId).flatMapLatest(reactionDao::observeForBuffer)

    override suspend fun byId(id: Long): MessageEntity? = messageDao.byCanonicalId(id)

    override suspend fun canonicalRoomId(bufferId: Long): Long = resolveRoomId(bufferId)

    override suspend fun byMsgid(bufferId: Long, msgid: String): MessageEntity? =
        messageDao.byMsgid(resolveRoomId(bufferId), msgid)

    override fun observeByMsgid(bufferId: Long, msgid: String): Flow<MessageEntity?> =
        canonicalRoomIdFlow(bufferId).flatMapLatest { messageDao.observeByMsgid(it, msgid) }

    // Wait for the echo to promote a pending own row's msgid in place. observeMsgid emits the
    // current value immediately (null while pending) and again when the row updates, so first
    // non-null wins; withTimeoutOrNull bounds the wait so a lost echo can't hang the react forever.
    override suspend fun awaitMsgid(id: Long, timeoutMs: Long): String? =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            messageDao.observeCanonicalMsgid(id).firstOrNull { it != null }
        }

    override suspend fun countNewerThan(bufferId: Long, serverTime: Long, id: Long): Int =
        messageDao.countNewerThan(resolveRoomId(bufferId), serverTime, id)

    override suspend fun firstUnreadOtherTime(bufferId: Long, after: Long): Long? =
        messageDao.firstUnreadOtherTime(resolveRoomId(bufferId), after)

    override suspend fun deleteMessage(id: Long) = messageDao.deleteById(id)

    private fun canonicalRoomIdFlow(bufferId: Long): Flow<Long> = bufferDao.observe(bufferId)
        .map { it?.id ?: bufferId }
        .distinctUntilChanged()

    private suspend fun resolveRoomId(bufferId: Long): Long =
        bufferDao.canonicalId(bufferId) ?: bufferId
}
