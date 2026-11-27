package io.github.trevarj.motd.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.e2e.robots.BaseRobot
import io.github.trevarj.motd.e2e.robots.ChatListRobot
import io.github.trevarj.motd.e2e.robots.NetworksRobot
import io.github.trevarj.motd.e2e.robots.SettingsRobot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal fun exerciseHostedProfileMetadata(
    compose: ComposeTestRule,
    bootstrap: E2eBootstrap,
    milestones: E2eMilestoneRecorder,
    back: () -> Unit,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val seams = bootstrap.seams
    val connections = seams.connections()
    val robot = BaseRobot(compose)
    val field = "display-name"
    val selfName = "Fixture Friendly"
    val channelName = "Fixture Room"
    val confirmed = instrumentation.targetContext.getString(R.string.profile_editor_confirmed)
    val rejected = instrumentation.targetContext.getString(R.string.profile_editor_rejected)
    val notSet = instrumentation.targetContext.getString(R.string.profile_editor_not_set)
    val readOnly = instrumentation.targetContext.getString(R.string.profile_editor_read_only)

    fun expectText(
        text: String,
        serverValue: Boolean = false,
    ) {
        val textMatcher = hasText(text)
        val matcher = if (serverValue) textMatcher and hasAnyAncestor(hasTestTag("profile_server_value")) else textMatcher
        compose.waitUntil("Visible profile text: $text", 20_000) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(matcher, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    fun openDisplayName() = robot.scrollToAndClick("profile_field_$field")

    fun edit(name: String) {
        robot.awaitTag("profile_value")
        compose.onNode(hasTestTag("profile_value") and hasSetTextAction(), useUnmergedTree = true).performTextReplacement(name)
    }

    fun publish() = robot.scrollToAndClick("profile_publish")

    fun remove() = robot.scrollToAndClick("profile_remove")

    ProfileMetadataFixture(instrumentation.context).use { fixture ->
        val priorPin = runBlocking { seams.certTrust().pinnedFor(fixture.host, fixture.port) }
        val networkId =
            runBlocking {
                seams.networks().addNetwork(
                    NetworkEntity(
                        name = "Hosted Profile Fixture",
                        role = NetworkRole.DIRECT,
                        host = fixture.host,
                        port = fixture.port,
                        nick = fixture.nick,
                        username = fixture.nick,
                        realname = "Hosted Profile Fixture",
                        autoConnect = false,
                    ),
                )
            }
        try {
            val roomId =
                runBlocking {
                    seams.certTrust().pin(fixture.host, fixture.port, fixture.fingerprint)
                    connections.connect(networkId)
                    ConnectionProbe(connections, milestones).awaitReady(networkId, setOf(fixture.cap, "batch"))
                    assertTrue(connections.joinChannel(networkId, fixture.channel.lowercase()))
                    BufferProbe(seams.buffers(), milestones).awaitJoinedChannel(networkId, fixture.channel)
                }
            val client = checkNotNull(connections.clientFor(networkId))

            fun waitForModes(vararg expected: Char) {
                compose.waitUntil("Self PREFIX modes: ${expected.joinToString()}", 10_000) {
                    client.selfChannelModes.value.entries
                        .find { it.key.equals(fixture.channel, ignoreCase = true) }
                        ?.value
                        .orEmpty() == expected.toSet()
                }
            }

            SettingsRobot(compose).apply {
                open()
                networks()
            }
            NetworksRobot(compose).openRoot(networkId)
            robot.scrollToAndClick("network_user_profile")
            robot.awaitTag("screen_user_profile")
            openDisplayName()
            edit(selfName)
            assertEquals(emptyList<ProfileMetadataFixture.Write>(), fixture.writeHistory())
            publish()
            expectText(confirmed)
            repeat(5) { robot.click("settings_back") }

            ChatListRobot(compose).open(roomId)
            robot.click("chat_overflow")
            robot.click("chat_user_profile")
            robot.awaitTag("screen_user_profile")
            openDisplayName()
            expectText(selfName, serverValue = true)
            remove()
            expectText(confirmed)
            expectText(notSet, serverValue = true)
            repeat(2) { robot.click("settings_back") }

            val infoAction = hasTestTag("chat_title") and hasClickAction()
            if (compose.onAllNodes(infoAction, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNode(infoAction, useUnmergedTree = true).performClick()
            } else {
                robot.click("chat_overflow")
                robot.click("chat_compact_details")
            }
            val channelInfoTitle = instrumentation.targetContext.getString(R.string.channelinfo_title)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(channelInfoTitle).fetchSemanticsNodes().isNotEmpty()
            }
            compose
                .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex), useUnmergedTree = true)
                .performScrollToNode(hasTestTag("channel_profile"))
            robot.scrollToAndClick("channel_profile")
            robot.awaitTag("screen_channel_profile")
            openDisplayName()
            expectText(fixture.initialChannelName, serverValue = true)
            expectText(readOnly)
            robot.awaitTagGone("profile_publish")
            fixture.setMode('v', true)
            waitForModes('v')
            expectText(readOnly)
            robot.awaitTagGone("profile_remove")

            fixture.setMode('o', true)
            waitForModes('o', 'v')
            robot.awaitTag("profile_publish")
            fixture.rejectNextWrite()
            edit(channelName)
            publish()
            expectText(rejected)
            expectText(fixture.initialChannelName, serverValue = true)
            publish()
            expectText(confirmed)
            expectText(channelName, serverValue = true)
            fixture.setMode('o', false)
            waitForModes('v')
            expectText(readOnly)
            robot.awaitTagGone("profile_remove")

            fixture.setMode('q', true)
            waitForModes('q', 'v')
            remove()
            expectText(confirmed)
            expectText(notSet, serverValue = true)
            assertEquals(
                listOf(
                    ProfileMetadataFixture.Write(fixture.nick, field, selfName, accepted = true),
                    ProfileMetadataFixture.Write(fixture.nick, field, null, accepted = true),
                    ProfileMetadataFixture.Write(fixture.channel, field, channelName, accepted = false),
                    ProfileMetadataFixture.Write(fixture.channel, field, channelName, accepted = true),
                    ProfileMetadataFixture.Write(fixture.channel, field, null, accepted = true),
                ),
                fixture.writeHistory(),
            )
            repeat(2) { robot.click("settings_back") }
            back() // Channel Info → chat.
            robot.awaitTag("chat_title")
            back() // Chat → chat list, before removing its network.
            robot.awaitTag("chatlist_more")
            milestones.record("ircv3_profile_metadata", "network=$networkId buffer=$roomId writes=5")
        } finally {
            runBlocking {
                connections.disconnect(networkId)
                seams.networks().deleteNetwork(networkId)
                if (priorPin == null) {
                    seams.certTrust().unpin(fixture.host, fixture.port)
                } else {
                    seams.certTrust().pin(fixture.host, fixture.port, priorPin)
                }
            }
        }
    }
}
