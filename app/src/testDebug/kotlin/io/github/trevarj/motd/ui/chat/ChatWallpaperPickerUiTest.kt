package io.github.trevarj.motd.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
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
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.emoji.EmojiSetStore
import io.github.trevarj.motd.data.fonts.CustomFontStore
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AppearancePrefsImpl
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.CustomWallpaperStore
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.data.prefs.WallpaperWorkshopStore
import io.github.trevarj.motd.data.prefs.resolveWallpaper
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
    fun scenicPresetAndIntensitySaveImmediatelyAndKeepPickerOpen() {
        var current by mutableStateOf(WallpaperSelection(ChatWallpaperPreset.PATTERN, 50, scenicAnimationEnabled = false))
        val changes = mutableListOf<WallpaperSelection>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatWallpaperPicker(
                    appearance = AppearanceConfig(wallpaper = current),
                    onChange = {
                        changes += it
                        // An older preference emission must not replace the user's active edit.
                        current = WallpaperSelection(ChatWallpaperPreset.NONE, 30)
                    },
                    onOpenWallpaperWorkshop = {},
                )
            }
        }

        compose.onNodeWithTag("settings_wallpaper_picker").performClick()
        compose.onNodeWithTag("settings_wallpaper_sheet").assertIsDisplayed()
        reveal("settings_wallpaper_preset_rainy_cafe")
        compose.onNodeWithTag("settings_wallpaper_preset_rainy_cafe").performClick()
        assertEquals(WallpaperSelection(ChatWallpaperPreset.RAINY_CAFE, 50, scenicAnimationEnabled = false), changes.last())
        compose.onNodeWithTag("settings_wallpaper_sheet").assertIsDisplayed()

        reveal("settings_wallpaper_intensity")
        compose.onNodeWithTag("settings_wallpaper_intensity").performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
        assertEquals(WallpaperSelection(ChatWallpaperPreset.RAINY_CAFE, 80, scenicAnimationEnabled = false), changes.last())
        compose.onNodeWithTag("settings_wallpaper_sheet").assertIsDisplayed()

        reveal("settings_wallpaper_preset_none")
        compose.onNodeWithTag("settings_wallpaper_preset_none").performClick().assertIsSelected()
        assertEquals(WallpaperSelection(ChatWallpaperPreset.NONE, 80, scenicAnimationEnabled = false), changes.last())
        compose.onNodeWithTag("settings_wallpaper_intensity").assertDoesNotExist()

        reveal("settings_wallpaper_done")
        compose.onNodeWithTag("settings_wallpaper_done").performClick()
        compose.onNodeWithTag("settings_wallpaper_sheet").assertDoesNotExist()
    }

    @Test
    fun scenicSelectionShowsOnlyScenicControlsAndPersistsBothChoices() {
        var current by mutableStateOf(WallpaperSelection())
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatWallpaperPicker(AppearanceConfig(wallpaper = current), onChange = { current = it }, onOpenWallpaperWorkshop = {})
            }
        }

        compose.onNodeWithTag("settings_wallpaper_picker").performClick()
        reveal("settings_wallpaper_preset_japanese_countryside")
        compose.onNodeWithTag("settings_wallpaper_preset_japanese_countryside").performClick().assertIsSelected()
        assertEquals(ChatWallpaperPreset.JAPANESE_COUNTRYSIDE, current.preset)
        reveal("settings_wallpaper_scenic_follow_system")
        compose
            .onNodeWithTag("settings_wallpaper_scenic_follow_system")
            .assertIsOn()
            .performClick()
            .assertIsOff()
        assertEquals(false, current.scenicFollowSystem)
        reveal("settings_wallpaper_scenic_animation")
        compose
            .onNodeWithTag("settings_wallpaper_scenic_animation")
            .assertIsOn()
            .performClick()
            .assertIsOff()
        assertEquals(false, current.scenicAnimationEnabled)
        compose.onNodeWithTag("settings_wallpaper_outline_color").assertDoesNotExist()
    }

    @Test
    fun colorDialogValidatesRestoresAppliesAndCancelsDraftWithoutPrematureWrites() {
        var current by mutableStateOf<Int?>(null)
        val changes = mutableListOf<Int?>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MotdTheme(dynamicColor = false) {
                var open by rememberSaveable { mutableStateOf(true) }
                Button(onClick = { open = true }, modifier = Modifier.testTag("open_color")) {
                    Text("Default ink")
                }
                if (open) {
                    WallpaperColorDialog(
                        label = "Default ink",
                        defaultLabel = "Theme",
                        defaultColor = android.graphics.Color.BLACK,
                        value = current,
                        onApply = {
                            current = it
                            changes += it
                            open = false
                        },
                        onDismiss = { open = false },
                        preview = { color, modifier -> Box(modifier.background(color?.let(::Color) ?: Color.Black)) },
                    )
                }
            }
        }
        val hex = compose.onNodeWithTag("settings_wallpaper_color_hex")
        val apply = compose.onNodeWithTag("settings_wallpaper_color_apply")
        val cancel = compose.onNodeWithTag("settings_wallpaper_color_cancel")
        val theme = compose.onNodeWithTag("settings_wallpaper_color_default")
        val preview = compose.onNodeWithTag("settings_wallpaper_color_preview")
        val open = compose.onNodeWithTag("open_color")
        hex.performScrollTo().performTextReplacement("#12GG34")
        apply.assertIsNotEnabled()
        assertTrue(changes.isEmpty())
        theme.performScrollTo().performClick()
        apply.assertIsEnabled()
        cancel.performClick()
        assertTrue(changes.isEmpty())

        open.performClick()
        hex.performScrollTo().performTextReplacement("#123ABC")
        val image = preview.performScrollTo().captureToImage().asAndroidBitmap()
        assertEquals(0xff123abc.toInt(), image.getPixel(image.width / 2, image.height / 2))
        restoration.emulateSavedInstanceStateRestore()
        hex.performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("#123ABC")))
        assertTrue(changes.isEmpty())
        apply.performClick()
        assertEquals(0xff123abc.toInt(), changes.single())

        open.performClick()
        theme.performScrollTo().performClick()
        cancel.performClick()
        assertEquals(0xff123abc.toInt(), current)
        open.performClick()
        hex.performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("#123ABC")))
        theme.performScrollTo().performClick()
        apply.performClick()
        assertNull(current)
        assertEquals(2, changes.size)
    }

    @Test
    fun blackInkKeepsWheelSpectrumVisibleAndRecoversColorWithBrightness() {
        var applied: Int? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                WallpaperColorDialog(
                    label = "Default ink",
                    defaultLabel = "Theme",
                    defaultColor = android.graphics.Color.WHITE,
                    value = android.graphics.Color.BLACK,
                    onApply = { applied = it },
                    onDismiss = {},
                    preview = { color, modifier -> Box(modifier.background(Color(color ?: android.graphics.Color.WHITE))) },
                )
            }
        }

        val wheel = compose.onNodeWithTag("settings_wallpaper_color_wheel").performScrollTo()
        val image = wheel.captureToImage().asAndroidBitmap()
        val radius = minOf(image.width, image.height) / 2f
        val samples =
            listOf(
                Triple(0.8f, 0f, 16),
                Triple(-0.4f, 0.693f, 8),
                Triple(-0.4f, -0.693f, 0),
            )
        for ((x, y, channel) in samples) {
            val pixel = image.getPixel((image.width / 2f + x * radius).toInt(), (image.height / 2f + y * radius).toInt())
            val dominant = pixel ushr channel and 255
            assertTrue("Wheel channel $channel must remain bright at zero selected brightness", dominant > 220)
            for (other in listOf(0, 8, 16).filter { it != channel }) {
                assertTrue("Wheel must show distinct red, green and blue regions", dominant - (pixel ushr other and 255) > 100)
            }
        }
        assertEquals(
            0f,
            compose
                .onNodeWithTag("settings_wallpaper_color_brightness")
                .fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo]
                .current,
        )
        wheel.performTouchInput { click(Offset(center.x + minOf(width, height) / 2f, center.y)) }
        compose
            .onNodeWithTag("settings_wallpaper_color_hex")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("#000000")))
        assertNull(applied)
        setColorComponent("brightness", 1f)
        compose
            .onNodeWithTag("settings_wallpaper_color_hex")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("#FF0000")))
        compose.onNodeWithTag("settings_wallpaper_color_apply").performClick()
        assertEquals(android.graphics.Color.RED, applied)
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun compactLargeFontColorEditorKeepsAccessibleControlsAndActionsReachable() {
        var current: Int? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MotdTheme(dynamicColor = false) {
                    WallpaperColorDialog(
                        label = "Default ink",
                        defaultLabel = "Theme",
                        defaultColor = android.graphics.Color.WHITE,
                        value = null,
                        onApply = { current = it },
                        onDismiss = {},
                        preview = { color, modifier -> Box(modifier.background(Color(color ?: android.graphics.Color.WHITE))) },
                    )
                }
            }
        }
        setColorComponent("hue", 1f)
        setColorComponent("saturation", 0.01f)
        compose.onNodeWithTag("settings_wallpaper_color_hue").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 degree"))
        compose.onNodeWithTag("settings_wallpaper_color_saturation").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1%"))
        compose.onNodeWithTag("settings_wallpaper_color_wheel").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 degree, 1%"))
        for ((component, value, state) in listOf(Triple("hue", 240f, "240 degrees"), Triple("saturation", 1f, "100%"), Triple("brightness", 1f, "100%"))) {
            val node = compose.onNodeWithTag("settings_wallpaper_color_$component").performScrollTo().assertIsDisplayed()
            val semantics = node.fetchSemanticsNode().config
            assertTrue(semantics[SemanticsProperties.ContentDescription].single().isNotBlank())
            node.performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
            node.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state))
        }
        compose.onNodeWithTag("settings_wallpaper_color_wheel").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "240 degrees, 100%"))
        compose
            .onNodeWithTag("settings_wallpaper_color_hex")
            .performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("#0000FF")))
        compose.onNodeWithTag("settings_wallpaper_color_default").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_wallpaper_color_cancel").assertIsDisplayed()
        compose.onNodeWithTag("settings_wallpaper_color_apply").assertIsDisplayed().performClick()
        assertEquals(0xff0000ff.toInt(), current)
    }

    @Test
    @Config(qualifiers = "w411dp-h480dp")
    fun compactPickerKeepsSheetAndScrollPositionStableWhileAllControlsRemainReachable() {
        var current by mutableStateOf(WallpaperSelection(ChatWallpaperPreset.JAPANESE_COUNTRYSIDE, scenicAnimationEnabled = false))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MotdTheme(dynamicColor = false) {
                    ChatWallpaperPicker(AppearanceConfig(wallpaper = current), onChange = { current = it }, onOpenWallpaperWorkshop = {})
                }
            }
        }
        compose.onNodeWithTag("settings_wallpaper_picker").performClick()
        for (preset in ChatWallpaperPreset.entries.filter(ChatWallpaperPreset::isScenic)) {
            val tag = "settings_wallpaper_preset_${preset.name.lowercase()}"
            reveal(tag)
            compose.onNodeWithTag("${tag}_thumbnail", useUnmergedTree = true).assertIsDisplayed()
        }
        reveal("settings_wallpaper_preset_rainy_cafe")
        val list = compose.onNodeWithTag("settings_wallpaper_list")
        val sheet = compose.onNodeWithTag("settings_wallpaper_sheet")
        val sheetBefore = sheet.fetchSemanticsNode().boundsInRoot
        val scrollBefore = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithTag("settings_wallpaper_preset_rainy_cafe").performClick().assertIsSelected()
        assertEquals(sheetBefore, sheet.fetchSemanticsNode().boundsInRoot)
        assertEquals(scrollBefore, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.01f)
        reveal("settings_wallpaper_intensity")
        val sliderBefore = compose.onNodeWithTag("settings_wallpaper_intensity").fetchSemanticsNode().boundsInRoot
        val sliderScroll = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithTag("settings_wallpaper_intensity").performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
        assertEquals(WallpaperSelection(ChatWallpaperPreset.RAINY_CAFE, 80, scenicAnimationEnabled = false), current)
        assertEquals(sheetBefore, sheet.fetchSemanticsNode().boundsInRoot)
        assertEquals(sliderBefore, compose.onNodeWithTag("settings_wallpaper_intensity").fetchSemanticsNode().boundsInRoot)
        assertEquals(sliderScroll, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.01f)
        reveal("settings_wallpaper_done")
        compose.onNodeWithTag("settings_wallpaper_done").assertIsDisplayed().performClick()
        sheet.assertDoesNotExist()
    }

    @Test
    fun nativeImportChangeCancelFailureScenicAndRemoveRenderTheRealLocalImage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppearancePrefsImpl(context)
        val store = CustomWallpaperStore(context)
        val directory = File(context.filesDir, CustomWallpaperStore.DIRECTORY)
        directory.deleteRecursively()
        runBlocking { prefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.NONE, 100, outlineColor = 0xffff0000.toInt())) }
        val vm =
            AppearanceSettingsViewModel(
                DataStoreSettingsRepository(context),
                prefs,
                CustomFontStore(context),
                WallpaperWorkshopStore(context, prefs, store),
                EmojiSetStore(context),
            )
        val originalLoader = Coil.imageLoader(context)
        val loader = ImageLoader.Builder(context).allowHardware(false).build()
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
                val config by prefs.config.collectAsState(initial = AppearanceConfig(wallpaper = WallpaperSelection(ChatWallpaperPreset.NONE, 100, outlineColor = 0xffff0000.toInt())))
                val importing by vm.wallpaperImporting.collectAsState()
                LaunchedEffect(vm) { vm.importedWallpaper.collect { imported = it } }
                LaunchedEffect(vm) { vm.wallpaperFailures.collect { failures++ } }
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MotdTheme(dynamicColor = false, themePreset = ColorThemePreset.DARK, trueBlack = true) {
                        ChatWallpaperPicker(
                            config,
                            vm::setWallpaper,
                            onOpenWallpaperWorkshop = {},
                            onImportImage = vm::importWallpaper,
                            importedSelection = imported,
                            importing = importing,
                        )
                    }
                }
            }
            compose.onNodeWithTag("settings_wallpaper_picker").performClick()
            val thumbnail = "settings_wallpaper_photo_thumbnail"
            val thumbnailNode = compose.onNodeWithTag(thumbnail, useUnmergedTree = true)
            reveal("settings_wallpaper_custom")
            thumbnailNode.assertDoesNotExist()
            val source = coloredPanorama(context, "selected.png", 0xff28c864.toInt())

            fun pick(uri: Uri?) {
                reveal("settings_wallpaper_custom")
                compose.onNodeWithTag("settings_wallpaper_custom").performClick()
                compose.runOnIdle { registry.dispatchResult(pickerRequestCode, uri) }
            }
            pick(Uri.fromFile(source))
            compose.waitUntil(10_000) { compose.runOnIdle { imported != null } }
            val first = requireNotNull(imported)
            assertEquals(0xffff0000.toInt(), first.outlineColor)
            compose.onNodeWithTag("settings_wallpaper_outline_color").assertDoesNotExist()
            val firstBytes = requireNotNull(CustomWallpaperStore.resolve(context, first.localImageName)).readBytes()
            reveal("settings_wallpaper_preview")
            assertWallpaperColor(0xff28c864.toInt())
            // A wide source crops its red/blue edges away rather than fitting or tiling it.
            val preview = compose.onNodeWithTag("settings_wallpaper_preview").captureToImage().asAndroidBitmap()
            assertEquals(0xff28c864.toInt(), preview.getPixel(preview.width / 6, preview.height / 12))
            reveal(thumbnail)
            thumbnailNode.assertIsDisplayed()
            assertWallpaperColor(0xff28c864.toInt(), tag = thumbnail)
            val photoThumbnail = thumbnailNode.captureToImage().asAndroidBitmap()
            assertEquals(0xff28c864.toInt(), photoThumbnail.getPixel(photoThumbnail.width / 6, photoThumbnail.height / 2))
            pick(null)
            assertEquals(first, runBlocking { prefs.config.first().wallpaper })
            val invalid = File(context.cacheDir, "invalid-wallpaper.png").apply { writeText("not an image") }
            pick(Uri.fromFile(invalid))
            compose.waitUntil(10_000) { compose.runOnIdle { failures == 1 } }
            assertEquals(first, runBlocking { prefs.config.first().wallpaper })
            assertArrayEquals(firstBytes, requireNotNull(CustomWallpaperStore.resolve(context, first.localImageName)).readBytes())
            reveal("settings_wallpaper_preview")
            assertWallpaperColor(0xff28c864.toInt())
            reveal("settings_wallpaper_intensity")
            compose.onNodeWithTag("settings_wallpaper_intensity").performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
            reveal("settings_wallpaper_preview")
            assertWallpaperColor(0xff146432.toInt(), tolerance = 2)
            val changed = coloredPanorama(context, "changed.png", 0xffc8329a.toInt())
            pick(Uri.fromFile(changed))
            compose.waitUntil(10_000) { compose.runOnIdle { imported?.let { it.localImageName != first.localImageName } == true } }
            assertEquals(50, imported?.intensity)
            assertEquals(0xffff0000.toInt(), imported?.outlineColor)
            assertNull(CustomWallpaperStore.resolve(context, first.localImageName))
            reveal("settings_wallpaper_preview")
            assertWallpaperColor(0xff64194d.toInt(), tolerance = 2)
            reveal(thumbnail)
            assertWallpaperColor(0xff64194d.toInt(), tolerance = 2, tag = thumbnail)
            reveal("settings_wallpaper_preset_rainy_cafe")
            compose.onNodeWithTag("settings_wallpaper_preset_rainy_cafe").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { compose.runOnIdle { directory.listFiles().orEmpty().isEmpty() } }
            reveal("settings_wallpaper_custom")
            thumbnailNode.assertDoesNotExist()
            assertEquals(WallpaperSelection(ChatWallpaperPreset.RAINY_CAFE, 50, outlineColor = 0xffff0000.toInt()), runBlocking { prefs.config.first().wallpaper })
            pick(Uri.fromFile(source))
            compose.waitUntil(10_000) { compose.runOnIdle { imported?.preset == ChatWallpaperPreset.RAINY_CAFE && directory.listFiles().orEmpty().size == 1 } }
            reveal("settings_wallpaper_remove")
            compose.onNodeWithTag("settings_wallpaper_remove").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { compose.runOnIdle { directory.listFiles().orEmpty().isEmpty() } }
            assertEquals(WallpaperSelection(ChatWallpaperPreset.RAINY_CAFE, 50, outlineColor = 0xffff0000.toInt()), runBlocking { prefs.config.first().wallpaper })
            compose.onNodeWithTag("settings_wallpaper_remove").assertIsNotEnabled()
            thumbnailNode.assertDoesNotExist()
            reveal("settings_wallpaper_done")
            compose.onNodeWithTag("settings_wallpaper_done").performClick()
            compose.onNodeWithTag("settings_wallpaper_picker").performClick()
            reveal("settings_wallpaper_preset_rainy_cafe")
            compose.onNodeWithTag("settings_wallpaper_preset_rainy_cafe").assertIsSelected()
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
        var selection by mutableStateOf(WallpaperSelection(ChatWallpaperPreset.PATTERN, 100, "${UUID.randomUUID()}.image", 0xffff0000.toInt()))
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
                            ChatWallpaperBackground(resolveWallpaper(AppearanceConfig(wallpaper = selection)), Modifier.matchParentSize())
                        }
                        Box(Modifier.size(200.dp, 100.dp).testTag("builtin")) {
                            ChatWallpaperBackground(resolveWallpaper(AppearanceConfig(wallpaper = selection.copy(localImageName = null))), Modifier.matchParentSize())
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
                val builtin = compose.onNodeWithTag("builtin").captureToImage().asAndroidBitmap()
                val fallback = compose.onNodeWithTag("fallback").captureToImage().asAndroidBitmap()
                builtin.sameAs(fallback) &&
                    (0 until builtin.width).any { x -> (0 until builtin.height).any { y -> builtin.getPixel(x, y) != android.graphics.Color.BLACK } }
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

    private fun setColorComponent(
        component: String,
        value: Float,
    ) {
        compose.onNodeWithTag("settings_wallpaper_color_$component").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    }

    private fun assertWallpaperColor(
        expected: Int,
        tolerance: Int = 0,
        tag: String = "settings_wallpaper_preview",
    ) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        compose.waitUntil(10_000) {
            val bitmap = node.captureToImage().asAndroidBitmap()
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 12)
            listOf(0, 8, 16, 24).all { shift ->
                kotlin.math.abs((expected ushr shift and 255) - (pixel ushr shift and 255)) <= tolerance
            }
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
