package io.github.trevarj.motd.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.trevarj.motd.data.prefs.PaletteDocument
import io.github.trevarj.motd.data.prefs.PaletteError
import io.github.trevarj.motd.data.prefs.PaletteException
import io.github.trevarj.motd.data.prefs.decodePalette
import io.github.trevarj.motd.data.prefs.encodePalette
import io.github.trevarj.motd.data.prefs.readPaletteBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

internal suspend fun readPaletteFile(
    context: Context,
    uri: Uri,
): PaletteDocument =
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { decodePalette(readPaletteBytes(it)) }
                ?: throw PaletteException(PaletteError.READ, "Palette source unavailable")
        } catch (error: IOException) {
            throw PaletteException(PaletteError.READ, "Could not read palette", error)
        } catch (error: SecurityException) {
            throw PaletteException(PaletteError.READ, "Could not access palette", error)
        }
    }

internal suspend fun writePaletteFile(
    context: Context,
    uri: Uri,
    palette: PaletteDocument,
) {
    withContext(Dispatchers.IO) {
        val bytes = encodePalette(palette)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IOException("Palette destination unavailable")
    }
}

internal suspend fun sharePaletteFile(
    context: Context,
    palette: PaletteDocument,
) {
    val share =
        withContext(Dispatchers.IO) {
            val bytes = encodePalette(palette)
            val directory = File(context.cacheDir, "palette-share/${UUID.randomUUID()}")
            if (!directory.mkdirs()) throw IOException("Palette share destination unavailable")
            val file = File(directory, "${palette.id}.motd-palette.json")
            file.outputStream().use { it.write(bytes) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, palette.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    withContext(Dispatchers.Main.immediate) {
        context.startActivity(Intent.createChooser(share, palette.name))
    }
}
