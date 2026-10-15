package io.github.trevarj.motd.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27, 30, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplashBrandingTest {
    @Test
    @Config(qualifiers = "notnight")
    fun lightSystem_splashKeepsWhiteCeramicOnBlack() {
        assertSplashBranding()
    }

    @Test
    @Config(qualifiers = "night")
    fun darkSystem_splashKeepsWhiteCeramicOnBlack() {
        assertSplashBranding()
    }

    private fun assertSplashBranding() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val theme = ContextThemeWrapper(context, R.style.Theme_Motd_Splash)
        val attributes =
            theme.obtainStyledAttributes(
                intArrayOf(
                    android.R.attr.statusBarColor,
                    android.R.attr.navigationBarColor,
                    android.R.attr.windowLightStatusBar,
                ),
            )
        try {
            assertEquals(Color.BLACK, attributes.getColor(0, Color.WHITE))
            assertEquals(Color.BLACK, attributes.getColor(1, Color.WHITE))
            assertFalse(attributes.getBoolean(2, true))
        } finally {
            attributes.recycle()
        }
        if (Build.VERSION.SDK_INT >= 27) {
            val navigation = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowLightNavigationBar))
            try {
                assertFalse(navigation.getBoolean(0, true))
            } finally {
                navigation.recycle()
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val splash = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowSplashScreenBackground))
            try {
                assertEquals(Color.BLACK, splash.getColor(0, Color.WHITE))
            } finally {
                splash.recycle()
            }
        } else {
            val background = render(R.drawable.splash_window_background)
            assertEquals(Color.BLACK, background.getPixel(0, 0))
        }

        val mark = render(R.drawable.ic_splash_logo)
        val pixels = List(mark.width * mark.height) { index -> mark.getPixel(index % mark.width, index / mark.width) }
        assertTrue("splash contour keeps transparency", pixels.any { Color.alpha(it) == 0 })
        assertTrue("splash contour keeps soft alpha", pixels.any { Color.alpha(it) in 1..254 })
        val tones = pixels.filter { Color.alpha(it) == 255 }.map(Color::red).toSet()
        assertTrue("splash ceramic must remain light", tones.min() >= 128)
        assertTrue("splash ceramic must not be flattened", tones.max() - tones.min() > 15)
        assertTrue(
            "splash ceramic must be neutral",
            pixels.filter { Color.alpha(it) == 255 }.all { Color.red(it) == Color.green(it) && Color.red(it) == Color.blue(it) },
        )
    }

    private fun render(resource: Int): Bitmap {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val drawable = requireNotNull(ContextCompat.getDrawable(context, resource))
        return Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
        }
    }
}
