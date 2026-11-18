package io.github.trevarj.motd.ui.components

import android.content.SharedPreferences
import io.github.trevarj.motd.data.emoji.EmojiDataset
import io.github.trevarj.motd.data.emoji.EmojiEntry

internal const val EMOJI_PICKER_PREFERENCES = "androidx.emoji2.emojipicker.preferences"
internal const val EMOJI_PICKER_RECENTS_KEY = "pref_key_recent_emoji"
internal const val EMOJI_PICKER_STICKY_KEY = "pref_key_sticky_variant"
internal const val EMOJI_PICKER_RECENT_LIMIT = 100

internal data class EmojiPickerHistory(
    val recents: List<String>,
    val sticky: Map<String, String>,
)

internal fun emojiPickerRoots(dataset: EmojiDataset): List<EmojiEntry> = dataset.entries.filter { it.group != "Component" && it.sequence == it.baseSequence }

internal fun emojiPickerVariants(
    dataset: EmojiDataset,
    sequence: String,
): List<EmojiEntry> {
    val entry = dataset.entry(sequence) ?: return emptyList()
    val family = dataset.variantsOf(entry.sequence).filter { it.group != "Component" }
    return family.sortedBy { if (it.sequence == entry.baseSequence) 0 else 1 }
}

internal fun emojiPickerTag(sequence: String): String = sequence.codePoints().toArray().joinToString("_") { it.toString(16) }

internal fun emojiCategoryTag(group: String): String = group.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')

/** AndroidX emoji2-emojipicker 1.7.0 stores comma-separated Unicode recents and
 * pipe-separated Map.Entry strings (base=variant) in this same preference file.
 * Normalize that format in place, retaining compatibility without a second history store.
 */
internal fun readEmojiPickerHistory(
    preferences: SharedPreferences,
    dataset: EmojiDataset,
): EmojiPickerHistory {
    val recents =
        preferences
            .getString(EMOJI_PICKER_RECENTS_KEY, null)
            .orEmpty()
            .split(',')
            .mapNotNull { dataset.entry(it)?.takeUnless { entry -> entry.group == "Component" }?.sequence }
            .distinct()
            .take(EMOJI_PICKER_RECENT_LIMIT)
    val sticky =
        buildMap {
            preferences.getString(EMOJI_PICKER_STICKY_KEY, null).orEmpty().split('|').forEach { pair ->
                val parts = pair.split('=', limit = 2)
                if (parts.size != 2) return@forEach
                val base = dataset.entry(parts[0]) ?: return@forEach
                val variant = dataset.entry(parts[1]) ?: return@forEach
                if (base.group != "Component" && variant.baseSequence == base.baseSequence &&
                    variant.sequence != base.baseSequence
                ) {
                    put(base.baseSequence, variant.sequence)
                }
            }
        }
    return EmojiPickerHistory(recents, sticky)
}

internal fun writeEmojiPickerHistory(
    preferences: SharedPreferences,
    history: EmojiPickerHistory,
) {
    preferences
        .edit()
        .putString(EMOJI_PICKER_RECENTS_KEY, history.recents.joinToString(","))
        .putString(EMOJI_PICKER_STICKY_KEY, history.sticky.entries.joinToString("|") { "${it.key}=${it.value}" })
        .apply()
}

internal fun recordEmojiPickerSelection(
    history: EmojiPickerHistory,
    entry: EmojiEntry,
    rememberVariant: Boolean,
): EmojiPickerHistory {
    val sticky =
        if (rememberVariant) {
            history.sticky.toMutableMap().apply {
                if (entry.sequence == entry.baseSequence) {
                    remove(entry.baseSequence)
                } else {
                    put(entry.baseSequence, entry.sequence)
                }
            }
        } else {
            history.sticky
        }
    return EmojiPickerHistory(
        (listOf(entry.sequence) + history.recents.filterNot { it == entry.sequence }).take(EMOJI_PICKER_RECENT_LIMIT),
        sticky,
    )
}
