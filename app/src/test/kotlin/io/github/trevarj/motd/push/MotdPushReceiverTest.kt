package io.github.trevarj.motd.push

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MotdPushReceiverTest {

    @Test
    fun known_numeric_instance_resolves_to_network_id() = runTest {
        val decision = classifyInstance("42") { it == 42L }
        assertEquals(InstanceDecision.Known(42L), decision)
    }

    @Test
    fun non_numeric_instance_is_stale() = runTest {
        val decision = classifyInstance("garbage") { true }
        assertEquals(InstanceDecision.Stale, decision)
    }

    @Test
    fun numeric_instance_without_row_is_stale() = runTest {
        val decision = classifyInstance("7") { false }
        assertEquals(InstanceDecision.Stale, decision)
    }
}
