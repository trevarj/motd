package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.service.PresenceState
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceBadgeVisualTest {
    @Test fun online_isFilled() {
        assertEquals(PresenceBadgeVisual.FILLED, presenceBadgeVisual(PresenceState.ONLINE))
    }

    @Test fun offline_isHollow() {
        assertEquals(PresenceBadgeVisual.HOLLOW, presenceBadgeVisual(PresenceState.OFFLINE))
    }

    @Test fun unknown_usesQuestionMark() {
        assertEquals(PresenceBadgeVisual.UNKNOWN, presenceBadgeVisual(PresenceState.UNKNOWN))
    }
}
