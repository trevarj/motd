package io.github.trevarj.motd

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.trevarj.motd.data.prefs.EffectSurface
import io.github.trevarj.motd.data.prefs.SurfaceEffect
import io.github.trevarj.motd.data.prefs.SurfaceEffects
import io.github.trevarj.motd.ui.components.EffectFloatingActionButton
import io.github.trevarj.motd.ui.components.SurfaceBackdropRoot
import io.github.trevarj.motd.ui.components.effectSurface
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/** Window screenshots exercise HWUI shadows that Robolectric does not reproduce. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class FabPaintWindowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun glassHasNoNativeShadowWhileOpaqueRetainsNativePaint() {
        val opacity = mutableIntStateOf(30)
        val enabled = mutableStateOf(true)
        // 0: application wrapper, 1: expected native reference, 2: old elevated glass control.
        val paint = mutableIntStateOf(0)
        val tint = Color(0xFF305080)
        var region: LayoutCoordinates? = null
        compose.setContent {
            MotdTheme(
                dynamicColor = false,
                surfaceEffects = SurfaceEffects(enabled = enabled.value, surfaces = mapOf(EffectSurface.FLOATING_BUTTON to SurfaceEffect(opacity.intValue, 0))),
            ) {
                SurfaceBackdropRoot(
                    content = { Box(Modifier.fillMaxSize().background(Color(0xFFB0C0D0))) },
                    overlays = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(112.dp).onGloballyPositioned { region = it }, contentAlignment = Alignment.Center) {
                                if (paint.intValue == 0) {
                                    EffectFloatingActionButton(onClick = {}, containerColor = tint, contentColor = Color.White) { Text("+") }
                                } else {
                                    val opaque = opacity.intValue == 100 || (!enabled.value && opacity.intValue > 0)
                                    FloatingActionButton(
                                        onClick = {},
                                        modifier = if (opaque) Modifier else Modifier.effectSurface(EffectSurface.FLOATING_BUTTON, tint, FloatingActionButtonDefaults.shape),
                                        containerColor = if (opaque) tint else Color.Transparent,
                                        contentColor = Color.White,
                                        elevation = if (opaque || paint.intValue == 2) FloatingActionButtonDefaults.elevation() else FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                                    ) { Text("+") }
                                }
                            }
                        }
                    },
                )
            }
        }

        fun capture(mode: Int): IntArray {
            compose.runOnIdle { paint.intValue = mode }
            compose.waitForIdle()
            var origin = Offset.Zero
            var width = 0
            var height = 0
            compose.runOnIdle {
                val coordinates = region!!
                origin = coordinates.localToScreen(Offset.Zero)
                width = coordinates.size.width
                height = coordinates.size.height
            }
            val screen = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                assertTrue("Hardware screenshot must contain the whole FAB and shadow", origin.x >= 0 && origin.y >= 0 && origin.x + width <= screen.width && origin.y + height <= screen.height)
                return IntArray(width * height).also { screen.getPixels(it, 0, width, origin.x.roundToInt(), origin.y.roundToInt(), width, height) }
            } finally {
                screen.recycle()
            }
        }

        fun differences(
            first: IntArray,
            second: IntArray,
        ): Int =
            first.indices.count { index ->
                (0..16 step 8).any { shift -> abs(((first[index] shr shift) and 255) - ((second[index] shr shift) and 255)) > 4 }
            }

        // A passing comparison is meaningful only if this device actually renders the old shadow.
        val flatGlass = capture(1)
        val elevatedGlass = capture(2)
        assertTrue("HWUI must distinguish elevated glass from flat glass", differences(flatGlass, elevatedGlass) > 10)

        for ((nextOpacity, nextEnabled) in listOf(30 to true, 0 to true, 100 to true, 30 to false, 0 to false)) {
            compose.runOnIdle {
                opacity.intValue = nextOpacity
                enabled.value = nextEnabled
            }
            val expected = capture(1)
            val actual = capture(0)
            assertTrue("FAB paint differs at opacity=$nextOpacity enabled=$nextEnabled", differences(expected, actual) <= 4)
        }
    }
}
