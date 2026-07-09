package io.github.trevarj.motd.ui.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * Reactive UNIFIED_PUSH availability for the Settings Delivery section.
 *
 * Two independent facts drive the UI:
 *  - [bouncerWebpush] — a currently-connected client advertises `soju.im/webpush` (the soju root
 *    connection). This is the hard requirement: without it the bouncer cannot deliver Web Push, so
 *    the UNIFIED_PUSH radio is disabled with an explainer.
 *  - [distributorInstalled] — an installed UnifiedPush distributor exists (e.g. ntfy). This is a
 *    soft requirement: registration self-heals once a distributor appears (PushInstanceCoordinator
 *    auto-selects one and registers), so the user may still SELECT push while missing a distributor,
 *    with actionable guidance to install one.
 *
 * [selectable] is therefore gated only on [bouncerWebpush]; the distributor gap surfaces as guidance.
 */
data class PushAvailability(
    val bouncerWebpush: Boolean = false,
    val distributorInstalled: Boolean = false,
) {
    /** Push may be selected once the bouncer advertises webpush, even if a distributor is missing. */
    val selectable: Boolean get() = bouncerWebpush

    /** True when push is selectable but no distributor is installed yet (show install guidance). */
    val needsDistributor: Boolean get() = bouncerWebpush && !distributorInstalled
}

/**
 * Emits [PushAvailability] and recomputes it whenever connection or distributor state changes, so the
 * Settings toggle enables the moment the soju bouncer reaches Ready with the webpush cap.
 *
 * The default is conservative (push unavailable) so a stub renders the disabled/explainer state.
 * The real implementation lives in `di/RealPushAvailabilityProvider` and is bound via `di/AppModule`.
 */
open class PushAvailabilityProvider @Inject constructor() {
    open fun availability(): Flow<PushAvailability> = flowOf(PushAvailability())
}
