package io.github.trevarj.motd

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.io.FileInputStream
import java.util.ArrayDeque

/** Focused release smoke: real onboarding, TLS TOFU, SASL, and optional soju discovery. */
class OnboardingConnectivitySmokeTest(
    private val instrumentation: Instrumentation,
    private val arguments: Bundle,
) {
    private val ui get() = instrumentation.uiAutomation
    private val targetPackage get() = instrumentation.targetContext.packageName

    fun run() {
        // Gradle Managed Devices start from a clean snapshot. Never call `pm clear` here:
        // instrumentation runs in the target process, so clearing the target kills the runner.
        shell("pm grant $targetPackage ${Manifest.permission.POST_NOTIFICATIONS}")
        val launch = instrumentation.targetContext.packageManager
            .getLaunchIntentForPackage(targetPackage)
            ?: error("No launch intent for $targetPackage")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.startActivitySync(launch)
        instrumentation.waitForIdleSync()

        val host = arguments.getString("sojuHost") ?: "10.0.2.2"
        val port = arguments.getString("sojuPort") ?: "6697"
        val user = arguments.getString("sojuUser") ?: "motd"
        val password = arguments.getString("sojuPassword") ?: "motdtest"
        val nick = arguments.getString("nick") ?: "motdadb"
        val bouncerKind = arguments.getString("bouncerKind")?.lowercase() ?: "soju"
        val zncNetwork = arguments.getString("zncNetwork") ?: "libera"

        enterConnectionChoice()
        clickTag("onboarding_choice_bouncer")
        clickTag(if (bouncerKind == "znc") "onboarding_choice_znc" else "onboarding_choice_soju")
        clickTag("onboarding_forward_button")

        setText("network_host_field", host)
        setText("network_port_field", port)
        setText("network_nick_field", nick)
        clickTag("onboarding_forward_button")

        setText("bouncer_username_field", user)
        if (bouncerKind == "znc") setText("bouncer_znc_network_field", zncNetwork)
        setText("bouncer_password_field", password)
        clickTag("onboarding_forward_button")

        findText("Trust this certificate?", 30_000)?.let {
            click(findText("Trust", 5_000) ?: error("TOFU dialog has no Trust action"))
        }

        waitFor(45_000, "connection ready") {
            findNode { it.text?.toString()?.startsWith("Connected as ") == true }
        }
        if (bouncerKind != "znc") {
            requireNotNull(findText("Bouncer networks", 30_000)) {
                "Connected to soju but bouncer network discovery did not load"
            }
            requireNotNull(findText("libera", 30_000)) {
                "Hermetic soju did not expose the provisioned libera network"
            }
        }
    }

    private fun clickTag(tag: String) = click(waitFor(15_000, "enabled tag $tag") {
        findTag(tag)?.takeIf(AccessibilityNodeInfo::isEnabled)
    })

    /**
     * Compose can acknowledge an accessibility click before delivering it to the button. The
     * welcome page is safe to retry because the next page's forward button is disabled until a
     * connection type is selected.
     */
    private fun enterConnectionChoice() {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var nextAttemptAt = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            if (findTag("onboarding_choice_bouncer") != null) return

            val now = SystemClock.uptimeMillis()
            if (now >= nextAttemptAt) {
                findTag("onboarding_forward_button")
                    ?.takeIf(AccessibilityNodeInfo::isEnabled)
                    ?.let(::click)
                nextAttemptAt = now + 1_000
            }
            SystemClock.sleep(200)
        }
        error(
            "Timed out entering connection choice; visible UI: ${visibleUiSummary()}",
        )
    }

    private fun setText(tag: String, value: String) {
        val node = waitFor(15_000, "editable tag $tag") { findTag(tag) }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            "Could not set text on $tag"
        }
        instrumentation.waitForIdleSync()
        // ACTION_SET_TEXT is delivered through accessibility and Compose state can settle a
        // frame after instrumentation becomes idle. Forward-button enablement is the eventual
        // assertion; this short yield prevents back-to-back field actions from racing it.
        SystemClock.sleep(250)
    }

    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            // Compose semantics can advertise ACTION_CLICK without setting isClickable on the
            // resource-id node, so try the action before walking to a merged parent.
            if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync()
                return
            }
            current = current.parent
        }
        error("Node is not clickable: ${node.viewIdResourceName ?: node.text}")
    }

    private fun findTag(tag: String): AccessibilityNodeInfo? = findNode {
        it.viewIdResourceName?.substringAfterLast('/') == tag
    }

    private fun findText(text: String, timeoutMs: Long): AccessibilityNodeInfo? =
        runCatching {
            waitFor(timeoutMs, "text $text") {
                findNode {
                    it.text?.toString()?.equals(text, ignoreCase = true) == true ||
                        it.contentDescription?.toString()?.equals(text, ignoreCase = true) == true
                }
            }
        }.getOrNull()

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = ui.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
        }
        return null
    }

    private fun visibleUiSummary(): String {
        val root = ui.rootInActiveWindow ?: return "<no active window>"
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val visible = mutableListOf<String>()
        while (queue.isNotEmpty() && visible.size < 30) {
            val node = queue.removeFirst()
            val tag = node.viewIdResourceName?.substringAfterLast('/')
            val label = node.text?.toString() ?: node.contentDescription?.toString()
            if (tag != null || !label.isNullOrBlank()) {
                visible += listOfNotNull(tag, label).joinToString("=")
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
        }
        return visible.joinToString(", ")
    }

    private fun <T> waitFor(timeoutMs: Long, label: String, block: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            block()?.let { return it }
            SystemClock.sleep(200)
        }
        error("Timed out waiting for $label")
    }

    private fun shell(command: String) {
        val output: ParcelFileDescriptor = ui.executeShellCommand(command)
        FileInputStream(output.fileDescriptor).use { it.readBytes() }
        output.close()
    }
}

/** Minimal runner with standard instrumentation status events, requiring no extra test library. */
class SmokeTestRunner : Instrumentation() {
    private var testArguments: Bundle = Bundle.EMPTY

    override fun onCreate(arguments: Bundle?) {
        testArguments = arguments ?: Bundle.EMPTY
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = testResultBundle()
        sendStatus(1, Bundle(result).apply {
            putString(REPORT_KEY_STREAMRESULT, "OnboardingConnectivitySmokeTest: START\n")
        })
        try {
            OnboardingConnectivitySmokeTest(this, testArguments).run()
            result.putString(REPORT_KEY_STREAMRESULT, "OnboardingConnectivitySmokeTest: PASS\n")
            sendStatus(0, result)
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            val trace = failure.stackTraceToString()
            result.putString(REPORT_KEY_STREAMRESULT, "OnboardingConnectivitySmokeTest: FAIL\n$trace\n")
            sendStatus(-2, result)
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun testResultBundle() = Bundle().apply {
        putString("id", "InstrumentationTestRunner")
        putInt("numtests", 1)
        putInt("current", 1)
        putString("class", OnboardingConnectivitySmokeTest::class.java.name)
        putString("test", "onboardingConnectsToHermeticSoju")
    }
}
