package io.github.trevarj.motd.ui.chatlist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieClipSpec
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.LottieDynamicProperties
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.model.KeyPath
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.LocalLottieMotionEnabled
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.lottieFillColor
import io.github.trevarj.motd.ui.theme.lottieStrokeColor

/**
 * Aggregate history-sync line, pinned above the list (outside the LazyColumn) so it neither scrolls
 * away nor participates in item animations. Rendered only in the normal chat-list mode; archive and
 * invitation modes are scoped views where a global sync line would be noise.
 *
 * A11y: only the static label is a polite live region, so TalkBack announces "syncing history" once
 * per episode. The changing count lives in a sibling node and therefore never re-announces.
 */

/** The transition key: content within a kind updates in place, only kind changes animate. */
internal enum class SyncHeaderKind { HIDDEN, WAITING, SYNCING }

@Composable
fun ChatListSyncHeader(
    chrome: ChatListSyncChrome,
    modifier: Modifier = Modifier,
) {
    val kind =
        when (chrome) {
            ChatListSyncChrome.Hidden -> SyncHeaderKind.HIDDEN
            is ChatListSyncChrome.Waiting -> SyncHeaderKind.WAITING
            is ChatListSyncChrome.Syncing -> SyncHeaderKind.SYNCING
        }
    // The exiting SYNCING content keeps composing after chrome has moved on; hold the last Syncing
    // value so it renders real counts through the transition instead of collapsing to nothing.
    var lastSyncing by remember { mutableStateOf<ChatListSyncChrome.Syncing?>(null) }
    if (chrome is ChatListSyncChrome.Syncing) lastSyncing = chrome
    // The glyph's phase is owned here, above AnimatedContent, for the same reason the connection
    // banner hoists the arc: a count change produces a new content instance every settled buffer,
    // and an animatable living inside it would restart the wave on each one. The composition is
    // loaded once here and handed down, rather than resolved again inside the glyph.
    val glyphComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.sync_state))
    val glyphProgress =
        rememberSyncGlyphProgress(
            composition = glyphComposition,
            beat = syncGlyphBeat(kind, rememberLatestVisible(kind)),
        )
    AnimatedContent(
        targetState = kind,
        transitionSpec = {
            val contentTransform =
                when {
                    initialState == SyncHeaderKind.HIDDEN -> {
                        (
                            fadeIn(MotdMotion.fadeIn) +
                                expandVertically(animationSpec = MotdMotion.contentSize)
                        ) togetherWith
                            ExitTransition.None
                    }

                    targetState == SyncHeaderKind.HIDDEN -> {
                        EnterTransition.None togetherWith
                            (
                                fadeOut(MotdMotion.fadeOut) +
                                    shrinkVertically(animationSpec = MotdMotion.contentSize)
                            )
                    }

                    else -> {
                        fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut)
                    }
                }
            // expand/shrink already own the hidden <-> content size change. Disable
            // AnimatedContent's default SizeTransform so the same height is not animated twice.
            contentTransform.using(null)
        },
        modifier = modifier,
        label = "chatlist_sync_header",
    ) { current ->
        when (current) {
            SyncHeaderKind.HIDDEN -> {}

            SyncHeaderKind.WAITING -> {
                SyncHeaderSurface {
                    SyncHeaderLabel(
                        label = stringResource(R.string.chatlist_sync_header_waiting),
                        tag = "chatlist_sync_header_waiting",
                    )
                }
            }

            SyncHeaderKind.SYNCING -> {
                val syncing = (chrome as? ChatListSyncChrome.Syncing) ?: lastSyncing
                if (syncing != null) {
                    SyncHeaderSurface {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SyncStateGlyph(glyphComposition, glyphProgress)
                            SyncHeaderLabel(
                                label =
                                    stringResource(
                                        if (syncing.backfill) R.string.chatlist_sync_header_backfilling else R.string.chatlist_sync_header_syncing,
                                    ),
                                tag = "chatlist_sync_header_label",
                            )
                            Text(
                                text = stringResource(R.string.chatlist_sync_header_count, syncing.done, syncing.total),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("chatlist_sync_header_count"),
                            )
                        }
                        // Targets can be discovered mid-pass, so the fraction can move backwards; animating it
                        // keeps that from reading as a glitch.
                        val fraction by animateFloatAsState(
                            targetValue = if (syncing.total > 0) (syncing.done.toFloat() / syncing.total).coerceIn(0f, 1f) else 0f,
                            animationSpec = MotdMotion.fadeIn,
                            label = "chatlist_sync_header_progress",
                        )
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .testTag("chatlist_sync_header_progress"),
                        )
                    }
                }
            }
        }
    }
}

/** The two named beats packed into [R.raw.sync_state]. */
internal enum class SyncBeat { SYNCING, RESOLVE }

/**
 * Frame ranges of the one sync asset, at its 60fps timebase.
 *
 * The syncing beat is a seamless loop: frame [SyncingLast] draws the three dots back at their
 * frame-0 rest, so it is played exclusive of its end. The resolve beat fades the dots out and draws
 * the check on, once.
 */
internal object SyncStateFrames {
    const val Total = 39
    const val SyncingFirst = 0
    const val SyncingLast = 30
    const val ResolveFirst = 30
    const val ResolveLast = 39

    /** Composition progress of each beat's settled frame, for the animator-scale-off snap. */
    val syncingProgress: Float = SyncingFirst.toFloat() / Total
    val resolvedProgress: Float = (ResolveLast - 1).toFloat() / Total

    fun clipSpec(beat: SyncBeat?): LottieClipSpec =
        when (beat) {
            SyncBeat.RESOLVE -> LottieClipSpec.Frame(ResolveFirst, ResolveLast, maxInclusive = false)
            else -> LottieClipSpec.Frame(SyncingFirst, SyncingLast, maxInclusive = false)
        }

    fun settledProgress(beat: SyncBeat?): Float = if (beat == SyncBeat.RESOLVE) resolvedProgress else syncingProgress
}

/**
 * Which beat the header's glyph shows.
 *
 * [lastVisible] is the last kind the header actually rendered, which during an exit transition is
 * what AnimatedContent is still composing. Resolving requires that kind to have been [SYNCING]:
 * chrome also collapses to hidden straight out of [WAITING] (nothing was ever connected), and
 * drawing a check for a pass that never ran would be the banner's aborted-connect mistake again.
 *
 * The header's own show/hide logic is untouched. `SyncChromePresenter` holds visible chrome for
 * `SYNC_CHROME_MIN_VISIBLE_MS` after it appears, but a pass that outlives that hold collapses the
 * moment it settles, so the only window this beat can count on is the exit fade -- which is what
 * [SyncStateFrames] is sized against, and why nothing here holds the header open.
 */
internal fun syncGlyphBeat(
    kind: SyncHeaderKind,
    lastVisible: SyncHeaderKind?,
): SyncBeat? =
    when {
        kind == SyncHeaderKind.SYNCING -> SyncBeat.SYNCING
        kind == SyncHeaderKind.HIDDEN && lastVisible == SyncHeaderKind.SYNCING -> SyncBeat.RESOLVE
        else -> null
    }

/** Holds the most recent non-hidden [kind], mirroring what AnimatedContent keeps through its exit. */
@Composable
private fun rememberLatestVisible(kind: SyncHeaderKind): SyncHeaderKind? {
    val holder = remember { mutableStateOf<SyncHeaderKind?>(null) }
    if (kind != SyncHeaderKind.HIDDEN) holder.value = kind
    return holder.value
}

/**
 * Drives the sync asset for [beat] and returns a reader for its progress.
 *
 * A reader, not a `Float`: returning the value would make every rendered frame of a multi-minute
 * sync pass recompose the whole header. The lambda is read in the draw phase instead, so the wave
 * costs a redraw and nothing more. Only the beat, composition and motion gate re-key the
 * animation, so the settled-buffer counter ticking up never restarts it.
 */
@Composable
private fun rememberSyncGlyphProgress(
    composition: LottieComposition?,
    beat: SyncBeat?,
): () -> Float {
    val motionEnabled = LocalLottieMotionEnabled.current
    val animation =
        animateLottieCompositionAsState(
            composition = composition,
            isPlaying = motionEnabled && beat != null,
            iterations = if (beat == SyncBeat.SYNCING) LottieConstants.IterateForever else 1,
            clipSpec = SyncStateFrames.clipSpec(beat),
        )
    val settled = SyncStateFrames.settledProgress(beat)
    return remember(animation, motionEnabled, settled) {
        { if (motionEnabled) animation.value else settled }
    }
}

/**
 * The header's leading glyph: three waving dots while a pass runs, resolving into a drawn-on check
 * when it settles. One asset, two frame ranges, recolored from the active theme -- the dots are
 * fills and the check is a stroke, so the two typed helpers are not interchangeable here.
 */
@Composable
private fun SyncStateGlyph(
    composition: LottieComposition?,
    progress: () -> Float,
) {
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val checkColor = MaterialTheme.colorScheme.primary.toArgb()
    val dynamicProperties =
        remember(dotColor, checkColor) {
            // Built directly rather than through rememberLottieDynamicProperty, which keys on the
            // vararg keypath array's identity and so rebuilds (and re-resolves keypaths) every pass.
            // The dots keep the header's own label ink; the check earns the theme accent.
            LottieDynamicProperties(
                listOf(
                    lottieFillColor(dotColor, KeyPath("dots", "**")),
                    lottieStrokeColor(checkColor, KeyPath("check", "**")),
                ),
            )
        }
    LottieAnimation(
        composition = composition,
        progress = progress,
        dynamicProperties = dynamicProperties,
        modifier =
            Modifier
                .size(12.dp)
                .testTag("chatlist_sync_header_glyph"),
    )
}

@Composable
private fun SyncHeaderSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("chatlist_sync_header"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SyncHeaderLabel(
    label: String,
    tag: String,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .testTag(tag)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@PreviewLightDark
@Composable
private fun ChatListSyncHeaderPreview() {
    MotdTheme(dynamicColor = false) {
        Column {
            ChatListSyncHeader(ChatListSyncChrome.Syncing(done = 12, total = 42))
            ChatListSyncHeader(ChatListSyncChrome.Waiting(queued = 7))
        }
    }
}
