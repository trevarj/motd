package io.github.trevarj.motd.ui.chatlist

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.LocalLottieMotionEnabled
import io.github.trevarj.motd.ui.theme.ensureContrast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

internal class SeasonalWordmarkPlayback(
    private val season: SeasonalWordmarkKind,
    private val scope: CoroutineScope,
    val enabled: Boolean,
    private val motionEnabled: Boolean,
) {
    private var time by mutableFloatStateOf(-1f)
    private var job: Job? = null
    var snowAnchorInRoot by mutableStateOf(Offset.Unspecified)

    fun elapsed(): Float? = time.takeIf { it >= 0f }

    fun play() {
        if (!enabled || time >= 0f) return
        val animated = scope.coroutineContext[MotionDurationScale]?.let { it.scaleFactor > 0f } ?: motionEnabled
        time = if (animated) 0f else Float.POSITIVE_INFINITY
        job =
            scope.launch {
                if (animated) {
                    val duration =
                        when (season) {
                            SeasonalWordmarkKind.HALLOWEEN -> 960
                            SeasonalWordmarkKind.CHRISTMAS -> 1637
                            SeasonalWordmarkKind.NEW_YEAR -> 1800
                            SeasonalWordmarkKind.ORTHODOX_EASTER -> 1240
                        }
                    animate(0f, duration.toFloat(), animationSpec = tween(duration, easing = LinearEasing)) { value, _ -> time = value }
                } else {
                    delay(
                        when (season) {
                            SeasonalWordmarkKind.HALLOWEEN -> 960
                            SeasonalWordmarkKind.CHRISTMAS -> 900
                            SeasonalWordmarkKind.NEW_YEAR -> 2200
                            SeasonalWordmarkKind.ORTHODOX_EASTER -> 740
                        },
                    )
                }
                time = -1f
                job = null
            }
    }

    fun reset() {
        job?.cancel()
        job = null
        time = -1f
    }
}

@Composable
internal fun rememberSeasonalWordmarkPlayback(season: SeasonalWordmark): SeasonalWordmarkPlayback {
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val motionEnabled = LocalLottieMotionEnabled.current
    return key(season, resumed, motionEnabled) {
        val scope = rememberCoroutineScope()
        remember { SeasonalWordmarkPlayback(season.kind, scope, resumed, motionEnabled) }
    }
}

@Composable
internal fun Modifier.seasonalToolbarSnow(
    playback: SeasonalWordmarkPlayback,
    statusInsetPx: Float,
): Modifier {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.36f
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    return onGloballyPositioned { origin = it.positionInRoot() }
        .drawWithContent {
            val time = playback.elapsed()
            val anchor = playback.snowAnchorInRoot
            if (time != null && origin.isSpecified && anchor.isSpecified && size.height > statusInsetPx) {
                inset(left = 0f, top = statusInsetPx, right = 0f, bottom = 0f) {
                    clipRect {
                        drawSeasonalSnow(anchor - origin - Offset(0f, statusInsetPx), time, dark)
                    }
                }
            }
            drawContent()
        }
}

@Composable
internal fun SeasonalChatListTitle(
    season: SeasonalWordmark,
    showStatus: Boolean,
    modifier: Modifier = Modifier,
    playback: SeasonalWordmarkPlayback = rememberSeasonalWordmarkPlayback(season),
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    var titleLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    val year =
        remember(season.year, locale) {
            season.year?.let { NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = false }.format(it) }
        }
    val yearLayout = if (year != null) rememberTextMeasurer().measure(year, MaterialTheme.typography.labelMedium, maxLines = 1) else null
    val label =
        when (season.kind) {
            SeasonalWordmarkKind.HALLOWEEN -> stringResource(R.string.chatlist_seasonal_halloween)
            SeasonalWordmarkKind.CHRISTMAS -> stringResource(R.string.chatlist_seasonal_christmas)
            SeasonalWordmarkKind.NEW_YEAR -> stringResource(R.string.chatlist_seasonal_new_year, year.orEmpty())
            SeasonalWordmarkKind.ORTHODOX_EASTER -> stringResource(R.string.chatlist_seasonal_orthodox_easter)
        }
    val surface = MaterialTheme.colorScheme.surface
    val dark = surface.luminance() < 0.36f
    val yearColor =
        if (yearLayout != null) {
            remember(surface, dark) { ensureContrast(if (dark) Color(0xFFFFD386) else Color(0xFF824410), listOf(surface), minimum = 4.5) }
        } else {
            Color.Unspecified
        }

    val pascha = season.kind == SeasonalWordmarkKind.ORTHODOX_EASTER
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val layout = titleLayout
        val free = if (layout == null) 0.dp else maxWidth - with(density) { layout.size.width.toDp() }
        val text = layout?.layoutInput?.text
        val letterIndex = text?.indexOf(if (pascha) 't' else 'd', ignoreCase = true) ?: -1
        val ornamentFits = layout != null && !layout.hasVisualOverflow && (if (pascha) letterIndex >= 0 else free >= 20.dp)
        DisposableEffect(playback, ornamentFits) {
            onDispose { playback.reset() }
        }

        if (layout == null || !ornamentFits) {
            ChatListDefaultTitle(showStatus = showStatus, onTextLayout = { titleLayout = it })
        } else {
            val yearGap = 8.dp
            val yearWidth = if (yearLayout == null) 0.dp else with(density) { yearLayout.size.width.toDp() } + yearGap
            val yearFits = yearLayout != null && yearWidth <= free && yearLayout.size.height <= layout.size.height
            val artSpan =
                when (season.kind) {
                    SeasonalWordmarkKind.HALLOWEEN -> 46f
                    SeasonalWordmarkKind.CHRISTMAS -> 24f
                    SeasonalWordmarkKind.NEW_YEAR -> 40f
                    SeasonalWordmarkKind.ORTHODOX_EASTER -> 0f
                }
            val preferredUnit = if (showStatus || season.kind == SeasonalWordmarkKind.CHRISTMAS) 0.833.dp else 0.95.dp
            val extra = maxOf(minOf(preferredUnit * artSpan, free), if (yearFits) yearWidth else 0.dp)
            val fontSize = layout.layoutInput.style.fontSize
            val fontPx = with(density) { fontSize.toPx() }
            val letter = layout.getBoundingBox(if (letterIndex >= 0) letterIndex else layout.layoutInput.text.lastIndex)
            val letterX = if (rtl) letter.left + fontPx * 0.09f else letter.right - fontPx * 0.09f
            val capTop = layout.firstBaseline - fontPx * 0.72f
            val artUnit =
                if (pascha) {
                    0f
                } else {
                    with(density) {
                        minOf(
                            preferredUnit.toPx(),
                            extra.toPx() / artSpan,
                            if (showStatus) (capTop + 7.dp.toPx()) / 22f else Float.MAX_VALUE,
                        )
                    }
                }
            val hat = if (season.kind == SeasonalWordmarkKind.CHRISTMAS) remember { SeasonalChristmasArt() } else null
            val bat = if (season.kind == SeasonalWordmarkKind.HALLOWEEN) remember { SeasonalHalloweenArt() } else null
            val sparkler = if (season.kind == SeasonalWordmarkKind.NEW_YEAR) remember { SeasonalNewYearArt() } else null
            val cross = if (pascha) remember { SeasonalPaschaArt() } else null
            val position =
                if (hat != null) {
                    Modifier.onGloballyPositioned { coordinates ->
                        val wordLeft = if (rtl) coordinates.size.width - layout.size.width else 0
                        playback.snowAnchorInRoot = coordinates.positionInRoot() + Offset(wordLeft + letterX, capTop)
                    }
                } else {
                    Modifier
                }
            val click =
                if (playback.enabled) {
                    Modifier
                        .testTag("chatlist_seasonal_action")
                        .clickable(interactionSource = null, indication = null, onClickLabel = label, role = Role.Button, onClick = playback::play)
                } else {
                    Modifier
                }
            Row(
                click.then(position).drawWithContent {
                    drawContent()
                    val wordLeft = if (rtl) size.width - layout.size.width else 0f
                    val anchor = Offset(wordLeft + letterX, capTop)
                    val time = playback.elapsed()
                    if (cross != null) {
                        val width = minOf(fontPx * 0.44f, letter.width + fontPx * 0.06f)
                        val height = fontPx * 0.86f
                        val topLeft = Offset(wordLeft + letter.center.x - width / 2f, layout.firstBaseline - height)
                        cross.draw(this, topLeft, width, height, time, dark)
                    } else {
                        scale(scaleX = if (rtl) -1f else 1f, scaleY = 1f, pivot = anchor) {
                            hat?.drawHat(this, anchor, artUnit, time)
                            bat?.draw(this, anchor, artUnit, time, dark)
                            sparkler?.draw(this, anchor, artUnit, time, dark)
                        }
                    }
                    if (yearFits && time != null && time >= 468f) {
                        val opacity = if (time.isInfinite()) 1f else minOf((time - 468f) / 216f, (1800f - time) / 216f, 1f).coerceAtLeast(0f)
                        val yearX = if (rtl) wordLeft - yearGap.toPx() - yearLayout.size.width else layout.size.width + yearGap.toPx()
                        val y = (layout.getLineTop(0) + layout.getLineBottom(0) - yearLayout.size.height) / 2f
                        drawText(yearLayout, color = yearColor, alpha = opacity, topLeft = Offset(yearX, y))
                    }
                },
            ) {
                ChatListDefaultTitle(
                    showStatus = showStatus,
                    onTextLayout = { titleLayout = it },
                    maskedLetter = letterIndex.takeIf { pascha },
                )
                if (extra > 0.dp) Spacer(Modifier.width(extra))
            }
        }
    }
}
