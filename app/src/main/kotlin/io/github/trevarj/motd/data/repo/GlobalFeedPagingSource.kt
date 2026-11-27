package io.github.trevarj.motd.data.repo

import android.database.SQLException
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.visibility.ActivityFilter
import io.github.trevarj.motd.data.visibility.GlobalFeedKey
import io.github.trevarj.motd.data.visibility.GlobalFeedSeek
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.globalFeedPagingQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Every table the global feed projection reads: any of them can change which rows a page contains. */
internal val GLOBAL_FEED_TABLES =
    arrayOf("messages", "buffers", "networks", "event_redirects", "network_identity")

/**
 * Keyset pager over the cross-buffer stream.
 *
 * Room's generated paging source is positional: it runs `SELECT COUNT(*)` over this five-way join
 * on every initial load — once per arriving message, since any of [GLOBAL_FEED_TABLES] invalidates it
 * — and pages with LIMIT/OFFSET, so a deep scroll recomputes every preceding row. This seeks on
 * `(serverTime, id)` instead: no COUNT, no OFFSET, one indexed page per load in either direction.
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
        object : InvalidationTracker.Observer(GLOBAL_FEED_TABLES) {
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
            ?.let { GlobalFeedKey(it.message.serverTime, it.message.id) }
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
                        if (activityFilter != null) return@withContext refreshActivity(params)
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

private fun SearchHit.key(): GlobalFeedKey = GlobalFeedKey(message.serverTime, message.id)
