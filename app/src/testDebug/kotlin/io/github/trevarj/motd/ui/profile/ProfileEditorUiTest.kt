package io.github.trevarj.motd.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.profile.ProfileAccess
import io.github.trevarj.motd.profile.ProfileEditorState
import io.github.trevarj.motd.profile.ProfileKey
import io.github.trevarj.motd.profile.ProfileTarget
import io.github.trevarj.motd.profile.ProfileWriteResult
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
@Config(qualifiers = "w411dp-h891dp")
class ProfileEditorUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun publishAndRemoveAreExplicitAndDenialPreservesTheDraft() {
        val writes = mutableListOf<Pair<ProfileKey, String?>>()
        compose.setContent {
            MotdTheme {
                var writeState by remember { mutableStateOf(ProfileWriteState()) }
                ProfileEditorContent(
                    target = ProfileTarget.Self(7),
                    state = ProfileEditorState("alice", 7, ProfileAccess.WRITABLE, mapOf(ProfileKey.DISPLAY_NAME to "Original")),
                    writeState = writeState,
                    onBack = {},
                    onOpenNetworkSettings = {},
                    onClearResult = { writeState = ProfileWriteState() },
                    onWrite = { key, value ->
                        writes += key to value
                        writeState = ProfileWriteState(result = ProfileWriteResult.REJECTED)
                    },
                )
            }
        }

        compose.onNodeWithTag("profile_field_display-name").performScrollTo().performClick()
        val value = compose.onNodeWithTag("profile_value")
        value.performScrollTo().performTextReplacement("Unpublished draft")
        compose.runOnIdle { assertEquals(emptyList<Pair<ProfileKey, String?>>(), writes) }
        compose.onNodeWithTag("profile_publish").performScrollTo().performClick()
        compose.onNodeWithText("The server denied the change.").performScrollTo().assertIsDisplayed()
        value.performScrollTo().assertTextContains("Unpublished draft")
        compose.onNodeWithText("Original").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("profile_remove").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(ProfileKey.DISPLAY_NAME to "Unpublished draft", ProfileKey.DISPLAY_NAME to null), writes)
        }
    }

    @Test
    fun ordinaryChannelMemberSeesTheCurrentValueWithoutWriteControls() {
        compose.setContent {
            MotdTheme {
                ProfileEditorContent(
                    target = ProfileTarget.Channel(17),
                    state = ProfileEditorState("#motd", 7, ProfileAccess.READ_ONLY, mapOf(ProfileKey.DISPLAY_NAME to "MOTD Chat")),
                    writeState = ProfileWriteState(),
                    onBack = {},
                    onOpenNetworkSettings = {},
                    onClearResult = {},
                    onWrite = { _, _ -> error("Read-only channel cannot publish or remove values") },
                )
            }
        }

        compose.onNodeWithTag("profile_field_avatar").assertIsDisplayed()
        compose.onNodeWithTag("profile_field_status").assertDoesNotExist()
        compose.onNodeWithTag("profile_field_display-name").performScrollTo().performClick()
        compose.onNodeWithText("MOTD Chat").assertIsDisplayed()
        compose.onNodeWithTag("profile_publish").assertDoesNotExist()
        compose.onNodeWithTag("profile_remove").assertDoesNotExist()
    }
}
