package io.github.trevarj.motd

import org.junit.Assert.assertFalse
import org.junit.Test

class MainActivityUiStateTest {
    @Test
    fun initialRemoteContentStateFailsClosedUntilPreferencesLoad() {
        val initial = MainActivityUiState()
        val previews = initial.contentPreviews

        assertFalse(initial.rootReady)
        assertFalse(previews.showImages)
        assertFalse(previews.showLinkPreviews)
        assertFalse(previews.autoLoadOnUnmetered)
        assertFalse(previews.autoLoadOnMetered)
    }
}
