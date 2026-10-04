package io.github.trevarj.motd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.chatlist.DrawerRow
import io.github.trevarj.motd.ui.chatlist.ServerDrawerContent
import io.github.trevarj.motd.ui.theme.LocalMotdSemanticColors
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
    fun network_badges_and_scoped_mark_all_read_remain_without_header_unread_total() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ready = IrcClientState.Ready("alice", emptySet(), emptyMap())
        val rows =
            listOf(
                drawerRow(1, ready).copy(unread = 3, mentions = 1),
                drawerRow(2, ready).copy(unread = 5, mentions = 2, unreadIncomplete = true, mentionsIncomplete = true),
            )
        val selectedNetworkId = mutableStateOf<Long?>(null)
        val totalMentions = mutableStateOf(3)
        val scopedUnread = mutableStateOf(8)
        val markedScopes = mutableListOf<Long?>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ServerDrawerContent(
                    drawerRows = rows,
                    selectedNetworkId = selectedNetworkId.value,
                    allMentions = totalMentions.value,
                    allMentionsIncomplete = true,
                    scopedUnreadCount = scopedUnread.value,
                    allOffline = false,
                    onSelectNetwork = {
                        selectedNetworkId.value = it
                        scopedUnread.value = if (it == 1L) 3 else 8
                    },
                    onConnect = {},
                    onDisconnect = {},
                    onServerMessages = {},
                    onOpenNetworkSettings = {},
                    onAddNetwork = {},
                    onToggleOffline = {},
                    onOpenSettings = {},
                    onMarkAllRead = { markedScopes += selectedNetworkId.value },
                )
            }
        }

        fun badgeDescription(
            resource: Int,
            count: Int,
        ) = context.resources.getQuantityString(resource, count, count)

        for ((id, unread, mentions) in listOf(Triple(1L, 3, 1), Triple(2L, 5, 2))) {
            val withinNetwork = hasAnyAncestor(hasTestTag("drawer_network_row_$id"))
            val unreadResource = if (id == 1L) R.plurals.badge_unread else R.plurals.badge_unread_at_least
            val mentionResource = if (id == 1L) R.plurals.badge_mention else R.plurals.badge_mention_at_least
            compose
                .onNode(hasContentDescription(badgeDescription(unreadResource, unread)) and withinNetwork, useUnmergedTree = true)
                .assertIsDisplayed()
            compose
                .onNode(hasContentDescription(badgeDescription(mentionResource, mentions)) and withinNetwork, useUnmergedTree = true)
                .assertIsDisplayed()
        }
        for (resource in listOf(R.plurals.badge_unread, R.plurals.badge_unread_at_least)) {
            compose.onAllNodesWithContentDescription(badgeDescription(resource, 8), useUnmergedTree = true).assertCountEquals(0)
        }
        val mentionTotal = badgeDescription(R.plurals.badge_mention_at_least, 3)
        compose.onAllNodesWithContentDescription(mentionTotal, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("drawer_mark_all_read").assertIsDisplayed().performClick()

        compose.onNodeWithTag("drawer_network_row_1").performClick()
        compose.onAllNodesWithContentDescription(mentionTotal, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("drawer_mark_all_read").assertIsDisplayed().performClick()
        compose.onNodeWithTag("drawer_clear_filter").performClick()
        compose.runOnIdle { totalMentions.value = 0 }
        compose.onAllNodesWithContentDescription(mentionTotal, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("drawer_mark_all_read").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(null, 1L, null), markedScopes)
            scopedUnread.value = 0
        }
        compose.onNodeWithTag("drawer_mark_all_read").assertDoesNotExist()
    }

    @Test
    fun ircNetworkIcons_showIdenticalNeutralCirclesWithOnlineAndOfflineDotsAcrossThemes() {
        var scanned = false
        var contactInviteNetworkId: Long? = null
        lateinit var selectTheme: (ColorThemePreset) -> Unit
        var connectedColor = 0
        var disconnectedColor = 0
        var badgeBackgroundColor = 0
        var iconSurfaceColor = 0
        compose.setContent {
            var theme by remember { mutableStateOf(ColorThemePreset.LIGHT) }
            selectTheme = { theme = it }
            MotdTheme(themePreset = theme, dynamicColor = false) {
                connectedColor = LocalMotdSemanticColors.current.success.toArgb()
                disconnectedColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
                badgeBackgroundColor = MaterialTheme.colorScheme.background.toArgb()
                iconSurfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()
                ServerDrawerContent(
                    drawerRows =
                        listOf(
                            drawerRow(1, IrcClientState.Ready("alice", emptySet(), emptyMap())),
                            drawerRow(2, IrcClientState.Connecting),
                            drawerRow(3, IrcClientState.Disconnected),
                        ),
                    selectedNetworkId = 1,
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

        compose.onNodeWithTag("drawer_create_contact_invite").assertIsDisplayed().performClick()
        assertTrue(contactInviteNetworkId == 1L)
        compose.onNodeWithTag("drawer_scan_invite").assertIsDisplayed().performClick()
        assertTrue(scanned)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedStates =
            mapOf(
                1L to context.getString(R.string.drawer_state_connected),
                2L to context.getString(R.string.drawer_state_disconnected),
                3L to context.getString(R.string.drawer_state_disconnected),
            )
        for (theme in listOf(ColorThemePreset.LIGHT, ColorThemePreset.DARK)) {
            compose.runOnUiThread { selectTheme(theme) }
            var offlineIcon: Bitmap? = null
            for ((networkId, state) in expectedStates) {
                val icon =
                    compose
                        .onNodeWithTag("drawer_network_icon_$networkId", useUnmergedTree = true)
                        .performScrollTo()
                        .assertIsDisplayed()
                        .assertWidthIsEqualTo(40.dp)
                        .assertHeightIsEqualTo(40.dp)
                        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state))
                val iconPixels = icon.captureToImage().asAndroidBitmap()
                assertEquals(iconSurfaceColor, sample(iconPixels, 0.5f, 0.075f))
                assertEquals(iconSurfaceColor, sample(iconPixels, 0.075f, 0.5f))
                assertEquals(iconSurfaceColor, sample(iconPixels, 0.5f, 0.9f))
                assertTrue(
                    "The network glyph must use the theme's neutral foreground",
                    (0 until iconPixels.width).any { x ->
                        (0 until iconPixels.height / 2).any { y -> iconPixels.getPixel(x, y) == disconnectedColor }
                    },
                )
                if (networkId != 1L) {
                    offlineIcon?.let {
                        assertTrue("Different network names and IDs must show the same default icon", it.sameAs(iconPixels))
                    }
                    offlineIcon = iconPixels
                }
                val badge =
                    compose
                        .onNodeWithTag("drawer_network_status_$networkId", useUnmergedTree = true)
                        .assertIsDisplayed()
                        .assertWidthIsEqualTo(14.dp)
                        .assertHeightIsEqualTo(14.dp)
                val iconBounds = icon.fetchSemanticsNode().boundsInRoot
                val badgeBounds = badge.fetchSemanticsNode().boundsInRoot
                assertEquals(iconBounds.right, badgeBounds.right, 0.5f)
                assertEquals(iconBounds.bottom, badgeBounds.bottom, 0.5f)
                val pixels = badge.captureToImage().asAndroidBitmap()
                val expectedColor = if (networkId == 1L) connectedColor else disconnectedColor
                assertEquals(expectedColor, sample(pixels, 0.5f, 0.25f))
                assertEquals(expectedColor, sample(pixels, 0.5f, 0.75f))
                assertEquals(expectedColor, sample(pixels, 0.25f, 0.5f))
                assertEquals(expectedColor, sample(pixels, 0.75f, 0.5f))
                assertEquals(
                    if (networkId == 1L) connectedColor else badgeBackgroundColor,
                    sample(pixels, 0.5f, 0.5f),
                )
                assertEquals(badgeBackgroundColor, sample(pixels, 0.5f, 0.1f))
            }
        }
        compose.onNodeWithTag("drawer_open_feed").assertIsDisplayed()
    }

    @Test
    fun bouncer_roots_and_znc_get_distinct_badges_and_root_shortcut() {
        val ready = IrcClientState.Ready("alice", emptySet(), emptyMap())
        val rows =
            listOf(
                drawerRow(1, ready).copy(role = NetworkRole.BOUNCER_ROOT),
                drawerRow(2, ready).copy(isZnc = true),
                drawerRow(3, ready),
                drawerRow(4, ready).copy(role = NetworkRole.BOUNCER_CHILD, depth = 1),
            )
        lateinit var selectTheme: (ColorThemePreset) -> Unit
        var openedRootId: Long? = null
        var networkSettingsId: Long? = null
        var connectedColor = 0
        var iconSurfaceColor = 0
        compose.setContent {
            var theme by remember { mutableStateOf(ColorThemePreset.LIGHT) }
            selectTheme = { theme = it }
            MotdTheme(themePreset = theme, dynamicColor = false) {
                connectedColor = LocalMotdSemanticColors.current.success.toArgb()
                iconSurfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()
                ServerDrawerContent(
                    drawerRows = rows,
                    selectedNetworkId = null,
                    allMentions = 0,
                    scopedUnreadCount = 0,
                    allOffline = false,
                    onSelectNetwork = {},
                    onConnect = {},
                    onDisconnect = {},
                    onServerMessages = {},
                    onOpenNetworkSettings = { networkSettingsId = it },
                    onOpenBouncerSettings = { openedRootId = it },
                    onAddNetwork = {},
                    onToggleOffline = {},
                    onOpenSettings = {},
                    onMarkAllRead = {},
                )
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        for (theme in listOf(ColorThemePreset.LIGHT, ColorThemePreset.DARK)) {
            compose.runOnUiThread { selectTheme(theme) }
            val icons =
                (1L..4L).associateWith { id ->
                    val icon =
                        compose
                            .onNodeWithTag("drawer_network_icon_$id", useUnmergedTree = true)
                            .performScrollTo()
                            .assertIsDisplayed()
                            .assertWidthIsEqualTo(40.dp)
                            .assertHeightIsEqualTo(40.dp)
                            .assert(
                                SemanticsMatcher.expectValue(
                                    SemanticsProperties.StateDescription,
                                    context.getString(R.string.drawer_state_connected),
                                ),
                            )
                    val pixels = icon.captureToImage().asAndroidBitmap()
                    assertEquals(iconSurfaceColor, sample(pixels, 0.5f, 0.075f))
                    val dot =
                        compose
                            .onNodeWithTag("drawer_network_status_$id", useUnmergedTree = true)
                            .assertIsDisplayed()
                            .assertWidthIsEqualTo(14.dp)
                            .assertHeightIsEqualTo(14.dp)
                    val iconBounds = icon.fetchSemanticsNode().boundsInRoot
                    val dotBounds = dot.fetchSemanticsNode().boundsInRoot
                    assertEquals(iconBounds.right, dotBounds.right, 0.5f)
                    assertEquals(iconBounds.bottom, dotBounds.bottom, 0.5f)
                    assertEquals(connectedColor, sample(dot.captureToImage().asAndroidBitmap(), 0.5f, 0.25f))
                    pixels
                }
            // Rasterization can shift by a subpixel between rows; verify each bouncer mark
            // differs from both topology marks rather than requiring pixel-identical siblings.
            for (bouncerId in 1L..2L) {
                for (topologyId in 3L..4L) {
                    assertTrue(
                        "Bouncer $bouncerId must differ visually from network $topologyId",
                        !icons.getValue(bouncerId).sameAs(icons.getValue(topologyId)),
                    )
                }
            }
            compose
                .onAllNodesWithContentDescription(context.getString(R.string.drawer_bouncer_icon), useUnmergedTree = true)
                .assertCountEquals(2)
        }

        val bouncerSettings = context.getString(R.string.drawer_bouncer_settings)
        val networkSettings = context.getString(R.string.drawer_network_settings)
        for (id in listOf(2L, 3L, 4L)) {
            compose.onNodeWithTag("drawer_network_row_$id").performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithText(bouncerSettings).assertDoesNotExist()
            compose.onNodeWithText(networkSettings).assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(id, networkSettingsId) }
        }
        compose.onNodeWithTag("drawer_network_row_1").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText(networkSettings).assertIsDisplayed()
        compose.onNodeWithText(bouncerSettings).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1L, openedRootId) }
        compose.onNodeWithText(bouncerSettings).assertDoesNotExist()
    }

    @Test
    fun long_network_list_keeps_settings_reachable_and_selection_visible() {
        val rows = (1L..20L).map { drawerRow(it, IrcClientState.Ready("alice", emptySet(), emptyMap())) }
        val selectedNetworkId = mutableStateOf<Long?>(1)
        var settingsOpened = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ServerDrawerContent(
                    drawerRows = rows,
                    selectedNetworkId = selectedNetworkId.value,
                    allMentions = 0,
                    scopedUnreadCount = 0,
                    allOffline = false,
                    onSelectNetwork = { selectedNetworkId.value = it },
                    onConnect = {},
                    onDisconnect = {},
                    onServerMessages = {},
                    onOpenNetworkSettings = {},
                    onAddNetwork = {},
                    onToggleOffline = {},
                    onOpenSettings = { settingsOpened++ },
                    onMarkAllRead = {},
                )
            }
        }

        compose.onNodeWithTag("drawer_network_row_1").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("drawer_open_settings").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, settingsOpened) }

        compose
            .onNodeWithTag("drawer_network_row_20")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
            .assertIsSelected()
        compose.runOnIdle { assertEquals(20L, selectedNetworkId.value) }
        compose.onNodeWithTag("drawer_open_settings").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, settingsOpened) }
        compose.onNodeWithTag("drawer_network_row_20").assertIsDisplayed().assertIsSelected()
        compose
            .onNodeWithTag("drawer_network_row_1")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotSelected()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun long_network_list_keeps_action_grid_and_mark_read_header_reachable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rows = (1L..20L).map { drawerRow(it, IrcClientState.Ready("alice", emptySet(), emptyMap())) }
        val selectedNetworkId = mutableStateOf<Long?>(1)
        val scopedUnread = mutableStateOf(3)
        val mentionsEnabled = mutableStateOf(true)
        val globalFeedEnabled = mutableStateOf(true)
        val allOffline = mutableStateOf(false)
        val actions = mutableListOf<String>()
        var contactInviteNetworkId: Long? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ServerDrawerContent(
                    drawerRows = rows,
                    selectedNetworkId = selectedNetworkId.value,
                    allMentions = 2,
                    scopedUnreadCount = scopedUnread.value,
                    allOffline = allOffline.value,
                    onSelectNetwork = {
                        selectedNetworkId.value = it
                        actions += "select"
                    },
                    onConnect = {},
                    onDisconnect = {},
                    onServerMessages = {},
                    onOpenNetworkSettings = {},
                    onAddNetwork = { actions += "add" },
                    onToggleOffline = {
                        allOffline.value = !allOffline.value
                        actions += "offline"
                    },
                    onOpenSettings = { actions += "settings" },
                    onOpenFeed = { actions += "feed" },
                    onOpenMentions = { actions += "mentions" },
                    mentionsEnabled = mentionsEnabled.value,
                    globalFeedEnabled = globalFeedEnabled.value,
                    onMarkAllRead = { actions += "mark" },
                    onCreateContactInvite = {
                        contactInviteNetworkId = it
                        actions += "share"
                    },
                    onScanInvite = { actions += "scan" },
                )
            }
        }

        val labels =
            listOf(
                "drawer_open_mentions" to R.string.mentions_title,
                "drawer_open_feed" to R.string.drawer_feed,
                "drawer_add_network" to R.string.drawer_add_network,
                "drawer_create_contact_invite" to R.string.contact_invite_create_title,
                "drawer_scan_invite" to R.string.invite_scan_title,
                "drawer_toggle_offline" to R.string.drawer_go_offline,
                "drawer_open_settings" to R.string.drawer_settings,
            )
        val gridTags =
            listOf(
                "drawer_open_mentions",
                "drawer_open_feed",
                "drawer_create_contact_invite",
                "drawer_scan_invite",
                "drawer_open_settings",
            )

        fun assertFourColumns(tags: List<String>) {
            val tiles = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
            val first = tiles.first()
            for ((index, tile) in tiles.withIndex()) {
                assertEquals("Each tile has a quarter-width cell", first.width, tile.width, 1f)
                assertEquals("Tile stays in its grid column", first.left + (index % 4) * first.width, tile.left, 2f)
                if (index < 4) {
                    assertEquals("Four tiles fit on the first row", first.top, tile.top, 1f)
                } else {
                    assertTrue("Remaining tiles stay on the second row", tile.top > first.top + 1f)
                    assertEquals("No third action row", tiles[4].top, tile.top, 1f)
                }
            }
        }

        fun assertHeaderActions() {
            val offline = compose.onNodeWithTag("drawer_toggle_offline").assertIsDisplayed().assertWidthIsEqualTo(48.dp)
            val add = compose.onNodeWithTag("drawer_add_network").assertIsDisplayed().assertWidthIsEqualTo(48.dp)
            val mark = compose.onNodeWithTag("drawer_mark_all_read").assertIsDisplayed().assertWidthIsEqualTo(48.dp)
            val offlineBounds = offline.fetchSemanticsNode().boundsInRoot
            val addBounds = add.fetchSemanticsNode().boundsInRoot
            val markBounds = mark.fetchSemanticsNode().boundsInRoot
            assertEquals(offlineBounds.right, addBounds.left, 1f)
            assertEquals(addBounds.right, markBounds.left, 1f)
            assertEquals(offlineBounds.top, addBounds.top, 1f)
            assertEquals(addBounds.top, markBounds.top, 1f)
            val networkLabel =
                compose
                    .onNodeWithText(context.getString(R.string.drawer_networks).uppercase(), useUnmergedTree = true)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue("Network label sits left of the action icons", networkLabel.right <= offlineBounds.left + 1f)
            assertTrue("Network label shares the action row", networkLabel.top >= offlineBounds.top - 1f)
            assertTrue("Network label shares the action row", networkLabel.bottom <= offlineBounds.bottom + 1f)
            val clear =
                compose
                    .onNodeWithTag("drawer_clear_filter")
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue("Show all chats fits below the header icons", clear.top >= offlineBounds.bottom - 1f)
        }
        assertFourColumns(gridTags)
        assertHeaderActions()
        for ((tag, label) in labels) {
            compose
                .onNodeWithTag(tag)
                .assertIsDisplayed()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf(context.getString(label)),
                    ),
                )
            if (tag in gridTags) {
                compose
                    .onNodeWithTag(tag, useUnmergedTree = true)
                    .assertHasClickAction()
                    .assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.ContentDescription,
                            listOf(context.getString(label)),
                        ),
                    )
                compose.onAllNodesWithContentDescription(context.getString(label), useUnmergedTree = true).assertCountEquals(1)
            }
        }
        val visibleLabels =
            listOf(
                R.string.mentions_title,
                R.string.drawer_feed,
                R.string.invite_share,
                R.string.invite_scan_title,
                R.string.drawer_settings,
            )
        for (label in visibleLabels) {
            compose
                .onNodeWithText(context.getString(label), useUnmergedTree = true)
                .assertIsDisplayed()
                .assertHasNoClickAction()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        }
        compose
            .onNodeWithTag("drawer_create_contact_invite")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(context.getString(R.string.contact_invite_create_title)),
                ),
            )

        for ((tag, _) in labels) compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf("mentions", "feed", "add", "share", "scan", "offline", "settings"), actions)
            assertEquals(1L, contactInviteNetworkId)
        }
        compose
            .onNodeWithTag("drawer_toggle_offline")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(context.getString(R.string.drawer_go_online)),
                ),
            )

        compose
            .onNodeWithTag("drawer_network_row_20")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
            .assertIsSelected()
        compose.runOnIdle { assertEquals(20L, selectedNetworkId.value) }
        for ((tag, _) in labels) compose.onNodeWithTag(tag).assertIsDisplayed()
        assertHeaderActions()
        compose.onNodeWithTag("drawer_create_contact_invite").performClick()
        compose.onNodeWithTag("drawer_network_row_20").assertIsDisplayed()
        compose
            .onNodeWithTag("drawer_mark_all_read")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(context.getString(R.string.drawer_mark_all_read)),
                ),
            ).performClick()
        compose.onNodeWithTag("drawer_clear_filter").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(20L, contactInviteNetworkId)
            assertEquals(null, selectedNetworkId.value)
            assertEquals(listOf("select", "share", "mark", "select"), actions.takeLast(4))
        }
        compose
            .onNodeWithTag("drawer_network_row_20")
            .assertIsDisplayed()
            .performClick()
            .assertIsSelected()
        compose.runOnIdle {
            assertEquals(20L, selectedNetworkId.value)
            scopedUnread.value = 0
            mentionsEnabled.value = false
            globalFeedEnabled.value = false
        }
        assertFourColumns(
            listOf(
                "drawer_create_contact_invite",
                "drawer_scan_invite",
                "drawer_open_settings",
            ),
        )
        compose.onNodeWithTag("drawer_mark_all_read").assertDoesNotExist()
        compose.onNodeWithTag("drawer_toggle_offline").assertIsDisplayed()
        compose.onNodeWithTag("drawer_add_network").assertIsDisplayed()
        compose.onNodeWithTag("drawer_open_mentions").assertDoesNotExist()
        compose.onNodeWithTag("drawer_open_feed").assertDoesNotExist()
        compose.onNodeWithTag("drawer_open_settings").assertIsDisplayed()
    }

    /** The feed shortcut lives behind the Global Feed lab. */
    @Test
    fun feedRow_isAbsentWhileTheGlobalFeedLabIsOff() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ServerDrawerContent(
                    drawerRows = listOf(drawerRow(1, IrcClientState.Ready("alice", emptySet(), emptyMap()))),
                    selectedNetworkId = null,
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
