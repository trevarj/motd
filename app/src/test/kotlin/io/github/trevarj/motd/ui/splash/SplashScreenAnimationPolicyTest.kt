package io.github.trevarj.motd.ui.splash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashScreenAnimationPolicyTest {
    @Test
    fun firstCreationWithEnabledMotionPlaysTheCut() {
        assertTrue(shouldShowStartupSplash(true, true))
    }

    @Test
    fun reducedMotionAndRecreationDoNotReplay() {
        assertFalse(shouldShowStartupSplash(true, false))
        assertFalse(shouldShowStartupSplash(false, true))
        assertFalse(shouldShowStartupSplash(false, false))
    }
}
