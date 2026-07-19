package io.github.trevarj.motd.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.NetworkIdentityDao
import io.github.trevarj.motd.data.db.ReactionDao
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.countTimelineNewerQuery
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// Paging 3 stream backed by the local pagingSource, with a RemoteMediator supplied per buffer
// by the injected factory (WP1 no-op / WP5 CHATHISTORY-backed).
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageRepositoryImpl @Inject constructor(
    private val bufferDao: BufferDao,
    private val networkIdentityDao: NetworkIdentityDao,
    private val messageDao: MessageDao,
    private val reactionDao: ReactionDao,
    private val mediatorFactory: ChatHistoryMediatorFactory,
) : MessageRepository {
    @OptIn(ExperimentalPagingApi::class)
    override fun messages(
        bufferId: Long,
        visibility: MessageVisibilitySpec,
    ): Flow<PagingData<MessageEntity>> =
        pagingContextFlow(bufferId).flatMapLatest { (roomId, identityRules) ->
            Pager(
                config = MESSAGE_PAGING_CONFIG,
                remoteMediator = mediatorFactory.create(roomId),
                pagingSourceFactory = {
                    messageDao.pagingSource(messagePagingQuery(roomId, visibility, identityRules))
                },
            ).flow
        }

    // Kept for the frozen contract; scopes to a small, fixed msgid set (safe under 999 vars).
    override fun reactions(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>> =
        canonicalRoomIdFlow(bufferId).flatMapLatest { reactionDao.observeFor(it, msgids) }

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

    override suspend fun countNewerThan(
        bufferId: Long,
        serverTime: Long,
        id: Long,
        visibility: MessageVisibilitySpec,
    ): Int {
        val (roomId, identityRules) = resolvePagingContext(bufferId)
        return messageDao.rawCount(
            countTimelineNewerQuery(roomId, serverTime, id, visibility, identityRules),
        )
    }

    override suspend fun deleteMessage(id: Long) = messageDao.deleteWithAnchorFallback(id)

    private fun canonicalRoomIdFlow(bufferId: Long): Flow<Long> = bufferDao.observe(bufferId)
        .map { it?.id ?: bufferId }
        .distinctUntilChanged()

    private fun pagingContextFlow(bufferId: Long): Flow<PagingContext> =
        bufferDao.observe(bufferId).flatMapLatest { room ->
            if (room == null) {
                flowOf(PagingContext(bufferId, IrcIdentityRules()))
            } else {
                networkIdentityDao.observe(room.networkId).map { identity ->
                    PagingContext(room.id, identity?.identityRules ?: IrcIdentityRules())
                }
            }
        }.distinctUntilChanged()

    private suspend fun resolvePagingContext(bufferId: Long): PagingContext {
        val room = bufferDao.observeById(bufferId)
            ?: return PagingContext(bufferId, IrcIdentityRules())
        val identityRules = networkIdentityDao.byNetwork(room.networkId)?.identityRules
            ?: IrcIdentityRules()
        return PagingContext(room.id, identityRules)
    }

    private suspend fun resolveRoomId(bufferId: Long): Long =
        bufferDao.canonicalId(bufferId) ?: bufferId

    private data class PagingContext(
        val roomId: Long,
        val identityRules: IrcIdentityRules,
    )
}

internal val MESSAGE_PAGING_CONFIG = PagingConfig(
    pageSize = 50,
    prefetchDistance = 25,
    enablePlaceholders = true,
    maxSize = 500,
    jumpThreshold = 250,
)
