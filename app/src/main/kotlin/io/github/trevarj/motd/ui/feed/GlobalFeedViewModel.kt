package io.github.trevarj.motd.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.GlobalFeedRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Recreate the Paging generation only when the part of the spec the query actually reads changes.
 *
 * `globalFeedPagingQuery` consumes `fools` and nothing else — presence mode, fools mode, and the
 * chat-local reveal never reach it — so projecting first keeps toggling those prefs from tearing
 * down the Pager and resetting scroll for an identical query. An equal projection keeps the
 * generation, since transforming the same PagingData would re-emit its single-collector page flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun globalFeedPages(
    source: (MessageVisibilitySpec) -> Flow<PagingData<SearchHit>>,
    specs: Flow<MessageVisibilitySpec>,
): Flow<PagingData<SearchHit>> =
    specs
        .map { MessageVisibilitySpec(fools = it.fools) }
        .distinctUntilChanged()
        .flatMapLatest(source)

/** Rows name their network only when more than one exists — identically named channels otherwise collide. */
internal fun showsNetworkName(networks: Flow<List<NetworkEntity>>): Flow<Boolean> = networks.map { it.size > 1 }

/** Exposes the cross-buffer stream as a live [PagingData]. */
@HiltViewModel
class GlobalFeedViewModel
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
        networkRepository: NetworkRepository,
        globalFeedRepository: GlobalFeedRepository,
    ) : ViewModel() {
        /** Only a fools change rebuilds the Pager; every other change invalidates through Room. */
        val items: Flow<PagingData<SearchHit>> =
            globalFeedPages(
                source = globalFeedRepository::globalFeed,
                specs = settingsRepository.settings.map(MessageVisibilitySpec::from),
            ).cachedIn(viewModelScope)

        val showNetwork: StateFlow<Boolean> =
            showsNetworkName(networkRepository.observeNetworks())
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    }

/** The same live cross-buffer paging path, restricted to stored mentions. */
@HiltViewModel
class MentionsViewModel
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
        networkRepository: NetworkRepository,
        globalFeedRepository: GlobalFeedRepository,
    ) : ViewModel() {
        // Paging reads this from Room's invalidation thread, while Compose reports it on main.
        // Start at newest so the initial page and an initially empty feed pick up new mentions.
        private val atNewest = AtomicBoolean(true)

        val items: Flow<PagingData<SearchHit>> =
            globalFeedPages(
                source = { spec -> globalFeedRepository.mentionsFeed(spec, atNewest::get) },
                specs = settingsRepository.settings.map(MessageVisibilitySpec::from),
            ).cachedIn(viewModelScope)

        val showNetwork: StateFlow<Boolean> =
            showsNetworkName(networkRepository.observeNetworks())
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        /** Called only after a non-empty mentions list has a real viewport. */
        fun reportViewportAtNewest(value: Boolean) {
            atNewest.set(value)
        }
    }
