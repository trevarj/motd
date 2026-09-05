package io.github.trevarj.motd

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.ui.components.ReplyMiniBubble
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.NickColorScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ReplyPreviewUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun resolvedReplyPreview_opensItsOriginalMessage() {
        var clicks = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ReplyMiniBubble(
                    reply = ReplyPreviewData(sender = "alice", text = "original"),
                    nickColors =
                        NickColorScheme(
                            enabled = true,
                            palette = NickColorPalette.THEME,
                            overrides = emptyMap(),
                            isDark = false,
                        ),
                    onClick = { clicks++ },
                )
            }
        }

        compose
            .onNodeWithTag("chat_reply_preview")
            .assertHasClickAction()
            .performClick()

        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
