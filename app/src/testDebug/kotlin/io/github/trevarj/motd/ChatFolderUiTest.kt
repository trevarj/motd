package io.github.trevarj.motd

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatFolderEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.FolderDisplayMode
import io.github.trevarj.motd.data.prefs.MentionsPlacement
import io.github.trevarj.motd.ui.chatlist.ChatListContent
import io.github.trevarj.motd.ui.chatlist.ChatListInvitation
import io.github.trevarj.motd.ui.chatlist.ChatListState
import io.github.trevarj.motd.ui.chatlist.FolderTabStrip
import io.github.trevarj.motd.ui.chatlist.ordinaryChatListRows
import io.github.trevarj.motd.ui.chatlist.partitionArchivedRows
import io.github.trevarj.motd.ui.chatlist.summarizeFolder
import io.github.trevarj.motd.ui.components.captureSurfaceBackdrop
import io.github.trevarj.motd.ui.components.rememberSurfaceBackdropSource
import io.github.trevarj.motd.ui.theme.MotdShapes
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
class ChatFolderUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun folder_expands_and_long_press_opens_editor() {
        val state = mutableStateOf(ChatListState(rows = listOf(row()), folders = listOf(folder()), showFolderChatsInAll = false, loading = false))
        var edited: Long? = null
        setContent(state) {
            onSetFolderExpanded = { id, expanded ->
                state.value = state.value.copy(folders = listOf(folder().copy(id = id, expanded = expanded)))
            }
            onOpenFolderEditor = { edited = it }
        }

        compose.onAllNodesWithTag("chatlist_row_1").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_folder_preview_sender", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_7").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_7").performTouchInput { longClick() }
        assertEquals(7L, edited)
    }

    @Test
    fun discord_tab_navigates_from_inline_and_clears_selection() {
        val portal = row(41, "#discord.guild.general", folderId = 7, mentions = 3)
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row(1, "#dev", folderId = 7), row(2, "#ordinary", folderId = null)),
                    folders = listOf(folder()),
                    dickordEnabled = true,
                    dickordUnreadSummary = summarizeFolder(listOf(portal)),
                    loading = false,
                ),
            )
        var opens = 0
        setContent(state) {
            onOpenDickord = { opens++ }
        }

        compose.onNodeWithTag("chatlist_folder_tabs").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        compose.onNodeWithTag("chatlist_folder_tab_discord").assert(hasText("Discord")).assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_7").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_2").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()

        compose.onNodeWithTag("chatlist_folder_tab_discord").performClick()

        compose.onAllNodesWithTag("chatlist_selection_top_app_bar").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun incomplete_folder_summary_exposes_history_coverage_without_claiming_activity() {
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row(incomplete = true)),
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    loading = false,
                ),
            )
        setContent(state)

        val incomplete = ApplicationProvider.getApplicationContext<Context>().getString(R.string.chat_history_partial_chip)
        compose.onNodeWithTag("chatlist_folder_tab_all").assert(hasContentDescription(incomplete))
        compose.onNodeWithTag("chatlist_folder_tab_7").assert(hasContentDescription(incomplete))
        compose.onNodeWithTag("chatlist_row_history_incomplete", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun folder_badges_preserve_mention_lower_bounds() {
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row(mentions = 2).copy(mentionCountIncomplete = true)),
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    showFolderChatsInAll = false,
                    loading = false,
                ),
            )
        setContent(state)
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val mentions = hasContentDescription(resources.getQuantityString(R.plurals.badge_mention_at_least, 2, 2))

        compose.onNodeWithTag("chatlist_folder_tab_7").assert(mentions)
        compose
            .onNode(hasText("@2+") and hasAnyAncestor(hasTestTag("chatlist_folder_tab_7")), useUnmergedTree = true)
            .assertIsDisplayed()

        compose.runOnIdle { state.value = state.value.copy(folderDisplayMode = FolderDisplayMode.INLINE) }

        compose.onNodeWithTag("chatlist_folder_7").assert(mentions)
        compose
            .onNode(hasText("@2+") and hasAnyAncestor(hasTestTag("chatlist_folder_7")), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun discord_tab_keeps_all_and_correct_folder_selection_in_tabs_and_empty_lists() {
        val portal = row(41, "#discord.guild.general", folderId = null)
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row()),
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    showFolderChatsInAll = false,
                    dickordEnabled = true,
                    dickordUnreadSummary = summarizeFolder(emptyList()),
                    loading = false,
                ),
            )
        setContent(state)

        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        compose.onNodeWithTag("chatlist_folder_tab_discord").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_tab_7").performClick().assertIsSelected()

        state.value =
            ChatListState(
                dickordEnabled = true,
                dickordUnreadSummary = summarizeFolder(emptyList()),
                loading = false,
            )
        compose.onNodeWithTag("chatlist_folder_tabs").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        compose.onNodeWithTag("chatlist_folder_tab_discord").assertIsDisplayed()

        state.value =
            state.value.copy(
                dickordUnreadSummary = summarizeFolder(listOf(portal)),
            )
        val emptyHeaderBottom =
            compose
                .onNodeWithTag("chatlist_folder_overlay")
                .fetchSemanticsNode()
                .boundsInRoot
                .bottom
        val emptyHintTop =
            compose
                .onNodeWithText("Discord conversations are in the Discord tab.")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        assertTrue("Empty-state content remains below the folder header", emptyHintTop >= emptyHeaderBottom)
        compose.onNodeWithText("Discord conversations are in the Discord tab.").assertIsDisplayed()
    }

    @Test
    fun enabled_partition_hides_pinned_foldered_and_archived_portal_rows_and_disabled_restores_them() {
        val pinned = row(41, "#discord.guild.pinned", folderId = 7, pinned = true)
        val foldered = row(42, "#DiScOrD.guild.foldered", folderId = 7)
        val archived = row(43, "#discord.guild.archived", folderId = 7, archived = true)
        val source = listOf(pinned, foldered, archived)

        fun projected(
            rows: List<ChatListRow>,
            enabled: Boolean,
        ): ChatListState {
            val (activeRows, archivedRows) = partitionArchivedRows(ordinaryChatListRows(rows, enabled))
            return ChatListState(
                rows = activeRows,
                archivedRows = archivedRows,
                folders = listOf(folder().copy(expanded = true)),
                dickordEnabled = enabled,
                dickordUnreadSummary = if (enabled) summarizeFolder(rows.filterNot(ChatListRow::archived)) else null,
                loading = false,
            )
        }

        val state = mutableStateOf(projected(source, enabled = true))
        setContent(state)

        compose.onNodeWithTag("chatlist_folder_tab_discord").assertIsDisplayed()
        compose.onAllNodesWithTag("chatlist_row_41").assertCountEquals(0)
        compose.onAllNodesWithTag("chatlist_row_42").assertCountEquals(0)
        compose.onAllNodesWithTag("chatlist_archived_folder").assertCountEquals(0)
        compose.onAllNodesWithTag("chatlist_folder_7").assertCountEquals(0)

        state.value = projected(source, enabled = false)
        compose.onAllNodesWithTag("chatlist_folder_tab_discord").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_row_41").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_42").assertIsDisplayed()

        state.value = projected(listOf(archived), enabled = false)
        compose.onNodeWithTag("chatlist_archived_folder").performClick()
        compose.onNodeWithTag("chatlist_row_43").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(true, pinned.pinned)
            assertEquals(7L, foldered.folderId)
            assertEquals(true, archived.archived)
        }
    }

    @Test
    fun tabs_all_is_flat_and_folder_filters_with_icon_name_and_badge() {
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row(1, "#dev", folderId = 7, mentions = 3), row(2, "#other", folderId = null)),
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    loading = false,
                ),
            )
        setContent(state)

        compose.onNodeWithTag("chatlist_folder_tabs").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        compose.onAllNodesWithTag("chatlist_folder_7").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_2").assertIsDisplayed()
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        compose
            .onNodeWithTag("chatlist_folder_tab_7")
            .assert(hasContentDescription(resources.getQuantityString(R.plurals.badge_mention, 3, 3)))
            .performClick()
        compose.onNodeWithTag("chatlist_folder_tab_icon_7", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
        compose.onAllNodesWithTag("chatlist_row_2").assertCountEquals(0)
    }

    @Test
    fun folder_tabs_keep_selected_pills_sharp_above_the_translucent_capsule_and_scrolling_rows() {
        val colors =
            lightColorScheme(
                surface = Color(0xFFABCDEF),
                surfaceContainerHigh = Color(0xFFDDEEFF),
                primaryContainer = Color(0xFFEE8844),
                primary = Color(0xFF3322CC),
                onPrimary = Color.White,
            )
        val state =
            mutableStateOf(
                ChatListState(
                    rows = (1L..20L).map { id -> row(id, "#room$id", folderId = if (id % 2 == 1L) 7L else null).copy(unreadCount = if (id == 1L) 1 else 0) },
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    mentionsEnabled = true,
                    mentionsPlacement = MentionsPlacement.CHAT_LIST,
                    allMentions = 2,
                    loading = false,
                ),
            )
        setContent(state, colorScheme = colors)

        fun pillPixel(tag: String): Int {
            val strip = compose.onNodeWithTag("chatlist_folder_tabs")
            val stripBounds = strip.fetchSemanticsNode().boundsInRoot
            val pillBounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val pixels = strip.captureToImage().asAndroidBitmap()
            val x = ((pillBounds.left + pillBounds.right) / 2 - stripBounds.left).toInt()
            val y = (pillBounds.top - stripBounds.top + 2).toInt()
            // This point is inside the pill fill but above the icon, label, and badge.
            return pixels.getPixel(x, y)
        }

        val allTag = "chatlist_folder_tab_pill_all"
        val folderTag = "chatlist_folder_tab_pill_7"
        val selectedFill = pillPixel(allTag)
        val plainFill = pillPixel(folderTag)
        assertNotEquals(plainFill, selectedFill)
        assertEquals(colors.secondaryContainer.toArgb(), selectedFill)
        val unreadTint = lerp(colors.surface, colors.primaryContainer, 0.20f)
        val unreadFill = unreadTint.toArgb()
        val restingFill = unreadTint.copy(alpha = 0.60f).compositeOver(colors.surface).toArgb()
        assertColorNear("The capsule is a 60%-opaque unread tint, not an opaque card", restingFill, plainFill)
        assertNotEquals(unreadFill, plainFill)
        val unreadRowPixels = compose.onNodeWithTag("chatlist_row_1").captureToImage().asAndroidBitmap()
        val unreadRowFill = unreadRowPixels.getPixel(unreadRowPixels.width / 2, 2)
        assertEquals("The unread row uses the unread card tint", unreadFill, unreadRowFill)
        assertNotEquals("The unselected tab is translucent while the unread row stays opaque", unreadRowFill, plainFill)

        val strip = compose.onNodeWithTag("chatlist_folder_tabs")
        val stripBounds = strip.fetchSemanticsNode().boundsInRoot
        val pillBounds = compose.onNodeWithTag(allTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val pixels = strip.captureToImage().asAndroidBitmap()
        val center = ((pillBounds.left + pillBounds.right) / 2 - stripBounds.left).toInt()
        assertColorNear("No underline above the selected pill", plainFill, pixels.getPixel(center, 0))
        assertColorNear("No separator below the selected pill", plainFill, pixels.getPixel(center, pixels.height - 1))
        val root = compose.onRoot()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val overlay = compose.onNodeWithTag("chatlist_folder_overlay").fetchSemanticsNode().boundsInRoot
        val firstRow =
            compose
                .onNodeWithTag("chatlist_row_surface_1")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue("The first row starts below the complete measured folder header", firstRow.top >= overlay.bottom)
        val toolbar = compose.onNodeWithTag("chatlist_top_app_bar").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("chatlist_rows").fetchSemanticsNode().boundsInRoot
        assertEquals("The recorded viewport reaches the window top, not merely the folder strip", rootBounds.top, viewport.top, 0.5f)
        assertEquals("The folder header keeps its original position below the app bar", toolbar.bottom, overlay.top, 0.5f)

        val scrollBy = overlay.height - with(compose.density) { 24.dp.toPx() }
        compose.onNodeWithTag("chatlist_rows").performSemanticsAction(SemanticsActions.ScrollBy) {
            assertTrue(it(0f, scrollBy))
        }
        compose.waitForIdle()

        val shiftedRow =
            compose
                .onNodeWithTag("chatlist_row_surface_1")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue("The chat row scrolls beneath the folder strip", shiftedRow.top < overlay.bottom)
        val pixelsUnderStrip = root.captureToImage().asAndroidBitmap()

        fun rootPixel(
            x: Float,
            y: Float,
        ) = pixelsUnderStrip.getPixel((x - rootBounds.left).toInt(), (y - rootBounds.top).toInt())

        val underStripX = rootBounds.left + with(compose.density) { 4.dp.toPx() }
        val underStripY = overlay.top + with(compose.density) { 28.dp.toPx() }
        assertTrue(underStripY in shiftedRow.top..shiftedRow.bottom)
        assertEquals("The transparent strip reveals the moving chat row", colors.surface.toArgb(), rootPixel(underStripX, underStripY))
        val capsuleBounds = compose.onNodeWithTag("chatlist_folder_capsule").fetchSemanticsNode().boundsInRoot
        val movingCapsulePixel = rootPixel(capsuleBounds.right - with(compose.density) { 8.dp.toPx() }, underStripY)
        for (shift in listOf(16, 8, 0)) {
            val channel = (movingCapsulePixel shr shift) and 0xFF
            val resting = (restingFill shr shift) and 0xFF
            val unread = (unreadFill shr shift) and 0xFF
            assertTrue("The blurred capsule stays between the tinted canvas and unread row", channel in minOf(resting, unread) - 2..maxOf(resting, unread) + 2)
        }
        val selectedBounds = compose.onNodeWithTag(allTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(
            "The selected pill remains secondary-container above the row",
            colors.secondaryContainer.toArgb(),
            rootPixel((selectedBounds.left + selectedBounds.right) / 2, selectedBounds.top + with(compose.density) { 2.dp.toPx() }),
        )
        var selectedInkVisible = false
        for (x in (selectedBounds.left - rootBounds.left).toInt() until (selectedBounds.right - rootBounds.left).toInt()) {
            for (y in (selectedBounds.top - rootBounds.top).toInt() until (selectedBounds.bottom - rootBounds.top).toInt()) {
                if (pixelsUnderStrip.getPixel(x, y) == colors.onSecondaryContainer.toArgb()) {
                    selectedInkVisible = true
                    break
                }
            }
            if (selectedInkVisible) break
        }
        assertTrue("Selected tab content uses its paired ink", selectedInkVisible)

        compose.onNodeWithTag("chatlist_pinned_mentions").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_tab_7").performClick().assertIsSelected()
        assertColorNear("Switching tabs restores the measured initial backdrop", plainFill, pillPixel(allTag))
        assertEquals(colors.secondaryContainer.toArgb(), pillPixel(folderTag))
    }

    @Test
    fun selected_folder_and_all_tabs_preserve_unread_and_mention_badge_meaning() {
        val colors =
            lightColorScheme(
                primary = Color(0xFF3322CC),
                onPrimary = Color.White,
                secondary = Color(0xFF005544),
                onSecondary = Color(0xFFFFCCAA),
            )
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row(1, folderId = 7).copy(unreadCount = 8), row(2, folderId = 8).copy(unreadCount = 8)),
                    folders = listOf(folder(7, "First"), folder(8, "Second")),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    loading = false,
                ),
            )
        setContent(state, colorScheme = colors)
        val resources = ApplicationProvider.getApplicationContext<Context>().resources

        fun assertBadgeColors(
            tabTag: String,
            count: Int,
            mention: Boolean = false,
        ) {
            val description = resources.getQuantityString(if (mention) R.plurals.badge_mention else R.plurals.badge_unread, count, count)
            val tab = compose.onNodeWithTag(tabTag).assertIsDisplayed()
            val badge =
                compose
                    .onNode(hasContentDescription(description) and hasAnyAncestor(hasTestTag(tabTag)), useUnmergedTree = true)
                    .assertIsDisplayed()
            val background = if (mention) colors.secondary else colors.primary
            val foreground = if (mention) colors.onSecondary else colors.onPrimary
            var backgroundVisible = false
            var foregroundVisible = false
            // Badge semantics/layout can precede the native repaint after text and palette changes.
            compose.waitUntil(timeoutMillis = 5_000) {
                val pixels = tab.captureToImage().asAndroidBitmap()
                val tabBounds = tab.fetchSemanticsNode().boundsInRoot
                val badgeBounds = badge.fetchSemanticsNode().boundsInRoot
                backgroundVisible = false
                foregroundVisible = false
                for (x in (badgeBounds.left - tabBounds.left).toInt() until (badgeBounds.right - tabBounds.left).toInt()) {
                    for (y in (badgeBounds.top - tabBounds.top).toInt() until (badgeBounds.bottom - tabBounds.top).toInt()) {
                        val color = pixels.getPixel(x, y)
                        if (color == foreground.toArgb()) foregroundVisible = true
                        if (color == background.toArgb()) backgroundVisible = true
                    }
                    if (foregroundVisible && backgroundVisible) break
                }
                backgroundVisible && foregroundVisible
            }
            assertTrue("The count chip uses the expected fill on $tabTag", backgroundVisible)
            assertTrue("The count chip uses the expected text color on $tabTag", foregroundVisible)
        }

        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        assertBadgeColors("chatlist_folder_tab_all", 16)
        assertBadgeColors("chatlist_folder_tab_7", 8)
        compose.onNodeWithTag("chatlist_folder_tab_7").performClick().assertIsSelected()
        assertBadgeColors("chatlist_folder_tab_all", 16)
        assertBadgeColors("chatlist_folder_tab_7", 8)
        assertBadgeColors("chatlist_folder_tab_8", 8)

        compose.runOnIdle {
            state.value = state.value.copy(rows = state.value.rows.map { if (it.folderId == 7L) it.copy(mentionCount = 2) else it })
        }
        compose.onNodeWithTag("chatlist_folder_tab_7").assertIsSelected()
        assertBadgeColors("chatlist_folder_tab_7", 2, mention = true)
        assertBadgeColors("chatlist_folder_tab_all", 2, mention = true)
    }

    @Test
    fun selected_folder_countless_indicators_use_paired_ink_without_changing_unselected_colors() {
        val colors =
            lightColorScheme(
                primary = Color(0xFF3322CC),
                onPrimary = Color.White,
                onSurfaceVariant = Color(0xFF333333),
            )
        val state =
            mutableStateOf(
                ChatListState(
                    rows =
                        listOf(
                            row(1, folderId = 7).copy(unreadCountIncomplete = true),
                            row(2, folderId = 8).copy(mentionCountIncomplete = true),
                        ),
                    folders = listOf(folder(7, "A"), folder(8, "B")),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    loading = false,
                ),
            )
        setContent(state, colorScheme = colors)
        val resources = ApplicationProvider.getApplicationContext<Context>().resources

        fun assertIndicatorColor(
            tabTag: String,
            descriptionRes: Int,
            expected: Color,
        ) {
            val description = resources.getString(descriptionRes)
            val tab = compose.onNodeWithTag(tabTag).assert(hasContentDescription(description)).assertIsDisplayed()
            val indicator =
                compose
                    .onNode(hasContentDescription(description) and hasAnyAncestor(hasTestTag(tabTag)), useUnmergedTree = true)
                    .assertIsDisplayed()
            var expectedVisible = false
            // Scan only the indicator, excluding the selected label and leading icon.
            compose.waitUntil(timeoutMillis = 5_000) {
                val pixels = tab.captureToImage().asAndroidBitmap()
                val tabBounds = tab.fetchSemanticsNode().boundsInRoot
                val indicatorBounds = indicator.fetchSemanticsNode().boundsInRoot
                expectedVisible = false
                for (x in (indicatorBounds.left - tabBounds.left).toInt() until (indicatorBounds.right - tabBounds.left).toInt()) {
                    for (y in (indicatorBounds.top - tabBounds.top).toInt() until (indicatorBounds.bottom - tabBounds.top).toInt()) {
                        if (pixels.getPixel(x, y) == expected.toArgb()) expectedVisible = true
                    }
                    if (expectedVisible) break
                }
                expectedVisible
            }
            assertTrue("The activity indicator uses $expected on $tabTag", expectedVisible)
        }

        val history = R.string.chat_history_partial_chip
        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        assertIndicatorColor("chatlist_folder_tab_7", history, colors.onSurfaceVariant)
        assertIndicatorColor("chatlist_folder_tab_8", history, colors.onSurfaceVariant)
        compose.onNodeWithTag("chatlist_folder_tab_7").performClick().assertIsSelected()
        assertIndicatorColor("chatlist_folder_tab_7", history, colors.onSecondaryContainer)
        assertIndicatorColor("chatlist_folder_tab_all", history, colors.onSurfaceVariant)
        compose.onNodeWithTag("chatlist_folder_tab_8").performClick().assertIsSelected()
        assertIndicatorColor("chatlist_folder_tab_8", history, colors.onSecondaryContainer)
        assertIndicatorColor("chatlist_folder_tab_7", history, colors.onSurfaceVariant)
        compose.onNodeWithTag("chatlist_folder_tab_all").performClick().assertIsSelected()
        assertIndicatorColor("chatlist_folder_tab_all", history, colors.onSecondaryContainer)

        compose.runOnIdle {
            state.value =
                state.value.copy(
                    rows =
                        state.value.rows.map {
                            it.copy(unreadCountIncomplete = false, mentionCountIncomplete = false, advertisedUnread = true)
                        },
                )
        }
        val pending = R.string.badge_unread_pending
        assertIndicatorColor("chatlist_folder_tab_7", pending, colors.primary)
        assertIndicatorColor("chatlist_folder_tab_8", pending, colors.primary)
        compose.onNodeWithTag("chatlist_folder_tab_7").performClick().assertIsSelected()
        assertIndicatorColor("chatlist_folder_tab_7", pending, colors.onSecondaryContainer)
        assertIndicatorColor("chatlist_folder_tab_all", pending, colors.primary)
        compose.onNodeWithTag("chatlist_folder_tab_all").performClick().assertIsSelected()
        assertIndicatorColor("chatlist_folder_tab_all", pending, colors.onSecondaryContainer)
    }

    @Test
    fun folder_strip_keeps_transparent_gutters_and_smooth_capsule_edges_in_light_and_dark_themes() {
        val preset = mutableStateOf(ColorThemePreset.LIGHT)
        lateinit var colors: ColorScheme
        val state =
            ChatListState(
                rows = listOf(row(1, "#general", folderId = null), row(2, "#dev", folderId = 7), row(3, "#support", folderId = null)),
                folders = listOf(folder()),
                folderDisplayMode = FolderDisplayMode.TABS,
                dickordEnabled = true,
                dickordUnreadSummary = summarizeFolder(emptyList()),
                mentionsEnabled = true,
                mentionsPlacement = MentionsPlacement.FOLDER_TAB,
                loading = false,
            )
        compose.setContent {
            MotdTheme(themePreset = preset.value, dynamicColor = false) {
                colors = MaterialTheme.colorScheme
                ChatListContent(
                    state = state,
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        for (theme in listOf(ColorThemePreset.LIGHT, ColorThemePreset.DARK)) {
            compose.runOnIdle { preset.value = theme }
            compose.waitForIdle()
            val root = compose.onRoot()
            val rootBounds = root.fetchSemanticsNode().boundsInRoot
            val capsule = compose.onNodeWithTag("chatlist_folder_capsule").assertIsDisplayed()
            val capsuleNode = capsule.fetchSemanticsNode()
            val bounds = capsuleNode.boundsInRoot
            val viewport = compose.onNodeWithTag("chatlist_folder_tabs").fetchSemanticsNode().boundsInRoot
            val outerInset = with(compose.density) { 8.dp.toPx() }
            val sampleInset = with(compose.density) { 1.dp.toPx() }
            assertEquals(rootBounds.left + outerInset, bounds.left, 0.5f)
            assertEquals(rootBounds.right - outerInset, bounds.right, 0.5f)
            assertEquals(rootBounds.left + outerInset, viewport.left, 0.5f)
            assertEquals(rootBounds.right - outerInset, viewport.right, 0.5f)
            assertTrue("The shared surface must not consume tab selection", !capsuleNode.config.contains(SemanticsActions.OnClick))
            val overlayBounds = compose.onNodeWithTag("chatlist_folder_overlay").fetchSemanticsNode().boundsInRoot
            assertEquals(
                "The capsule keeps 4dp of transparent padding above and below",
                with(compose.density) { 8.dp.toPx() },
                overlayBounds.height - bounds.height,
                0.5f,
            )

            val pixels = root.captureToImage().asAndroidBitmap()

            fun pixel(
                x: Float,
                y: Float,
            ) = pixels.getPixel((x - rootBounds.left).toInt(), (y - rootBounds.top).toInt())

            val centerY = (bounds.top + bounds.bottom) / 2
            assertEquals("The transparent strip shows the shared chat surface", colors.surface.toArgb(), pixel(rootBounds.left + sampleInset, centerY))
            val rowsBounds = compose.onNodeWithTag("chatlist_rows").fetchSemanticsNode().boundsInRoot
            assertEquals("Empty list space keeps the shared chat surface", colors.surface.toArgb(), pixel(rowsBounds.left + sampleInset, rowsBounds.bottom - sampleInset))
            val rowPixels = compose.onNodeWithTag("chatlist_row_1").captureToImage().asAndroidBitmap()
            assertEquals("Read cards match the canvas", colors.surface.toArgb(), rowPixels.getPixel(rowPixels.width / 2, rowPixels.height - 2))
            compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
            val selected =
                compose
                    .onNodeWithTag("chatlist_folder_tab_pill_all", useUnmergedTree = true)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertEquals("The selected pill uses the paired secondary container", colors.secondaryContainer.toArgb(), pixel((selected.left + selected.right) / 2, selected.top + 2 * sampleInset))
            val tabBounds = compose.onNodeWithTag("chatlist_folder_tab_all").fetchSemanticsNode().boundsInRoot
            val pillInset = with(compose.density) { 4.dp.toPx() }
            assertEquals("The clickable tab retains its original 48dp height", with(compose.density) { 48.dp.toPx() }, tabBounds.height, 0.5f)
            assertEquals("The selected pill is 40dp tall", with(compose.density) { 40.dp.toPx() }, selected.height, 0.5f)
            assertEquals("The first pill starts at the chat avatar's 12dp inset inside the card", bounds.left + with(compose.density) { 12.dp.toPx() }, selected.left, 0.5f)
            assertEquals("The tab adds no horizontal inset around its pill", tabBounds.left, selected.left, 0.5f)
            assertEquals(tabBounds.right, selected.right, 0.5f)
            assertEquals("The selected pill is inset 4dp from the tab top", pillInset, selected.top - tabBounds.top, 0.5f)
            assertEquals("The selected pill is inset 4dp from the tab bottom", pillInset, tabBounds.bottom - selected.bottom, 0.5f)
            // The blur samples first-row artwork below the header, so its edges need not match a flat canvas.
            val pillCenterX = (selected.left + selected.right) / 2
            assertColorNear("The leading inset has no hard border", pixel(bounds.left + 2 * sampleInset, centerY), pixel(bounds.left + sampleInset, centerY))
            assertColorNear("The upper inset has no underline", pixel(pillCenterX, bounds.top + 2 * sampleInset), pixel(pillCenterX, bounds.top + sampleInset))
            assertColorNear("The lower inset has no separator", pixel(pillCenterX, bounds.bottom - 2 * sampleInset), pixel(pillCenterX, bounds.bottom - sampleInset))
            val mentionsTab = compose.onNodeWithTag("chatlist_folder_tab_mentions").fetchSemanticsNode().boundsInRoot
            val mentionsPill = compose.onNodeWithTag("chatlist_folder_tab_pill_mentions", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("Mentions keeps the same 48dp target", tabBounds.height, mentionsTab.height, 0.5f)
            assertEquals("Mentions keeps the same 40dp content geometry", selected.height, mentionsPill.height, 0.5f)
            assertEquals(pillInset, mentionsPill.top - mentionsTab.top, 0.5f)
            assertEquals(pillInset, mentionsTab.bottom - mentionsPill.bottom, 0.5f)
            compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
        }
    }

    @Test
    @Config(sdk = [31, 35], qualifiers = "w411dp-h891dp")
    fun folder_capsule_blurs_live_scrolling_backdrop_and_tracks_capsule_offsets() {
        val capsuleTop = mutableStateOf(48.dp)
        setStripedBackdropContent(capsuleTop)
        val root = compose.onRoot()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val source = compose.onNodeWithTag("folder_backdrop_source").fetchSemanticsNode().boundsInRoot

        fun capture(): Bitmap {
            compose.waitForIdle()
            return root.captureToImage().asAndroidBitmap()
        }

        fun assertBackdrop(
            pixels: Bitmap,
            redBand: Boolean,
        ) {
            val capsule = compose.onNodeWithTag("chatlist_folder_capsule").fetchSemanticsNode().boundsInRoot
            val y = ((capsule.top + capsule.bottom) / 2 - rootBounds.top).toInt()
            val xs = (capsule.right - with(compose.density) { 96.dp.toPx() }).toInt() until (capsule.right - with(compose.density) { 24.dp.toPx() }).toInt()
            val samples = xs.map { pixels.getPixel((it - rootBounds.left).toInt(), y) }
            val greens = samples.map(android.graphics.Color::green)
            assertTrue("32dp blur removes the 8dp source stripes, unlike sharp translucency", greens.max() - greens.min() < 8)
            val tint = lerp(backdropColors.surface, backdropColors.primaryContainer, 0.20f).copy(alpha = 0.60f)
            val expected = tint.compositeOver(if (redBand) Color(0xFFFF8000) else Color(0xFF0080FF)).toArgb()
            assertColorNear("The live sampled band and 60% tint match at the capsule's actual coordinates", expected, samples[samples.size / 2], tolerance = 3)
            val plainY = (source.top + with(compose.density) { 360.dp.toPx() } - rootBounds.top).toInt()
            val sharpGreens = xs.map { android.graphics.Color.green(pixels.getPixel((it - rootBounds.left).toInt(), plainY)) }
            assertEquals("Only the capsule backing is blurred; exposed source stripes stay sharp", 255, sharpGreens.max() - sharpGreens.min())
            val pill = compose.onNodeWithTag("chatlist_folder_tab_pill_all", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(
                "The selected pill remains opaque and sharp",
                backdropColors.secondaryContainer.toArgb(),
                pixels.getPixel(((pill.left + pill.right) / 2 - rootBounds.left).toInt(), (pill.top + with(compose.density) { 2.dp.toPx() } - rootBounds.top).toInt()),
            )
            val ink =
                (pill.left.toInt() until pill.right.toInt()).sumOf { x ->
                    (pill.top.toInt() until pill.bottom.toInt()).count { py ->
                        pixels.getPixel((x - rootBounds.left).toInt(), (py - rootBounds.top).toInt()) == backdropColors.onSecondaryContainer.toArgb()
                    }
                }
            assertTrue("Tab text and icons retain solid foreground ink, not blurred artwork", ink > 10)
            val gutterX = (capsule.left - with(compose.density) { 4.dp.toPx() } - rootBounds.left).toInt()
            val gutter = pixels.getPixel(gutterX, y)
            assertTrue("External gutters expose untinted, unblurred source stripes", android.graphics.Color.green(gutter) in listOf(0, 255))
        }

        val initial = capture()
        assertBackdrop(initial, redBand = true)
        compose.onNodeWithTag("folder_backdrop_source").performSemanticsAction(SemanticsActions.ScrollBy) {
            assertTrue(it(0f, with(compose.density) { 160.dp.toPx() }))
        }
        val scrolled = capture()
        assertBackdrop(scrolled, redBand = false)
        compose.runOnIdle { capsuleTop.value = 208.dp }
        val repositioned = capture()
        assertBackdrop(repositioned, redBand = true)
    }

    @Test
    @Config(sdk = [26, 30], qualifiers = "w411dp-h891dp")
    fun folder_capsule_uses_sharp_60_percent_tint_without_native_blur() {
        setStripedBackdropContent(mutableStateOf(48.dp))
        assertSharpTintedBackdrop()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp")
    fun folder_preview_without_backdrop_source_keeps_translucent_tint() {
        setStripedBackdropContent(mutableStateOf(48.dp), attachSource = false)
        assertSharpTintedBackdrop()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp")
    fun folder_capsule_releases_backdrop_when_last_chat_disappears_and_recaptures_on_return() {
        val colors = lightColorScheme(surface = Color.Black, primaryContainer = Color.White)
        val rows = (1L..20L).map { row(it, "#room$it", folderId = null).copy(unreadCount = 1) }
        val state =
            mutableStateOf(
                ChatListState(
                    rows = rows,
                    folderDisplayMode = FolderDisplayMode.TABS,
                    mentionsEnabled = true,
                    mentionsPlacement = MentionsPlacement.FOLDER_TAB,
                    loading = false,
                ),
            )
        setContent(state, colorScheme = colors)
        val tintOnly = lerp(colors.surface, colors.primaryContainer, 0.20f).copy(alpha = 0.60f).compositeOver(colors.surface).toArgb()

        fun capsulePixel(): Int {
            val root = compose.onRoot()
            val rootBounds = root.fetchSemanticsNode().boundsInRoot
            val capsule = compose.onNodeWithTag("chatlist_folder_capsule").fetchSemanticsNode().boundsInRoot
            return root.captureToImage().asAndroidBitmap().getPixel(
                (capsule.right - with(compose.density) { 32.dp.toPx() } - rootBounds.left).toInt(),
                ((capsule.top + capsule.bottom) / 2 - rootBounds.top).toInt(),
            )
        }

        fun scrollUnreadRowsBehindCapsule() {
            compose.onNodeWithTag("chatlist_rows").performSemanticsAction(SemanticsActions.ScrollBy) {
                assertTrue(it(0f, with(compose.density) { 360.dp.toPx() }))
            }
            compose.waitForIdle()
            assertTrue(
                "The mounted unread rows visibly contribute to the backdrop before removal",
                android.graphics.Color.red(capsulePixel()) - android.graphics.Color.red(tintOnly) >= 5,
            )
        }

        scrollUnreadRowsBehindCapsule()
        compose.runOnIdle { state.value = state.value.copy(rows = emptyList()) }
        compose.onAllNodesWithTag("chatlist_rows").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_folder_tab_mentions").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        assertColorNear("The empty header shows only its tint, never disposed unread rows", tintOnly, capsulePixel())

        compose.runOnIdle { state.value = state.value.copy(rows = rows) }
        scrollUnreadRowsBehindCapsule()
    }

    @Test
    @Config(sdk = [31, 35], qualifiers = "w411dp-h891dp")
    fun title_backdrop_has_live_rows_beneath_it_without_folders_and_releases_empty_source() {
        val colors = lightColorScheme(surface = Color.Black, primaryContainer = Color.White)
        val rows = (1L..20L).map { row(it, "#room$it", folderId = null).copy(unreadCount = 1) }
        val state = mutableStateOf(ChatListState(rows = rows, loading = false))
        setContent(state, colorScheme = colors)
        val root = compose.onRoot()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("chatlist_top_app_bar").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("chatlist_rows").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("chatlist_row_surface_1").fetchSemanticsNode().boundsInRoot
        assertEquals("The source reaches the status/title area even without folder tabs", rootBounds.top, viewport.top, 0.5f)
        assertEquals("Removing body top padding must not move the resting first card", bar.bottom, first.top, 0.5f)
        val tintOnly = lerp(colors.surface, colors.primaryContainer, 0.20f).copy(alpha = 0.60f).compositeOver(colors.surface).toArgb()

        // Sample inside the card width, not its 8dp external gutter: the native blur also
        // samples the black canvas beyond the viewport edge. Stay above toolbar foreground ink.
        fun headerPixel(): Int =
            root.captureToImage().asAndroidBitmap().getPixel(
                (bar.center.x - rootBounds.left).toInt(),
                (bar.top - rootBounds.top + with(compose.density) { 8.dp.toPx() }).toInt(),
            )
        assertColorNear("The resting rectangular header uses the shared 60% tint", tintOnly, headerPixel())

        fun overlapHeader() {
            val currentBar = compose.onNodeWithTag("chatlist_top_app_bar").fetchSemanticsNode().boundsInRoot
            val initial = compose.onNodeWithTag("chatlist_row_surface_1").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("chatlist_rows").performSemanticsAction(SemanticsActions.ScrollBy) {
                assertTrue(it(0f, initial.top - currentBar.top))
            }
            compose.waitForIdle()
            val shifted = compose.onNodeWithTag("chatlist_row_surface_1").fetchSemanticsNode().boundsInRoot
            assertTrue("A real card occupies the title/status region, not a viewport clipped below it", shifted.top < currentBar.bottom && shifted.bottom > currentBar.top)
            val sampledRed = android.graphics.Color.red(headerPixel())
            val tintRed = android.graphics.Color.red(tintOnly)
            assertTrue("The title backing samples live cards, not a permanently tinted canvas (sample=$sampledRed, tint=$tintRed)", sampledRed - tintRed >= 5)
            assertEquals("Scrolling cannot move toolbar foreground", currentBar, compose.onNodeWithTag("chatlist_top_app_bar").fetchSemanticsNode().boundsInRoot)
        }
        overlapHeader()
        compose.runOnIdle { state.value = state.value.copy(rows = emptyList()) }
        compose.onAllNodesWithTag("chatlist_rows").assertCountEquals(0)
        assertColorNear("An empty title never replays the disposed capture", tintOnly, headerPixel())
        compose.runOnIdle { state.value = state.value.copy(rows = rows) }
        overlapHeader()
        compose.runOnIdle {
            state.value = state.value.copy(rows = emptyList(), archivedRows = rows.map { it.copy(archived = true) })
        }
        val archiveCard = compose.onNodeWithTag("chatlist_archived_folder")
        val archiveBounds = archiveCard.fetchSemanticsNode().boundsInRoot
        val currentBar = compose.onNodeWithTag("chatlist_top_app_bar").fetchSemanticsNode().boundsInRoot
        assertEquals("The archive-only card retains its original resting offset", currentBar.bottom + with(compose.density) { 2.dp.toPx() }, archiveBounds.top, 0.5f)
        archiveCard.performClick()
        val archiveBar = compose.onNodeWithTag("chatlist_top_app_bar").fetchSemanticsNode().boundsInRoot
        assertEquals(
            "Archive route rows retain the same initial header reservation",
            archiveBar.bottom,
            compose
                .onNodeWithTag("chatlist_row_surface_1")
                .fetchSemanticsNode()
                .boundsInRoot.top,
            0.5f,
        )
        overlapHeader()
    }

    @Test
    fun folder_tabs_keep_content_sized_pills_with_4dp_gaps_and_48dp_targets() {
        val folders = listOf(folder(7, "First"), folder(8, "Second"))
        val state =
            mutableStateOf(
                ChatListState(
                    rows = folders.map { row(it.id, "#room${it.id}", folderId = it.id) },
                    folders = folders,
                    folderDisplayMode = FolderDisplayMode.TABS,
                    loading = false,
                ),
            )
        setContent(state)

        val pillTags = listOf("chatlist_folder_tab_pill_all", "chatlist_folder_tab_pill_7", "chatlist_folder_tab_pill_8")
        val tabTags = listOf("chatlist_folder_tab_all", "chatlist_folder_tab_7", "chatlist_folder_tab_8")
        val pillBounds = pillTags.map { compose.onNodeWithTag(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
        val touchBounds = tabTags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val pillInset = with(compose.density) { 4.dp.toPx() }
        val minTouchHeight = with(compose.density) { 48.dp.toPx() }
        val minPillHeight = with(compose.density) { 40.dp.toPx() }
        pillBounds.zipWithNext().forEach { (left, right) ->
            assertEquals("Adjacent visible pills have a consistent 4dp gap", pillInset, right.left - left.right, 0.5f)
        }
        touchBounds.zip(pillBounds).forEach { (target, pill) ->
            assertEquals("Each tab should be 48dp tall", minTouchHeight, target.height, 0.5f)
            assertEquals("Each visible pill should be 40dp tall", minPillHeight, pill.height, 0.5f)
            assertEquals(pillInset, pill.top - target.top, 0.5f)
            assertEquals(pillInset, target.bottom - pill.bottom, 0.5f)
            assertEquals("Targets add no horizontal padding", target.width, pill.width, 0.5f)
        }
        assertTrue("Tab widths follow their label content, not equal shares of the capsule", pillBounds[2].width > pillBounds[1].width)
        val icon = compose.onNodeWithTag("chatlist_folder_tab_icon_7", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val originalIconSize = with(compose.density) { 20.dp.toPx() }
        assertEquals("Folder icons keep their original 20dp width", originalIconSize, icon.width, 0.5f)
        assertEquals("Folder icons keep their original 20dp height", originalIconSize, icon.height, 0.5f)
    }

    @Test
    fun folder_tabs_align_visible_pills_with_chat_content_at_both_scroll_extremes() {
        val folders = (1L..12L).map { folder(it, "Folder $it") }
        val state =
            mutableStateOf(
                ChatListState(
                    rows = folders.map { row(it.id, "#room${it.id}", folderId = it.id) },
                    folders = folders,
                    folderDisplayMode = FolderDisplayMode.TABS,
                    showFolderChatsInAll = false,
                    loading = false,
                ),
            )
        setContent(state)

        compose.onAllNodesWithTag("chatlist_folder_tab_all").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_folder_tab_1").assertIsSelected()
        val root = compose.onRoot()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val strip = compose.onNodeWithTag("chatlist_folder_tabs")
        val viewportBounds = strip.fetchSemanticsNode().boundsInRoot
        val scrollRange = strip.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        compose.runOnIdle {
            assertTrue("The fixture must overflow the folder viewport", scrollRange.maxValue() > 0f)
            assertEquals(0f, scrollRange.value(), 0.5f)
        }
        val outerInset = with(compose.density) { 8.dp.toPx() }
        val rowInset = with(compose.density) { 12.dp.toPx() }
        val capsule = compose.onNodeWithTag("chatlist_folder_capsule").fetchSemanticsNode().boundsInRoot
        val firstTab = compose.onNodeWithTag("chatlist_folder_tab_1").fetchSemanticsNode().boundsInRoot
        val firstPill = compose.onNodeWithTag("chatlist_folder_tab_pill_1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val firstRow =
            compose
                .onNodeWithTag("chatlist_row_1")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals("The capsule starts at the chat card's outer edge", firstRow.left, capsule.left, 0.5f)
        assertEquals("The capsule ends at the chat card's outer edge", firstRow.right, capsule.right, 0.5f)
        assertEquals("The capsule has an 8dp leading scaffold gutter", outerInset, capsule.left - rootBounds.left, 0.5f)
        assertEquals("The first visible pill starts at the avatar's left edge", firstRow.left + rowInset, firstPill.left, 0.5f)
        assertEquals("The first visible pill starts 12dp inside the capsule", capsule.left + rowInset, firstPill.left, 0.5f)
        assertEquals("The first folder target starts with its pill", firstPill.left, firstTab.left, 0.5f)
        assertEquals("The first folder target is inset 12dp from the scroll viewport", viewportBounds.left + rowInset, firstTab.left, 0.5f)

        // Reach the last tab before clicking: offscreen touch targets are not clickable.
        strip.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(scrollRange.maxValue(), 0f)) }
        compose.waitForIdle()
        compose.onNodeWithTag("chatlist_folder_tab_12").performClick().assertIsSelected()
        // Selection brings the last tab into view; explicitly reach the native scroll limit.
        strip.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(scrollRange.maxValue(), 0f)) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(scrollRange.maxValue(), scrollRange.value(), 0.5f) }
        val lastTab =
            compose
                .onNodeWithTag("chatlist_folder_tab_12")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val lastPill = compose.onNodeWithTag("chatlist_folder_tab_pill_12", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val lastRow =
            compose
                .onNodeWithTag("chatlist_row_12")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val lastCapsule = compose.onNodeWithTag("chatlist_folder_capsule").fetchSemanticsNode().boundsInRoot
        assertEquals("The capsule starts at the chat card's outer edge after scrolling", lastRow.left, lastCapsule.left, 0.5f)
        assertEquals("The capsule ends at the chat card's outer edge after scrolling", lastRow.right, lastCapsule.right, 0.5f)
        assertEquals("The trailing capsule gutter is 8dp", outerInset, rootBounds.right - lastCapsule.right, 0.5f)
        assertEquals("The last visible pill ends at the symmetric chat content inset", lastRow.right - rowInset, lastPill.right, 0.5f)
        assertEquals("The last visible pill ends 12dp inside the capsule", lastCapsule.right - rowInset, lastPill.right, 0.5f)
        assertEquals("The last folder target ends with its pill", lastPill.right, lastTab.right, 0.5f)
        assertEquals("The last tab keeps the trailing 12dp viewport inset", viewportBounds.right - rowInset, lastTab.right, 0.5f)
    }

    @Test
    fun folder_tab_edge_taps_preserve_48dp_target_outside_visible_pill() {
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row(1, "#dev", folderId = 7)),
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    loading = false,
                ),
            )
        setContent(state)
        val tab = compose.onNodeWithTag("chatlist_folder_tab_7")

        val bounds = tab.fetchSemanticsNode().boundsInRoot
        val minTouchHeight = with(compose.density) { 48.dp.toPx() }
        assertEquals(minTouchHeight, bounds.height, 0.5f)
        assertEquals(MotdShapes.channelAvatar, tab.fetchSemanticsNode().config[SemanticsProperties.Shape])
        val pill = compose.onNodeWithTag("chatlist_folder_tab_pill_7", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val pillInset = with(compose.density) { 4.dp.toPx() }
        assertEquals(pillInset, pill.top - bounds.top, 0.5f)
        assertEquals(pillInset, bounds.bottom - pill.bottom, 0.5f)

        // The target remains tappable above and below the visible pill.
        tab.performTouchInput { click(Offset(center.x, 2f)) }
        tab.assertIsSelected()
        compose.onNodeWithTag("chatlist_folder_tab_all").performClick().assertIsSelected()
        tab.performTouchInput { click(Offset(center.x, height - 2f)) }
        tab.assertIsSelected()
    }

    @Test
    fun tabs_all_only_shows_unassigned_chats_when_folder_chats_hidden() {
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row(), row(2, "#other", folderId = null)),
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    showFolderChatsInAll = false,
                    loading = false,
                ),
            )
        setContent(state)

        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        compose.onAllNodesWithTag("chatlist_row_1").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_row_2").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_tab_7").performClick()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
        compose.onAllNodesWithTag("chatlist_row_2").assertCountEquals(0)
    }

    @Test
    fun tabs_omit_empty_all_and_select_first_folder() {
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row()),
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    showFolderChatsInAll = false,
                    loading = false,
                ),
            )
        setContent(state)

        compose.onAllNodesWithTag("chatlist_folder_tab_all").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_folder_tab_7").assertIsSelected()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
    }

    @Test
    fun tabs_all_routes_remain_for_archives_and_invitations() {
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row()),
                    invitations = listOf(invitation()),
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    showFolderChatsInAll = false,
                    loading = false,
                ),
            )
        setContent(state)

        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        compose.onAllNodesWithTag("chatlist_row_1").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_invitations_folder").assertIsDisplayed()

        compose.onNodeWithTag("chatlist_folder_tab_7").performClick()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
        compose.onAllNodesWithTag("chatlist_invitations_folder").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_folder_tab_all").performClick()

        state.value =
            state.value.copy(
                archivedRows = listOf(row(3, "#archived", folderId = 7)),
                invitations = emptyList(),
            )
        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        val folderOverlay = compose.onNodeWithTag("chatlist_folder_overlay").fetchSemanticsNode().boundsInRoot
        val archivedFolder = compose.onNodeWithTag("chatlist_archived_folder")
        assertTrue("The archived-folder action stays below the folder overlay", archivedFolder.fetchSemanticsNode().boundsInRoot.top >= folderOverlay.bottom)
        archivedFolder.performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithTag("chatlist_archived_folder").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_rows").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_3").assertIsDisplayed()
    }

    @Test
    fun portal_channel_invitation_survives_lab_toggle_and_remains_actionable() {
        val portal = row(41, "#discord.guild.invited", folderId = null)
        val invitation =
            invitation().copy(
                bufferId = portal.bufferId,
                channel = portal.displayName,
                text = "alice invited you to ${portal.displayName}",
            )
        val state =
            mutableStateOf(
                ChatListState(
                    invitations = listOf(invitation),
                    dickordEnabled = true,
                    dickordUnreadSummary = summarizeFolder(listOf(portal)),
                    loading = false,
                ),
            )
        var accepted: Long? = null
        var ignored: Long? = null
        setContent(state) {
            onAcceptInvitation = { accepted = it }
            onIgnoreInvitation = { ignored = it }
        }

        compose.onNodeWithTag("chatlist_invitations_folder").performClick()
        compose.onNodeWithTag("chatlist_invitation_11").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_invitation_join_11").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_invitation_ignore_11").assertIsDisplayed()

        state.value = state.value.copy(dickordEnabled = false, dickordUnreadSummary = null)

        compose.onNodeWithTag("chatlist_invitation_11").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(null, accepted)
            assertEquals(null, ignored)
        }
        compose.onNodeWithTag("chatlist_invitation_join_11").performClick()
        compose.runOnIdle { assertEquals(11L, accepted) }
    }

    @Test
    fun selected_folder_disappearance_falls_back_to_all() {
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row(1, "#dev", folderId = 7), row(2, "#ops", folderId = 8)),
                    folders = listOf(folder(), folder(8, "Ops")),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    loading = false,
                ),
            )
        setContent(state)
        compose.onNodeWithTag("chatlist_folder_tab_7").performClick()

        state.value = state.value.copy(rows = listOf(row(2, "#ops", folderId = 8)))

        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        compose.onNodeWithTag("chatlist_row_2").assertIsDisplayed()
    }

    @Test
    fun selected_folder_survives_transient_loading_snapshot() {
        val loaded =
            ChatListState(
                rows = listOf(row()),
                folders = listOf(folder()),
                folderDisplayMode = FolderDisplayMode.TABS,
                loading = false,
            )
        val state = mutableStateOf(loaded)
        setContent(state)
        compose.onNodeWithTag("chatlist_folder_tab_7").performClick()

        state.value = ChatListState(folderDisplayMode = FolderDisplayMode.TABS, loading = true)
        state.value = loaded

        compose.onNodeWithTag("chatlist_folder_tab_7").assertIsSelected()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
    }

    @Test
    fun tab_switch_clears_selection_and_starts_each_list_at_top() {
        val allRows = (1L..15L).map { row(it, "#room$it", folderId = null) } + row(100, "#dev", folderId = 7)
        val state =
            mutableStateOf(
                ChatListState(
                    rows = allRows,
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    loading = false,
                ),
            )
        setContent(state)

        compose.onNodeWithTag("chatlist_rows").performScrollToIndex(14)
        compose.onNodeWithTag("chatlist_row_15").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_tab_7").performClick()
        compose.onAllNodesWithTag("chatlist_selection_top_app_bar").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_row_100").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_tab_all").performClick()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
    }

    @Test
    fun many_tabs_scroll_horizontally() {
        val folders = (1L..12L).map { folder(it, "Folder $it") }
        val state =
            mutableStateOf(
                ChatListState(
                    rows = folders.map { row(it.id, "#room${it.id}", folderId = it.id) },
                    folders = folders,
                    folderDisplayMode = FolderDisplayMode.TABS,
                    loading = false,
                ),
            )
        setContent(state)

        repeat(5) { compose.onNodeWithTag("chatlist_folder_tabs").performTouchInput { swipeLeft() } }
        compose.onNodeWithTag("chatlist_folder_tab_12").assertIsDisplayed()
    }

    @Test
    fun stock_row_swipe_archives_in_tabs_mode() {
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row()),
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    loading = false,
                ),
            )
        var archived: Pair<List<Long>, Boolean>? = null
        setContent(state) {
            onSetArchived = { ids, value -> archived = ids.toList() to value }
        }
        compose.onNodeWithTag("chatlist_folder_tab_7").performClick()

        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }

        compose.runOnIdle { assertEquals(listOf(1L) to true, archived) }
    }

    @Test
    fun assignment_sheet_clears_selection_only_after_success() {
        val succeeds = mutableStateOf(false)
        val state = mutableStateOf(ChatListState(rows = listOf(row().copy(folderId = null)), loading = false))
        setContent(state) {
            onAssignFolder = { _, _, done -> done(succeeds.value) }
        }

        compose.onNodeWithTag("chatlist_row_1").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_selection_more").performClick()
        compose.onNodeWithTag("chatlist_selection_add_folder").performClick()
        compose.onNodeWithTag("folder_destination_none").performClick()
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()

        succeeds.value = true
        compose.onNodeWithTag("folder_destination_none").performClick()
        compose.onAllNodesWithTag("chatlist_selection_top_app_bar").assertCountEquals(0)
    }

    private val backdropColors =
        lightColorScheme(
            surface = Color(0xFF27313D),
            primaryContainer = Color(0xFF586C80),
            secondaryContainer = Color(0xFF5B347A),
            onSecondaryContainer = Color.White,
        )

    private fun setStripedBackdropContent(
        capsuleTop: State<Dp>,
        attachSource: Boolean = true,
    ) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                MaterialTheme(colorScheme = backdropColors) {
                    val backdrop = if (attachSource) rememberSurfaceBackdropSource() else null
                    Box(Modifier.fillMaxSize().background(Color.Magenta).padding(start = 24.dp, top = 40.dp, end = 12.dp, bottom = 12.dp)) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .then(backdrop?.let { Modifier.captureSurfaceBackdrop(it).background(backdropColors.surface) } ?: Modifier)
                                .verticalScroll(rememberScrollState())
                                .testTag("folder_backdrop_source"),
                        ) {
                            Canvas(Modifier.fillMaxWidth().height(1600.dp)) {
                                val stripe = 8.dp.toPx()
                                val band = 160.dp.toPx()
                                repeat(10) { row ->
                                    var column = 0
                                    while (column * stripe < size.width) {
                                        val color =
                                            if (row % 2 == 0) {
                                                if (column % 2 == 0) Color.Red else Color.Yellow
                                            } else {
                                                if (column % 2 == 0) Color.Blue else Color.Cyan
                                            }
                                        drawRect(color, Offset(column * stripe, row * band), Size(stripe, band))
                                        column++
                                    }
                                }
                            }
                        }
                        Box(Modifier.padding(start = 16.dp, top = capsuleTop.value, end = 8.dp)) {
                            FolderTabStrip(
                                folders = emptyList(),
                                allSummary = summarizeFolder(emptyList()),
                                showAllTab = true,
                                dickordSummary = null,
                                mentionsEnabled = false,
                                mentionsCount = 0,
                                mentionsIncomplete = false,
                                selectedFolderId = null,
                                onSelect = {},
                                onOpenDickord = {},
                                onOpenMentions = {},
                                backdrop = backdrop,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun assertSharpTintedBackdrop() {
        val root = compose.onRoot()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val capsule = compose.onNodeWithTag("chatlist_folder_capsule").fetchSemanticsNode().boundsInRoot
        val source = compose.onNodeWithTag("folder_backdrop_source").fetchSemanticsNode().boundsInRoot
        val pixels = root.captureToImage().asAndroidBitmap()
        val y = ((capsule.top + capsule.bottom) / 2 - rootBounds.top).toInt()
        val stripe = with(compose.density) { 8.dp.toPx() }
        val tint = lerp(backdropColors.surface, backdropColors.primaryContainer, 0.20f).copy(alpha = 0.60f)
        val xs = (capsule.right - with(compose.density) { 96.dp.toPx() }).toInt() until (capsule.right - with(compose.density) { 24.dp.toPx() }).toInt()
        val samples =
            xs.map { x ->
                val sourceColumn = ((x + 0.5f - source.left) / stripe).toInt()
                val expected = tint.compositeOver(if (sourceColumn % 2 == 0) Color.Red else Color.Yellow).toArgb()
                val actual = pixels.getPixel((x - rootBounds.left).toInt(), y)
                assertColorNear("Without a native source, the capsule only tints the sharp canvas", expected, actual)
                android.graphics.Color.green(actual)
            }
        assertTrue("Tint-only fallback preserves 40% of the source stripe contrast instead of claiming blur", samples.max() - samples.min() >= 99)
    }

    private fun assertColorNear(
        message: String,
        expected: Int,
        actual: Int,
        tolerance: Int = 2,
    ) {
        for (shift in listOf(16, 8, 0)) {
            assertEquals(message, ((expected shr shift) and 0xFF).toDouble(), ((actual shr shift) and 0xFF).toDouble(), tolerance.toDouble())
        }
    }

    private fun setContent(
        state: androidx.compose.runtime.State<ChatListState>,
        colorScheme: ColorScheme? = null,
        callbacks: Callbacks.() -> Unit = {},
    ) {
        val configured = Callbacks().apply(callbacks)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                MaterialTheme(colorScheme = colorScheme ?: MaterialTheme.colorScheme) {
                    ChatListContent(
                        state = state.value,
                        onOpenBuffer = configured.onOpenBuffer,
                        onOpenSettings = {},
                        onOpenSearch = {},
                        onOpenDickord = configured.onOpenDickord,
                        onSetPinned = { _, _ -> },
                        onSetMuted = { _, _ -> },
                        onSetArchived = configured.onSetArchived,
                        onJoinChannel = { _, _, _ -> },
                        onMessageUser = { _, _ -> },
                        onAssignFolder = configured.onAssignFolder,
                        onSetFolderExpanded = configured.onSetFolderExpanded,
                        onOpenFolderEditor = configured.onOpenFolderEditor,
                        onAcceptInvitation = configured.onAcceptInvitation,
                        onIgnoreInvitation = configured.onIgnoreInvitation,
                    )
                }
            }
        }
    }

    private class Callbacks {
        var onOpenBuffer: (Long) -> Unit = {}
        var onOpenDickord: () -> Unit = {}
        var onAcceptInvitation: (Long) -> Unit = {}
        var onIgnoreInvitation: (Long) -> Unit = {}
        var onSetArchived: (Collection<Long>, Boolean) -> Unit = { _, _ -> }
        var onAssignFolder: (Collection<Long>, Long?, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) }
        var onSetFolderExpanded: (Long, Boolean) -> Unit = { _, _ -> }
        var onOpenFolderEditor: (Long) -> Unit = {}
    }

    private fun invitation() =
        ChatListInvitation(
            messageId = 11,
            bufferId = 1,
            networkId = 1,
            networkName = "net",
            inviter = "alice",
            channel = "#dev",
            text = "alice invited you to #dev",
            state = InviteState.PENDING,
            serverTime = 1,
        )

    private fun folder(
        id: Long = 7,
        name: String = "Dev",
    ) = ChatFolderEntity(id = id, displayName = name, normalizedName = name.lowercase(), ordering = id.toInt(), expanded = false)

    private fun row(
        id: Long = 1,
        name: String = "#dev",
        folderId: Long? = 7,
        mentions: Int = 0,
        pinned: Boolean = false,
        archived: Boolean = false,
        incomplete: Boolean = false,
    ) = ChatListRow(
        bufferId = id,
        networkId = 1,
        networkName = "net",
        displayName = name,
        type = BufferType.CHANNEL,
        pinned = pinned,
        muted = false,
        archived = archived,
        folderId = folderId,
        lastMessageText = "hello",
        lastMessageSender = "alice",
        lastMessageTime = id,
        unreadCount = 0,
        mentionCount = mentions,
        unreadCountIncomplete = incomplete,
        mentionCountIncomplete = incomplete,
    )
}
