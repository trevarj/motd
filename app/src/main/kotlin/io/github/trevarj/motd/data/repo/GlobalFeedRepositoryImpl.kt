package io.github.trevarj.motd.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.visibility.ActivityFilter
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

// Derived state: one cross-buffer keyset query, kept live by Room invalidation.
class GlobalFeedRepositoryImpl
    @Inject
    constructor(
        private val db: MotdDatabase,
    ) : GlobalFeedRepository {
        override fun globalFeed(
            spec: MessageVisibilitySpec,
            activityFilter: ActivityFilter?,
            isAtNewest: () -> Boolean,
        ): Flow<PagingData<SearchHit>> =
            globalFeedPager {
                GlobalFeedPagingSource(db, spec, activityFilter, isAtNewest)
            }
    }

internal fun globalFeedPager(factory: () -> GlobalFeedPagingSource): Flow<PagingData<SearchHit>> =
    flow {
        var latest: GlobalFeedPagingSource? = null
        try {
            emitAll(
                Pager(
                    config = GLOBAL_FEED_PAGING_CONFIG,
                    pagingSourceFactory = { factory().also { latest = it } },
                ).flow,
            )
        } finally {
            // Paging invalidates superseded refresh generations, but not the source left by a canceled collector.
            latest?.let { source ->
                withContext(NonCancellable + Dispatchers.IO) { source.invalidate() }
            }
        }
    }

// Placeholders off: a merged stream has no stable positional identity, and a keyset source has no
// row count to place them against.
internal val GLOBAL_FEED_PAGING_CONFIG =
    PagingConfig(
        pageSize = 50,
        prefetchDistance = 25,
        enablePlaceholders = false,
    )
