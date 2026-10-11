package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.UiDispatcherResetRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaterialIconRenderingTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test fun replyMirrorsInRtlWhileForumKeepsItsGeometryAndBothReuseTheirVectors() {
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val reply = Icons.AutoMirrored.Filled.Reply
        val forum = Icons.Outlined.Forum
        assertSame(reply, Icons.AutoMirrored.Filled.Reply)
        assertSame(forum, Icons.Outlined.Forum)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalLayoutDirection provides direction.value) {
                Row {
                    Icon(reply, null, Modifier.size(72.dp).background(Color.White).testTag("reply"), tint = Color.Black)
                    Icon(forum, null, Modifier.size(72.dp).background(Color.White).testTag("forum"), tint = Color.Black)
                }
            }
        }
        val ltrReply = compose.onNodeWithTag("reply").captureToImage().asAndroidBitmap()
        val ltrForum = compose.onNodeWithTag("forum").captureToImage().asAndroidBitmap()
        compose.runOnIdle { direction.value = LayoutDirection.Rtl }
        val rtlReply = compose.onNodeWithTag("reply").captureToImage().asAndroidBitmap()
        val rtlForum = compose.onNodeWithTag("forum").captureToImage().asAndroidBitmap()
        assertFalse("reply must change direction in RTL", ltrReply.sameAs(rtlReply))
        assertTrue("forum is not direction-dependent", ltrForum.sameAs(rtlForum))
        assertEquals(ltrReply.width, rtlReply.width)
        assertEquals(ltrReply.height, rtlReply.height)
        for (y in 0 until ltrReply.height) {
            for (x in 0 until ltrReply.width) {
                val original = ltrReply.getPixel(x, y)
                val mirrored = rtlReply.getPixel(rtlReply.width - 1 - x, y)
                for (shift in 0..24 step 8) {
                    assertEquals(
                        "reply mirrored pixel $x,$y channel $shift",
                        (original ushr shift and 255).toFloat(),
                        (mirrored ushr shift and 255).toFloat(),
                        1f,
                    )
                }
            }
        }
    }
}
