package io.github.trevarj.motd.attachment

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

class ImageMetadataSanitizerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val secret = "private GPS camera owner location".toByteArray()

    @Test fun progressiveJpegRemovesMetadataBetweenScansAndPreservesPixelsAndOrientation() =
        runBlocking {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val encoded = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(encoded).use { destination ->
                writer.output = destination
                val params = writer.defaultWriteParam.apply { progressiveMode = ImageWriteParam.MODE_DEFAULT }
                writer.write(null, javax.imageio.IIOImage(picture(), null, null), params)
            }
            writer.dispose()
            val original = encoded.toByteArray()
            val scans = (0 until original.size - 1).filter { original[it] == 255.toByte() && original[it + 1] == 0xda.toByte() }
            assertTrue(scans.size > 1)
            val insertion = scans[1]
            val dirty =
                original.copyOfRange(0, 2) + jpegSegment(0xe1, exif(6) + secret) +
                    original.copyOfRange(2, insertion) + jpegSegment(0xfe, secret) +
                    jpegSegment(0xed, secret) + original.copyOfRange(insertion, original.size) + secret
            val result = sanitize(dirty)
            assertEquals("image/jpeg", result.first.mimeType)
            assertPrivateDataGone(result.second)
            assertPixelsEqual(original, result.second)
            assertTrue(contains(result.second, exif(6)))
        }

    @Test fun pngAndApngRetainCompressedPixelsFramesAndFreshOrientationOnly() =
        runBlocking {
            val png = encode("png")
            val parts = pngChunks(png)
            val signature = png.copyOfRange(0, 8)
            val header = parts.single { it.first == "IHDR" }.second
            val pixels = parts.filter { it.first == "IDAT" }.flatMap { it.second.toList() }.toByteArray()
            val control =
                ByteBuffer
                    .allocate(26)
                    .putInt(0)
                    .putInt(2)
                    .putInt(2)
                    .putInt(0)
                    .putInt(0)
                    .putShort(1.toShort())
                    .putShort(10.toShort())
                    .put(0.toByte())
                    .put(0.toByte())
                    .array()
            val secondControl = control.copyOf().also { ByteBuffer.wrap(it).putInt(1) }
            val dirty =
                signature + pngChunk("IHDR", header) + pngChunk("acTL", intBytes(2) + intBytes(0)) +
                    pngChunk("eXIf", exif(8).copyOfRange(6, 32) + secret) + pngChunk("fcTL", control) +
                    pngChunk("IDAT", pixels) + pngChunk("tEXt", secret) + pngChunk("fcTL", secondControl) +
                    pngChunk("fdAT", intBytes(2) + pixels) + pngChunk("iTXt", secret) + pngChunk("IEND", byteArrayOf()) + secret
            val result = sanitize(dirty).second
            assertPrivateDataGone(result)
            assertPixelsEqual(png, result)
            val clean = pngChunks(result)
            assertEquals(2, clean.count { it.first == "fcTL" })
            assertArrayEquals(pixels, clean.single { it.first == "IDAT" }.second)
            assertArrayEquals(intBytes(2) + pixels, clean.single { it.first == "fdAT" }.second)
            assertArrayEquals(exif(8).copyOfRange(6, 32), clean.single { it.first == "eXIf" }.second)
        }

    @Test fun gifRemovesExtensionsBetweenFramesWithoutRemovingLoopOrDelay() =
        runBlocking {
            val gif = encode("gif")
            val table = if ((gif[10].toInt() and 128) != 0) 3 shl ((gif[10].toInt() and 7) + 1) else 0
            val start = 13 + table
            val frame = gif.copyOfRange(start, gif.size - 1)
            val comment = byteArrayOf(0x21, 0xfe.toByte(), secret.size.toByte()) + secret + byteArrayOf(0)
            val loop = byteArrayOf(0x21, 0xff.toByte(), 11) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0)
            val control = byteArrayOf(0x21, 0xf9.toByte(), 4, 0, 5, 0, 0, 0)
            val dirty = gif.copyOfRange(0, start) + loop + comment + control + frame + comment + control + frame + byteArrayOf(0x3b) + secret
            val result = sanitize(dirty).second
            assertPrivateDataGone(result)
            assertTrue(contains(result, loop))
            assertTrue(contains(result, control))
            ImageIO.createImageInputStream(ByteArrayInputStream(result)).use { source ->
                val reader = ImageIO.getImageReadersByFormatName("gif").next()
                reader.input = source
                assertEquals(2, reader.getNumImages(true))
                assertEquals(reader.read(0).getRGB(0, 0), reader.read(1).getRGB(0, 0))
                reader.dispose()
            }
        }

    @Test fun animatedWebpRetainsFramePayloadAndOrientationWhileRemovingXmp() =
        runBlocking {
            val simple = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA")
            val payload = simple.copyOfRange(12, simple.size)
            val extended = byteArrayOf(14, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            val frameHeader = ByteArray(16)
            val frame = webpChunk("ANMF", frameHeader + payload)
            val dirty =
                riff(
                    webpChunk("VP8X", extended) + webpChunk("ANIM", ByteArray(6)) + frame +
                        webpChunk("XMP ", secret) + frame + webpChunk("EXIF", exif(6) + secret),
                ) + secret
            val result = sanitize(dirty)
            assertEquals("image/webp", result.first.mimeType)
            assertPrivateDataGone(result.second)
            assertTrue(contains(result.second, frame + frame))
            assertTrue(contains(result.second, webpChunk("EXIF", exif(6).copyOfRange(6, 32))))
            assertEquals(result.second.size - 8, ByteBuffer.wrap(result.second, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        }

    @Test fun unsupportedBytesAndProfilesDoNotCreateOutputAndMalformedImagesFail() =
        runBlocking {
            val input = temporary.newFile("spoofed.jpg").apply { writeText("not an image") }
            val output = File(temporary.root, "absent")
            assertNull(stripImageMetadata(input, output))
            assertFalse(output.exists())
            val png = encode("png")
            val headerEnd = 8 + 12 + 13
            input.writeBytes(png.copyOfRange(0, headerEnd) + pngChunk("iCCP", secret) + png.copyOfRange(headerEnd, png.size))
            assertNull(stripImageMetadata(input, output))
            assertFalse(output.exists())
            input.writeBytes(png.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() })
            try {
                stripImageMetadata(input, output)
                throw AssertionError("Corrupt CRC accepted")
            } catch (_: IOException) {
                assertFalse(output.exists())
            }
            input.writeBytes(byteArrayOf(255.toByte(), 216.toByte(), 255.toByte(), 225.toByte(), 0, 50, 1))
            try {
                stripImageMetadata(input, output)
                throw AssertionError("Truncated JPEG accepted")
            } catch (_: IOException) {
                assertFalse(output.exists())
            }
        }

    @Test fun oversizedExifRequiresOriginalConsentWithoutCreatingOutput() =
        runBlocking {
            val input = temporary.newFile()
            val output = File(temporary.root, "unsupported-exif")
            val exif = exif(6) + ByteArray(65537)
            val png = encode("png")
            val headerEnd = 8 + 12 + 13
            input.writeBytes(
                png.copyOfRange(0, headerEnd) + pngChunk("eXIf", exif.copyOfRange(6, exif.size)) +
                    png.copyOfRange(headerEnd, png.size),
            )
            assertNull(stripImageMetadata(input, output))
            assertFalse(output.exists())
            val webp = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA")
            input.writeBytes(
                riff(
                    webpChunk("VP8X", byteArrayOf(8, 0, 0, 0, 0, 0, 0, 0, 0, 0)) +
                        webp.copyOfRange(12, webp.size) + webpChunk("EXIF", exif),
                ),
            )
            assertNull(stripImageMetadata(input, output))
            assertFalse(output.exists())
        }

    @Test fun twelveBitExtendedAndProgressiveJpegHeadersAreUnsupportedNotMalformed() =
        runBlocking {
            val input = temporary.newFile()
            val output = File(temporary.root, "unsupported-precision")
            val jpeg = encode("jpeg")
            val frame = (0 until jpeg.size - 1).first { jpeg[it] == 255.toByte() && jpeg[it + 1] == 0xc0.toByte() }
            for (marker in listOf(0xc1, 0xc2)) {
                input.writeBytes(
                    jpeg.copyOf().also {
                        it[frame + 1] = marker.toByte()
                        it[frame + 4] = 12
                    },
                )
                assertNull(stripImageMetadata(input, output))
                assertFalse(output.exists())
            }
            for (marker in listOf(0xc0, 0xc1, 0xc2)) {
                input.writeBytes(
                    jpeg.copyOf().also {
                        it[frame + 1] = marker.toByte()
                        it[frame + 4] = if (marker == 0xc0) 12 else 7
                    },
                )
                try {
                    stripImageMetadata(input, output)
                    throw AssertionError("Illegal JPEG precision accepted")
                } catch (_: IOException) {
                    assertFalse(output.exists())
                }
            }
        }

    private suspend fun sanitize(bytes: ByteArray): Pair<SanitizedMedia, ByteArray> {
        val input = temporary.newFile().apply { writeBytes(bytes) }
        val output = temporary.newFile()
        val media = stripImageMetadata(input, output)
        assertNotNull(media)
        return media!! to output.readBytes()
    }

    private fun picture() =
        BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB).apply {
            setRGB(0, 0, 0xff112233.toInt())
            setRGB(1, 0, 0xffabcdef.toInt())
            setRGB(0, 1, 0xff556677.toInt())
            setRGB(1, 1, 0xffcc0011.toInt())
        }

    private fun encode(format: String): ByteArray = ByteArrayOutputStream().also { ImageIO.write(picture(), format, it) }.toByteArray()

    private fun assertPixelsEqual(
        first: ByteArray,
        second: ByteArray,
    ) {
        val a = ImageIO.read(ByteArrayInputStream(first))
        val b = ImageIO.read(ByteArrayInputStream(second))
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        assertArrayEquals(a.getRGB(0, 0, a.width, a.height, null, 0, a.width), b.getRGB(0, 0, b.width, b.height, null, 0, b.width))
    }

    private fun assertPrivateDataGone(bytes: ByteArray) {
        assertFalse(contains(bytes, secret))
    }

    private fun contains(
        bytes: ByteArray,
        needle: ByteArray,
    ): Boolean = (0..bytes.size - needle.size).any { index -> needle.indices.all { bytes[index + it] == needle[it] } }

    private fun intBytes(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    private fun jpegSegment(
        marker: Int,
        data: ByteArray,
    ): ByteArray = byteArrayOf(255.toByte(), marker.toByte(), ((data.size + 2) ushr 8).toByte(), (data.size + 2).toByte()) + data

    private fun exif(orientation: Int): ByteArray =
        byteArrayOf(
            69,
            120,
            105,
            102,
            0,
            0,
            73,
            73,
            42,
            0,
            8,
            0,
            0,
            0,
            1,
            0,
            18,
            1,
            3,
            0,
            1,
            0,
            0,
            0,
            orientation.toByte(),
            0,
            0,
            0,
            0,
            0,
            0,
            0,
        )

    private fun pngChunk(
        type: String,
        data: ByteArray,
    ): ByteArray {
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc =
            CRC32().apply {
                update(name)
                update(data)
            }
        return intBytes(data.size) + name + data + intBytes(crc.value.toInt())
    }

    private fun pngChunks(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        var offset = 8
        while (offset < bytes.size) {
            val length = ByteBuffer.wrap(bytes, offset, 4).int
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            result += type to bytes.copyOfRange(offset + 8, offset + 8 + length)
            offset += length + 12
        }
        return result
    }

    private fun webpChunk(
        type: String,
        data: ByteArray,
    ): ByteArray =
        type.toByteArray(Charsets.US_ASCII) +
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(data.size)
                .array() + data +
            if (data.size % 2 == 1) byteArrayOf(0) else byteArrayOf()

    private fun riff(chunks: ByteArray): ByteArray =
        "RIFF".toByteArray() +
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(chunks.size + 4)
                .array() + "WEBP".toByteArray() + chunks
}
