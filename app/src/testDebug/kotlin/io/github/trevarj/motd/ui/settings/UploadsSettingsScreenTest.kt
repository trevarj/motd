package io.github.trevarj.motd.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.attachment.AVAILABLE_ATTACHMENT_BACKENDS
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.forBackend
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UploadsSettingsScreenTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    private var config by mutableStateOf(PasteBackendConfig())
    private var dark by mutableStateOf(false)
    private val opened = mutableListOf<String>()

    private fun render() {
        compose.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            opened += uri
                        }
                    },
            ) {
                MotdTheme(themePreset = if (dark) ColorThemePreset.DARK else ColorThemePreset.LIGHT, dynamicColor = false) {
                    SettingsScaffold(
                        title =
                            androidx.compose.ui.res
                                .stringResource(R.string.settings_uploads),
                        onBack = {},
                        modifier = Modifier.testTag("screen_uploads_settings"),
                    ) {
                        UploadsSettingsContent(config = config, onUpdate = { config = it(config) })
                    }
                }
            }
        }
    }

    private fun select(backend: AttachmentBackend) {
        compose.onNodeWithTag("settings_upload_provider").performScrollTo().performClick()
        val tag = "settings_upload_backend_${backend.name.lowercase()}"
        compose.onNodeWithTag("settings_upload_provider_sheet_options").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag("settings_upload_provider_sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(backend, config.backend) }
    }

    @Test
    fun providerPickerLinksOpenHomepagesWithoutChangingConfiguration() {
        render()
        val sites =
            listOf(
                AttachmentBackend.CRAFTERBIN to "https://crafterbin.glennstack.dev",
                AttachmentBackend.X0_AT to "https://x0.at",
                AttachmentBackend.CNET to "https://paste.c-net.org",
                AttachmentBackend.UGUU to "https://uguu.se/",
                AttachmentBackend.LITTERBOX to "https://litterbox.catbox.moe/",
                AttachmentBackend.CATBOX to "https://catbox.moe/",
                AttachmentBackend.SOJU_FILEHOST to "https://soju.im",
                AttachmentBackend.TERMBIN to "https://termbin.com/",
            )
        for ((backend, site) in sites) {
            select(backend)
            val before = config
            compose.onNodeWithTag("settings_upload_guidance").performScrollTo().assertIsDisplayed()
            compose
                .onNodeWithTag("settings_upload_website")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.runOnIdle {
                assertEquals(site, opened.last())
                assertEquals(before, config)
            }
            if (backend == AttachmentBackend.SOJU_FILEHOST) {
                compose.onNodeWithText("About Soju").assertIsDisplayed()
            }
        }
        compose.onNodeWithTag("settings_upload_provider").performScrollTo().performClick()
        compose.onNodeWithTag("settings_upload_backend_zero_x_zero").assertDoesNotExist()
    }

    @Test
    fun customEndpointLinksAndPasswordVisibilityPreserveCredentialBinding() {
        config =
            PasteBackendConfig().forBackend(AttachmentBackend.CUSTOM_0X0).copy(
                endpoint = "https://files.example/upload",
                customEndpoint = "https://files.example/upload",
                username = "alice",
                password = "secret",
            )
        render()
        val endpoint = compose.onNodeWithTag("settings_upload_custom_endpoint")
        val username = compose.onNodeWithTag("settings_upload_custom_username")
        val password = compose.onNodeWithTag("settings_upload_custom_password")

        fun assertRenderedPassword(expected: String) {
            val layouts = mutableListOf<TextLayoutResult>()
            password.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
            assertEquals(
                expected,
                layouts
                    .single()
                    .layoutInput.text.text,
            )
        }
        password.performScrollTo()
        assertRenderedPassword("•".repeat("secret".length))
        compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        password.assertEditableText("secret")
        assertRenderedPassword("secret")
        compose.onNodeWithContentDescription("Hide password").performClick()
        assertRenderedPassword("•".repeat("secret".length))
        compose.onNodeWithTag("settings_upload_website").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("https://files.example/upload"), opened) }
        compose.onNodeWithText("Open endpoint").assertIsDisplayed()

        for (invalid in listOf("http://files.example/upload", "https://alice:secret@files.example/upload", "not a URL")) {
            endpoint.performScrollTo().performTextReplacement(invalid)
            compose.onNodeWithTag("settings_upload_website").assertDoesNotExist()
            endpoint.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            compose.runOnIdle {
                assertEquals("https://files.example/upload", config.endpoint)
                assertEquals("alice", config.username)
                assertEquals("secret", config.password)
                assertEquals(1, opened.size)
            }
        }

        endpoint.performTextReplacement("https://FILES.example:443/other")
        compose.runOnIdle {
            assertEquals("alice", config.username)
            assertEquals("secret", config.password)
        }
        endpoint.performTextReplacement("https://elsewhere.example/upload")
        compose.runOnIdle {
            assertEquals("", config.username)
            assertEquals("", config.password)
        }
        username.performScrollTo().assertEditableText("").performTextReplacement("bob")
        password.performScrollTo().performTextReplacement("new secret")
        endpoint.performScrollTo().performTextClearance()
        compose.onNodeWithTag("settings_upload_website").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("", config.endpoint)
            assertEquals("", config.customEndpoint)
            assertEquals("", config.username)
            assertEquals("", config.password)
        }
        username.performScrollTo().assertEditableText("")
    }

    @Test
    fun sizeDraftCanBeClearedReplacedAndResynchronizesForProviderCaps() {
        render()
        val size = compose.onNodeWithTag("settings_upload_size_limit")
        val error = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
        size.performScrollTo().performTextClearance()
        size.assertEditableText("").assert(error)
        compose.runOnIdle { assertEquals(25L * MIB, config.sizeLimitBytes) }
        for (invalid in listOf("0", "26", "-1", "abc", "9223372036854775808")) {
            size.performTextReplacement(invalid)
            size.assertEditableText(invalid).assert(error)
            compose.runOnIdle { assertEquals(25L * MIB, config.sizeLimitBytes) }
        }
        size.performTextReplacement("1")
        size.assertEditableText("1").assert(error.not())
        compose.runOnIdle { assertEquals(MIB, config.sizeLimitBytes) }
        size.performTextReplacement("12")
        size.assertEditableText("12").assert(error.not())
        compose.runOnIdle { assertEquals(12L * MIB, config.sizeLimitBytes) }
        select(AttachmentBackend.X0_AT)
        size.performScrollTo().performTextReplacement("1024")
        size.assert(error.not())
        compose.runOnIdle { assertEquals(1024L * MIB, config.sizeLimitBytes) }
        size.performTextReplacement("1025")
        size.assertEditableText("1025").assert(error)
        select(AttachmentBackend.CUSTOM_0X0)
        size.performScrollTo().assertEditableText("512").assert(error.not())
        size.performTextReplacement("513")
        size.assert(error)
        select(AttachmentBackend.CRAFTERBIN)
        size.performScrollTo().assertEditableText("25").assert(error.not())
        size.performTextClearance()
        compose.runOnIdle { config = config.copy(sizeLimitBytes = 7L * MIB) }
        size.assertEditableText("7").assert(error.not())
    }

    @Test
    fun invalidConfiguredCredentialsNeverBecomeBrowserLinks() {
        config =
            PasteBackendConfig().forBackend(AttachmentBackend.CUSTOM_0X0).copy(
                endpoint = "https://user:secret@files.example/upload",
                customEndpoint = "https://user:secret@files.example/upload",
            )
        render()
        compose.onNodeWithTag("settings_upload_website").assertDoesNotExist()
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
    }

    @Test
    @Config(qualifiers = "w412dp-h900dp")
    fun lightAndDarkProviderPagesKeepActionsReachable() {
        exerciseProviderPages()
    }

    @Test
    @Config(qualifiers = "w320dp-h900dp", fontScale = 1.5f)
    fun narrowEnlargedFontPagesKeepActionsReachable() {
        exerciseProviderPages()
    }

    private fun exerciseProviderPages() {
        render()
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark = isDark }
            for (backend in AVAILABLE_ATTACHMENT_BACKENDS) {
                select(backend)
                if (backend == AttachmentBackend.CUSTOM_0X0) {
                    compose.onNodeWithTag("settings_upload_custom_endpoint").performScrollTo().performTextReplacement("https://uploads.example/upload")
                }
                val website = compose.onNodeWithTag("settings_upload_website")
                website
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertTouchHeightIsEqualTo(48.dp)
                    .assertWidthIsAtLeast(48.dp)
                compose.onNodeWithTag("settings_upload_provider").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("settings_upload_size_limit").performScrollTo().assertIsDisplayed()
                if (backend == AttachmentBackend.CUSTOM_0X0) {
                    compose
                        .onNodeWithContentDescription("Show password")
                        .performScrollTo()
                        .assertIsDisplayed()
                        .performClick()
                    compose.onNodeWithContentDescription("Hide password").assertIsDisplayed().performClick()
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.assertEditableText(expected: String): SemanticsNodeInteraction = assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected)))

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
