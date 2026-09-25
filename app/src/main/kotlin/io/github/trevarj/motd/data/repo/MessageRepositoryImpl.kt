package io.github.trevarj.motd.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.sqlite.db.SimpleSQLiteQuery
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
import io.github.trevarj.motd.data.visibility.MessageVisibilitySql
import io.github.trevarj.motd.data.visibility.countTimelineNewerQuery
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.data.visibility.newestPresentedMessageQuery
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        ): Flow<PagingData<MessageEntity>> = messages(bufferId, visibility, initialKey, MutableStateFlow(null))

        @OptIn(ExperimentalPagingApi::class)
        override fun messages(
            bufferId: Long,
            visibility: MessageVisibilitySpec,
            initialKey: Int?,
            viewport: StateFlow<ViewportRefreshAnchor?>,
        ): Flow<PagingData<MessageEntity>> =
            pagingContextFlow(bufferId).flatMapLatest { context ->
                Pager(
                    config = MESSAGE_PAGING_CONFIG,
                    // Initial entry retains its own computed key until the screen has a laid-out
                    // viewport. Later invalidations use that viewport instead of collapsed-row hints.
                    initialKey = initialKey?.coerceAtLeast(0),
                    remoteMediator = mediatorFactory.create(context.roomId, visibility, context.identityRules),
                    pagingSourceFactory = {
                        ViewportPagingSource(
                            messageDao.pagingSource(
                                messagePagingQuery(context.roomId, visibility, context.identityRules),
                            ),
                            viewport,
                        ) { id ->
                            val row =
                                messageDao.rawMessage(
                                    SimpleSQLiteQuery(
                                        "SELECT m.* FROM messages m WHERE m.id = ? AND m.bufferId = ? " +
                                            "AND ${MessageVisibilitySql(visibility, context.identityRules).timeline()}",
                                        arrayOf(id, context.roomId),
                                    ),
                                ) ?: return@ViewportPagingSource null
                            messageDao.rawCount(
                                countTimelineNewerQuery(
                                    context.roomId,
                                    row.serverTime,
                                    row.id,
                                    row.timelineOrder,
                                    visibility,
                                    context.identityRules,
                                ),
                            )
                        }
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

// ponytail: Room still owns pages/counts; only the refresh key follows the visual viewport.
@OptIn(ExperimentalPagingApi::class)
internal class ViewportPagingSource(
    private val room: PagingSource<Int, MessageEntity>,
    private val viewport: StateFlow<ViewportRefreshAnchor?>,
    private val resolveParkedIndex: suspend (Long) -> Int?,
) : PagingSource<Int, MessageEntity>() {
    // ponytail: remember loaded offsets for this generation; Room owns the actual rows and counts.
    private val loadedOffsets = HashMap<Long, Int>()

    init {
        room.registerInvalidatedCallback { invalidate() }
        registerInvalidatedCallback { room.invalidate() }
    }

    override val jumpingSupported: Boolean get() = room.jumpingSupported

    // Parked refresh resolves its current Room position in load, after intervening inserts.
    override fun getRefreshKey(state: PagingState<Int, MessageEntity>): Int? = if (viewport.value != null) 0 else room.getRefreshKey(state)

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MessageEntity> {
        val result =
            if (params !is LoadParams.Refresh || (params.key != null && params.key != 0)) {
                room.load(params)
            } else {
                // ponytail: viewport invalidations use key zero; preserve explicit nonzero jumps/entries.
                val key =
                    when (val anchor = viewport.value) {
                        ViewportRefreshAnchor.Newest -> 0
                        is ViewportRefreshAnchor.Parked -> resolveParkedIndex(anchor.id)?.let { entryAnchorPagingKey(it) ?: 0 }
                        null -> null
                    } ?: params.key
                room.load(LoadParams.Refresh(key, params.loadSize, params.placeholdersEnabled))
            }
        synchronized(loadedOffsets) {
            if (invalid) return LoadResult.Invalid()
            // Unknown counts have no absolute offsets; the production Pager always enables placeholders.
            if (result is LoadResult.Page && result.itemsBefore != LoadResult.Page.COUNT_UNDEFINED) {
                result.data.forEachIndexed { offset, message ->
                    val position = result.itemsBefore + offset
                    val previous = loadedOffsets.put(message.id, position)
                    if (previous != null && previous != position) {
                        invalidate()
                        return LoadResult.Invalid()
                    }
                }
            }
        }
        return result
    }
}

internal val MESSAGE_PAGING_CONFIG =
    PagingConfig(
        pageSize = 50,
        prefetchDistance = 25,
        enablePlaceholders = true,
        initialLoadSize = 600,
        maxSize = 1_500,
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
 * Anchors inside the selected newest load return null: the plain newest-first refresh already
 * materializes them, keeping first-open backfill behavior untouched.
 */
internal fun entryAnchorPagingKey(index: Int): Int? {
    if (index < MESSAGE_PAGING_CONFIG.initialLoadSize) return null
    return (index - (MESSAGE_PAGING_CONFIG.initialLoadSize - MESSAGE_PAGING_CONFIG.pageSize)).coerceAtLeast(0)
}
