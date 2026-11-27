package io.github.trevarj.motd.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.GlobalFeedRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.visibility.ActivityFilter
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Recreate the Paging generation only when the part of the spec the query actually reads changes.
 *
 * Global Feed consumes only fool membership. Activity also consumes visibility and category
 * permissions; unrelated settings must not reset either feed's paging generation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun globalFeedPages(
    source: (MessageVisibilitySpec) -> Flow<PagingData<SearchHit>>,
    specs: Flow<MessageVisibilitySpec>,
    activity: Boolean = false,
): Flow<PagingData<SearchHit>> =
    specs
        .map {
            MessageVisibilitySpec(
                fools = it.fools,
                foolsMode = if (activity) it.foolsMode else FoolsMode.DIMMED,
                foolPreferences = if (activity) it.foolPreferences else emptyMap(),
            )
        }.distinctUntilChanged()
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
                source = { spec -> globalFeedRepository.globalFeed(spec) },
                specs = settingsRepository.settings.map(MessageVisibilitySpec::from),
            ).cachedIn(viewModelScope)

        val showNetwork: StateFlow<Boolean> =
            showsNetworkName(networkRepository.observeNetworks())
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    }

internal class ActivityPage(
    val filter: ActivityFilter,
    val items: Flow<PagingData<SearchHit>>,
    val reportViewportAtNewest: (Boolean) -> Unit,
)

/** Stored cross-buffer attention events, with one pager for the selected filter. */
@HiltViewModel
class ActivityViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        networkRepository: NetworkRepository,
        private val globalFeedRepository: GlobalFeedRepository,
    ) : ViewModel() {
        private var pageJob: Job? = null
        private val selectedPage = MutableStateFlow(newPage(ActivityFilter.ALL))
        internal val page = selectedPage.asStateFlow()

        fun selectFilter(value: ActivityFilter) {
            if (selectedPage.value.filter == value) return
            selectedPage.value = newPage(value)
        }

        val visibilitySpec: StateFlow<MessageVisibilitySpec> =
            settingsRepository.settings
                .map(MessageVisibilitySpec::from)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessageVisibilitySpec())

        private fun newPage(filter: ActivityFilter): ActivityPage {
            // Only the selected filter stays cached, and its presenter never receives an old filter's pages.
            pageJob?.cancel()
            val job = SupervisorJob(viewModelScope.coroutineContext.job)
            pageJob = job
            val scope = CoroutineScope(viewModelScope.coroutineContext + job)
            // Room may invalidate off-main; each filter starts with a fresh newest-edge viewport.
            val atNewest = AtomicBoolean(true)
            val items =
                globalFeedPages(
                    source = { spec -> globalFeedRepository.globalFeed(spec, filter, atNewest::get) },
                    specs = settingsRepository.settings.map(MessageVisibilitySpec::from),
                    activity = true,
                ).cachedIn(scope)
            return ActivityPage(filter, items, atNewest::set)
        }

        val showNetwork: StateFlow<Boolean> =
            showsNetworkName(networkRepository.observeNetworks())
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    }
