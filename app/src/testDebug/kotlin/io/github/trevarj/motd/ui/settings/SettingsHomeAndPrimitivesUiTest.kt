package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.MessageSpacing
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.ui.chat.ConversationLayoutSheet
import io.github.trevarj.motd.ui.chat.ConversationLayoutState
import io.github.trevarj.motd.ui.components.withStripedMessageWallpaper
import io.github.trevarj.motd.ui.nav.NetworkSettingsTarget
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.MotdLightScheme
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.contrastRatio
import io.github.trevarj.motd.ui.theme.ensureContrast
import io.github.trevarj.motd.ui.theme.readingInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsHomeAndPrimitivesUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun root_groups_search_and_exact_result_callback() {
        var state by mutableStateOf(SettingsHomeUiState(networks = listOf(network(4, "Libera"))))
        var opened: SettingsSearchDestination? = null
        var notificationsOpened = false
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                SettingsContent(
                    state = state,
                    onQueryChange = { state = state.copy(query = it) },
                    onBack = {},
                    onOpenAppearance = {},
                    onOpenChat = {},
                    onOpenDelivery = {},
                    onOpenNotifications = { notificationsOpened = true },
                    onOpenNetworks = {},
                    onOpenUploads = {},
                    onOpenBackupRestore = {},
                    onOpenLabs = {},
                    onOpenAbout = {},
                    onOpenSearchResult = { opened = it },
                )
            }
        }

        compose.onNodeWithText("Connections").assert(isHeading())
        compose.onNodeWithText("Experience").assert(isHeading())
        compose.onNodeWithText("Services").assert(isHeading())
        compose
            .onNodeWithTag("settings_category_notifications")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertTrue(notificationsOpened) }
        compose.onNodeWithTag("settings_search_action").performClick()
        compose.onNodeWithTag("settings_search_prompt").assertIsDisplayed()
        compose.onNodeWithTag("settings_category_networks").assertDoesNotExist()
        compose.onNodeWithTag("settings_search_field").performTextReplacement("obfuscation")
        compose.onNodeWithTag("settings_search_result_network_4_OBFUSCATION").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings_search_field").assertIsDisplayed()
        compose.onNodeWithTag("settings_search_result_network_4_OBFUSCATION").assertIsDisplayed()

        compose.runOnIdle {
            assertEquals(SettingsSearchDestination.Network(4L, NetworkSettingsTarget.OBFUSCATION), opened)
            state = state.copy(networks = listOf(network(4, "Renamed"), network(5, "Renamed")))
        }

        compose.onNodeWithTag("settings_search_result_network_4_OBFUSCATION").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(SettingsSearchDestination.Network(4L, NetworkSettingsTarget.OBFUSCATION), opened)
        }
        compose.onNodeWithTag("settings_search_result_network_5_OBFUSCATION").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(SettingsSearchDestination.Network(5L, NetworkSettingsTarget.OBFUSCATION), opened)
        }

        compose.onNodeWithTag("settings_search_field").performTextReplacement("join part")
        compose.onNodeWithTag("settings_search_result_page_CHAT_PRESENCE").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(SettingsSearchDestination.Page(SettingsSearchPage.CHAT, SettingsTarget.PRESENCE), opened)
        }

        compose.onNodeWithTag("settings_search_field").performTextReplacement("watch")
        compose
            .onNodeWithTag("settings_search_result_page_NOTIFICATIONS_NOTIFICATIONS")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            assertEquals(SettingsSearchDestination.Page(SettingsSearchPage.NOTIFICATIONS, SettingsTarget.NOTIFICATIONS), opened)
        }
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun wide_root_keeps_same_groups_and_callbacks() {
        var networksOpened = false
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                SettingsContent(
                    state = SettingsHomeUiState(),
                    onQueryChange = {},
                    onBack = {},
                    onOpenAppearance = {},
                    onOpenChat = {},
                    onOpenDelivery = {},
                    onOpenNotifications = {},
                    onOpenNetworks = { networksOpened = true },
                    onOpenUploads = {},
                    onOpenBackupRestore = {},
                    onOpenLabs = {},
                    onOpenAbout = {},
                    onOpenSearchResult = {},
                )
            }
        }

        compose
            .onNodeWithTag("settings_category_networks")
            .assertIsDisplayed()
            .assert(hasClickAction())
            .performClick()
        compose.runOnIdle { assertTrue(networksOpened) }
    }

    @Test
    fun nick_hue_swatches_expose_localized_label_selection_and_click() {
        var picked: Int? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                NickHuePickerDialog("alice", 210, { picked = it }, {})
            }
        }

        compose
            .onNodeWithTag("nick_hue_210")
            .assertIsSelected()
            .assertContentDescriptionEquals("Hue 210°")
            .assert(hasClickAction())
        compose.onNodeWithTag("nick_hue_240").performClick()
        compose.runOnIdle { assertEquals(240, picked) }
    }

    @Test
    fun error_notice_is_persistent_polite_live_region() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                PersistentStatusNotice(
                    text = "Could not import backup",
                    error = true,
                    modifier = Modifier.testTag("settings_error_notice"),
                )
            }
        }

        compose
            .onNodeWithTag("settings_error_notice")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Could not import backup"))
    }

    @Test
    fun primitives_expose_heading_switch_role_and_target_highlight() {
        var checked by mutableStateOf(true)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                SectionHeader("Conversation")
                SwitchRow("Images", "Show images", checked, { checked = it }, switchTag = "images_switch")
                SettingsTarget(SettingsTarget.PRESENCE.name, SettingsTarget.PRESENCE.name) { modifier ->
                    SettingsActionRow("Presence changes", modifier = modifier, onClick = {})
                }
            }
        }

        compose.onNodeWithText("Conversation").assert(isHeading())
        compose.onNodeWithTag("images_switch_row").assertIsOn().assert(hasClickAction())
        compose.onNodeWithTag("settings_target_highlight_PRESENCE").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun selectedRadioTargetScrollsIntoViewWithReadableAnimatedCueAndRestoresSelection() {
        var requested by mutableStateOf<String?>(null)
        var preset by mutableStateOf(ColorThemePreset.ONE_DARK)
        var trueBlack by mutableStateOf(false)
        var clicks = 0
        val target = SettingsTarget.PERSISTENT_DELIVERY.name
        val targetTag = "settings_target_highlight_$target"
        val label = "Persistent connection"
        val subtitle = "Keep this connection ready"
        val sample = "Aa 0O1lI"
        compose.setContent {
            MotdTheme(dynamicColor = false, themePreset = preset, trueBlack = trueBlack, useThemeTextColors = true) {
                SettingsScaffold(title = "Delivery", onBack = {}) {
                    Text("Top of settings")
                    Spacer(Modifier.height(900.dp))
                    SettingsGroup {
                        SettingsTarget(requested, target) { modifier ->
                            RadioRow(
                                label = label,
                                subtitle = subtitle,
                                selected = true,
                                enabled = true,
                                onClick = { clicks++ },
                                modifier = modifier.then(if (requested == target) Modifier else Modifier.testTag("selected_target_radio")),
                                supportingContent = { Text(sample) },
                            )
                        }
                    }
                }
            }
        }

        fun pixels(tag: String) = compose.onNodeWithTag(tag).captureToImage().toPixelMap()

        fun ink(text: String): Color {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts
                .single()
                .layoutInput.style.color
        }

        fun assertReadable(tag: String): Color {
            val image = pixels(tag)
            val fill = image[image.width - 2, image.height / 2]
            for (text in listOf(label, subtitle, sample)) {
                // Allow the native backing's 8-bit channel rounding at the contrast boundary.
                assertTrue("Selected $text must contrast with its actual animated backing", contrastRatio(ink(text), fill) >= 4.49)
            }
            return fill
        }

        for ((theme, black) in listOf(ColorThemePreset.ONE_DARK to false, ColorThemePreset.AYU_LIGHT to false, ColorThemePreset.AYU_DARK to true)) {
            compose.runOnIdle {
                requested = null
                preset = theme
                trueBlack = black
            }
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("selected_target_radio").performScrollTo().assertIsSelected()
            val normal = pixels("selected_target_radio")
            val selectedFill = assertReadable("selected_target_radio")
            val selectedInks = listOf(label, subtitle, sample).map(::ink)
            compose.onNodeWithText("Top of settings").performScrollTo()
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { requested = target }
            // Relocation also runs Android node/layout work: yield between frames instead of
            // assuming one virtual-time jump completes an off-screen bringIntoView request.
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithTag(targetTag).isDisplayed()
            }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            compose.onNodeWithTag(targetTag).assertIsDisplayed().assertIsSelected()
            compose.onNodeWithTag(targetTag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            val highlighted = pixels(targetTag)
            val cueFill = assertReadable(targetTag)
            assertNotEquals("The search cue must paint over the selected backing", selectedFill, cueFill)
            assertEquals("The search cue retains the group's clipped corner", normal[0, 0], highlighted[0, 0])
            val animatedFills = mutableSetOf(selectedFill, cueFill)
            repeat(20) {
                compose.mainClock.advanceTimeBy(80)
                compose.waitForIdle()
                animatedFills += assertReadable(targetTag)
            }
            assertTrue("The cue fades rather than switching between two flat fills", animatedFills.size > 2)
            compose.mainClock.advanceTimeBy(2_000)
            assertEquals("The selected backing returns when the cue expires", selectedFill, assertReadable(targetTag))
            assertEquals("The normal selected inks return after the fade", selectedInks, listOf(label, subtitle, sample).map(::ink))
            compose.onNodeWithTag(targetTag).assertIsSelected()
            assertEquals(
                "Targeting adds no second selection action",
                1,
                compose.onAllNodes(hasClickAction() and (hasTestTag(targetTag) or hasAnyAncestor(hasTestTag(targetTag))), useUnmergedTree = true).fetchSemanticsNodes().size,
            )
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag(targetTag).performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun narrow_switch_row_wraps_label_with_space_before_toggle() {
        val title = "Automatically download images sent to this conversation"
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Box(Modifier.width(220.dp)) {
                    SwitchRow(title, "Show images", checked = true, onCheckedChange = {}, switchTag = "narrow_switch")
                }
            }
        }

        val label = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val toggle = compose.onNodeWithTag("narrow_switch", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Label should wrap at a narrow width", label.height > toggle.height)
        assertTrue("Label should have a 16 dp gap before the switch", toggle.left - label.right >= with(compose.density) { 16.dp.toPx() } - 1f)
    }

    @Test
    @Config(qualifiers = "w320dp-h360dp", fontScale = 2f)
    fun narrowLargeFontRadioChoicesWrapAndRemainReachableWithOneSelectionAction() {
        var selected by mutableStateOf(0)
        val clicks = mutableListOf<Int>()
        val label = "Use the inherited conversation layout for all messages"
        val summary = "The global setting applies until this conversation has its own saved choice."
        compose.setContent {
            MotdTheme(dynamicColor = false, uiFontScalePercent = 140) {
                SingleChoiceSheet(
                    title = "Conversation layout",
                    selected = selected,
                    options =
                        listOf(
                            ChoiceOption(0, label, summary, "long_radio"),
                            ChoiceOption(1, "Compact", tag = "short_radio"),
                            ChoiceOption(2, "Another conversation layout with a complete readable description", summary, "last_radio"),
                        ),
                    onSelect = {
                        clicks += it
                        selected = it
                    },
                    onDismiss = {},
                    tag = "radio_sheet",
                    dismissOnSelect = false,
                    preview = { Text("A local conversation example") },
                    footer = { Text("The policy disclosure stays after the choices.") },
                )
            }
        }
        val options = compose.onNodeWithTag("radio_sheet_options")
        for ((tag, value) in listOf("long_radio" to 0, "short_radio" to 1, "last_radio" to 2)) {
            options.performScrollToNode(hasTestTag(tag))
            val row = compose.onNodeWithTag(tag).assertIsDisplayed()
            row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            val bounds = row.getUnclippedBoundsInRoot()
            assertTrue("Each choice retains a minimum touch host", bounds.bottom - bounds.top >= 48.dp)
            assertEquals(
                "The row, not its indicator, owns selection",
                1,
                compose.onAllNodes(hasClickAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true).fetchSemanticsNodes().size,
            )
            row.performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(value, selected) }
        }
        options.performScrollToNode(hasTestTag("long_radio"))
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNode(hasText(label) and hasAnyAncestor(hasTestTag("long_radio")), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("The complete label wraps instead of shrinking", layouts.single().lineCount > 1)
        assertTrue("The full label remains laid out", !layouts.single().hasVisualOverflow)
        options.performScrollToNode(hasText("The policy disclosure stays after the choices."))
        compose.onNodeWithText("The policy disclosure stays after the choices.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(0, 1, 2), clicks) }
    }

    @Test
    @Config(qualifiers = "w320dp-h1000dp", fontScale = 2f)
    fun valuePillsWrapWithoutBecomingChoicesOrNarrowingTheirTitles() {
        val navigationValue = "Inherited from the saved configuration for this conversation"
        val metadataValue = "irc.really-long-network.example:6697 · encrypted connection"
        var preset by mutableStateOf(ColorThemePreset.AYU_LIGHT)
        var trueBlack by mutableStateOf(false)
        var selected by mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            MotdTheme(dynamicColor = false, themePreset = preset, trueBlack = trueBlack) {
                SettingsScaffold(title = "Sample", onBack = {}) {
                    SettingsGroup(title = "Conversation") {
                        SettingsNavigationRow(
                            title = "Conversation layout",
                            summary = "Choose a layout",
                            value = navigationValue,
                            modifier = Modifier.testTag("value_navigation"),
                            onClick = { clicks++ },
                        )
                        SettingsValueRow(
                            title = "Connection address",
                            value = metadataValue,
                            modifier = Modifier.testTag("value_metadata"),
                        )
                        RadioRow("Saved choice", selected, true, { selected = !selected }, Modifier.testTag("value_choice"))
                        SwitchRow("Images", "Show images", true, {}, modifier = Modifier.testTag("value_switch"))
                    }
                }
            }
        }

        fun layout(text: String): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }

        fun fillBeforeText(
            tag: String,
            text: String,
        ): Color {
            val row = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val bounds = row.fetchSemanticsNode().boundsInRoot
            val textBounds = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val image = row.captureToImage().toPixelMap()
            val x = (textBounds.left - bounds.left - with(compose.density) { 4.dp.toPx() }).roundToInt()
            val y = (textBounds.center.y - bounds.top).roundToInt()
            return image[x, y]
        }

        fun rowEdge(tag: String): Color {
            val image =
                compose
                    .onNodeWithTag(tag)
                    .performScrollTo()
                    .captureToImage()
                    .toPixelMap()
            return image[1, image.height / 2]
        }

        for ((theme, black) in listOf(ColorThemePreset.AYU_LIGHT to false, ColorThemePreset.AYU_DARK to false, ColorThemePreset.AYU_DARK to true)) {
            compose.runOnIdle {
                preset = theme
                trueBlack = black
                selected = false
            }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            val proseFill = fillBeforeText("value_navigation", "Choose a layout")
            val pillFill = fillBeforeText("value_navigation", navigationValue)
            assertNotEquals("The current value has its own neutral backing", proseFill, pillFill)
            for ((tag, value, title) in listOf(Triple("value_navigation", navigationValue, "Conversation layout"), Triple("value_metadata", metadataValue, "Connection address"))) {
                val row = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                val valueLayout = layout(value)
                assertEquals("The values use the native large font", 2f, valueLayout.layoutInput.density.fontScale, 0f)
                assertTrue("Long values wrap at the native large font", valueLayout.lineCount > 1)
                assertTrue("The complete value remains readable", !valueLayout.hasVisualOverflow)
                assertEquals(value.length, valueLayout.getLineEnd(valueLayout.lineCount - 1))
                assertNotEquals("Current values are not context notes", FontStyle.Italic, valueLayout.layoutInput.style.fontStyle)
                val rowBounds = row.fetchSemanticsNode().boundsInRoot
                val titleBounds = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("A long value must not squeeze the title into a trailing-column layout", titleBounds.width > rowBounds.width / 2)
                assertTrue("The title also remains complete", !layout(title).hasVisualOverflow)
                assertNotEquals("Read-only values remain distinct from their panel", rowEdge(tag), fillBeforeText(tag, value))
            }
            compose.onNodeWithTag("value_navigation").performScrollTo()
            assertEquals(
                "The value pill adds no second action to navigation",
                1,
                compose.onAllNodes(hasClickAction() and (hasTestTag("value_navigation") or hasAnyAncestor(hasTestTag("value_navigation"))), useUnmergedTree = true).fetchSemanticsNodes().size,
            )
            assertEquals(
                "Read-only metadata stays noninteractive",
                0,
                compose.onAllNodes(hasClickAction() and (hasTestTag("value_metadata") or hasAnyAncestor(hasTestTag("value_metadata"))), useUnmergedTree = true).fetchSemanticsNodes().size,
            )
            val ordinaryFill = rowEdge("value_choice")
            compose.runOnIdle { selected = true }
            compose.onNodeWithTag("value_choice").assertIsSelected()
            val selectionFill = rowEdge("value_choice")
            assertNotEquals("Selection is stronger than an ordinary row", ordinaryFill, selectionFill)
            assertNotEquals("A current-value pill does not impersonate selection", pillFill, selectionFill)
            assertNotEquals("Read-only metadata does not impersonate selection", fillBeforeText("value_metadata", metadataValue), selectionFill)
            assertEquals("An enabled switch does not paint its row as a selected choice", ordinaryFill, rowEdge("value_switch"))
        }
        compose.onNodeWithTag("value_navigation").performScrollTo().performClick()
        compose
            .onNodeWithTag("value_choice")
            .performScrollTo()
            .performClick()
            .assertIsNotSelected()
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertTrue(!selected)
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land", fontScale = 2f)
    fun layoutSheetUsesSavedInheritanceAndStaysOpenAcrossSelectionsAndRejectedWrites() {
        var saved by mutableStateOf(ConversationLayoutState(global = LayoutDensity.COMPACT))
        var rejectWrite = false
        val writes = mutableListOf<LayoutDensity?>()
        var dismissals = 0
        compose.setContent {
            MotdTheme(dynamicColor = false, uiFontScalePercent = 140) {
                ConversationLayoutSheet(
                    state = saved,
                    onSelect = {
                        writes += it
                        if (!rejectWrite) saved = saved.copy(override = it)
                    },
                    onOpacity = { if (!rejectWrite) saved = saved.copy(messageBackgroundOpacityOverride = it) },
                    globalOpacity = 73,
                    onBlur = { if (!rejectWrite) saved = saved.copy(messageBackgroundBlurOverride = it) },
                    globalBlur = 41,
                    onDismiss = { dismissals++ },
                    wallpaper = WallpaperSelection(),
                    conversationFontScalePercent = 140,
                    messageSpacing = MessageSpacing.DEFAULT,
                    bubbleCorners = BubbleCornerStyle.ROUNDED,
                )
            }
        }
        val options = compose.onNodeWithTag("chat_layout_sheet_options")

        fun choice(tag: String) = options.performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag) }

        fun sample(density: LayoutDensity) {
            options.performScrollToNode(hasTestTag("chat_layout_preview_configuration"))
            compose.onNodeWithTag("chat_layout_preview_${density.name.lowercase()}_default_rounded").assertExists()
            assertEquals(1, compose.onAllNodesWithTag("chat_layout_preview_configuration").fetchSemanticsNodes().size)
        }
        sample(LayoutDensity.COMPACT)
        choice("chat_layout_comfortable").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(LayoutDensity.COMFORTABLE), writes) }
        sample(LayoutDensity.COMFORTABLE)
        compose.onNodeWithTag("chat_layout_sheet").assertIsDisplayed()
        choice("chat_layout_global").performClick().assertIsSelected()
        sample(LayoutDensity.COMPACT)

        compose.runOnIdle { saved = saved.copy(bufferDefault = LayoutDensity.TWO_LINE) }
        sample(LayoutDensity.TWO_LINE)
        compose.runOnIdle { saved = saved.copy(global = LayoutDensity.COMFORTABLE) }
        sample(LayoutDensity.TWO_LINE)
        choice("chat_layout_compact").performClick().assertIsSelected()
        sample(LayoutDensity.COMPACT)
        choice("chat_layout_global").performClick().assertIsSelected()
        sample(LayoutDensity.TWO_LINE)

        choice("chat_message_background_opacity").performSemanticsAction(SemanticsActions.SetProgress) { it(37f) }
        choice("chat_layout_global_opacity_row").assertIsOff()
        assertEquals(37, saved.messageBackgroundOpacityOverride)
        choice("chat_layout_comfortable").performClick().assertIsSelected()
        choice("chat_layout_global").performClick().assertIsSelected()
        assertEquals(37, saved.messageBackgroundOpacityOverride)
        choice("chat_layout_global_opacity_row").performClick().assertIsOn()
        assertEquals(null, saved.messageBackgroundOpacityOverride)
        compose.runOnIdle { rejectWrite = true }
        choice("chat_layout_comfortable").performClick().assertIsNotSelected()
        choice("chat_layout_global").assertIsSelected()
        sample(LayoutDensity.TWO_LINE)
        choice("chat_message_background_opacity").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        choice("chat_layout_global_opacity_row").assertIsOn()
        assertEquals(null, saved.messageBackgroundOpacityOverride)
        compose.runOnIdle {
            assertEquals(listOf(LayoutDensity.COMFORTABLE, null, LayoutDensity.COMPACT, null, LayoutDensity.COMFORTABLE, null, LayoutDensity.COMFORTABLE), writes)
            assertEquals(0, dismissals)
            assertEquals(null, saved.override)
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun chatOpacityPreviewUsesAcceptedLocalOrGlobalValueAndIndependentResets() {
        var saved by mutableStateOf(ConversationLayoutState(global = LayoutDensity.COMFORTABLE))
        var globalOpacity by mutableStateOf(100)
        var rejectWrite = false
        compose.setContent {
            MotdTheme(dynamicColor = false, themePreset = ColorThemePreset.AYU_LIGHT, avatarStyle = io.github.trevarj.motd.data.prefs.AvatarStyle.NONE) {
                ConversationLayoutSheet(
                    state = saved,
                    onSelect = { saved = saved.copy(override = it) },
                    onOpacity = { if (!rejectWrite) saved = saved.copy(messageBackgroundOpacityOverride = it) },
                    globalOpacity = globalOpacity,
                    onBlur = { if (!rejectWrite) saved = saved.copy(messageBackgroundBlurOverride = it) },
                    globalBlur = 0,
                    onDismiss = {},
                    wallpaper = WallpaperSelection(io.github.trevarj.motd.data.prefs.ChatWallpaperPreset.NONE),
                    conversationFontScalePercent = 100,
                    messageSpacing = MessageSpacing.DEFAULT,
                    bubbleCorners = BubbleCornerStyle.ROUNDED,
                )
            }
        }
        val options = compose.onNodeWithTag("chat_layout_sheet_options")

        fun control(tag: String) = options.performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag) }

        fun pixels(): android.graphics.Bitmap {
            // Reset the whole lazy viewport, not merely the potentially clipped preview descendant.
            options.performScrollToIndex(0)
            val incoming = compose.onNodeWithTag("chat_layout_preview_incoming").assertIsDisplayed()
            val viewport = options.fetchSemanticsNode().boundsInRoot
            val bounds = incoming.fetchSemanticsNode().boundsInRoot
            val unclipped = incoming.getUnclippedBoundsInRoot()
            assertTrue("preview must be fully in the viewport", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            assertEquals("unclipped width", with(compose.density) { (unclipped.right - unclipped.left).toPx() }, bounds.width, 1f)
            assertEquals("unclipped height", with(compose.density) { (unclipped.bottom - unclipped.top).toPx() }, bounds.height, 1f)
            return incoming.captureToImage().asAndroidBitmap()
        }
        val baseline = pixels()
        control("chat_message_background_opacity").performSemanticsAction(SemanticsActions.SetProgress) { it(37f) }
        control("chat_layout_global_opacity_row").assertIsOff()
        val local = pixels()
        assertTrue(!baseline.sameAs(local))
        compose.runOnIdle { globalOpacity = 0 }
        assertTrue("local override ignores global edits", local.sameAs(pixels()))
        control("chat_layout_comfortable").performClick()
        control("chat_layout_global").performClick()
        assertEquals(37, saved.messageBackgroundOpacityOverride)
        assertTrue("density reset preserves opacity", local.sameAs(pixels()))
        compose.runOnIdle { rejectWrite = true }
        control("chat_message_background_opacity").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        assertTrue("rejected writes preserve accepted preview", local.sameAs(pixels()))
        compose.runOnIdle { rejectWrite = false }
        control("chat_layout_global_opacity_row").performClick().assertIsOn()
        assertEquals(null, saved.messageBackgroundOpacityOverride)
        assertTrue("inherit uses the current global value", !local.sameAs(pixels()))
        compose.runOnIdle { globalOpacity = 100 }
        assertTrue("inherited global reset restores exact baseline", baseline.sameAs(pixels()))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp")
    fun chatBlurPreviewUsesAcceptedRoomValuesAndIndependentInheritance() =
        withStripedMessageWallpaper { wallpaper ->
            var saved by mutableStateOf(ConversationLayoutState(global = LayoutDensity.COMFORTABLE, messageBackgroundOpacityOverride = 0))
            var globalBlur by mutableStateOf(0)
            var rejectWrite = false
            val writes = mutableListOf<Int?>()
            compose.setContent {
                MotdTheme(dynamicColor = false, themePreset = ColorThemePreset.AYU_LIGHT, avatarStyle = io.github.trevarj.motd.data.prefs.AvatarStyle.NONE) {
                    ConversationLayoutSheet(
                        state = saved,
                        onSelect = { saved = saved.copy(override = it) },
                        onOpacity = { saved = saved.copy(messageBackgroundOpacityOverride = it) },
                        globalOpacity = 100,
                        onBlur = {
                            writes += it
                            if (!rejectWrite) saved = saved.copy(messageBackgroundBlurOverride = it)
                        },
                        globalBlur = globalBlur,
                        onDismiss = {},
                        wallpaper = wallpaper,
                        conversationFontScalePercent = 100,
                        messageSpacing = MessageSpacing.DEFAULT,
                        bubbleCorners = BubbleCornerStyle.ROUNDED,
                    )
                }
            }
            val options = compose.onNodeWithTag("chat_layout_sheet_options")

            fun control(tag: String) = options.performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag) }

            fun pixels(): android.graphics.Bitmap {
                options.performScrollToIndex(0)
                val incoming = compose.onNodeWithTag("chat_layout_preview_incoming").assertIsDisplayed()
                val viewport = options.fetchSemanticsNode().boundsInRoot
                val bounds = incoming.fetchSemanticsNode().boundsInRoot
                val unclipped = incoming.getUnclippedBoundsInRoot()
                assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
                assertEquals(with(compose.density) { (unclipped.bottom - unclipped.top).toPx() }, bounds.height, 1f)
                return incoming.captureToImage().asAndroidBitmap()
            }
            compose.waitUntil(10_000) {
                val image = pixels()
                var black = 0
                var white = 0
                for (x in 0 until image.width) {
                    for (y in 0 until image.height) {
                        if (image.getPixel(x, y) == android.graphics.Color.BLACK) black++
                        if (image.getPixel(x, y) == android.graphics.Color.WHITE) white++
                    }
                }
                black > image.width * image.height / 8 && white > image.width * image.height / 8
            }
            val baseline = pixels()
            control("chat_layout_global_blur_row").assertIsOn().performClick().assertIsOff()
            assertEquals(0, saved.messageBackgroundBlurOverride)
            control("chat_message_background_blur").performSemanticsAction(SemanticsActions.SetProgress) { it(63f) }
            val local = pixels()
            assertTrue("accepted local blur changes the real wallpaper preview at zero tint", !baseline.sameAs(local))
            compose.runOnIdle { globalBlur = 100 }
            assertTrue("local blur ignores global edits", local.sameAs(pixels()))
            control("chat_layout_compact").performClick()
            control("chat_layout_global").performClick()
            assertEquals(63, saved.messageBackgroundBlurOverride)
            assertTrue("density reset preserves blur", local.sameAs(pixels()))
            control("chat_message_background_opacity").performSemanticsAction(SemanticsActions.SetProgress) { it(37f) }
            assertEquals(63, saved.messageBackgroundBlurOverride)
            control("chat_message_background_opacity").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
            assertTrue("opacity reset preserves blur", local.sameAs(pixels()))
            control("chat_message_background_blur").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
            assertEquals(0, saved.messageBackgroundBlurOverride)
            assertTrue("explicit Off overrides a nonzero global value", baseline.sameAs(pixels()))
            compose.runOnIdle { rejectWrite = true }
            control("chat_message_background_blur").performSemanticsAction(SemanticsActions.SetProgress) { it(63f) }
            assertTrue("rejected writes cannot change the accepted preview", baseline.sameAs(pixels()))
            compose.runOnIdle { rejectWrite = false }
            control("chat_layout_global_blur_row").performClick().assertIsOn()
            assertEquals(null, saved.messageBackgroundBlurOverride)
            assertTrue("inherit uses current global blur", !baseline.sameAs(pixels()))
            compose.runOnIdle { globalBlur = 0 }
            assertTrue("global Off restores exact original pixels", baseline.sameAs(pixels()))
            assertEquals(0, saved.messageBackgroundOpacityOverride)
            assertEquals(listOf(0, 63, 0, 63, null), writes)
            compose.onNodeWithTag("chat_layout_sheet").assertIsDisplayed()
        }

    @Test
    fun blurControlKeepsNativeContinuousSemanticsSlimTrackFullTouchHeightAndOffLabel() {
        var value by mutableStateOf(37)
        var background = Color.Unspecified
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                background = MaterialTheme.colorScheme.surface
                Surface { MessageBackgroundBlurSlider(value = value, onValue = { value = it }) }
            }
        }
        val slider = compose.onNodeWithTag("settings_message_background_blur")
        val bounds = slider.getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
        slider.assertContentDescriptionEquals("Message background blur").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                androidx.compose.ui.semantics
                    .ProgressBarRangeInfo(37f, 0f..100f, 0),
            ),
        )
        val image = slider.captureToImage().toPixelMap()
        val trackRows = (0 until image.height).count { image[image.width * 3 / 4, it] != background }
        assertTrue("4dp track retains native 48dp interaction target", trackRows in 1..with(compose.density) { 4.dp.roundToPx() + 1 })
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        assertEquals(0, value)
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
        compose.onNodeWithText("Blur the wallpaper behind messages. Lower opacity reveals more blur. Android 12+ only; older devices keep the unblurred tint.").assertExists()
    }

    @Test
    fun opacityControlKeepsNativeContinuousSemanticsAndSlimTrackWithFullTouchHeight() {
        var value by mutableStateOf(37)
        var background = Color.Unspecified
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                background = MaterialTheme.colorScheme.surface
                Surface {
                    MessageBackgroundOpacitySlider(value = value, onValue = { value = it })
                }
            }
        }
        val slider = compose.onNodeWithTag("settings_message_background_opacity")
        val bounds = slider.getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
        slider.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                androidx.compose.ui.semantics
                    .ProgressBarRangeInfo(37f, 0f..100f, 0),
            ),
        )
        val image = slider.captureToImage().toPixelMap()
        val trackRows = (0 until image.height).count { image[image.width * 3 / 4, it] != background }
        assertTrue("4dp track, no ticks or stop decoration: $trackRows rows", trackRows in 1..with(compose.density) { 4.dp.roundToPx() + 1 })
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        assertEquals(0, value)
        slider.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                org.robolectric.RuntimeEnvironment
                    .getApplication()
                    .getString(io.github.trevarj.motd.R.string.settings_message_background_opacity_off),
            ),
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun ordinaryTextAndSettingsMetadataUseSelectedInkWithoutOverridingSemanticControls() {
        var preset by mutableStateOf(ColorThemePreset.AYU_LIGHT)
        var trueBlack by mutableStateOf(false)
        var useThemeTextColors by mutableStateOf(false)
        var scheme: ColorScheme = MotdLightScheme
        var disabledButtonInk = Color.Unspecified
        compose.setContent {
            MotdTheme(dynamicColor = true, themePreset = preset, trueBlack = trueBlack, useThemeTextColors = useThemeTextColors) {
                scheme = MaterialTheme.colorScheme
                disabledButtonInk = ButtonDefaults.buttonColors().disabledContentColor
                Surface {
                    Column {
                        Text("Ordinary body")
                        Text("Background text", color = scheme.onBackground)
                        SettingsGroup(title = "Settings heading") {
                            SettingsValueRow("Ordinary title", "Current value", summary = "Supporting description")
                            SettingsActionRow("Ordinary action", onClick = {})
                            SettingsActionRow("Destructive action", destructive = true, onClick = {})
                        }
                        RadioRow("Disabled choice", selected = false, enabled = false, onClick = {})
                        SwitchRow("Disabled switch", "Why unavailable", checked = false, onCheckedChange = {}, enabled = false)
                        TextField(
                            value = "Input text",
                            onValueChange = {},
                            supportingText = { Text("Input metadata") },
                        )
                        TextField(
                            value = "",
                            onValueChange = {},
                            placeholder = { Text("Input placeholder") },
                        )
                        Button(onClick = {}) { Text("Filled action") }
                        Button(onClick = {}, enabled = false) { Text("Disabled action") }
                        Surface(color = scheme.inverseSurface, contentColor = scheme.inverseOnSurface) {
                            Text("Inverse content")
                        }
                    }
                }
            }
        }

        fun inkOf(text: String): Color {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts
                .single()
                .layoutInput.style.color
        }
        for ((theme, black) in listOf(ColorThemePreset.AYU_LIGHT to false, ColorThemePreset.AYU_DARK to false, ColorThemePreset.AYU_DARK to true, ColorThemePreset.SYSTEM to false)) {
            var staticBody = Color.Unspecified
            for (themed in listOf(false, true)) {
                compose.runOnIdle {
                    preset = theme
                    trueBlack = black
                    useThemeTextColors = themed
                }
                compose.waitForIdle()
                val surfaces =
                    listOf(
                        scheme.background,
                        scheme.surface,
                        scheme.surfaceContainerLowest,
                        scheme.surfaceContainerLow,
                        scheme.surfaceContainer,
                        scheme.surfaceContainerHigh,
                        scheme.surfaceContainerHighest,
                        scheme.surfaceVariant,
                    )
                val neutral = ensureContrast(readingInk(scheme, useThemeTextColors = false), surfaces)
                if (themed) {
                    assertNotEquals("$theme trueBlack=$black ordinary text changes", staticBody, inkOf("Ordinary body"))
                } else {
                    staticBody = inkOf("Ordinary body")
                    assertEquals("$theme static ink", neutral, staticBody)
                    assertEquals(neutral, scheme.onBackground)
                    assertEquals(neutral, scheme.onSurfaceVariant)
                }
                listOf(
                    "Ordinary body" to scheme.onSurface,
                    "Background text" to scheme.onBackground,
                    "Ordinary title" to scheme.onSurface,
                    "Supporting description" to scheme.onSurfaceVariant,
                    "Ordinary action" to scheme.onSurface,
                    "Input text" to scheme.onSurface,
                    "Input metadata" to scheme.onSurfaceVariant,
                    "Input placeholder" to scheme.onSurfaceVariant,
                ).forEach { (text, expected) ->
                    assertEquals("$theme trueBlack=$black themed=$themed $text", expected, inkOf(text))
                }
                assertEquals("$theme destructive", scheme.error, inkOf("Destructive action"))
                assertNotEquals("$theme error stays colored", neutral, inkOf("Destructive action"))
                assertEquals("$theme filled action", scheme.onPrimary, inkOf("Filled action"))
                assertTrue("$theme filled contrast", contrastRatio(inkOf("Filled action"), scheme.primary) >= 4.5)
                assertEquals("$theme inverse content", scheme.inverseOnSurface, inkOf("Inverse content"))
                assertTrue("$theme inverse contrast", contrastRatio(inkOf("Inverse content"), scheme.inverseSurface) >= 4.5)
                assertEquals("$theme framework disabled action", disabledButtonInk, inkOf("Disabled action"))
                assertEquals("$theme disabled choice", scheme.onSurface.copy(alpha = 0.38f), inkOf("Disabled choice"))
                assertEquals("$theme disabled switch", scheme.onSurface.copy(alpha = 0.38f), inkOf("Disabled switch"))
            }
        }
    }

    private fun network(
        id: Long,
        name: String,
    ) = NetworkEntity(
        id = id,
        name = name,
        role = NetworkRole.DIRECT,
        host = "irc.libera.chat",
        port = 6697,
        nick = "me",
        username = "me",
        realname = "Me",
    )
}
