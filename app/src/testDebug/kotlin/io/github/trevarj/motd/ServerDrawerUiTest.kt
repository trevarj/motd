package io.github.trevarj.motd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.chatlist.DrawerRow
import io.github.trevarj.motd.ui.chatlist.ServerDrawerContent
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
@Config(qualifiers = "w411dp-h891dp")
class ServerDrawerUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ircNetworkIcons_remainVisibleAcrossConnectionStates() {
        var scanned = false
        var contactInviteNetworkId: Long? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ServerDrawerContent(
                    drawerRows =
                        listOf(
                            drawerRow(1, IrcClientState.Ready("alice", emptySet(), emptyMap())),
                            drawerRow(2, IrcClientState.Connecting),
                            drawerRow(3, IrcClientState.Disconnected),
                        ),
                    selectedNetworkId = 1,
                    allUnread = 0,
                    allMentions = 0,
                    scopedUnreadCount = 0,
                    allOffline = false,
                    onSelectNetwork = {},
                    onConnect = {},
                    onDisconnect = {},
                    onServerMessages = {},
                    onOpenNetworkSettings = {},
                    onAddNetwork = {},
                    onToggleOffline = {},
                    onOpenSettings = {},
                    globalFeedEnabled = true,
                    onMarkAllRead = {},
                    onCreateContactInvite = { contactInviteNetworkId = it },
                    onScanInvite = { scanned = true },
                )
            }
        }

        val createInvite = compose.onNodeWithTag("drawer_create_contact_invite").assertIsDisplayed()
        val scanInvite = compose.onNodeWithTag("drawer_scan_invite").assertIsDisplayed()
        assertTrue(createInvite.fetchSemanticsNode().boundsInRoot.top < scanInvite.fetchSemanticsNode().boundsInRoot.top)
        createInvite.performClick()
        assertTrue(contactInviteNetworkId == 1L)
        scanInvite.performClick()
        assertTrue(scanned)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedStates =
            mapOf(
                1L to context.getString(R.string.drawer_state_connected),
                2L to context.getString(R.string.drawer_state_disconnected),
                3L to context.getString(R.string.drawer_state_disconnected),
            )
        for ((networkId, state) in expectedStates) {
            compose
                .onNodeWithTag("drawer_network_icon_$networkId", useUnmergedTree = true)
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state))
        }
        compose.onNodeWithTag("drawer_open_feed").assertIsDisplayed()
    }

    /** The feed lives behind the Global Feed lab, so its row is absent until the lab is on. */
    @Test
    fun feedRow_isAbsentWhileTheGlobalFeedLabIsOff() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ServerDrawerContent(
                    drawerRows = listOf(drawerRow(1, IrcClientState.Ready("alice", emptySet(), emptyMap()))),
                    selectedNetworkId = null,
                    allUnread = 0,
                    allMentions = 0,
                    scopedUnreadCount = 0,
                    allOffline = false,
                    onSelectNetwork = {},
                    onConnect = {},
                    onDisconnect = {},
                    onServerMessages = {},
                    onOpenNetworkSettings = {},
                    onAddNetwork = {},
                    onToggleOffline = {},
                    onOpenSettings = {},
                    onMarkAllRead = {},
                )
            }
        }

        compose.onNodeWithTag("drawer_open_feed").assertDoesNotExist()
    }

    @Test
    fun ceramicLogo_preservesThemeAwareShadingAcrossThemeRoundTrip() {
        lateinit var selectTheme: (ColorThemePreset) -> Unit
        compose.setContent {
            var theme by remember { mutableStateOf(ColorThemePreset.LIGHT) }
            selectTheme = { theme = it }
            MotdTheme(themePreset = theme, dynamicColor = false) {
                ServerDrawerContent(
                    drawerRows = emptyList(),
                    selectedNetworkId = null,
                    allUnread = 0,
                    allMentions = 0,
                    scopedUnreadCount = 0,
                    allOffline = false,
                    onSelectNetwork = {},
                    onConnect = {},
                    onDisconnect = {},
                    onServerMessages = {},
                    onOpenNetworkSettings = {},
                    onAddNetwork = {},
                    onToggleOffline = {},
                    onOpenSettings = {},
                    onMarkAllRead = {},
                )
            }
        }

        fun logoPixels(): Bitmap = compose.onNodeWithTag("drawer_logo_mark").captureToImage().asAndroidBitmap()

        val light = logoPixels()
        compose.runOnUiThread { selectTheme(ColorThemePreset.DARK) }
        val dark = logoPixels()
        compose.runOnUiThread { selectTheme(ColorThemePreset.LIGHT) }
        val lightAgain = logoPixels()

        val lightShading = ceramicShading(light)
        val darkShading = ceramicShading(dark)
        assertTrue("light theme should retain ceramic highlights", lightShading > 3)
        assertTrue("dark theme should invert ceramic highlights into shadows", darkShading < -3)
        assertEquals(sample(light, 0.25f, 0.25f), sample(lightAgain, 0.25f, 0.25f))
        assertEquals(sample(light, 0.80f, 0.70f), sample(lightAgain, 0.80f, 0.70f))
    }

    private fun drawerRow(
        networkId: Long,
        state: IrcClientState,
    ) = DrawerRow(
        networkId = networkId,
        name = "Network $networkId",
        role = NetworkRole.DIRECT,
        depth = 0,
        state = state,
        nick = (state as? IrcClientState.Ready)?.nick,
        unread = 0,
        mentions = 0,
    )

    private fun ceramicShading(bitmap: Bitmap): Int = luminance(sample(bitmap, 0.25f, 0.25f)) - luminance(sample(bitmap, 0.80f, 0.70f))

    private fun sample(
        bitmap: Bitmap,
        xFraction: Float,
        yFraction: Float,
    ): Int =
        bitmap.getPixel(
            (bitmap.width * xFraction).toInt().coerceIn(0, bitmap.width - 1),
            (bitmap.height * yFraction).toInt().coerceIn(0, bitmap.height - 1),
        )

    private fun luminance(color: Int): Int = (Color.red(color) * 2126 + Color.green(color) * 7152 + Color.blue(color) * 722) / 10_000
}
