package io.github.trevarj.motd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MainActivityRecentsPreviewsTest {
    @Test
    fun `recents previews fail closed then follow the recovered setting`() =
        runTest {
            val setting = MutableStateFlow(false)
            var reads = 0
            val preferences =
                flow {
                    if (++reads <= 2) throw IOException("Settings temporarily unavailable")
                    emitAll(setting)
                }
            val observed = mutableListOf<Boolean>()
            backgroundScope.launch { preferences.retryRecentsPreferenceReads().toList(observed) }

            runCurrent()
            assertEquals(1, reads)
            assertEquals(listOf(true), observed)

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, reads)
            assertEquals(listOf(true), observed)

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(3, reads)
            assertEquals(listOf(true, false), observed)

            setting.value = true
            runCurrent()
            setting.value = false
            runCurrent()
            assertEquals(listOf(true, false, true, false), observed)
        }
}
