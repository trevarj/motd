package io.github.trevarj.motd

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.prefs.*
import io.github.trevarj.motd.ui.nav.PaletteEditorRoute
import io.github.trevarj.motd.ui.nav.PalettePreviewRoute
import io.github.trevarj.motd.ui.settings.PaletteEditorScreen
import io.github.trevarj.motd.ui.settings.PaletteEditorViewModel
import io.github.trevarj.motd.ui.settings.PaletteHubScreen
import io.github.trevarj.motd.ui.settings.PaletteHubViewModel
import io.github.trevarj.motd.ui.settings.PalettePreviewScreen
import io.github.trevarj.motd.ui.settings.PalettePreviewViewModel
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.rawFixedThemeScheme
import io.github.trevarj.motd.ui.theme.themeIdentityPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class PaletteEditorUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = AppearancePrefsImpl(context)
    private val settings = DataStoreSettingsRepository(context)
    private val viewModels = androidx.lifecycle.ViewModelStore()
    private var nextViewModel = 0
    private lateinit var originalAppearance: Preferences
    private lateinit var originalSettings: Preferences
    private var editor by mutableStateOf<PaletteEditorViewModel?>(null)
    private var preview by mutableStateOf<PalettePreviewViewModel?>(null)
    private var hub by mutableStateOf<PaletteHubViewModel?>(null)
    private var rootPrimary = 0
    private var rootBackground = 0

    @Before fun isolatePreferences() =
        runBlocking {
            originalAppearance = context.appearanceDataStore.data.first()
            originalSettings = context.settingsDataStore.data.first()
            context.appearanceDataStore.edit { it.clear() }
            context.settingsDataStore.edit { it.clear() }
            prefs.selectPalette(PaletteSelection(dark = PaletteChoice(ColorThemePreset.DARK), mode = PaletteMode.DARK))
            settings.setNickColorsEnabled(true)
            settings.setNickColorPalette(NickColorPalette.THEME)
            prefs.setUseThemeTextColors(true)
        }

    @After fun restorePreferences(): Unit =
        runBlocking {
            viewModels.clear()
            context.appearanceDataStore.edit { target ->
                target.clear()
                originalAppearance.asMap().forEach { (key, value) ->
                    @Suppress("UNCHECKED_CAST")
                    target[key as Preferences.Key<Any>] = value
                }
            }
            context.settingsDataStore.edit { target ->
                target.clear()
                originalSettings.asMap().forEach { (key, value) ->
                    @Suppress("UNCHECKED_CAST")
                    target[key as Preferences.Key<Any>] = value
                }
            }
        }

    @Test fun rawDraftEditsStayContained_untilAtomicApply_andReloadRetainsState() {
        val base = rawDraft()
        val selectionBefore = current().paletteSelection
        val handle = editorHandle(base, assignmentTarget = PaletteMode.DARK)
        editor = editorModel(handle)
        mount()
        compose.onNodeWithTag("palette_editor_preview_dark").performClick()
        compose.onNodeWithTag("palette_editor_edit_dark").performScrollTo().performClick()
        val rootBefore = rootPrimary
        val before = sample()
        editHex("palette_editor_primary", "#FF5500")
        val accented = sample()
        assertFalse(before.sameAs(accented))
        for (index in base.dark!!.nicknameColors.indices) {
            editHex("palette_editor_nick_$index", "#00DDBB")
        }
        assertFalse(accented.sameAs(sample()))
        assertEquals(rootBefore, rootPrimary)
        assertTrue(current().customPalettes.isEmpty())
        assertEquals(
            "#FF5500",
            editor!!
                .state.value.draft!!
                .dark!!
                .primary,
        )
        assertEquals(
            "#00DDBB",
            editor!!
                .state.value.draft!!
                .dark!!
                .nicknameColors
                .first(),
        )

        val restored = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        compose.runOnIdle { editor = editorModel(restored) }
        awaitLoaded()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(
            "#FF5500",
            editor!!
                .state.value.draft!!
                .dark!!
                .primary,
        )
        assertTrue(editor!!.state.value.dirty)
        compose.onNodeWithTag("palette_editor_save_apply").performClick()
        awaitUi { editor == null }
        compose.onNodeWithTag("retained_composer").assertTextEquals("unsent channel draft")
        val saved = current()
        assertEquals(base.id, saved.paletteSelection.dark.installedId)
        assertEquals(selectionBefore.light, saved.paletteSelection.light)
        assertEquals(selectionBefore.mode, saved.paletteSelection.mode)
        val savedDocument = saved.customPalette(saved.paletteSelection.dark)!!
        assertEquals("#FF5500", savedDocument.dark!!.primary)
        assertNotEquals(rootBefore, rootPrimary)
        val reloaded = runBlocking { AppearancePrefsImpl(context).config.first() }
        assertEquals(saved.paletteSelection, reloaded.paletteSelection)
        assertEquals(savedDocument, reloaded.customPalette(reloaded.paletteSelection.dark))
        assertEquals(base.license, savedDocument.license)
        assertEquals(base.notices, savedDocument.notices)
        assertEquals(base.sourceUrl, savedDocument.sourceUrl)
        compose.runOnIdle { editor = editorModel(editorHandle(savedDocument, base.id)) }
        awaitLoaded()
        compose.onNodeWithTag("palette_editor_back").performClick()
        awaitUi { editor == null }
        compose.onNodeWithTag("retained_composer").assertTextEquals("unsent channel draft")
    }

    @Test fun dirtyBackAndModeRemoval_requireExplicitConfirmation_andLastModeSurvives() {
        editor = editorModel(editorHandle(rawDraft().copy(light = null)))
        mount()
        compose.onNodeWithTag("palette_editor_name").performScrollTo().performTextReplacement("Uncommitted")
        compose.onNodeWithTag("palette_editor_back").performClick()
        compose.onNodeWithTag("palette_editor_keep_editing").performClick()
        compose.onNodeWithTag("palette_editor_name").assertTextContains("Uncommitted")
        compose.onNodeWithTag("palette_editor_add_light").performScrollTo().performClick()
        compose.waitForIdle()
        assertNotNull(
            editor!!
                .state.value.draft!!
                .light,
        )
        compose.onNodeWithTag("palette_editor_remove_light").performScrollTo().performClick()
        assertNotNull(
            editor!!
                .state.value.draft!!
                .light,
        )
        compose.onNodeWithTag("palette_editor_confirm_remove").performClick()
        compose.waitForIdle()
        assertNull(
            editor!!
                .state.value.draft!!
                .light,
        )
        assertNotNull(
            editor!!
                .state.value.draft!!
                .dark,
        )
        compose.onNodeWithTag("palette_editor_remove_dark").assertDoesNotExist()
        compose.onNodeWithTag("palette_editor_back").performClick()
        compose.onNodeWithTag("palette_editor_discard").performClick()
        awaitUi { editor == null }
        assertTrue(current().customPalettes.isEmpty())
    }

    @Test fun invalidHexAndAccessibleWheel_keepInvalidInputFromSaving() {
        editor = editorModel(editorHandle(rawDraft()))
        mount()
        compose.onNodeWithTag("palette_editor_primary").performScrollTo().performClick()
        compose.onNodeWithTag("palette_editor_hex").performScrollTo().performTextReplacement("#XYZ")
        compose.onNodeWithTag("palette_editor_color_done").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("palette_editor_color_done").assertIsNotEnabled()
        compose.onNodeWithTag("palette_editor_hex").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Error))
        compose
            .onNodeWithTag("palette_editor_wheel")
            .assertExists()
            .assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription))
        assertTrue(editor!!.state.value.invalidFields)
        compose.runOnIdle { editor!!.save(false) }
        compose.waitForIdle()
        assertFalse(editor!!.state.value.saved)
        assertTrue(current().customPalettes.isEmpty())
        compose.onNodeWithTag("palette_editor_hex").performScrollTo().performTextReplacement("#0077CC")
        compose.onNodeWithTag("palette_editor_wheel").performScrollTo().performTouchInput { click(center) }
        assertNotEquals(
            "#0077CC",
            editor!!
                .state.value.draft!!
                .light!!
                .primary,
        )
        compose.onNodeWithTag("palette_editor_color_done").performClick()
        compose.onNodeWithTag("palette_editor_save").assertIsEnabled()
    }

    @Test fun oversizedInputStaysInvalidAcrossRestore_untilThatFieldIsCorrected() {
        val handle = editorHandle(rawDraft())
        editor = editorModel(handle)
        mount()
        compose.onNodeWithTag("palette_editor_edit_light").performScrollTo().performClick()
        compose.onNodeWithTag("palette_editor_primary").performScrollTo().performClick()
        compose.onNodeWithTag("palette_editor_hex").performScrollTo().performTextReplacement("#123456" + " ".repeat(26))
        compose.onNodeWithTag("palette_editor_color_done").assertIsNotEnabled()
        compose.onNode(hasText(context.getString(R.string.palette_editor_hex_error)) and hasAnyAncestor(hasTestTag("palette_editor_color_dialog"))).assertIsDisplayed()
        val restored = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        compose.runOnIdle { editor = editorModel(restored) }
        awaitLoaded()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("palette_editor_color_done").assertIsNotEnabled()
        compose.onNodeWithTag("palette_editor_hex").performScrollTo().performTextReplacement("#123456")
        compose.onNodeWithTag("palette_editor_color_done").performClick()
        compose.runOnIdle {
            editor!!.setColor(PaletteMode.LIGHT, "nick_0", "x".repeat(33))
            editor!!.moveNickname(PaletteMode.LIGHT, 0, 1)
        }
        compose.onNodeWithTag("palette_editor_save").assertIsNotEnabled()
        compose.runOnIdle { editor!!.removeNickname(PaletteMode.LIGHT, 1) }
        compose.onNodeWithTag("palette_editor_save").assertIsEnabled()
        compose.runOnIdle { editor!!.setColor(PaletteMode.DARK, "primary", "x".repeat(33)) }
        compose.onNodeWithTag("palette_editor_save").assertIsNotEnabled()
        compose.runOnIdle { editor!!.removeMode(PaletteMode.DARK) }
        compose.onNodeWithTag("palette_editor_save").assertIsEnabled()
        compose.onNodeWithTag("palette_editor_name").performScrollTo().performTextReplacement("x".repeat(257))
        compose.onNodeWithTag("palette_editor_save").assertIsNotEnabled()
        compose.runOnIdle { editor!!.save(true) }
        assertTrue(current().customPalettes.isEmpty())
        assertEquals(PaletteChoice(), current().paletteSelection.light)
        assertNull(current().paletteSelection.dark.installedId)
        compose.onNodeWithTag("palette_editor_name").performTextReplacement("Corrected name")
        compose.onNodeWithTag("palette_editor_save").assertIsEnabled().performClick()
        awaitUi { editor == null }
        val saved = current()
        assertEquals(
            "Corrected name",
            saved.customPalettes
                .single()
                .document.name,
        )
        assertEquals(
            "#123456",
            saved.customPalettes
                .single()
                .document.light!!
                .primary,
        )
        assertEquals(
            rawDraft().light!!.nicknameColors.drop(1),
            saved.customPalettes
                .single()
                .document.light!!
                .nicknameColors,
        )
        assertNull(
            saved.customPalettes
                .single()
                .document.dark,
        )
        assertEquals(PaletteChoice(), saved.paletteSelection.light)
        assertNull(saved.paletteSelection.dark.installedId)
    }

    @Test fun nativeEditorLightDarkAndTrueBlack_renderAuthoredSample_notRootTheme() {
        editor = editorModel(editorHandle(rawDraft()))
        mount()
        compose.onNodeWithTag("palette_editor_preview_light").performClick()
        val light = capture("editor-light.png")
        assertContainsColor(light, android.graphics.Color.parseColor(rawDraft().light!!.background))
        compose.onNodeWithTag("palette_editor_preview_dark").performClick()
        val dark = capture("editor-dark.png")
        assertContainsColor(dark, android.graphics.Color.parseColor(rawDraft().dark!!.background))
        assertFalse(light.sameAs(dark))
        runBlocking { prefs.setTrueBlack(true) }
        awaitUi {
            editor!!
                .state.value.appearance.trueBlack && rootBackground == android.graphics.Color.BLACK
        }
        compose.waitForIdle()
        val black = capture("editor-trueblack.png")
        assertContainsColor(black, android.graphics.Color.BLACK)
        assertFalse(dark.sameAs(black))
        assertEquals(android.graphics.Color.BLACK, rootBackground)
        assertTrue(current().customPalettes.isEmpty())
    }

    @Test fun readabilityPolicyChangesContainedPreviewWithoutChangingAuthoredDraft() {
        val base = rawDraft()
        val document = base.copy(light = requireNotNull(base.light).copy(primary = "#FFFFFF", onSurface = "#EEEEEE", onBackground = "#EEEEEE"))
        runBlocking { prefs.setUseThemeTextColors(false) }
        editor = editorModel(editorHandle(document))
        mount()
        compose.onNodeWithTag("palette_editor_preview_light").performClick()
        val authored = sample()
        capture("editor-readability-off.png")
        compose.onNodeWithText("Adjusted for readability", useUnmergedTree = true).assertDoesNotExist()
        runBlocking { prefs.setAdjustPaletteReadability(true) }
        awaitUi {
            editor!!
                .state.value.appearance.adjustPaletteReadability
        }
        compose.waitForIdle()
        assertFalse(authored.sameAs(sample()))
        capture("editor-readability-on.png")
        assertEquals(document, editor!!.state.value.draft)
        assertFalse(current().useThemeTextColors)
        assertTrue(current().customPalettes.isEmpty())
        runBlocking { prefs.setAdjustPaletteReadability(false) }
        awaitUi {
            !editor!!
                .state.value.appearance.adjustPaletteReadability
        }
        compose.waitForIdle()
        assertTrue(authored.sameAs(sample()))
        compose.runOnIdle {
            editor = null
            preview = previewModel(document)
        }
        awaitLoaded()
        compose.onNodeWithTag("palette_preview_mode_light").performClick()
        val fullSample = compose.onNodeWithTag("palette_preview_sample_light")
        fullSample.performScrollTo()
        val fullAuthored = fullSample.captureToImage().asAndroidBitmap()
        capture("full-preview-readability-off.png")
        runBlocking { prefs.setAdjustPaletteReadability(true) }
        awaitUi {
            preview!!
                .uiState.value.appearance.adjustPaletteReadability
        }
        compose.waitForIdle()
        assertFalse(fullAuthored.sameAs(fullSample.captureToImage().asAndroidBitmap()))
        capture("full-preview-readability-on.png")
        assertTrue(current().customPalettes.isEmpty())
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp", fontScale = 1.5f)
    fun largeFontNarrowPane_keepsActionsAndLayoutControlsReachable() {
        editor = editorModel(editorHandle(rawDraft()))
        mount()
        compose.onNodeWithTag("palette_editor_save").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("palette_editor_save_apply").performScrollTo().assertIsDisplayed()
        for (layout in LayoutDensity.entries) {
            compose.onNodeWithTag("palette_editor_layout_${layout.name}").performScrollTo().performClick()
            compose.onNodeWithTag("palette_editor_sample").assertExists()
        }
        compose.onNodeWithTag("palette_editor_primary").performScrollTo().performClick()
        compose.onNodeWithTag("palette_editor_hex").assertIsDisplayed()
        compose.onNodeWithTag("palette_editor_color_done").assertIsDisplayed()
    }

    @Test fun fullPreviewCancelApplyAndConflicts_useRealInstalledDefinitions() {
        val base = rawDraft()
        preview = previewModel(base)
        mount()
        assertTrue(current().customPalettes.isEmpty())
        assertEquals(PaletteChoice(), current().paletteSelection.light)
        assertNull(current().paletteSelection.dark.installedId)
        assertEquals(PaletteMode.SYSTEM, preview!!.uiState.value.mode)
        assertEquals(PaletteMode.SYSTEM, preview!!.uiState.value.assignmentTarget)
        assertEquals(PaletteMode.DARK, current().paletteSelection.mode)
        capture("full-preview-light.png")
        compose.onNodeWithTag("palette_preview_list").performScrollToNode(hasText("Dark preview"))
        capture("full-preview-dark.png")
        compose.onNodeWithTag("palette_preview_cancel").performClick()
        awaitUi { preview == null }
        assertTrue(current().customPalettes.isEmpty())
        compose.runOnIdle { preview = previewModel(base) }
        awaitLoaded()
        compose.onNodeWithTag("palette_assign_dark").performClick()
        compose.onNodeWithTag("palette_preview_apply").performClick()
        awaitUi { preview == null }
        assertEquals(base.id, current().paletteSelection.dark.installedId)
        assertEquals(PaletteChoice(), current().paletteSelection.light)
        assertEquals(PaletteMode.DARK, current().paletteSelection.mode)
        val altered = base.copy(name = "Different snapshot", dark = base.dark!!.copy(primary = "#00AACC"))
        compose.runOnIdle { preview = previewModel(altered) }
        awaitLoaded()
        compose.onNodeWithTag("palette_preview_save").performClick()
        awaitUi { preview!!.uiState.value.conflict }
        compose.onNodeWithTag("palette_preview_keep_both").assertIsSelected()
        compose.onNodeWithTag("palette_preview_confirm_conflict").performClick()
        awaitUi { preview == null }
        assertEquals(2, current().customPalettes.size)
        assertEquals(base, current().customPalettes.single { it.document.id == base.id }.document)
        assertEquals(base.id, current().paletteSelection.dark.installedId)
        compose.runOnIdle { preview = previewModel(altered) }
        awaitLoaded()
        compose.onNodeWithTag("palette_preview_save").performClick()
        awaitUi { preview!!.uiState.value.conflict }
        compose.onNodeWithTag("palette_preview_replace").performClick()
        compose.onNodeWithTag("palette_preview_confirm_conflict").performClick()
        awaitUi { preview == null }
        assertEquals(2, current().customPalettes.size)
        assertEquals(altered, current().customPalette(current().paletteSelection.dark))
    }

    @Test fun overlongOptionalSourceLink_doesNotInvalidatePreviewOrAppliedDocument() {
        val document = rawDraft()
        val before = current()
        val sourceLink = "https://files.example/${document.id}.motd-palette.json#" + "x".repeat(2048)
        preview = previewModel(document, sourceLink = sourceLink)
        mount()
        compose.onNodeWithText(document.name).assertIsDisplayed()
        assertEquals(before, current())
        compose.onNodeWithTag("palette_assign_dark").performClick()
        compose.onNodeWithTag("palette_preview_apply").performClick()
        awaitUi { preview == null }
        assertEquals(document, current().customPalette(current().paletteSelection.dark))
        assertEquals(before.paletteSelection.mode, current().paletteSelection.mode)
        val installed = current().customPalettes.single()
        assertNull(installed.sourceLink)
        assertNull(installed.catalogId)
        assertNull(installed.catalogRevision)
    }

    @Test fun updateMissingExplicitTarget_neverFallsBackToInstalling() {
        preview = previewModel(rawDraft(), "deleted-target")
        mount()
        awaitUi { preview!!.uiState.value.error != null }
        compose.onNodeWithTag("palette_preview_error").assertExists()
        compose.onNodeWithTag("palette_preview_update").assertIsNotEnabled()
        compose.runOnIdle { preview!!.saveWithoutApplying() }
        awaitUi { !preview!!.uiState.value.saving && preview!!.uiState.value.error != null }
        assertTrue(current().customPalettes.isEmpty())
        assertEquals(PaletteChoice(), current().paletteSelection.light)
        assertNull(current().paletteSelection.dark.installedId)
    }

    @Test fun nearLimitDuplicateReportsError_withoutNavigatingOrChangingInstalledData() {
        val base = rawDraft().copy(id = "x", name = "N", light = null, sourceUrl = null, license = null, notices = "")
        val nearLimit = base.copy(notices = "\u0001".repeat((PALETTE_MAX_BYTES - encodePalette(base).size) / 6))
        runBlocking { prefs.savePalette(InstalledPalette(nearLimit), false, PaletteMode.DARK) }
        hub =
            PaletteHubViewModel(
                prefs,
                settings,
                io.github.trevarj.motd.data.repo
                    .PaletteCatalog(context),
            ).also { viewModels.put("hub", it) }
        mount()
        awaitUi {
            hub!!
                .state.value.appearance.customPalettes.size == 1
        }
        compose.onNodeWithText(context.getString(R.string.palette_yours)).performClick()
        compose.onNodeWithTag("palette_target_dark").performClick()
        compose.onNodeWithText(context.getString(R.string.palette_duplicate)).performScrollTo().performClick()
        awaitUi { hub!!.state.value.error != null }
        compose.onNodeWithText(requireNotNull(hub!!.state.value.error)).assertIsDisplayed()
        assertNull(editor)
        assertEquals(listOf(InstalledPalette(nearLimit)), current().customPalettes)
        assertEquals(PaletteChoice(), current().paletteSelection.light)
        assertNull(current().paletteSelection.dark.installedId)
    }

    private fun mount() {
        restoration.setContent {
            val appearance by prefs.config.collectAsState(AppearanceConfig())
            val selection = appearance.paletteSelection
            val dark = selection.mode == PaletteMode.DARK || (selection.mode == PaletteMode.SYSTEM && isSystemInDarkTheme())
            val choice = selection.choiceFor(dark)
            MotdTheme(
                themePreset = choice.builtin,
                paletteMode = if (dark) PaletteMode.DARK else PaletteMode.LIGHT,
                customPalette = appearance.customPalette(choice),
                trueBlack = appearance.trueBlack,
                dynamicColor = false,
                useThemeTextColors = appearance.useThemeTextColors,
                adjustPaletteReadability = appearance.adjustPaletteReadability,
                syncSystemBars = false,
            ) {
                val primary = MaterialTheme.colorScheme.primary.toArgb()
                val background = MaterialTheme.colorScheme.background.toArgb()
                SideEffect {
                    rootPrimary = primary
                    rootBackground = background
                }
                var composer by rememberSaveable { mutableStateOf("unsent channel draft") }
                Surface(Modifier.fillMaxSize()) {
                    Column {
                        OutlinedTextField(composer, { composer = it }, Modifier.height(64.dp).testTag("retained_composer"))
                        val activeEditor = editor
                        val activePreview = preview
                        val activeHub = hub
                        when {
                            activeEditor != null -> {
                                PaletteEditorScreen(
                                    onBack = { editor = null },
                                    onSaved = { editor = null },
                                    viewModel = activeEditor,
                                )
                            }

                            activePreview != null -> {
                                PalettePreviewScreen(
                                    onBack = { preview = null },
                                    onInstalled = { preview = null },
                                    viewModel = activePreview,
                                )
                            }

                            activeHub != null -> {
                                PaletteHubScreen(
                                    onBack = { hub = null },
                                    onEdit = { document, id, target ->
                                        editor = editorModel(editorHandle(document, id, target))
                                        hub = null
                                    },
                                    onPreview = { document, _, _, id, target ->
                                        preview = previewModel(document, id, assignmentTarget = target)
                                        hub = null
                                    },
                                    viewModel = activeHub,
                                )
                            }
                        }
                    }
                }
            }
        }
        awaitLoaded()
    }

    private fun awaitUi(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            condition()
        }
    }

    private fun awaitLoaded() {
        awaitUi {
            val editorReady = editor?.state?.value?.let { it.draft != null || it.error != null } ?: true
            val previewReady = preview?.uiState?.value?.let { it.document != null || it.error != null } ?: true
            editorReady && previewReady && rootPrimary != 0
        }
        compose.waitForIdle()
    }

    private fun editorHandle(
        document: PaletteDocument,
        installedId: String? = null,
        assignmentTarget: PaletteMode = PaletteMode.DARK,
    ): SavedStateHandle {
        val route = PaletteEditorRoute(encodePalette(document).decodeToString(), installedId, assignmentTarget)
        return SavedStateHandle(mapOf("paletteJson" to route.paletteJson, "editingInstalledId" to route.editingInstalledId, "assignmentTarget" to assignmentTarget))
    }

    private fun editorModel(handle: SavedStateHandle) = PaletteEditorViewModel(handle, prefs, settings).also { viewModels.put("editor-${nextViewModel++}", it) }

    private fun previewModel(
        document: PaletteDocument,
        target: String? = null,
        sourceLink: String? = null,
        assignmentTarget: PaletteMode? = null,
    ): PalettePreviewViewModel {
        val route =
            PalettePreviewRoute(
                encodePalette(document).decodeToString(),
                sourceLink = sourceLink,
                catalogId = document.id.takeIf { target != null },
                targetInstalledId = target,
                assignmentTarget = assignmentTarget,
            )
        return PalettePreviewViewModel(
            SavedStateHandle(
                mapOf(
                    "paletteJson" to route.paletteJson,
                    "sourceLink" to route.sourceLink,
                    "catalogId" to route.catalogId,
                    "targetInstalledId" to route.targetInstalledId,
                    "assignmentTarget" to route.assignmentTarget,
                ),
            ),
            prefs,
            settings,
        ).also { viewModels.put("preview-${nextViewModel++}", it) }
    }

    private fun editHex(
        tag: String,
        color: String,
    ) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.onNodeWithTag("palette_editor_hex").performScrollTo().performTextReplacement(color)
        compose.onNodeWithTag("palette_editor_color_done").performClick()
        compose.waitForIdle()
    }

    private fun current() = runBlocking { prefs.config.first() }

    private fun sample(): Bitmap = compose.onNodeWithTag("palette_editor_sample").captureToImage().asAndroidBitmap()

    private fun capture(name: String): Bitmap {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val module = if (File("app/src").isDirectory) File("app") else File(".")
        val file = File(module, "build/reports/palettes/$name")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return bitmap
    }

    private fun assertContainsColor(
        bitmap: Bitmap,
        color: Int,
    ) {
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) == color) count++
            }
        }
        assertTrue("Expected meaningful native surface ${color.toUInt().toString(16)}; found $count pixels", count > 100)
    }

    private fun rawDraft(): PaletteDocument {
        fun colors(preset: ColorThemePreset): PaletteColors {
            val raw = requireNotNull(rawFixedThemeScheme(preset))

            fun hex(color: androidx.compose.ui.graphics.Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)
            return PaletteColors(
                hex(raw.primary),
                hex(raw.secondary),
                hex(raw.tertiary),
                hex(raw.background),
                hex(raw.surface),
                hex(raw.surfaceContainer),
                hex(raw.onBackground),
                hex(raw.onSurface),
                hex(raw.onSurfaceVariant),
                hex(raw.outlineVariant),
                themeIdentityPalette(preset).map(::hex),
            )
        }
        return PaletteDocument(
            "native-editor",
            "Native editor",
            1,
            light = colors(ColorThemePreset.AYU_LIGHT),
            dark = colors(ColorThemePreset.AYU_DARK),
            sourceUrl = "https://example.org/palette",
            license = "Preserved license",
            notices = "Preserved notice",
        )
    }
}
