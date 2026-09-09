package io.github.trevarj.motd.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CeramicLogoColorFilterTest {
    @Test
    fun colorMatrix_preservesAlphaAndUsesOppositeCeramicShadingForLightAndDarkTargets() {
        val black = ceramicLogoColorMatrix(Color.BLACK)
        assertEquals(0f, mappedChannel(black, 0, 0f), 0.001f)
        assertEquals(204f, mappedChannel(black, 0, 255f), 0.001f)
        assertEquals(1f, black[18], 0f)
        assertEquals(0f, black[15], 0f)
        assertEquals(0f, black[16], 0f)
        assertEquals(0f, black[17], 0f)
        assertEquals(0f, black[19], 0f)

        val white = ceramicLogoColorMatrix(Color.WHITE)
        assertEquals(255f, mappedChannel(white, 0, 0f), 0.001f)
        assertEquals(51f, mappedChannel(white, 0, 255f), 0.001f)

        val accent = ceramicLogoColorMatrix(Color.rgb(0, 122, 124))
        assertEquals(0f, mappedChannel(accent, 0, 0f), 0.001f)
        assertEquals(122f, mappedChannel(accent, 1, 0f), 0.001f)
        assertEquals(124f, mappedChannel(accent, 2, 0f), 0.001f)
        assertEquals(204f, mappedChannel(accent, 0, 255f), 0.001f)
        assertEquals(228.4f, mappedChannel(accent, 1, 255f), 0.001f)
        assertEquals(228.8f, mappedChannel(accent, 2, 255f), 0.001f)
    }

    @Test
    fun rasterMark_retainsTransparencyAndCeramicShadingAfterThemeRecoloring() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = renderMark(context)
        val black = renderMark(context, Color.BLACK)
        val white = renderMark(context, Color.WHITE)

        assertTrue("mark background must be transparent", source.pixels().any { Color.alpha(it) == 0 })
        assertShaded(source, "source")
        assertShaded(black, "black target")
        assertShaded(white, "white target")
    }

    private fun mappedChannel(
        matrix: FloatArray,
        channel: Int,
        sourceGray: Float,
    ): Float {
        val offset = channel * 5
        return sourceGray * (matrix[offset] + matrix[offset + 1] + matrix[offset + 2]) + matrix[offset + 4]
    }

    private fun renderMark(
        context: Context,
        target: Int? = null,
    ): Bitmap {
        val drawable = requireNotNull(ContextCompat.getDrawable(context, R.drawable.motd_logo_mark)).mutate()
        target?.let { drawable.colorFilter = ColorMatrixColorFilter(ColorMatrix(ceramicLogoColorMatrix(it))) }
        return Bitmap.createBitmap(192, 184, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
        }
    }

    private fun assertShaded(
        bitmap: Bitmap,
        description: String,
    ) {
        val opaqueGrays =
            bitmap
                .pixels()
                .filter { Color.alpha(it) == 255 }
                .map(Color::red)
                .toSet()
        assertTrue("$description mark must contain more than one opaque ceramic tone", opaqueGrays.size > 1)
    }

    private fun Bitmap.pixels(): List<Int> = List(width * height) { index -> getPixel(index % width, index / width) }
}
