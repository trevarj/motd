package io.github.trevarj.motd.data.prefs

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** One local wallpaper slot; preferences own the active filename, never an external URI. */
@Singleton
class CustomWallpaperStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        suspend fun import(source: Uri): Result<String> {
            var installed: File? = null
            try {
                return withContext(Dispatchers.IO) {
                    val directory = File(context.filesDir, DIRECTORY)
                    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Unable to create wallpaper storage")
                    val temporary = File(directory, ".${UUID.randomUUID()}.tmp")
                    try {
                        context.contentResolver.openInputStream(source)?.use { input ->
                            temporary.outputStream().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var total = 0L
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > MAX_BYTES) throw IOException("Wallpaper image is larger than 20 MiB")
                                    output.write(buffer, 0, count)
                                }
                                output.fd.sync()
                            }
                        } ?: throw IOException("Unable to open image")
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(temporary.path, bounds)
                        if (
                            bounds.outWidth !in 1..MAX_DIMENSION || bounds.outHeight !in 1..MAX_DIMENSION ||
                            bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS
                        ) {
                            throw IOException("Invalid or oversized image dimensions")
                        }
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > VALIDATION_SIZE) sample *= 2
                        val decoded =
                            BitmapFactory.decodeFile(
                                temporary.path,
                                BitmapFactory.Options().apply { inSampleSize = sample },
                            ) ?: throw IOException("Unable to decode image")
                        decoded.recycle()
                        coroutineContext.ensureActive()
                        val target = File(directory, "${UUID.randomUUID()}.image")
                        if (!temporary.renameTo(target)) throw IOException("Unable to store wallpaper image")
                        installed = target
                        Result.success(target.name)
                    } finally {
                        temporary.delete()
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { installed?.delete() }
                throw cancelled
            } catch (error: Exception) {
                return Result.failure(error)
            }
        }

        suspend fun delete(name: String?) =
            withContext(Dispatchers.IO) {
                resolve(context, name)?.delete()
                Unit
            }

        companion object {
            internal const val DIRECTORY = "chat-wallpaper-images"
            internal const val MAX_BYTES = 20L * 1024 * 1024
            private const val MAX_DIMENSION = 32_768
            private const val MAX_PIXELS = 100_000_000L
            private const val VALIDATION_SIZE = 1024
            private val ownedName = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.image")

            /** Reject paths, URIs, symlinks escaping storage, and missing files before reaching Coil. */
            fun resolve(
                context: Context,
                name: String?,
            ): File? {
                if (name == null || !ownedName.matches(name)) return null
                return runCatching {
                    val directory = File(context.filesDir, DIRECTORY).canonicalFile
                    File(directory, name).takeIf { it.isFile && it.canonicalFile.parentFile == directory }
                }.getOrNull()
            }
        }
    }
