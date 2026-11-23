package io.github.trevarj.motd.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp")
class DccSendReviewDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun reviewShowsPreparedFileAndTargetAndOnlySendConfirms() {
        val showing = mutableStateOf(true)
        val actions = mutableListOf<String>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                if (showing.value) {
                    DccSendReviewDialog(
                        filename = "prepared-fixture.png",
                        sizeBytes = 12_345L,
                        peerNick = "fixture-peer",
                        networkName = "Fixture Child Network",
                        onConfirm = {
                            actions += "send"
                            showing.value = false
                        },
                        onDismiss = {
                            actions += "dismiss"
                            showing.value = false
                        },
                    )
                }
            }
        }

        compose.onNodeWithTag("chat_dcc_send_review_file").assertIsDisplayed().assertTextEquals("File: prepared-fixture.png")
        compose.onNodeWithTag("chat_dcc_send_review_size").assertIsDisplayed().assertTextEquals("Size in bytes: 12345")
        compose.onNodeWithTag("chat_dcc_send_review_peer").assertIsDisplayed().assertTextEquals("Recipient: fixture-peer")
        compose.onNodeWithTag("chat_dcc_send_review_network").assertIsDisplayed().assertTextEquals("Network: Fixture Child Network")
        compose
            .onNodeWithTag("chat_dcc_send_review_disclosure")
            .assertIsDisplayed()
            .assertTextEquals("Direct file transfers use unencrypted TCP and expose your IP address to the recipient.")
        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }

        compose.onNodeWithTag("chat_dcc_send_review_cancel").assertTextEquals("Cancel").performClick()
        compose.onNodeWithTag("chat_dcc_send_review").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("dismiss"), actions)
            showing.value = true
        }

        compose.onNodeWithTag("chat_dcc_send_review_confirm").assertTextEquals("Send file").performClick()
        compose.onNodeWithTag("chat_dcc_send_review").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("dismiss", "send"), actions) }
    }
}
