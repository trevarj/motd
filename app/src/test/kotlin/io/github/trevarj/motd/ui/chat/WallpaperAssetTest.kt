package io.github.trevarj.motd.ui.chat

import android.content.Context
import android.util.Xml
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
class WallpaperAssetTest {
    private val assets = ApplicationProvider.getApplicationContext<Context>().assets

    @Test fun everyPreset_isAConstrainedMonochromeSvg() {
        ChatWallpaperPreset.entries.filterNot { it == ChatWallpaperPreset.NONE }.forEach { preset ->
            assets.open("chat-wallpapers/${assetName(preset)}").use { input ->
                val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
                var paths = 0
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        assertTrue("unsupported ${parser.name}", parser.name == "svg" || parser.name == "path")
                        if (parser.name == "svg") assertEquals("0 0 512 512", parser.getAttributeValue(null, "viewBox"))
                        if (parser.name == "path") {
                            paths++
                            assertEquals("#000000", parser.getAttributeValue(null, "stroke"))
                            assertEquals("none", parser.getAttributeValue(null, "fill"))
                            assertFalse(parser.getAttributeValue(null, "d").isNullOrBlank())
                            assertFalse(parser.getAttributeValue(null, "transform").isNullOrBlank())
                        }
                    }
                    parser.next()
                }
                assertTrue("$preset must contain paths", paths >= 16)
            }
        }
    }
}
