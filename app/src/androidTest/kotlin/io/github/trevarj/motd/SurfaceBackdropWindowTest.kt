package io.github.trevarj.motd

import android.graphics.Bitmap
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.trevarj.motd.ui.components.SurfaceBackdropSource
import io.github.trevarj.motd.ui.components.captureSurfaceBackdrop
import io.github.trevarj.motd.ui.components.rememberSurfaceBackdropSource
import io.github.trevarj.motd.ui.components.surfaceBackdrop
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

/** Real Android windows: Robolectric's native capture does not dispatch window frame callbacks. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class SurfaceBackdropWindowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun admissionGateIsCapturedBeforeTranslucentConsumersCanReplayMessages() {
        val admitted = mutableStateOf(false)
        var consumer: LayoutCoordinates? = null
        compose.setContent {
            val source = rememberSurfaceBackdropSource()
            Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black) }) {
                Box(Modifier.size(120.dp).captureSurfaceBackdrop(source).graphicsLayer { alpha = if (admitted.value) 1f else 0f }) {
                    Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Red) })
                }
                Box(Modifier.size(100.dp).surfaceBackdrop(source, Color.Black, 1, 0).onGloballyPositioned { consumer = it })
            }
        }
        for (visible in listOf(false, true, false)) {
            compose.runOnIdle { admitted.value = visible }
            var point = Offset.Zero
            compose.runOnIdle { point = consumer!!.localToScreen(Offset(consumer!!.size.width / 2f, consumer!!.size.height / 2f)) }
            compose.waitUntil(timeoutMillis = 5_000) {
                val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return@waitUntil false
                try {
                    val red = AndroidColor.red(pixel(image, point))
                    if (visible) red > 220 else red < 20
                } finally {
                    image.recycle()
                }
            }
        }
    }

    @Test
    fun dropdown_childLayerUpdates_scheduleWindowBeforeScreenshot_and_show_current_pixels() {
        val fixture = Fixture()
        val phase = mutableIntStateOf(0)
        val enabled = mutableStateOf(true)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                val source = rememberSurfaceBackdropSource()
                fixture.source = source
                Pattern(source, phase, fixture)
                Box(Modifier.offset(52.dp, 130.dp).size(40.dp)) {
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = {},
                        containerColor = Color.Transparent,
                        modifier =
                            Modifier
                                .width(240.dp)
                                .surfaceBackdrop(source, Color.Black, 8, 12, effectsEnabled = enabled.value)
                                .onGloballyPositioned { fixture.consumerCoordinates = it },
                    ) {
                        ObserveWindow(fixture.consumer)
                        DropdownMenuItem(text = { Text("Native menu action") }, onClick = {})
                        SharpForeground(fixture)
                        Spacer(Modifier.height(160.dp))
                    }
                }
            }
        }
        awaitInitialWindows(fixture)
        assertIdle(fixture)
        assertScreenPixels(fixture, 0)
        repeat(4) { index ->
            assertLiveUpdate(fixture, phase, index + 1)
        }

        val sourceIdentity = fixture.source
        val popupIdentity = fixture.consumer.view!!.rootView
        val beforeOff = fixture.consumer.commits
        compose.runOnIdle { enabled.value = false }
        awaitConsumerFrame(fixture, beforeOff)
        assertOpaquePixels(fixture)
        compose.runOnIdle {
            assertTrue("master does not recreate source", sourceIdentity === fixture.source)
            assertTrue("master does not recreate popup window", popupIdentity === fixture.consumer.view!!.rootView)
            assertEquals("master Off releases capture", 0, fixture.source!!.users)
        }
        val beforeOn = fixture.consumer.commits
        compose.runOnIdle { enabled.value = true }
        awaitConsumerFrame(fixture, beforeOn)
        assertScreenPixels(fixture, 4)
        assertIdle(fixture)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun modalSheet_childLayerUpdates_scheduleWindowBeforeScreenshot_and_show_current_pixels() {
        val fixture = Fixture()
        val phase = mutableIntStateOf(0)
        val showSheet = mutableStateOf(false)
        val height = mutableIntStateOf(260)
        compose.setContent {
            MotdTheme(
                dynamicColor = false,
                surfaceEffects =
                    io.github.trevarj.motd.data.prefs
                        .SurfaceEffects(
                            surfaces =
                                mapOf(
                                    io.github.trevarj.motd.data.prefs.EffectSurface.SHEET to
                                        io.github.trevarj.motd.data.prefs
                                            .SurfaceEffect(8, 12),
                                ),
                        ),
            ) {
                val source = rememberSurfaceBackdropSource()
                fixture.source = source
                Pattern(source, phase, fixture)
                if (showSheet.value) {
                    androidx.compose.runtime.CompositionLocalProvider(io.github.trevarj.motd.ui.components.LocalSurfaceBackdrop provides source) {
                        io.github.trevarj.motd.ui.components.EffectModalBottomSheet(
                            onDismissRequest = {},
                            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                            containerColor = Color.Black,
                        ) {
                            ObserveWindow(fixture.consumer)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(height.intValue.dp)
                                    .onGloballyPositioned { fixture.consumerCoordinates = it.parentLayoutCoordinates?.parentLayoutCoordinates },
                            ) {
                                Column {
                                    Text("Native sheet content")
                                    SharpForeground(fixture)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { fixture.producer.commits > 0 && fixture.childDraws > 0 }
        compose.runOnIdle { showSheet.value = true }
        awaitInitialWindows(fixture)
        assertIdle(fixture)
        assertScreenPixels(fixture, 0)
        assertSheetHandleBacking(fixture, 0)
        repeat(4) { index ->
            assertLiveUpdate(fixture, phase, index + 1)
        }
        assertSheetHandleBacking(fixture, 4)
        val before = fixture.consumerCoordinates!!.localToScreen(Offset.Zero).y
        compose.runOnIdle { height.intValue = 340 }
        compose.waitUntil(timeoutMillis = 5_000) { fixture.consumerCoordinates!!.localToScreen(Offset.Zero).y < before - 20f }
        compose.waitForIdle()
        assertScreenPixels(fixture, 4)
        assertSheetHandleBacking(fixture, 4)
    }

    private fun assertSheetHandleBacking(
        fixture: Fixture,
        phase: Int,
    ) {
        var point = Offset.Zero
        var red = false
        compose.runOnIdle {
            val surface = fixture.consumerCoordinates!!
            val source = fixture.sourceCoordinates!!
            val candidate = surface.localToScreen(Offset(surface.size.width / 4f, fixture.stripeWidth / 4f))
            val local = source.screenToLocal(candidate)
            val stripe = floor(local.x / fixture.stripeWidth)
            point = source.localToScreen(Offset((stripe + 0.5f) * fixture.stripeWidth, local.y))
            red = (stripe.toInt() + phase) % 2 == 0
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return@waitUntil false
            try {
                val value = pixel(screenshot, point)
                if (red) {
                    AndroidColor.red(value) > 190 && AndroidColor.blue(value) < 60
                } else {
                    AndroidColor.blue(value) > 190 && AndroidColor.red(value) < 60
                }
            } finally {
                screenshot.recycle()
            }
        }
    }

    private class WindowFrames {
        @Volatile var draws = 0

        @Volatile var commits = 0
        var view: View? = null
    }

    private class Fixture {
        val producer = WindowFrames()
        val consumer = WindowFrames()

        @Volatile var childDraws = 0
        var source: SurfaceBackdropSource? = null
        var sourceCoordinates: LayoutCoordinates? = null
        var consumerCoordinates: LayoutCoordinates? = null
        var foregroundCoordinates: LayoutCoordinates? = null
        var stripeWidth = 0f
    }

    @Composable
    private fun Pattern(
        source: SurfaceBackdropSource,
        phase: MutableIntState,
        fixture: Fixture,
    ) {
        ObserveWindow(fixture.producer)
        Box(
            Modifier
                .fillMaxSize()
                .captureSurfaceBackdrop(source)
                .onGloballyPositioned { fixture.sourceCoordinates = it },
        ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer().drawBehind {
                    // No composition observes phase: only this child RenderNode redraws.
                    val current = phase.intValue
                    fixture.childDraws++
                    val stripe = 40.dp.toPx()
                    fixture.stripeWidth = stripe
                    var x = 0f
                    var index = 0
                    while (x < size.width) {
                        drawRect(
                            if ((index + current) % 2 == 0) Color.Red else Color.Blue,
                            Offset(x, 0f),
                            Size(stripe, size.height),
                        )
                        index++
                        x += stripe
                    }
                },
            )
        }
    }

    @Composable
    private fun SharpForeground(fixture: Fixture) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .onGloballyPositioned { fixture.foregroundCoordinates = it }
                .drawBehind {
                    drawRect(Color.White, Offset(20.dp.toPx(), 0f), Size(8.dp.toPx(), size.height))
                    drawRect(Color.Black, Offset(28.dp.toPx(), 0f), Size(8.dp.toPx(), size.height))
                },
        )
    }

    @Composable
    private fun ObserveWindow(frames: WindowFrames) {
        val view = LocalView.current
        frames.view = view
        DisposableEffect(view) {
            var active = true
            val observer = view.viewTreeObserver
            val committed = Runnable { if (active) frames.commits++ }
            val drawn = ViewTreeObserver.OnDrawListener { frames.draws++ }
            val preDraw =
                ViewTreeObserver.OnPreDrawListener {
                    val current = view.viewTreeObserver
                    if (active && current.isAlive) {
                        current.unregisterFrameCommitCallback(committed)
                        current.registerFrameCommitCallback(committed)
                    }
                    true
                }
            observer.addOnDrawListener(drawn)
            observer.addOnPreDrawListener(preDraw)
            onDispose {
                active = false

                fun remove(current: ViewTreeObserver) {
                    if (!current.isAlive) return
                    current.removeOnDrawListener(drawn)
                    current.removeOnPreDrawListener(preDraw)
                    current.unregisterFrameCommitCallback(committed)
                }
                remove(observer)
                if (view.viewTreeObserver !== observer) remove(view.viewTreeObserver)
            }
        }
    }

    private fun awaitInitialWindows(fixture: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) { fixture.producer.commits > 0 && fixture.consumer.commits > 0 }
        compose.runOnIdle {
            assertTrue("real hardware producer", fixture.producer.view!!.isHardwareAccelerated)
            assertTrue("consumer really has another Android window", fixture.producer.view!!.rootView !== fixture.consumer.view!!.rootView)
        }
    }

    private fun assertLiveUpdate(
        fixture: Fixture,
        phase: MutableIntState,
        next: Int,
    ) {
        assertIdle(fixture)
        val beforeDraw = fixture.consumer.draws
        val beforeCommit = fixture.consumer.commits
        val beforeChild = fixture.childDraws
        var rawRevision = 0
        compose.runOnIdle {
            rawRevision = fixture.source!!.revision
            phase.intValue = next
        }
        // No screenshot, captureToImage, source redraw, or popup invalidation may precede this.
        compose.waitUntil(timeoutMillis = 5_000) {
            fixture.childDraws > beforeChild && fixture.consumer.draws > beforeDraw && fixture.consumer.commits > beforeCommit
        }
        compose.runOnIdle { assertEquals("raw producer display list stays cached", rawRevision, fixture.source!!.revision) }
        assertScreenPixels(fixture, next)
        assertIdle(fixture)
    }

    private fun awaitConsumerFrame(
        fixture: Fixture,
        beforeCommit: Int,
    ) {
        compose.waitUntil(timeoutMillis = 5_000) { fixture.consumer.commits > beforeCommit }
    }

    private fun assertIdle(fixture: Fixture) {
        compose.waitForIdle()
        // Drain the finite producer-commit -> consumer-frame pipeline before measuring idle.
        repeat(2) { awaitAnimationTurn(fixture) }
        val producer = fixture.producer.draws
        val consumer = fixture.consumer.draws
        // Vsync callbacks schedule no View invalidation: both windows must stay quiescent.
        repeat(2) { awaitAnimationTurn(fixture) }
        assertEquals("producer must not self-invalidate indefinitely", producer, fixture.producer.draws)
        assertEquals("consumer must not self-invalidate indefinitely", consumer, fixture.consumer.draws)
    }

    private fun awaitAnimationTurn(fixture: Fixture) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(2)
        instrumentation.runOnMainSync {
            fixture.producer.view!!.postOnAnimation { ready.countDown() }
            fixture.consumer.view!!.postOnAnimation { ready.countDown() }
        }
        assertTrue("native animation callbacks complete", ready.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private data class ScanPoint(
        val screen: Offset,
        val red: Boolean,
        val edge: Boolean,
    )

    private data class ScreenSample(
        val scan: List<ScanPoint>,
        val source: ScanPoint,
        val white: Offset,
        val black: Offset,
    )

    private fun screenSample(
        fixture: Fixture,
        phase: Int,
    ): ScreenSample {
        lateinit var result: ScreenSample
        compose.runOnIdle {
            val source = fixture.sourceCoordinates!!
            val consumer = fixture.consumerCoordinates!!
            val foreground = fixture.foregroundCoordinates!!
            val density = fixture.stripeWidth / 40f
            val y = consumer.size.height - 20f * density
            val scan = ArrayList<ScanPoint>()
            for (x in (8 * density).roundToInt() until consumer.size.width - (8 * density).roundToInt()) {
                val screen = consumer.localToScreen(Offset(x.toFloat(), y))
                val local = source.screenToLocal(screen)
                val position = local.x / fixture.stripeWidth
                val fraction = position - floor(position)
                val edge = fraction < 0.025f || fraction > 0.975f
                if (fraction in 0.35f..0.65f || edge) {
                    scan.add(ScanPoint(screen, (floor(position).toInt() + phase) % 2 == 0, edge))
                }
            }
            result =
                ScreenSample(
                    scan,
                    ScanPoint(source.localToScreen(Offset(fixture.stripeWidth / 2, 24f * density)), phase % 2 == 0, false),
                    foreground.localToScreen(Offset(26f * density, foreground.size.height / 2f)),
                    foreground.localToScreen(Offset(30f * density, foreground.size.height / 2f)),
                )
        }
        return result
    }

    private fun pixel(
        bitmap: Bitmap,
        point: Offset,
    ): Int = bitmap.getPixel(point.x.roundToInt(), point.y.roundToInt())

    private fun assertScreenPixels(
        fixture: Fixture,
        phase: Int,
    ) {
        val sample = screenSample(fixture, phase)
        var lastFailure = "no SurfaceFlinger screenshot"
        // Commit means submitted, not necessarily presented. Read physical display buffers only;
        // unlike Compose captureToImage this never asks either application window to draw.
        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                if (screenshot == null) {
                    false
                } else {
                    try {
                        fun matches(point: ScanPoint): Boolean {
                            val value = pixel(screenshot, point.screen)
                            val red = AndroidColor.red(value)
                            val blue = AndroidColor.blue(value)
                            return if (point.red) red > 190 && blue < 60 else blue > 190 && red < 60
                        }
                        val interiors = sample.scan.filterNot { it.edge }
                        val edges = sample.scan.filter { it.edge }
                        val softened =
                            edges.count {
                                val value = pixel(screenshot, it.screen)
                                AndroidColor.red(value) in 30..215 && AndroidColor.blue(value) in 30..215
                            }
                        val white = pixel(screenshot, sample.white)
                        val black = pixel(screenshot, sample.black)
                        val matching = interiors.count(::matches)
                        val sourcePixel = pixel(screenshot, sample.source.screen)
                        val sourceRed = AndroidColor.red(sourcePixel)
                        val sourceBlue = AndroidColor.blue(sourcePixel)
                        // A sheet's native dim scrim darkens the exposed page, not the sampled raw
                        // source. Check its current phase without pretending that scrim is absent.
                        val sourceMatches = if (sample.source.red) sourceRed > 100 && sourceRed > sourceBlue + 80 else sourceBlue > 100 && sourceBlue > sourceRed + 80
                        val valid =
                            interiors.size > 10 && matching == interiors.size && sourceMatches && softened > 2 &&
                                AndroidColor.red(white) > 245 && AndroidColor.green(white) > 245 && AndroidColor.blue(white) > 245 &&
                                AndroidColor.red(black) < 10 && AndroidColor.green(black) < 10 && AndroidColor.blue(black) < 10
                        lastFailure = "phase=$phase source=$sourceMatches($sourceRed,$sourceBlue) interior=$matching/${interiors.size} softened=$softened white=$white black=$black"
                        valid
                    } finally {
                        screenshot.recycle()
                    }
                }
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError(lastFailure, failure)
        }
    }

    private fun assertOpaquePixels(fixture: Fixture) {
        val sample = screenSample(fixture, 4)
        compose.waitUntil(timeoutMillis = 5_000) {
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (screenshot == null) {
                false
            } else {
                try {
                    sample.scan.filterNot { it.edge }.all {
                        val value = pixel(screenshot, it.screen)
                        AndroidColor.red(value) < 10 && AndroidColor.green(value) < 10 && AndroidColor.blue(value) < 10
                    }
                } finally {
                    screenshot.recycle()
                }
            }
        }
    }
}
