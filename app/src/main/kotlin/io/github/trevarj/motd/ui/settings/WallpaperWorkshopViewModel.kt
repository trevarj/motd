package io.github.trevarj.motd.ui.settings

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.RoomEntity
import io.github.trevarj.motd.data.fonts.CustomFontStore
import io.github.trevarj.motd.data.prefs.*
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.ui.nav.WallpaperWorkshopRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import kotlin.random.Random

@Serializable
internal enum class WallpaperWorkshopPage { HUB, DETAIL, EDITOR, PREVIEW }

@Serializable
internal enum class WallpaperWorkshopTab { ICONS, PATTERN, COLOR }

@Serializable
internal enum class WallpaperWorkshopPreviewMode { CHAT, WALLPAPER }

@Serializable
internal enum class WallpaperWorkshopPreviewTheme { CURRENT, LIGHT, DARK }

internal enum class WallpaperWorkshopError {
    INVALID,
    LIBRARY_INVALID,
    TOO_LARGE,
    CONFLICT,
    TARGET_UNAVAILABLE,
    SAVED_NOT_APPLIED,
    SVG_UNREADABLE,
    SVG_TOO_LARGE,
    SVG_UNSUPPORTED,
    SVG_NOT_OUTLINE,
}

internal data class WallpaperWorkshopDraft(
    val id: String,
    val name: String,
    val pattern: WallpaperPattern,
    val baseline: SavedWallpaperDesign? = null,
) {
    fun record() = SavedWallpaperDesign(id, name, pattern)
}

internal data class WallpaperWorkshopIconPicker(
    val selectedIds: Set<String>,
    val query: String = "",
    val category: WallpaperCatalogCategory? = null,
    val importing: Boolean = false,
)

internal data class WallpaperWorkshopUiState(
    val appearance: AppearanceConfig = AppearanceConfig(),
    val settings: Settings = Settings(),
    val roomId: Long? = null,
    val room: RoomEntity? = null,
    val entries: List<WallpaperCatalogEntry> = emptyList(),
    val imports: List<ImportedWallpaperIcon> = emptyList(),
    val loaded: Boolean = false,
    val page: WallpaperWorkshopPage = WallpaperWorkshopPage.HUB,
    val detailChoice: WallpaperChatChoice? = null,
    val draft: WallpaperWorkshopDraft? = null,
    val tab: WallpaperWorkshopTab = WallpaperWorkshopTab.ICONS,
    val previewMode: WallpaperWorkshopPreviewMode = WallpaperWorkshopPreviewMode.CHAT,
    val previewTheme: WallpaperWorkshopPreviewTheme = WallpaperWorkshopPreviewTheme.CURRENT,
    val dirty: Boolean = false,
    val busy: Boolean = false,
    val applied: Boolean = false,
    val error: WallpaperWorkshopError? = null,
    val missingAssets: Set<String> = emptySet(),
    val picker: WallpaperWorkshopIconPicker? = null,
    val iconDetail: String? = null,
    val discardConfirm: Boolean = false,
    val deleteConfirm: SavedWallpaperDesign? = null,
    val usedByGlobal: Boolean = false,
    val chatUses: Int = 0,
    val conflicted: Boolean = false,
) {
    private val canWrite get() =
        loaded && !busy && !applied && appearance.wallpaperLibraryReadError == null &&
            picker == null && iconDetail == null && !discardConfirm && deleteConfirm == null

    val canSave: Boolean
        get() {
            val design = draft ?: return false
            return canWrite && !conflicted && missingAssets.isEmpty() &&
                design.name.none(Char::isISOControl) && isWallpaperName(design.name.trim()) &&
                runCatching { validateWallpaperPattern(design.pattern, imports.mapTo(HashSet(), ImportedWallpaperIcon::id)) }.isSuccess
        }

    val canApply: Boolean
        get() =
            canWrite && page != WallpaperWorkshopPage.HUB && (roomId == null || room != null) &&
                if (draft != null) {
                    canSave
                } else {
                    (roomId != null || detailChoice?.pattern != null) &&
                        detailChoice?.pattern?.isValidIn(appearance.wallpaperLibrary) != false
                }

    val renderSpec: WallpaperRenderSpec
        get() {
            val pattern = draft?.pattern ?: return resolveWallpaper(appearance, detailChoice)
            return WallpaperRenderSpec(
                WallpaperSelection(ChatWallpaperPreset.PATTERN),
                pattern,
                imports.filter { icon -> pattern.icons.any { it.assetId == icon.id } },
            )
        }

    val previewAppearance: AppearanceConfig
        get() =
            room?.let {
                appearance.copy(
                    messageBackgroundOpacityPercent = it.messageBackgroundOpacityOverride ?: appearance.messageBackgroundOpacityPercent,
                    messageBackgroundBlur = it.messageBackgroundBlurOverride ?: appearance.messageBackgroundBlur,
                )
            } ?: appearance

    val previewSettings: Settings
        get() =
            room?.let {
                settings.copy(
                    layoutDensity = it.layoutDensityOverride ?: settings.layoutDensity,
                    compactNickBrackets = it.compactNickBracketsOverride ?: settings.compactNickBrackets,
                )
            } ?: settings
}

@HiltViewModel
class WallpaperWorkshopViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        @ApplicationContext private val context: Context,
        appearancePrefs: AppearancePrefs,
        settingsRepository: SettingsRepository,
        private val store: WallpaperWorkshopStore,
        private val bufferStore: BufferStore,
        private val customFontStore: CustomFontStore,
    ) : ViewModel() {
        private val checkpoint =
            savedStateHandle.get<String>(CHECKPOINT_KEY)?.let {
                runCatching { wallpaperJson.decodeFromString<WorkshopCheckpoint>(it) }.getOrNull()
            }
        private val roomId = savedStateHandle.toRoute<WallpaperWorkshopRoute>().bufferId
        private var detailOpen = checkpoint?.detailOpen ?: false
        private var initial = checkpoint?.initial
        private var starterSource = checkpoint?.starterSource
        private var baselineDigest = checkpoint?.baselineDigest
        private var session = checkpoint?.session?.takeIf(::isCanonicalWallpaperUuid) ?: newId()
        private val stage = WallpaperWorkshopStage(context)
        private var stagedNames = checkpoint?.stagedNames.orEmpty()
        private var cachedIcons = emptyMap<String, ImportedWallpaperIcon>()
        private var writingDesign: SavedWallpaperDesign? = null
        private var watchedRef: WallpaperPatternRef? = null
        private var usageJob: Job? = null
        private var importJob: Job? = null
        private val mutableState =
            MutableStateFlow(
                WallpaperWorkshopUiState(
                    roomId = roomId,
                    page = checkpoint?.page ?: WallpaperWorkshopPage.HUB,
                    detailChoice = checkpoint?.detailChoice,
                    draft = checkpoint?.draft?.let { WallpaperWorkshopDraft(it.id, it.name, it.pattern) },
                    tab = checkpoint?.tab ?: WallpaperWorkshopTab.ICONS,
                    previewMode = checkpoint?.previewMode ?: WallpaperWorkshopPreviewMode.CHAT,
                    previewTheme = checkpoint?.previewTheme ?: WallpaperWorkshopPreviewTheme.CURRENT,
                    dirty = checkpoint?.dirty ?: false,
                    picker = checkpoint?.pickerIds?.let { WallpaperWorkshopIconPicker(it) },
                    iconDetail = checkpoint?.iconDetail,
                ),
            )
        internal val uiState: StateFlow<WallpaperWorkshopUiState> = mutableState.asStateFlow()
        val customFontFile: File? get() = customFontStore.installedFile()

        init {
            viewModelScope.launch {
                val currentSession = session
                val names = stagedNames
                val (staged, entries) =
                    withContext(Dispatchers.IO) {
                        runCatching { stage.prune(currentSession) }
                        stage.read(currentSession, names) to runCatching { WallpaperCatalog.entries(context) }.getOrNull()
                    }
                cachedIcons = staged
                val roomFlow = roomId?.let(bufferStore::observeWallpaperTarget) ?: flowOf(null)
                combine(appearancePrefs.config, settingsRepository.settings, roomFlow, ::Triple).collect { (appearance, settings, room) ->
                    val state = mutableState.value
                    val candidate = state.draft?.let { appearance.design(it.id) }
                    val expected = state.draft?.baseline ?: candidate?.takeIf { baselineDigest != null && it.digest() == baselineDigest }
                    val recordChanged = !state.loaded || state.draft?.let { state.appearance.design(it.id) != candidate } == true
                    val ownWrite = writingDesign != null && candidate == writingDesign
                    val conflicted =
                        state.conflicted || baselineDigest != null && recordChanged && !ownWrite &&
                            (expected == null || expected != candidate)
                    val libraryError = appearance.wallpaperLibraryReadError?.asWorkshopError()
                    publish(
                        state.copy(
                            appearance = appearance,
                            settings = settings,
                            room = room,
                            entries = entries.orEmpty(),
                            loaded = true,
                            draft = state.draft?.copy(baseline = expected),
                            conflicted = conflicted,
                            error =
                                state.error ?: when {
                                    !state.loaded || state.appearance.wallpaperLibraryReadError != appearance.wallpaperLibraryReadError -> {
                                        libraryError ?: if (entries == null) WallpaperWorkshopError.LIBRARY_INVALID else null
                                    }

                                    else -> {
                                        null
                                    }
                                } ?: when {
                                    conflicted && !state.conflicted -> WallpaperWorkshopError.CONFLICT
                                    else -> null
                                },
                        ),
                    )
                }
            }
        }

        fun newDesign() {
            if (!readyForEditor()) return
            val pattern =
                WallpaperStarters.entries
                    .first()
                    .pattern
                    .copy(seed = Random.nextInt(), icons = emptyList())
            openEditor(SavedWallpaperDesign(newId(), context.getString(R.string.wallpaper_workshop_untitled), pattern))
        }

        internal fun detail(choice: WallpaperChatChoice?) {
            val state = mutableState.value
            if (!state.loaded || state.busy || state.draft != null || (roomId == null && choice?.pattern == null)) return
            if (choice?.pattern?.isValidIn(state.appearance.wallpaperLibrary) == false) {
                publish(state.copy(error = WallpaperWorkshopError.CONFLICT))
                return
            }
            detailOpen = true
            publish(
                state.copy(
                    page = WallpaperWorkshopPage.DETAIL,
                    detailChoice = choice,
                    previewMode = WallpaperWorkshopPreviewMode.CHAT,
                    previewTheme = WallpaperWorkshopPreviewTheme.CURRENT,
                ),
                persist = true,
            )
        }

        fun editDetail() {
            if (!readyForEditor()) return
            val state = mutableState.value
            val ref = state.detailChoice?.pattern ?: return
            when (ref.kind) {
                WallpaperPatternRefKind.STARTER -> {
                    val starter = WallpaperStarters.find(ref.id) ?: return
                    openEditor(SavedWallpaperDesign(newId(), context.getString(starter.label), starter.pattern), starter = ref)
                }

                WallpaperPatternRefKind.SAVED -> {
                    val saved = state.appearance.design(ref.id)
                    if (saved == null) {
                        publish(state.copy(error = WallpaperWorkshopError.CONFLICT))
                    } else {
                        openEditor(saved, baseline = saved)
                    }
                }
            }
        }

        fun duplicate(ref: WallpaperPatternRef) {
            if (!readyForEditor()) return
            val state = mutableState.value
            val source =
                when (ref.kind) {
                    WallpaperPatternRefKind.SAVED -> {
                        state.appearance.design(ref.id)
                    }

                    WallpaperPatternRefKind.STARTER -> {
                        WallpaperStarters.find(ref.id)?.let {
                            SavedWallpaperDesign(it.id, context.getString(it.label), it.pattern)
                        }
                    }
                }
            if (source == null) {
                publish(state.copy(error = WallpaperWorkshopError.CONFLICT))
            } else {
                openEditor(source.copy(id = newId(), name = copyName(source.name)))
            }
        }

        fun duplicateDraft() {
            val state = mutableState.value
            val draft = state.draft ?: return
            if (state.busy || state.picker != null) return
            val copied = draft.record().copy(id = newId(), name = copyName(draft.name))
            val carryDirty = state.dirty || state.conflicted
            initial = copied.takeUnless { carryDirty }
            baselineDigest = null
            starterSource = null
            publish(
                state.copy(
                    draft = WallpaperWorkshopDraft(copied.id, copied.name, copied.pattern),
                    dirty = carryDirty,
                    conflicted = false,
                    error = null,
                ),
                persist = true,
            )
        }

        fun requestDelete(ref: WallpaperPatternRef) {
            val state = mutableState.value
            if (!state.loaded || state.busy || ref.kind != WallpaperPatternRefKind.SAVED) return
            val saved = state.appearance.design(ref.id)
            if (saved == null) {
                publish(state.copy(error = state.appearance.wallpaperLibraryReadError?.asWorkshopError() ?: WallpaperWorkshopError.CONFLICT))
            } else {
                publish(state.copy(deleteConfirm = saved))
            }
        }

        fun cancelDelete() {
            val state = mutableState.value
            if (!state.busy) publish(state.copy(deleteConfirm = null))
        }

        fun confirmDelete() {
            val expected = mutableState.value.deleteConfirm ?: return
            perform {
                store.deleteDesign(expected, bufferStore::clearWallpaperReferences)
                val state = mutableState.value
                if (state.detailChoice?.pattern == expected.ref()) {
                    detailOpen = false
                    publish(state.copy(page = WallpaperWorkshopPage.HUB, detailChoice = null, deleteConfirm = null), persist = true)
                } else {
                    publish(state.copy(deleteConfirm = null))
                }
            }
        }

        fun dismissError() = publish(mutableState.value.copy(error = null))

        fun back(): Boolean {
            val state = mutableState.value
            if (!state.loaded) return state.page == WallpaperWorkshopPage.HUB
            if (state.busy || state.applied) return false
            when {
                state.deleteConfirm != null -> {
                    cancelDelete()
                }

                state.discardConfirm -> {
                    cancelDiscard()
                }

                state.picker != null -> {
                    cancelPicker()
                }

                state.iconDetail != null -> {
                    closeIcon()
                }

                state.page == WallpaperWorkshopPage.PREVIEW -> {
                    publish(
                        state.copy(page = if (state.draft == null) WallpaperWorkshopPage.DETAIL else WallpaperWorkshopPage.EDITOR),
                        persist = true,
                    )
                }

                state.page == WallpaperWorkshopPage.EDITOR && state.dirty -> {
                    publish(state.copy(discardConfirm = true))
                }

                state.page == WallpaperWorkshopPage.EDITOR -> {
                    discardDraft()
                }

                state.page == WallpaperWorkshopPage.DETAIL -> {
                    detailOpen = false
                    publish(state.copy(page = WallpaperWorkshopPage.HUB, detailChoice = null), persist = true)
                }

                else -> {
                    return true
                }
            }
            return false
        }

        fun confirmDiscard() {
            if (mutableState.value.discardConfirm && !mutableState.value.busy) discardDraft()
        }

        fun cancelDiscard() = publish(mutableState.value.copy(discardConfirm = false))

        fun setName(name: String) = changeDraft { copy(name = name.take(256)) }

        fun updatePattern(transform: (WallpaperPattern) -> WallpaperPattern) = changeDraft { copy(pattern = transform(pattern)) }

        fun shuffle() =
            updatePattern { pattern ->
                var seed: Int
                do {
                    seed = Random.nextInt()
                } while (seed == pattern.seed)
                pattern.copy(seed = seed)
            }

        internal fun setTab(tab: WallpaperWorkshopTab) {
            val state = mutableState.value
            if (!state.busy && state.draft != null) publish(state.copy(tab = tab), persist = true)
        }

        fun preview() {
            val state = mutableState.value
            if (!state.busy && (state.page == WallpaperWorkshopPage.DETAIL || state.page == WallpaperWorkshopPage.EDITOR)) {
                publish(state.copy(page = WallpaperWorkshopPage.PREVIEW), persist = true)
            }
        }

        internal fun setPreviewMode(mode: WallpaperWorkshopPreviewMode) = publish(mutableState.value.copy(previewMode = mode), persist = true)

        internal fun setPreviewTheme(theme: WallpaperWorkshopPreviewTheme) = publish(mutableState.value.copy(previewTheme = theme), persist = true)

        fun save() {
            val state = mutableState.value
            if (state.canSave) writeDraft(checkNotNull(state.draft), apply = false)
        }

        fun apply() {
            val state = mutableState.value
            if (!state.canApply) return
            val draft = state.draft
            if (draft != null) {
                val baseline = draft.baseline
                val pristine = if (state.dirty) null else baseline?.ref() ?: starterSource
                if (pristine == null) {
                    writeDraft(draft, apply = true)
                } else {
                    applyChoice(WallpaperChatChoice(WallpaperChatChoiceKind.PATTERN, pristine), expected = baseline)
                }
            } else {
                val choice = state.detailChoice
                applyChoice(choice, expected = choice?.pattern?.takeIf { it.kind == WallpaperPatternRefKind.SAVED }?.let { state.appearance.design(it.id) })
            }
        }

        fun openPicker() {
            val state = mutableState.value
            val draft = state.draft ?: return
            if (!state.busy && state.picker == null) {
                publish(state.copy(picker = WallpaperWorkshopIconPicker(draft.pattern.icons.mapTo(LinkedHashSet(), WallpaperPatternIcon::assetId))), persist = true)
            }
        }

        fun setPickerQuery(query: String) = updatePicker { copy(query = query.take(128)) }

        internal fun setPickerCategory(category: WallpaperCatalogCategory?) = updatePicker { copy(category = category) }

        fun togglePickerIcon(assetId: String) {
            val state = mutableState.value
            val picker = state.picker ?: return
            if (state.busy || picker.importing) return
            val ids =
                when {
                    assetId in picker.selectedIds -> picker.selectedIds - assetId
                    picker.selectedIds.size >= MAX_WALLPAPER_ICONS -> return
                    WallpaperCatalog.contains(assetId) || state.imports.any { it.id == assetId } -> picker.selectedIds + assetId
                    else -> return
                }
            publish(state.copy(picker = picker.copy(selectedIds = ids)), persist = true)
        }

        fun finishPicker() {
            val state = mutableState.value
            val draft = state.draft ?: return
            val picker = state.picker ?: return
            if (picker.importing || state.busy) return
            val existing = draft.pattern.icons.associateBy(WallpaperPatternIcon::assetId)
            val selected = picker.selectedIds.map { existing[it] ?: WallpaperPatternIcon(it) }
            retainStage(draft, picker.selectedIds)
            val importsToStage =
                state.imports.filter {
                    it.id in picker.selectedIds && (it.id !in stagedNames || it.id !in cachedIcons)
                }
            if (importsToStage.isEmpty()) {
                finishPickerWith(draft, selected)
            } else {
                perform {
                    writeStage(importsToStage)
                    finishPickerWith(draft, selected)
                }
            }
        }

        fun cancelPicker() {
            val state = mutableState.value
            if (state.busy || state.picker == null) return
            importJob?.cancel()
            retainStage(state.draft)
            publish(state.copy(picker = null), persist = true)
        }

        fun importSvg(uri: Uri) {
            val state = mutableState.value
            val picker = state.picker ?: return
            if (state.busy || picker.importing) return
            val sessionAtImport = session
            publish(state.copy(picker = picker.copy(importing = true), error = null))
            importJob =
                viewModelScope.launch {
                    try {
                        val imported = WallpaperSvg.import(context, uri)
                        val live = mutableState.value
                        val selection = live.picker ?: return@launch
                        if (imported.id !in selection.selectedIds && selection.selectedIds.size >= MAX_WALLPAPER_ICONS) {
                            publish(live.copy(picker = selection.copy(importing = false)))
                            return@launch
                        }
                        val installed =
                            live.appearance.wallpaperLibrary
                                ?.imports
                                ?.firstOrNull { it.id == imported.id }
                        val icon = installed ?: cachedIcons[imported.id] ?: imported
                        if (installed == null) {
                            val required =
                                live.draft
                                    ?.pattern
                                    ?.icons
                                    .orEmpty()
                                    .mapTo(selection.selectedIds.toMutableSet(), WallpaperPatternIcon::assetId)
                            required.add(icon.id)
                            val overflow = (stagedNames.size + (if (icon.id in stagedNames) 0 else 1) - 2 * MAX_WALLPAPER_ICONS).coerceAtLeast(0)
                            val evicted =
                                stagedNames.keys
                                    .filterNot { it in required }
                                    .take(overflow)
                                    .toSet()
                            val keep = stagedNames.keys - evicted + icon.id
                            withContext(Dispatchers.IO) { stage.write(sessionAtImport, listOf(icon), keep) }
                            stagedNames = stagedNames - evicted + (icon.id to icon.name)
                            cachedIcons = cachedIcons - evicted + (icon.id to icon)
                        }
                        val current = mutableState.value
                        val currentPicker = current.picker ?: return@launch
                        publish(current.copy(picker = currentPicker.copy(selectedIds = currentPicker.selectedIds + icon.id, importing = false)), persist = true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        val current = mutableState.value
                        publish(current.copy(picker = current.picker?.copy(importing = false), error = failure.asWorkshopError()))
                    }
                }
        }

        fun editIcon(assetId: String) {
            val state = mutableState.value
            if (!state.busy && state.draft
                    ?.pattern
                    ?.icons
                    ?.any { it.assetId == assetId } == true
            ) {
                publish(state.copy(iconDetail = assetId), persist = true)
            }
        }

        fun closeIcon() = publish(mutableState.value.copy(iconDetail = null), persist = true)

        fun removeIcon(assetId: String) {
            val state = mutableState.value
            val draft = state.draft ?: return
            if (state.busy || state.picker != null) return
            val changed = draft.copy(pattern = draft.pattern.copy(icons = draft.pattern.icons.filterNot { it.assetId == assetId }))
            retainStage(changed)
            publishDraft(state.copy(iconDetail = state.iconDetail.takeUnless { it == assetId }), changed)
        }

        fun updateIcon(
            assetId: String,
            transform: (WallpaperPatternIcon) -> WallpaperPatternIcon,
        ) {
            updatePattern { pattern -> pattern.copy(icons = pattern.icons.map { if (it.assetId == assetId) transform(it).copy(assetId = assetId) else it }) }
        }

        private fun readyForEditor(): Boolean {
            val state = mutableState.value
            val error = state.appearance.wallpaperLibraryReadError?.asWorkshopError()
            if (error != null) publish(state.copy(error = error))
            return state.loaded && !state.busy && state.draft == null && error == null
        }

        private fun openEditor(
            design: SavedWallpaperDesign,
            baseline: SavedWallpaperDesign? = null,
            starter: WallpaperPatternRef? = null,
        ) {
            val imports = mutableState.value.imports.filter { icon -> design.pattern.icons.any { it.assetId == icon.id } }

            fun open() {
                initial = design.takeIf { baseline == null }
                baselineDigest = baseline?.digest()
                starterSource = starter
                val state = mutableState.value
                val conflicted = baseline != null && state.appearance.design(baseline.id) != baseline
                publish(
                    state.copy(
                        draft = WallpaperWorkshopDraft(design.id, design.name, design.pattern, baseline),
                        page = WallpaperWorkshopPage.EDITOR,
                        tab = WallpaperWorkshopTab.ICONS,
                        previewMode = WallpaperWorkshopPreviewMode.CHAT,
                        previewTheme = WallpaperWorkshopPreviewTheme.CURRENT,
                        dirty = false,
                        conflicted = conflicted,
                        error = if (conflicted) WallpaperWorkshopError.CONFLICT else null,
                    ),
                    persist = true,
                )
            }
            if (imports.isEmpty()) {
                open()
            } else {
                perform {
                    writeStage(imports)
                    open()
                }
            }
        }

        private fun changeDraft(transform: WallpaperWorkshopDraft.() -> WallpaperWorkshopDraft) {
            val state = mutableState.value
            val draft = state.draft ?: return
            if (state.busy || state.picker != null) return
            val next = draft.transform()
            if (next == draft || next.pattern.icons.size > MAX_WALLPAPER_ICONS || next.pattern.icons.any { it.assetId.length > 68 }) return
            publishDraft(state, next)
        }

        private fun publishDraft(
            state: WallpaperWorkshopUiState,
            draft: WallpaperWorkshopDraft,
        ) {
            val dirty = draft.record() != (draft.baseline ?: initial)
            publish(state.copy(draft = draft, dirty = dirty), persist = true)
        }

        private fun finishPickerWith(
            draft: WallpaperWorkshopDraft,
            selected: List<WallpaperPatternIcon>,
        ) {
            val next = draft.copy(pattern = draft.pattern.copy(icons = selected))
            retainStage(next)
            publishDraft(mutableState.value.copy(picker = null), next)
        }

        private fun updatePicker(transform: WallpaperWorkshopIconPicker.() -> WallpaperWorkshopIconPicker) {
            val state = mutableState.value
            val picker = state.picker ?: return
            if (!state.busy) publish(state.copy(picker = picker.transform()))
        }

        private fun writeDraft(
            draft: WallpaperWorkshopDraft,
            apply: Boolean,
        ) {
            val target = roomId.takeIf { apply }
            val checkTarget: (suspend () -> Boolean)? =
                if (target == null) null else suspend { checkRoomQuota { bufferStore.checkWallpaperAssignment(target, true) } }
            val applyTarget: (suspend (WallpaperChatChoice) -> Boolean)? =
                if (target == null) null else { choice -> bufferStore.setWallpaperOverride(target, choice) }
            val imports = mutableState.value.imports.filter { icon -> draft.pattern.icons.any { it.assetId == icon.id } }
            perform {
                val record = draft.record().let { it.copy(name = it.name.trim()) }
                writingDesign = record
                val result =
                    try {
                        store.saveDesign(
                            design = record,
                            expected = draft.baseline,
                            imports = imports,
                            applyGlobally = apply && roomId == null,
                            checkChatTarget = checkTarget,
                            applyToChat = applyTarget,
                        )
                    } finally {
                        writingDesign = null
                    }
                val saved = result.design
                val oldSession = resetStageSession()
                // Keep the committed glyphs available while the appearance flow delivers its update.
                cachedIcons = imports.associateBy(ImportedWallpaperIcon::id)
                initial = null
                baselineDigest = saved.digest()
                starterSource = null
                val state = mutableState.value
                val error = if (result.chatApplied == false) WallpaperWorkshopError.SAVED_NOT_APPLIED else null
                publish(
                    state.copy(
                        draft = WallpaperWorkshopDraft(saved.id, saved.name, saved.pattern, saved),
                        detailChoice = if (detailOpen) WallpaperChatChoice(WallpaperChatChoiceKind.PATTERN, saved.ref()) else null,
                        dirty = false,
                        applied = false,
                        conflicted = false,
                        error = error,
                    ),
                    persist = true,
                )
                withContext(NonCancellable + Dispatchers.IO) { stage.discard(oldSession) }
                if (apply && error == null) publish(mutableState.value.copy(applied = true))
            }
        }

        private fun applyChoice(
            choice: WallpaperChatChoice?,
            expected: SavedWallpaperDesign?,
        ) {
            perform {
                if (roomId == null) {
                    store.applyGlobal(checkNotNull(choice?.pattern), expected)
                } else {
                    val applied =
                        store.applyToChat(choice, expected) { selected ->
                            checkRoomQuota { bufferStore.setWallpaperOverride(roomId, selected) }
                        }
                    if (!applied) throw WallpaperTargetUnavailableException()
                }
                publish(mutableState.value.copy(applied = true))
            }
        }

        private fun discardDraft() {
            initial = null
            baselineDigest = null
            starterSource = null
            val oldSession = resetStageSession()
            cachedIcons = emptyMap()
            publish(
                mutableState.value.copy(
                    draft = null,
                    page = if (detailOpen) WallpaperWorkshopPage.DETAIL else WallpaperWorkshopPage.HUB,
                    dirty = false,
                    conflicted = false,
                    error = null,
                    discardConfirm = false,
                    picker = null,
                    iconDetail = null,
                ),
                persist = true,
            )
            viewModelScope.launch { withContext(NonCancellable + Dispatchers.IO) { stage.discard(oldSession) } }
        }

        private fun resetStageSession(): String =
            session.also {
                session = newId()
                stagedNames = emptyMap()
            }

        private fun retainStage(
            draft: WallpaperWorkshopDraft?,
            extra: Set<String> = emptySet(),
        ) {
            val keep =
                draft
                    ?.pattern
                    ?.icons
                    .orEmpty()
                    .mapTo(extra.toMutableSet(), WallpaperPatternIcon::assetId)
            stagedNames = stagedNames.filterKeys { it in keep }
            cachedIcons = cachedIcons.filterKeys { it in keep }
        }

        private suspend fun writeStage(imports: List<ImportedWallpaperIcon>) {
            val keep = stagedNames.keys + imports.map(ImportedWallpaperIcon::id)
            withContext(Dispatchers.IO) { stage.write(session, imports, keep) }
            stagedNames = stagedNames + imports.associate { it.id to it.name }
            cachedIcons = cachedIcons + imports.associateBy(ImportedWallpaperIcon::id)
        }

        private fun perform(action: suspend () -> Unit) {
            val state = mutableState.value
            if (state.busy || state.applied) return
            publish(state.copy(busy = true, error = null))
            viewModelScope.launch {
                try {
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val current = mutableState.value
                    publish(
                        current.copy(
                            conflicted = current.conflicted || (current.draft != null && failure is WallpaperConflictException),
                            deleteConfirm = null,
                            error = failure.asWorkshopError(),
                        ),
                    )
                } finally {
                    publish(mutableState.value.copy(busy = false))
                }
            }
        }

        private fun publish(
            state: WallpaperWorkshopUiState,
            persist: Boolean = false,
        ) {
            val libraryImports =
                state.appearance.wallpaperLibrary
                    ?.imports
                    .orEmpty()
            val imports = if (cachedIcons.isEmpty()) libraryImports else (libraryImports + cachedIcons.values).distinctBy(ImportedWallpaperIcon::id)
            val missing =
                state.draft
                    ?.pattern
                    ?.icons
                    .orEmpty()
                    .asSequence()
                    .map(WallpaperPatternIcon::assetId)
                    .filterNot { id -> WallpaperCatalog.contains(id) || imports.any { it.id == id } }
                    .toSet()
            val reference =
                state.deleteConfirm?.ref() ?: state.draft?.baseline?.ref()
                    ?: state.detailChoice?.pattern?.takeIf { state.draft == null && it.kind == WallpaperPatternRefKind.SAVED }
            val newRef = reference != watchedRef
            mutableState.value =
                state.copy(
                    imports = imports,
                    missingAssets = missing,
                    usedByGlobal = reference != null && state.appearance.wallpaper.patternRef == reference,
                    chatUses = if (newRef) 0 else state.chatUses,
                )
            if (newRef) {
                watchedRef = reference
                usageJob?.cancel()
                usageJob =
                    reference?.let { ref ->
                        viewModelScope.launch {
                            bufferStore.observeWallpaperUseCount(ref).collect { count ->
                                if (watchedRef == ref) mutableState.value = mutableState.value.copy(chatUses = count)
                            }
                        }
                    }
            }
            if (persist) persistCheckpoint()
        }

        private fun persistCheckpoint() {
            val state = mutableState.value
            savedStateHandle[CHECKPOINT_KEY] =
                wallpaperJson.encodeToString(
                    WorkshopCheckpoint(
                        session = session,
                        stagedNames = stagedNames,
                        detailOpen = detailOpen,
                        detailChoice = state.detailChoice,
                        page = state.page,
                        draft = state.draft?.record(),
                        initial = initial,
                        baselineDigest = baselineDigest,
                        starterSource = starterSource,
                        tab = state.tab,
                        previewMode = state.previewMode,
                        previewTheme = state.previewTheme,
                        dirty = state.dirty,
                        pickerIds = state.picker?.selectedIds,
                        iconDetail = state.iconDetail,
                    ),
                )
        }

        private fun copyName(name: String): String {
            val suffixLength = context.getString(R.string.wallpaper_workshop_copy, "").length
            val base =
                name
                    .trim()
                    .take((64 - suffixLength).coerceAtLeast(0))
                    .trimEnd()
                    .dropLastWhile(Char::isHighSurrogate)
            return context.getString(R.string.wallpaper_workshop_copy, base).trim()
        }
    }

@Serializable
private data class WorkshopCheckpoint(
    val session: String,
    val stagedNames: Map<String, String>,
    val detailOpen: Boolean,
    val detailChoice: WallpaperChatChoice?,
    val page: WallpaperWorkshopPage,
    val draft: SavedWallpaperDesign?,
    val initial: SavedWallpaperDesign?,
    val baselineDigest: String?,
    val starterSource: WallpaperPatternRef?,
    val tab: WallpaperWorkshopTab,
    val previewMode: WallpaperWorkshopPreviewMode,
    val previewTheme: WallpaperWorkshopPreviewTheme,
    val dirty: Boolean,
    val pickerIds: Set<String>?,
    val iconDetail: String?,
)

private const val CHECKPOINT_KEY = "wallpaper-workshop-checkpoint"

private fun AppearanceConfig.design(id: String): SavedWallpaperDesign? = wallpaperLibrary?.designs?.firstOrNull { it.id == id }

private fun newId(): String = UUID.randomUUID().toString()

private fun SavedWallpaperDesign.ref() = WallpaperPatternRef(WallpaperPatternRefKind.SAVED, id)

private fun SavedWallpaperDesign.digest(): String =
    Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(wallpaperJson.encodeToString(this).toByteArray()),
        Base64.NO_WRAP,
    )

private fun WallpaperLibraryReadError.asWorkshopError(): WallpaperWorkshopError =
    when (this) {
        WallpaperLibraryReadError.INVALID -> WallpaperWorkshopError.LIBRARY_INVALID
        WallpaperLibraryReadError.TOO_LARGE -> WallpaperWorkshopError.TOO_LARGE
    }

private fun Exception.asWorkshopError(): WallpaperWorkshopError =
    when (this) {
        is WallpaperLibraryException -> {
            error.asWorkshopError()
        }

        is WallpaperConflictException -> {
            WallpaperWorkshopError.CONFLICT
        }

        is WallpaperTargetUnavailableException -> {
            WallpaperWorkshopError.TARGET_UNAVAILABLE
        }

        is WallpaperSvgException -> {
            when (error) {
                WallpaperSvgError.UNREADABLE -> WallpaperWorkshopError.SVG_UNREADABLE
                WallpaperSvgError.TOO_LARGE -> WallpaperWorkshopError.SVG_TOO_LARGE
                WallpaperSvgError.UNSUPPORTED -> WallpaperWorkshopError.SVG_UNSUPPORTED
                WallpaperSvgError.NOT_OUTLINE -> WallpaperWorkshopError.SVG_NOT_OUTLINE
            }
        }

        else -> {
            WallpaperWorkshopError.INVALID
        }
    }

private inline fun checkRoomQuota(check: () -> Boolean): Boolean =
    try {
        check()
    } catch (error: IllegalArgumentException) {
        throw WallpaperLibraryException(WallpaperLibraryReadError.TOO_LARGE, error)
    }
