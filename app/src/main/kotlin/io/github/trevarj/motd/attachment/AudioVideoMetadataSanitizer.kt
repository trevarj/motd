package io.github.trevarj.motd.attachment

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

private const val MAX_COMPRESSED_SAMPLE_BYTES = 4 * 1024 * 1024

internal suspend fun stripAudioVideoMetadata(
    input: File,
    output: File,
): SanitizedMedia? {
    currentCoroutineContext().ensureActive()
    val prefix =
        input.inputStream().use { stream ->
            val bytes = ByteArray(4096)
            var size = 0
            while (size < bytes.size) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            bytes.copyOf(size)
        }
    if (prefix.startsWithBytes("OggS".toByteArray())) return stripOgg(input, output)
    val webm = prefix.startsWithBytes(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()))
    val mp4 = prefix.size >= 8 && String(prefix, 4, 4, Charsets.US_ASCII) == "ftyp"
    if (!webm && !mp4) return null
    val webmDocType = byteArrayOf(0x42, 0x82.toByte(), 0x84.toByte(), 0x77, 0x65, 0x62, 0x6d)
    if (webm &&
        !(0..prefix.size - webmDocType.size).any { offset ->
            webmDocType.indices.all { prefix[offset + it] == webmDocType[it] }
        }
    ) {
        return null
    }
    if (mp4) {
        val declaredSize = ByteBuffer.wrap(prefix, 0, 4).int.toLong() and 0xffffffffL
        val extended = declaredSize == 1L
        val headerSize = if (extended) 16 else 8
        if (prefix.size < headerSize) throw IOException("Truncated media file type box")
        val size =
            when (declaredSize) {
                0L -> input.length()
                1L -> ByteBuffer.wrap(prefix, 8, 8).long
                else -> declaredSize
            }
        if (size < headerSize + 8L || size > input.length() || (size - headerSize - 8) % 4 != 0L) {
            throw IOException("Invalid media file type box")
        }
        if (extended || size > prefix.size) return null
        val brands =
            (headerSize until size.toInt() step 4)
                .filter { it != headerSize + 4 }
                .map { String(prefix, it, 4, Charsets.US_ASCII) }
        if (brands.none { it in setOf("isom", "iso2", "mp41", "mp42", "avc1", "M4A ", "M4V ") || it.startsWith("3gp") }) return null
    }
    return remuxMedia(input, output, webm)
}

private suspend fun remuxMedia(
    input: File,
    output: File,
    webm: Boolean,
): SanitizedMedia? {
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    try {
        extractor.setDataSource(input.absolutePath)
        val tracks = mutableListOf<Pair<Int, MediaFormat>>()
        var rotation: Int? = null
        var audioTracks = 0
        for (index in 0 until extractor.trackCount) {
            currentCoroutineContext().ensureActive()
            val original = extractor.getTrackFormat(index)
            val mime = original.getString(MediaFormat.KEY_MIME) ?: throw IOException("Missing track codec")
            if (!mime.startsWith("audio/") && !mime.startsWith("video/")) {
                if (mime == "application/id3") continue
                return null
            }
            if (Build.VERSION.SDK_INT < 28 && original.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) &&
                original.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) > MAX_COMPRESSED_SAMPLE_BYTES
            ) {
                return null
            }
            val allowed =
                if (webm) {
                    setOf("video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "audio/opus", "audio/vorbis")
                } else {
                    setOf("video/avc", "video/hevc", "video/mp4v-es", "audio/mp4a-latm", "audio/3gpp", "audio/amr-wb")
                }
            if (mime !in allowed) return null
            if (webm && mime == "audio/opus" && Build.VERSION.SDK_INT < 29) return null
            if (mime.startsWith("audio/")) {
                audioTracks++
                if (webm && audioTracks > 1) return null
            }
            if (mime.startsWith("video/")) {
                if (rotation != null) return null
                rotation = if (original.containsKey(MediaFormat.KEY_ROTATION)) original.getInteger(MediaFormat.KEY_ROTATION) else 0
                if (rotation !in listOf(0, 90, 180, 270)) throw IOException("Invalid video rotation")
            }
            val clean = MediaFormat()
            clean.setString(MediaFormat.KEY_MIME, mime)
            for (key in listOf(
                "width",
                "height",
                "sample-rate",
                "channel-count",
                "bitrate",
                "profile",
                "level",
                "color-standard",
                "color-transfer",
                "color-range",
                "encoder-delay",
                "encoder-padding",
                "aac-profile",
                "pcm-encoding",
                "sar-width",
                "sar-height",
                "crop-left",
                "crop-right",
                "crop-top",
                "crop-bottom",
            )) {
                if (original.containsKey(key)) clean.setInteger(key, original.getInteger(key))
            }
            if (original.containsKey("frame-rate")) {
                try {
                    clean.setInteger("frame-rate", original.getInteger("frame-rate"))
                } catch (_: ClassCastException) {
                    clean.setFloat("frame-rate", original.getFloat("frame-rate"))
                }
            }
            for (key in listOf("csd-0", "csd-1", "csd-2", "hdr-static-info")) {
                original.getByteBuffer(key)?.let { clean.setByteBuffer(key, it) }
            }
            tracks += index to clean
        }
        if (tracks.isEmpty()) throw IOException("Media contains no audio or video")
        val writer =
            MediaMuxer(
                output.absolutePath,
                if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
        muxer = writer
        rotation?.let {
            if (webm && it != 0) return null
            if (!webm) writer.setOrientationHint(it)
        }
        val mapping =
            tracks.associate { (index, format) ->
                extractor.selectTrack(index)
                index to writer.addTrack(format)
            }
        writer.start()
        // ponytail: cap compressed samples at 4 MiB; larger frames need a separately bounded policy.
        val buffer = ByteBuffer.allocateDirect(MAX_COMPRESSED_SAMPLE_BYTES)
        val info = MediaCodec.BufferInfo()
        var samples = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val track = extractor.sampleTrackIndex
            if (track < 0) break
            if (extractor.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                return null
            }
            if (Build.VERSION.SDK_INT >= 28 && extractor.sampleSize > buffer.capacity()) return null
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) throw IOException("Invalid compressed sample")
            if (size > buffer.capacity()) return null
            val timestamp = extractor.sampleTime
            if (timestamp < 0) throw IOException("Invalid media timestamp")
            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            info.set(0, size, timestamp, flags)
            writer.writeSampleData(mapping.getValue(track), buffer, info)
            samples++
            if (!extractor.advance()) break
        }
        if (samples == 0L) throw IOException("Media contains no samples")
        writer.stop()
        return if (webm) {
            SanitizedMedia(if (rotation != null) "video/webm" else "audio/webm", ".webm")
        } else {
            SanitizedMedia(if (rotation != null) "video/mp4" else "audio/mp4", if (rotation != null) ".mp4" else ".m4a")
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: RuntimeException) {
        currentCoroutineContext().ensureActive()
        throw IOException("Could not sanitize media", error)
    } finally {
        try {
            muxer?.release()
        } finally {
            extractor.release()
        }
    }
}

private data class OggPage(
    val header: ByteArray,
    val laces: ByteArray,
    val body: ByteArray,
)

private suspend fun readOggPage(stream: DataInputStream): OggPage? {
    currentCoroutineContext().ensureActive()
    val first = stream.read()
    if (first < 0) return null
    val header = ByteArray(27)
    header[0] = first.toByte()
    stream.readFully(header, 1, 26)
    if (!header.startsWithBytes("OggS".toByteArray()) || header[4] != 0.toByte() || header[5].toInt() and 0xf8 != 0) {
        throw IOException("Invalid Ogg page")
    }
    val laces = ByteArray(header[26].toInt() and 255)
    stream.readFully(laces)
    val body = ByteArray(laces.sumOf { it.toInt() and 255 })
    stream.readFully(body)
    val expected = header.copyOfRange(22, 26)
    header.fill(0, 22, 26)
    val crc = oggCrc(header, laces, body)
    if ((0..3).any { expected[it] != (crc ushr (8 * it)).toByte() }) throw IOException("Invalid Ogg checksum")
    return OggPage(header, laces, body)
}

private suspend fun stripOgg(
    input: File,
    output: File,
): SanitizedMedia? {
    try {
        DataInputStream(input.inputStream().buffered()).use { source ->
            output.outputStream().buffered().use { target ->
                val first = readOggPage(source) ?: throw IOException("Empty Ogg stream")
                if (first.header[5].toInt() != 2 || first.laces.isEmpty() || first.laces.last().toInt() and 255 == 255 ||
                    first.laces.dropLast(1).any { it.toInt() and 255 != 255 }
                ) {
                    throw IOException("Invalid Ogg identification page")
                }
                val opus = first.body.startsWithBytes("OpusHead".toByteArray())
                val vorbis = first.body.startsWithBytes(byteArrayOf(1) + "vorbis".toByteArray())
                if (!opus && !vorbis) return null
                if (opus) {
                    if (first.body.size < 19) throw IOException("Truncated Opus identification")
                    if (first.body[8] != 1.toByte()) return null
                    val channels = first.body[9].toInt() and 255
                    if (channels == 0) throw IOException("Invalid Opus channel count")
                    if (first.body[18] != 0.toByte()) {
                        if (first.body.size < 21 + channels) throw IOException("Truncated Opus channel mapping")
                        return null
                    }
                    if (first.body.size != 19 || channels > 2) throw IOException("Invalid Opus identification")
                }
                if (vorbis && first.body.size != 30) throw IOException("Invalid Vorbis identification")
                if (vorbis && (
                        littleInt(first.body, 7) != 0 || first.body[11] == 0.toByte() ||
                            littleInt(first.body, 12) <= 0 || first.body[29] != 1.toByte()
                    )
                ) {
                    throw IOException("Invalid Vorbis identification")
                }
                val serial = first.header.copyOfRange(14, 18)
                var inputSequence = littleInt(first.header, 18)
                if (inputSequence != 0) throw IOException("Invalid Ogg sequence")
                var outputSequence = 0

                fun emit(page: OggPage) {
                    val header = page.header.copyOf()
                    putLittleInt(header, 18, outputSequence++)
                    header[26] = page.laces.size.toByte()
                    header.fill(0, 22, 26)
                    putLittleInt(header, 22, oggCrc(header, page.laces, page.body))
                    target.write(header)
                    target.write(page.laces)
                    target.write(page.body)
                }
                emit(first)
                val comment = ByteArrayOutputStream()
                var commentsDone = false
                var continued = false
                var ended = false
                var completedPackets = 1L
                while (true) {
                    val page = readOggPage(source) ?: break
                    if (!page.header.copyOfRange(14, 18).contentEquals(serial)) return null
                    if (ended || page.header[5].toInt() and 2 != 0 || littleInt(page.header, 18) != ++inputSequence ||
                        (page.header[5].toInt() and 1 != 0) != continued
                    ) {
                        throw IOException("Invalid Ogg stream sequence")
                    }
                    ended = page.header[5].toInt() and 4 != 0
                    completedPackets += page.laces.count { it.toInt() and 255 != 255 }
                    continued = page.laces.lastOrNull()?.let { it.toInt() and 255 == 255 } ?: continued
                    if (commentsDone) {
                        emit(page)
                        continue
                    }
                    var length = 0
                    var end = -1
                    for (index in page.laces.indices) {
                        length += page.laces[index].toInt() and 255
                        if (page.laces[index].toInt() and 255 != 255) {
                            end = index
                            break
                        }
                    }
                    if (comment.size() + length > 1024 * 1024) throw IOException("Ogg comments exceed 1 MiB")
                    comment.write(page.body, 0, length)
                    if (end < 0) continue
                    val clean = cleanOggComment(comment.toByteArray(), opus)
                    val tail = page.laces.copyOfRange(end + 1, page.laces.size)
                    val laces = byteArrayOf(clean.size.toByte()) + tail
                    if (laces.size > 255) throw IOException("Invalid Ogg comment page")
                    val header = page.header.copyOf()
                    header[5] = (header[5].toInt() and 1.inv()).toByte()
                    emit(OggPage(header, laces, clean + page.body.copyOfRange(length, page.body.size)))
                    commentsDone = true
                }
                if (!commentsDone || !ended || continued || completedPackets < (if (opus) 3 else 4)) {
                    throw IOException("Truncated Ogg audio")
                }
            }
        }
    } catch (error: EOFException) {
        throw IOException("Truncated Ogg page", error)
    }
    return SanitizedMedia("audio/ogg", ".ogg")
}

private suspend fun cleanOggComment(
    packet: ByteArray,
    opus: Boolean,
): ByteArray {
    val signature = if (opus) "OpusTags".toByteArray() else byteArrayOf(3) + "vorbis".toByteArray()
    if (!packet.startsWithBytes(signature)) throw IOException("Missing Ogg comments")
    var offset = signature.size

    fun readLength(): Int {
        if (packet.size - offset < 4) throw IOException("Invalid Ogg comment length")
        val length = littleInt(packet, offset)
        offset += 4
        if (length < 0) throw IOException("Invalid Ogg comment length")
        return length
    }

    fun skip(length: Int) {
        if (length > packet.size - offset) throw IOException("Truncated Ogg comments")
        offset += length
    }
    skip(readLength())
    val count = readLength()
    if (count > (packet.size - offset) / 4) throw IOException("Invalid Ogg comment count")
    repeat(count) {
        currentCoroutineContext().ensureActive()
        skip(readLength())
    }
    if (!opus && (offset != packet.lastIndex || packet[offset] != 1.toByte())) throw IOException("Invalid Vorbis comment framing")
    return signature + ByteArray(8) + if (opus) ByteArray(0) else byteArrayOf(1)
}

private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun littleInt(
    bytes: ByteArray,
    offset: Int,
): Int = (0..3).fold(0) { value, index -> value or ((bytes[offset + index].toInt() and 255) shl (8 * index)) }

private fun putLittleInt(
    bytes: ByteArray,
    offset: Int,
    value: Int,
) {
    for (index in 0..3) bytes[offset + index] = (value ushr (8 * index)).toByte()
}

private val oggCrcTable =
    IntArray(256) { index ->
        var value = index shl 24
        repeat(8) { value = if (value < 0) (value shl 1) xor 0x04c11db7 else value shl 1 }
        value
    }

private fun oggCrc(vararg chunks: ByteArray): Int {
    var crc = 0
    for (chunk in chunks) {
        for (byte in chunk) {
            crc = (crc shl 8) xor oggCrcTable[((crc ushr 24) xor (byte.toInt() and 255)) and 255]
        }
    }
    return crc
}
