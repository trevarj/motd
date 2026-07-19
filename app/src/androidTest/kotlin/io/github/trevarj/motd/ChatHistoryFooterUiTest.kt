package io.github.trevarj.motd

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.ui.chat.CHAT_HISTORY_RETRY_TAG
import io.github.trevarj.motd.ui.chat.ChatHistoryFooter
import io.github.trevarj.motd.ui.chat.ChatHistoryUiState
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatHistoryFooterUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun errorRetryHasStableTagAndMinimumTouchHeight() {
        var retries = 0
        compose.setContent {
            MotdTheme {
                ChatHistoryFooter(ChatHistoryUiState.Error) { retries++ }
            }
        }

        compose.onNodeWithTag(CHAT_HISTORY_RETRY_TAG)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, retries)
    }
}
