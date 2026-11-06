package io.github.trevarj.motd.ui.splash

/** Warm entry and reduced motion never replay the launch treatment. */
internal fun shouldShowStartupSplash(
    isFirstActivityCreation: Boolean,
    animatorsEnabled: Boolean,
): Boolean = isFirstActivityCreation && animatorsEnabled
