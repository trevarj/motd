package io.github.trevarj.motd.macrobenchmark

import android.content.Intent
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.waitForStableInActiveWindow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

private const val ROOM_A = "#bench-a"
private const val ROOM_B = "#bench-b"
private const val ANCHOR_A = "chat-a ready"
private const val ANCHOR_B = "chat-b ready"
private const val TIMELINE = "chat_timeline"
private const val EDITOR = "chat_composer_field"
private const val POLL_MS = 16L
private const val TIMEOUT_MS = 15_000L
private const val LOG_TAG = "MotdChatBench"
private val editor = By.res(EDITOR)
private val chatList = By.res("screen_chat_list")
private val messageTag = "chat_message_.+".toPattern()
private val localMessageTag = "chat_message_\\d+".toPattern()
private val retryControls = listOf(By.res("chat_failed_retry"), By.desc("Retry"), By.text("Retry"))
private val deleteControls = listOf(By.res("chat_failed_delete"), By.desc("Delete"), By.text("Delete"))
private val newestFab = By.res("chat_scroll_to_bottom_fab")

@RunWith(AndroidJUnit4::class)
class ChatInteractionBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Before
    fun clearTargetData() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val result = device.executeShellCommand("pm clear $TARGET_PACKAGE").trim()
        check(result == "Success") { "pm clear $TARGET_PACKAGE failed: $result" }
    }

    @Test
    fun warmChatSwitch() =
        profile(setup = {
            device.openChat(ROOM_A, ANCHOR_A, ANCHOR_B)
            device.backToList()
            device.openChat(ROOM_B, ANCHOR_B, ANCHOR_A)
            device.backToList()
        }) { timings ->
            val a = device.await(chatRow(ROOM_A)).visibleCenter
            timings.observe("chatListToAAnchor") {
                device.tap(a)
                device.awaitAnchor(ANCHOR_A, ANCHOR_B)
            }
            device.backToList()
            val b = device.await(chatRow(ROOM_B)).visibleCenter
            timings.observe("chatListToBAnchor") {
                device.tap(b)
                device.awaitAnchor(ANCHOR_B, ANCHOR_A)
            }
        }

    @Test
    fun typePlainText() =
        profile(setup = { device.prepareEditor() }) { timings ->
            var expected = ""
            for ((key, letter) in listOf(KeyEvent.KEYCODE_K to 'k', KeyEvent.KEYCODE_E to 'e', KeyEvent.KEYCODE_Y to 'y', KeyEvent.KEYCODE_S to 's')) {
                expected += letter
                timings.observe("plainKeyToEditorText") {
                    device.key(key)
                    device.await(By.res(EDITOR).text(expected))
                }
            }
        }

    @Test
    fun typeNickAutocomplete() =
        profile(setup = {
            device.prepareEditor()
            device.setText(editor, "@")
            device.key(KeyEvent.KEYCODE_MOVE_END)
        }) { timings ->
            timings.observe("nickKeysToEditorText") {
                for (key in listOf(KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_A)) {
                    device.key(key)
                }
                device.await(By.res(EDITOR).text("@bencha"))
            }
            val suggestionRow = By.res("autocomplete_item_\\d+".toPattern()).clickable(true).enabled(true)
            val nick = By.text("benchalice")
            val pick =
                awaitValue("visible benchalice autocomplete suggestion") {
                    device
                        .findObjects(suggestionRow)
                        .firstOrNull { it.text == "benchalice" || it.hasObject(nick) }
                        ?.visibleCenter
                }
            timings.observe("nickPickToCompletedText") {
                device.tap(pick)
                device.await(By.res(EDITOR).text("benchalice: "))
            }
        }

    @Test
    fun replaceMultilineThenEdit() =
        profile(setup = { device.prepareEditor() }) { timings ->
            val text =
                (1..12).joinToString("\n") { "Line $it: This draft has enough text to wrap at normal phone widths." } +
                    "\nEdit the final lin"
            val field = device.await(editor)
            // Accessibility ACTION_SET_TEXT, not paste, keyboard input, or clipboard latency.
            timings.observe("accessibilityReplaceToMultilineText") {
                field.text = text
                device.await(By.res(EDITOR).text(text))
            }
            device.key(KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_CTRL_ON)
            timings.observe("multilineKeyToEditorText") {
                device.key(KeyEvent.KEYCODE_E)
                device.await(By.res(EDITOR).text("${text}e"))
            }
        }

    @Test
    fun openEmbeddedReplyParent() {
        lateinit var preview: Point
        val parent = "chat_message_bench-a-parent"
        val child = "chat_message_bench-a-reply"
        profile(setup = {
            device.openChat(ROOM_A, ANCHOR_A, ANCHOR_B, newest = true)
            device.await(rowWithBody("embedded reply body", By.res(child)))
            val selector = By.res("chat_reply_preview").hasAncestor(By.res(child)).clickable(true)
            preview =
                awaitValue("resolved embedded parent preview") {
                    device.findObject(selector)?.takeIf { it.text?.contains("embedded parent body") == true || it.hasObject(By.text("embedded parent body")) }
                }.visibleCenter
            check(!device.hasObject(By.res(parent))) { "fixture parent must start outside the newest viewport" }
        }) { timings ->
            timings.observe("replyPreviewTapToParentRow") {
                device.tap(preview)
                device.await(rowWithBody("embedded parent body", By.res(parent)))
            }
            device.settleWindow(requireStableScreenshot = true)
            device.await(By.res(parent))
        }
    }

    @Test
    fun sendToFailedControls() {
        lateinit var payload: String
        lateinit var send: Point
        profile(setup = {
            device.openLatestFailureRoom()
            payload = failPayload()
            send = device.stageSend(payload)
        }) { timings ->
            timings.observe("sendTapToFailedControls") {
                device.tap(send)
                device.failedControl(payload, retryControls)
                device.failedControl(payload, deleteControls)
            }
        }
    }

    @Test
    fun retryFailedSend() {
        lateinit var payload: String
        lateinit var localTag: String
        lateinit var retry: Point
        lateinit var deliveredTag: String
        profile(setup = {
            device.openLatestFailureRoom()
            payload = failPayload()
            deliveredTag = retryEchoTag(payload)
            device.failSend(payload)
            localTag = device.await(rowWithBody(payload, By.res(localMessageTag))).resourceName
            retry = device.failedControl(payload, retryControls).visibleCenter
        }) { timings ->
            timings.observe<UiObject2>("retryTapToPendingRow") {
                device.tap(retry)
                awaitValue("same local row pending after Retry") {
                    device
                        .findObjects(rowWithBody(payload))
                        .singleOrNull()
                        ?.takeIf { it.resourceName == localTag && it.hasObject(By.desc("Sending…")) }
                }
            }
            device.await(rowWithBody(payload, By.res(deliveredTag)))
            device.settleWindow()
            check(device.findObjects(rowWithBody(payload)).singleOrNull()?.resourceName == deliveredTag) {
                "retry must settle to a single promoted row for $payload"
            }
            check(!device.hasObject(newestFab)) { "retry confirmation moved a following reader off the newest edge" }
        }
    }

    @Test
    fun deleteFailedSend() {
        lateinit var survivor: String
        lateinit var payload: String
        lateinit var delete: Point
        profile(setup = {
            device.openLatestFailureRoom()
            survivor = failPayload()
            payload = failPayload()
            device.failSend(survivor)
            device.failSend(payload)
            device.await(rowWithBody(survivor))
            delete = device.failedControl(payload, deleteControls).visibleCenter
        }) { timings ->
            val confirmButton = By.res("chat_failed_delete_confirm")
            val removed = rowWithBody(payload)
            timings.observe("deleteTapToConfirmation") {
                device.tap(delete)
                device.await(confirmButton)
            }
            device.await(removed)
            val confirm = device.await(confirmButton).visibleCenter
            timings.observe("confirmTapToRowRemoval") {
                device.tap(confirm)
                awaitValue("failed row removed after confirmation") {
                    true.takeIf {
                        device.hasObject(By.res(TIMELINE)) && !device.hasObject(confirmButton) && !device.hasObject(removed)
                    }
                }
            }
            device.settleWindow()
            check(!device.hasObject(removed)) { "deleted row reappeared after the timeline settled" }
            device.failedControl(survivor, deleteControls)
        }
    }

    private fun profile(
        setup: MacrobenchmarkScope.() -> Unit,
        measure: MacrobenchmarkScope.(Observations) -> Unit,
    ) {
        val mode = InstrumentationRegistry.getArguments().getString("compilationMode") ?: "baseline"
        val compilation =
            when (mode) {
                "baseline" -> CompilationMode.Partial(BaselineProfileMode.UseIfAvailable)
                "aot" -> CompilationMode.Full()
                else -> error("compilationMode must be baseline or aot")
            }
        val timings = Observations()
        val configurator = Configurator.getInstance()
        val idleTimeout = configurator.waitForIdleTimeout
        try {
            rule.measureRepeated(
                packageName = TARGET_PACKAGE,
                metrics = listOf(FrameTimingMetric()),
                compilationMode = compilation,
                startupMode = null,
                iterations = BenchmarkFixture.intArg("iterations", 10),
                setupBlock = {
                    configurator.setWaitForIdleTimeout(1_000)
                    ensureLocalFixture()
                    setup()
                    device.settleWindow()
                    // Do not add an accessibility-idle wait to each measured observation.
                    configurator.setWaitForIdleTimeout(0)
                },
            ) {
                measure(timings)
                device.settleWindow()
            }
        } finally {
            configurator.setWaitForIdleTimeout(idleTimeout)
        }
        timings.report(mode)
    }
}

private class Observations {
    private val samples = mutableMapOf<String, MutableList<Double>>()

    fun <T> observe(
        phase: String,
        action: () -> T,
    ): T {
        // Test-process trace markers are best-effort; FrameTimingMetric and presented frames come
        // from the target's Perfetto track, not these accessibility timestamps.
        Trace.beginSection("motdBench.$phase")
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            val result = action()
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            samples.getOrPut(phase) { mutableListOf() }.add(elapsedMs)
            return result
        } finally {
            Trace.endSection()
        }
    }

    fun report(mode: String) {
        val lines =
            samples.map { (phase, values) ->
                val sorted = values.sorted()
                val median = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
                val p95 = sorted[ceil(sorted.size * 0.95).toInt() - 1]
                String.format(Locale.ROOT, "%s %s semanticObservedMs: n=%d median=%.2f p95=%.2f", mode, phase, sorted.size, median, p95)
            }
        lines.forEach { Log.i(LOG_TAG, it) }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "\n${lines.joinToString("\n")}\n") },
        )
    }
}

private fun MacrobenchmarkScope.ensureLocalFixture() {
    if (Build.VERSION.SDK_INT >= 33) device.executeShellCommand("pm grant $TARGET_PACKAGE android.permission.POST_NOTIFICATIONS")
    startActivityAndWait { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
    with(device) {
        val forward = By.res("onboarding_forward_button").enabled(true)
        val needsOnboarding =
            awaitValue("onboarding or the chat list") {
                when {
                    hasObject(chatList) -> false
                    hasObject(forward) -> true
                    else -> null
                }
            }
        if (needsOnboarding) {
            click(forward)
            click(By.res("onboarding_choice_network"))
            click(forward)
            setText(By.res("network_host_field"), "127.0.0.1")
            click(By.checkable(true).checked(true))
            setText(By.res("network_port_field"), "17668")
            setText(By.res("network_nick_field"), "motdbench")
            click(forward)
            await(By.text("Authentication"))
            click(forward)
            click(By.text("Connect anyway"))
            tap(onboardingForward("Next"))
            tap(onboardingForward("Finish"))
            await(chatList)
            joinChat(ROOM_A, ANCHOR_A, ANCHOR_B)
            joinChat(ROOM_B, ANCHOR_B, ANCHOR_A)
        }
        await(chatRow(ROOM_A))
        await(chatRow(ROOM_B))
    }
}

private fun UiDevice.onboardingForward(label: String): Point =
    awaitValue("enabled onboarding $label") {
        val forward = By.res("onboarding_forward_button").enabled(true)
        val button =
            findObject(forward.hasDescendant(By.text(label)))
                ?: findObject(By.res("onboarding_forward_button").enabled(true).text(label))
        button?.visibleCenter
    }

private fun chatRow(room: String) = By.res("chatlist_row_\\d+".toPattern()).hasDescendant(By.text(room))

private fun timelineText(text: String) = By.text(text).hasAncestor(By.res(TIMELINE))

private fun rowWithBody(
    text: String,
    selector: BySelector = By.res(messageTag),
): BySelector = selector.hasAncestor(By.res(TIMELINE)).hasDescendant(By.text(text))

private fun UiDevice.awaitAnchor(
    own: String,
    other: String,
) {
    awaitValue("$own in the timeline without stale $other or the entry veil") {
        true.takeIf { hasObject(timelineText(own)) && !hasObject(timelineText(other)) && !hasObject(By.res("chat_entry_veil")) }
    }
}

private fun UiDevice.joinChat(
    room: String,
    own: String,
    other: String,
) {
    click(By.res("chatlist_new_conversation"))
    val input =
        awaitValue("channel input") {
            findObject(By.res("new_conversation_input").enabled(true)) ?: findObject(By.clazz("android.widget.EditText").enabled(true))
        }
    input.text = room
    val submit =
        awaitValue("enabled Join channel submit button") {
            findObject(By.res("new_conversation_submit").enabled(true))
                ?: findObjects(By.text("Join channel").enabled(true)).maxByOrNull { it.visibleCenter.y }
        }
    tap(submit.visibleCenter)
    val needsJump =
        awaitValue("$own or the return-to-newest action after entry") {
            when {
                hasObject(By.res("chat_entry_veil")) -> null
                hasObject(timelineText(own)) -> false
                hasObject(newestFab) -> true
                else -> null
            }
        }
    if (needsJump) jumpToNewestIfNeeded()
    awaitAnchor(own, other)
    backToList()
}

private fun UiDevice.openChat(
    room: String,
    own: String,
    other: String,
    newest: Boolean = false,
) {
    click(chatRow(room))
    if (newest) {
        awaitValue("anchor or return-to-newest action") {
            true.takeIf { hasObject(timelineText(own)) || hasObject(newestFab) }
        }
        jumpToNewestIfNeeded()
    }
    awaitAnchor(own, other)
}

private fun UiDevice.openLatestFailureRoom() {
    click(chatRow(ROOM_A))
    // Earlier iterations leave real rows in the timeline and can push the original seed offscreen.
    awaitValue("$ROOM_A chat title") {
        findObject(By.res("chat_title").text(ROOM_A)) ?: findObject(By.text(ROOM_A).hasAncestor(By.res("chat_title")))
    }
    awaitValue("failure fixture timeline after entry") {
        true.takeIf { hasObject(By.res(TIMELINE)) && !hasObject(By.res("chat_entry_veil")) }
    }
    jumpToNewestIfNeeded()
}

private fun UiDevice.jumpToNewestIfNeeded() {
    findObject(newestFab)?.longClick() ?: return
    awaitValue("newest edge after the bottom jump") { true.takeUnless { hasObject(newestFab) } }
}

private fun UiDevice.backToList() {
    click(By.desc("Back").hasAncestor(By.res("chat_top_app_bar")))
    awaitValue("chat list after leaving the timeline") { true.takeIf { hasObject(chatList) && !hasObject(By.res(TIMELINE)) } }
}

private fun imeIsVisible(): Boolean {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    return automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
}

private fun UiDevice.prepareEditor() {
    openChat(ROOM_A, ANCHOR_A, ANCHOR_B, newest = true)
    click(editor)
    awaitValue("focused editor and visible soft keyboard") { true.takeIf { hasObject(By.res(EDITOR).focused(true)) && imeIsVisible() } }
    await(editor).text = ""
}

private fun UiDevice.stageSend(payload: String): Point {
    setText(editor, payload)
    settleWindow()
    val send = By.res("chat_composer_send").enabled(true)
    return awaitValue("settled Send button") { findObject(send)?.visibleCenter }
}

private fun UiDevice.failSend(payload: String) {
    tap(stageSend(payload))
    failedControl(payload, deleteControls)
}

private fun UiDevice.failedControl(
    payload: String,
    selectors: List<BySelector>,
): UiObject2 =
    awaitValue("failed control $selectors for $payload") {
        val body = findObject(timelineText(payload)) ?: return@awaitValue null
        val bottom = body.visibleBounds.bottom
        // Failure actions are siblings, not children of the message-tagged bubble. The unique
        // payload is newest; take the nearest enabled failure action below that body.
        val timeline = findObject(By.res(TIMELINE)) ?: return@awaitValue null
        selectors.firstNotNullOfOrNull { selector ->
            timeline
                .findObjects(selector)
                .filter { it.isEnabled && it.visibleCenter.y >= bottom }
                .minByOrNull { it.visibleCenter.y }
        }
    }

private fun failPayload() = "0 bench fail ${UUID.randomUUID().toString().take(8)}"

private fun retryEchoTag(payload: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
    return "chat_message_bench-retry-${digest.take(8).joinToString("") { "%02x".format(it) }}"
}

private fun UiDevice.settleWindow(requireStableScreenshot: Boolean = false) {
    val stable =
        waitForStableInActiveWindow(
            stableTimeoutMs = 3_000,
            stableIntervalMs = 200,
            stablePollIntervalMs = 50,
            requireStableScreenshot = requireStableScreenshot,
        )
    check(!stable.isTimeout) { "accessibility window did not settle" }
}

private fun UiDevice.key(
    code: Int,
    meta: Int = 0,
) {
    check(pressKeyCode(code, meta)) { "could not inject key $code" }
}

private fun UiDevice.tap(point: Point) {
    check(click(point.x, point.y)) { "could not inject tap at $point" }
}

private fun UiDevice.click(selector: BySelector) = tap(awaitValue(selector.toString()) { findObject(selector)?.visibleCenter })

private fun UiDevice.setText(
    selector: BySelector,
    text: String,
) {
    awaitValue(selector.toString()) {
        findObject(selector)?.let {
            it.text = text
        }
    }
}

private fun UiDevice.await(selector: BySelector): UiObject2 = awaitValue(selector.toString()) { findObject(selector) }

private inline fun <T : Any> awaitValue(
    description: String,
    find: () -> T?,
): T {
    val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
    do {
        try {
            find()?.let { return it }
        } catch (_: StaleObjectException) {
            // Compose replaced a node during observation; re-query the window on the next poll.
        }
        SystemClock.sleep(POLL_MS)
    } while (SystemClock.uptimeMillis() < deadline)
    error("Timed out waiting for $description; this is a UI observation, not a frame-present timeout")
}
