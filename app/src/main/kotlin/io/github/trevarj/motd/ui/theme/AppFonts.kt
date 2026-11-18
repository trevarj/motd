package io.github.trevarj.motd.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.emoji.EmojiSequenceCatalog
import io.github.trevarj.motd.data.emoji.EmojiSetStore
import io.github.trevarj.motd.data.emoji.EmojiSets
import io.github.trevarj.motd.data.prefs.FontChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Variable-weight JetBrains Mono, built from the two bundled TTFs (upright + italic). Each entry
 * pins a static weight instance via [FontVariation.Settings] rather than shipping separate static
 * files.
 */
val JetBrainsMonoFamily: FontFamily =
    FontFamily(
        Font(
            R.font.jetbrains_mono_wght,
            weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
        Font(
            R.font.jetbrains_mono_wght,
            weight = FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500)),
        ),
        Font(
            R.font.jetbrains_mono_wght,
            weight = FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700)),
        ),
        Font(
            R.font.jetbrains_mono_italic_wght,
            weight = FontWeight.Normal,
            style = FontStyle.Italic,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
        Font(
            R.font.jetbrains_mono_italic_wght,
            weight = FontWeight.Medium,
            style = FontStyle.Italic,
            variationSettings = FontVariation.Settings(FontVariation.weight(500)),
        ),
        Font(
            R.font.jetbrains_mono_italic_wght,
            weight = FontWeight.Bold,
            style = FontStyle.Italic,
            variationSettings = FontVariation.Settings(FontVariation.weight(700)),
        ),
    )

/**
 * Resolve a user's font choice to a Compose [FontFamily]; null keeps the platform default.
 * CUSTOM has no bundled resource — it always resolves through [rememberAppFontFamily] instead,
 * which is why it falls through to null (system) here.
 */
fun FontChoice.fontFamily(): FontFamily? =
    when (this) {
        FontChoice.SYSTEM -> null
        FontChoice.SANS -> FontFamily.SansSerif
        FontChoice.SERIF -> FontFamily.Serif
        FontChoice.MONOSPACE -> FontFamily.Monospace
        FontChoice.JETBRAINS_MONO -> JetBrainsMonoFamily
        FontChoice.CUSTOM -> null
    }

/**
 * Composable font resolution that additionally handles CUSTOM by loading [customFontFile] from
 * disk. Remembered on the file's path and last-modified time so a re-import (same path, new bytes)
 * invalidates the cached [FontFamily]. A missing file falls back to the platform default, which
 * covers a restored backup whose font binary never traveled (see ConfigurationBackup).
 */
@Composable
fun rememberAppFontFamily(
    choice: FontChoice,
    customFontFile: File?,
): FontFamily? {
    if (choice != FontChoice.CUSTOM) return choice.fontFamily()
    val file = customFontFile?.takeIf { it.exists() } ?: return null
    return remember(file.absolutePath, file.lastModified()) {
        FontFamily(Font(file))
    }
}

/** File validation, metadata and font loading never block composition. Missing installs use System. */
@Composable
fun rememberEmojiRenderer(
    setId: String,
    revision: Long,
    store: EmojiSetStore,
    catalog: EmojiSequenceCatalog,
): EmojiRenderer? =
    key(setId, revision, store, catalog) {
        val renderer by produceState<EmojiRenderer?>(null) {
            if (setId == EmojiSets.SYSTEM_ID) return@produceState
            value =
                withContext(Dispatchers.IO) {
                    try {
                        val file = store.installedFile(setId) ?: return@withContext null
                        EmojiRenderer(catalog.load(), setId, FontFamily(Font(file)))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Removal/replacement can race resolution; keep the selected ID for reinstall.
                        null
                    }
                }
        }
        renderer
    }

/** Apply independent UI family and emoji paragraph policy, leaving other typography intact. */
internal fun Typography.withFontFamily(
    family: FontFamily?,
    emojiRenderer: EmojiRenderer? = null,
): Typography {
    if (family == null && emojiRenderer == null) return this

    fun resolve(style: androidx.compose.ui.text.TextStyle): androidx.compose.ui.text.TextStyle {
        val styled = if (family == null) style else style.copy(fontFamily = family)
        return emojiRenderer?.textStyle(styled) ?: styled
    }
    return copy(
        displayLarge = resolve(displayLarge),
        displayMedium = resolve(displayMedium),
        displaySmall = resolve(displaySmall),
        headlineLarge = resolve(headlineLarge),
        headlineMedium = resolve(headlineMedium),
        headlineSmall = resolve(headlineSmall),
        titleLarge = resolve(titleLarge),
        titleMedium = resolve(titleMedium),
        titleSmall = resolve(titleSmall),
        bodyLarge = resolve(bodyLarge),
        bodyMedium = resolve(bodyMedium),
        bodySmall = resolve(bodySmall),
        labelLarge = resolve(labelLarge),
        labelMedium = resolve(labelMedium),
        labelSmall = resolve(labelSmall),
    )
}
