package io.github.trevarj.motd.macrobenchmark

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.waitForStableInActiveWindow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val GUARD_ROW = 20
private const val PAGING_MARGIN_ROW = 45
private const val NEWEST_ROW = 90
private const val TIMELINE_TAG = "chat_timeline"
private val rowText = Regex("Timeline row ([0-9]{3}): .*", RegexOption.DOT_MATCHES_ALL)
private val newestFab = By.res("chat_scroll_to_bottom_fab")

@RunWith(AndroidJUnit4::class)
class TimelineScrollBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    private val fixture = BenchmarkFixture.read()
    private val welcome = "${fixture.nick}: welcome to the seed channel"

    @Before
    fun clearTargetData() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val result = device.executeShellCommand("pm clear $TARGET_PACKAGE").trim()
        check(result == "Success") { "pm clear $TARGET_PACKAGE failed: $result" }
    }

    @Test
    fun flingIntoRetainedHistory() {
        lateinit var viewport: Rect
        var oldestAtNewest = NEWEST_ROW
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = BenchmarkFixture.intArg("iterations", 10),
            startupMode = null,
            setupBlock = {
                ensureOnboarded(fixture)
                val chat = device.wait(Until.findObject(fixture.chatListRow()), 10_000)
                checkNotNull(chat) { "fixture channel ${fixture.channel} not shown in the chat list" }.click()
                check(device.wait(Until.hasObject(By.res(TIMELINE_TAG).hasDescendant(By.text(rowText.toPattern()))), 30_000)) {
                    "no numbered fixture rows in the timeline; start a fresh local stack with MOTD_BENCHMARK_SCROLL=1"
                }
                val initial = device.settleAtNewest()
                device.proveRunway(initial, device.gestureViewport())
                oldestAtNewest =
                    device
                        .settleAtNewest()
                        .visibleTexts()
                        .ordinals()
                        .min()
                viewport = device.gestureViewport()
            },
        ) {
            device.swipeTowardHistory(viewport, steps = 14)
            val visible = device.awaitSettledTimeline().visibleTexts().ordinals()
            val oldest = visible.minOrNull()
            check(oldest != null && oldest < oldestAtNewest && NEWEST_ROW !in visible) {
                "fling did not move into older history: started at $oldestAtNewest–$NEWEST_ROW, ended at $visible"
            }
            check(oldest > PAGING_MARGIN_ROW && visible.size == visible.max() - oldest + 1) {
                "fling approached the paging boundary or left the contiguous fixture: visible rows $visible"
            }
        }
    }

    private fun UiDevice.settleAtNewest(): AccessibilityNodeInfo {
        val anchor = By.res(TIMELINE_TAG).hasDescendant(By.text(welcome))
        check(wait(Condition<UiDevice, Boolean> { it.hasObject(anchor) || it.hasObject(newestFab) }, 10_000) == true) {
            "neither the newest fixture welcome nor the jump button appeared"
        }
        findObject(newestFab)?.longClick()
        check(wait(Until.hasObject(anchor), 10_000)) { "newest fixture welcome not visible after jump" }
        check(wait(Until.gone(newestFab), 10_000)) { "timeline did not return to the newest boundary" }
        val node = awaitSettledTimeline(stableIntervalMs = 500)
        val texts = node.visibleTexts()
        val ordinals = texts.ordinals()
        check(welcome in texts && NEWEST_ROW in ordinals && !hasObject(newestFab)) {
            "expected the newest boundary with row 090 and the welcome; visible numbered rows: $ordinals"
        }
        return node
    }
}

private fun UiDevice.gestureViewport(): Rect {
    val timeline = checkNotNull(findObject(By.res(TIMELINE_TAG))).visibleBounds
    val header = checkNotNull(findObject(By.res("chat_top_app_bar"))).visibleBounds
    val composer = checkNotNull(findObject(By.res("chat_bottom_host"))).visibleBounds
    timeline.top = maxOf(timeline.top, header.bottom)
    timeline.bottom = minOf(timeline.bottom, composer.top)
    check(!timeline.isEmpty) { "no unobscured timeline between header and composer" }
    return timeline
}

private fun UiDevice.swipeTowardHistory(
    viewport: Rect,
    steps: Int,
) {
    val x = viewport.centerX()
    val y = viewport.centerY()
    val halfTravel = viewport.height() * 35 / 100
    check(swipe(x, y - halfTravel, x, y + halfTravel, steps)) { "could not inject the history gesture" }
}

private fun UiDevice.proveRunway(
    timeline: AccessibilityNodeInfo,
    viewport: Rect,
) {
    var visible = timeline.visibleTexts().ordinals()
    val deadline = SystemClock.elapsedRealtime() + 90_000
    var drags = 0
    while (visible.none { it <= GUARD_ROW } && drags < 24 && SystemClock.elapsedRealtime() < deadline) {
        swipeTowardHistory(viewport, steps = 24)
        drags++
        visible = awaitSettledTimeline().visibleTexts().ordinals()
    }
    check(visible.any { it <= GUARD_ROW }) {
        "could not reach retained row 020 or older after $drags drags; visible numbered rows: $visible"
    }
}

private fun UiDevice.awaitSettledTimeline(stableIntervalMs: Long = 250): AccessibilityNodeInfo {
    val stable =
        waitForStableInActiveWindow(
            stableTimeoutMs = 3_000,
            stableIntervalMs = stableIntervalMs,
            stablePollIntervalMs = 50,
            requireStableScreenshot = false,
        )
    check(!stable.isTimeout) { "timeline did not settle after the history gesture" }
    return checkNotNull(stable.node.findTimeline()) { "settled window did not contain the fixture timeline" }
}

private fun AccessibilityNodeInfo.findTimeline(): AccessibilityNodeInfo? {
    if (viewIdResourceName == TIMELINE_TAG) return this
    for (i in 0 until childCount) getChild(i)?.findTimeline()?.let { return it }
    return null
}

private fun AccessibilityNodeInfo.visibleTexts(): List<String> =
    buildList {
        fun collect(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            node.text?.toString()?.let(::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::collect)
        }
        collect(this@visibleTexts)
    }

private fun List<String>.ordinals(): List<Int> =
    mapNotNull { text ->
        val match = rowText.matchEntire(text) ?: return@mapNotNull null
        match.groupValues[1].toInt()
    }
