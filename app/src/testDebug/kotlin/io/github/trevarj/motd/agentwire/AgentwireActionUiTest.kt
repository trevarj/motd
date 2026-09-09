package io.github.trevarj.motd.agentwire

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class AgentwireActionUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun receiptShowsAppliedOutcomeAndOnlyOffersEligibleStatusChecks() {
        var canCheck by mutableStateOf(true)
        var checks = 0
        val receipt =
            AgentwireActionReceipt(
                id = "00000000-0000-4000-8000-000000000001",
                kind = "turn.prompt",
                channel = "#closed-session",
                sentAt = 1_800_000_000_000,
                outcome = "succeeded",
                scope = "opaque",
            )
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AgentwireActionRow(receipt, canCheck = canCheck, onCheck = { checks += 1 })
            }
        }
        compose.onNodeWithText("Action applied", substring = true).assertIsDisplayed()
        compose.onNodeWithText("#closed-session", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Check status").performClick()
        compose.runOnIdle {
            assertEquals(1, checks)
            canCheck = false
        }
        compose.onNodeWithText("Check status").assertDoesNotExist()
    }
}
