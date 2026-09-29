package io.github.trevarj.motd.ui.about

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp")
class AboutDonationsUiTest {
    @get:Rule val composeRule = createComposeRule()

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun donation_buttons_copy_wallets_and_open_paypal() {
        val context = ApplicationProvider.getApplicationContext<Application>()
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
            Triple("about_donate_bitcoin", "bc1quyz4krs97k5d408kupukdu72pnghaahsuattfz", Color.rgb(0xF7, 0x93, 0x1A)),
            Triple("about_donate_ethereum", "0xBD82C33D5812fb0F712f342f0B2b1394988541BD", Color.rgb(0x62, 0x7E, 0xEA)),
            Triple("about_donate_monero", "8462JtvFaUqGEGpsZbToiv22FjSXN3wWrdXU4NPB898aG6zeyxD1xwC8hkVErGHHXnW2XYvmdhd75K3MRdeVizDU1BAENGq", Color.rgb(0xFF, 0x66, 0)),
        ).forEach { (tag, expected, brandColor) ->
            composeRule
                .onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            assertLogoColor(tag, brandColor)
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

        composeRule
            .onNodeWithTag("about_donate_paypal")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertLogoColor("about_donate_paypal", Color.rgb(0, 0x30, 0x87))
        composeRule.runOnIdle {
            val intent = shadowOf(context).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent?.action)
            assertEquals("https://www.paypal.com/donate/?business=tmarjeski%40gmail.com", intent?.data?.toString())
            assertEquals(
                "8462JtvFaUqGEGpsZbToiv22FjSXN3wWrdXU4NPB898aG6zeyxD1xwC8hkVErGHHXnW2XYvmdhd75K3MRdeVizDU1BAENGq",
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString(),
            )
        }
    }

    private fun assertLogoColor(
        tag: String,
        brandColor: Int,
    ) {
        val bitmap = composeRule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("$tag logo is not visible", pixels.any { it == brandColor })
    }
}
