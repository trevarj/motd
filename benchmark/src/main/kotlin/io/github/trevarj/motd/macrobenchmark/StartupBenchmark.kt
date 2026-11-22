package io.github.trevarj.motd.macrobenchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    private val fixture = BenchmarkFixture.read()

    @Before
    fun clearTargetData() {
        // Start each invocation with no saved trust decision, even when the APK is reused.
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val result = device.executeShellCommand("pm clear $TARGET_PACKAGE").trim()
        check(result == "Success") { "pm clear $TARGET_PACKAGE failed: $result" }
    }

    /** Cold start of an onboarded account until its fixture channel is shown in the chat list. */
    @Test
    fun coldStartToChatList() =
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            iterations = BenchmarkFixture.intArg("iterations", 10),
            startupMode = StartupMode.COLD,
            setupBlock = { ensureOnboarded(fixture) },
        ) {
            startActivityAndWait()
            check(device.wait(Until.hasObject(fixture.chatListRow()), 10_000)) {
                "fixture channel ${fixture.channel} not shown in the chat list"
            }
        }
}
