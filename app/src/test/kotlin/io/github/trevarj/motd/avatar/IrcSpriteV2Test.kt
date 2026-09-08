package io.github.trevarj.motd.avatar

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IrcSpriteV2Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun tint_preserves_alpha_and_applies_the_documented_nickname_hue() {
        val source = Color.argb(127, 100, 100, 100)
        val tinted = tintIrcSpriteV2Pixel(source, Color.RED)
        val fullStrength = tintIrcSpriteV2Pixel(source, Color.RED, accentStrength = 1f)

        assertEquals(127, Color.alpha(tinted))
        assertEquals(100, Color.red(tinted))
        assertEquals(78, Color.green(tinted))
        assertEquals(78, Color.blue(tinted))
        assertEquals(100, Color.red(fullStrength))
        assertEquals(0, Color.green(fullStrength))
        assertEquals(0, Color.blue(fullStrength))
        assertEquals(Color.TRANSPARENT, tintIrcSpriteV2Pixel(Color.TRANSPARENT, Color.BLUE))
        assertNotEquals(tinted, tintIrcSpriteV2Pixel(source, Color.BLUE))
    }

    @Test fun catalog_parser_keeps_component_order_and_head_specific_placement() {
        val catalog =
            parseIrcSpriteV2Catalog(
                """
                {"version":2,"canvasSize":256,"components":{
                  "body":[{"id":"compact","file":"body.png","rect":[0,0,1,1]}],
                  "head":[{"id":"terminal","file":"head.png","rect":[0,0,1,1],"faceRect":[0.2,0.3,0.4,0.1],"accessoryRects":{"antenna":[0.4,0,0.2,0.2]}}],
                  "face":[{"id":"welcoming","file":"face.png","rect":[0,0,1,1]}],
                  "accessory":[{"id":"antenna","file":"antenna.png","rect":[0,0,1,1],"behindHead":true}],
                  "accent":[{"id":"badge","file":"badge.png","rect":[0,0,1,1]}]
                }}
                """.trimIndent(),
            )

        assertEquals("compact", catalog.bodies.single().id)
        assertEquals("terminal", catalog.heads.single().id)
        assertEquals(
            0.4f,
            catalog.heads
                .single()
                .accessoryRects
                .getValue("antenna")
                .x,
        )
        assertEquals(true, catalog.accessories.single().behindHead)
        assertNull(catalog.framing)
    }

    @Test fun active_framing_centers_each_head_and_preserves_relative_layer_placement() {
        val catalog = IrcSpriteV2Renderer.catalogForTest(context)!!
        val framing = requireNotNull(catalog.framing)
        assertEquals(1.4f, framing.zoom, 0f)
        val body = catalog.bodies.first()

        catalog.heads.forEach { head ->
            val transform = IrcSpriteV2LayerTransform.forHead(head.rect, framing.zoom)
            val centeredHead = transform.rect(head.rect)
            assertEquals(0.5f, centerX(centeredHead), 0.0001f)
            assertEquals(0.5f, centerY(centeredHead), 0.0001f)
            assertTrue(transform.rect(body.rect).bottom > 1f)

            val accessory = catalog.accessories.first()
            val attachment = head.accessoryRects.getValue(accessory.id)
            val framedAttachment = transform.rect(attachment)
            assertEquals(
                (centerX(attachment) - centerX(head.rect)) * framing.zoom,
                centerX(framedAttachment) - centerX(centeredHead),
                0.0001f,
            )
            assertEquals(
                (centerY(attachment) - centerY(head.rect)) * framing.zoom,
                centerY(framedAttachment) - centerY(centeredHead),
                0.0001f,
            )
        }
    }

    @Test fun packaged_catalog_loads_every_layer_and_renders_a_circular_tinted_scene() {
        val catalog = IrcSpriteV2Renderer.catalogForTest(context)!!
        assertEquals(3, catalog.bodies.size)
        assertEquals(8, catalog.heads.size)
        assertEquals(7, catalog.faces.size)
        assertEquals(10, catalog.accessories.size)
        assertEquals(1, catalog.accents.size)
        (catalog.bodies + catalog.heads + catalog.faces + catalog.accessories + catalog.accents).forEach { component ->
            val bitmap = context.assets.open("irc-sprites-v2/${component.file}").use(BitmapFactory::decodeStream)
            requireNotNull(bitmap) { "Unable to decode ${component.file}" }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("${component.file} has no transparent pixels", pixels.any { Color.alpha(it) == 0 })
            assertTrue("${component.file} has no visible pixels", pixels.any { Color.alpha(it) > 128 })
        }

        val red =
            IrcSpriteV2Renderer.render(
                context,
                "alice",
                Color.RED,
                64,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
            )!!
        val blue =
            IrcSpriteV2Renderer.render(
                context,
                "alice",
                Color.BLUE,
                64,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
            )!!
        assertEquals(0, Color.alpha(red.getPixel(0, 0)))
        assertFalse(red.sameAs(blue))
    }

    private fun centerX(rect: IrcSpriteV2Rect): Float = rect.x + rect.width / 2f

    private fun centerY(rect: IrcSpriteV2Rect): Float = rect.y + rect.height / 2f

    private val IrcSpriteV2Rect.bottom: Float get() = y + height
}
