package io.github.trevarj.motd.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.fonts.CustomFontStore
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AppearancePrefsImpl
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.CustomWallpaperStore
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.ui.settings.AppearanceSettingsViewModel
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ChatWallpaperPickerUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun presetAndIntensitySaveImmediatelyAndKeepEditorOpen() {
        var current by mutableStateOf(WallpaperSelection(ChatWallpaperPreset.MOTD, 50))
        val changes = mutableListOf<WallpaperSelection>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatWallpaperPicker(current = current, onChange = {
                    changes += it
                    // An older preference emission must not replace the user's active edit.
                    current = WallpaperSelection(ChatWallpaperPreset.NONE, 30)
                })
            }
        }

        compose.onNodeWithTag("settings_wallpaper_picker").performClick()
        compose.onNodeWithTag("settings_wallpaper_sheet").assertIsDisplayed()
        reveal("settings_wallpaper_preset_deep_space")
        compose.onNodeWithTag("settings_wallpaper_preset_deep_space").performClick()
        assertEquals(WallpaperSelection(ChatWallpaperPreset.DEEP_SPACE, 50), changes.last())
        compose.onNodeWithTag("settings_wallpaper_sheet").assertIsDisplayed()

        reveal("settings_wallpaper_intensity")
        compose.onNodeWithTag("settings_wallpaper_intensity").performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
        assertEquals(WallpaperSelection(ChatWallpaperPreset.DEEP_SPACE, 80), changes.last())
        compose.onNodeWithTag("settings_wallpaper_sheet").assertIsDisplayed()

        reveal("settings_wallpaper_done")
        compose.onNodeWithTag("settings_wallpaper_done").performClick()
        compose.onNodeWithTag("settings_wallpaper_sheet").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h480dp")
    fun compactEditorKeepsSheetAndScrollPositionStableWhileAllControlsRemainReachable() {
        var current by mutableStateOf(WallpaperSelection())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MotdTheme(dynamicColor = false) {
                    ChatWallpaperPicker(current, onChange = { current = it })
                }
            }
        }
        compose.onNodeWithTag("settings_wallpaper_picker").performClick()
        reveal("settings_wallpaper_preset_memes")
        val list = compose.onNodeWithTag("settings_wallpaper_list")
        val sheet = compose.onNodeWithTag("settings_wallpaper_sheet")
        val sheetBefore = sheet.fetchSemanticsNode().boundsInRoot
        val scrollBefore = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithTag("settings_wallpaper_preset_memes").performClick().assertIsSelected()
        assertEquals(sheetBefore, sheet.fetchSemanticsNode().boundsInRoot)
        assertEquals(scrollBefore, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.01f)
        reveal("settings_wallpaper_intensity")
        val sliderBefore = compose.onNodeWithTag("settings_wallpaper_intensity").fetchSemanticsNode().boundsInRoot
        val sliderScroll = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithTag("settings_wallpaper_intensity").performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
        assertEquals(WallpaperSelection(ChatWallpaperPreset.MEMES, 80), current)
        assertEquals(sheetBefore, sheet.fetchSemanticsNode().boundsInRoot)
        assertEquals(sliderBefore, compose.onNodeWithTag("settings_wallpaper_intensity").fetchSemanticsNode().boundsInRoot)
        assertEquals(sliderScroll, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.01f)
        reveal("settings_wallpaper_done")
        compose.onNodeWithTag("settings_wallpaper_done").assertIsDisplayed().performClick()
        sheet.assertDoesNotExist()
    }

    @Test
    fun nativeImportChangeCancelFailureBuiltinAndRemoveRenderTheRealLocalImage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppearancePrefsImpl(context)
        val store = CustomWallpaperStore(context)
        val directory = File(context.filesDir, CustomWallpaperStore.DIRECTORY)
        directory.deleteRecursively()
        runBlocking { prefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.NONE, 100)) }
        val vm = AppearanceSettingsViewModel(DataStoreSettingsRepository(context), prefs, CustomFontStore(context), store)
        val originalLoader = Coil.imageLoader(context)
        val loaded = CopyOnWriteArrayList<File>()
        val loader =
            ImageLoader
                .Builder(context)
                .allowHardware(false)
                .eventListener(
                    object : EventListener {
                        override fun onSuccess(
                            request: ImageRequest,
                            result: SuccessResult,
                        ) {
                            (request.data as? File)?.let(loaded::add)
                        }
                    },
                ).build()
        Coil.setImageLoader(loader)
        var pickerRequestCode = 0
        val registry =
            object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    pickerRequestCode = requestCode
                }
            }
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
        var imported by mutableStateOf<WallpaperSelection?>(null)
        var failures = 0
        try {
            compose.setContent {
                val config by prefs.config.collectAsState(initial = AppearanceConfig(wallpaper = WallpaperSelection(ChatWallpaperPreset.NONE, 100)))
                val importing by vm.wallpaperImporting.collectAsState()
                LaunchedEffect(vm) { vm.importedWallpaper.collect { imported = it } }
                LaunchedEffect(vm) { vm.wallpaperFailures.collect { failures++ } }
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MotdTheme(dynamicColor = false, themePreset = ColorThemePreset.DARK, trueBlack = true) {
                        ChatWallpaperPicker(config.wallpaper, vm::setWallpaper, onImportImage = vm::importWallpaper, importedSelection = imported, importing = importing)
                    }
                }
            }
            compose.onNodeWithTag("settings_wallpaper_picker").performClick()
            val source = coloredPanorama(context, "selected.png", 0xff28c864.toInt())

            fun pick(uri: Uri?) {
                reveal("settings_wallpaper_custom")
                compose.onNodeWithTag("settings_wallpaper_custom").performClick()
                compose.runOnIdle { registry.dispatchResult(pickerRequestCode, uri) }
            }
            pick(Uri.fromFile(source))
            compose.waitUntil(10_000) { compose.runOnIdle { imported != null && loaded.any { it.name == imported?.localImageName } } }
            val first = requireNotNull(imported)
            val firstBytes = requireNotNull(CustomWallpaperStore.resolve(context, first.localImageName)).readBytes()
            reveal("settings_wallpaper_preview")
            assertPreviewColor(0xff28c864.toInt())
            // A wide source crops its red/blue edges away rather than fitting or tiling it.
            val preview = compose.onNodeWithTag("settings_wallpaper_preview").captureToImage().asAndroidBitmap()
            assertEquals(0xff28c864.toInt(), preview.getPixel(preview.width / 6, preview.height / 12))
            pick(null)
            assertEquals(first, runBlocking { prefs.config.first().wallpaper })
            val invalid = File(context.cacheDir, "invalid-wallpaper.png").apply { writeText("not an image") }
            pick(Uri.fromFile(invalid))
            compose.waitUntil(10_000) { compose.runOnIdle { failures == 1 } }
            assertEquals(first, runBlocking { prefs.config.first().wallpaper })
            assertArrayEquals(firstBytes, requireNotNull(CustomWallpaperStore.resolve(context, first.localImageName)).readBytes())
            reveal("settings_wallpaper_preview")
            assertPreviewColor(0xff28c864.toInt())
            reveal("settings_wallpaper_intensity")
            compose.onNodeWithTag("settings_wallpaper_intensity").performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
            reveal("settings_wallpaper_preview")
            assertPreviewColor(0xff146432.toInt(), tolerance = 2)
            val changed = coloredPanorama(context, "changed.png", 0xffc8329a.toInt())
            pick(Uri.fromFile(changed))
            compose.waitUntil(10_000) { compose.runOnIdle { imported?.localImageName != first.localImageName && loaded.any { it.name == imported?.localImageName } } }
            assertEquals(50, imported?.intensity)
            assertNull(CustomWallpaperStore.resolve(context, first.localImageName))
            reveal("settings_wallpaper_preview")
            assertPreviewColor(0xff64194d.toInt(), tolerance = 2)
            reveal("settings_wallpaper_preset_deep_space")
            compose.onNodeWithTag("settings_wallpaper_preset_deep_space").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { compose.runOnIdle { directory.listFiles().orEmpty().isEmpty() } }
            assertEquals(WallpaperSelection(ChatWallpaperPreset.DEEP_SPACE, 50), runBlocking { prefs.config.first().wallpaper })
            pick(Uri.fromFile(source))
            compose.waitUntil(10_000) { compose.runOnIdle { imported?.preset == ChatWallpaperPreset.DEEP_SPACE && directory.listFiles().orEmpty().size == 1 } }
            reveal("settings_wallpaper_remove")
            compose.onNodeWithTag("settings_wallpaper_remove").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { compose.runOnIdle { directory.listFiles().orEmpty().isEmpty() } }
            assertEquals(WallpaperSelection(ChatWallpaperPreset.DEEP_SPACE, 50), runBlocking { prefs.config.first().wallpaper })
            compose.onNodeWithTag("settings_wallpaper_remove").assertIsNotEnabled()
            reveal("settings_wallpaper_done")
            compose.onNodeWithTag("settings_wallpaper_done").performClick()
            compose.onNodeWithTag("settings_wallpaper_picker").performClick()
            reveal("settings_wallpaper_preset_deep_space")
            compose.onNodeWithTag("settings_wallpaper_preset_deep_space").assertIsSelected()
        } finally {
            vm.viewModelScope.cancel()
            Coil.setImageLoader(originalLoader)
            loader.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingCorruptAndUnsafeLocalReferencesRenderTheRetainedBuiltinFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, CustomWallpaperStore.DIRECTORY).apply { mkdirs() }
        val corrupt = File(directory, "${UUID.randomUUID()}.image").apply { writeText("not an image") }
        var selection by mutableStateOf(WallpaperSelection(ChatWallpaperPreset.MOTD, 100, "${UUID.randomUUID()}.image"))
        val errors = CopyOnWriteArrayList<File>()
        val requested = CopyOnWriteArrayList<Any>()
        val originalLoader = Coil.imageLoader(context)
        val loader =
            ImageLoader
                .Builder(context)
                .eventListener(
                    object : EventListener {
                        override fun onStart(request: ImageRequest) {
                            requested += request.data
                        }

                        override fun onError(
                            request: ImageRequest,
                            result: ErrorResult,
                        ) {
                            (request.data as? File)?.let(errors::add)
                        }
                    },
                ).build()
        Coil.setImageLoader(loader)
        try {
            compose.setContent {
                MotdTheme(dynamicColor = false, themePreset = ColorThemePreset.DARK, trueBlack = true) {
                    Column {
                        Box(Modifier.size(200.dp, 100.dp).testTag("fallback")) {
                            ChatWallpaperBackground(selection, Modifier.matchParentSize())
                        }
                        Box(Modifier.size(200.dp, 100.dp).testTag("builtin")) {
                            ChatWallpaperBackground(selection.copy(localImageName = null), Modifier.matchParentSize())
                        }
                    }
                }
            }

            fun assertBuiltin() {
                val image = compose.onNodeWithTag("fallback").captureToImage().asAndroidBitmap()
                val builtin = compose.onNodeWithTag("builtin").captureToImage().asAndroidBitmap()
                assertTrue("Local failures must render the retained builtin, not an empty or external image", builtin.sameAs(image))
            }
            compose.waitUntil(10_000) {
                val image = compose.onNodeWithTag("builtin").captureToImage().asAndroidBitmap()
                (0 until image.width).any { x -> (0 until image.height).any { y -> image.getPixel(x, y) != android.graphics.Color.BLACK } }
            }
            assertBuiltin()
            compose.runOnIdle { selection = selection.copy(localImageName = corrupt.name) }
            compose.waitUntil(10_000) { compose.runOnIdle { errors.contains(corrupt) } }
            assertBuiltin()
            for (unsafe in listOf("../${corrupt.name}", corrupt.path, "content://external/photo", "https://example.com/photo.png")) {
                compose.runOnIdle { selection = selection.copy(localImageName = unsafe) }
                assertBuiltin()
            }
            assertEquals(listOf(corrupt), errors.toList())
            assertEquals("Stored paths and URIs must never reach an image loader", listOf(corrupt), requested.toList())
        } finally {
            Coil.setImageLoader(originalLoader)
            loader.shutdown()
            corrupt.delete()
        }
    }

    private fun reveal(tag: String) {
        compose.onNodeWithTag("settings_wallpaper_list").performScrollToNode(hasTestTag(tag))
    }

    private fun assertPreviewColor(
        expected: Int,
        tolerance: Int = 0,
    ) {
        val bitmap = compose.onNodeWithTag("settings_wallpaper_preview").captureToImage().asAndroidBitmap()
        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 12)
        for (shift in listOf(0, 8, 16, 24)) {
            assertEquals((expected ushr shift and 255).toFloat(), (pixel ushr shift and 255).toFloat(), tolerance.toFloat())
        }
    }

    private fun coloredPanorama(
        context: Context,
        name: String,
        center: Int,
    ): File {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(600, 100, Bitmap.Config.ARGB_8888)
        for (x in 0 until bitmap.width) {
            val color =
                when (x) {
                    in 0..199 -> android.graphics.Color.RED
                    in 200..399 -> center
                    else -> android.graphics.Color.BLUE
                }
            for (y in 0 until bitmap.height) bitmap.setPixel(x, y, color)
        }
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}
