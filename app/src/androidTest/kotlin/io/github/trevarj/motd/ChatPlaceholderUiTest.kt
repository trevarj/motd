package io.github.trevarj.motd

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.ui.chat.MessagePlaceholderRow
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Rule
import org.junit.Test

class ChatPlaceholderUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pagingPlaceholderReservesNonzeroRowHeight() {
        compose.setContent {
            MotdTheme {
                Box(Modifier.testTag("placeholder_container")) {
                    MessagePlaceholderRow()
                }
            }
        }

        compose.onNodeWithTag("placeholder_container").assertHeightIsAtLeast(48.dp)
    }
}
