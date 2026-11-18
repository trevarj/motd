package io.github.trevarj.motd.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.emoji.EmojiSequenceCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmojiPickerPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun androidX170PreferencesMigrateInPlaceWithoutLosingOrderOrStickyUnicode() =
        runTest {
            val dataset = EmojiSequenceCatalog(context).load()
            val preferences = context.getSharedPreferences(EMOJI_PICKER_PREFERENCES, Context.MODE_PRIVATE)
            preferences
                .edit()
                .clear()
                .putString("pref_key_recent_emoji", "👍🏽,❤,😀,👍🏽,unknown,")
                .putString("pref_key_sticky_variant", "👍=👍🏽|broken|❤=❤️|🤝=🫱🏻‍🫲🏿|😀=👍🏽|=|")
                .commit()

            val migrated = readEmojiPickerHistory(preferences, dataset)
            assertEquals(listOf("👍🏽", "❤️", "😀"), migrated.recents)
            assertEquals(mapOf("👍" to "👍🏽", "🤝" to "🫱🏻‍🫲🏿"), migrated.sticky)
            writeEmojiPickerHistory(preferences, migrated)
            assertEquals("👍🏽,❤️,😀", preferences.getString("pref_key_recent_emoji", null))
            assertEquals(migrated, readEmojiPickerHistory(preferences, dataset))
            // The replacement continues writing AndroidX's exact base=variant|base=variant format.
            assertEquals("👍=👍🏽|🤝=🫱🏻‍🫲🏿", preferences.getString("pref_key_sticky_variant", null))
        }

    @Test
    fun historyIsBoundedOrderedDeduplicatedAndPersistsExactSelectedSequences() =
        runTest {
            val dataset = EmojiSequenceCatalog(context).load()
            val preferences = context.getSharedPreferences("picker-bounded-test", Context.MODE_PRIVATE)
            val roots = emojiPickerRoots(dataset).take(EMOJI_PICKER_RECENT_LIMIT + 20)
            preferences
                .edit()
                .clear()
                .putString(EMOJI_PICKER_RECENTS_KEY, roots.joinToString(",") { it.sequence })
                .commit()
            val imported = readEmojiPickerHistory(preferences, dataset)
            assertEquals(roots.take(EMOJI_PICKER_RECENT_LIMIT).map { it.sequence }, imported.recents)
            val variant = requireNotNull(dataset.entry("👍🏽"))
            val first = recordEmojiPickerSelection(imported, variant, true)
            val second = recordEmojiPickerSelection(first, roots.first(), false)
            assertEquals(EMOJI_PICKER_RECENT_LIMIT, second.recents.size)
            assertEquals(listOf(roots.first().sequence, variant.sequence), second.recents.take(2))
            assertEquals(second.recents.size, second.recents.distinct().size)
            writeEmojiPickerHistory(preferences, second)
            assertEquals(second, readEmojiPickerHistory(preferences, dataset))
            assertEquals("👍🏽", second.sticky["👍"])
            val reset = recordEmojiPickerSelection(second, requireNotNull(dataset.entry("👍")), true)
            assertFalse(reset.sticky.containsKey("👍"))
        }

    @Test
    fun familiesContainAllMixedTonesAndDirectionalVariantsWhileGridUsesRootsOnly() =
        runTest {
            val dataset = EmojiSequenceCatalog(context).load()
            val roots = emojiPickerRoots(dataset)
            assertTrue(roots.all { it.sequence == it.baseSequence && it.group != "Component" })
            assertTrue(roots.any { it.sequence == "🤝" })
            assertFalse(roots.any { it.sequence == "🫱🏻‍🫲🏿" })
            val handshake = emojiPickerVariants(dataset, "🫱🏻‍🫲🏿")
            assertEquals("🤝", handshake.first().sequence)
            assertTrue(handshake.any { it.sequence == "🫱🏻‍🫲🏿" })
            assertEquals(dataset.variantsOf("🤝").toSet(), handshake.toSet())
            val directional = emojiPickerVariants(dataset, "🚶‍➡️")
            assertTrue(directional.size >= 6)
            assertTrue(directional.all { it.baseSequence == "🚶‍➡️" })
            assertEquals(dataset.variantsOf("🚶‍➡️").toSet(), directional.toSet())
            assertTrue(roots.any { it.sequence == "🚶‍➡️" })
            assertEquals("1f44d_1f3fd", emojiPickerTag("👍🏽"))
            assertEquals("people_body", emojiCategoryTag("People & Body"))
        }
}
