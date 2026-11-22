package io.github.trevarj.motd.macrobenchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    private val fixture = BenchmarkFixture.read()

    /** Cold start of an onboarded, synced account until the chat list is on screen. */
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
            check(device.wait(Until.hasObject(By.res("screen_chat_list")), 10_000)) { "chat list not shown" }
        }
}
