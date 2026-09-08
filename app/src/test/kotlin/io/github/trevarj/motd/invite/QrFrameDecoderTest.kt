package io.github.trevarj.motd.invite

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrFrameDecoderTest {
    @Test
    fun `generated invitation decodes from padded luminance frame`() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = JoinInviteCodec.installUri(JoinInviteV1(networkName = "Ergo", host = "irc.example", port = 6697, channel = "#friends"))
        val bitmap = inviteQrBitmap(text, 320)
        val stride = bitmap.width + 16
        val bytes = ByteArray(stride * bitmap.height) { 0x7f }
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                bytes[y * stride + x] = if ((pixel and 0xFF) < 128) 0 else 0xFF.toByte()
            }
        }

        assertEquals(text, decodeQrFrame(bytes, bitmap.width, bitmap.height, stride, 0))
    }

    @Test
    fun `high correction QR survives Signal style card branding`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val text =
            JoinInviteCodec.installUri(
                JoinInviteV2(
                    networkName = "Example Network",
                    host = "irc.example.test",
                    port = 6697,
                    contactNick = "inviter[mobile]",
                    certSha256 = "ab".repeat(32),
                ),
            )
        val bitmap = brandedInviteQrBitmap(context, text, "inviter[mobile]", size = 512)
        val qrInset = (bitmap.width * 0.06f).toInt()
        assertEquals(Color.rgb(0, 122, 124), bitmap.getPixel(qrInset, qrInset))
        val bytes = ByteArray(bitmap.width * bitmap.height)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                bytes[y * bitmap.width + x] = if ((bitmap.getPixel(x, y) and 0xFF) < 128) 0 else 0xFF.toByte()
            }
        }

        assertEquals(text, decodeQrFrame(bytes, bitmap.width, bitmap.height, bitmap.width, 0))
    }

    @Test
    fun `branded contact QR has an avatar free centered nickname footer`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val accent = Color.rgb(0, 122, 124)
        val bitmap = brandedInviteQrBitmap(context, "https://example.test/invite", "i", accent = accent, size = 512)
        val horizontalInset = (bitmap.width * 0.08f).toInt()
        val footerCenterY = ((bitmap.width * 0.06f).toInt() + (bitmap.width * 0.88f).toInt() + bitmap.height) / 2
        val footerPixels =
            buildList {
                for (y in footerCenterY - 24..footerCenterY + 24) {
                    // Exclude the card's transparent rounded corners from the footer ink bounds.
                    for (x in horizontalInset until bitmap.width - horizontalInset) {
                        if (bitmap.getPixel(x, y) != accent) add(x to y)
                    }
                }
            }

        assertTrue(footerPixels.isNotEmpty())
        val left = footerPixels.minOf { it.first }
        val right = footerPixels.maxOf { it.first }
        val top = footerPixels.minOf { it.second }
        val bottom = footerPixels.maxOf { it.second }
        assertTrue("footer should contain only the nickname, not an avatar", right - left < 24)
        assertTrue("footer nickname should be vertically compact", bottom - top < 32)
        assertEquals(bitmap.width / 2f, (left + right) / 2f, 3f)
    }

    @Test
    fun `empty frame is ignored`() {
        assertNull(decodeQrFrame(ByteArray(100), 10, 10, 10, 0))
    }
}
