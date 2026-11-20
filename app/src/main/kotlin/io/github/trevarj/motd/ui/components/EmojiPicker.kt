package io.github.trevarj.motd.ui.components

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.emoji.EmojiDataset
import io.github.trevarj.motd.data.emoji.EmojiEntry
import io.github.trevarj.motd.data.emoji.EmojiSequenceCatalog
import io.github.trevarj.motd.ui.chat.EmojiSearchEntry
import io.github.trevarj.motd.ui.chat.searchEmojis
import io.github.trevarj.motd.ui.theme.LocalEmojiSequenceCatalog
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import io.github.trevarj.motd.ui.components.EmojiText as Text
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

@Composable
internal fun EmojiPickerPanel(
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "chat_composer",
) {
    val context = LocalContext.current.applicationContext
    val fallbackCatalog = remember(context) { EmojiSequenceCatalog(context) }
    val catalog = LocalEmojiSequenceCatalog.current ?: fallbackCatalog
    val dataset by produceState<EmojiDataset?>(null, catalog) { value = catalog.load() }
    Surface(modifier.testTag("${tagPrefix}_emoji_picker"), color = MaterialTheme.colorScheme.surface) {
        val data = dataset
        if (data == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.emoji_picker_loading))
            }
        } else {
            EmojiPickerContent(data, onPick, tagPrefix, context)
        }
    }
}

@Composable
private fun EmojiPickerContent(
    dataset: EmojiDataset,
    onPick: (String) -> Unit,
    tagPrefix: String,
    context: Context,
) {
    val preferences = remember(context) { context.getSharedPreferences(EMOJI_PICKER_PREFERENCES, Context.MODE_PRIVATE) }
    var history by remember(dataset, preferences) { mutableStateOf(readEmojiPickerHistory(preferences, dataset)) }
    DisposableEffect(preferences, dataset) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == EMOJI_PICKER_RECENTS_KEY || key == EMOJI_PICKER_STICKY_KEY) {
                    history = readEmojiPickerHistory(preferences, dataset)
                }
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(preferences, dataset) {
        // Migrate AndroidX aliases, invalid entries and unbounded history once on opening.
        writeEmojiPickerHistory(preferences, history)
    }
    val roots = remember(dataset) { emojiPickerRoots(dataset) }
    val rootsByGroup = remember(roots) { roots.groupBy { it.group } }
    val categories = remember(dataset) { listOf("recents") + dataset.groups.filter { it != "Component" } }
    var category by rememberSaveable { mutableStateOf(if (history.recents.isEmpty()) categories[1] else "recents") }
    val gridStates = remember(dataset) { categories.associateWith { LazyGridState() } }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var variantBase by rememberSaveable { mutableStateOf<String?>(null) }
    val containGridScroll =
        remember {
            object : NestedScrollConnection {
                // A grid gesture belongs to the picker, even when it reaches an edge; do not
                // hand the remaining drag/fling to an enclosing dismissible reaction sheet.
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset = Offset(0f, available.y)

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity = Velocity(0f, available.y)
            }
        }
    val cells =
        if (category == "recents") {
            history.recents.mapNotNull(dataset::entry)
        } else {
            rootsByGroup[category].orEmpty()
        }

    fun pick(
        entry: EmojiEntry,
        rememberVariant: Boolean,
    ) {
        val updated = recordEmojiPickerSelection(readEmojiPickerHistory(preferences, dataset), entry, rememberVariant)
        history = updated
        writeEmojiPickerHistory(preferences, updated)
        searchOpen = false
        onPick(entry.sequence)
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { searchOpen = true }, modifier = Modifier.testTag("${tagPrefix}_emoji_search")) {
                Icon(Icons.Outlined.Search, stringResource(R.string.emoji_picker_search))
            }
            LazyRow(Modifier.weight(1f).testTag("${tagPrefix}_emoji_categories"), contentPadding = PaddingValues(end = 4.dp)) {
                items(categories, key = { it }) { group ->
                    val isSelected = category == group
                    TextButton(
                        onClick = { category = group },
                        modifier =
                            Modifier
                                .testTag("${tagPrefix}_emoji_category_${emojiCategoryTag(group)}")
                                .semantics { selected = isSelected },
                        shape = MaterialTheme.shapes.large,
                        colors =
                            ButtonDefaults.textButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                            ),
                    ) {
                        Text(emojiCategoryLabel(group), maxLines = 1)
                    }
                }
            }
        }
        Text(
            emojiCategoryLabel(category),
            style = MaterialTheme.typography.labelLarge,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag("${tagPrefix}_emoji_category_title")
                    .semantics { heading() },
        )
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (cells.isEmpty()) Text(stringResource(R.string.emoji_picker_empty_recents))
            EmojiPickerGrid(
                cells = cells,
                dataset = dataset,
                sticky = if (category == "recents") emptyMap() else history.sticky,
                state = gridStates.getValue(category),
                tagPrefix = "${tagPrefix}_emoji",
                onPick = { pick(it, false) },
                onVariants = { variantBase = it },
                modifier = Modifier.fillMaxSize().nestedScroll(containGridScroll),
            )
        }
    }
    if (searchOpen) {
        EmojiPickerSearch(
            dataset = dataset,
            roots = roots,
            sticky = history.sticky,
            tagPrefix = "${tagPrefix}_emoji_search",
            onPick = { pick(it, false) },
            onVariants = { variantBase = it },
            onDismiss = { searchOpen = false },
        )
    }
    variantBase?.let { base ->
        val variants = remember(dataset, base) { emojiPickerVariants(dataset, base) }
        val root = dataset.entry(base)
        if (root != null && variants.isNotEmpty()) {
            Dialog(onDismissRequest = { variantBase = null }) {
                Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                    Column(Modifier.fillMaxWidth().padding(8.dp).testTag("${tagPrefix}_emoji_variants")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.emoji_picker_variants, root.name),
                                modifier = Modifier.weight(1f).padding(8.dp).semantics { heading() },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            TextButton(colors = readableTextButtonColors(), onClick = { variantBase = null }) {
                                Text(stringResource(R.string.emoji_picker_close_variants))
                            }
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(48.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            items(variants, key = { it.sequence }) { variant ->
                                EmojiPickerCell(
                                    entry = variant,
                                    tag = "${tagPrefix}_emoji_variant_${emojiPickerTag(variant.sequence)}",
                                    isSelected = variant.sequence == (history.sticky[base] ?: base),
                                    onClick = {
                                        variantBase = null
                                        pick(variant, true)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmojiPickerSearch(
    dataset: EmojiDataset,
    roots: List<EmojiEntry>,
    sticky: Map<String, String>,
    tagPrefix: String,
    onPick: (EmojiEntry) -> Unit,
    onVariants: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            focus.requestFocus()
            keyboard?.show()
        }
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 480.dp)) {
            EmojiPickerSearchContent(
                dataset = dataset,
                roots = roots,
                sticky = sticky,
                tagPrefix = tagPrefix,
                onPick = onPick,
                onVariants = onVariants,
                onDismiss = onDismiss,
                fieldModifier = Modifier.focusRequester(focus),
            )
        }
    }
}

@Composable
internal fun EmojiPickerSearchContent(
    dataset: EmojiDataset,
    roots: List<EmojiEntry>,
    sticky: Map<String, String>,
    tagPrefix: String,
    onPick: (EmojiEntry) -> Unit,
    onVariants: (String) -> Unit,
    onDismiss: () -> Unit,
    fieldModifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val index = remember(roots) { roots.map { EmojiSearchEntry(it.sequence, it.name) } }
    val results =
        remember(dataset, index, query) {
            val exact = dataset.entry(query.trim())
            if (exact != null) listOf(exact) else searchEmojis(index, query, limit = index.size).mapNotNull { dataset.entry(it.emoji) }
        }
    val gridState = rememberLazyGridState()
    Column(Modifier.fillMaxWidth().height(360.dp).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiOutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    gridState.requestScrollToItem(0)
                },
                label = { Text(stringResource(R.string.emoji_picker_search)) },
                singleLine = true,
                modifier = Modifier.weight(1f).then(fieldModifier).testTag("${tagPrefix}_field"),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("${tagPrefix}_close")) {
                Icon(Icons.Filled.Close, stringResource(R.string.emoji_picker_close_search))
            }
        }
        if (results.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (query.isBlank()) R.string.emoji_picker_search_prompt else R.string.emoji_picker_search_empty),
                    modifier = Modifier.testTag(if (query.isBlank()) "${tagPrefix}_prompt" else "${tagPrefix}_empty"),
                )
            }
        } else {
            EmojiPickerGrid(
                cells = results,
                dataset = dataset,
                sticky = sticky,
                state = gridState,
                tagPrefix = tagPrefix,
                onPick = onPick,
                onVariants = onVariants,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

@Composable
private fun EmojiPickerGrid(
    cells: List<EmojiEntry>,
    dataset: EmojiDataset,
    sticky: Map<String, String>,
    state: LazyGridState,
    tagPrefix: String,
    onPick: (EmojiEntry) -> Unit,
    onVariants: (String) -> Unit,
    modifier: Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(48.dp),
        state = state,
        modifier = modifier.testTag("${tagPrefix}_grid"),
        contentPadding = PaddingValues(4.dp),
    ) {
        items(cells, key = { it.sequence }) { root ->
            val displayed = sticky[root.sequence]?.let(dataset::entry) ?: root
            val variants = remember(dataset, root.baseSequence) { emojiPickerVariants(dataset, root.sequence) }
            EmojiPickerCell(
                entry = displayed,
                tag = "${tagPrefix}_cell_${emojiPickerTag(root.sequence)}",
                onClick = { onPick(displayed) },
                onVariants = if (variants.size > 1) ({ onVariants(root.baseSequence) }) else null,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmojiPickerCell(
    entry: EmojiEntry,
    tag: String,
    onClick: () -> Unit,
    onVariants: (() -> Unit)? = null,
    isSelected: Boolean = false,
) {
    val variantsLabel = stringResource(R.string.emoji_picker_show_variants)
    Box(
        modifier =
            Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag(tag)
                .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClickLabel = if (onVariants != null) variantsLabel else null,
                    onLongClick = onVariants,
                ).semantics(mergeDescendants = true) {
                    contentDescription = entry.name
                    selected = isSelected
                    if (onVariants != null) {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(variantsLabel) {
                                    onVariants()
                                    true
                                },
                            )
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(entry.sequence, fontSize = 28.sp, modifier = Modifier.padding(4.dp).clearAndSetSemantics {})
    }
}

@Composable
private fun emojiCategoryLabel(group: String): String =
    stringResource(
        when (group) {
            "recents" -> R.string.emoji_picker_recents
            "Smileys & Emotion" -> R.string.emoji_category_smileys_emotion
            "People & Body" -> R.string.emoji_category_people_body
            "Animals & Nature" -> R.string.emoji_category_animals_nature
            "Food & Drink" -> R.string.emoji_category_food_drink
            "Travel & Places" -> R.string.emoji_category_travel_places
            "Activities" -> R.string.emoji_category_activities
            "Objects" -> R.string.emoji_category_objects
            "Symbols" -> R.string.emoji_category_symbols
            "Flags" -> R.string.emoji_category_flags
            else -> error("Unknown emoji group: $group")
        },
    )
