package io.github.trevarj.motd.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

internal class AudioFileLease(
    val file: File,
    private val release: (File) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) release(file)
    }
}

@Singleton
class AudioCacheStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val mediaCache: AudioMediaCache,
        private val waveformRepository: AudioWaveformRepository,
    ) {
        private val root = File(context.cacheDir, "audio-cache").also { it.mkdirs() }
        private val inputLeaseRoot = File(root, INPUT_LEASE_DIRECTORY)
        private val pcmLeaseRoot = File(root, PCM_LEASE_DIRECTORY)
        private val legacyInputLeaseRoot = File(context.cacheDir, "audio-input-leases")
        private val legacyPcmLeaseRoot = File(context.cacheDir, "pcm-audio-leases")
        private val leaseLock = Any()
        private val activeLeases = mutableSetOf<String>()

        suspend fun clear() =
            withContext(Dispatchers.IO) {
                val failures = mutableListOf<Exception>()
                try {
                    synchronized(leaseLock) { clearInactiveFiles() }
                } catch (error: Exception) {
                    failures += error
                }
                try {
                    mediaCache.clear()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failures += error
                }
                try {
                    waveformRepository.clear()
                    if (File(root, WAVEFORM_DIRECTORY).listFilesChecked().isNotEmpty()) {
                        throw IOException("Audio waveform cache could not be cleared")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failures += error
                }
                failures.firstOrNull()?.let { failure ->
                    failures.drop(1).forEach(failure::addSuppressed)
                    throw failure
                }
                requireDirectory(root)
            }

        suspend fun pruneStaleLeases() =
            withContext(Dispatchers.IO) {
                val failures = mutableListOf<Exception>()
                try {
                    synchronized(leaseLock) {
                        deleteInactiveUnder(inputLeaseRoot)
                        deleteInactiveUnder(pcmLeaseRoot)
                        deleteChecked(legacyInputLeaseRoot)
                        deleteChecked(legacyPcmLeaseRoot)
                    }
                } catch (error: Exception) {
                    failures += error
                }
                try {
                    mediaCache.pruneLegacyKeys()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failures += error
                }
                failures.firstOrNull()?.let { failure ->
                    failures.drop(1).forEach(failure::addSuppressed)
                    throw failure
                }
            }

        suspend fun trim(maxBytes: Long = MAX_AUDIO_CACHE_BYTES) =
            withContext(Dispatchers.IO) {
                synchronized(leaseLock) {
                    val files =
                        root
                            .listFilesChecked()
                            .filterNot { it.name == MEDIA_DIRECTORY || it.name == WAVEFORM_DIRECTORY }
                            .flatMap { it.walkTopDown().filter(File::isFile).toList() }
                    var total = files.sumOf(File::length)
                    if (total <= maxBytes) return@synchronized
                    for (file in files.filterNot(::isActive).sortedBy(File::lastModified)) {
                        val size = file.length()
                        deleteChecked(file)
                        total -= size
                        if (total <= maxBytes) break
                    }
                }
            }

        internal fun inputLease(): AudioFileLease = newLease(inputLeaseRoot, "audio-input-", ".media")

        internal fun pcmLease(): AudioFileLease = newLease(pcmLeaseRoot, "pcm-", ".wav")

        fun tempFile(
            prefix: String,
            extension: String,
        ): File {
            requireDirectory(root)
            return File.createTempFile(prefix, extension, root)
        }

        fun ciphertextFile(url: String): File = File(root, "cipher-${url.sha256()}.motdvoice")

        private fun newLease(
            directory: File,
            prefix: String,
            extension: String,
        ): AudioFileLease =
            synchronized(leaseLock) {
                requireDirectory(directory)
                val file = File.createTempFile(prefix, extension, directory)
                activeLeases += file.absolutePath
                AudioFileLease(file, ::releaseLease)
            }

        private fun releaseLease(file: File) =
            synchronized(leaseLock) {
                activeLeases -= file.absolutePath
                deleteChecked(file)
            }

        private fun clearInactiveFiles() {
            deleteInactiveUnder(inputLeaseRoot)
            deleteInactiveUnder(pcmLeaseRoot)
            deleteChecked(legacyInputLeaseRoot)
            deleteChecked(legacyPcmLeaseRoot)
            root
                .listFilesChecked()
                .filterNot {
                    it.name == MEDIA_DIRECTORY ||
                        it.name == WAVEFORM_DIRECTORY ||
                        it.name == INPUT_LEASE_DIRECTORY ||
                        it.name == PCM_LEASE_DIRECTORY
                }.forEach(::deleteChecked)
        }

        private fun deleteInactiveUnder(directory: File) {
            directory.listFilesChecked().forEach { entry ->
                val entryPrefix = entry.absolutePath + File.separator
                if (activeLeases.any { it == entry.absolutePath || it.startsWith(entryPrefix) }) return@forEach
                deleteChecked(entry)
            }
            requireDirectory(directory)
        }

        private fun isActive(file: File): Boolean = file.absolutePath in activeLeases

        private fun File.listFilesChecked(): List<File> =
            when {
                !exists() -> emptyList()
                !isDirectory -> throw IOException("An audio cache directory is invalid")
                else -> listFiles()?.toList() ?: throw IOException("An audio cache directory could not be read")
            }

        private fun requireDirectory(directory: File) {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("An audio cache directory could not be created")
            }
        }

        private fun deleteChecked(file: File) {
            if (file.exists() && !file.deleteRecursively()) {
                throw IOException("An audio cache file could not be deleted")
            }
        }

        private fun String.sha256(): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(substringBefore('#').toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        companion object {
            const val MAX_AUDIO_CACHE_BYTES = 128L * 1024L * 1024L
            private const val MEDIA_DIRECTORY = "media3"
            private const val WAVEFORM_DIRECTORY = "waveforms"
            private const val INPUT_LEASE_DIRECTORY = "input-leases"
            private const val PCM_LEASE_DIRECTORY = "pcm-leases"
        }
    }
