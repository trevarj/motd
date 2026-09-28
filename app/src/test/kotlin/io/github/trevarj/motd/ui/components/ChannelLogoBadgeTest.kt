package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChannelLogoBadgeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun motd_channel_renders_the_bundled_logo_without_matching_other_channels() {
        composeRule.setContent {
            MotdTheme(dynamicColor = false) {
                Row {
                    Avatar(name = "#motd", isChannel = true)
                    Avatar(name = "#MOTD", isChannel = true)
                    Avatar(name = "#motd-help", isChannel = true)
                    Avatar(name = "#notmotd", isChannel = true)
                    Avatar(name = "#general", isChannel = true)
                }
            }
        }

        composeRule.onAllNodesWithTag("motd_channel_logo").assertCountEquals(2)
        composeRule.onAllNodesWithTag("motd_channel_logo")[0].assertIsDisplayed()
    }
}
