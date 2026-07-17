package io.github.trevarj.motd

import android.Manifest
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.Locale
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Marks the real-stack, app-owned journeys that form the fast headless acceptance tier. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FastHeadlessE2e

/**
 * Shared driver for the headless journeys.
 *
 * These tests intentionally use the production Activity, database, service graph, TLS transport,
 * and bouncer protocol. The only fixture is the hermetic IRC network supplied through runner
 * arguments. Android Test Orchestrator clears package data between test methods.
 */
abstract class HeadlessE2eDriver {
    protected val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(compose)

    private val args get() = InstrumentationRegistry.getArguments()
    protected val host get() = args.getString("sojuHost") ?: "10.0.2.2"
    protected val port get() = args.getString("sojuPort") ?: "6697"
    protected val user get() = args.getString("sojuUser") ?: "motd"
    protected val password get() = args.getString("sojuPassword") ?: "motdtest"
    protected val nick get() = args.getString("nick") ?: "motdadb"
    protected val channel get() = args.getString("channel") ?: "##motdtest"
    protected val secondNick get() = args.getString("secondNick") ?: "motdadb2"

    @Before
    fun connectToHermeticSoju() {
        waitForText("Welcome to motd")
        clickTag("onboarding_forward_button")
        waitForText("How do you connect?")
        clickTag("onboarding_choice_bouncer")
        clickTag("onboarding_choice_soju")
        clickTag("onboarding_forward_button")

        replaceTag("network_host_field", host)
        replaceTag("network_port_field", port)
        replaceTag("network_nick_field", nick)
        clickTag("onboarding_forward_button")

        replaceTag("bouncer_username_field", user)
        replaceTag("bouncer_password_field", password)
        clickTag("onboarding_forward_button")

        waitForText("Trust this certificate?", timeoutMillis = 30_000)
        clickText("Trust")
        waitForText("Bouncer networks", timeoutMillis = 45_000)
        waitForTextWithConnectionDiagnostics("libera", timeoutMillis = 30_000)
        clickTagPrefix("onboarding_bouncer_switch_")
        clickTag("onboarding_forward_button")
        waitForText("Finish")
        clickTag("onboarding_forward_button")
        waitForDescription("New conversation", timeoutMillis = 30_000)
    }

    protected fun openOrJoinChannel() {
        clickDescription("New conversation")
        waitForTag("new_conversation_sheet")
        replaceTag("new_conversation_input", channel)
        clickTag("new_conversation_submit")
        waitForText(channel, timeoutMillis = 30_000)
        clickText(channel)
        waitForTag("chat_composer_field", timeoutMillis = 30_000)
    }

    protected fun openSettings() {
        clickDescription("Settings")
        waitForText("Settings")
        waitForTag("settings_category_networks")
    }

    protected fun waitForTag(tag: String, timeoutMillis: Long = 15_000) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun waitForTagToDisappear(tag: String, timeoutMillis: Long = 15_000) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    protected fun waitForText(text: String, timeoutMillis: Long = 15_000) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithText(text, substring = true, ignoreCase = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Keep a failed history assertion actionable by reporting the live bouncer capability state. */
    protected fun waitForTextWithConnectionDiagnostics(text: String, timeoutMillis: Long = 15_000) {
        try {
            waitForText(text, timeoutMillis)
        } catch (error: ComposeTimeoutException) {
            val states = buildString {
                compose.activityRule.scenario.onActivity { activity ->
                    append(
                        activity.connectionManager.connectionStates.value.entries.joinToString { (networkId, state) ->
                            "$networkId=$state caps=${activity.connectionManager.clientFor(networkId)?.caps}"
                        },
                    )
                }
            }
            throw AssertionError("Timed out waiting for '$text'. Connection states: $states", error)
        }
    }

    /**
     * Retained messages can precede rows produced by other independently-cleared Orchestrator
     * tests. A LazyColumn exposes only its current viewport to the semantics tree, so looking for
     * an older retained line without scrolling would turn a successful backfill into a false
     * negative. This proves that the imported line is reachable in the timeline.
     */
    protected fun scrollTimelineToTextWithConnectionDiagnostics(
        text: String,
        timeoutMillis: Long = 15_000,
    ) {
        repeat(MAX_HISTORY_SCROLLS) {
            val textIsVisible = compose.onAllNodesWithText(
                text,
                substring = true,
                ignoreCase = true,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
            if (textIsVisible) {
                return
            }
            compose.onNodeWithTag("chat_timeline", useUnmergedTree = true)
                // MessageList is reverseLayout=true, so scrolling toward older (higher-index)
                // rows uses the inverse gesture direction.
                .performTouchInput { swipeDown() }
            compose.waitForIdle()
        }
        waitForTextWithConnectionDiagnostics(text, timeoutMillis)
    }

    protected fun waitForDescription(description: String, timeoutMillis: Long = 15_000) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithContentDescription(
                description,
                substring = true,
                ignoreCase = true,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun clickTag(tag: String) {
        waitForTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    protected fun clickTagPrefix(prefix: String) {
        val matcher = SemanticsMatcher("test tag starts with $prefix") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith(prefix)
        }
        compose.waitUntil(15_000) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(matcher, useUnmergedTree = true).onFirst().performClick()
    }

    protected fun assertTagPrefix(prefix: String) {
        val matcher = SemanticsMatcher("test tag starts with $prefix") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith(prefix)
        }
        compose.waitUntil(15_000) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(matcher, useUnmergedTree = true).onFirst().assertIsDisplayed()
    }

    protected fun clickText(text: String) {
        waitForText(text)
        compose.onAllNodes(
            hasText(text, substring = true, ignoreCase = true).and(hasClickAction()),
        ).onFirst().performClick()
    }

    protected fun clickDescription(description: String) {
        waitForDescription(description)
        compose.onAllNodes(
            hasContentDescription(description, substring = true, ignoreCase = true)
                .and(hasClickAction()),
        ).onFirst().performClick()
    }

    protected fun replaceTag(tag: String, text: String) {
        waitForTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performTextReplacement(text)
    }

    protected fun longClickMessageContaining(text: String) {
        val messageTag = SemanticsMatcher("message row containing $text") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith("chat_message_")
        }
        val row = messageTag.and(hasAnyDescendant(hasText(text, substring = true, ignoreCase = true)))
        compose.waitUntil(15_000) {
            compose.onAllNodes(row, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(row, useUnmergedTree = true).onFirst().performTouchInput { longClick() }
    }

    protected fun assertComposerText(text: String) {
        waitForTag("chat_composer_field")
        compose.onNodeWithTag("chat_composer_field", useUnmergedTree = true).assertTextEquals(text)
    }

    protected fun reconnectActiveNetwork() {
        compose.activityRule.scenario.onActivity { activity ->
            val manager = activity.connectionManager
            val networkId = manager.connectionStates.value.keys.firstOrNull { manager.clientFor(it) != null }
                ?: manager.connectionStates.value.keys.firstOrNull()
                ?: error("no connected network available for reconnect test")
            activity.lifecycleScope.launch {
                manager.disconnect(networkId)
                manager.connect(networkId)
            }
        }
    }

    protected fun scrollToAndClickText(text: String) {
        waitForText(text)
        compose.onAllNodes(
            hasText(text, substring = true, ignoreCase = true).and(hasClickAction()),
        ).onFirst().performScrollTo().performClick()
    }

    protected fun scrollToAndClickTag(tag: String) {
        waitForTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()
    }

    protected fun scrollToAndAssertTag(tag: String) {
        waitForTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    protected fun assertTag(tag: String) {
        waitForTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
    }

    /** Assert the accessibility state on the concrete message row containing [text]. */
    protected fun assertMentionHighlight(text: String, highlighted: Boolean) {
        val messageTag = SemanticsMatcher("message row") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith("chat_message_")
        }
        val row = messageTag.and(
            hasAnyDescendant(hasText(text, substring = true, ignoreCase = true)),
        )
        compose.waitUntil(15_000) {
            compose.onAllNodes(row, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val mentionsYou = SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            "Mentions you",
        )
        val matches = compose.onAllNodes(
            row.and(mentionsYou),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        if (highlighted && matches.isEmpty()) {
            throw AssertionError("Expected '$text' to expose the Mentions you state")
        }
        if (!highlighted && matches.isNotEmpty()) {
            throw AssertionError("Expected '$text' not to expose the Mentions you state")
        }
    }

    protected fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }

    protected fun returnToSettingsRoot() {
        repeat(3) {
            if (compose.onAllNodesWithTag("settings_category_networks", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            ) {
                return
            }
            back()
            compose.waitForIdle()
        }
        waitForTag("settings_category_networks")
    }

    protected fun uniqueMessage(prefix: String): String = String.format(
        Locale.US,
        "%s-%x",
        prefix,
        System.nanoTime(),
    )

    private companion object {
        const val MAX_HISTORY_SCROLLS = 12
    }
}

@RunWith(AndroidJUnit4::class)
@FastHeadlessE2e
class OnboardingHeadlessE2eTest : HeadlessE2eDriver() {
    @Test
    fun connectsImportsNetworkAndAutomaticallyBackfillsRetainedHistory() {
        waitForDescription("Open navigation drawer")
        clickDescription("Open navigation drawer")
        assertTagPrefix("drawer_network_icon_")
        back()
        waitForDescription("New conversation")
        // The retained channel must arrive via the automatic CHATHISTORY TARGETS/LATEST pass;
        // do not create or join it manually before making this assertion.
        waitForText(channel, timeoutMillis = 30_000)
        clickText(channel)
        waitForTag("chat_composer_field", timeoutMillis = 30_000)
        scrollTimelineToTextWithConnectionDiagnostics("$nick: welcome", timeoutMillis = 30_000)
        assertMentionHighlight("$nick: welcome", highlighted = true)
        scrollTimelineToTextWithConnectionDiagnostics("hello, this is a seeded plain line", timeoutMillis = 30_000)
        assertMentionHighlight("hello, this is a seeded plain line", highlighted = false)
    }
}

@RunWith(AndroidJUnit4::class)
@FastHeadlessE2e
class ChatHeadlessE2eTest : HeadlessE2eDriver() {
    @Test
    fun joinsSendsSearchesAndExercisesComposer() {
        openOrJoinChannel()
        val message = uniqueMessage("headless")
        replaceTag("chat_composer_field", message)
        clickTag("chat_composer_send")
        waitForText(message, timeoutMillis = 20_000)

        replaceTag("chat_composer_field", ":smile")
        assertTag("chat_composer_emoji_autocomplete")
        waitForText("smile")

        replaceTag("chat_composer_field", "/")
        waitForText("/join")
        replaceTag("chat_composer_field", "/me waves")
        clickTag("chat_composer_send")
        waitForText("* $nick waves", timeoutMillis = 20_000)
        assertTag("chat_action_row")

        clickDescription("Search")
        waitForText("Search messages")
        compose.onAllNodes(
            hasText("Search messages", substring = true, ignoreCase = true)
                .and(hasSetTextAction()),
        ).onFirst().performTextReplacement(message)
        waitForText(message, timeoutMillis = 20_000)
    }

    @Test
    fun repliesReactsAndRestoresIndependentChannelAndQueryDrafts() {
        openOrJoinChannel()
        val parent = uniqueMessage("reply-parent")
        replaceTag("chat_composer_field", parent)
        clickTag("chat_composer_send")
        waitForText(parent, timeoutMillis = 20_000)

        longClickMessageContaining(parent)
        waitForTag("message_action_sheet")
        clickText("Reply")
        replaceTag("chat_composer_field", "reply-$parent")
        clickTag("chat_composer_send")
        waitForText("reply-$parent", timeoutMillis = 20_000)

        longClickMessageContaining(parent)
        waitForTag("message_action_sheet")
        clickText("👍")
        // Recovery can join one serialized 35-second CHATHISTORY request before issuing up to two
        // fresh requests that promote this row's msgid. Keep the E2E allowance beyond that contract.
        waitForTag("chat_reaction_chip_👍", timeoutMillis = 115_000)
        clickTag("chat_reaction_chip_👍")
        waitForTagToDisappear("chat_reaction_chip_👍", timeoutMillis = 20_000)

        replaceTag("chat_composer_field", "channel draft")
        back()
        clickDescription("New conversation")
        waitForTag("new_conversation_sheet")
        clickTag("new_conversation_message_tab")
        replaceTag("new_conversation_input", secondNick)
        clickTag("new_conversation_submit")
        waitForTag("chat_composer_field")
        replaceTag("chat_composer_field", "query draft")
        back()

        waitForText(channel)
        clickText(channel)
        assertComposerText("channel draft")
        back()

        waitForText(secondNick)
        clickText(secondNick)
        assertComposerText("query draft")
    }

    @Test
    fun reconnectKeepsExistingMessageUniqueAndAllowsNewSend() {
        openOrJoinChannel()
        val before = uniqueMessage("before-reconnect")
        replaceTag("chat_composer_field", before)
        clickTag("chat_composer_send")
        waitForTextWithConnectionDiagnostics(before, timeoutMillis = 20_000)

        reconnectActiveNetwork()
        waitForTextWithConnectionDiagnostics(before, timeoutMillis = 30_000)

        val after = uniqueMessage("after-reconnect")
        replaceTag("chat_composer_field", after)
        clickTag("chat_composer_send")
        waitForTextWithConnectionDiagnostics(after, timeoutMillis = 30_000)
        assertEquals(
            1,
            compose.onAllNodesWithText(before, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
    }
}

@RunWith(AndroidJUnit4::class)
@FastHeadlessE2e
class ChannelHeadlessE2eTest : HeadlessE2eDriver() {
    @Test
    fun opensChannelInfoMemberPmDetailsAndLeaveConfirmation() {
        openOrJoinChannel()
        clickText(channel)
        waitForText("Channel info")
        waitForText("Mute")
        waitForText("Pin")
        clickText("Leave")
        waitForText("Leave channel?")
        clickText("Cancel")

        waitForTag("channelinfo_member_$secondNick", timeoutMillis = 30_000)
        clickTag("channelinfo_member_$secondNick")
        waitForTag("nick_sheet")
        waitForText("Message")
        clickText("Message")
        waitForTag("chat_title")
        clickTag("chat_title")
        waitForTag("nick_sheet")
        waitForText("Add to friends")
        waitForText("Add to fools")
    }
}

@RunWith(AndroidJUnit4::class)
@FastHeadlessE2e
class SettingsAndBouncerHeadlessE2eTest : HeadlessE2eDriver() {
    @Test
    fun navigatesSettingsPresentationAndBouncerPanels() {
        openSettings()
        clickTag("settings_category_appearance")
        waitForText("Appearance")
        scrollToAndAssertTag("settings_avatar_sprite_preview")
        scrollToAndClickTag("settings_avatar_style_irc_sprite")
        scrollToAndClickTag("settings_theme_picker")
        waitForTag("settings_theme_sheet")
        scrollToAndClickText("AMOLED (true black)")
        returnToSettingsRoot()
        clickTag("settings_category_chat")
        waitForText("Show join/part messages")
        returnToSettingsRoot()

        clickTag("settings_category_networks")
        waitForText("Bouncers")
        clickTagPrefix("settings_network_row_")
        waitForText("Bouncer networks", timeoutMillis = 30_000)
        scrollToAndClickText("Bouncer networks")
        waitForText("Soju control center", timeoutMillis = 30_000)
        waitForText("BouncerServ commands verified", timeoutMillis = 30_000)
        clickTag("bouncer_tab_channels")
        assertTag("bouncer_channels_panel")
        clickTag("bouncer_tab_account")
        assertTag("bouncer_account_panel")
        clickTag("bouncer_tab_console")
        assertTag("bouncer_console_panel")
    }
}
