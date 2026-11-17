package io.github.trevarj.motd.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.data.prefs.InstalledPalette
import io.github.trevarj.motd.data.prefs.PALETTE_MAX_URL_CHARACTERS
import io.github.trevarj.motd.data.prefs.PaletteDocument
import io.github.trevarj.motd.data.prefs.PaletteMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.decodePalette
import io.github.trevarj.motd.data.prefs.encodePalette
import io.github.trevarj.motd.data.prefs.paletteSelection
import io.github.trevarj.motd.data.prefs.validateInstalledPalette
import io.github.trevarj.motd.ui.nav.PalettePreviewRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID
import javax.inject.Inject

enum class PaletteConflictChoice { KEEP_BOTH, REPLACE }

data class PalettePreviewUiState(
    val document: PaletteDocument? = null,
    val settings: Settings = Settings(),
    val appearance: AppearanceConfig = AppearanceConfig(),
    val mode: PaletteMode = PaletteMode.SYSTEM,
    val assignmentTarget: PaletteMode = PaletteMode.SYSTEM,
    val sourceHostname: String? = null,
    val targetInstalledId: String? = null,
    val previousDocument: PaletteDocument? = null,
    val conflict: Boolean = false,
    val conflictChoice: PaletteConflictChoice = PaletteConflictChoice.KEEP_BOTH,
    val saving: Boolean = false,
    val completed: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class PalettePreviewViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        private val appearancePrefs: AppearancePrefs,
        settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private var snapshot: InstalledPalette? = null
        private var pendingApply = false
        private val local = MutableStateFlow(PalettePreviewUiState())

        init {
            viewModelScope.launch {
                val appearance = appearancePrefs.config.first()
                local.value = loadSnapshot(appearance)
            }
        }

        val uiState =
            combine(local, appearancePrefs.config, settingsRepository.settings) { state, appearance, settings ->
                val target = appearance.customPalettes.find { it.document.id == state.targetInstalledId }
                val targetError =
                    if (state.targetInstalledId == null) {
                        null
                    } else {
                        when {
                            target == null -> "Update target is no longer installed"
                            target.catalogId != snapshot?.catalogId -> "Update target no longer tracks this catalog palette"
                            (target.catalogRevision ?: 0) > (snapshot?.catalogRevision ?: 0) -> "A newer revision is already installed"
                            state.previousDocument != null && target.document != state.previousDocument -> "Update target changed; reopen its preview"
                            else -> null
                        }
                    }
                state.copy(
                    appearance = appearance,
                    settings = settings,
                    error = state.error ?: targetError,
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

        private fun loadSnapshot(appearance: AppearanceConfig): PalettePreviewUiState =
            try {
                val route = savedStateHandle.toRoute<PalettePreviewRoute>()
                val document = decodePalette(route.paletteJson.toByteArray(Charsets.UTF_8))
                val installed =
                    InstalledPalette(
                        document = document,
                        catalogId = route.catalogId,
                        catalogRevision = route.catalogId?.let { document.revision },
                        sourceLink = route.sourceLink?.takeIf { it.codePointCount(0, it.length) <= PALETTE_MAX_URL_CHARACTERS },
                    )
                validateInstalledPalette(installed)
                require(route.targetInstalledId == null || route.catalogId != null) { "Update needs a trusted catalog snapshot" }
                val target = appearance.customPalettes.find { it.document.id == route.targetInstalledId }
                val targetError =
                    if (route.targetInstalledId == null) {
                        null
                    } else {
                        when {
                            target == null -> "Update target is no longer installed"
                            target.catalogId != installed.catalogId -> "Update target does not track this catalog palette"
                            (target.catalogRevision ?: 0) > document.revision -> "A newer revision is already installed"
                            else -> null
                        }
                    }
                snapshot = installed
                val restoredMode =
                    savedStateHandle.get<String>("previewMode")?.let { value ->
                        PaletteMode.entries.find { it.name == value }
                    } ?: route.assignmentTarget ?: PaletteMode.SYSTEM
                PalettePreviewUiState(
                    document = document,
                    mode = supportedMode(document, restoredMode),
                    assignmentTarget = supportedMode(document, savedStateHandle.get<String>("previewAssignmentTarget")?.let(PaletteMode::valueOf) ?: route.assignmentTarget ?: PaletteMode.SYSTEM),
                    sourceHostname = route.sourceLink?.let { URI(it).host },
                    targetInstalledId = route.targetInstalledId,
                    previousDocument = target?.document,
                    error = targetError,
                )
            } catch (error: Exception) {
                PalettePreviewUiState(error = error.message ?: "Unable to open palette preview")
            }

        fun selectMode(mode: PaletteMode) {
            val document = snapshot?.document ?: return
            if (local.value.saving || supportedMode(document, mode) != mode) return
            savedStateHandle["previewMode"] = mode.name
            local.value = local.value.copy(mode = mode)
        }

        fun selectAssignmentTarget(mode: PaletteMode) {
            val document = snapshot?.document ?: return
            if (local.value.saving || supportedMode(document, mode) != mode) return
            savedStateHandle["previewAssignmentTarget"] = mode.name
            local.value = local.value.copy(assignmentTarget = mode)
        }

        fun apply() = save(apply = true)

        fun saveWithoutApplying() = save(apply = false)

        fun selectConflictChoice(choice: PaletteConflictChoice) {
            if (!local.value.saving) local.value = local.value.copy(conflictChoice = choice)
        }

        fun cancelConflict() {
            if (!local.value.saving) local.value = local.value.copy(conflict = false)
        }

        fun confirmConflict() {
            if (local.value.conflict) save(pendingApply, local.value.conflictChoice)
        }

        private fun save(
            apply: Boolean,
            choice: PaletteConflictChoice? = null,
        ) {
            val incoming = snapshot ?: return
            if (local.value.saving || local.value.completed) return
            local.value = local.value.copy(saving = true, error = null)
            viewModelScope.launch {
                try {
                    val appearance = appearancePrefs.config.first()
                    require(appearance.customPaletteReadError == null) { "Installed palettes could not be read" }
                    val explicitTarget = local.value.targetInstalledId
                    val existing = appearance.customPalettes.find { it.document.id == (explicitTarget ?: incoming.document.id) }
                    if (explicitTarget != null) {
                        require(existing != null) { "Update target is no longer installed" }
                        require(existing.catalogId == incoming.catalogId) { "Update target no longer tracks this catalog palette" }
                        require((existing.catalogRevision ?: 0) <= incoming.document.revision) { "A newer revision is already installed" }
                        require(existing.document == local.value.previousDocument) { "Update target changed; reopen its preview" }
                        val updated = incoming.copy(document = incoming.document.copy(id = explicitTarget))
                        validateInstalledPalette(updated)
                        appearancePrefs.savePalette(updated, apply = false, mode = local.value.mode, targetInstalledId = explicitTarget, expectedTarget = existing)
                    } else {
                        require(choice == null || existing != null) { "Replace target is no longer installed" }
                        val reusable =
                            if (incoming.catalogId != null) {
                                appearance.customPalettes
                                    .filter { it.catalogId == incoming.catalogId && it.catalogRevision == incoming.catalogRevision }
                                    .let { matches ->
                                        matches.find { it.document.id == incoming.document.id }
                                            ?: existing?.takeIf { it.catalogId == null && sameCatalogDocument(it.document, incoming.document) }
                                            ?: matches.find { it.document.id == appearance.paletteSelection.choiceFor(local.value.assignmentTarget == PaletteMode.DARK).installedId }
                                            ?: matches.find { appearance.paletteSelection.usesInstalled(it.document.id) }
                                            ?: matches.firstOrNull()
                                    }
                                    ?: appearance.customPalettes.find { it.catalogId == null && sameCatalogDocument(it.document, incoming.document) }
                            } else {
                                null
                            }
                        val destination = reusable ?: existing
                        val identical =
                            destination != null && (
                                sameCatalogDocument(destination.document, incoming.document) ||
                                    incoming.catalogId != null && destination.catalogId == incoming.catalogId && destination.catalogRevision == incoming.catalogRevision
                            )
                        if (destination != null && !identical && choice == null) {
                            pendingApply = apply
                            local.value = local.value.copy(saving = false, conflict = true, conflictChoice = PaletteConflictChoice.KEEP_BOTH)
                            return@launch
                        }
                        val saved =
                            when {
                                identical && incoming.catalogId == null -> {
                                    requireNotNull(destination)
                                }

                                identical -> {
                                    val previous = requireNotNull(destination)
                                    if (previous.catalogId == incoming.catalogId && previous.catalogRevision == incoming.catalogRevision) {
                                        previous
                                    } else {
                                        incoming.copy(document = incoming.document.copy(id = previous.document.id), sourceLink = incoming.sourceLink ?: previous.sourceLink)
                                    }
                                }

                                destination != null && choice == PaletteConflictChoice.KEEP_BOTH -> {
                                    incoming.copy(
                                        document = incoming.document.copy(id = UUID.randomUUID().toString(), name = copyName(incoming.document.name)),
                                    )
                                }

                                else -> {
                                    incoming
                                }
                            }
                        validateInstalledPalette(saved)
                        appearancePrefs.savePalette(
                            saved,
                            apply,
                            local.value.assignmentTarget,
                            targetInstalledId = destination?.document?.id?.takeIf { identical || choice == PaletteConflictChoice.REPLACE },
                        )
                    }
                    local.value = local.value.copy(saving = false, conflict = false, completed = true)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    local.value = local.value.copy(saving = false, conflict = false, error = error.message ?: "Unable to save palette")
                }
            }
        }

        private fun supportedMode(
            document: PaletteDocument,
            mode: PaletteMode,
        ): PaletteMode =
            when {
                document.light == null -> PaletteMode.DARK
                document.dark == null -> PaletteMode.LIGHT
                else -> mode
            }

        private fun copyName(name: String): String {
            val suffix = " (copy)"
            val trimmed = name.trim()
            val length = minOf(trimmed.codePointCount(0, trimmed.length), 64 - suffix.length)
            return trimmed.substring(0, trimmed.offsetByCodePoints(0, length)).trimEnd() + suffix
        }
    }

internal fun sameCatalogDocument(
    installed: PaletteDocument,
    catalog: PaletteDocument,
): Boolean = encodePalette(installed.copy(id = catalog.id)).contentEquals(encodePalette(catalog))
