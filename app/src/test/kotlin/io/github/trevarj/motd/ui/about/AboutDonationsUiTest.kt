package io.github.trevarj.motd.ui.about

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp")
class AboutDonationsUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun donation_buttons_copy_exact_published_destinations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        composeRule.setContent {
            MotdTheme(dynamicColor = false) {
                AboutContent(
                    state = AboutDiagnosticsUiState(),
                    onBack = {},
                    onDiagnosticLoggingChanged = {},
                    onExportDiagnostics = {},
                )
            }
        }

        listOf(
            "about_donate_bitcoin" to "bc1quyz4krs97k5d408kupukdu72pnghaahsuattfz",
            "about_donate_ethereum" to "0xBD82C33D5812fb0F712f342f0B2b1394988541BD",
            "about_donate_monero" to "8462JtvFaUqGEGpsZbToiv22FjSXN3wWrdXU4NPB898aG6zeyxD1xwC8hkVErGHHXnW2XYvmdhd75K3MRdeVizDU1BAENGq",
            "about_donate_paypal" to "tmarjeski@gmail.com",
        ).forEach { (tag, expected) ->
            composeRule
                .onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            composeRule.runOnIdle {
                assertEquals(
                    expected,
                    clipboard.primaryClip
                        ?.getItemAt(0)
                        ?.text
                        ?.toString(),
                )
            }
        }
    }
}
