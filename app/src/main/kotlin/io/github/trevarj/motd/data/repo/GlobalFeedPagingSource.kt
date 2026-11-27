package io.github.trevarj.motd.data.repo

import android.database.SQLException
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.visibility.ActivityFilter
import io.github.trevarj.motd.data.visibility.GlobalFeedKey
import io.github.trevarj.motd.data.visibility.GlobalFeedKind
import io.github.trevarj.motd.data.visibility.GlobalFeedSeek
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.globalFeedPagingQuery
import io.github.trevarj.motd.data.visibility.includesReactions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Base feed tables; only the reaction scopes also observe the active reaction aggregates. */
private val GLOBAL_FEED_TABLES =
    arrayOf("messages", "buffers", "networks", "event_redirects", "network_identity")
private val REACTION_FEED_TABLES = GLOBAL_FEED_TABLES + "reactions"

/**
 * Keyset pager over the cross-buffer stream.
 *
 * Room's generated paging source is positional: it runs `SELECT COUNT(*)` over this five-way join
 * on every initial load — once per arriving message, since any of [GLOBAL_FEED_TABLES] invalidates it
 * — and pages with LIMIT/OFFSET, so a deep scroll recomputes every preceding row. This seeks on
 * `(serverTime, id)` instead: no COUNT, no OFFSET. Activity also seeks reactions by receipt time.
 *
 * Invalidation is wired by hand for the same tables Room used to observe, registered on the first
 * load and released with the source.
 */
internal class GlobalFeedPagingSource(
    private val db: MotdDatabase,
    private val spec: MessageVisibilitySpec,
    private val activityFilter: ActivityFilter? = null,
    private val isAtNewest: () -> Boolean = { false },
) : PagingSource<GlobalFeedKey, SearchHit>() {
    private val observer =
        object : InvalidationTracker.Observer(if (activityFilter.includesReactions) REACTION_FEED_TABLES else GLOBAL_FEED_TABLES) {
            override fun onInvalidated(tables: Set<String>) = invalidate()
        }
    private val observing = AtomicBoolean(false)

    init {
        registerInvalidatedCallback {
            if (observing.getAndSet(false)) db.invalidationTracker.removeObserver(observer)
        }
    }

    /**
     * The viewport survives a refresh: re-seek from the closest loaded row's own key rather than
     * from a position, which a merged stream does not have.
     */
    override fun getRefreshKey(state: PagingState<GlobalFeedKey, SearchHit>): GlobalFeedKey? {
        // At Activity's newest edge, refresh from the top so new events land in the first page.
        // Elsewhere, retain the anchor so an invalidation does not move the viewport.
        if (activityFilter != null && isAtNewest()) return null
        return state.anchorPosition
            ?.let(state::closestItemToPosition)
            ?.key()
    }

    override suspend fun load(params: LoadParams<GlobalFeedKey>): LoadResult<GlobalFeedKey, SearchHit> =
        // Off the collector's thread: registering the tracker observer and reading a page are both
        // database work, and the caller collects this Pager on the main dispatcher.
        withContext(Dispatchers.IO) {
            observe()
            if (invalid) return@withContext LoadResult.Invalid()
            try {
                when (params) {
                    // Anchored refresh includes the anchor row itself, so the viewport keeps its place.
                    is LoadParams.Refresh -> {
                        if (activityFilter != null) {
                            // Each mixed page is one SQL snapshot. An anchored refresh also keeps
                            // its two directions together across concurrent parent/reaction writes.
                            return@withContext if (activityFilter.includesReactions && params.key != null && params.loadSize > 1) {
                                db.withTransaction { refreshActivity(params) }
                            } else {
                                refreshActivity(params)
                            }
                        }
                        val rows = page(params.key, GlobalFeedSeek.OLDER_OR_AT, params.loadSize)
                        LoadResult.Page(
                            data = rows,
                            // Fall back to the anchor so an anchored refresh that found nothing at
                            // or below it can still prepend the rows above it.
                            prevKey = rows.firstOrNull()?.key() ?: params.key,
                            nextKey = rows.lastOrNull()?.key(),
                        )
                    }

                    // Older rows. A short page ends append pagination; nextKey null says so.
                    is LoadParams.Append -> {
                        val rows = page(params.key, GlobalFeedSeek.OLDER, params.loadSize)
                        LoadResult.Page(rows, prevKey = null, nextKey = rows.lastOrNull()?.key())
                    }

                    // Newer rows, read ascending from the key and flipped back to newest-first.
                    is LoadParams.Prepend -> {
                        val rows = page(params.key, GlobalFeedSeek.NEWER, params.loadSize).asReversed()
                        LoadResult.Page(rows, prevKey = rows.firstOrNull()?.key(), nextKey = null)
                    }
                }
            } catch (e: SQLException) {
                LoadResult.Error(e)
            }
        }

    private suspend fun refreshActivity(params: LoadParams.Refresh<GlobalFeedKey>): LoadResult.Page<GlobalFeedKey, SearchHit> {
        val newerLimit = params.loadSize / 2
        val newer =
            if (params.key != null && newerLimit > 0) {
                page(params.key, GlobalFeedSeek.NEWER, newerLimit).asReversed()
            } else {
                emptyList()
            }
        val olderLimit = params.loadSize - newer.size
        val older = page(params.key, GlobalFeedSeek.OLDER_OR_AT, olderLimit)
        return LoadResult.Page(
            data = if (newer.isEmpty()) older else newer + older,
            prevKey =
                if (params.key != null && (newerLimit == 0 || newer.size == newerLimit)) {
                    newer.firstOrNull()?.key() ?: older.firstOrNull()?.key() ?: params.key
                } else {
                    null
                },
            nextKey = if (older.size == olderLimit) older.lastOrNull()?.key() else null,
        )
    }

    private suspend fun page(
        key: GlobalFeedKey?,
        seek: GlobalFeedSeek,
        limit: Int,
    ): List<SearchHit> = db.messageDao().globalFeedPage(globalFeedPagingQuery(spec, key, seek, limit, activityFilter))

    /** First load arms the observer; the source is discarded on the first invalidation after that. */
    private fun observe() {
        if (!invalid && observing.compareAndSet(false, true)) {
            db.invalidationTracker.addObserver(observer)
            // The pager may have been canceled before registration finished.
            if (invalid) db.invalidationTracker.removeObserver(observer)
        }
    }
}

private fun SearchHit.key(): GlobalFeedKey {
    val reaction = activityReaction
    return if (reaction == null) {
        GlobalFeedKey(message.serverTime, message.id)
    } else {
        GlobalFeedKey(reaction.at, reaction.id, GlobalFeedKind.REACTION)
    }
}
