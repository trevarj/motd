package io.github.trevarj.motd.macrobenchmark

import android.content.Intent
import android.os.Build
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

const val TARGET_PACKAGE = "io.github.trevarj.motd.benchmark"
private const val FIXTURE_NETWORK = "libera"
private const val FIXTURE_SEED_PREVIEW = "motdadb: welcome to the seed channel"
private val chatListRowTag = Pattern.compile("chatlist_row_\\d+")

/**
 * The soju fixture the benchmark runs against. Argument names match the required E2E suite, so
 * `test/e2e/local-stack.sh` (and its adb reverse) serves both. The stack is prepared externally.
 */
data class BenchmarkFixture(
    val host: String,
    val sojuPort: Int,
    val sojuUser: String,
    val sojuPassword: String,
    val nick: String,
    val channel: String,
    val sojuTlsSha256: String,
) {
    companion object {
        fun read(): BenchmarkFixture {
            val args = InstrumentationRegistry.getArguments()

            fun arg(
                name: String,
                default: String,
            ) = args.getString(name)?.trim()?.takeIf(String::isNotEmpty) ?: default
            val fingerprint = arg("sojuTlsSha256", "").lowercase().replace(":", "")
            require(fingerprint.matches(Regex("[0-9a-f]{64}"))) {
                "sojuTlsSha256 must be the fixture certificate's SHA-256 (local-stack.sh tls-fingerprint)"
            }
            return BenchmarkFixture(
                host = arg("sojuHost", "127.0.0.1"),
                sojuPort = arg("sojuPort", "6697").toInt(),
                sojuUser = arg("sojuUser", "motd"),
                sojuPassword = arg("sojuPassword", "motdtest"),
                nick = arg("nick", "motdadb"),
                channel = arg("channel", "##motdtest"),
                sojuTlsSha256 = fingerprint,
            )
        }

        fun intArg(
            name: String,
            default: Int,
        ): Int = InstrumentationRegistry.getArguments().getString(name)?.toIntOrNull() ?: default
    }
}

private fun res(tag: String): BySelector = By.res(tag)

private fun UiDevice.await(
    selector: BySelector,
    timeoutMs: Long = 15_000,
): UiObject2 = wait(Until.findObject(selector), timeoutMs) ?: error("timed out waiting for $selector")

private fun UiDevice.clickWhenEnabled(tag: String) {
    await(res(tag).enabled(true)).click()
}

private fun UiDevice.replaceText(
    tag: String,
    value: String,
) {
    await(res(tag)).text = value
}

/** Trusts the presented certificate only if it is the fixture's; never a blind accept. */
private fun UiDevice.trustFixtureCertificate(fixture: BenchmarkFixture) {
    val shown = await(res("cert_trust_fingerprint")).text.lowercase().replace(":", "")
    check(shown == fixture.sojuTlsSha256) { "soju presented an unexpected certificate: $shown" }
    clickWhenEnabled("cert_trust_accept")
}

/**
 * Leaves the app on the chat list, onboarding through the real soju import UI when app data is
 * empty (first run, or after Macrobenchmark reinstalls the APK to reset compilation state).
 */
fun MacrobenchmarkScope.ensureOnboarded(fixture: BenchmarkFixture) {
    if (Build.VERSION.SDK_INT >= 33) device.executeShellCommand("pm grant $packageName android.permission.POST_NOTIFICATIONS")
    // Clear the task so a previous iteration's screen never hides the chat list.
    startActivityAndWait { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
    with(device) {
        val start = await(By.res(Pattern.compile("chatlist_row_\\d+|onboarding_forward_button")))
        if (start.resourceName != "onboarding_forward_button") {
            await(fixture.chatListRow(), 10_000)
            return
        }
        clickWhenEnabled("onboarding_forward_button")
        clickWhenEnabled("onboarding_choice_bouncer")
        clickWhenEnabled("onboarding_choice_soju")
        clickWhenEnabled("onboarding_forward_button")
        replaceText("network_host_field", fixture.host)
        replaceText("network_port_field", fixture.sojuPort.toString())
        replaceText("network_nick_field", fixture.nick)
        clickWhenEnabled("onboarding_forward_button")
        replaceText("bouncer_username_field", fixture.sojuUser)
        replaceText("bouncer_password_field", fixture.sojuPassword)
        clickWhenEnabled("onboarding_forward_button")
        trustFixtureCertificate(fixture)
        val fixtureRow = await(By.res(Pattern.compile("onboarding_bouncer_row_.*")).hasDescendant(By.text(FIXTURE_NETWORK)), 60_000)
        val switch =
            checkNotNull(fixtureRow.findObject(By.res(Pattern.compile("onboarding_bouncer_switch_.*")).enabled(true))) {
                "fixture network $FIXTURE_NETWORK has no enabled import switch"
            }
        if (!switch.isChecked) switch.click()
        clickWhenEnabled("onboarding_forward_button")
        clickWhenEnabled("onboarding_forward_button")
        // Provision positive fixture history outside the startup trace, not merely a channel shell.
        await(fixture.chatListRow().hasDescendant(By.text(FIXTURE_SEED_PREVIEW)), 120_000)
    }
}

internal fun BenchmarkFixture.chatListRow(): BySelector =
    By
        .res(chatListRowTag)
        .hasDescendant(By.text(channel))
        .hasDescendant(By.text(FIXTURE_NETWORK))
