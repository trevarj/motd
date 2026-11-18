package io.github.trevarj.motd.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertTrue
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
class DaySeparatorUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun dateAndUnreadHaveVisibleCapsulesAndReadableInkInLightDarkAndTrueBlack() {
        val mode = mutableStateOf("light")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MotdTheme(
                    themePreset = if (mode.value == "light") ColorThemePreset.LIGHT else ColorThemePreset.DARK,
                    trueBlack = mode.value == "true-black",
                    dynamicColor = false,
                    syncSystemBars = false,
                ) {
                    Column(
                        Modifier
                            .width(360.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(vertical = 16.dp)
                            .testTag("separators"),
                    ) {
                        DaySeparator(label = "November 18, 2026")
                        NewMessagesDivider(label = "New messages")
                    }
                }
            }
        }
        for (name in listOf("light", "dark", "true-black")) {
            compose.runOnIdle { mode.value = name }
            val root = compose.onNodeWithTag("separators")
            val rootBounds = root.fetchSemanticsNode().boundsInRoot
            val image = root.captureToImage().asAndroidBitmap()
            val module = if (File("src/main").isDirectory) File(".") else File("app")
            val png = File(module, "build/reports/palettes/separator-pills-$name.png").apply { parentFile!!.mkdirs() }
            png.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val labels = listOf("November 18, 2026", "New messages")
            val centers =
                labels.map { label ->
                    compose
                        .onNodeWithText(label)
                        .assertIsDisplayed()
                        .fetchSemanticsNode()
                        .boundsInRoot.center.y - rootBounds.top
                }
            val split = ((centers[0] + centers[1]) / 2).toInt()
            for ((index, label) in labels.withIndex()) {
                val top = if (index == 0) 0 else split
                val bottom = if (index == 0) split else image.height
                val band = Bitmap.createBitmap(image, 0, top, image.width, bottom - top)
                assertReadableCapsule("$name $label", band, image.getPixel(0, 0))
            }
        }
    }

    private fun assertReadableCapsule(
        label: String,
        image: Bitmap,
        canvas: Int,
    ) {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val fill =
            pixels
                .filter { it != canvas }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
        requireNotNull(fill) { "$label has no visible pixels" }
        assertTrue("$label background must stand out from the canvas", ColorUtils.calculateContrast(fill.key, canvas) > 1.1)

        val filled = pixels.indices.filter { pixels[it] == fill.key }
        val left = filled.minOf { it % image.width }
        val right = filled.maxOf { it % image.width }
        val top = filled.minOf { it / image.width }
        val bottom = filled.maxOf { it / image.width }
        val height = bottom - top + 1
        assertTrue("$label needs a substantial filled background", fill.value > (right - left + 1) * height / 2)
        val centerY = (top + bottom) / 2
        val shoulderY = top + height / 8
        val shoulderLeft = (left..right).first { image.getPixel(it, shoulderY) == fill.key }
        assertTrue("$label needs rounded capsule shoulders", shoulderLeft - left >= height / 12)
        assertTrue("$label capsule must extend to its sides at mid-height", image.getPixel(left + 1, centerY) == fill.key)
        assertTrue("$label rounded corner must expose the canvas", image.getPixel(left, top) == canvas)
        val inkPixels =
            (top + height / 4..bottom - height / 4).sumOf { y ->
                (left + height / 2..right - height / 2).count { x ->
                    ColorUtils.calculateContrast(image.getPixel(x, y), fill.key) >= 4.5
                }
            }
        assertTrue("$label needs actual readable rendered ink", inkPixels >= 20)
    }
}
