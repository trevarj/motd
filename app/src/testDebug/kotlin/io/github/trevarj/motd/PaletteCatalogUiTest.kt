package io.github.trevarj.motd

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.drawToBitmap
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.prefs.*
import io.github.trevarj.motd.data.repo.PaletteCatalog
import io.github.trevarj.motd.ui.settings.PaletteHubScreen
import io.github.trevarj.motd.ui.settings.PaletteHubViewModel
import io.github.trevarj.motd.ui.settings.PalettePreviewScreen
import io.github.trevarj.motd.ui.settings.PalettePreviewViewModel
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class PaletteCatalogUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = AppearancePrefsImpl(context)
    private val settings = DataStoreSettingsRepository(context)
    private val models = ViewModelStore()
    private val server = MockWebServer()
    private lateinit var catalogFiles: File
    private lateinit var catalog: PaletteCatalog
    private lateinit var hub: PaletteHubViewModel
    private lateinit var originalAppearance: Preferences
    private lateinit var originalSettings: Preferences
    private lateinit var baseline: AppearanceConfig
    private var preview by mutableStateOf<PalettePreviewViewModel?>(null)
    private var serial = 0
    private var rootPrimary = 0
    private var serverStopped = false
    private val fixture: ByteArray get() =
        requireNotNull(
            javaClass.getResourceAsStream(
                "/palettes/duplicate-last-valid.motd-palette.json",
            ),
        ).use { it.readBytes() }
    private val document: PaletteDocument get() = decodePalette(fixture)

    @Before fun isolatePreferences() =
        runBlocking {
            originalAppearance = context.appearanceDataStore.data.first()
            originalSettings = context.settingsDataStore.data.first()
            context.appearanceDataStore.edit { it.clear() }
            context.settingsDataStore.edit { it.clear() }
            prefs.selectPalette(PaletteSelection(dark = PaletteChoice(ColorThemePreset.DARK), mode = PaletteMode.DARK))
            baseline = current()
            catalogFiles =
                File.createTempFile("catalog-ui-", "", context.cacheDir).apply {
                    delete()
                    mkdirs()
                }
            val isolatedContext =
                object : ContextWrapper(context) {
                    override fun getFilesDir(): File = catalogFiles
                }
            server.start()
            catalog = PaletteCatalog(isolatedContext, server.url("/palettes/index.json").toUrl())
            hub = PaletteHubViewModel(prefs, settings, catalog).also { models.put("hub", it) }
        }

    @After fun restorePreferences(): Unit =
        runBlocking {
            models.clear()
            if (!serverStopped) server.shutdown()
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
            catalogFiles.deleteRecursively()
        }

    @Test fun hubAssignsIndependentLightAndDarkIdentities_withoutChangingOtherSlotOrDisplayMode() {
        val light = document.copy(id = "light-only", name = "Independent light", dark = null)
        val dark = document.copy(id = "dark-only", name = "Independent dark", light = null)
        runBlocking {
            prefs.savePalette(InstalledPalette(light), apply = false, mode = PaletteMode.LIGHT)
            prefs.savePalette(InstalledPalette(dark), apply = false, mode = PaletteMode.DARK)
            prefs.selectPalette(baseline.paletteSelection.copy(mode = PaletteMode.SYSTEM))
        }
        val before = current().paletteSelection
        mount()
        await { hub.state.value.appearance.customPalettes.size == 2 }
        compose.onNodeWithText(context.getString(R.string.palette_yours)).performClick()
        compose.onNodeWithTag("palette_target_light").performClick().assertIsSelected()
        compose.onNodeWithText(dark.name).assertDoesNotExist()
        compose.onNodeWithText(light.name).performClick()
        await { current().paletteSelection.light.installedId == light.id }
        compose.onNodeWithText(light.name).assertIsSelected()
        assertEquals(before.dark, current().paletteSelection.dark)
        assertEquals(before.mode, current().paletteSelection.mode)

        compose.onNodeWithTag("palette_target_dark").performClick().assertIsSelected()
        compose.onNodeWithText(light.name).assertDoesNotExist()
        compose.onNodeWithText(dark.name).performClick()
        await { current().paletteSelection.dark.installedId == dark.id }
        compose.onNodeWithText(dark.name).assertIsSelected()
        assertEquals(PaletteChoice(installedId = light.id), current().paletteSelection.light)
        assertEquals(before.mode, current().paletteSelection.mode)

        compose.onNodeWithTag("palette_target_light").performClick()
        compose.onNodeWithText(light.name).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.palette_builtin)).performClick()
        compose.onNodeWithTag("palette_builtin_system").performClick()
        await { current().paletteSelection.light == PaletteChoice() }
        compose.onNodeWithTag("palette_builtin_system").assertIsSelected()
        assertEquals(PaletteChoice(installedId = dark.id), current().paletteSelection.dark)
        assertEquals(before.mode, current().paletteSelection.mode)
        assertEquals(setOf(InstalledPalette(light), InstalledPalette(dark)), current().customPalettes.toSet())
    }

    @Test fun darkOnlyBuiltinsAreHiddenForLight_andApplyOnlyToDark() {
        mount()
        await { hub.state.value.appearance.paletteSelection == baseline.paletteSelection }
        compose.onNodeWithTag("palette_search").performTextReplacement("Ayu Mirage")
        compose.onNodeWithTag("palette_target_light").assertIsSelected()
        compose.onNodeWithTag("palette_builtin_ayu_mirage").assertDoesNotExist()

        compose.onNodeWithTag("palette_target_dark").performClick()
        compose.onNodeWithTag("palette_builtin_ayu_mirage").performClick()
        await { current().paletteSelection.dark == PaletteChoice(ColorThemePreset.AYU_MIRAGE) }
        compose.onNodeWithTag("palette_builtin_ayu_mirage").assertIsSelected()
        assertEquals(baseline.paletteSelection.light, current().paletteSelection.light)
        assertEquals(baseline.paletteSelection.mode, current().paletteSelection.mode)

        compose.onNodeWithTag("palette_target_light").performClick()
        compose.onNodeWithTag("palette_builtin_ayu_mirage").assertDoesNotExist()
        assertEquals(PaletteChoice(ColorThemePreset.AYU_MIRAGE), current().paletteSelection.dark)
        assertEquals(baseline.paletteSelection.light, current().paletteSelection.light)
        assertEquals(baseline.paletteSelection.mode, current().paletteSelection.mode)
    }

    @Test fun communityEntryAndRefresh_fetchEmptyThenLive_searchAndOfflineSnapshotInstall() {
        server.enqueue(response(index()).setBodyDelay(1, TimeUnit.SECONDS))
        mount()
        compose.waitForIdle()
        assertEquals(0, server.requestCount)
        assertEquals(baseline, current())
        enterCommunity()
        await { catalog.state.value.loading }
        compose.onNodeWithTag("palette_catalog_loading").assertIsDisplayed()
        await { server.requestCount == 1 && !catalog.state.value.loading }
        compose.onNodeWithTag("palette_catalog_empty").assertIsDisplayed()
        compose.onNodeWithTag("palette_create").assertIsDisplayed()
        compose.onNodeWithTag("palette_import").assertIsDisplayed()
        capture("catalog-empty.png")
        assertEquals(baseline, current())

        server.enqueue(response(index(fixture.decodeToString())))
        compose.onNodeWithTag("palette_catalog_refresh").performClick()
        await { catalog.state.value.palettes.size == 1 && !catalog.state.value.loading }
        val primaryBefore = rootPrimary
        compose.onNodeWithTag(rowTag()).assertIsDisplayed()
        compose.onNodeWithText("Light and dark").assertIsDisplayed()
        capture("catalog-live.png")
        compose.onNodeWithTag("palette_search").performTextReplacement("not present")
        compose.onNodeWithTag(rowTag()).assertDoesNotExist()
        compose.onNodeWithTag("palette_search").performTextClearance()
        compose.onNodeWithTag(rowTag()).assertIsDisplayed()
        assertEquals(2, server.requestCount)
        assertEquals(baseline, current())
        assertEquals(primaryBefore, rootPrimary)

        stopServer()
        compose.onNodeWithTag("palette_catalog_refresh").performClick()
        await { catalog.state.value.error != null && !catalog.state.value.loading }
        compose.onNodeWithTag("palette_catalog_cached").assertIsDisplayed()
        compose.onNodeWithTag("palette_catalog_error").assertIsDisplayed()
        compose.onNodeWithTag("palette_list").performScrollToNode(hasTestTag(rowTag()))
        compose.onNodeWithTag(rowTag()).assertIsDisplayed().performClick()
        awaitPreview()
        compose.onNodeWithText(document.name).assertIsDisplayed()
        compose.onNodeWithText("Light and dark").assertIsDisplayed()
        capture("catalog-live-preview.png")
        assertEquals(document, preview!!.uiState.value.document)
        assertEquals(baseline, current())
        assertEquals(primaryBefore, rootPrimary)
        compose.onNodeWithTag("palette_assign_dark").performClick()
        compose.onNodeWithTag("palette_preview_apply").performClick()
        await { preview == null && current().customPalettes.size == 1 }
        val installed = current().customPalettes.single()
        assertEquals(document, installed.document)
        assertEquals(document.id, installed.catalogId)
        assertEquals(document.revision, installed.catalogRevision)
        assertEquals(document.id, current().paletteSelection.dark.installedId)
        assertEquals(baseline.paletteSelection.light, current().paletteSelection.light)
        assertEquals(PaletteMode.DARK, current().paletteSelection.mode)
        val reloaded = runBlocking { AppearancePrefsImpl(context).config.first() }
        assertEquals(current().paletteSelection, reloaded.paletteSelection)
        assertEquals(document, reloaded.customPalette(reloaded.paletteSelection.dark))
        compose.onNodeWithTag("palette_list").performScrollToNode(hasText("Installed"))
        compose.onNodeWithText("Installed").assertIsDisplayed()
    }

    @Test fun multipleTrackedInstances_chooseTarget_showOldNew_cancelThenUpdateOnce_preservesSelectionAndMode() {
        val old = tracked("kept-copy", "Old selected copy")
        val other = tracked("other-copy", "Other installed copy")
        runBlocking {
            prefs.savePalette(other, apply = false, mode = PaletteMode.LIGHT)
            prefs.savePalette(old, apply = true, mode = PaletteMode.LIGHT)
        }
        val before = current()
        val newer =
            document.copy(
                revision = 2,
                name = "Updated community palette",
                light = document.light!!.copy(primary = "#D04433"),
            )
        server.enqueue(response(index(encodePalette(newer).decodeToString())))
        mount()
        enterCommunity()
        await {
            catalog.state.value.palettes
                .singleOrNull()
                ?.revision == 2 && !catalog.state.value.loading
        }
        compose.onNodeWithText("Update available").assertIsDisplayed()
        compose.onNodeWithTag(rowTag()).performClick()
        compose.onNodeWithTag("palette_catalog_targets").assertIsDisplayed()
        compose.onNodeWithTag("palette_catalog_target_${old.document.id}").assertIsDisplayed()
        compose.onNodeWithTag("palette_catalog_target_${other.document.id}").assertIsDisplayed()
        capture("catalog-update-targets.png", dialog = true)
        compose.onNodeWithTag("palette_catalog_target_${old.document.id}").performClick()
        awaitPreview()
        compose.onNodeWithTag("palette_preview_list").performScrollToNode(hasTestTag("palette_preview_old"))
        compose.onNodeWithTag("palette_preview_old").assertIsDisplayed().assertTextContains(old.document.name, substring = true)
        capture("catalog-update-old.png")
        compose.onNodeWithTag("palette_preview_list").performScrollToNode(hasText(newer.name))
        compose.onNodeWithTag("palette_preview_new").assertIsDisplayed()
        compose.onNodeWithText(newer.name).assertIsDisplayed()
        compose.onNodeWithTag("palette_preview_list").performScrollToNode(hasTestTag("palette_preview_sample_light"))
        capture("catalog-update-new.png")
        compose.onNodeWithTag("palette_preview_apply").assertDoesNotExist()
        compose.onNodeWithTag("palette_preview_update").assertIsEnabled()
        assertEquals(before, current())
        compose.onNodeWithTag("palette_preview_cancel").performClick()
        await { preview == null }
        assertEquals(before, current())

        compose.onNodeWithTag(rowTag()).performClick()
        compose.onNodeWithTag("palette_catalog_target_${old.document.id}").performClick()
        awaitPreview()
        val submitted = preview!!
        stopServer()
        compose.onNodeWithTag("palette_preview_update").performClick()
        await { preview == null && current().customPalettes.find { it.document.id == old.document.id }?.catalogRevision == 2 }
        val after = current()
        assertEquals(before.paletteSelection, after.paletteSelection)
        assertEquals(2, after.customPalettes.size)
        assertEquals(other, after.customPalettes.single { it.document.id == other.document.id })
        assertEquals(newer.copy(id = old.document.id), after.customPalette(after.paletteSelection.light))
        assertEquals(document.id, after.customPalettes.single { it.document.id == old.document.id }.catalogId)
        compose.runOnIdle { submitted.saveWithoutApplying() }
        compose.waitForIdle()
        assertEquals(after, current())
    }

    @Test fun currentRevisionMultipleInstances_appliesChosenCopy_withoutUpdatingOrDuplicatingDefinitions() {
        val selected = tracked("selected-copy", "Selected copy")
        val chosen = tracked("chosen-copy", "Chosen copy")
        runBlocking {
            prefs.savePalette(selected, apply = true, mode = PaletteMode.LIGHT)
            prefs.savePalette(chosen, apply = false, mode = PaletteMode.DARK)
        }
        val before = current()
        server.enqueue(response(index(encodePalette(document).decodeToString())))
        mount()
        enterCommunity()
        await { catalog.state.value.palettes.size == 1 && !catalog.state.value.loading }
        compose.onNodeWithTag(rowTag()).performClick()
        compose.onNodeWithTag("palette_catalog_target_${chosen.document.id}").performClick()
        awaitPreview()
        compose.onNodeWithTag("palette_preview_update").assertDoesNotExist()
        compose.onNodeWithTag("palette_preview_apply").assertIsEnabled()
        assertEquals(chosen.document, preview!!.uiState.value.document)
        assertEquals(before, current())
        compose.onNodeWithTag("palette_assign_light").performClick()
        compose.onNodeWithTag("palette_preview_apply").performClick()
        await { preview == null && current().paletteSelection.light.installedId == chosen.document.id }
        assertEquals(before.customPalettes, current().customPalettes)
        assertEquals(before.paletteSelection.dark, current().paletteSelection.dark)
        assertEquals(before.paletteSelection.mode, current().paletteSelection.mode)
    }

    @Test fun identicalUntrackedId_gainsCatalogTracking_insteadOfSelectingAnotherTrackedCopy() {
        val other = tracked("selected-copy", "Selected tracked copy")
        runBlocking {
            prefs.savePalette(InstalledPalette(document), apply = false, mode = PaletteMode.DARK)
            prefs.savePalette(other, apply = true, mode = PaletteMode.LIGHT)
        }
        server.enqueue(response(index(encodePalette(document).decodeToString())))
        mount()
        enterCommunity()
        await { catalog.state.value.palettes.size == 1 && !catalog.state.value.loading }
        compose.onNodeWithTag(rowTag()).performClick()
        awaitPreview()
        compose.onNodeWithTag("palette_assign_light").performClick()
        compose.onNodeWithTag("palette_preview_apply").performClick()
        await { preview == null }
        val after = current()
        assertEquals(document.id, after.paletteSelection.light.installedId)
        assertEquals(PaletteMode.DARK, after.paletteSelection.mode)
        assertEquals(PaletteChoice(ColorThemePreset.DARK), after.paletteSelection.dark)
        assertEquals(2, after.customPalettes.size)
        assertEquals(other, after.customPalettes.single { it.document.id == other.document.id })
        val installed = after.customPalettes.single { it.document.id == document.id }
        assertEquals(document, installed.document)
        assertEquals(document.id, installed.catalogId)
        assertEquals(document.revision, installed.catalogRevision)
    }

    @Test fun removedUpdateTarget_showsRecoverableError_andNeverInstallsSnapshot() {
        val target = tracked("removed-target", "Removed target")
        runBlocking { prefs.savePalette(target, apply = false, mode = PaletteMode.DARK) }
        server.enqueue(response(index(encodePalette(document.copy(revision = 2)).decodeToString())))
        mount()
        enterCommunity()
        await {
            catalog.state.value.palettes
                .singleOrNull()
                ?.revision == 2 && !catalog.state.value.loading
        }
        compose.onNodeWithTag(rowTag()).performClick()
        awaitPreview()
        runBlocking { prefs.removePalette(target.document.id) }
        val afterRemoval = current()
        await { preview!!.uiState.value.error != null }
        compose.onNodeWithTag("palette_preview_error").assertIsDisplayed()
        compose.onNodeWithTag("palette_preview_update").assertIsNotEnabled()
        compose.runOnIdle { preview!!.saveWithoutApplying() }
        await { !preview!!.uiState.value.saving }
        assertEquals(afterRemoval, current())
        assertTrue(current().customPalettes.isEmpty())
        compose.onNodeWithTag("palette_preview_cancel").performClick()
        await { preview == null }
        assertEquals(afterRemoval, current())
    }

    @Test fun singleModeCatalogRefresh_isRejectedEntirely_andRetainsLastGoodRowsAndTheme() {
        server.enqueue(response(index(fixture.decodeToString())))
        mount()
        enterCommunity()
        await { catalog.state.value.palettes.size == 1 && !catalog.state.value.loading }
        val before = current()
        val primaryBefore = rootPrimary
        val invalid = document.copy(id = "single-mode-submission", name = "Invalid single mode", dark = null)
        server.enqueue(response(index(encodePalette(document.copy(revision = 2)).decodeToString(), encodePalette(invalid).decodeToString())))
        compose.onNodeWithTag("palette_catalog_refresh").performClick()
        await { catalog.state.value.error != null && !catalog.state.value.loading }
        compose.onNodeWithTag("palette_catalog_error").assertIsDisplayed()
        compose.onNodeWithTag("palette_catalog_cached").assertIsDisplayed()
        compose.onNodeWithTag(rowTag()).assertIsDisplayed()
        compose.onNodeWithText(invalid.name).assertDoesNotExist()
        compose.onNodeWithTag(rowTag()).performClick()
        awaitPreview()
        compose.onNodeWithText("Revision 1").assertIsDisplayed()
        assertEquals(document, preview!!.uiState.value.document)
        assertEquals(before, current())
        assertEquals(primaryBefore, rootPrimary)
        compose.onNodeWithTag("palette_preview_cancel").performClick()
        await { preview == null }
        assertEquals(before, current())
    }

    private fun mount() {
        compose.setContent {
            val appearance by prefs.config.collectAsState(AppearanceConfig())
            val destinations = rememberSaveableStateHolder()
            val selection = appearance.paletteSelection
            val dark = selection.mode == PaletteMode.DARK || (selection.mode == PaletteMode.SYSTEM && isSystemInDarkTheme())
            val choice = selection.choiceFor(dark)
            MotdTheme(
                themePreset = choice.builtin,
                paletteMode = if (dark) PaletteMode.DARK else PaletteMode.LIGHT,
                customPalette = appearance.customPalette(choice),
                dynamicColor = false,
                syncSystemBars = false,
            ) {
                val primary = MaterialTheme.colorScheme.primary.toArgb()
                SideEffect { rootPrimary = primary }
                Surface(Modifier.fillMaxSize()) {
                    val active = preview
                    if (active != null) {
                        PalettePreviewScreen(onBack = { preview = null }, onInstalled = { preview = null }, viewModel = active)
                    } else {
                        destinations.SaveableStateProvider("hub") {
                            PaletteHubScreen(
                                onBack = {},
                                onEdit = { _, _, _ -> error("Unexpected editor navigation") },
                                onPreview = { document, sourceLink, catalogId, targetInstalledId, assignmentTarget ->
                                    preview =
                                        PalettePreviewViewModel(
                                            SavedStateHandle(
                                                mapOf(
                                                    "paletteJson" to encodePalette(document).decodeToString(),
                                                    "sourceLink" to sourceLink,
                                                    "catalogId" to catalogId,
                                                    "targetInstalledId" to targetInstalledId,
                                                    "assignmentTarget" to assignmentTarget,
                                                ),
                                            ),
                                            prefs,
                                            settings,
                                        ).also { models.put("preview-${serial++}", it) }
                                },
                                viewModel = hub,
                            )
                        }
                    }
                }
            }
        }
        await { rootPrimary != 0 }
    }

    private fun enterCommunity() = compose.onNodeWithText(context.getString(R.string.palette_community)).performClick()

    private fun awaitPreview() = await { preview?.uiState?.value?.document != null }

    private fun await(condition: () -> Boolean) =
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            condition()
        }

    private fun current() = runBlocking { prefs.config.first() }

    private fun rowTag() = "palette_catalog_${document.id}"

    private fun tracked(
        id: String,
        name: String,
    ) = InstalledPalette(
        document.copy(id = id, name = name),
        catalogId = document.id,
        catalogRevision = document.revision,
        sourceLink = "https://github.com/trevarj/motd/blob/main/palettes/${document.id}/${document.revision}.motd-palette.json",
    )

    private fun index(vararg documents: String) = """{"format":"motd-palette-catalog","version":1,"palettes":[${documents.joinToString(",")}]}"""

    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun stopServer() {
        server.shutdown()
        serverStopped = true
    }

    private fun capture(
        name: String,
        dialog: Boolean = false,
    ) {
        compose.waitForIdle()
        val bitmap =
            if (dialog) {
                compose.runOnIdle {
                    requireNotNull(requireNotNull(ShadowDialog.getLatestDialog()).window).decorView.drawToBitmap()
                }
            } else {
                compose.onRoot().captureToImage().asAndroidBitmap()
            }
        val module = if (File("app/src").isDirectory) File("app") else File(".")
        val file = File(module, "build/reports/palettes/$name")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
