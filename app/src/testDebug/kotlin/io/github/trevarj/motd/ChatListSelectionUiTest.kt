package io.github.trevarj.motd

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.ChatListSwipeAction
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.ui.chatlist.ArchiveAccessibilityAnnouncement
import io.github.trevarj.motd.ui.chatlist.ArchiveFolderPull
import io.github.trevarj.motd.ui.chatlist.ChatListContent
import io.github.trevarj.motd.ui.chatlist.ChatListDefaultTitle
import io.github.trevarj.motd.ui.chatlist.ChatListInvitation
import io.github.trevarj.motd.ui.chatlist.ChatListRowItem
import io.github.trevarj.motd.ui.chatlist.ChatListState
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.spacingFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ChatListSelectionUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun selected_row_exposes_selected_semantics_on_the_full_row() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListRowItem(row(), false, {}, {}, selected = true)
            }
        }

        compose
            .onNodeWithTag("chatlist_row_1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test fun invitation_folder_hides_after_all_are_handled_until_another_arrives() {
        val first = invitation(11, "#one")
        val second = invitation(12, "#two")
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row()),
                    invitations = listOf(first, second),
                    networks = listOf(network()),
                    loading = false,
                ),
            )
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = state.value,
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                    onAcceptInvitation = { id ->
                        state.value =
                            state.value.copy(
                                invitations =
                                    state.value.invitations.map {
                                        if (it.messageId == id) it.copy(state = InviteState.JOINED) else it
                                    },
                            )
                    },
                    onIgnoreInvitation = { id ->
                        state.value =
                            state.value.copy(
                                invitations =
                                    state.value.invitations.map {
                                        if (it.messageId == id) it.copy(state = InviteState.DISMISSED) else it
                                    },
                            )
                    },
                )
            }
        }

        compose.onNodeWithTag("chatlist_invitations_folder").assertIsDisplayed().performClick()
        compose.onNodeWithText("Invitations").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_invitation_join_11").performClick()
        compose.onNodeWithTag("chatlist_invitation_ignore_12").performClick()

        compose
            .onNodeWithTag("chatlist_invitation_11")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Joined"))
        compose
            .onNodeWithTag("chatlist_invitation_12")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ignored"))
        assertEquals(0, compose.onAllNodesWithTag("chatlist_invitation_join_11").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("chatlist_invitation_ignore_12").fetchSemanticsNodes().size)

        compose.onNodeWithTag("chatlist_selection_close").performClick()
        assertEquals(0, compose.onAllNodesWithTag("chatlist_invitations_folder").fetchSemanticsNodes().size)

        compose.runOnIdle {
            state.value =
                state.value.copy(
                    invitations = state.value.invitations + invitation(13, "#three"),
                )
        }
        compose.onNodeWithTag("chatlist_invitations_folder").assertIsDisplayed()
    }

    @Test fun collapsing_fools_clears_their_selection_and_contextual_actions() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = ChatListState(rows = listOf(row().copy(displayName = "fool")), fools = setOf("fool"), loading = false),
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

        compose.onNodeWithText("FOOLS (1)").performClick()
        assertEquals(1, compose.onAllNodesWithTag("chatlist_row_surface_1").fetchSemanticsNodes().size)
        compose.onNodeWithTag("chatlist_row_1").performTouchInput { longClick() }
        assertEquals(1, compose.onAllNodesWithTag("chatlist_selection_top_app_bar").fetchSemanticsNodes().size)

        compose.onNodeWithText("FOOLS (1)").performClick()
        assertEquals(0, compose.onAllNodesWithTag("chatlist_row_1").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("chatlist_selection_top_app_bar").fetchSemanticsNodes().size)
    }

    @Test fun unscoped_title_uses_lowercase_text() {
        compose.setContent {
            MotdTheme(dynamicColor = false) { ChatListDefaultTitle(titleConnecting = false) }
        }

        val title =
            compose
                .onNodeWithTag("chatlist_title", useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .single()
                .text
        assertTrue(title.startsWith("motd"))
        assertEquals(0, compose.onAllNodesWithText("/motd").fetchSemanticsNodes().size)
    }

    @Test fun archive_pull_reveals_while_held_after_early_release_and_cancel_settle_back() {
        assertTouchPullOpensArchive(changeGeometryDuringPull = false)
    }

    @Test fun archive_pull_hold_survives_layout_density_remeasurement() {
        assertTouchPullOpensArchive(changeGeometryDuringPull = true)
    }

    @Test fun archive_pull_completed_reveal_survives_native_cancel_and_queued_drag() {
        assertTouchPullOpensArchive(changeGeometryDuringPull = false, finishWithCancel = true)
    }

    private fun advanceArchivePullClock(millis: Long) {
        compose.runOnUiThread { ShadowSystemClock.advanceBy(Duration.ofMillis(millis)) }
        compose.mainClock.advanceTimeBy(millis, ignoreFrameDuration = true)
        compose.waitForIdle()
    }

    private fun assertTouchPullOpensArchive(
        changeGeometryDuringPull: Boolean,
        finishWithCancel: Boolean = false,
    ) {
        val density = mutableStateOf(LayoutDensity.COMPACT)
        val state =
            ChatListState(
                rows = (1L..20L).map { row().copy(bufferId = it) },
                archivedRows = listOf(row().copy(bufferId = 99, displayName = "archived", archived = true)),
                loading = false,
            )
        compose.setContent {
            MotdTheme(dynamicColor = false, layoutDensity = density.value) {
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

        val rowBounds = compose.onNodeWithTag("chatlist_row_1").getUnclippedBoundsInRoot()
        val initialHeight = rowBounds.bottom - rowBounds.top
        compose.mainClock.autoAdvance = false

        fun assertArmed() {
            // The pull card deliberately clears its test tag from accessibility semantics.
            compose
                .onNode(
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Keep holding…"),
                    useUnmergedTree = true,
                ).assertIsDisplayed()
            compose.onNodeWithTag("chatlist_archived_folder").assertDoesNotExist()
        }

        if (!changeGeometryDuringPull && !finishWithCancel) {
            compose.onNodeWithTag("chatlist_rows").performTouchInput {
                down(center)
                moveBy(Offset(0f, initialHeight.toPx() / 4f))
                moveBy(Offset(0f, initialHeight.toPx() / 4f))
            }
            advanceArchivePullClock(32)
            assertTrue(compose.onNodeWithTag("chatlist_row_1").getUnclippedBoundsInRoot().top > rowBounds.top)
            compose.onNodeWithTag("chatlist_rows").performTouchInput { up() }
            advanceArchivePullClock(ArchiveFolderPull.HoldMillis + 200)
            compose.onNodeWithTag("chatlist_archived_folder").assertDoesNotExist()
            assertEquals(rowBounds, compose.onNodeWithTag("chatlist_row_1").getUnclippedBoundsInRoot())

            for (cancelled in listOf(false, true)) {
                compose.onNodeWithTag("chatlist_rows").performTouchInput {
                    down(center)
                    moveBy(Offset(0f, initialHeight.toPx()))
                    moveBy(Offset(0f, initialHeight.toPx()))
                }
                advanceArchivePullClock(32)
                assertArmed()
                advanceArchivePullClock(ArchiveFolderPull.HoldMillis / 2)
                assertArmed()
                compose.onNodeWithTag("chatlist_rows").performTouchInput {
                    if (cancelled) cancel() else up()
                }
                advanceArchivePullClock(ArchiveFolderPull.HoldMillis + 200)
                compose.onNodeWithTag("chatlist_archived_folder").assertDoesNotExist()
                assertEquals(rowBounds, compose.onNodeWithTag("chatlist_row_1").getUnclippedBoundsInRoot())
            }
        }

        compose.onNodeWithTag("chatlist_rows").performTouchInput {
            down(center)
            moveBy(Offset(0f, initialHeight.toPx()))
            moveBy(Offset(0f, initialHeight.toPx()))
        }
        advanceArchivePullClock(32)
        assertArmed()
        advanceArchivePullClock(ArchiveFolderPull.HoldMillis / 2)
        assertArmed()
        if (changeGeometryDuringPull) {
            compose.runOnUiThread {
                density.value = LayoutDensity.COMFORTABLE
                // A paused Compose clock does not pump global snapshot apply notifications.
                Snapshot.sendApplyNotifications()
            }
            // Compose, then measure; each frame also drains Android layout via waitForIdle.
            advanceArchivePullClock(16)
            advanceArchivePullClock(16)
            val changedBounds = compose.onNodeWithTag("chatlist_row_1").getUnclippedBoundsInRoot()
            assertTrue(changedBounds.bottom - changedBounds.top > initialHeight)
            assertArmed()
        }
        // More than 1200ms from arming, but less than a restarted hold after remeasurement.
        advanceArchivePullClock(ArchiveFolderPull.HoldMillis / 2)
        val revealed = compose.onNodeWithTag("chatlist_archived_folder").assertIsDisplayed().getUnclippedBoundsInRoot()
        val shiftedRow = compose.onNodeWithTag("chatlist_row_1").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("chatlist_rows").performTouchInput {
            // Batch a final upward drag with termination: it cannot hide or scroll the completed pull.
            moveBy(Offset(0f, -initialHeight.toPx() * 2))
            if (finishWithCancel) cancel() else up()
        }
        advanceArchivePullClock(600)
        assertEquals(revealed, compose.onNodeWithTag("chatlist_archived_folder").assertIsDisplayed().getUnclippedBoundsInRoot())
        assertEquals(shiftedRow, compose.onNodeWithTag("chatlist_row_1").getUnclippedBoundsInRoot())
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("chatlist_archived_folder").performTouchInput { click() }
        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_99").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_1").assertDoesNotExist()
    }

    @Test fun archive_cards_match_chat_geometry_across_density_and_large_fonts() {
        val density = mutableStateOf(LayoutDensity.COMPACT)
        val fontScale = mutableStateOf(1f)
        val active = row()
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(active),
                    archivedRows = listOf(row().copy(bufferId = 2, archived = true)),
                    loading = false,
                ),
            )
        lateinit var colors: ColorScheme
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                MotdTheme(dynamicColor = false, layoutDensity = density.value) {
                    colors = MaterialTheme.colorScheme
                    ChatListContent(
                        state = state.value,
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
        }

        for (mode in listOf(LayoutDensity.COMPACT, LayoutDensity.COMFORTABLE)) {
            val spacing = spacingFor(mode)
            var restingHeight = 0.dp
            for (scale in listOf(1f, 2f)) {
                compose.runOnIdle {
                    density.value = mode
                    fontScale.value = scale
                    state.value = state.value.copy(rows = listOf(active))
                }
                val initialRow = compose.onNodeWithTag("chatlist_row_1").getUnclippedBoundsInRoot()
                val chatTitle = compose.onNodeWithText("alice", useUnmergedTree = true).getUnclippedBoundsInRoot()
                val chatLayouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText("alice", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                    it(chatLayouts)
                }
                val reveal =
                    compose
                        .onNodeWithTag("chatlist_archive_pull_target")
                        .fetchSemanticsNode()
                        .config[SemanticsActions.CustomActions]
                        .single { it.label == "Reveal archived chats" }
                compose.runOnIdle { assertTrue(reveal.action()) }

                val folder = compose.onNodeWithTag("chatlist_archived_folder").assertIsDisplayed()
                val card = folder.getUnclippedBoundsInRoot()
                val viewport = compose.onNodeWithTag("chatlist_archive_pull_target").getUnclippedBoundsInRoot()
                val shiftedRow = compose.onNodeWithTag("chatlist_row_1").getUnclippedBoundsInRoot()
                val iconHost = compose.onNodeWithTag("chatlist_archived_icon_host", true).getUnclippedBoundsInRoot()
                val glyph = compose.onNodeWithTag("chatlist_archived_glyph", true).getUnclippedBoundsInRoot()
                val titleHost = compose.onNodeWithTag("chatlist_archived_title_host", true).getUnclippedBoundsInRoot()
                val title = compose.onNodeWithText("Archived Chats (1)", useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertEquals(viewport.left + 8.dp, card.left)
                assertEquals(viewport.right - 8.dp, card.right)
                assertEquals(initialRow.left, card.left)
                assertEquals(initialRow.right, card.right)
                assertEquals(initialRow.top, card.top)
                assertEquals(card.bottom + 4.dp, shiftedRow.top)
                assertEquals(card.bottom - card.top + 4.dp, shiftedRow.top - initialRow.top)
                assertEquals(card.left + 12.dp, iconHost.left)
                assertEquals(spacing.chatListAvatar, iconHost.right - iconHost.left)
                assertEquals(spacing.chatListAvatar, iconHost.bottom - iconHost.top)
                assertEquals((card.top + card.bottom) / 2, (iconHost.top + iconHost.bottom) / 2)
                assertEquals(24.dp, glyph.right - glyph.left)
                assertEquals(24.dp, glyph.bottom - glyph.top)
                assertEquals((iconHost.left + iconHost.right) / 2, (glyph.left + glyph.right) / 2)
                assertEquals((iconHost.top + iconHost.bottom) / 2, (glyph.top + glyph.bottom) / 2)
                assertEquals(iconHost.right + 12.dp, title.left)
                assertEquals(chatTitle.left, title.left)
                assertEquals(card.right - 12.dp, titleHost.right)
                assertEquals((card.top + card.bottom) / 2, (title.top + title.bottom) / 2)
                assertEquals(
                    maxOf(spacing.chatListAvatar, titleHost.bottom - titleHost.top) + spacing.chatListVPad * 2,
                    card.bottom - card.top,
                )
                assertTrue(title.top >= card.top + spacing.chatListVPad)
                assertTrue(title.bottom <= card.bottom - spacing.chatListVPad)
                val archiveLayouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText("Archived Chats (1)", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                    it(archiveLayouts)
                }
                val archiveLayout = archiveLayouts.single()
                val chatTextStyle = chatLayouts.single().layoutInput.style
                assertEquals(chatTextStyle.fontSize, archiveLayout.layoutInput.style.fontSize)
                assertEquals(chatTextStyle.lineHeight, archiveLayout.layoutInput.style.lineHeight)
                val layoutDiagnostic =
                    "$mode fontScale=$scale size=${archiveLayout.size} paragraph=${archiveLayout.multiParagraph.width}x${archiveLayout.multiParagraph.height} " +
                        "constraints=${archiveLayout.layoutInput.constraints} title=$title card=$card viewport=$viewport"
                // String-text semantics rebuild at max width; compare painted lines, not paragraph width.
                assertTrue("The archive count overflows vertically: $layoutDiagnostic", !archiveLayout.didOverflowHeight)
                assertEquals(archiveLayout.layoutInput.text.length, archiveLayout.getLineEnd(archiveLayout.lineCount - 1))
                for (line in 0 until archiveLayout.lineCount) {
                    val left = archiveLayout.getLineLeft(line)
                    val right = archiveLayout.getLineRight(line)
                    assertTrue(
                        "Archive line $line ($left..$right) clips its measured width: $layoutDiagnostic",
                        left >= 0f && right <= archiveLayout.size.width,
                    )
                    assertTrue("Archive line $line is ellipsized: $layoutDiagnostic", !archiveLayout.isLineEllipsized(line))
                }
                if (scale == 1f) {
                    restingHeight = card.bottom - card.top
                } else {
                    assertTrue("The archive extent must grow with wrapped accessible text", card.bottom - card.top > restingHeight)
                }
                val pixels = folder.captureToImage().asAndroidBitmap()
                val sampleInset = with(compose.density) { 8.dp.roundToPx() }
                assertEquals(lerp(colors.surface, colors.primaryContainer, .20f).toArgb(), pixels.getPixel(pixels.width - sampleInset, pixels.height / 2))
                assertEquals("The card keeps rounded, unpainted corners", colors.surface.toArgb(), pixels.getPixel(0, 0))

                compose.runOnIdle { state.value = state.value.copy(rows = emptyList()) }
                val onlyCard = compose.onNodeWithTag("chatlist_archived_folder").assertIsDisplayed().getUnclippedBoundsInRoot()
                assertEquals("Archive-only uses the same complete card extent and gutters", card, onlyCard)
                assertEquals(title, compose.onNodeWithText("Archived Chats (1)", useUnmergedTree = true).getUnclippedBoundsInRoot())
            }
        }
    }

    @Test fun empty_archive_uses_archive_specific_copy_without_connection_prompt() {
        val state =
            mutableStateOf(
                ChatListState(
                    archivedRows = listOf(row().copy(archived = true)),
                    networks = listOf(network()),
                    loading = false,
                ),
            )
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = state.value,
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

        compose.onNodeWithText("Archived Chats (1)").performClick()
        compose.runOnIdle { state.value = state.value.copy(archivedRows = emptyList()) }

        compose.onNodeWithText("No archived chats yet").assertIsDisplayed()
        assertEquals(
            0,
            compose
                .onAllNodesWithText("Connect to a network to start chatting.")
                .fetchSemanticsNodes()
                .size,
        )
    }

    @Test fun end_to_start_swipe_archives_once_and_undo_reverses_once() {
        val archiveCalls = mutableListOf<Pair<List<Long>, Boolean>>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = ChatListState(rows = listOf(row()), loading = false),
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onSetArchived = { ids, archived -> archiveCalls += ids.toList() to archived },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Chat archived").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(listOf(1L) to true), archiveCalls) }

        compose.onNodeWithText("Undo").performClick()

        compose.runOnIdle {
            assertEquals(listOf(listOf(1L) to true, listOf(1L) to false), archiveCalls)
        }
    }

    @Test fun configured_mark_read_swipe_invokes_only_read() {
        val calls = mutableListOf<Pair<String, List<Long>>>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = ChatListState(rows = listOf(row().copy(unreadCount = 3)), chatListSwipeAction = ChatListSwipeAction.MARK_READ, loading = false),
                    onOpenBuffer = { calls += "open" to listOf(it) },
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { ids, _ -> calls += "pin" to ids.toList() },
                    onSetMuted = { ids, _ -> calls += "mute" to ids.toList() },
                    onSetArchived = { ids, _ -> calls += "archive" to ids.toList() },
                    onMarkSelectedRead = { ids -> calls += "read" to ids.toList() },
                    onDeleteBuffers = { rows -> calls += "delete" to rows.map(ChatListRow::bufferId) },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }

        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_delete_dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("read" to listOf(1L)), calls) }
    }

    @Test fun configured_delete_swipe_cancels_unchanged_then_confirms_once() {
        val calls = mutableListOf<Pair<String, List<Long>>>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = ChatListState(rows = listOf(row()), chatListSwipeAction = ChatListSwipeAction.DELETE, loading = false),
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { ids, _ -> calls += "pin" to ids.toList() },
                    onSetMuted = { ids, _ -> calls += "mute" to ids.toList() },
                    onSetArchived = { ids, _ -> calls += "archive" to ids.toList() },
                    onMarkSelectedRead = { ids -> calls += "read" to ids.toList() },
                    onDeleteBuffers = { rows -> calls += "delete" to rows.map(ChatListRow::bufferId) },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("chatlist_delete_dialog").assertIsDisplayed()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithTag("chatlist_delete_cancel").performClick()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }

        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("chatlist_delete_dialog").assertIsDisplayed()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithTag("chatlist_delete_confirm").performClick()

        compose.onNodeWithTag("chatlist_delete_dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("delete" to listOf(1L)), calls) }
    }

    @Test fun dismissing_swipe_undo_leaves_archive_unchanged() {
        val archiveCalls = mutableListOf<Pair<List<Long>, Boolean>>()
        val snackbarHostState = SnackbarHostState()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = ChatListState(rows = listOf(row()), loading = false),
                    snackbarHostState = snackbarHostState,
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onSetArchived = { ids, archived -> archiveCalls += ids.toList() to archived },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Chat archived").assertIsDisplayed()
        compose.runOnIdle { snackbarHostState.currentSnackbarData?.dismiss() }

        compose.runOnIdle { assertEquals(listOf(listOf(1L) to true), archiveCalls) }
    }

    @Test fun archiving_query_round_trip_moves_the_same_row_once_each_way() {
        val active = row()
        val remaining = row().copy(bufferId = 2, displayName = "bob")
        val archived = active.copy(archived = true)
        val state = mutableStateOf(ChatListState(rows = listOf(active, remaining), networks = listOf(network()), loading = false))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = state.value,
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onSetArchived = { ids, archivedFlag ->
                        if (ids == listOf(active.bufferId)) {
                            state.value =
                                if (archivedFlag) {
                                    state.value.copy(rows = listOf(remaining), archivedRows = listOf(archived))
                                } else {
                                    state.value.copy(rows = listOf(active, remaining), archivedRows = emptyList())
                                }
                        }
                    },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }

        compose.onNodeWithTag("chatlist_archived_folder").assertIsDisplayed()
        compose.onNodeWithText("Archived Chats (1)").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_archived_folder").performTouchInput { click() }
        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()

        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.onNodeWithText("No archived chats yet").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_selection_close").performClick()

        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
    }

    @Test fun unarchiving_query_ignores_disabled_swipe_preference_and_stays_in_archive_until_back() {
        val active = row().copy(bufferId = 2, displayName = "bob")
        val archived = row().copy(archived = true)
        val restored = archived.copy(archived = false)
        val state = mutableStateOf(ChatListState(rows = listOf(active), archivedRows = listOf(archived), networks = listOf(network()), chatListSwipeAction = ChatListSwipeAction.NONE, loading = false))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = state.value,
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onSetArchived = { ids, archivedFlag ->
                        if (ids == listOf(archived.bufferId)) {
                            state.value =
                                if (archivedFlag) {
                                    state.value.copy(rows = listOf(active), archivedRows = listOf(archived))
                                } else {
                                    state.value.copy(rows = listOf(restored, active), archivedRows = emptyList())
                                }
                        }
                    },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        val revealAction =
            compose
                .onNodeWithTag("chatlist_archive_pull_target")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == "Reveal archived chats" }
        compose.runOnIdle { assertEquals(true, revealAction.action()) }
        compose.onNodeWithText("Archived Chats (1)").performTouchInput { click() }
        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }

        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.onNodeWithText("No archived chats yet").assertIsDisplayed()
        compose.onNodeWithText("Chat unarchived").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_selection_close").performClick()

        compose.onNodeWithTag("chatlist_row_2").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("chatlist_row_1").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("No archived chats yet").fetchSemanticsNodes().size)
    }

    @Test fun disabled_swipe_directions_do_not_archive() {
        val archiveCalls = mutableListOf<Pair<List<Long>, Boolean>>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = ChatListState(rows = listOf(row()), loading = false),
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onSetArchived = { ids, archived -> archiveCalls += ids.toList() to archived },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeRight() }
        compose.onNodeWithTag("drawer_open_settings").assertIsNotDisplayed()
        compose.runOnIdle { assertEquals(emptyList<Pair<List<Long>, Boolean>>(), archiveCalls) }
        compose.onNodeWithTag("chatlist_row_1").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_selection_close").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }

        compose.runOnIdle { assertEquals(emptyList<Pair<List<Long>, Boolean>>(), archiveCalls) }
        assertEquals(0, compose.onAllNodesWithText("Chat archived").fetchSemanticsNodes().size)
    }

    @Test fun selection_menu_archive_does_not_offer_swipe_undo() {
        val archiveCalls = mutableListOf<Pair<List<Long>, Boolean>>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = ChatListState(rows = listOf(row()), loading = false),
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onSetArchived = { ids, archived -> archiveCalls += ids.toList() to archived },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("chatlist_row_1").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_selection_more").performClick()
        compose.onNodeWithTag("chatlist_selection_archive").performClick()

        compose.runOnIdle { assertEquals(listOf(listOf(1L) to true), archiveCalls) }
        assertEquals(0, compose.onAllNodesWithText("Chat archived").fetchSemanticsNodes().size)
    }

    @Test fun clear_activity_dot_only_recovers_selected_dotted_rows_without_hiding_them() {
        val recoveries = mutableListOf<List<Long>>()
        val markedRead = mutableListOf<Long>()
        val recoveringIds = mutableStateOf<Set<Long>>(emptySet())
        val rows =
            listOf(
                row().copy(advertisedUnread = true),
                row().copy(bufferId = 2, unreadCountIncomplete = true),
                row().copy(bufferId = 3, muted = true, advertisedUnread = true, unreadCountIncomplete = true),
                row().copy(bufferId = 4, unreadCount = 1, advertisedUnread = true, unreadCountIncomplete = true),
                row().copy(bufferId = 5),
            )
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = ChatListState(rows = rows, loading = false),
                    recoveringActivityIds = recoveringIds.value,
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                    onMarkSelectedRead = { markedRead += it },
                    onClearActivityDots = {
                        recoveries += it.toList()
                        recoveringIds.value = it.toSet()
                    },
                )
            }
        }

        compose.onNodeWithTag("chatlist_row_3").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_row_4").performClick()
        compose.onNodeWithTag("chatlist_row_5").performClick()
        compose.onNodeWithTag("chatlist_selection_more").performClick()
        assertEquals(0, compose.onAllNodesWithTag("chatlist_selection_clear_dot").fetchSemanticsNodes().size)
        compose.onNodeWithTag("chatlist_selection_pin").performClick()

        compose.onNodeWithTag("chatlist_row_1").performTouchInput { longClick() }
        (2..5).forEach { compose.onNodeWithTag("chatlist_row_$it").performClick() }
        compose.onNodeWithTag("chatlist_selection_more").performClick()
        compose.onNodeWithTag("chatlist_selection_clear_dot").assertIsDisplayed().performClick()

        compose.runOnIdle {
            assertEquals(listOf(listOf(1L, 2L)), recoveries)
            assertEquals(emptyList<Long>(), markedRead)
        }
        assertEquals(0, compose.onAllNodesWithTag("chatlist_selection_top_app_bar").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("chatlist_selection_clear_dot").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("chatlist_row_activity_recovery_spinner", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("chatlist_row_advertised_activity_dot", useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag("chatlist_row_1").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_selection_more").performClick()
        assertEquals(0, compose.onAllNodesWithTag("chatlist_selection_clear_dot").fetchSemanticsNodes().size)
        compose.onNodeWithTag("chatlist_selection_pin").performClick()
        // No Room update was supplied: once recovery ends each row's original cue returns.
        compose.runOnIdle { recoveringIds.value = emptySet() }
        assertEquals(0, compose.onAllNodesWithTag("chatlist_row_activity_recovery_spinner", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag("chatlist_row_advertised_activity_dot", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag("chatlist_row_history_incomplete", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun archive_announcement_uses_a_polite_live_region() {
        val announcement = mutableStateOf<String?>(null)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ArchiveAccessibilityAnnouncement(announcement.value)
            }
        }

        compose.runOnIdle { announcement.value = "Archived chats revealed" }
        compose
            .onNodeWithTag("chatlist_archive_announcement")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assertTextEquals("Archived chats revealed")

        compose.runOnIdle { announcement.value = "Archived chats hidden" }
        compose
            .onNodeWithTag("chatlist_archive_announcement")
            .assertTextEquals("Archived chats hidden")
    }

    private fun row() =
        ChatListRow(
            bufferId = 1,
            networkId = 1,
            networkName = "network",
            displayName = "alice",
            type = BufferType.QUERY,
            pinned = false,
            muted = false,
            lastMessageText = "hello",
            lastMessageSender = "alice",
            lastMessageTime = 1,
            unreadCount = 0,
            mentionCount = 0,
        )

    private fun invitation(
        id: Long,
        channel: String,
    ) = ChatListInvitation(
        messageId = id,
        bufferId = id + 100,
        networkId = 1,
        networkName = "network",
        inviter = "alice",
        channel = channel,
        text = "alice invited you to $channel",
        state = InviteState.PENDING,
        serverTime = id,
    )

    private fun network() =
        NetworkEntity(
            id = 1,
            name = "network",
            role = NetworkRole.DIRECT,
            host = "irc.example.test",
            port = 6697,
            nick = "me",
            username = "me",
            realname = "Me",
        )
}
