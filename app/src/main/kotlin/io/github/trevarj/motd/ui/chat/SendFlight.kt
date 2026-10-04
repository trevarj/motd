package io.github.trevarj.motd.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.components.MessageBubble
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.components.rememberMessageTimeFormatter
import kotlin.math.max
import kotlin.math.min

/*
 * The real outgoing bubble rises from the composer into its slot. Its body starts at the pinned
 * draft-text origin while its own surface and metadata grow in, then the same renderer lands.
 * Reply, compact and two-line layouts keep the ordinary bubble flight.
 */

/**
 * Where a send flight starts and where it ends, in the coordinates of the chat surface that hosts
 * the overlay.
 *
 * Both rects are reported from `onGloballyPositioned`, which runs on every layout pass. They live
 * in snapshot state that only [SendFlightOverlay] reads, so a composer growing a line or a
 * timeline settling never recomposes the timeline itself.
 */
@Stable
internal class SendFlightAnchors {
    /**
     * Every rect is reported in window coordinates, because the composer and a timeline row have
     * no common local space. [hostOrigin] is the overlay's own window position, which turns them
     * back into the offsets the ghost is placed with.
     */
    var hostOrigin by mutableStateOf(Offset.Zero)
    var composerField by mutableStateOf<Rect?>(null)

    /**
     * The composer field's inner text origin (window coords): where the first glyph of the draft
     * is drawn. The morph presentation aligns its own text here on the tap frame so the typed
     * line visually never moves when the field clears.
     */
    var composerTextOrigin by mutableStateOf<Offset?>(null)

    /** The landing row, keyed by event id so a report can be traced back to the row that made it. */
    var landingRow by mutableStateOf<Pair<Long, Rect>?>(null)

    /**
     * The ghost's measured height, written from the overlay's layout pass. Sizes the runway the
     * timeline opens under the flight; 0 until the overlay has laid out (the same frame's draw
     * already sees the real value, so at worst the runway's first frame targets only the gap).
     */
    var ghostHeight by mutableStateOf(0f)

    /**
     * The composer field's height on the tap frame, pinned alongside the motion reset. A
     * multi-line draft collapses the field to one line when it clears, which grows the timeline
     * pane and drops the list foot by the difference on that same frame; [composerShrink] feeds
     * that drop into the runway and hover so the vacated-space math survives the collapse.
     */
    var launchFieldHeight by mutableStateOf(0f)
    var launchField by mutableStateOf<Rect?>(null)
    var launchTextOrigin by mutableStateOf<Offset?>(null)
    private var pendingLaunchField: Rect? = null
    private var pendingLaunchTextOrigin: Offset? = null
    private var launchCaptured = false

    /** Stage submit geometry; a command that launches no flight must not move the active ghost. */
    fun captureLaunch() {
        pendingLaunchField = composerField
        pendingLaunchTextOrigin = composerTextOrigin
        launchCaptured = true
    }

    fun beginFlight() {
        // Promote only when a new flight starts; external launches use the latest geometry.
        launchField = if (launchCaptured) pendingLaunchField else composerField
        launchTextOrigin = if (launchCaptured) pendingLaunchTextOrigin else composerTextOrigin
        launchFieldHeight = launchField?.height ?: 0f
        launchCaptured = false
        pendingLaunchField = null
        pendingLaunchTextOrigin = null
        landingRow = null
        ghostHeight = 0f
    }

    /** How much the composer has shrunk since the tap frame (0 while it has not). */
    fun composerShrink(): Float {
        val current = composerField?.height ?: return 0f
        return max(0f, launchFieldHeight - current)
    }

    fun reportLandingRow(
        eventId: Long,
        bounds: Rect,
    ) {
        landingRow = eventId to bounds
    }

    fun local(bounds: Rect): Rect = bounds.translate(-hostOrigin.x, -hostOrigin.y)
}

/**
 * One tap's motion, shared by the bubble in flight and the gap it is flying into.
 *
 * A single progress value drives both, which is the whole point: the conversation opens up
 * underneath the arriving bubble instead of jumping open first and leaving the bubble to chase a
 * hole that is already there. Both sides read it from deferred lambdas, so the spring invalidates
 * one layer and one row's layout per frame and never recomposes the timeline.
 */
@Stable
internal class SendFlightMotion(
    val morphEnabled: Boolean = false,
) {
    /** The flight proper: the bubble's travel into its slot and the gap opening beneath it. */
    val progress = Animatable(0f)

    /**
     * The immediate pre-landing rise, started on the tap frame before the pending row exists.
     * It carries the ghost's birth over the input box -- the flight replica's materialization
     * fade ([sendFlightEntryFade]) and the hover rise both read it -- so a slow send stays
     * visibly in flight for however long persistence takes. See [sendFlightGhostTop] for how it
     * blends with [progress].
     */
    val lift = Animatable(0f)

    /** The launch-time transformation; row ownership waits for this as well as the flight. */
    val morph = Animatable(0f)
}

/**
 * The flight replica's materialization over the composer, from the lift fraction. The overlay
 * draws above the input bar, so without this the finished bubble would pop in fully opaque on
 * the tap frame; instead it fades in across the lift's first stretch, while still over the
 * input box, and then rides up whole while persistence prepares its landing row.
 */
internal fun sendFlightEntryFade(liftFraction: Float): Float {
    val t = (liftFraction / 0.35f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * How far up the timeline slides while a flight is airborne: the runway.
 *
 * The runway is what makes full-height in-flight feedback safe. The landing row -- and with it
 * the gap the flight aims for -- can only exist once persistence completes, so on the tap frame
 * there is nowhere vacated for the bubble to rise into. Sliding the whole list up by the
 * predicted landing-row height ([runwayHeight] = measured ghost height + predicted group gap),
 * on the same spring as the lift, opens that space in lockstep with the rising bubble.
 *
 * Once the row lands its gap starts absorbing the runway: the reveal grows inside the list
 * while the shift shrinks by the same amount, so the neighbour's edge moves continuously and
 * ownership of the vacated space transfers to the ordinary gap mechanism without a seam. A
 * mispredicted runway height self-corrects here too -- both terms are animated, so the error
 * drains through the springs instead of jumping.
 *
 * [footDrop] is how far the list foot fell when a multi-line composer collapsed on the tap
 * frame ([SendFlightAnchors.composerShrink]). It is absorbed into the shift IMMEDIATELY -- not
 * on the spring -- so the neighbour never visibly drops toward the collapsed bar; from there
 * the spring carries the remaining (runway - drop) of travel, and the reveal drains the whole
 * shift exactly as before. Without this term a long message's ghost outran the vacated space
 * by the collapse amount and rode over the neighbour on both presentations.
 *
 * The lift fraction is capped at 1 so the flight spring's deliberate bounce stays on the
 * bubble; an underdamped shift would nod the entire conversation.
 */
internal fun sendFlightListShift(
    runwayHeight: Float,
    liftFraction: Float,
    revealedGap: Float,
    footDrop: Float = 0f,
): Float = max(0f, footDrop + (runwayHeight - footDrop) * min(liftFraction, 1f) - revealedGap)

/**
 * The ghost's absolute top for one frame.
 *
 * While the landing row has not reported (and while the flight is still short of the hover
 * line), the lift holds the bubble in-flight feedback above the composer. The hover rise is the
 * ghost height LESS [footDrop]: a collapsing multi-line composer already moved the resting slot
 * down by the drop, so a full-height rise from the pinned (pre-collapse) field top would
 * overshoot the slot by exactly that amount and poke into the neighbour above. The runway
 * ([sendFlightListShift]) opens beneath the reduced rise on the same spring and always faster,
 * so the hover sits in vacated space by construction. min keeps whichever of hover and flight
 * is higher, so the flight takes over smoothly once it climbs past the hover line.
 *
 * The flight aims at the row's *resting* foot: the reported [landingBottom] is a visual
 * coordinate that rides the runway shift, so [listShift] is added back to keep the target
 * stationary while the shift drains. The flight term is floored at [landingTop] (the reported
 * rect is clipped to the opened gap, and its coordinates already include the shift): a safety
 * net that only bites when the spring's overshoot would poke the bubble past the vacated edge
 * into the neighbour, and then by at most the overshoot itself.
 */
internal fun sendFlightGhostTop(
    startTop: Float,
    ghostHeight: Float,
    listShift: Float,
    landingTop: Float?,
    landingBottom: Float?,
    flightFraction: Float,
    liftFraction: Float,
    footDrop: Float = 0f,
): Float {
    val hoverTop = startTop - max(0f, ghostHeight - footDrop) * liftFraction
    val restingFoot = landingBottom?.plus(listShift) ?: return hoverTop
    val flightTop = startTop + (restingFoot - ghostHeight - startTop) * flightFraction
    val flooredFlight = landingTop?.let { max(flightTop, it) } ?: flightTop
    return min(hoverTop, flooredFlight)
}

/**
 * The bubble that rises from the composer into the timeline after a send.
 *
 * The ghost does not imitate the row it becomes -- it *is* that row, rendered by the same
 * [MessageBubble] the timeline uses. That renderer dispatches on layout density, resolves the
 * grouped-corner silhouette from [showSender], and builds the same linkified body, quoted reply,
 * and status/time line, so the replica cannot drift from its landing row by construction. A
 * hand-copied bubble had already drifted on three of those axes.
 *
 * The body is drawn once, unscaled, inside the real bubble throughout the morph. Only the surface
 * grows; translating the whole bubble from its measured body origin avoids a second layout or
 * crossfade of text. At completion its pixels match the ordinary row.
 *
 * The ghost is invisible to the semantics tree ([clearAndSetSemantics], which clears the whole
 * subtree including the bubble's own click semantics). Its text duplicates a real row's, and a
 * second match would make every `onNodeWithText` assertion in chat ambiguous.
 */

@Composable
internal fun BoxScope.SendFlightOverlay(
    flight: OutgoingFlight?,
    anchors: SendFlightAnchors,
    motion: SendFlightMotion,
    listShift: () -> Float,
    selfNick: String,
    showSender: Boolean,
    networkId: Long?,
    knownNicks: Set<String>,
    identityRules: IrcIdentityRules,
) {
    // Read nothing while idle: an overlay that sampled the anchors unconditionally would recompose
    // on every composer layout pass for the whole life of the screen.
    if (flight == null) return
    val field = anchors.launchField ?: anchors.composerField ?: return
    val start = remember(flight.token) { field }
    // The row shows its Room timestamp; the ghost shows the clock for the moment it launched, built
    // with the timeline's own formatter so 12/24-hour and locale can never disagree. The launch
    // instant comes from the flight, which is also what row matching and grouping are decided by.
    val formatTime = rememberMessageTimeFormatter()
    val launchedAt = flight.launchedAtMs
    val time = remember(flight.token, formatTime) { formatTime(launchedAt) }
    val morph = motion.morphEnabled
    val morphProgress = remember(motion) { { motion.morph.value } }
    var textDelta by remember(flight.token) { mutableStateOf<Offset?>(null) }

    Box(
        modifier =
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                // Layout-phase write: sizes the runway the timeline opens under this flight.
                .onSizeChanged { anchors.ghostHeight = it.height.toFloat() }
                .graphicsLayer {
                    // Launch stays pinned in window space as the header moves this host; landing
                    // stays live while the keyboard and list settle.
                    val landing = anchors.landingRow?.second?.let(anchors::local)
                    translationY =
                        sendFlightGhostTop(
                            startTop = start.top - anchors.hostOrigin.y,
                            ghostHeight = size.height,
                            listShift = listShift(),
                            landingTop = landing?.top,
                            landingBottom = landing?.bottom,
                            flightFraction = motion.progress.value,
                            liftFraction = motion.lift.value,
                            footDrop = anchors.composerShrink(),
                        )
                }.clearAndSetSemantics {},
    ) {
        MessageBubble(
            sender = selfNick,
            text = flight.text,
            timeMs = launchedAt,
            isSelf = true,
            kind = MessageKind.PRIVMSG,
            showSender = showSender,
            networkId = networkId,
            formattedTime = time,
            pending = true,
            reply =
                flight.replyText?.let {
                    ReplyPreviewData(flight.replySender.orEmpty(), it, flight.replyIrcFormattedText)
                },
            knownNicks = knownNicks,
            identityRules = identityRules,
            modifier = if (morph) Modifier else Modifier.graphicsLayer { alpha = sendFlightEntryFade(motion.lift.value) },
            sendMorphProgress = if (morph) morphProgress else null,
            bubbleModifier =
                if (morph) {
                    Modifier.graphicsLayer {
                        val delta = textDelta
                        if (delta != null) {
                            val remaining = 1f - motion.morph.value.coerceIn(0f, 1f)
                            translationX = delta.x * remaining
                            translationY = delta.y * remaining
                        }
                    }
                } else {
                    Modifier
                },
            bodyModifier =
                if (morph) {
                    Modifier.onGloballyPositioned {
                        if (textDelta == null) {
                            anchors.launchTextOrigin?.let { origin ->
                                textDelta = origin - it.positionInWindow()
                            }
                        }
                    }
                } else {
                    Modifier
                },
        )
    }
}
