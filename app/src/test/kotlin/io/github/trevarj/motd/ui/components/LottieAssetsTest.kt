package io.github.trevarj.motd.ui.components

import android.content.Context
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.compose.LottieDynamicProperties
import com.airbnb.lottie.compose.LottieDynamicProperty
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieFrameInfo
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.lottieFillColor
import io.github.trevarj.motd.ui.theme.lottieStrokeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToLong

/**
 * The hand-authored bodymovin assets parse, share the 60fps timebase, and expose exactly the layer
 * names the call sites recolor through. A typo in a keypath is otherwise silent: Lottie just renders
 * the placeholder color baked into the JSON.
 *
 * The recolors themselves are checked here too. Round 1 shipped a crash this file did not catch:
 * [LottieDynamicProperty]'s trailing-lambda constructor widened `T` to `Any`, storing the lambda
 * object as the color, and Lottie's cast to Integer blew up on first draw. Every property a call
 * site builds is asserted to resolve to an [Int].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LottieAssetsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private companion object {
        /** Lottie parks `endFrame` a hundredth short of `op` so the last frame stays drawable. */
        const val END_FRAME_TOLERANCE = 0.02f
        const val ARGB = 0xFF336699.toInt()

        /** The grey every asset bakes in (`[0.4, 0.4, 0.4, 1]`) and nothing may ever render. */
        const val PLACEHOLDER = 0xFF666666.toInt()

        /** Three distinguishable inks, so a recolor landing on the wrong layer is visible. */
        const val INK_A = 0xFFE91E63.toInt()
        const val INK_B = 0xFF00BCD4.toInt()
        const val INK_C = 0xFFCDDC39.toInt()

        /** Long edge each asset is scaled to before sampling, so strokes have interior pixels. */
        const val RENDER_EDGE_PX = 480

        /** Antialiasing eats the outline; a genuinely painted shape leaves far more than this. */
        const val MIN_PIXELS = 20
    }

    private fun load(rawRes: Int): LottieComposition {
        val result = LottieCompositionFactory.fromRawResSync(context, rawRes)
        assertNull(result.exception)
        return requireNotNull(result.value)
    }

    /** Keypaths resolve against a drawable, which is where the runtime applies them too. */
    private fun LottieComposition.resolves(vararg keyPath: String) =
        LottieDrawable()
            .apply { composition = this@resolves }
            .resolveKeyPath(KeyPath(*keyPath))
            .isNotEmpty()

    /**
     * The value Lottie will actually hand its color setter.
     *
     * The value-taking constructor wraps the color in a callback, so the only way to see what was
     * stored is to run it. `callback` is Kotlin-internal, hence the reflective read.
     */
    @Suppress("UNCHECKED_CAST")
    private fun LottieDynamicProperty<*>.resolvedValue(): Any? {
        val getter = LottieDynamicProperty::class.java.getMethod("getCallback\$lottie_compose_release")
        val callback = getter.invoke(this) as Function1<LottieFrameInfo<Any?>, Any?>
        return callback.invoke(LottieFrameInfo())
    }

    /**
     * Renders one frame of an asset with exactly the [LottieDynamicProperties] a call site builds,
     * and returns every pixel it painted.
     *
     * This is the assertion the keypath/type checks above cannot make. A `lottieFillColor` aimed at
     * a stroke keypath -- or the reverse -- resolves fine, constructs fine, holds an Int fine, and
     * then renders the placeholder grey baked into the JSON. Only the pixels say so.
     */
    private fun render(
        rawRes: Int,
        frame: Int,
        properties: List<LottieDynamicProperty<Int>>,
    ): IntArray {
        val composition = load(rawRes)
        val drawable = LottieDrawable().apply { this.composition = composition }
        LottieDynamicProperties::class.java
            .getMethod("addTo\$lottie_compose_release", LottieDrawable::class.java)
            .invoke(LottieDynamicProperties(properties), drawable)
        drawable.frame = frame

        // Scaled up so antialiasing cannot swallow a thin stroke entirely.
        val bounds = composition.bounds
        val scale = RENDER_EDGE_PX / maxOf(bounds.width(), bounds.height())
        val width = bounds.width() * scale
        val height = bounds.height() * scale
        drawable.setBounds(0, 0, width, height)
        val bitmap = createBitmap(width, height)
        drawable.draw(Canvas(bitmap))

        return IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
    }

    /** Asserts each ink was painted and the placeholder grey never was. */
    private fun IntArray.assertPainted(vararg inks: Int) {
        inks.forEach { ink ->
            assertTrue(
                "expected ink %06X on screen, found %d pixels of it".format(ink, count { it == ink }),
                count { it == ink } >= MIN_PIXELS,
            )
        }
        assertEquals("placeholder grey reached the screen", 0, count { it == PLACEHOLDER })
    }

    /** Asserts a layer that is meant to be invisible at this frame painted nothing at all. */
    private fun IntArray.assertAbsent(vararg inks: Int) = inks.forEach { ink -> assertEquals(0, count { it == ink }) }

    @Test fun `every asset shares the 60fps timebase`() {
        listOf(
            R.raw.status_delivered,
            R.raw.status_failed,
            R.raw.connection_state,
            R.raw.sync_state,
            R.raw.onboarding_hero,
            R.raw.ghost_rows,
            R.raw.reaction_burst,
        ).forEach { res -> assertEquals(60f, load(res).frameRate, 0f) }
    }

    @Test fun `the delivery tick is one short one-shot holding both glyphs`() {
        val composition = load(R.raw.status_delivered)

        assertEquals(0f, composition.startFrame, 0f)
        assertEquals(23f, composition.endFrame, END_FRAME_TOLERANCE)
        assertTrue(composition.resolves("clock", "**"))
        assertTrue(composition.resolves("check", "**"))
    }

    @Test fun `the failure tick mirrors the delivery tick frame for frame`() {
        // Sibling assets, identical ranges: that is what lets MessageBubble pick a raw resource and
        // a keypath and otherwise share every frame decision between the two endings.
        val failed = load(R.raw.status_failed)
        val delivered = load(R.raw.status_delivered)

        assertEquals(delivered.startFrame, failed.startFrame, 0f)
        assertEquals(delivered.endFrame, failed.endFrame, 0f)
        assertTrue(failed.resolves("clock", "**"))
        assertTrue(failed.resolves("cross", "**"))
    }

    @Test fun `the connection asset spans both beats`() {
        val composition = load(R.raw.connection_state)

        assertEquals(0f, composition.startFrame, 0f)
        assertEquals(63f, composition.endFrame, END_FRAME_TOLERANCE)
        assertTrue(composition.resolves("arc", "**"))
        assertTrue(composition.resolves("check", "**"))
    }

    @Test fun `the sync asset spans both beats`() {
        val composition = load(R.raw.sync_state)

        assertEquals(0f, composition.startFrame, 0f)
        assertEquals(39f, composition.endFrame, END_FRAME_TOLERANCE)
        assertTrue(composition.resolves("dots", "**"))
        assertTrue(composition.resolves("check", "**"))
    }

    @Test fun `the onboarding hero names the glyph and each staggered ray`() {
        val composition = load(R.raw.onboarding_hero)

        assertEquals(0f, composition.startFrame, 0f)
        assertEquals(42f, composition.endFrame, END_FRAME_TOLERANCE)
        assertTrue(composition.resolves("hero_glyph", "**"))
        assertTrue(composition.resolves("hero_ray_1", "**"))
        assertTrue(composition.resolves("hero_ray_2", "**"))
        assertTrue(composition.resolves("hero_ray_3", "**"))
    }

    @Test fun `the ghost rows stagger three named skeleton rows`() {
        // A 350ms rise per row, the last of them delayed by 200ms, plus one frame so the settled
        // state is drawable. The caption is Compose text and deliberately outside the asset.
        val composition = load(R.raw.ghost_rows)

        assertEquals(0f, composition.startFrame, 0f)
        assertEquals(EmptyStateGhostRows.TotalFrames.toFloat(), composition.endFrame, END_FRAME_TOLERANCE)
        assertTrue(composition.resolves("ghost_row_1", "**"))
        assertTrue(composition.resolves("ghost_row_2", "**"))
        assertTrue(composition.resolves("ghost_row_3", "**"))
    }

    @Test fun `the reaction burst is one short one-shot of sparks`() {
        // 24 frames = 400ms, matching REACTION_BURST_DURATION_MS, which is when the chip unmounts it.
        val composition = load(R.raw.reaction_burst)

        assertEquals(0f, composition.startFrame, 0f)
        assertEquals(24f, composition.endFrame, END_FRAME_TOLERANCE)
        assertEquals(REACTION_BURST_DURATION_MS, (composition.endFrame / 60f * 1_000f).roundToLong())
        assertTrue(composition.resolves("burst", "**"))
    }

    @Test fun `every recolor a call site builds resolves to an Int`() {
        // The round-1 crash in one assertion: anything but an Int here is a ClassCastException on
        // the asset's first draw, on a device, with no unit test in the way.
        val properties =
            listOf(
                // MessageBubble's two morph endings.
                lottieStrokeColor(ARGB, KeyPath("clock", "**")),
                lottieStrokeColor(ARGB, KeyPath("check", "**")),
                lottieStrokeColor(ARGB, KeyPath("cross", "**")),
                // Connection asset.
                lottieStrokeColor(ARGB, KeyPath("arc", "**")),
                // Sync asset: dots are fills, the check is a stroke.
                lottieFillColor(ARGB, KeyPath("dots", "**")),
                // EmptyState's ghost rows.
                lottieFillColor(ARGB, KeyPath("ghost_row_1", "**")),
                lottieFillColor(ARGB, KeyPath("ghost_row_2", "**")),
                lottieFillColor(ARGB, KeyPath("ghost_row_3", "**")),
                // ReactionRow's sparks.
                lottieFillColor(ARGB, KeyPath("burst", "**")),
            )

        properties.forEach { property ->
            val value = property.resolvedValue()
            assertTrue("expected an Int color, got ${value?.javaClass}", value is Int)
            assertEquals(ARGB, value)
        }
    }

    @Test fun `fills and strokes are recolored through different Lottie properties`() {
        // The stroke helper reaches no fill content at all: an asset recolored with the wrong
        // sibling silently keeps the placeholder grey baked into its JSON.
        assertEquals(
            com.airbnb.lottie.LottieProperty.STROKE_COLOR,
            lottieStrokeColor(ARGB, KeyPath("check", "**")).property(),
        )
        assertEquals(
            com.airbnb.lottie.LottieProperty.COLOR,
            lottieFillColor(ARGB, KeyPath("dots", "**")).property(),
        )
    }

    private fun LottieDynamicProperty<*>.property(): Any? = LottieDynamicProperty::class.java.getMethod("getProperty\$lottie_compose_release").invoke(this)

    // --- rendered recolors: keypath, property constant and value, checked in one assertion ---

    @Test fun `the delivery morph paints the clock then the check`() {
        val properties =
            listOf(
                lottieStrokeColor(INK_A, KeyPath("clock", "**")),
                lottieStrokeColor(INK_B, KeyPath("check", "**")),
            )

        render(R.raw.status_delivered, frame = 0, properties = properties).run {
            assertPainted(INK_A)
            assertAbsent(INK_B)
        }
        render(R.raw.status_delivered, frame = 23, properties = properties).run {
            assertPainted(INK_B)
            assertAbsent(INK_A)
        }
    }

    @Test fun `the failure morph paints the clock then the cross`() {
        val properties =
            listOf(
                lottieStrokeColor(INK_A, KeyPath("clock", "**")),
                lottieStrokeColor(INK_B, KeyPath("cross", "**")),
            )

        render(R.raw.status_failed, frame = 0, properties = properties).run {
            assertPainted(INK_A)
            assertAbsent(INK_B)
        }
        render(R.raw.status_failed, frame = 23, properties = properties).run {
            assertPainted(INK_B)
            assertAbsent(INK_A)
        }
    }

    @Test fun `the connection asset paints the arc then the check`() {
        val properties =
            listOf(
                lottieStrokeColor(INK_A, KeyPath("arc", "**")),
                lottieStrokeColor(INK_B, KeyPath("check", "**")),
            )

        render(R.raw.connection_state, frame = 0, properties = properties).run {
            assertPainted(INK_A)
            // The check's trim is still at zero: the resolve beat has not begun.
            assertAbsent(INK_B)
        }
        render(R.raw.connection_state, frame = 62, properties = properties).run {
            assertPainted(INK_B)
            assertAbsent(INK_A)
        }
    }

    @Test fun `the sync asset paints filled dots then a stroked check`() {
        // The one asset in the set that mixes both helpers: swapping them renders grey and nothing
        // else would notice.
        val properties =
            listOf(
                lottieFillColor(INK_A, KeyPath("dots", "**")),
                lottieStrokeColor(INK_B, KeyPath("check", "**")),
            )

        render(R.raw.sync_state, frame = 0, properties = properties).run {
            assertPainted(INK_A)
            assertAbsent(INK_B)
        }
        render(R.raw.sync_state, frame = 38, properties = properties).run {
            assertPainted(INK_B)
            assertAbsent(INK_A)
        }
    }

    @Test fun `every ghost row paints its own skeleton tone`() {
        // Three separately named layers, three distinct inks: a keypath aimed at the wrong row
        // leaves one of them grey.
        render(
            R.raw.ghost_rows,
            // One frame short of `op`, which is the last frame Lottie will draw and the one the
            // animations-off snap parks on: every row must be fully opaque there.
            frame = EmptyStateGhostRows.TotalFrames - 1,
            properties =
                listOf(
                    lottieFillColor(INK_A, KeyPath("ghost_row_1", "**")),
                    lottieFillColor(INK_B, KeyPath("ghost_row_2", "**")),
                    lottieFillColor(INK_C, KeyPath("ghost_row_3", "**")),
                ),
        ).assertPainted(INK_A, INK_B, INK_C)
    }

    @Test fun `the reaction sparks paint the accent`() {
        // Frame 10 is the last fully opaque one before the sparks start fading.
        render(
            R.raw.reaction_burst,
            frame = 10,
            properties = listOf(lottieFillColor(INK_A, KeyPath("burst", "**"))),
        ).assertPainted(INK_A)
    }

    @Test fun `the onboarding hero paints one ink across every layer`() {
        // Its call site recolors with a single global keypath, so the whole mark takes one color.
        render(
            R.raw.onboarding_hero,
            frame = 42,
            properties = listOf(lottieStrokeColor(INK_A, KeyPath("**"))),
        ).assertPainted(INK_A)
    }
}
