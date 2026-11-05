package io.github.trevarj.motd.agentwire

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.contrastRatio
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AgentwireLiveSessionsUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun attachedSessionMetadataKeepsPairedInkAndUprightStatusAcrossLiveThemes() {
        val preset = mutableStateOf(ColorThemePreset.AYU_LIGHT)
        val themedText = mutableStateOf(false)
        val trueBlack = mutableStateOf(false)
        var fill = Color.Unspecified
        var ink = Color.Unspecified
        compose.setContent {
            MotdTheme(dynamicColor = false, themePreset = preset.value, useThemeTextColors = themedText.value, trueBlack = trueBlack.value) {
                fill = MaterialTheme.colorScheme.secondaryContainer
                ink = MaterialTheme.colorScheme.onSecondaryContainer
                AgentwireSessionDrawer(
                    rows =
                        listOf(
                            AgentwireDrawerRow(
                                sid = "bound",
                                title = "Attached project",
                                backend = "pi",
                                cwd = "/work/motd",
                                directory = "motd",
                                status = AgentwireDrawerStatus.WORKING,
                                tuiAttached = false,
                                attached = true,
                                section = AgentwireDrawerSection.BOUND,
                            ),
                        ),
                    attached = true,
                    actions = emptySet(),
                    onSelect = { error("The attached session is not a second attach action") },
                    onDetach = {},
                    onNewSession = {},
                )
            }
        }
        for ((theme, useText, black) in listOf(
            Triple(ColorThemePreset.AYU_LIGHT, false, false),
            Triple(ColorThemePreset.ONE_DARK, true, false),
            Triple(ColorThemePreset.AYU_DARK, true, true),
            Triple(ColorThemePreset.AYU_LIGHT, false, false),
        )) {
            compose.runOnIdle {
                preset.value = theme
                themedText.value = useText
                trueBlack.value = black
            }
            val row = compose.onNodeWithTag("agentwire_drawer_row_bound").assertIsDisplayed().assertHasNoClickAction()
            val image = row.captureToImage().toPixelMap()
            assertEquals(fill, image[image.width / 2, 3])
            for (text in listOf("Attached project", "/work/motd", "working", "Attached")) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val style = layouts.single().layoutInput.style
                assertEquals(ink, style.color)
                assertTrue(contrastRatio(style.color, fill) >= 4.49)
                assertEquals(FontStyle.Normal, style.fontStyle ?: FontStyle.Normal)
            }
        }
    }

    @Test
    fun closeChannel_requiresConfirmation() {
        var closes = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AgentwireCloseChannel { closes++ }
            }
        }

        compose.onNodeWithTag("agentwire_close_channel").performClick()
        compose.onNodeWithText("Owned Pi process stops. Session remains resumable from saved history.").assertIsDisplayed()
        compose.onNodeWithTag("agentwire_close_cancel").performClick()
        compose.onNodeWithTag("agentwire_close_confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, closes) }

        compose.onNodeWithTag("agentwire_close_channel").performClick()
        compose.onNodeWithTag("agentwire_close_confirm").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun managedSession_isVisibleWithoutAttach() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AgentwireLiveSessions(
                    sessions =
                        listOf(
                            AgentwireListItem(
                                id = "thread-managed",
                                title = "Managed Pi",
                                subtitle = "/work/motd",
                                raw = buildJsonObject {},
                            ),
                        ),
                    activeSid = null,
                    actions = emptySet(),
                    onAttach = { _, _ -> error("Attach must not be offered") },
                )
            }
        }

        compose.onNodeWithText("Managed Pi").assertIsDisplayed()
        compose.onNodeWithText("Attach").assertDoesNotExist()
    }

    @Test
    fun drawerActions_followAdvertisedCapabilities() {
        val actions = mutableStateOf(emptySet<String>())
        var selected: String? = null
        var detaches = 0
        var creates = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AgentwireSessionDrawer(
                    rows =
                        listOf(
                            AgentwireDrawerRow(
                                sid = "bound",
                                title = "Bound",
                                backend = "pi",
                                cwd = "/work/motd",
                                directory = "motd",
                                status = AgentwireDrawerStatus.IDLE,
                                tuiAttached = false,
                                attached = true,
                                section = AgentwireDrawerSection.BOUND,
                            ),
                            AgentwireDrawerRow(
                                sid = "other",
                                title = "Other",
                                backend = "pi",
                                cwd = "/work/other",
                                directory = "other",
                                status = AgentwireDrawerStatus.IDLE,
                                tuiAttached = false,
                                attached = false,
                                section = AgentwireDrawerSection.LIVE,
                            ),
                        ),
                    attached = true,
                    actions = actions.value,
                    onSelect = { selected = it.sid },
                    onDetach = { detaches++ },
                    onNewSession = { creates++ },
                )
            }
        }

        compose.onNodeWithText("Other").assertIsDisplayed()
        compose.onNodeWithTag("agentwire_drawer_row_other").assertHasNoClickAction()
        compose.onNodeWithTag("agentwire_drawer_detach").assertDoesNotExist()
        compose.onNodeWithTag("agentwire_drawer_new_session").assertDoesNotExist()

        compose.runOnIdle {
            actions.value = setOf("session.attach", "session.detach", "session.create")
        }
        compose.onNodeWithTag("agentwire_drawer_row_other").assertHasClickAction().performClick()
        compose.onNodeWithTag("agentwire_drawer_detach").performClick()
        compose.onNodeWithTag("agentwire_drawer_new_session").performClick()
        compose.runOnIdle {
            assertEquals("other", selected)
            assertEquals(1, detaches)
            assertEquals(1, creates)
        }
    }

    @Test
    fun desktopTui_isVisibleAndRemainsManualToAttach() {
        var attached: Pair<String, String?>? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AgentwireLiveSessions(
                    sessions =
                        listOf(
                            AgentwireListItem(
                                id = "thread-desktop",
                                title = "Desktop TUI",
                                subtitle = "/work/motd",
                                raw =
                                    buildJsonObject {
                                        put("busy", true)
                                        put("tuiAttached", true)
                                    },
                            ),
                        ),
                    activeSid = null,
                    actions = setOf("session.attach"),
                    onAttach = { sid, cwd -> attached = sid to cwd },
                )
            }
        }

        compose.onNodeWithTag("agentwire_live_sessions").assertIsDisplayed()
        compose.onNodeWithText("Desktop TUI").assertIsDisplayed()
        compose.onNodeWithText("TUI").assertIsDisplayed()
        compose.onNodeWithText("Running").assertIsDisplayed()
        compose.runOnIdle { assertNull(attached) }

        compose.onNodeWithText("Attach").performClick()
        compose.runOnIdle {
            assertEquals("thread-desktop" to "/work/motd", attached)
        }
    }
}
