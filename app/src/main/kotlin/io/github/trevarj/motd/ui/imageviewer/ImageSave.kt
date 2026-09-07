package io.github.trevarj.motd.ui.imageviewer

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

internal const val IMAGE_SAVE_MAX_BYTES = 25L * 1024L * 1024L
private const val IMAGE_SAVE_BUFFER_BYTES = 8 * 1024

/** A streaming response boundary that makes save failures testable without Android. */
internal interface ImageSaveConnection {
    val responseCode: Int
    val contentLength: Long
    val contentType: String?

    fun header(name: String): String?

    fun openInputStream(): InputStream

    fun disconnect()
}

internal fun interface ImageSaveConnectionFactory {
    fun open(url: String): ImageSaveConnection
}

/** A MediaStore transaction boundary. A location exists only after a pending row was inserted. */
internal interface ImageSaveStore<Location : Any> {
    fun insert(metadata: ImageSaveMetadata): Location?

    fun openOutputStream(location: Location): OutputStream?

    fun publish(location: Location): Boolean

    fun delete(location: Location)
}

internal data class ImageSaveMetadata(
    val displayName: String,
    val mimeType: String,
)

internal sealed interface ImageSaveResult {
    data object Saved : ImageSaveResult

    data object Failed : ImageSaveResult
}

/** The UI must not claim success until the MediaStore row has been published. */
internal enum class ImageSaveFeedback { SAVED, FAILED }

internal fun ImageSaveResult.feedback(): ImageSaveFeedback =
    when (this) {
        ImageSaveResult.Saved -> ImageSaveFeedback.SAVED
        ImageSaveResult.Failed -> ImageSaveFeedback.FAILED
    }

/**
 * Streams an image response into a pending MediaStore row. Every failure after insert removes the
 * row, including cancellation, so gallery apps never observe an incomplete image.
 */
internal class ImageSaveOperation<Location : Any>(
    private val connectionFactory: ImageSaveConnectionFactory,
    private val store: ImageSaveStore<Location>,
    private val maxBytes: Long = IMAGE_SAVE_MAX_BYTES,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    suspend fun save(url: String): ImageSaveResult =
        suspendCancellableCoroutine { continuation ->
            continuation.resumeWith(runCatching { save(url, continuation) })
        }

    private fun save(
        url: String,
        continuation: CancellableContinuation<ImageSaveResult>,
    ): ImageSaveResult {
        var connection: ImageSaveConnection? = null
        var location: Location? = null
        var published = false
        val disconnected = AtomicBoolean(false)

        fun disconnectOnce() {
            if (disconnected.compareAndSet(false, true)) connection?.disconnect()
        }

        try {
            continuation.context.ensureActive()
            val response = connectionFactory.open(url)
            connection = response
            continuation.invokeOnCancellation { disconnectOnce() }
            continuation.context.ensureActive()
            if (response.responseCode !in 200..299) {
                return ImageSaveResult.Failed
            }
            if (response.contentLength > maxBytes) return ImageSaveResult.Failed

            val metadata =
                imageSaveMetadata(response.contentType, response.header("Content-Disposition"))
                    ?: return ImageSaveResult.Failed
            val inserted = store.insert(metadata) ?: return ImageSaveResult.Failed
            location = inserted
            val output = store.openOutputStream(inserted) ?: return ImageSaveResult.Failed
            output.use { destination ->
                response.openInputStream().use { input -> copyBounded(input, destination, continuation.context) }
            }
            continuation.context.ensureActive()
            if (!store.publish(inserted)) return ImageSaveResult.Failed
            published = true
            return ImageSaveResult.Saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ImageSaveResult.Failed
        } finally {
            if (!published) location?.let { inserted -> runCatching { store.delete(inserted) } }
            disconnectOnce()
        }
    }

    private fun copyBounded(
        input: InputStream,
        output: OutputStream,
        context: CoroutineContext,
    ) {
        val buffer = ByteArray(IMAGE_SAVE_BUFFER_BYTES)
        var written = 0L
        while (true) {
            context.ensureActive()
            val count = input.read(buffer)
            if (count < 0) return
            if (written + count > maxBytes) throw ImageSaveTooLargeException()
            output.write(buffer, 0, count)
            written += count
        }
    }
}

private class ImageSaveTooLargeException : Exception()

private fun imageSaveMetadata(
    contentType: String?,
    contentDisposition: String?,
): ImageSaveMetadata? {
    val responseMimeType =
        contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
    // A generic response type is ambiguous, but an explicit non-image response must never be
    // written to Images as a fabricated JPEG (for example, an HTML error document with HTTP 200).
    if (responseMimeType != null && responseMimeType !in imageMimeExtensions && responseMimeType != "application/octet-stream") {
        return null
    }
    val mimeType = responseMimeType?.takeIf { it in imageMimeExtensions } ?: "image/jpeg"
    val extension = imageMimeExtensions.getValue(mimeType)
    val suppliedBaseName =
        contentDisposition
            ?.split(';')
            ?.firstNotNullOfOrNull { parameter ->
                parameter
                    .trim()
                    .takeIf { it.startsWith("filename=", ignoreCase = true) }
                    ?.substringAfter('=')
                    ?.trim()
                    ?.removeSurrounding("\"")
            }?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringBeforeLast('.', missingDelimiterValue = "")
            ?.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
            ?.trim('.')
            ?.takeIf { it.isNotEmpty() }
            ?.take(80)
            ?: "motd-image"
    return ImageSaveMetadata(displayName = "$suppliedBaseName.$extension", mimeType = mimeType)
}

private val imageMimeExtensions =
    mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/bmp" to "bmp",
        "image/x-ms-bmp" to "bmp",
        "image/heic" to "heic",
        "image/heic-sequence" to "heic",
        "image/heif" to "heif",
        "image/heif-sequence" to "heif",
    )

internal class OkHttpImageSaveConnectionFactory(
    private val calls: Call.Factory,
) : ImageSaveConnectionFactory {
    override fun open(url: String): ImageSaveConnection = OkHttpImageSaveConnection(calls.newCall(Request.Builder().url(url).build()))
}

private class OkHttpImageSaveConnection(
    private val call: Call,
) : ImageSaveConnection {
    @Volatile private var response: Response? = null

    private fun response(): Response =
        response ?: call.execute().also {
            response = it
            if (call.isCanceled()) {
                it.close()
                throw IOException("Image save cancelled")
            }
        }

    override val responseCode: Int get() = response().code
    override val contentLength: Long get() = response().body.contentLength()
    override val contentType: String? get() = response().header("Content-Type")

    override fun header(name: String): String? = response().header(name)

    override fun openInputStream(): InputStream = response().body.byteStream()

    override fun disconnect() {
        call.cancel()
        response?.close()
    }
}

/** Android adapter that keeps a Q+ MediaStore row hidden until the complete copy is finalized. */
@RequiresApi(Build.VERSION_CODES.Q)
internal class MediaStoreImageSaveStore(
    private val resolver: ContentResolver,
) : ImageSaveStore<Uri> {
    override fun insert(metadata: ImageSaveMetadata): Uri? {
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, metadata.displayName)
                put(MediaStore.Images.Media.MIME_TYPE, metadata.mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/motd")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    override fun openOutputStream(location: Uri): OutputStream? = resolver.openOutputStream(location)

    override fun publish(location: Uri): Boolean =
        resolver.update(
            location,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        ) > 0

    override fun delete(location: Uri) {
        resolver.delete(location, null, null)
    }
}
