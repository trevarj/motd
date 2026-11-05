package io.github.trevarj.motd.ui.splash

import io.github.trevarj.motd.data.prefs.SplashScreenAnimation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashScreenAnimationPolicyTest {
    @Test
    fun onlyFirstCreationWithEnabledMotionPlaysNonStaticSelection() {
        assertTrue(shouldShowStartupSplash(true, SplashScreenAnimation.EDGE_TRACE, true))
        assertTrue(shouldShowStartupSplash(true, SplashScreenAnimation.SOFT_WASH, true))
        assertTrue(shouldShowStartupSplash(true, SplashScreenAnimation.TRAVELING_PULSE, true))
    }

    @Test
    fun staticReducedMotionAndRecreationDoNotReplay() {
        assertFalse(shouldShowStartupSplash(true, SplashScreenAnimation.STATIC, true))
        assertFalse(shouldShowStartupSplash(true, SplashScreenAnimation.EDGE_TRACE, false))
        assertFalse(shouldShowStartupSplash(false, SplashScreenAnimation.EDGE_TRACE, true))
    }
}
