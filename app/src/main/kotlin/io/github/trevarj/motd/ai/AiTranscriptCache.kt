package io.github.trevarj.motd.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

internal const val MAX_TRANSCRIPT_BYTES: Int = 256 * 1024
internal const val MAX_TRANSCRIPT_CACHE_BYTES: Long = 20L * 1024 * 1024
internal const val TRANSCRIPTION_PREPROCESSING_VERSION: String = "pcm-mono-s16le-16000-linear-v1"
internal const val TRANSCRIPTION_RUNTIME_VERSION: String =
    "whisper.cpp-642b5d3260e020c2fc6f34a9569d10ddd7672963-jni-v1"

private const val TRANSCRIPT_DIRECTORY = "ai/transcripts"
private val SHA_256 = Regex("[0-9a-f]{64}")

@Singleton
class AiTranscriptCache internal constructor(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val maxEntryBytes: Int = MAX_TRANSCRIPT_BYTES,
    private val maxDirectoryBytes: Long = MAX_TRANSCRIPT_CACHE_BYTES,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val atomicReplace: (Path, Path) -> Unit = ::atomicReplace,
) {
    init {
        require(maxEntryBytes > 0) { "Transcript entry limit must be positive" }
        require(maxDirectoryBytes >= 0) { "Transcript directory limit must not be negative" }
    }

    @Inject
    constructor(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(File(context.cacheDir, TRANSCRIPT_DIRECTORY), ioDispatcher)

    private val lock = Any()

    suspend fun get(key: String): String? =
        withContext(ioDispatcher) {
            requireKey(key)
            coroutineContext.ensureActive()
            synchronized(lock) { readEntry(File(directory, key)) }
        }

    suspend fun put(
        key: String,
        text: String,
    ) = withContext(ioDispatcher) {
        requireKey(key)
        coroutineContext.ensureActive()
        synchronized(lock) {
            putEntry(File(directory, key), text) { coroutineContext.ensureActive() }
        }
    }

    suspend fun remove(key: String) =
        withContext(ioDispatcher) {
            requireKey(key)
            coroutineContext.ensureActive()
            synchronized(lock) {
                cleanupTemporaryFiles(strict = false)
                val entry = File(directory, key)
                if (entry.exists() && !entry.deleteRecursively()) {
                    throw IOException("Transcript cache entry could not be deleted")
                }
            }
        }

    suspend fun clear() =
        withContext(ioDispatcher) {
            coroutineContext.ensureActive()
            synchronized(lock) {
                if (directory.exists() && !directory.deleteRecursively()) {
                    throw IOException("Transcript cache could not be deleted")
                }
            }
        }

    private fun putEntry(
        destination: File,
        text: String,
        checkCancellation: () -> Unit,
    ) {
        if (directory.isDirectory) cleanupTemporaryFiles(strict = true)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (text.isBlank() || bytes.size > maxEntryBytes) return
        ensureDirectory()
        val temporary = File.createTempFile(".transcript-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            checkCancellation()
            atomicReplace(temporary.toPath(), destination.toPath())
            destination.setLastModified(nowMillis())
            enforceDirectoryLimit()
        } finally {
            temporary.delete()
        }
    }

    private fun readEntry(entry: File): String? {
        if (!directory.isDirectory) return null
        cleanupTemporaryFiles(strict = false)
        val size = entry.length()
        if (!entry.isFile || size !in 1..maxEntryBytes.toLong()) {
            if (entry.exists()) entry.deleteRecursively()
            return null
        }
        return try {
            val bytes = readExact(entry, size.toInt())
            val text =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            if (text.isBlank()) {
                entry.delete()
                null
            } else {
                entry.setLastModified(nowMillis())
                text
            }
        } catch (_: Exception) {
            entry.deleteRecursively()
            null
        }
    }

    private fun ensureDirectory() {
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw IOException("Transcript cache directory could not be created")
        }
    }

    private fun cleanupTemporaryFiles(strict: Boolean) {
        if (!directory.isDirectory) return
        val files =
            directory.listFiles()
                ?: if (strict) {
                    throw IOException("Transcript cache directory could not be read")
                } else {
                    return
                }
        files.filterNot { it.isFile && SHA_256.matches(it.name) }.forEach { file ->
            if (!file.deleteRecursively() && strict) {
                throw IOException("Transcript cache temporary file could not be deleted")
            }
        }
    }

    private fun enforceDirectoryLimit() {
        cleanupTemporaryFiles(strict = true)
        val entries =
            directory.listFiles()?.toList()
                ?: throw IOException("Transcript cache directory could not be read")
        var totalBytes = entries.sumOf { it.length() }
        if (totalBytes <= maxDirectoryBytes) return
        for (entry in entries.sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))) {
            val size = entry.length()
            if (entry.delete()) totalBytes -= size
            if (totalBytes <= maxDirectoryBytes) return
        }
        throw IOException("Transcript cache size limit could not be enforced")
    }

    companion object {
        fun settingsFingerprint(settings: TranscriptionSettings): String =
            sha256(
                TRANSCRIPTION_PREPROCESSING_VERSION,
                TRANSCRIPTION_RUNTIME_VERSION,
                settings.language,
                settings.initialPrompt,
                settings.cpuThreads.toString(),
            )

        fun key(
            plaintextAudioSha256: String,
            transcriptionModelSha256: String,
            settings: TranscriptionSettings,
        ): String {
            requireKey(plaintextAudioSha256)
            requireKey(transcriptionModelSha256)
            return sha256(
                plaintextAudioSha256,
                transcriptionModelSha256,
                settingsFingerprint(settings),
            )
        }
    }
}

private fun readExact(
    file: File,
    size: Int,
): ByteArray =
    FileInputStream(file).use { input ->
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw IOException("Transcript cache entry was truncated")
            if (count == 0) continue
            offset += count
        }
        if (input.read() != -1) throw IOException("Transcript cache entry grew while being read")
        bytes
    }

private fun atomicReplace(
    source: Path,
    destination: Path,
) {
    Files.move(
        source,
        destination,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

private fun requireKey(key: String) {
    require(SHA_256.matches(key)) { "Transcript cache key must be a lowercase SHA-256" }
}

private fun sha256(vararg fields: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (field in fields) {
        val bytes = field.toByteArray(StandardCharsets.UTF_8)
        digest.update((bytes.size ushr 24).toByte())
        digest.update((bytes.size ushr 16).toByte())
        digest.update((bytes.size ushr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }
    return digest.digest().lowerHex()
}

private fun ByteArray.lowerHex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@lowerHex) {
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0f])
        }
    }
}
