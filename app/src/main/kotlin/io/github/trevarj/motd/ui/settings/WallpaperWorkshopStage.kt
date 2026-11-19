package io.github.trevarj.motd.ui.settings

import android.content.Context
import android.util.AtomicFile
import io.github.trevarj.motd.data.prefs.ImportedWallpaperIcon
import io.github.trevarj.motd.data.prefs.MAX_WALLPAPER_ICONS
import io.github.trevarj.motd.data.prefs.WallpaperSvg
import io.github.trevarj.motd.data.prefs.WallpaperSvgError
import io.github.trevarj.motd.data.prefs.WallpaperSvgException
import io.github.trevarj.motd.data.prefs.isCanonicalWallpaperUuid
import io.github.trevarj.motd.data.prefs.isWallpaperName
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/** Process state contains only the session ID and the labels of its referenced digests. */
internal class WallpaperWorkshopStage(
    context: Context,
) {
    private val cache = context.cacheDir

    fun prune(active: String) {
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(48)
        root().listFiles()?.forEach { file ->
            if (file.name != active && isCanonicalWallpaperUuid(file.name) && file.lastModified() < cutoff) discard(file.name)
        }
        directory(active).takeIf(File::isDirectory)?.setLastModified(System.currentTimeMillis())
    }

    fun read(
        session: String,
        names: Map<String, String>,
    ): Map<String, ImportedWallpaperIcon> =
        buildMap {
            if (names.size > 2 * MAX_WALLPAPER_ICONS) return@buildMap
            for ((id, name) in names) {
                if (!STAGED_ID.matches(id) || !isWallpaperName(name)) continue
                val icon =
                    runCatching {
                        val file = iconFile(directory(session), id)
                        val buffer = ByteArray(SVG_BYTE_LIMIT + 1)
                        val size =
                            file.inputStream().use { input ->
                                var count = 0
                                while (count < buffer.size) {
                                    val read = input.read(buffer, count, buffer.size - count)
                                    if (read == -1) break
                                    count += read
                                }
                                require(count <= SVG_BYTE_LIMIT)
                                count
                            }
                        val svg =
                            Charsets.UTF_8
                                .newDecoder()
                                .decode(ByteBuffer.wrap(buffer, 0, size))
                                .toString()
                        ImportedWallpaperIcon(id, name, svg).also { WallpaperSvg.validate(it) }
                    }.getOrNull()
                if (icon != null) put(id, icon)
            }
        }

    @Synchronized
    fun write(
        session: String,
        icons: List<ImportedWallpaperIcon>,
        keepIds: Set<String>,
    ) {
        try {
            val directory = directory(session)
            if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot open wallpaper staging directory")
            for (icon in icons) {
                val file = AtomicFile(iconFile(directory, icon.id))
                val output = file.startWrite()
                try {
                    output.write(icon.svg.toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (failure: Exception) {
                    file.failWrite(output)
                    throw failure
                }
            }
            val keepFiles = keepIds.mapTo(HashSet()) { it.removePrefix("svg:") + ".svg" }
            directory.listFiles()?.forEach { file -> if (file.name !in keepFiles) file.delete() }
            directory.setLastModified(System.currentTimeMillis())
        } catch (failure: IOException) {
            throw WallpaperSvgException(WallpaperSvgError.UNREADABLE, failure)
        }
    }

    @Synchronized
    fun discard(session: String) {
        runCatching {
            val directory = directory(session)
            // A session is flat; don't recurse through anything unexpected in cache.
            directory.listFiles()?.forEach(File::delete)
            directory.delete()
        }
    }

    private fun root(): File = ownedChild(cache.canonicalFile, "wallpaper-workshop")

    private fun directory(session: String): File {
        require(isCanonicalWallpaperUuid(session))
        return ownedChild(root(), session)
    }

    private fun iconFile(
        directory: File,
        id: String,
    ): File {
        require(STAGED_ID.matches(id))
        return ownedChild(directory, id.removePrefix("svg:") + ".svg")
    }

    private fun ownedChild(
        parent: File,
        name: String,
    ): File =
        File(parent, name).also { file ->
            if (file.canonicalFile != file) throw IOException("Wallpaper staging path is not owned")
        }

    private companion object {
        val STAGED_ID = Regex("svg:[a-f0-9]{64}")
        const val SVG_BYTE_LIMIT = 16_384
    }
}
