package io.github.trevarj.motd.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionGenerationGateTest {
    @Test
    fun newerAttemptRejectsLateCallbacksFromSupersededAttempt() {
        val gate = ConnectionGenerationGate()
        val first = gate.begin(7)
        val second = gate.begin(7)

        assertFalse(gate.isCurrent(7, first))
        assertTrue(gate.isCurrent(7, second))
    }

    @Test
    fun disconnectAndGlobalStopInvalidateCurrentAttempts() {
        val gate = ConnectionGenerationGate()
        val first = gate.begin(7)
        val second = gate.begin(8)

        gate.invalidate(7)
        assertFalse(gate.isCurrent(7, first))
        assertTrue(gate.isCurrent(8, second))

        gate.invalidateAll()
        assertFalse(gate.isCurrent(8, second))
    }
}
