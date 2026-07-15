package io.github.trevarj.motd.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkEventSequencerTest {
    @Test
    fun sameNetwork_isSerialized_whileDifferentNetworksProceed() = runTest {
        val sequencer = NetworkEventSequencer()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val first = async {
            sequencer.withNetwork(1) {
                order += "first-start"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-end"
            }
        }
        firstEntered.await()
        val second = async {
            sequencer.withNetwork(1) { order += "second" }
        }
        val otherNetwork = async {
            sequencer.withNetwork(2) { "other" }
        }

        assertEquals("other", withTimeout(1_000) { otherNetwork.await() })
        assertEquals(listOf("first-start"), order)
        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first-start", "first-end", "second"), order)
    }

    @Test
    fun evictionAndClear_releaseSequencerEntries() = runTest {
        val sequencer = NetworkEventSequencer()
        sequencer.withNetwork(1) { Unit }
        sequencer.withNetwork(2) { Unit }
        assertEquals(2, sequencer.size())

        sequencer.evict(1)
        assertEquals(1, sequencer.size())
        sequencer.clear()
        assertEquals(0, sequencer.size())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun eviction_waitsForExistingUsers_beforeAllowingANewEntry() = runTest {
        val sequencer = NetworkEventSequencer()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val active = async {
            sequencer.withNetwork(1) {
                order += "active"
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val eviction = async { sequencer.evict(1) }
        runCurrent()
        val afterEviction = async {
            sequencer.withNetwork(1) { order += "new" }
        }
        runCurrent()
        assertEquals(listOf("active"), order)

        release.complete(Unit)
        active.await()
        eviction.await()
        afterEviction.await()
        assertEquals(listOf("active", "new"), order)
    }
}
