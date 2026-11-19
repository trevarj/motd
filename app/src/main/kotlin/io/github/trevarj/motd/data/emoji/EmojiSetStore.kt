package io.github.trevarj.motd.data.emoji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

data class EmojiDownloadProgress(
    val id: String,
    val bytes: Long,
    val totalBytes: Long,
)

/** Owns downloaded binaries only; selecting a set belongs to appearance preferences. */
@Singleton
class EmojiSetStore internal constructor(
    context: Context,
    private val sets: List<EmojiSetSpec>,
    private val openConnection: (URL) -> HttpURLConnection,
    private val renderSample: String = "😀",
    private val requireColor: Boolean = true,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        context,
        EmojiSets.all,
        { it.openConnection() as HttpURLConnection },
    )

    private val directory = File(context.filesDir, "emoji-sets")
    private val mutex = Mutex()
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()
    private val mutableProgress = MutableStateFlow<EmojiDownloadProgress?>(null)
    val progress: StateFlow<EmojiDownloadProgress?> = mutableProgress.asStateFlow()

    private data class Validation(
        val fingerprint: Fingerprint?,
        val valid: Boolean,
    )

    private val validations = mutableMapOf<String, Validation>()

    suspend fun installedFile(id: String): File? =
        withContext(Dispatchers.IO) {
            mutex.withLock { sets.find { it.id == id }?.let { installed(it) } }
        }

    suspend fun download(id: String): Result<File> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val spec =
                    sets.find { it.id == id }
                        ?: return@withLock Result.failure(IOException("Unknown emoji set"))
                var temporary: File? = null
                try {
                    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create emoji directory")
                    temporary = File.createTempFile("${spec.id}-", ".tmp", directory)
                    mutableProgress.value = EmojiDownloadProgress(id, 0, spec.sizeBytes)
                    fetch(spec, temporary)
                    currentCoroutineContext().ensureActive()
                    validateFont(temporary)
                    // Pinned identical bytes need no publication or revision change.
                    val previous = installed(spec)
                    if (previous != null) return@withLock Result.success(previous)
                    currentCoroutineContext().ensureActive()
                    val destination = file(spec)
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    validations[id] = Validation(fingerprint(destination), true)
                    mutableRevision.value++
                    Result.success(destination)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    Result.failure(error)
                } finally {
                    temporary?.delete()
                    mutableProgress.value = null
                }
            }
        }

    suspend fun remove(id: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val spec = sets.find { it.id == id } ?: return@withLock
                val target = file(spec)
                val existed = target.exists()
                if (existed && !target.delete()) throw IOException("Could not remove emoji set")
                val previous = validations.remove(id)
                if (existed || previous?.valid == true) mutableRevision.value++
            }
        }

    private fun file(spec: EmojiSetSpec) = File(directory, "${spec.id}.ttf")

    private suspend fun installed(spec: EmojiSetSpec): File? {
        val target = file(spec)
        val before = fingerprint(target)
        val previous = validations[spec.id]
        if (before != null && previous?.fingerprint == before) return target.takeIf { previous.valid }
        val valid =
            try {
                if (!target.isFile || Files.isSymbolicLink(target.toPath()) || target.length() != spec.sizeBytes) {
                    false
                } else {
                    val context = currentCoroutineContext()
                    val digest =
                        target.inputStream().use { input ->
                            copyEmojiBytes(input, null, spec.sizeBytes, { context.ensureActive() })
                        }
                    if (digest != spec.sha256) {
                        false
                    } else {
                        validateFont(target)
                        // Never memoize bytes read while a different process replaced the file.
                        before == fingerprint(target)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        validations[spec.id] = Validation(if (valid) before else fingerprint(target), valid)
        if (previous != null && previous.valid != valid) mutableRevision.value++
        return target.takeIf { valid }
    }

    private suspend fun fetch(
        spec: EmojiSetSpec,
        target: File,
    ) {
        var url = URL(spec.downloadUrl)
        val release = spec.downloadUrl == EmojiSets.find("twemoji")?.downloadUrl
        if (!safeEmojiUrl(url) || url.query != null ||
            (url.host != "raw.githubusercontent.com" && !release)
        ) {
            throw IOException("Untrusted emoji source")
        }
        val activeConnection = AtomicReference<HttpURLConnection?>()
        val input = AtomicReference<InputStream?>()
        suspendCancellableCoroutine<Unit> { continuation ->
            fun close() {
                runCatching { activeConnection.getAndSet(null)?.disconnect() }
                runCatching { input.getAndSet(null)?.close() }
            }
            continuation.invokeOnCancellation { close() }
            try {
                continuation.context.ensureActive()
                var redirects = 0
                var connection: HttpURLConnection
                while (true) {
                    continuation.context.ensureActive()
                    connection = openConnection(url)
                    activeConnection.set(connection)
                    continuation.context.ensureActive()
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    val status = connection.responseCode
                    if (connection.url.toExternalForm() != url.toExternalForm()) {
                        throw IOException("Emoji download response rejected")
                    }
                    if (status == HttpURLConnection.HTTP_OK) break
                    if (!release || status !in listOf(301, 302, 303, 307, 308) || redirects++ >= 3) {
                        throw IOException("Emoji download response rejected")
                    }
                    val location = connection.getHeaderField("Location") ?: throw IOException("Missing emoji redirect")
                    val next = URL(url, location)
                    if (!safeEmojiUrl(next) || next.host != "release-assets.githubusercontent.com" ||
                        !next.path.startsWith("/github-production-release-asset/921888585/")
                    ) {
                        throw IOException("Untrusted emoji redirect")
                    }
                    close()
                    url = next
                }
                val encoding = connection.contentEncoding
                if (encoding != null && !encoding.equals("identity", true)) throw IOException("Unexpected emoji encoding")
                val length = connection.contentLengthLong
                if (length >= 0 && length != spec.sizeBytes) throw IOException("Unexpected emoji file size")
                val source = connection.inputStream
                input.set(source)
                continuation.context.ensureActive()
                val digest =
                    FileOutputStream(target).use { output ->
                        copyEmojiBytes(source, output, spec.sizeBytes, { continuation.context.ensureActive() }) { bytes ->
                            mutableProgress.value = EmojiDownloadProgress(spec.id, bytes, spec.sizeBytes)
                        }.also { output.fd.sync() }
                    }
                if (digest != spec.sha256) throw IOException("Emoji checksum mismatch")
                continuation.context.ensureActive()
                continuation.resumeWith(Result.success(Unit))
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            } finally {
                close()
            }
        }
    }

    private fun validateFont(file: File) {
        val signature = ByteArray(4)
        val headerBytes = file.inputStream().use { it.read(signature) }
        if (headerBytes != 4 ||
            !(
                signature.contentEquals(byteArrayOf(0, 1, 0, 0)) ||
                    signature.contentEquals("OTTO".toByteArray(Charsets.US_ASCII)) ||
                    signature.contentEquals("ttcf".toByteArray(Charsets.US_ASCII))
            )
        ) {
            throw IOException("Invalid emoji font signature")
        }
        val typeface = Typeface.Builder(file).build() ?: throw IOException("Invalid emoji font")
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                textSize = 64f
                color = Color.WHITE
            }
        if (!paint.hasGlyph(renderSample)) throw IOException("Emoji font cannot shape the rendering probe")
        val bitmap = createBitmap(160, 128)
        try {
            Canvas(bitmap).drawText(renderSample, 8f, 88f, paint)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var visible = false
            var colored = false
            for (pixel in pixels) {
                if (Color.alpha(pixel) > 32) {
                    visible = true
                    if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) -
                        minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 24
                    ) {
                        colored = true
                    }
                }
            }
            if (!visible || (requireColor && !colored)) throw IOException("Emoji font did not render visible color pixels")
        } finally {
            bitmap.recycle()
        }
    }
}

private fun safeEmojiUrl(url: URL): Boolean = url.protocol == "https" && url.port in listOf(-1, 443) && url.userInfo == null && url.ref == null

private data class Fingerprint(
    val device: Any,
    val inode: Any,
    val size: Long,
    val modified: FileTime,
    val changed: FileTime,
)

/** If a platform cannot expose a trustworthy change time, hash again rather than trust size/mtime. */
private fun fingerprint(file: File): Fingerprint? =
    runCatching {
        val attributes = Files.readAttributes(file.toPath(), "unix:dev,ino,size,lastModifiedTime,ctime", LinkOption.NOFOLLOW_LINKS)
        Fingerprint(
            attributes.getValue("dev"),
            attributes.getValue("ino"),
            attributes.getValue("size") as Long,
            attributes.getValue("lastModifiedTime") as FileTime,
            attributes.getValue("ctime") as FileTime,
        )
    }.getOrNull()

/** Reads only one sentinel byte beyond the pin and never writes that byte to disk. */
internal fun copyEmojiBytes(
    input: InputStream,
    output: java.io.OutputStream?,
    sizeBytes: Long,
    checkCancellation: () -> Unit,
    onProgress: (Long) -> Unit = {},
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    var copied = 0L
    while (true) {
        checkCancellation()
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), sizeBytes - copied + 1).toInt())
        checkCancellation()
        if (read < 0) break
        if (read == 0) continue
        if (read > sizeBytes - copied) throw IOException("Emoji file exceeds pinned size")
        output?.write(buffer, 0, read)
        digest.update(buffer, 0, read)
        copied += read
        onProgress(copied)
    }
    if (copied != sizeBytes) throw IOException("Emoji file is truncated")
    return digest.digest().joinToString("") { "%02x".format(it) }
}
