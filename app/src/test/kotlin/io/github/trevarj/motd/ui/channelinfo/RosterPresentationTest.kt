package io.github.trevarj.motd.ui.channelinfo

import io.github.trevarj.motd.service.RosterLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterPresentationTest {
    @Test fun `only loaded roster exposes an authoritative count`() {
        RosterLoadState.entries.filter { it != RosterLoadState.LOADED }.forEach { state ->
            assertNull(rosterPresentation(0, state).memberCount)
            assertNull(rosterPresentation(42, state).memberCount)
        }
        assertEquals(0, rosterPresentation(0, RosterLoadState.LOADED).memberCount)
        assertEquals(42, rosterPresentation(42, RosterLoadState.LOADED).memberCount)
    }

    @Test fun `cached rows are explicitly stale until loaded`() {
        assertTrue(rosterPresentation(42, RosterLoadState.LOADING).hasStaleMembers)
        assertTrue(rosterPresentation(42, RosterLoadState.FAILED).hasStaleMembers)
        assertFalse(rosterPresentation(42, RosterLoadState.LOADED).hasStaleMembers)
        assertFalse(rosterPresentation(0, RosterLoadState.NOT_LOADED).hasStaleMembers)
    }
}
