package io.github.trevarj.motd.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.ReactionDao
import io.github.trevarj.motd.data.db.ReactionEntity
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

// Paging 3 stream backed by the local pagingSource, with a RemoteMediator supplied per buffer
// by the injected factory (WP1 no-op / WP5 CHATHISTORY-backed). PagingConfig is the frozen
// contract: pageSize 50, prefetch 25, no placeholders.
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val reactionDao: ReactionDao,
    private val mediatorFactory: ChatHistoryMediatorFactory,
) : MessageRepository {
    @OptIn(ExperimentalPagingApi::class)
    override fun messages(bufferId: Long): Flow<PagingData<MessageEntity>> =
        Pager(
            config = PagingConfig(
                pageSize = 50,
                prefetchDistance = 25,
                enablePlaceholders = false,
            ),
            remoteMediator = mediatorFactory.create(bufferId),
            pagingSourceFactory = { messageDao.pagingSource(bufferId) },
        ).flow

    override fun reactions(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>> =
        reactionDao.observeFor(bufferId, msgids)
}
