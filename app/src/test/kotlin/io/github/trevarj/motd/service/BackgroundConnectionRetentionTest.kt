package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundConnectionRetentionTest {

    @Test
    fun repeatedBackgroundSignalsDoNotExtendDeadline() = runTest {
        var elapsed = false
        val retention = BackgroundConnectionRetention(
            scope = this,
            graceMs = 300_000L,
            nowMs = { testScheduler.currentTime },
        )

        retention.onBackgrounded { elapsed = true }
        runCurrent()
        advanceTimeBy(240_000L)
        retention.onBackgrounded { elapsed = true }
        advanceTimeBy(59_999L)
        runCurrent()
        assertFalse(elapsed)
        assertTrue(retention.isRetaining)

        advanceTimeBy(1L)
        runCurrent()
        assertTrue(elapsed)
        assertTrue(retention.graceElapsed)
        assertFalse(retention.isRetaining)
    }

    @Test
    fun foregroundCancellationPreventsObsoleteHandoffAndResetsDeadline() = runTest {
        var elapsedCount = 0
        val retention = BackgroundConnectionRetention(
            scope = this,
            graceMs = 300_000L,
            nowMs = { testScheduler.currentTime },
        )

        retention.onBackgrounded { elapsedCount++ }
        runCurrent()
        advanceTimeBy(120_000L)
        retention.cancel()
        advanceTimeBy(300_000L)
        runCurrent()
        assertTrue(elapsedCount == 0)
        assertFalse(retention.graceElapsed)

        retention.onBackgrounded { elapsedCount++ }
        advanceTimeBy(299_999L)
        runCurrent()
        assertTrue(elapsedCount == 0)
        advanceTimeBy(1L)
        runCurrent()
        assertTrue(elapsedCount == 1)
    }

    @Test
    fun concurrentBackgroundSignalsCreateOneExpiryCallback() = runTest {
        val elapsedCount = AtomicInteger()
        val retention = BackgroundConnectionRetention(
            scope = this,
            graceMs = 300_000L,
            nowMs = { testScheduler.currentTime },
        )

        coroutineScope {
            List(64) {
                async(Dispatchers.Default) {
                    retention.onBackgrounded { elapsedCount.incrementAndGet() }
                }
            }.awaitAll()
        }
        // The contenders run on real worker threads while the expiry uses virtual time. Drain the
        // test scheduler completely so the deadline callback and its finally cleanup both finish
        // before asserting; a boundary advance can otherwise race the newly enqueued lazy job.
        advanceUntilIdle()

        assertEquals(1, elapsedCount.get())
        assertFalse(retention.isRetaining)
    }

    @Test
    fun embeddedRealityDetectionUsesPhysicalRootForBouncerChildren() {
        val root = network(id = 1, role = NetworkRole.BOUNCER_ROOT, obfsMode = ObfsMode.EMBEDDED_REALITY)
        val child = network(id = 2, role = NetworkRole.BOUNCER_CHILD, parentId = root.id)
        val direct = network(id = 3, role = NetworkRole.DIRECT)
        val all = listOf(root, child, direct)

        assertTrue(wantedNetworkUsesEmbeddedReality(root.id, all))
        assertTrue(wantedNetworkUsesEmbeddedReality(child.id, all))
        assertFalse(wantedNetworkUsesEmbeddedReality(direct.id, all))
        assertFalse(wantedNetworkUsesEmbeddedReality(999, all))
    }

    private fun network(
        id: Long,
        role: NetworkRole,
        parentId: Long? = null,
        obfsMode: ObfsMode? = null,
    ) = NetworkEntity(
        id = id,
        name = "network-$id",
        role = role,
        parentId = parentId,
        host = "irc.example",
        port = 6697,
        nick = "motd",
        username = "motd",
        realname = "MOTD",
        obfsMode = obfsMode,
    )
}
