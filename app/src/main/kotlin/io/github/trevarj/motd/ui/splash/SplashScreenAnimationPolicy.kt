package io.github.trevarj.motd.ui.splash

import io.github.trevarj.motd.data.prefs.SplashScreenAnimation

/** Keeps launch animation decisions independent of Android lifecycle timing. */
internal fun shouldShowStartupSplash(
    isFirstActivityCreation: Boolean,
    animation: SplashScreenAnimation,
    animatorsEnabled: Boolean,
): Boolean = isFirstActivityCreation && animation != SplashScreenAnimation.STATIC && animatorsEnabled
