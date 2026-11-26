package io.github.trevarj.motd.attachment

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32

internal suspend fun stripImageMetadata(
    input: File,
    output: File,
): SanitizedMedia? {
    currentCoroutineContext().ensureActive()
    val kind =
        RandomAccessFile(input, "r").use { file ->
            val magic = ByteArray(minOf(12L, file.length()).toInt())
            file.readFully(magic)
            when {
                magic.size >= 2 && magic[0] == 0xff.toByte() && magic[1] == 0xd8.toByte() -> "jpeg"

                magic.size >= 8 && magic.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "png"

                magic.size >= 6 && String(magic, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "gif"

                magic.size >= 12 && String(magic, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(magic, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"

                else -> return null
            }
        }

    suspend fun process(sink: OutputStream?) {
        RandomAccessFile(input, "r").use { file ->
            val reader = ImageReader(file, sink)
            when (kind) {
                "jpeg" -> reader.jpeg()
                "png" -> reader.png()
                "gif" -> reader.gif()
                else -> reader.webp()
            }
        }
    }
    try {
        process(null)
    } catch (_: UnsupportedImage) {
        return null
    }
    try {
        output.outputStream().buffered().use { process(it) }
    } catch (failure: Throwable) {
        output.delete()
        throw failure
    }
    return SanitizedMedia("image/$kind", if (kind == "jpeg") ".jpg" else ".$kind")
}

private val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

private class UnsupportedImage : IOException()

private fun webpRenderingChunk(type: String): Boolean =
    when (type) {
        "VP8X", "VP8 ", "VP8L", "ALPH", "ANIM", "ANMF" -> true
        else -> false
    }

private class ImageReader(
    private val input: RandomAccessFile,
    private val output: OutputStream?,
) {
    private val buffer = ByteArray(8192)

    private fun requireImage(condition: Boolean) {
        if (!condition) throw IOException("Malformed image")
    }

    private fun byte(): Int {
        val value = input.read()
        if (value < 0) throw IOException("Truncated image")
        return value
    }

    private fun bytes(size: Int): ByteArray {
        requireImage(size >= 0 && size.toLong() <= input.length() - input.filePointer)
        return ByteArray(size).also(input::readFully)
    }

    private fun be16(): Int = (byte() shl 8) or byte()

    private fun le32(): Long =
        byte().toLong() or (byte().toLong() shl 8) or
            (byte().toLong() shl 16) or (byte().toLong() shl 24)

    private fun emit(value: Int) {
        output?.write(value)
    }

    private fun emit(value: ByteArray) {
        output?.write(value)
    }

    private suspend fun transfer(
        size: Long,
        keep: Boolean = true,
        crc: CRC32? = null,
    ) {
        requireImage(size >= 0 && size <= input.length() - input.filePointer)
        var left = size
        while (left > 0) {
            currentCoroutineContext().ensureActive()
            val count = minOf(left, buffer.size.toLong()).toInt()
            input.readFully(buffer, 0, count)
            crc?.update(buffer, 0, count)
            if (keep) output?.write(buffer, 0, count)
            left -= count
        }
    }

    private fun emitLe32(value: Long) {
        repeat(4) { emit((value ushr (it * 8)).toInt() and 255) }
    }

    private fun emitBe32(value: Long) {
        repeat(4) { emit((value ushr ((3 - it) * 8)).toInt() and 255) }
    }

    suspend fun jpeg() {
        requireImage(be16() == 0xffd8)
        emit(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        var frame = false
        var scan = false
        var pending = -1
        var orientation: Int? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val marker =
                if (pending >= 0) {
                    pending.also { pending = -1 }
                } else {
                    requireImage(byte() == 255)
                    var next = byte()
                    while (next == 255) {
                        currentCoroutineContext().ensureActive()
                        next = byte()
                    }
                    next
                }
            if (marker == 0xd9) {
                requireImage(frame && scan)
                emit(255)
                emit(marker)
                return
            }
            requireImage(marker !in 0xd0..0xd8 && marker != 0 && marker != 1)
            val size = be16() - 2
            requireImage(size >= 0)
            if (marker in 0xe0..0xef || marker == 0xfe) {
                val data = bytes(size)
                if (marker == 0xe1 && data.startsWithAscii("Exif\u0000\u0000")) {
                    val value = exifOrientation(data.copyOfRange(6, data.size))
                    requireImage(orientation == null || value == null || orientation == value)
                    if (value != null && orientation == null) {
                        orientation = value
                        val clean = orientationExif(value)
                        emit(255)
                        emit(0xe1)
                        emit(0)
                        emit(clean.size + 2)
                        emit(clean)
                    }
                }
                if (marker == 0xe2 && data.startsWithAscii("ICC_PROFILE\u0000")) throw UnsupportedImage()
                if (marker == 0xe2 && data.startsWithAscii("MPF\u0000") ||
                    marker == 0xeb && data.startsWithAscii("JP")
                ) {
                    throw UnsupportedImage()
                }
                if (marker == 0xe0 && data.startsWithAscii("JFIF\u0000")) {
                    requireImage(data.size >= 14 && data[7].toInt() in 0..2)
                    emit(255)
                    emit(0xe0)
                    emit(0)
                    emit(16)
                    emit(byteArrayOf(74, 70, 73, 70, 0, 1, 2, 0, data[8], data[9], data[10], data[11], 0, 0))
                }
                if (marker == 0xee && data.startsWithAscii("Adobe")) {
                    requireImage(data.size >= 12 && data[11].toInt() in 0..2)
                    emit(255)
                    emit(marker)
                    emit(0)
                    emit(14)
                    emit(byteArrayOf(65, 100, 111, 98, 101, 0, 100, 0, 0, 0, 0, data[11]))
                }
                continue
            }
            if (marker in 0xc0..0xc2) {
                requireImage(!frame && size >= 6)
                val data = bytes(size)
                val components = data[5].toInt() and 255
                requireImage(
                    components in 1..4 && size == 6 + 3 * components &&
                        (data[3].toInt() != 0 || data[4].toInt() != 0),
                )
                val precision = data[0].toInt() and 255
                requireImage(precision == 8 || marker != 0xc0 && precision == 12)
                if (precision == 12 || data[1].toInt() == 0 && data[2].toInt() == 0) throw UnsupportedImage()
                frame = true
                emit(255)
                emit(marker)
                emit((size + 2) ushr 8)
                emit((size + 2) and 255)
                emit(data)
                continue
            }
            if (marker != 0xda && marker != 0xdb && marker != 0xc4 && marker != 0xdd) throw UnsupportedImage()
            requireImage(marker != 0xda || frame)
            emit(255)
            emit(marker)
            emit((size + 2) ushr 8)
            emit((size + 2) and 255)
            if (marker == 0xda) {
                val header = bytes(size)
                requireImage(
                    size >= 6 && (header[0].toInt() and 255) in 1..4 &&
                        size == 4 + 2 * (header[0].toInt() and 255),
                )
                emit(header)
                scan = true
                pending = jpegEntropy()
            } else {
                requireImage(size > 0 && (marker != 0xdd || size == 2))
                val data = bytes(size)
                if (marker == 0xdb || marker == 0xc4) {
                    var offset = 0
                    while (offset < data.size) {
                        currentCoroutineContext().ensureActive()
                        val table = data[offset++].toInt() and 255
                        requireImage((table and 15) <= 3)
                        if (marker == 0xdb) {
                            requireImage(table ushr 4 <= 1)
                            offset += 64 * (1 + (table ushr 4))
                        } else {
                            requireImage(table ushr 4 <= 1 && offset + 16 <= data.size)
                            var symbols = 0
                            var available = 1
                            repeat(16) {
                                val count = data[offset++].toInt() and 255
                                available = 2 * available - count
                                requireImage(available >= 0)
                                symbols += count
                            }
                            requireImage(symbols in 1..256)
                            offset += symbols
                        }
                        requireImage(offset <= data.size)
                    }
                }
                emit(data)
            }
        }
    }

    private suspend fun jpegEntropy(): Int {
        var escaped = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            requireImage(count > 0)
            var start = 0
            for (index in 0 until count) {
                val value = buffer[index].toInt() and 255
                if (escaped) {
                    if (value == 255) {
                        start = index + 1
                        continue
                    }
                    if (value != 0 && value !in 0xd0..0xd7) {
                        input.seek(input.filePointer - (count - index - 1))
                        return value
                    }
                    emit(255)
                    emit(value)
                    escaped = false
                    start = index + 1
                } else if (value == 255) {
                    output?.write(buffer, start, index - start)
                    escaped = true
                    start = index + 1
                }
            }
            if (!escaped) output?.write(buffer, start, count - start)
        }
    }

    suspend fun png() {
        requireImage(bytes(8).contentEquals(PNG_SIGNATURE))
        emit(PNG_SIGNATURE)
        var header = false
        var pixels = false
        var endedPixels = false
        var palette = false
        var indexed = false
        var animation = false
        var frames = 0L
        var expectedFrames = 0L
        var sequence = 0L
        var frameControl = false
        var frameData = false
        var canvasWidth = 0L
        var canvasHeight = 0L
        var firstFrameUsesIdat = false
        var hasOrientation = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val length = input.readInt().toLong() and 0xffffffffL
            requireImage(length <= Int.MAX_VALUE && length + 8 <= input.length() - input.filePointer)
            val typeBytes = bytes(4)
            requireImage(
                typeBytes.all { (it.toInt() and 255) in 65..90 || (it.toInt() and 255) in 97..122 } &&
                    (typeBytes[2].toInt() and 32) == 0,
            )
            val type = String(typeBytes, Charsets.US_ASCII)
            requireImage(header || type == "IHDR")
            val keep =
                when (type) {
                    "IHDR", "PLTE", "tRNS", "IDAT", "IEND", "acTL", "fcTL", "fdAT", "sRGB", "gAMA", "cHRM" -> true
                    else -> false
                }
            if (!keep && (typeBytes[0].toInt() and 32) == 0) throw UnsupportedImage()
            if (type == "iCCP" || type == "cICP" || type == "mDCV" || type == "cLLI") throw UnsupportedImage()
            if (pixels && type != "IDAT") endedPixels = true
            val crc = CRC32().also { it.update(typeBytes) }
            if (keep) {
                emitBe32(length)
                emit(typeBytes)
            }
            val small =
                when (type) {
                    "IHDR", "PLTE", "tRNS", "IEND", "acTL", "fcTL", "sRGB", "gAMA", "cHRM", "eXIf" -> true
                    else -> false
                }
            var replacement: ByteArray? = null
            if (small) {
                if (type == "eXIf" && length > 65536) throw UnsupportedImage()
                requireImage(length <= 65536)
                val data = bytes(length.toInt())
                crc.update(data)
                when (type) {
                    "IHDR" -> {
                        requireImage(
                            !header && data.size == 13 && data.uint32(0) in 1..0x7fffffffL &&
                                data.uint32(4) in 1..0x7fffffffL && data[10].toInt() == 0 && data[11].toInt() == 0 &&
                                data[12].toInt() in 0..1,
                        )
                        val depth = data[8].toInt()
                        val color = data[9].toInt()
                        requireImage(
                            when (color) {
                                0 -> depth in setOf(1, 2, 4, 8, 16)
                                2, 4, 6 -> depth in setOf(8, 16)
                                3 -> depth in setOf(1, 2, 4, 8)
                                else -> false
                            },
                        )
                        indexed = color == 3
                        canvasWidth = data.uint32(0)
                        canvasHeight = data.uint32(4)
                        header = true
                    }

                    "PLTE" -> {
                        requireImage(!pixels && !palette && data.isNotEmpty() && data.size <= 768 && data.size % 3 == 0)
                        palette = true
                    }

                    "tRNS" -> {
                        requireImage(!pixels && data.isNotEmpty() && data.size <= 256)
                    }

                    "acTL" -> {
                        requireImage(!animation && !pixels && data.size == 8 && data.uint32(0) > 0)
                        animation = true
                        expectedFrames = data.uint32(0)
                    }

                    "fcTL" -> {
                        requireImage(
                            animation && data.size == 26 && data.uint32(0) == sequence++ &&
                                (!frameControl || frameData) && data.uint32(4) > 0 && data.uint32(8) > 0 &&
                                data.uint32(4) + data.uint32(12) <= canvasWidth &&
                                data.uint32(8) + data.uint32(16) <= canvasHeight &&
                                data[24].toInt() in 0..2 && data[25].toInt() in 0..1,
                        )
                        frames++
                        frameControl = true
                        frameData = false
                    }

                    "sRGB" -> {
                        requireImage(!pixels && data.size == 1 && data[0].toInt() in 0..3)
                    }

                    "gAMA" -> {
                        requireImage(!pixels && data.size == 4 && data.uint32(0) > 0)
                    }

                    "cHRM" -> {
                        requireImage(!pixels && data.size == 32)
                    }

                    "eXIf" -> {
                        requireImage(!hasOrientation)
                        hasOrientation = true
                        replacement = orientationExif(exifOrientation(data) ?: 1).copyOfRange(6, 32)
                    }

                    "IEND" -> {
                        requireImage(data.isEmpty() && pixels && (!animation || frames == expectedFrames && frameData))
                    }
                }
                if (keep) emit(data)
            } else if (type == "fdAT") {
                requireImage(animation && pixels && frameControl && (!firstFrameUsesIdat || frames > 1) && length >= 4)
                val seq = bytes(4)
                requireImage(seq.uint32(0) == sequence++)
                crc.update(seq)
                emit(seq)
                transfer(length - 4, true, crc)
                frameData = true
            } else {
                if (type == "IDAT") {
                    requireImage(!endedPixels && (!indexed || palette))
                    if (!pixels && frameControl) firstFrameUsesIdat = true
                    pixels = true
                    if (frameControl) frameData = true
                }
                transfer(length, keep, crc)
            }
            val expected = input.readInt().toLong() and 0xffffffffL
            requireImage(crc.value == expected)
            replacement?.let { clean ->
                emitBe32(clean.size.toLong())
                val exifType = "eXIf".toByteArray(Charsets.US_ASCII)
                emit(exifType)
                emit(clean)
                val cleanCrc =
                    CRC32().also {
                        it.update(exifType)
                        it.update(clean)
                    }
                emitBe32(cleanCrc.value)
            }
            if (keep) emitBe32(expected)
            if (type == "IEND") return
        }
    }

    suspend fun gif() {
        emit(bytes(6))
        val screen = bytes(7)
        requireImage(screen.le16(0) > 0 && screen.le16(2) > 0)
        emit(screen)
        if ((screen[4].toInt() and 128) != 0) transfer((3 shl ((screen[4].toInt() and 7) + 1)).toLong())
        var frames = 0
        var control = false
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val introducer = byte()) {
                0x3b -> {
                    requireImage(frames > 0 && !control)
                    emit(introducer)
                    return
                }

                0x2c -> {
                    val descriptor = bytes(9)
                    requireImage(descriptor.le16(4) > 0 && descriptor.le16(6) > 0 && (descriptor[8].toInt() and 24) == 0)
                    emit(introducer)
                    emit(descriptor)
                    if ((descriptor[8].toInt() and 128) != 0) transfer((3 shl ((descriptor[8].toInt() and 7) + 1)).toLong())
                    val codeSize = byte()
                    requireImage(codeSize in 2..8)
                    emit(codeSize)
                    subBlocks(true)
                    frames++
                    control = false
                }

                0x21 -> {
                    when (val label = byte()) {
                        0xf9 -> {
                            requireImage(!control && byte() == 4)
                            val data = bytes(4)
                            requireImage((data[0].toInt() and 0xe0) == 0 && ((data[0].toInt() ushr 2) and 7) <= 3 && byte() == 0)
                            emit(introducer)
                            emit(label)
                            emit(4)
                            emit(data)
                            emit(0)
                            control = true
                        }

                        0xff -> {
                            val size = byte()
                            requireImage(size == 11)
                            val application = bytes(size)
                            val name = String(application, Charsets.US_ASCII)
                            if (name == "NETSCAPE2.0" || name == "ANIMEXTS1.0") {
                                requireImage(byte() == 3)
                                val loop = bytes(3)
                                requireImage(loop[0].toInt() == 1 && byte() == 0)
                                emit(introducer)
                                emit(label)
                                emit(11)
                                emit("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
                                emit(3)
                                emit(loop)
                                emit(0)
                            } else {
                                subBlocks(false)
                            }
                        }

                        0xfe -> {
                            subBlocks(false)
                        }

                        0x01 -> {
                            throw UnsupportedImage()
                        }

                        else -> {
                            throw IOException("Unknown GIF extension")
                        }
                    }
                }

                else -> {
                    throw IOException("Malformed GIF block")
                }
            }
        }
    }

    private suspend fun subBlocks(keep: Boolean) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val size = byte()
            if (keep) emit(size)
            if (size == 0) return
            transfer(size.toLong(), keep)
        }
    }

    suspend fun webp() {
        requireImage(String(bytes(4), Charsets.US_ASCII) == "RIFF")
        val end = le32() + 8
        requireImage(end in 12..input.length())
        requireImage(String(bytes(4), Charsets.US_ASCII) == "WEBP")
        val start = input.filePointer
        var cleanSize = 4L
        var extended = false
        var animated = false
        var animationHeader = false
        var frames = 0
        var image = false
        var orientation: Int? = null
        while (input.filePointer < end) {
            currentCoroutineContext().ensureActive()
            requireImage(end - input.filePointer >= 8)
            val type = String(bytes(4), Charsets.US_ASCII)
            val length = le32()
            requireImage(length + (length and 1) <= end - input.filePointer)
            val keep = webpRenderingChunk(type)
            if (keep) cleanSize += 8 + length + (length and 1)
            when (type) {
                "VP8X" -> {
                    requireImage(input.filePointer == start + 8 && length == 10L)
                    val data = bytes(10)
                    requireImage(
                        (data[0].toInt() and 0xc1) == 0 && data[1].toInt() == 0 &&
                            data[2].toInt() == 0 && data[3].toInt() == 0,
                    )
                    if ((data[0].toInt() and 32) != 0) throw UnsupportedImage()
                    extended = true
                    animated = (data[0].toInt() and 2) != 0
                }

                "ICCP" -> {
                    throw UnsupportedImage()
                }

                "EXIF" -> {
                    if (length > 65536) throw UnsupportedImage()
                    var data = bytes(length.toInt())
                    if (data.startsWithAscii("Exif\u0000\u0000")) data = data.copyOfRange(6, data.size)
                    val value = exifOrientation(data) ?: 1
                    requireImage(orientation == null || orientation == value)
                    orientation = value
                }

                "ANIM" -> {
                    requireImage(extended && animated && !animationHeader && frames == 0 && length == 6L)
                    animationHeader = true
                    transfer(length, false)
                }

                "ANMF" -> {
                    requireImage(animated && animationHeader && length >= 16)
                    val frameEnd = input.filePointer + length
                    val header = bytes(16)
                    requireImage((header[15].toInt() and 0xfc) == 0)
                    var frameImage = false
                    while (input.filePointer < frameEnd) {
                        currentCoroutineContext().ensureActive()
                        requireImage(frameEnd - input.filePointer >= 8)
                        val nested = String(bytes(4), Charsets.US_ASCII)
                        val count = le32()
                        requireImage(count + (count and 1) <= frameEnd - input.filePointer)
                        if (nested != "ALPH" && nested != "VP8 " && nested != "VP8L") throw UnsupportedImage()
                        if (nested != "ALPH") {
                            requireImage(!frameImage)
                            frameImage = true
                            webpPixels(nested, count)
                        } else {
                            requireImage(!frameImage)
                            transfer(count, false)
                        }
                        if (count and 1L != 0L) byte()
                    }
                    requireImage(frameImage)
                    frames++
                }

                "VP8 ", "VP8L" -> {
                    requireImage(!animated && !image)
                    webpPixels(type, length)
                    image = true
                }

                "ALPH" -> {
                    requireImage(extended && !animated && !image)
                    transfer(length, false)
                }

                else -> {
                    transfer(length, false)
                }
            }
            if (length and 1L != 0L) byte()
        }
        requireImage(input.filePointer == end && if (animated) frames > 0 else image)
        if (orientation != null) {
            requireImage(extended)
            cleanSize += 34
        }
        if (output == null) return
        emit("RIFF".toByteArray(Charsets.US_ASCII))
        emitLe32(cleanSize)
        emit("WEBP".toByteArray(Charsets.US_ASCII))
        input.seek(start)
        while (input.filePointer < end) {
            currentCoroutineContext().ensureActive()
            val type = bytes(4)
            val name = String(type, Charsets.US_ASCII)
            val length = le32()
            val keep = webpRenderingChunk(name)
            if (keep) {
                emit(type)
                emitLe32(length)
            }
            if (name == "VP8X") {
                val data = bytes(10)
                data[0] = ((data[0].toInt() and 0xf3) or if (orientation != null) 8 else 0).toByte()
                emit(data)
            } else {
                transfer(length, keep)
            }
            if (length and 1L != 0L) {
                byte()
                if (keep) emit(0)
            }
        }
        orientation?.let {
            emit("EXIF".toByteArray(Charsets.US_ASCII))
            emitLe32(26)
            emit(orientationExif(it).copyOfRange(6, 32))
        }
    }

    private suspend fun webpPixels(
        type: String,
        length: Long,
    ) {
        val minimum = if (type == "VP8L") 5 else 10
        requireImage(length >= minimum)
        val header = bytes(minimum)
        if (type == "VP8L") {
            requireImage(header[0].toInt() == 0x2f && (header[4].toInt() and 0xe0) == 0)
        } else {
            requireImage(
                (header[0].toInt() and 1) == 0 && header[3].toInt() == 0x9d.toByte().toInt() &&
                    header[4].toInt() == 1 && header[5].toInt() == 0x2a &&
                    (header.le16(6) and 0x3fff) > 0 && (header.le16(8) and 0x3fff) > 0,
            )
        }
        transfer(length - minimum, false)
    }
}

private fun ByteArray.startsWithAscii(value: String): Boolean = size >= value.length && value.indices.all { this[it].toInt() == value[it].code }

private fun ByteArray.le16(offset: Int): Int = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)

private fun ByteArray.uint32(offset: Int): Long =
    (0..3).fold(0L) { result, index ->
        (result shl 8) or (this[offset + index].toLong() and 255)
    }

private fun exifOrientation(data: ByteArray): Int? {
    fun valid(condition: Boolean) {
        if (!condition) throw IOException("Malformed EXIF orientation")
    }
    valid(data.size >= 8)
    val little = data[0].toInt() == 73 && data[1].toInt() == 73
    valid(little || data[0].toInt() == 77 && data[1].toInt() == 77)

    fun short(offset: Int): Int {
        valid(offset >= 0 && offset <= data.size - 2)
        return if (little) data.le16(offset) else ((data[offset].toInt() and 255) shl 8) or (data[offset + 1].toInt() and 255)
    }

    fun long(offset: Int): Long {
        valid(offset >= 0 && offset <= data.size - 4)
        return if (little) short(offset).toLong() or (short(offset + 2).toLong() shl 16) else data.uint32(offset)
    }
    valid(short(2) == 42)
    val offset = long(4)
    valid(offset in 8..(data.size - 2).toLong())
    val count = short(offset.toInt())
    valid(offset + 2 + count * 12L + 4 <= data.size)
    var orientation: Int? = null
    repeat(count) { index ->
        val entry = offset.toInt() + 2 + index * 12
        if (short(entry) == 0x112) {
            valid(short(entry + 2) == 3 && long(entry + 4) == 1L)
            val value = short(entry + 8)
            if (value == 0) return@repeat
            valid(value in 1..8 && (orientation == null || orientation == value))
            orientation = value
        }
    }
    return orientation
}

private fun orientationExif(value: Int): ByteArray =
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
        value.toByte(),
        0,
        0,
        0,
        0,
        0,
        0,
        0,
    )
