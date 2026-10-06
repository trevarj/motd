package io.github.trevarj.motd

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
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
import io.github.trevarj.motd.ui.chatlist.ordinaryChatListRows
import io.github.trevarj.motd.ui.chatlist.partitionArchivedRows
import io.github.trevarj.motd.ui.chatlist.summarizeFolder
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
    fun folder_tabs_show_only_selected_pill_without_underline_or_separator() {
        val colors =
            lightColorScheme(
                surface = Color(0xFFABCDEF),
                surfaceContainerHigh = Color(0xFFDDEEFF),
                primary = Color(0xFF3322CC),
                onPrimary = Color.White,
            )
        val state =
            mutableStateOf(
                ChatListState(
                    rows = (1L..20L).map { id -> row(id, "#room$id", folderId = if (id % 2 == 1L) 7L else null) },
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
        assertEquals(colors.primary.toArgb(), selectedFill)
        assertEquals(colors.surfaceContainerHigh.toArgb(), plainFill)

        val strip = compose.onNodeWithTag("chatlist_folder_tabs")
        val stripBounds = strip.fetchSemanticsNode().boundsInRoot
        val pillBounds = compose.onNodeWithTag(allTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val pixels = strip.captureToImage().asAndroidBitmap()
        val left = (pillBounds.left - stripBounds.left).toInt()
        val top = (pillBounds.top - stripBounds.top).toInt()
        val center = ((pillBounds.left + pillBounds.right) / 2 - stripBounds.left).toInt()
        assertEquals(colors.surfaceContainerHigh.toArgb(), pixels.getPixel(center, 0))
        assertEquals(colors.surfaceContainerHigh.toArgb(), pixels.getPixel(center, pixels.height - 1))
        val cornerInset = with(compose.density) { 2.dp.toPx().toInt() }
        val shoulderInset = with(compose.density) { 7.dp.toPx().toInt() }
        // The avatar-like corner reaches the top edge sooner than a fully round capsule.
        assertEquals(plainFill, pixels.getPixel(left + cornerInset, top + cornerInset))
        assertEquals(selectedFill, pixels.getPixel(left + shoulderInset, top + cornerInset))
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

        val underStripX = rootBounds.left + with(compose.density) { 18.dp.toPx() }
        val underStripY = overlay.top + with(compose.density) { 28.dp.toPx() }
        assertTrue(underStripY in shiftedRow.top..shiftedRow.bottom)
        assertEquals("The transparent strip reveals the moving chat row", colors.surface.toArgb(), rootPixel(underStripX, underStripY))
        val capsuleBounds = compose.onNodeWithTag("chatlist_folder_capsule").fetchSemanticsNode().boundsInRoot
        assertEquals(
            "The shared capsule remains visible above the row",
            colors.surfaceContainerHigh.toArgb(),
            rootPixel(capsuleBounds.right - with(compose.density) { 8.dp.toPx() }, underStripY),
        )
        val selectedBounds = compose.onNodeWithTag(allTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(
            "The selected pill remains primary above the row",
            colors.primary.toArgb(),
            rootPixel((selectedBounds.left + selectedBounds.right) / 2, selectedBounds.top + with(compose.density) { 2.dp.toPx() }),
        )
        var onPrimaryVisible = false
        for (x in (selectedBounds.left - rootBounds.left).toInt() until (selectedBounds.right - rootBounds.left).toInt()) {
            for (y in (selectedBounds.top - rootBounds.top).toInt() until (selectedBounds.bottom - rootBounds.top).toInt()) {
                if (pixelsUnderStrip.getPixel(x, y) == colors.onPrimary.toArgb()) {
                    onPrimaryVisible = true
                    break
                }
            }
            if (onPrimaryVisible) break
        }
        assertTrue("Selected tab content uses onPrimary", onPrimaryVisible)

        compose.onNodeWithTag("chatlist_pinned_mentions").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_tab_7").performClick().assertIsSelected()
        assertEquals(plainFill, pillPixel(allTag))
        assertNotEquals(plainFill, pillPixel(folderTag))
    }

    @Test
    fun folder_strip_keeps_transparent_backdrop_and_distinct_capsule_in_light_and_dark_themes() {
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
            val outerInset = with(compose.density) { 20.dp.toPx() }
            val viewportInset = with(compose.density) { 30.dp.toPx() }
            val sampleInset = with(compose.density) { 1.dp.toPx() }
            assertEquals(rootBounds.left + outerInset, bounds.left, 0.5f)
            assertEquals(rootBounds.right - outerInset, bounds.right, 0.5f)
            assertEquals(rootBounds.left + viewportInset, viewport.left, 0.5f)
            assertEquals(rootBounds.right - viewportInset, viewport.right, 0.5f)
            assertTrue("The shared surface must not consume tab selection", !capsuleNode.config.contains(SemanticsActions.OnClick))

            val pixels = root.captureToImage().asAndroidBitmap()

            fun pixel(
                x: Float,
                y: Float,
            ) = pixels.getPixel((x - rootBounds.left).toInt(), (y - rootBounds.top).toInt())

            val centerY = (bounds.top + bounds.bottom) / 2
            assertEquals("The transparent strip shows the unified chat surface", colors.surface.toArgb(), pixel(rootBounds.left + sampleInset, centerY))
            assertEquals("The shared capsule uses a distinct elevated surface", colors.surfaceContainerHigh.toArgb(), pixel(bounds.left + 2 * sampleInset, centerY))
            val topBar = compose.onNodeWithTag("chatlist_top_app_bar").fetchSemanticsNode().boundsInRoot
            assertEquals("The title bar retains its original surface color", colors.surface.toArgb(), pixel(topBar.left + sampleInset, (topBar.top + topBar.bottom) / 2))
            compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
            val selected =
                compose
                    .onNodeWithTag("chatlist_folder_tab_pill_all", useUnmergedTree = true)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertEquals("The selected pill uses the stronger theme primary color", colors.primary.toArgb(), pixel((selected.left + selected.right) / 2, selected.top + 2 * sampleInset))
            compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
        }
    }

    @Test
    fun folder_tabs_keep_pills_close_with_full_touch_targets() {
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
        val maxPillGap = with(compose.density) { 12.dp.toPx() }
        val minTouchHeight = with(compose.density) { 48.dp.toPx() }
        pillBounds.zipWithNext().forEach { (left, right) ->
            assertTrue("Adjacent pills should have less than 12dp between them", right.left - left.right < maxPillGap)
        }
        touchBounds.forEach { bounds ->
            assertTrue("Each tab should retain a 48dp touch target", bounds.height >= minTouchHeight)
        }
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
        val outerInset = with(compose.density) { 20.dp.toPx() }
        val rowInset = with(compose.density) { 12.dp.toPx() }
        val pillInset = with(compose.density) { 2.dp.toPx() }
        val capsule = compose.onNodeWithTag("chatlist_folder_capsule").fetchSemanticsNode().boundsInRoot
        val firstPill = compose.onNodeWithTag("chatlist_folder_tab_pill_1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val firstRow =
            compose
                .onNodeWithTag("chatlist_row_1")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals("The capsule starts at the avatar edge", firstRow.left + rowInset, capsule.left, 0.5f)
        assertEquals("The capsule has a 20dp leading scaffold gutter", outerInset, capsule.left - rootBounds.left, 0.5f)
        assertEquals("The first folder button stays inset inside the capsule", capsule.left + with(compose.density) { 12.dp.toPx() }, firstPill.left, 0.5f)
        assertEquals("The first folder button follows the scroll viewport", viewportBounds.left + pillInset, firstPill.left, 0.5f)

        // Reach the last tab before clicking: offscreen touch targets are not clickable.
        strip.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(scrollRange.maxValue(), 0f)) }
        compose.waitForIdle()
        compose.onNodeWithTag("chatlist_folder_tab_12").performClick().assertIsSelected()
        // Selection brings the last tab into view; explicitly reach the native scroll limit.
        strip.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(scrollRange.maxValue(), 0f)) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(scrollRange.maxValue(), scrollRange.value(), 0.5f) }
        val lastPill =
            compose
                .onNodeWithTag("chatlist_folder_tab_pill_12", useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val lastRow =
            compose
                .onNodeWithTag("chatlist_row_12")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val lastCapsule = compose.onNodeWithTag("chatlist_folder_capsule").fetchSemanticsNode().boundsInRoot
        assertEquals("The capsule ends at the avatar edge", lastRow.right - rowInset, lastCapsule.right, 0.5f)
        assertEquals("The trailing capsule gutter is 20dp", outerInset, rootBounds.right - lastCapsule.right, 0.5f)
        assertEquals("The last pill retains its inset inside the fixed viewport", viewportBounds.right - pillInset, lastPill.right, 0.5f)
    }

    @Test
    fun folder_tab_edge_tap_preserves_full_touch_target() {
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
        assertTrue(bounds.height >= minTouchHeight)
        assertEquals(MotdShapes.channelAvatar, tab.fetchSemanticsNode().config[SemanticsProperties.Shape])

        // The top edge stays tappable even though the visible pill is shorter than the target.
        tab.performTouchInput { click(Offset(center.x, 2f)) }
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
