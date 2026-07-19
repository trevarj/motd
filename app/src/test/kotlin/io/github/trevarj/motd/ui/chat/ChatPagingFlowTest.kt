package io.github.trevarj.motd.ui.chat

import androidx.paging.PagingData
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPagingFlowTest {
    @Test
    fun changingFools_recreatesPagingGeneration_insteadOfRecollectingIt() = runTest {
        val specs = MutableStateFlow(MessageVisibilitySpec())
        var sourceGenerations = 0
        val flow = repositoryMessagePages(
            source = {
                sourceGenerations++
                flowOf(PagingData.empty<MessageEntity>())
            },
            specs = specs,
        )
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect()
        }
        runCurrent()
        assertEquals(1, sourceGenerations)

        specs.value = MessageVisibilitySpec(
            fools = setOf("motdadb2"),
            foolsMode = FoolsMode.COLLAPSE,
        )
        runCurrent()

        assertEquals(2, sourceGenerations)
        job.cancel()
    }

    @Test
    fun changingVisibility_cancelsPreviousPagerGeneration() = runTest {
        val specs = MutableStateFlow(MessageVisibilitySpec())
        var cancelledGenerations = 0
        val pages = repositoryMessagePages(
            source = {
                flow {
                    emit(PagingData.empty<MessageEntity>())
                    try {
                        awaitCancellation()
                    } finally {
                        cancelledGenerations++
                    }
                }
            },
            specs = specs,
        )
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pages.collect() }
        runCurrent()

        specs.value = MessageVisibilitySpec(showJoinPartQuit = false)
        runCurrent()

        assertEquals(1, cancelledGenerations)
        job.cancel()
    }
}
