package io.github.trevarj.motd.data.emoji

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Generated from pinned Unicode data and the exact downloadable fonts; no font is bundled. */
@Singleton
class EmojiSequenceCatalog
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val mutex = Mutex()
        private var cached: EmojiDataset? = null

        suspend fun load(): EmojiDataset =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    cached ?: context.assets.open("emoji/catalog.json").bufferedReader().use {
                        decode(it.readText()).also { dataset -> cached = dataset }
                    }
                }
            }

        internal companion object {
            private val json = Json { ignoreUnknownKeys = true }

            fun decode(source: String): EmojiDataset {
                val raw = json.decodeFromString<CatalogData>(source)
                require(raw.unicodeVersion == "16.0") { "Unexpected emoji dataset version" }
                require(
                    raw.entries
                        .map { it.sequence }
                        .toSet()
                        .size == raw.entries.size,
                )
                val entries =
                    raw.entries.map {
                        require(it.sequence.isNotEmpty() && it.name.isNotEmpty() && it.group in raw.groups)
                        require(it.support.all { id -> EmojiSets.find(id) != null })
                        EmojiEntry(it.sequence, it.name, it.group, it.subgroup, it.base, it.support)
                    }
                val canonical = entries.mapTo(HashSet()) { it.sequence }
                require(entries.all { it.baseSequence in canonical }) { "Unknown emoji family root" }
                val aliases =
                    raw.aliases.map {
                        require(it.sequence.isNotEmpty() && it.entry in canonical)
                        require(it.support.all { id -> EmojiSets.find(id) != null })
                        EmojiAlias(it.sequence, it.entry, it.support)
                    }
                return EmojiDataset(entries, raw.groups, aliases)
            }
        }
    }

@Serializable
private class CatalogData(
    val unicodeVersion: String,
    val groups: List<String>,
    val entries: List<EntryData>,
    val aliases: List<AliasData>,
)

@Serializable
private class EntryData(
    val sequence: String,
    val name: String,
    val group: String,
    val subgroup: String,
    val base: String,
    val support: List<String>,
)

@Serializable
private class AliasData(
    val sequence: String,
    val entry: String,
    val support: List<String>,
)
