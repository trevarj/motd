package io.github.trevarj.motd.ui.saved

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.R
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.saved.SavedMessage
import io.github.trevarj.motd.data.saved.SavedMessageSource
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedMessagesScreenTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun displaysEmptyPreviewsAndRedactionsAndUsesCanonicalIdsForActions() {
        val context = RuntimeEnvironment.getApplication()
        val readable =
            SavedMessage(
                id = 41L,
                sender = "Anna",
                text = "Saved preview https://example.com",
                savedAt = 1_701_000_000_000L,
                serverTime = 1_700_000_000_000L,
                networkName = "Example IRC",
                conversationName = "#motd",
            )
        val redacted = readable.copy(id = 42L, text = null, conversationName = "#help")
        var messages by mutableStateOf(emptyList<SavedMessage>())
        var removedId: Long? = null
        var resolvedId: Long? = null
        var opened: SavedMessageSource? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                SavedMessagesContent(
                    messages = messages,
                    onBack = {},
                    onOpenSource = { opened = it },
                    resolveSource = {
                        resolvedId = it
                        null
                    },
                    onRemove = { id ->
                        removedId = id
                        messages = messages.filterNot { it.id == id }
                    },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.saved_messages_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.saved_messages_empty_message)).assertIsDisplayed()
        compose.runOnIdle { messages = listOf(readable, redacted) }
        compose.onNodeWithTag("saved_messages_empty").assertDoesNotExist()
        compose.onNodeWithText(readable.text.orEmpty(), useUnmergedTree = true).assertIsDisplayed()
        compose
            .onNodeWithText(context.getString(R.string.saved_messages_conversation, redacted.networkName, redacted.conversationName))
            .assertExists()
        compose.onNodeWithText(context.getString(R.string.saved_messages_redacted), useUnmergedTree = true).assertIsDisplayed()
        compose
            .onNodeWithTag("saved_message_remove_41")
            .assertContentDescriptionEquals(context.getString(R.string.saved_messages_remove))
            .performClick()
        compose.onNodeWithTag("saved_message_41").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(41L, removedId)
            assertNull(resolvedId)
        }

        compose.onNodeWithTag("saved_message_42").performClick()
        compose.onNodeWithText(context.getString(R.string.saved_messages_source_unavailable)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(42L, resolvedId)
            assertNull(opened)
        }
    }
}
