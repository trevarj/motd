package io.github.trevarj.motd.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.fonts.CustomFontStore
import io.github.trevarj.motd.data.prefs.*
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.cancel
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
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h760dp", fontScale = 1.6f)
class WallpaperWorkshopUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = inMemoryDb()
    private val buffers = BufferStore(db)
    private val prefs = AppearancePrefsImpl(context)
    private val settings = DataStoreSettingsRepository(context)
    private val store = WallpaperWorkshopStore(context, prefs, CustomWallpaperStore(context))
    private val fonts = CustomFontStore(context)
    private val global = WallpaperSelection(ChatWallpaperPreset.PATTERN, patternRef = WallpaperPatternRef(WallpaperPatternRefKind.STARTER, "radio-club"))
    private lateinit var originalAppearance: Preferences
    private lateinit var originalSettings: Preferences
    private lateinit var documents: File
    private var roomId = 0L
    private var otherRoomId = 0L
    private var editor by mutableStateOf<WallpaperWorkshopViewModel?>(null)
    private lateinit var handle: SavedStateHandle
    private val state get() = requireNotNull(editor).uiState.value

    private var pickerRequest: Int? = null
    private val picker =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                assertEquals(ActivityResultContracts.OpenDocument::class.java, contract.javaClass)
                assertTrue("image/svg+xml" in (input as Array<*>))
                pickerRequest = requestCode
            }
        }
    private val pickerOwner =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry = picker
        }

    @Before fun setUp() =
        runBlocking {
            originalAppearance = context.appearanceDataStore.data.first()
            originalSettings = context.settingsDataStore.data.first()
            context.appearanceDataStore.edit { it.clear() }
            context.settingsDataStore.edit { it.clear() }
            prefs.setWallpaper(global)
            prefs.setMessageBackgroundOpacity(100)
            prefs.setChatShadowsEnabled(false)
            prefs.setReceivedBubbleColor(ComfortableBubbleColor(ComfortableBubbleColorRole.CUSTOM, BUBBLE_COLOR))
            settings.setDynamicColor(false)
            settings.setLayoutDensity(LayoutDensity.COMPACT)
            val networkId = db.networkDao().insert(network())
            roomId = buffers.getOrCreate(networkId, "#wallpaper", "#wallpaper", BufferType.CHANNEL).id
            otherRoomId = buffers.getOrCreate(networkId, "alice", "Alice", BufferType.QUERY).id
            db.bufferDao().setLayoutDensityOverride(roomId, LayoutDensity.COMFORTABLE)
            db.bufferDao().setMessageBackgroundOpacityOverride(roomId, 0)
            documents = Files.createTempDirectory(context.cacheDir.toPath(), "wallpaper-test").toFile()
        }

    @After fun tearDown() =
        runBlocking {
            editor?.viewModelScope?.cancel()
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
            documents.deleteRecursively()
            db.close()
        }

    @Test fun draftPreviewCancelSaveApplyAndConversationInheritance() {
        openWorkshop()
        compose.setContent {
            MotdTheme(dynamicColor = false, syncSystemBars = false) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerOwner) {
                    editor?.let { vm ->
                        key(vm) {
                            WallpaperWorkshopScreen(vm, onBack = {
                                vm.viewModelScope.cancel()
                                editor = null
                            })
                        }
                    }
                }
            }
        }
        awaitWorkshop()
        assertEquals(1.6f, context.resources.configuration.fontScale, 0f)
        val initial = current()
        assertEquals(global, initial.wallpaper)
        val inherited = resolveWallpaper(initial)

        startDraft("Uncommitted clouds")
        click("wallpaper_editor_preview")
        assertEquals(WallpaperWorkshopPreviewMode.CHAT, state.previewMode)
        click("wallpaper_preview_wallpaper")
        node("wallpaper_preview_sample_incoming").assertDoesNotExist()
        click("wallpaper_preview_chat")
        click("wallpaper_preview_light")
        assertEquals(WallpaperWorkshopPreviewMode.CHAT, state.previewMode)
        assertEquals(WallpaperWorkshopPreviewTheme.LIGHT, state.previewTheme)
        val lightPixels = reveal("wallpaper_preview_sample_incoming").pixels()
        assertTrue("The light preview paints the fixed background", lightPixels.count { it == WHITE } >= 100)
        assertTrue("Light mode retains the configured 0% message backing", lightPixels.count { it == BUBBLE_COLOR } < 100)
        click("wallpaper_preview_dark")
        assertEquals(WallpaperWorkshopPreviewTheme.DARK, state.previewTheme)
        val darkPixels = reveal("wallpaper_preview_sample_incoming").pixels()
        assertTrue("The wallpaper stays white behind the backed message", darkPixels.count { it == WHITE } >= 100)
        assertTrue("Unreadable dark-theme ink gets an opaque message backing", darkPixels.count { it == BUBBLE_COLOR } >= 100)
        click("settings_back")
        assertEquals(WallpaperWorkshopTab.COLOR, state.tab)
        click("settings_back")
        click("wallpaper_discard")
        awaitState { it?.page == WallpaperWorkshopPage.HUB }
        assertEquals(initial.wallpaperLibrary, current().wallpaperLibrary)
        assertEquals(inherited, effective(roomId))
        assertNull(choice(roomId))

        startDraft(DESIGN_NAME)
        click("wallpaper_tab_icons")
        click("wallpaper_add_icons")
        pickDocument(null)
        assertNull(state.error)
        val source = File(documents, "star-trail.svg").apply { writeText(SVG) }
        pickDocument(Uri.fromFile(source))
        awaitSvgResult()
        assertNull("The outline fixture must stage successfully: ${state.error}", state.error)
        val imported = state.imports.single()
        assertEquals(SVG, imported.svg)
        assertEquals("star-trail", imported.name)
        assertTrue(imported.id in requireNotNull(state.picker).selectedIds)
        click("wallpaper_icons_done")
        click("wallpaper_tab_color")
        reveal("wallpaper_texture_none").assertIsSelected()
        click("wallpaper_texture_stars")
        val draft = requireNotNull(state.draft)
        assertEquals(
            setOf(SPARK, imported.id),
            draft.pattern.icons
                .map { it.assetId }
                .toSet(),
        )
        assertTrue(source.delete())
        val metadata = handle.keys().associateWith { handle.get<Any?>(it) }
        assertTrue(metadata.values.none { it.toString().contains("<svg") })
        compose.runOnIdle { openWorkshop(SavedStateHandle(metadata)) }
        awaitWorkshop()
        awaitState { it?.imports?.contains(imported) == true }
        reveal("wallpaper_editor_name").assertTextContains(DESIGN_NAME)
        assertEquals(draft, state.draft)
        assertEquals(WallpaperWorkshopTab.COLOR, state.tab)
        reveal("wallpaper_texture_stars").assertIsSelected()
        assertTrue(state.missingAssets.isEmpty())
        assertTrue(state.dirty)

        click("wallpaper_tab_icons")
        click("wallpaper_add_icons")
        pickDocument(Uri.fromFile(source))
        awaitSvgResult()
        assertEquals(WallpaperWorkshopError.SVG_UNREADABLE, state.error)
        compose.onNodeWithText(context.getString(R.string.wallpaper_workshop_svg_unreadable)).assertIsDisplayed()
        assertEquals(draft, state.draft)
        assertEquals(
            draft.pattern.icons
                .map { it.assetId }
                .toSet(),
            state.picker?.selectedIds,
        )
        assertEquals(initial.wallpaperLibrary, current().wallpaperLibrary)
        assertEquals(inherited, effective(roomId))
        click("wallpaper_icons_done")

        click("wallpaper_editor_save")
        awaitState { it != null && !it.busy && !it.dirty && it.draft?.baseline != null }
        val library = requireNotNull(current().wallpaperLibrary)
        val saved = library.designs.single()
        assertEquals(DESIGN_NAME, saved.name)
        assertEquals(draft.pattern, saved.pattern)
        assertEquals(listOf(imported), library.imports)
        assertEquals(global, current().wallpaper)
        assertNull(choice(roomId))

        click("wallpaper_editor_apply")
        awaitClosed()
        val savedRef = WallpaperPatternRef(WallpaperPatternRefKind.SAVED, saved.id)
        assertEquals(WallpaperChatChoice(WallpaperChatChoiceKind.PATTERN, savedRef), choice(roomId))
        assertEquals(savedRef, effective(roomId).selection.patternRef)
        assertEquals(saved.pattern, effective(roomId).pattern)
        assertNull(choice(otherRoomId))
        assertEquals(inherited, effective(otherRoomId))
        assertEquals(global, current().wallpaper)
        assertEquals(library, current().wallpaperLibrary)

        applyChoice("wallpaper_choice_none")
        assertEquals(WallpaperChatChoice(WallpaperChatChoiceKind.NONE), choice(roomId))
        assertEquals(ChatWallpaperPreset.NONE, effective(roomId).selection.preset)
        assertNull(effective(roomId).pattern)
        applyChoice("wallpaper_choice_inherit")
        assertNull(choice(roomId))
        assertEquals(inherited, effective(roomId))
        assertEquals(inherited, effective(otherRoomId))
        assertEquals(global, current().wallpaper)
    }

    @Test
    @Config(sdk = [35], fontScale = 1.1f)
    fun designNameKeepsFocusWhenKeyboardMakesEditorCompact() {
        openWorkshop()
        compose.setContent {
            MotdTheme(dynamicColor = false, syncSystemBars = false) {
                WallpaperWorkshopScreen(requireNotNull(editor), onBack = {})
            }
        }
        awaitWorkshop()
        assertEquals(1.1f, context.resources.configuration.fontScale, 0f)
        click("wallpaper_workshop_new", "wallpaper_workshop_hub")
        val name = node("wallpaper_editor_name")
        val preview = node("wallpaper_editor_preview")

        fun previewHeight() = preview.getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val tallPreview = previewHeight()
        name.performClick().assertIsFocused()
        name.performTextReplacement("Star")

        val view = (name.fetchSemanticsNode().root as ViewRootForTest).view

        fun setKeyboardInset(height: Int) {
            compose.runOnIdle {
                ViewCompat.dispatchApplyWindowInsets(
                    view,
                    WindowInsetsCompat
                        .Builder()
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, height))
                        .setVisible(WindowInsetsCompat.Type.ime(), height > 0)
                        .build(),
                )
            }
        }

        setKeyboardInset((300 * context.resources.displayMetrics.density).toInt())
        assertTrue(previewHeight() < tallPreview)
        name.assertIsDisplayed().assertIsFocused()
        name.performTextInput(" trail")
        assertEquals("Star trail", state.draft?.name)

        setKeyboardInset(0)
        assertEquals(tallPreview, previewHeight())
        name.assertIsFocused().assertTextContains("Star trail")
    }

    private fun openWorkshop(restored: SavedStateHandle = SavedStateHandle(mapOf("bufferId" to roomId))) {
        editor?.viewModelScope?.cancel()
        handle = restored
        editor = WallpaperWorkshopViewModel(handle, context, prefs, settings, store, buffers, fonts)
    }

    private fun awaitWorkshop() {
        awaitState { it != null && it.loaded && it.room?.id == roomId }
        node("wallpaper_workshop").assertIsDisplayed()
    }

    private fun awaitClosed() = awaitState { it == null }

    private fun applyChoice(tag: String) {
        compose.runOnIdle { openWorkshop() }
        awaitWorkshop()
        click(tag, "wallpaper_workshop_hub")
        click("wallpaper_detail_apply")
        awaitClosed()
    }

    private fun startDraft(name: String) {
        click("wallpaper_workshop_new", "wallpaper_workshop_hub")
        reveal("wallpaper_editor_name").performTextReplacement(name)
        click("wallpaper_add_icons")
        click("wallpaper_category_doodles", "wallpaper_icon_categories")
        click("wallpaper_icon_builtin_four-point-spark", "wallpaper_icon_grid")
        click("wallpaper_icons_done")
        click("wallpaper_mix_builtin_four-point-spark")
        reveal("wallpaper_icon_lead").assertIsSelected()
        click("wallpaper_icon_filler")
        reveal("wallpaper_icon_filler").assertIsSelected()
        reveal("wallpaper_icon_size_auto").assertIsSelected()
        node("wallpaper_icon_size").assertDoesNotExist()
        click("wallpaper_icon_size_fixed")
        setProgress("wallpaper_icon_size", 250f)
        click("wallpaper_icon_size_auto")
        val sizedIcons = requireNotNull(state.draft).pattern.icons
        assertNull(sizedIcons.single().scale)
        node("wallpaper_icon_size").assertDoesNotExist()
        click("wallpaper_icon_size_fixed")
        reveal("wallpaper_icon_rotation_auto").assertIsSelected()
        node("wallpaper_icon_rotation").assertDoesNotExist()
        click("wallpaper_icon_rotation_fixed")
        val fixedIcons = requireNotNull(state.draft).pattern.icons
        assertEquals(0, fixedIcons.single().rotation)
        setProgress("wallpaper_icon_rotation", -35f)
        click("wallpaper_icon_rotation_auto")
        val automaticIcons = requireNotNull(state.draft).pattern.icons
        assertNull(automaticIcons.single().rotation)
        click("wallpaper_icon_rotation_fixed")
        click("wallpaper_icon_done")
        click("wallpaper_tab_pattern")
        node("wallpaper_density").assertIsDisplayed()
        setProgress("wallpaper_density", 15f)
        assertEquals(15, requireNotNull(state.draft).pattern.density)
        setProgress("wallpaper_density", 95f)
        assertEquals(95, requireNotNull(state.draft).pattern.density)
        click("wallpaper_layout_waves")
        node("wallpaper_layout_rings").assertDoesNotExist()
        setProgress("wallpaper_size", 25f)
        click("wallpaper_tab_color")
        setProgress("wallpaper_intensity", 0f)
        click("wallpaper_background_custom")
        reveal("settings_wallpaper_color_hex").performTextReplacement("#FFFFFF")
        click("settings_wallpaper_color_apply")
        val pattern = requireNotNull(state.draft).pattern
        assertEquals(WallpaperPatternLayout.WAVES, pattern.layout)
        assertEquals(25, pattern.size)
        assertEquals(0, pattern.intensity)
        assertEquals(WHITE, pattern.background)
        assertEquals(SPARK, pattern.icons.single().assetId)
        assertEquals(250, pattern.icons.single().scale)
        assertEquals(-35, pattern.icons.single().rotation)
        assertTrue(pattern.icons.single().filler)
    }

    private fun pickDocument(uri: Uri?) {
        click("wallpaper_import_svg")
        compose.runOnIdle {
            val request = requireNotNull(pickerRequest)
            pickerRequest = null
            picker.dispatchResult(request, uri)
            assertEquals("The picker should start intake only for a picked URI", uri != null, state.picker?.importing)
        }
    }

    private fun awaitSvgResult() = awaitState { it?.picker?.importing == false }

    private fun awaitState(predicate: (WallpaperWorkshopUiState?) -> Boolean) {
        compose.waitUntil(10_000) { compose.runOnIdle { predicate(editor?.uiState?.value) } }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun reveal(
        tag: String,
        lazyContainer: String? = null,
    ): SemanticsNodeInteraction {
        val target = node(tag)
        if (!target.isDisplayed()) {
            if (lazyContainer != null) {
                node(lazyContainer).performScrollToNode(hasTestTag(tag))
            } else {
                target.performScrollTo()
            }
        }
        return target.assertIsDisplayed()
    }

    private fun click(
        tag: String,
        lazyContainer: String? = null,
    ) {
        reveal(tag, lazyContainer).performClick()
    }

    private fun setProgress(
        tag: String,
        value: Float,
    ) {
        reveal(tag).performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(value)) }
    }

    private fun SemanticsNodeInteraction.pixels(): IntArray {
        val bitmap = captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    private fun current() = runBlocking { prefs.config.first() }

    private fun choice(id: Long) =
        runBlocking {
            decodeWallpaperChatChoice(checkNotNull(db.bufferDao().observeById(id)).wallpaperOverrideEncoded)
        }

    private fun effective(id: Long) = resolveWallpaper(current(), choice(id))

    private companion object {
        const val DESIGN_NAME = "Star trail over waves"
        const val SPARK = "builtin:four-point-spark"
        const val WHITE = -1
        val BUBBLE_COLOR = 0xff78d7ed.toInt()
        const val SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 9 A7 7 0 0 1 17 9"/><line x1="6" y1="13" x2="17" y2="18"/></svg>"""
    }
}
