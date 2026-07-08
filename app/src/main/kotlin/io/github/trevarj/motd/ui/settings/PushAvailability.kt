package io.github.trevarj.motd.ui.settings

import javax.inject.Inject

/**
 * Whether UNIFIED_PUSH delivery is selectable: requires an installed distributor AND a bouncer
 * with soju webpush. A simple injected boolean provider is sufficient for the UI layer.
 *
 * Constructor-injectable (no Hilt module needed) so it works today. The default is conservative:
 * push is reported unavailable, so the radio renders its disabled/explainer state.
 *
 * TODO(WP10): replace [isUnifiedPushAvailable] with a real check —
 * `UnifiedPush.getDistributors(context)` non-empty AND any connected client
 * `hasCap("soju.im/webpush")` — either by editing this class or rebinding via a Hilt module.
 */
open class PushAvailabilityProvider @Inject constructor() {
    open fun isUnifiedPushAvailable(): Boolean = false
}
