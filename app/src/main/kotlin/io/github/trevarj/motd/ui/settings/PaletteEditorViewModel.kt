package io.github.trevarj.motd.ui.settings

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.prefs.*
import io.github.trevarj.motd.ui.chat.parseColorHex
import io.github.trevarj.motd.ui.chat.wallpaperColorHex
import io.github.trevarj.motd.ui.nav.PaletteEditorRoute
import io.github.trevarj.motd.ui.theme.MotdDarkRaw
import io.github.trevarj.motd.ui.theme.MotdLightRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

data class PaletteEditorState(
    val draft: PaletteDocument? = null,
    val fields: Map<String, String> = emptyMap(),
    val settings: Settings = Settings(),
    val appearance: AppearanceConfig = AppearanceConfig(),
    val assignmentTarget: PaletteMode = PaletteMode.SYSTEM,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    val revisionLimit: Boolean = false,
    val rejectedFields: Set<String> = emptySet(),
) {
    val invalidFields: Boolean get() =
        rejectedFields.isNotEmpty() ||
            fields.any { (key, value) ->
                if (key == "name") {
                    value.trim().let { it.isBlank() || it.codePointCount(0, it.length) > 64 } || value.any(Char::isISOControl)
                } else {
                    parseColorHex(value) == null
                }
            }
}

@HiltViewModel
class PaletteEditorViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        private val appearancePrefs: AppearancePrefs,
        settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(PaletteEditorState())
        val state = mutableState.asStateFlow()
        private var original: PaletteDocument? = null
        private var editingId: String? = null
        private var assignmentTarget: PaletteMode = PaletteMode.SYSTEM

        init {
            viewModelScope.launch {
                try {
                    val route = runCatching { savedStateHandle.toRoute<PaletteEditorRoute>() }.getOrNull()
                    val routeJson = route?.paletteJson ?: savedStateHandle.get<String>("paletteJson") ?: error("Missing palette draft")
                    val incoming = decodePalette(routeJson.toByteArray(Charsets.UTF_8))
                    editingId = route?.editingInstalledId ?: savedStateHandle["editingInstalledId"]
                    assignmentTarget = route?.assignmentTarget ?: PaletteMode.SYSTEM
                    val appearance = appearancePrefs.config.first()
                    val installed = editingId?.let { id -> appearance.customPalettes.find { it.document.id == id } ?: error("The palette being edited no longer exists") }
                    val local = installed?.takeIf { it.catalogId == null }
                    if (local != null) require(incoming.id == local.document.id) { "Palette edit target does not match" }
                    original = local?.document
                    val restored = savedStateHandle.get<String>("palette_editor_draft")
                    val draft =
                        if (restored != null) {
                            decodePalette(restored.toByteArray(Charsets.UTF_8))
                        } else if (installed?.catalogId != null) {
                            editingId = null
                            incoming.copy(id = UUID.randomUUID().toString(), revision = 1)
                        } else {
                            incoming
                        }
                    if (installed?.catalogId != null) editingId = null
                    val fieldsJson = savedStateHandle.get<String>("palette_editor_fields")
                    val fields =
                        if (fieldsJson != null) {
                            require(fieldsJson.toByteArray().size <= 8192) { "Draft fields are too large" }
                            Json.decodeFromString<Map<String, String>>(fieldsJson).also { values ->
                                require(values.size <= 69 && values.all { (key, value) -> key.length <= 40 && value.length <= if (key == "name") 256 else 32 }) { "Invalid draft fields" }
                            }
                        } else {
                            emptyMap()
                        }
                    val rejectedFields =
                        savedStateHandle
                            .get<ArrayList<String>>("palette_editor_rejected_fields")
                            .orEmpty()
                            .also { keys ->
                                require(keys.size <= 69 && keys.all { it.length <= 40 }) { "Invalid rejected draft fields" }
                            }.toSet()
                    mutableState.value = PaletteEditorState(draft, fields, appearance = appearance, assignmentTarget = assignmentTarget, dirty = savedStateHandle["palette_editor_dirty"] ?: false, rejectedFields = rejectedFields)
                    persist()
                    launch { appearancePrefs.config.collect { mutableState.value = mutableState.value.copy(appearance = it) } }
                    launch { settingsRepository.settings.collect { mutableState.value = mutableState.value.copy(settings = it) } }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    mutableState.value = mutableState.value.copy(error = failure.message ?: "Unable to restore palette")
                }
            }
        }

        fun setName(value: String) {
            editField("name", value) { it.copy(name = value.trim().takeIf { name -> name.isNotBlank() && name.codePointCount(0, name.length) <= 64 && name.none(Char::isISOControl) } ?: it.name) }
        }

        fun setColor(
            mode: PaletteMode,
            role: String,
            value: String,
        ) {
            val draft = mutableState.value.draft ?: return
            val colors =
                when (mode) {
                    PaletteMode.LIGHT -> draft.light
                    PaletteMode.DARK -> draft.dark
                    PaletteMode.SYSTEM -> null
                } ?: return
            when (role) {
                "primary", "secondary", "tertiary", "background", "surface", "surfaceContainer",
                "onBackground", "onSurface", "onSurfaceVariant", "outlineVariant",
                -> Unit

                else -> if (colors.nicknameColors.indices.none { role == "nick_$it" }) return
            }
            editField("${mode.name}:$role", value) { document ->
                val color = parseColorHex(value)?.let(::wallpaperColorHex) ?: return@editField document
                document.updateMode(mode) { it.withRole(role, color) }
            }
        }

        private fun editField(
            key: String,
            value: String,
            update: (PaletteDocument) -> PaletteDocument,
        ) {
            val current = mutableState.value
            val draft = current.draft ?: return
            if (current.saving) return
            val fields = current.fields + (key to value)
            if (value.length > (if (key == "name") 256 else 32) ||
                fields.size > 69 || Json.encodeToString(fields).toByteArray(Charsets.UTF_8).size > 8192
            ) {
                mutableState.value = current.copy(rejectedFields = current.rejectedFields + key, dirty = true, error = "Draft fields are too large")
                persist()
                return
            }
            val candidate = update(draft)
            val result = runCatching { decodePalette(encodePalette(candidate)) }
            mutableState.value = current.copy(draft = result.getOrDefault(draft), fields = fields, rejectedFields = current.rejectedFields - key, dirty = true, error = result.exceptionOrNull()?.message, revisionLimit = false)
            persist()
        }

        fun addMode(mode: PaletteMode) =
            change { draft ->
                val seed = (if (mode == PaletteMode.DARK) MotdDarkRaw else MotdLightRaw).editorColors()
                when (mode) {
                    PaletteMode.LIGHT -> if (draft.light == null) draft.copy(light = seed) else draft
                    PaletteMode.DARK -> if (draft.dark == null) draft.copy(dark = seed) else draft
                    PaletteMode.SYSTEM -> draft
                }
            }

        fun removeMode(mode: PaletteMode) {
            if (mutableState.value.saving || mode == PaletteMode.SYSTEM) return
            val draft = mutableState.value.draft ?: return
            if (draft.light == null || draft.dark == null) return
            change { if (mode == PaletteMode.LIGHT) it.copy(light = null) else it.copy(dark = null) }
            mutableState.value =
                mutableState.value.copy(
                    fields = mutableState.value.fields.filterKeys { !it.startsWith("${mode.name}:") },
                    rejectedFields =
                        mutableState.value.rejectedFields
                            .filterNot { it.startsWith("${mode.name}:") }
                            .toSet(),
                )
            persist()
        }

        fun addNickname(mode: PaletteMode) = change { it.updateMode(mode) { colors -> if (colors.nicknameColors.size < 24) colors.copy(nicknameColors = colors.nicknameColors + colors.primary) else colors } }

        fun removeNickname(
            mode: PaletteMode,
            index: Int,
        ) = changeNicknames(mode) { colors -> colors.filterIndexed { at, _ -> at != index } }

        fun moveNickname(
            mode: PaletteMode,
            index: Int,
            offset: Int,
        ) = changeNicknames(mode) { colors ->
            colors.toMutableList().apply { if (index in indices && index + offset in indices) add(index + offset, removeAt(index)) }
        }

        private fun changeNicknames(
            mode: PaletteMode,
            update: (List<String>) -> List<String>,
        ) {
            val current = mutableState.value
            if (current.saving) return
            val colors = if (mode == PaletteMode.DARK) current.draft?.dark else current.draft?.light
            colors ?: return
            val order = update(colors.nicknameColors.indices.map(Int::toString))
            change { it.updateMode(mode) { colors -> colors.copy(nicknameColors = update(colors.nicknameColors)) } }
            val fields = current.fields.filterKeys { !it.startsWith("${mode.name}:nick_") }.toMutableMap()
            val rejectedFields = current.rejectedFields.filterNot { it.startsWith("${mode.name}:nick_") }.toMutableSet()
            order.forEachIndexed { newIndex, oldIndex ->
                current.fields["${mode.name}:nick_$oldIndex"]?.let { fields["${mode.name}:nick_$newIndex"] = it }
                if ("${mode.name}:nick_$oldIndex" in current.rejectedFields) rejectedFields += "${mode.name}:nick_$newIndex"
            }
            mutableState.value = mutableState.value.copy(fields = fields, rejectedFields = rejectedFields)
            persist()
        }

        private fun change(update: (PaletteDocument) -> PaletteDocument) {
            val current = mutableState.value
            val draft = current.draft ?: return
            if (current.saving) return
            try {
                val candidate = decodePalette(encodePalette(update(draft)))
                mutableState.value = current.copy(draft = candidate, dirty = current.dirty || candidate != draft, error = null, revisionLimit = false)
                persist()
            } catch (failure: IllegalArgumentException) {
                mutableState.value = current.copy(error = failure.message)
            }
        }

        fun save(
            apply: Boolean,
            duplicate: Boolean = false,
            mode: PaletteMode? = null,
        ) {
            val current = mutableState.value
            val draft = current.draft ?: return
            if (current.saving || current.invalidFields) return
            viewModelScope.launch {
                mutableState.value = mutableState.value.copy(saving = true, error = null)
                try {
                    var snapshot = draft.copy(name = current.fields["name"] ?: draft.name)
                    current.fields.filterKeys { it != "name" }.forEach { (key, value) ->
                        val parts = key.split(':', limit = 2)
                        require(parts.size == 2) { "Invalid color field" }
                        val color = parseColorHex(value) ?: error("Enter a six-digit hex color")
                        snapshot = snapshot.updateMode(PaletteMode.valueOf(parts[0])) { it.withRole(parts[1], wallpaperColorHex(color)) }
                    }
                    snapshot = decodePalette(encodePalette(snapshot))
                    val baseline = original
                    snapshot =
                        if (duplicate) {
                            snapshot.copy(id = UUID.randomUUID().toString(), revision = 1)
                        } else if (baseline != null) {
                            prepareLocalPaletteSave(baseline, snapshot)
                        } else {
                            snapshot
                        }
                    snapshot = decodePalette(encodePalette(snapshot))
                    val chosenMode = mode ?: assignmentTarget
                    require(!apply || chosenMode != PaletteMode.LIGHT || snapshot.light != null) { "Add a light variant before applying to Light" }
                    require(!apply || chosenMode != PaletteMode.DARK || snapshot.dark != null) { "Add a dark variant before applying to Dark" }
                    appearancePrefs.savePalette(InstalledPalette(snapshot), apply, chosenMode, if (duplicate) null else editingId)
                    mutableState.value = mutableState.value.copy(draft = snapshot, saved = true, saving = false, dirty = false)
                    persist()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    mutableState.value = mutableState.value.copy(saving = false, error = failure.message ?: "Unable to save palette", revisionLimit = original?.revision == Int.MAX_VALUE)
                }
            }
        }

        private fun persist() {
            val current = mutableState.value
            current.draft?.let { savedStateHandle["palette_editor_draft"] = encodePalette(it).toString(Charsets.UTF_8) }
            savedStateHandle["palette_editor_fields"] = Json.encodeToString(current.fields)
            savedStateHandle["palette_editor_rejected_fields"] = ArrayList(current.rejectedFields)
            savedStateHandle["palette_editor_dirty"] = current.dirty
        }
    }

internal fun PaletteDocument.updateMode(
    mode: PaletteMode,
    update: (PaletteColors) -> PaletteColors,
): PaletteDocument =
    when (mode) {
        PaletteMode.LIGHT -> copy(light = light?.let(update))
        PaletteMode.DARK -> copy(dark = dark?.let(update))
        PaletteMode.SYSTEM -> this
    }

internal fun PaletteColors.role(role: String): String =
    when (role) {
        "primary" -> primary
        "secondary" -> secondary
        "tertiary" -> tertiary
        "background" -> background
        "surface" -> surface
        "surfaceContainer" -> surfaceContainer
        "onBackground" -> onBackground
        "onSurface" -> onSurface
        "onSurfaceVariant" -> onSurfaceVariant
        "outlineVariant" -> outlineVariant
        else -> nicknameColors[role.removePrefix("nick_").toInt()]
    }

internal fun PaletteColors.withRole(
    role: String,
    value: String,
): PaletteColors =
    when (role) {
        "primary" -> copy(primary = value)
        "secondary" -> copy(secondary = value)
        "tertiary" -> copy(tertiary = value)
        "background" -> copy(background = value)
        "surface" -> copy(surface = value)
        "surfaceContainer" -> copy(surfaceContainer = value)
        "onBackground" -> copy(onBackground = value)
        "onSurface" -> copy(onSurface = value)
        "onSurfaceVariant" -> copy(onSurfaceVariant = value)
        "outlineVariant" -> copy(outlineVariant = value)
        else -> copy(nicknameColors = nicknameColors.toMutableList().apply { this[role.removePrefix("nick_").toInt()] = value })
    }

private fun ColorScheme.editorColors() =
    PaletteColors(
        wallpaperColorHex(primary.toArgb()),
        wallpaperColorHex(secondary.toArgb()),
        wallpaperColorHex(tertiary.toArgb()),
        wallpaperColorHex(background.toArgb()),
        wallpaperColorHex(surface.toArgb()),
        wallpaperColorHex(surfaceContainer.toArgb()),
        wallpaperColorHex(onBackground.toArgb()),
        wallpaperColorHex(onSurface.toArgb()),
        wallpaperColorHex(onSurfaceVariant.toArgb()),
        wallpaperColorHex(outlineVariant.toArgb()),
    )
