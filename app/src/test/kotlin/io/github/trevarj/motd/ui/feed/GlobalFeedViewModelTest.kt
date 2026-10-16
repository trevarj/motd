package io.github.trevarj.motd.ui.feed

import androidx.paging.PagingData
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.FoolPreferences
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GlobalFeedViewModelTest {
    @Test
    fun changingTheSpec_rebuildsThePager_whileAnEqualSpecKeepsIt() =
        runTest {
            val specs = MutableStateFlow(MessageVisibilitySpec())
            var generations = 0
            val pages =
                globalFeedPages(
                    source = {
                        generations++
                        flowOf(PagingData.empty<SearchHit>())
                    },
                    specs = specs,
                )
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    pages.collect()
                }
            runCurrent()
            assertEquals(1, generations)

            // An equal value republished is not a spec change.
            specs.value = MessageVisibilitySpec()
            runCurrent()
            assertEquals(1, generations)

            specs.value = MessageVisibilitySpec(fools = setOf("motdadb2"), foolsMode = FoolsMode.HIDE)
            runCurrent()
            assertEquals(2, generations)

            job.cancel()
        }

    /**
     * The global feed query reads `fools` and nothing else, so toggling the presence or fools-mode
     * prefs must not tear the Pager down and drop the reader's scroll position.
     */
    @Test
    fun aSpecChangeTheQueryDoesNotReadKeepsThePagerAndItsScrollPosition() =
        runTest {
            val specs = MutableStateFlow(MessageVisibilitySpec(fools = setOf("motdadb2")))
            var generations = 0
            val pages =
                globalFeedPages(
                    source = {
                        generations++
                        flowOf(PagingData.empty<SearchHit>())
                    },
                    specs = specs,
                )
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    pages.collect()
                }
            runCurrent()
            assertEquals(1, generations)

            specs.value =
                specs.value.copy(
                    presenceMode = PresenceMode.HIDDEN,
                    foolsMode = FoolsMode.HIDE,
                    revealHiddenFools = true,
                )
            runCurrent()
            assertEquals(1, generations)

            // The one field the query does read still rebuilds it.
            specs.value = specs.value.copy(fools = setOf("someone-else"))
            runCurrent()
            assertEquals(2, generations)

            job.cancel()
        }

    @Test
    fun mentionsProjectionRetainsPermissionsAndVisibilityButAllKeepsNarrowMembershipProjection() =
        runTest {
            val specs = MutableStateFlow(MessageVisibilitySpec(fools = setOf("b")))
            val mentionsGenerations = mutableListOf<MessageVisibilitySpec>()
            var allGenerations = 0
            val mentions =
                globalFeedPages(
                    source = { spec ->
                        mentionsGenerations += spec
                        flowOf(PagingData.empty<SearchHit>())
                    },
                    specs = specs,
                    mentionsOnly = true,
                )
            val all =
                globalFeedPages(
                    source = {
                        allGenerations++
                        flowOf(PagingData.empty<SearchHit>())
                    },
                    specs = specs,
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { mentions.collect() }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { all.collect() }
            runCurrent()
            assertEquals(1, mentionsGenerations.size)
            specs.value = specs.value.copy(foolPreferences = mapOf("b" to FoolPreferences(FoolsMode.COLLAPSE, notifyMentions = true)))
            runCurrent()
            assertEquals(2, mentionsGenerations.size)
            assertEquals(specs.value.foolPreferences, mentionsGenerations.last().foolPreferences)
            specs.value = specs.value.copy(foolPreferences = mapOf("b" to FoolPreferences(FoolsMode.COLLAPSE)))
            runCurrent()
            assertEquals(3, mentionsGenerations.size)
            specs.value = specs.value.copy(foolsMode = FoolsMode.HIDE)
            runCurrent()
            assertEquals(4, mentionsGenerations.size)
            specs.value = specs.value.copy(presenceMode = PresenceMode.HIDDEN, showRedactedMessages = false, revealHiddenFools = true)
            runCurrent()
            assertEquals(4, mentionsGenerations.size)
            assertEquals(1, allGenerations)
        }

    @Test
    fun rowsNameTheirNetworkOnlyOnceMoreThanOneExists() =
        runTest {
            val networks =
                flowOf(
                    emptyList(),
                    listOf(network("libera")),
                    listOf(network("libera"), network("oftc")),
                )

            assertEquals(listOf(false, false, true), showsNetworkName(networks).toList())
        }
}
