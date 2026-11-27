package io.github.trevarj.motd.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.NetworkIgnoreEntity
import io.github.trevarj.motd.data.repo.SUPPRESS_HIGHLIGHT
import io.github.trevarj.motd.data.repo.SUPPRESS_NOTIFICATIONS
import io.github.trevarj.motd.data.repo.SUPPRESS_UNREAD
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp")
class NetworkToolsIgnoreUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun ignoreStartsHardAndSelectiveRequiresAtLeastOneIndependentSignal() {
        val saved = mutableListOf<Triple<String, Boolean, Int>>()
        compose.setContent {
            MotdTheme {
                NetworkToolsContent(
                    state = NetworkToolsUiState(networkId = 1),
                    onBack = {},
                    onAddIgnore = { pattern, dropMessages, flags -> saved += Triple(pattern, dropMessages, flags) },
                )
            }
        }

        val mask = compose.onNodeWithTag("network_tools_ignore_input")
        val mode = compose.onNodeWithTag("network_tools_ignore_selective")
        val save = compose.onNodeWithTag("network_tools_add_ignore")
        mode.assertIsOff()
        compose.onAllNodesWithTag("network_tools_ignore_unread").assertCountEquals(0)
        mask.performScrollTo().performTextInput("alice")
        save.performScrollTo().assertIsEnabled().performClick()

        mask.performScrollTo().performTextInput("alice!*@*")
        mode.performScrollTo().performClick().assertIsOn()
        save.assertIsNotEnabled()

        val unread = compose.onNodeWithTag("network_tools_ignore_unread")
        val highlights = compose.onNodeWithTag("network_tools_ignore_highlights")
        val notifications = compose.onNodeWithTag("network_tools_ignore_notifications")
        unread.performScrollTo().performClick().assertIsOn()
        highlights.assertIsOff()
        notifications.assertIsOff()
        highlights.performScrollTo().performClick()
        notifications.performScrollTo().performClick()
        unread.performScrollTo().performClick().assertIsOff()
        save.performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(Triple("alice", true, 0), Triple("alice!*@*", false, SUPPRESS_HIGHLIGHT or SUPPRESS_NOTIFICATIONS)),
                saved,
            )
        }
        mode.assertIsOff()
    }

    @Test
    fun sameMaskRulesShowTheirOwnModeAndDeleteOnlyTheSelectedRule() {
        val hard = NetworkIgnoreEntity(id = 1, networkId = 1, pattern = "alice!*@*", createdAt = 1)
        val selective = hard.copy(id = 2, enabled = false, dropMessages = false, suppressedSignals = SUPPRESS_UNREAD)
        var deleted: Long? = null
        compose.setContent {
            MotdTheme {
                NetworkToolsContent(
                    state = NetworkToolsUiState(networkId = 1, ignores = listOf(hard, selective)),
                    onBack = {},
                    onDeleteIgnore = { deleted = it },
                )
            }
        }

        compose.onNodeWithText("Hide messages · Enabled").assertExists()
        compose.onNodeWithText("Selective: suppress Unread counts · Disabled").assertExists()
        compose.onNodeWithTag("network_tools_ignore_rule_2").performScrollTo()
        compose.onNodeWithContentDescription("Delete rule for alice!*@* (Selective)").performClick()
        compose.runOnIdle { assertEquals(2L, deleted) }
    }
}
