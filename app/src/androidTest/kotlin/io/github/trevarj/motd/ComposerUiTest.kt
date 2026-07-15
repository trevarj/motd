package io.github.trevarj.motd

import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import io.github.trevarj.motd.ui.components.Composer
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Rule
import org.junit.Test

class ComposerUiTest {
    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule()

    @Test
    fun emojiPicker_opensAlongsideTheComposerInput() {
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("chat_composer_input_row").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
    }
}
