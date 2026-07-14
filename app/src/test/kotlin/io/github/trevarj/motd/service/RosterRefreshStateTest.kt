package io.github.trevarj.motd.service

import org.junit.Assert.assertEquals
import org.junit.Test

class RosterRefreshStateTest {
    @Test fun `names completion waits for paired whox during explicit refresh`() {
        assertEquals(RosterLoadState.LOADING, rosterStateAfterNames(explicitRefreshInFlight = true))
        assertEquals(RosterLoadState.LOADED, rosterStateAfterNames(explicitRefreshInFlight = false))
    }

    @Test fun `paired whox timeout makes explicit refresh retryable`() {
        assertEquals(RosterLoadState.LOADED, rosterStateAfterExplicitRefresh(completed = true))
        assertEquals(RosterLoadState.FAILED, rosterStateAfterExplicitRefresh(completed = false))
    }
}
