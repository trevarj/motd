package io.github.trevarj.motd.agentwire

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
