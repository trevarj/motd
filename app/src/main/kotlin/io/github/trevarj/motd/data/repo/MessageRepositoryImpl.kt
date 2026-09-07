package io.github.trevarj.motd.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.HistoryGapDao
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.NetworkIdentityDao
import io.github.trevarj.motd.data.db.ReactionDao
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.data.history.GapAnchorResolver
import io.github.trevarj.motd.data.history.TimelineSeam
import io.github.trevarj.motd.data.history.timelineSeams
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.countTimelineNewerQuery
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.data.visibility.newestPresentedMessageQuery
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

// Paging 3 stream backed by the local pagingSource, with a RemoteMediator supplied per buffer
// by the injected factory (WP1 no-op / WP5 CHATHISTORY-backed).
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageRepositoryImpl
    @Inject
    constructor(
        private val bufferDao: BufferDao,
        private val networkIdentityDao: NetworkIdentityDao,
        private val messageDao: MessageDao,
        private val reactionDao: ReactionDao,
        private val mediatorFactory: ChatHistoryMediatorFactory,
        private val historyGapDao: HistoryGapDao,
        // Gap-edge geometry lives in :data.history and is shared with the mediator. It is a stateless
        // reader over messageDao, so the default keeps hand-built call sites (tests) unchanged while
        // Hilt supplies the same instance through GapAnchorResolver's own @Inject constructor.
        private val gapAnchors: GapAnchorResolver = GapAnchorResolver(messageDao),
    ) : MessageRepository {
        @OptIn(ExperimentalPagingApi::class)
        override fun messages(
            bufferId: Long,
            visibility: MessageVisibilitySpec,
        ): Flow<PagingData<MessageEntity>> = messages(bufferId, visibility, initialKey = null)

        @OptIn(ExperimentalPagingApi::class)
        override fun messages(
            bufferId: Long,
            visibility: MessageVisibilitySpec,
            initialKey: Int?,
        ): Flow<PagingData<MessageEntity>> =
            pagingContextFlow(bufferId).flatMapLatest { context ->
                Pager(
                    config = MESSAGE_PAGING_CONFIG,
                    // Seed the first source load from the caller-computed key so a deep
                    // open-at-first-unread entry materializes together with the viewport below it in
                    // the initial refresh (see entryAnchorPagingKey — callers gate depth and shift
                    // the key; Room clamps a key at or past the window end to the trailing load, so
                    // a transiently smaller window cannot key past its own bounds).
                    initialKey = initialKey?.coerceAtLeast(0),
                    // Scroll-driven paging: the mediator is always attached so Paging3 APPEND drives
                    // older history at the bottom of the timeline; interior seams belong to the gap
                    // fill coordinator. The canonical id comes from pagingContextFlow, so a durable
                    // redirect still paints and pages the winner room.
                    remoteMediator = mediatorFactory.create(context.roomId, visibility, context.identityRules),
                    pagingSourceFactory = {
                        messageDao.pagingSource(
                            messagePagingQuery(
                                context.roomId,
                                visibility,
                                context.identityRules,
                            ),
                        )
                    },
                ).flow
            }

        // Kept for the frozen contract; scopes to a small, fixed msgid set (safe under 999 vars).
        override fun reactions(
            bufferId: Long,
            msgids: List<String>,
        ): Flow<List<ReactionEntity>> = canonicalRoomIdFlow(bufferId).flatMapLatest { reactionDao.observeFor(it, msgids) }

        override suspend fun byId(id: Long): MessageEntity? = messageDao.byCanonicalId(id)

        override suspend fun canonicalRoomId(bufferId: Long): Long = resolveRoomId(bufferId)

        override suspend fun byMsgid(
            bufferId: Long,
            msgid: String,
        ): MessageEntity? = messageDao.byMsgid(resolveRoomId(bufferId), msgid)

        override fun observeReplyTarget(
            bufferId: Long,
            eventId: Long?,
            msgid: String?,
        ): Flow<MessageEntity?> =
            canonicalRoomIdFlow(bufferId)
                .flatMapLatest { messageDao.observeReplyTarget(it, eventId, msgid) }
                .distinctUntilChanged()

        // Wait for the echo to promote a pending own row's msgid in place. observeMsgid emits the
        // current value immediately (null while pending) and again when the row updates, so first
        // non-null wins; withTimeoutOrNull bounds the wait so a lost echo can't hang the react forever.
        override suspend fun awaitMsgid(
            id: Long,
            timeoutMs: Long,
        ): String? =
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                messageDao.observeCanonicalMsgid(id).firstOrNull { it != null }
            }

        override suspend fun countNewerThan(
            bufferId: Long,
            serverTime: Long,
            id: Long,
            visibility: MessageVisibilitySpec,
        ): Int {
            val context = resolvePagingContext(bufferId)
            val timelineOrder = messageDao.byCanonicalId(id)?.timelineOrder ?: id
            return messageDao.rawCount(
                countTimelineNewerQuery(
                    context.roomId,
                    serverTime,
                    id,
                    timelineOrder,
                    visibility,
                    context.identityRules,
                ),
            )
        }

        override suspend fun deleteMessage(id: Long) {
            val bufferId = messageDao.byCanonicalId(id)?.bufferId
            messageDao.deleteWithAnchorFallback(id)
            bufferId?.let { bufferDao.refreshMonitorActivity(it) }
        }

        // Observe the room's stored gaps and resolve both edges against the local store, then project
        // them through the seam role and clamp them into the coordinate space of the list [visibility]
        // actually presents (see [timelineSeams]).
        //
        // Derived from pagingContextFlow so the clamp runs the SAME predicate as the PagingSource, down
        // to the network's identity rules — a clamp computed against a row the Pager hides would land
        // the seam back above every presented row, which is the defect this exists to close. That flow
        // is distinct-until-changed on (roomId, identityRules), so an unrelated room field changing
        // underneath still does not re-emit or re-subscribe anything here.
        override fun observeTimelineSeams(
            bufferId: Long,
            visibility: MessageVisibilitySpec,
        ): Flow<List<TimelineSeam>> =
            pagingContextFlow(bufferId)
                .flatMapLatest { context ->
                    historyGapDao.observeForRoom(context.roomId).flatMapLatest { gaps ->
                        if (gaps.isEmpty()) {
                            // No gap, no clamp to compute: the overwhelmingly common room never observes
                            // the messages table for this at all.
                            flowOf(emptyList())
                        } else {
                            newestPresentedAnchor(context, visibility).map { newestPresented ->
                                timelineSeams(gapAnchors.resolve(context.roomId, gaps), newestPresented)
                            }
                        }
                    }
                }.distinctUntilChanged()

        /** The presented list's ceiling, re-read whenever a write could have moved it. */
        private fun newestPresentedAnchor(
            context: PagingContext,
            visibility: MessageVisibilitySpec,
        ): Flow<TimelineAnchor?> =
            messageDao
                .observeRawMessage(
                    newestPresentedMessageQuery(context.roomId, visibility, context.identityRules),
                ).map { row -> row?.let { TimelineAnchor(it.serverTime, it.id, it.timelineOrder) } }
                .distinctUntilChanged()

        private fun canonicalRoomIdFlow(bufferId: Long): Flow<Long> =
            bufferDao
                .observe(bufferId)
                .map { it?.id ?: bufferId }
                .distinctUntilChanged()

        private fun pagingContextFlow(bufferId: Long): Flow<PagingContext> =
            bufferDao
                .observe(bufferId)
                .flatMapLatest { room ->
                    if (room == null) {
                        flowOf(PagingContext(bufferId, IrcIdentityRules()))
                    } else {
                        networkIdentityDao.observe(room.networkId).map { identity ->
                            PagingContext(room.id, identity?.identityRules ?: IrcIdentityRules())
                        }
                    }
                }.distinctUntilChanged()

        private suspend fun resolvePagingContext(bufferId: Long): PagingContext {
            val room =
                bufferDao.observeById(bufferId)
                    ?: return PagingContext(bufferId, IrcIdentityRules())
            val identityRules =
                networkIdentityDao.byNetwork(room.networkId)?.identityRules
                    ?: IrcIdentityRules()
            return PagingContext(room.id, identityRules)
        }

        private suspend fun resolveRoomId(bufferId: Long): Long = bufferDao.canonicalId(bufferId) ?: bufferId

        private data class PagingContext(
            val roomId: Long,
            val identityRules: IrcIdentityRules,
        )
    }

internal val MESSAGE_PAGING_CONFIG =
    PagingConfig(
        pageSize = 50,
        prefetchDistance = 25,
        enablePlaceholders = true,
        maxSize = 500,
        jumpThreshold = 250,
    )

/**
 * Pager initial key for an open-at-first-unread entry anchored at timeline offset [index].
 *
 * Room's paging source treats a refresh key as the load's START offset (end-clamping it only when
 * the key sits within `initialLoadSize` of the window end), so keying the Pager at the anchor
 * itself would materialize the anchor plus OLDER rows only — every newer row below it in the
 * reversed viewport would stay a placeholder until later prepend hints, which a regenerating
 * bounded window can starve. Shift the key back by `initialLoadSize - pageSize` so the first load
 * covers the anchor, a full viewport of newer rows below it, and one page of older rows above.
 * Anchors inside the default newest load return null: the plain newest-first refresh already
 * materializes them, keeping first-open backfill behavior untouched.
 */
internal fun entryAnchorPagingKey(index: Int): Int? {
    val config = MESSAGE_PAGING_CONFIG
    if (index < config.initialLoadSize) return null
    return (index - (config.initialLoadSize - config.pageSize)).coerceAtLeast(0)
}
