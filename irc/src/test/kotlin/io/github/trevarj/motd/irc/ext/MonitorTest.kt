package io.github.trevarj.motd.irc.ext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorTest {
    @Test fun `support distinguishes absent unlimited bounded and malformed`() {
        assertEquals(MonitorSupport.Unsupported, monitorSupport(emptyMap()))
        assertEquals(MonitorSupport.Unlimited, monitorSupport(mapOf("MONITOR" to "")))
        assertEquals(MonitorSupport.Limited(100), monitorSupport(mapOf("MONITOR" to "100")))
        assertEquals(MonitorSupport.Malformed, monitorSupport(mapOf("MONITOR" to "many")))
        assertEquals(MonitorSupport.Malformed, monitorSupport(mapOf("MONITOR" to "0")))
    }

    @Test fun `add and remove commands preserve order and fit byte budget`() {
        val targets = (1..80).map { "nick$it" }
        val added = MonitorCommands.add(targets, maxBytes = 80)
        assertTrue(added.size > 1)
        assertTrue(added.all { it.serialize().toByteArray(Charsets.UTF_8).size <= 80 })
        assertEquals(targets, added.flatMap { it.params[1].split(',') })
        assertEquals("MONITOR C", MonitorCommands.clear().serialize())
        assertEquals("MONITOR S", MonitorCommands.status().serialize())
        assertEquals("-", MonitorCommands.remove(listOf("nick")).single().params[0])
    }
}
