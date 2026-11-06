package io.github.trevarj.motd.ui.splash

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.UiDispatcherResetRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplashLogoUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun sparkCutOpensTheActualApertureAndSettlesToTheOriginal() {
        val progress = mutableFloatStateOf(0f)
        compose.setContent {
            Box(Modifier.size(180.dp).background(androidx.compose.ui.graphics.Color.Black)) {
                SplashMarkArtwork(progress.floatValue, 180.dp, Modifier.testTag("splash_logo"))
            }
        }

        fun capture(at: Float): Bitmap {
            compose.runOnIdle { progress.floatValue = at }
            return compose.onNodeWithTag("splash_logo").captureToImage().asAndroidBitmap()
        }
        val intact = capture(0f)
        val cutting = capture(0.5f)
        val settled = capture(0.95f)
        val original = capture(1f)
        val proofs = File("build/reports/spark-cut").apply { mkdirs() }
        listOf("intact" to intact, "cutting" to cutting, "settled" to settled, "original" to original).forEach { (name, bitmap) ->
            File(proofs, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertFalse("The cut must visibly change the intact ceramic", intact.sameAs(cutting))
        assertTrue("The final still is the unchanged original raster", settled.sameAs(original))
        assertEquals(Color.BLACK, original.getPixel(original.width / 2, original.height / 2))
        assertTrue(Color.red(intact.getPixel(intact.width / 2, intact.height / 2)) > 180)
        // A substantial open region already exists behind the moving contact before completion.
        val opened =
            (0 until cutting.width).sumOf { x ->
                (0 until cutting.height).count { y ->
                    Color.red(intact.getPixel(x, y)) > 180 && cutting.getPixel(x, y) == Color.BLACK
                }
            }
        assertTrue("The hole must open progressively, not appear only at rest", opened > 40)
        // The former incoming ray entered the upper-right exterior. It must remain empty.
        for (image in listOf(intact, cutting, settled)) {
            assertEquals(Color.BLACK, image.getPixel(image.width * 9 / 10, image.height / 10))
            val before = Color.red(original.getPixel(image.width / 4, image.height / 2))
            val after = Color.red(image.getPixel(image.width / 4, image.height / 2))
            // Density-selected originals and the full-resolution intact face can round by one tone.
            assertTrue("Untouched outer ceramic retains its shading", kotlin.math.abs(before - after) <= 2)
        }
    }
}
