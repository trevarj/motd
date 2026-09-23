package io.github.trevarj.motd.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.visibility.GlobalFeedMode
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

// Derived state: one cross-buffer keyset query, kept live by Room invalidation.
class GlobalFeedRepositoryImpl
    @Inject
    constructor(
        private val db: MotdDatabase,
    ) : GlobalFeedRepository {
        override fun globalFeed(spec: MessageVisibilitySpec): Flow<PagingData<SearchHit>> =
            Pager(
                config = GLOBAL_FEED_PAGING_CONFIG,
                pagingSourceFactory = { GlobalFeedPagingSource(db, spec, GlobalFeedMode.ALL) },
            ).flow

        override fun mentionsFeed(spec: MessageVisibilitySpec): Flow<PagingData<SearchHit>> =
            Pager(
                config = GLOBAL_FEED_PAGING_CONFIG,
                pagingSourceFactory = { GlobalFeedPagingSource(db, spec, GlobalFeedMode.MENTIONS) },
            ).flow
    }

// Placeholders off: a merged stream has no stable positional identity, and a keyset source has no
// row count to place them against.
internal val GLOBAL_FEED_PAGING_CONFIG =
    PagingConfig(
        pageSize = 50,
        prefetchDistance = 25,
        enablePlaceholders = false,
    )
