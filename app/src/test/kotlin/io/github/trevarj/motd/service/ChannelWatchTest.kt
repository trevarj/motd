package io.github.trevarj.motd.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelWatchTest {
    @Test
    fun `start expires once`() =
        runTest {
            val expired = mutableListOf<Long>()
            val watch =
                ChannelWatchImpl(
                    scope = backgroundScope,
                    clock = { testScheduler.currentTime },
                    onExpired = { expired += it },
                )
            watch.start(7, 1_000)
            assertTrue(watch.isActive(7))
            advanceTimeBy(999)
            runCurrent()
            assertTrue(expired.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertFalse(watch.isActive(7))
            assertEquals(listOf(7L), expired)
        }

    @Test
    fun `stop does not expire`() =
        runTest {
            val expired = mutableListOf<Long>()
            val watch =
                ChannelWatchImpl(
                    scope = backgroundScope,
                    clock = { testScheduler.currentTime },
                    onExpired = { expired += it },
                )
            watch.start(7, 1_000)
            watch.stop()
            assertFalse(watch.isActive(7))
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(expired.isEmpty())
        }

    @Test
    fun `start replaces the previous watch without expiring it`() =
        runTest {
            val expired = mutableListOf<Long>()
            val watch =
                ChannelWatchImpl(
                    scope = backgroundScope,
                    clock = { testScheduler.currentTime },
                    onExpired = { expired += it },
                )
            watch.start(7, 1_000)
            watch.start(8, 500)
            assertFalse(watch.isActive(7))
            assertTrue(watch.isActive(8))
            advanceTimeBy(500)
            runCurrent()
            assertEquals(listOf(8L), expired)
            assertFalse(watch.isActive(8))
        }

    @Test
    fun `old timer does not persist-clear a replacement`() =
        runTest {
            val persisted = mutableListOf<ChannelWatchState?>()
            val watch =
                ChannelWatchImpl(
                    scope = backgroundScope,
                    clock = { testScheduler.currentTime },
                    onExpired = {},
                    save = { persisted += it },
                )
            watch.start(7, 1_000)
            watch.start(8, 5_000)
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(watch.isActive(8))
            assertEquals(8L, persisted.last()?.bufferId)
        }
}
