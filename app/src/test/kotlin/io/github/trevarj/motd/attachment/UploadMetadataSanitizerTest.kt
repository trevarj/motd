package io.github.trevarj.motd.attachment

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UploadMetadataSanitizerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun sanitizer(): Pair<UploadMetadataSanitizer, File> {
        val cache = temporary.newFolder()
        val context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                override fun getCacheDir(): File = cache
            }
        return UploadMetadataSanitizer(context) to cache
    }

    @Test
    fun sniffedPhotoRemovesPrivateTextAndNameWithoutChangingSource() =
        runBlocking {
            val (sanitizer, cache) = sanitizer()
            val original = temporary.newFile("private-location.txt")
            val image = BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(1, 2, 0xff8833aa.toInt())
            val encoded = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            val text = "Author\u0000private-author-location".toByteArray()
            val chunk =
                ByteArrayOutputStream()
                    .also { bytes ->
                        DataOutputStream(bytes).use {
                            it.writeInt(text.size)
                            it.writeBytes("tEXt")
                            it.write(text)
                            val crc = CRC32()
                            crc.update("tEXt".toByteArray())
                            crc.update(text)
                            it.writeInt(crc.value.toInt())
                        }
                    }.toByteArray()
            original.writeBytes(encoded.copyOfRange(0, encoded.size - 12) + chunk + encoded.takeLast(12))
            val before = original.readBytes()
            val source = AttachmentSource.LocalFile(original, original.name, "text/plain", 1)
            val prepared = sanitizer.prepare(source, 1024 * 1024)
            val clean = prepared.source as AttachmentSource.LocalFile
            assertEquals("image/png", clean.mimeType)
            assertTrue(clean.name.endsWith(".png"))
            assertFalse(clean.name.contains("private-location"))
            assertEquals(clean.file.length(), clean.size)
            assertFalse(clean.file.readText(Charsets.ISO_8859_1).contains("private-author-location"))
            val decoded = ImageIO.read(clean.file)
            assertEquals(2, decoded.width)
            assertEquals(3, decoded.height)
            assertEquals(image.getRGB(1, 2), decoded.getRGB(1, 2))
            assertArrayEquals(before, original.readBytes())
            prepared.close()
            prepared.close()
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun unsupportedOriginalRequiresExplicitConsentAndCleansStaging() =
        runBlocking {
            val (sanitizer, cache) = sanitizer()
            val original = temporary.newFile("report.pdf")
            val bytes = "%PDF-1.7\n/Author(private-author)".toByteArray()
            original.writeBytes(bytes)
            val source = AttachmentSource.LocalFile(original, original.name, "application/pdf")
            try {
                sanitizer.prepare(source, 1024)
                throw AssertionError("Unsupported original was accepted without consent")
            } catch (_: MetadataNotSupportedException) {
                assertTrue(cache.listFiles().orEmpty().isEmpty())
            }
            sanitizer.prepare(source, 1024, allowOriginal = true).use { prepared ->
                val staged = prepared.source as AttachmentSource.LocalFile
                assertArrayEquals(bytes, staged.file.readBytes())
                assertEquals(original.name, staged.name)
            }
            assertArrayEquals(bytes, original.readBytes())
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun actualBytesEnforceLimitDespiteFalseProviderSize() =
        runBlocking {
            val (sanitizer, cache) = sanitizer()
            val original = temporary.newFile()
            original.writeBytes(ByteArray(2048))
            try {
                sanitizer.prepare(AttachmentSource.LocalFile(original, "file.bin", "application/octet-stream", 1), 1024, allowOriginal = true)
                throw AssertionError("False source size bypassed the upload limit")
            } catch (failure: UploadException) {
                assertFalse(failure is MetadataNotSupportedException)
                assertTrue(cache.listFiles().orEmpty().isEmpty())
            }
        }

    @Test
    fun malformedSupportedMediaCannotFallBackToOriginal() =
        runBlocking {
            val (sanitizer, cache) = sanitizer()
            val original = temporary.newFile("broken.png")
            original.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 73, 72, 68, 82))
            try {
                sanitizer.prepare(AttachmentSource.LocalFile(original, original.name, "image/png"), 1024, allowOriginal = true)
                throw AssertionError("Malformed image was sent unchanged")
            } catch (failure: java.io.IOException) {
                assertFalse(failure is MetadataNotSupportedException)
                assertTrue(cache.listFiles().orEmpty().isEmpty())
            }
        }
}
