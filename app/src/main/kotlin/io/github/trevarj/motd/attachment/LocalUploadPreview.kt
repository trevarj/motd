package io.github.trevarj.motd.attachment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer

internal const val UPLOAD_PREVIEW_THUMBNAIL_EDGE = 192
internal const val MAX_UPLOAD_PREVIEW_BASE64_LENGTH = 20 * 1024
private const val MAX_SNIPPET_LENGTH = 240
private const val MAX_TEXT_SCAN = MAX_SNIPPET_LENGTH * 4
private const val MAX_IMAGE_PIXELS = 100_000_000L
private const val MAX_ANIMATED_IMAGE_PIXELS = 16_777_216L
private const val MAX_VIDEO_PIXELS = 4_194_304L
private const val MAX_PDF_BYTES = 8L * 1024 * 1024

internal fun LocalUploadPreview.isBounded(): Boolean =
    (thumbnailBase64?.length ?: 0) <= MAX_UPLOAD_PREVIEW_BASE64_LENGTH &&
        (textSnippet?.length ?: 0) <= MAX_SNIPPET_LENGTH &&
        (durationMillis ?: 0) >= 0

/** The uploader only passes the live prepared source; never reopen a provider URI or a returned URL. */
internal fun localUploadPreview(source: AttachmentSource): LocalUploadPreview? =
    previewOrNull {
        when (source) {
            is AttachmentSource.Text -> {
                snippet(source.text)?.let { LocalUploadPreview(textSnippet = it) }
            }

            is AttachmentSource.LocalFile -> {
                val mime =
                    source.mimeType
                        .substringBefore(';')
                        .trim()
                        .lowercase()
                when {
                    mime.startsWith("image/") -> imageThumbnail(source.file)?.let { LocalUploadPreview(thumbnailBase64 = it) }
                    mime.startsWith("video/") -> mediaPreview(source.file, video = true)
                    mime.startsWith("audio/") -> mediaPreview(source.file, video = false)
                    mime == "application/pdf" -> pdfThumbnail(source.file)?.let { LocalUploadPreview(thumbnailBase64 = it) }
                    mime.startsWith("text/") || mime in textApplicationTypes -> readSnippet(source.file)?.let { LocalUploadPreview(textSnippet = it) }
                    else -> null
                }
            }

            else -> {
                null
            }
        }
    }

private val textApplicationTypes =
    setOf(
        "application/json",
        "application/xml",
        "application/javascript",
        "application/x-javascript",
        "application/ecmascript",
        "application/x-sh",
        "application/x-shellscript",
    )

private inline fun <T> previewOrNull(block: () -> T?): T? =
    try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        // A native decoder can exhaust memory on malformed input despite bounded output.
        null
    }

private fun imageThumbnail(file: File): String? {
    if (Build.VERSION.SDK_INT >= 28) {
        val bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val sample = imageSample(info.size.width, info.size.height, info.isAnimated || info.mimeType == "image/gif")
                if (sample > 1) decoder.setTargetSampleSize(sample)
            }
        return bitmap.useBitmap { encodeThumbnail() }
    }

    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    options.inSampleSize = imageSample(options.outWidth, options.outHeight, options.outMimeType == "image/gif")
    val jpeg = options.outMimeType == "image/jpeg"
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeFile(file.path, options)?.useBitmap {
        if (!jpeg) return@useBitmap encodeThumbnail()
        val orientation =
            previewOrNull {
                file.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            }
        if (orientation == null || orientation !in 2..8) return@useBitmap encodeThumbnail()
        val transform = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                transform.setScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_180 -> {
                transform.setRotate(180f)
            }

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                transform.setScale(1f, -1f)
            }

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                transform.setRotate(90f)
                transform.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> {
                transform.setRotate(90f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                transform.setRotate(-90f)
                transform.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> {
                transform.setRotate(-90f)
            }
        }
        if (transform.isIdentity) {
            encodeThumbnail()
        } else {
            Bitmap.createBitmap(this, 0, 0, width, height, transform, true).useBitmap { encodeThumbnail() }
        }
    }
}

private fun imageSample(
    width: Int,
    height: Int,
    animated: Boolean,
): Int {
    val pixelLimit = if (animated) MAX_ANIMATED_IMAGE_PIXELS else MAX_IMAGE_PIXELS
    require(width in 1..32768 && height in 1..32768 && width.toLong() * height <= pixelLimit)
    val longest = maxOf(width, height)
    var sample = 1
    while (longest / sample >= UPLOAD_PREVIEW_THUMBNAIL_EDGE * 2) sample *= 2
    return sample
}

private fun mediaPreview(
    file: File,
    video: Boolean,
): LocalUploadPreview? =
    file.inputStream().use { input ->
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(input.fd)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
            val thumbnail = if (video && Build.VERSION.SDK_INT >= 27) previewOrNull { videoThumbnail(retriever) } else null
            if (duration == null && thumbnail == null) null else LocalUploadPreview(thumbnailBase64 = thumbnail, durationMillis = duration)
        } finally {
            retriever.release()
        }
    }

@RequiresApi(27)
private fun videoThumbnail(retriever: MediaMetadataRetriever): String? {
    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
    // Scaled retrieval bounds the returned bitmap, not the native codec's working frame.
    if (width !in 1..4096 || height !in 1..4096 || width.toLong() * height > MAX_VIDEO_PIXELS) return null
    return retriever
        .getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, UPLOAD_PREVIEW_THUMBNAIL_EDGE, UPLOAD_PREVIEW_THUMBNAIL_EDGE)
        ?.useBitmap { encodeThumbnail() }
}

private fun pdfThumbnail(file: File): String? {
    if (file.length() !in 1..MAX_PDF_BYTES) return null
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { page ->
                val (width, height) = fitDimensions(page.width, page.height, UPLOAD_PREVIEW_THUMBNAIL_EDGE)
                createBitmap(width, height).useBitmap {
                    eraseColor(Color.WHITE)
                    page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    encodeThumbnail()
                }
            }
        }
    }
}

private fun fitDimensions(
    width: Int,
    height: Int,
    edge: Int,
): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val longest = maxOf(width, height, edge).toLong()
    return (width * edge.toLong() / longest).toInt().coerceAtLeast(1) to
        (height * edge.toLong() / longest).toInt().coerceAtLeast(1)
}

@Suppress("DEPRECATION") // The legacy WebP encoder is available at minSdk 26 and preserves alpha.
private fun Bitmap.encodeThumbnail(): String? {
    val bytes = ByteArrayOutputStream()
    var edge = UPLOAD_PREVIEW_THUMBNAIL_EDGE
    while (edge >= UPLOAD_PREVIEW_THUMBNAIL_EDGE / 2) {
        val (width, height) = fitDimensions(this.width, this.height, edge)
        val scaled = if (width == this.width && height == this.height) this else scale(width, height)
        try {
            if (!scaled.compress(Bitmap.CompressFormat.WEBP, 75, bytes)) return null
            if (bytes.size() <= MAX_UPLOAD_PREVIEW_BASE64_LENGTH / 4 * 3) return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
            bytes.reset()
        } finally {
            if (scaled !== this) scaled.recycle()
        }
        edge /= 2
    }
    return null
}

private inline fun <T> Bitmap.useBitmap(block: Bitmap.() -> T): T =
    try {
        block()
    } finally {
        recycle()
    }

private fun readSnippet(file: File): String? {
    val size = file.length()
    val bytes = ByteArray(minOf(size, MAX_TEXT_SCAN.toLong()).toInt())
    DataInputStream(file.inputStream()).use { it.readFully(bytes) }
    val chars = CharBuffer.allocate(bytes.size)
    // A scan ending mid-codepoint is fine; malformed UTF-8 within the scan is not text.
    val result = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes), chars, size <= MAX_TEXT_SCAN)
    if (result.isError) return null
    // Use the base Buffer method on Android versions without CharBuffer's covariant override.
    (chars as java.nio.Buffer).flip()
    return snippet(chars)
}

private fun snippet(text: CharSequence): String? {
    val cleaned = StringBuilder(MAX_SNIPPET_LENGTH)
    var index = 0
    var space = false
    while (index < minOf(text.length, MAX_TEXT_SCAN) && cleaned.length < MAX_SNIPPET_LENGTH) {
        val codePoint = Character.codePointAt(text, index)
        val chars = Character.charCount(codePoint)
        index += chars
        when {
            Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> {
                space = cleaned.isNotEmpty()
            }

            Character.isISOControl(codePoint) || codePoint in 0xd800..0xdfff -> {
                continue
            }

            Character.getType(codePoint) == Character.FORMAT.toInt() && codePoint !in 0x200c..0x200d -> {
                continue
            }

            else -> {
                if (cleaned.length + chars + (if (space) 1 else 0) > MAX_SNIPPET_LENGTH) break
                if (space) cleaned.append(' ')
                cleaned.appendCodePoint(codePoint)
                space = false
            }
        }
    }
    return cleaned.toString().takeIf(String::isNotEmpty)
}
