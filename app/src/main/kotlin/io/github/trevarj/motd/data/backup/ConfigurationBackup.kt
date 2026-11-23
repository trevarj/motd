package io.github.trevarj.motd.data.backup

import androidx.room.withTransaction
import io.github.trevarj.motd.BuildConfig
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.normalizeTrustedFileHost
import io.github.trevarj.motd.audio.VoiceConfig
import io.github.trevarj.motd.audio.VoicePrefs
import io.github.trevarj.motd.avatar.AvatarPrefs
import io.github.trevarj.motd.avatar.SelfAvatarSetting
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.FolderIconKind
import io.github.trevarj.motd.data.db.FolderIdentityKind
import io.github.trevarj.motd.data.db.IgnoredAutoGroupPatternEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.data.prefs.BouncerKindPrefs
import io.github.trevarj.motd.data.prefs.ChatSoundConfig
import io.github.trevarj.motd.data.prefs.ChatSoundPrefs
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.data.prefs.DEFAULT_WALLPAPER_INTENSITY
import io.github.trevarj.motd.data.prefs.InstalledPalette
import io.github.trevarj.motd.data.prefs.LauncherIcon
import io.github.trevarj.motd.data.prefs.MAX_WALLPAPER_DESIGNS
import io.github.trevarj.motd.data.prefs.MAX_WALLPAPER_IMPORTS
import io.github.trevarj.motd.data.prefs.PaletteChoice
import io.github.trevarj.motd.data.prefs.PaletteMode
import io.github.trevarj.motd.data.prefs.PaletteSelection
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.ReplyPrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.WallpaperChatChoice
import io.github.trevarj.motd.data.prefs.WallpaperLibrary
import io.github.trevarj.motd.data.prefs.WallpaperLibraryException
import io.github.trevarj.motd.data.prefs.WallpaperLibrarySnapshot
import io.github.trevarj.motd.data.prefs.WallpaperPatternRef
import io.github.trevarj.motd.data.prefs.WallpaperPatternRefKind
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.data.prefs.WallpaperWorkshopStore
import io.github.trevarj.motd.data.prefs.chatWallpaperPresetFromStored
import io.github.trevarj.motd.data.prefs.decodePalette
import io.github.trevarj.motd.data.prefs.decodePaletteTree
import io.github.trevarj.motd.data.prefs.decodeWallpaperValue
import io.github.trevarj.motd.data.prefs.encodePalette
import io.github.trevarj.motd.data.prefs.encodeWallpaperLibrary
import io.github.trevarj.motd.data.prefs.isValidIn
import io.github.trevarj.motd.data.prefs.normalizeEmojiSetId
import io.github.trevarj.motd.data.prefs.normalizePaletteSelection
import io.github.trevarj.motd.data.prefs.paletteSelection
import io.github.trevarj.motd.data.prefs.supportsMode
import io.github.trevarj.motd.data.prefs.validateInstalledPalette
import io.github.trevarj.motd.data.prefs.validateInstalledPalettes
import io.github.trevarj.motd.data.repo.ChatFolderRepository
import io.github.trevarj.motd.data.repo.FolderIconRef
import io.github.trevarj.motd.data.repo.FolderPortableAssignment
import io.github.trevarj.motd.data.repo.FolderPortableDefinition
import io.github.trevarj.motd.data.repo.MAX_CHAT_WALLPAPER_ASSIGNMENTS
import io.github.trevarj.motd.data.repo.PortableChatPlacement
import io.github.trevarj.motd.data.repo.PortableChatWallpaper
import io.github.trevarj.motd.data.repo.networkIdentityKey
import io.github.trevarj.motd.data.repo.validatePortableChatIdentity
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.gesture.GestureMenuConfig
import io.github.trevarj.motd.gesture.GesturePrefs
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private const val FORMAT_VERSION = 1
internal const val MAX_BACKUP_DOCUMENT_BYTES = 4 * 1024 * 1024
private const val MAX_DECRYPTED_BYTES = 2 * 1024 * 1024
private const val MAX_NETWORKS = 512
private const val MAX_FOLDERS = 512
private const val MAX_FOLDER_ASSIGNMENTS = 100_000
private const val MAX_CHAT_PLACEMENTS = 100_000
private const val MAX_IGNORED_AUTO_GROUP_PATTERNS = 10_000
private const val MAX_PORTABLE_IDENTITY_LENGTH = 512
private const val MAX_ICON_REFERENCE_LENGTH = 128
private const val PBKDF2_ITERATIONS = 600_000
private const val AES_KEY_BITS = 256
private const val GCM_TAG_BITS = 128
private val catalogRevisionToken = Regex("[1-9][0-9]*")

enum class BackupExportMode { CREDENTIALS_EXCLUDED, ENCRYPTED_WITH_CREDENTIALS }

enum class BackupImportMode { MERGE, REPLACE }

/** Valid backup envelope or payload that this build cannot safely accept. */
internal open class BackupFormatException(
    cause: Exception? = null,
) : IllegalArgumentException(cause)

internal class BackupDocumentTooLargeException : BackupFormatException()

internal class BackupLocalWallpaperLibraryException(
    cause: WallpaperLibraryException,
) : IllegalStateException(cause)

internal class BackupWallpaperMergeException(
    cause: IllegalArgumentException,
) : BackupFormatException(cause)

data class ConfigurationImportPreview(
    val appVersion: String,
    val exportedAtEpochMillis: Long,
    val containsSecrets: Boolean,
    val networkCount: Int,
    val addedNetworks: Int,
    val updatedNetworks: Int,
    val removedNetworks: Int,
    val retainedLocalCredentials: Int,
    val missingCredentialNetworks: Int,
    val settingGroups: List<String>,
    val folderCount: Int = 0,
    val folderAssignmentCount: Int = 0,
)

data class ConfigurationImportResult(
    val addedNetworks: Int,
    val updatedNetworks: Int,
    val removedNetworks: Int,
    val missingCredentialNetworks: Int,
)

interface ConfigurationBackupRepository {
    suspend fun exportToString(
        mode: BackupExportMode,
        password: String? = null,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): String

    suspend fun preview(
        rawDocument: String,
        password: String? = null,
        importMode: BackupImportMode,
    ): ConfigurationImportPreview

    suspend fun import(
        rawDocument: String,
        password: String? = null,
        importMode: BackupImportMode,
    ): ConfigurationImportResult

    fun isEncrypted(rawDocument: String): Boolean
}

@Singleton
class ConfigurationBackupRepositoryImpl
    @Inject
    constructor(
        private val db: MotdDatabase,
        private val settingsRepository: SettingsRepository,
        private val appearancePrefs: AppearancePrefs,
        private val contentPreviewPrefs: ContentPreviewPrefs,
        private val chatSoundPrefs: ChatSoundPrefs,
        private val replyPrefs: ReplyPrefs,
        private val attachmentPrefs: AttachmentPrefs,
        private val voicePrefs: VoicePrefs,
        private val avatarPrefs: AvatarPrefs,
        private val bouncerKindPrefs: BouncerKindPrefs,
        private val gesturePrefs: GesturePrefs,
        private val wallpaperStore: WallpaperWorkshopStore,
        private val chatFolders: ChatFolderRepository = ChatFolderRepository(db),
    ) : ConfigurationBackupRepository {
        private val json =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
                prettyPrint = true
            }
        private val compactJson =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }
        private val random = SecureRandom()
        private val buffers = BufferStore(db)

        override suspend fun exportToString(
            mode: BackupExportMode,
            password: String?,
            nowEpochMillis: Long,
        ): String {
            val includeSecrets = mode == BackupExportMode.ENCRYPTED_WITH_CREDENTIALS
            if (includeSecrets) {
                require(!password.isNullOrBlank() && password.length in 12..128) {
                    "Encrypted exports require a 12-128 character password."
                }
            }
            val payload = snapshotPayload(includeSecrets)
            val envelope =
                if (includeSecrets) {
                    val plainText = compactJson.encodeToString(payload).encodeToByteArray()
                    if (plainText.size > MAX_DECRYPTED_BYTES) throw BackupDocumentTooLargeException()
                    val encryptedPayload =
                        encryptPayload(
                            plainText,
                            password.orEmpty(),
                            BuildConfig.VERSION_NAME,
                            nowEpochMillis,
                        )
                    BackupEnvelope(
                        appVersion = BuildConfig.VERSION_NAME,
                        exportedAtEpochMillis = nowEpochMillis,
                        mode = BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS,
                        encryptedPayload = encryptedPayload,
                    )
                } else {
                    BackupEnvelope(
                        appVersion = BuildConfig.VERSION_NAME,
                        exportedAtEpochMillis = nowEpochMillis,
                        mode = BackupEnvelopeMode.CREDENTIALS_EXCLUDED,
                        payload = payload,
                    )
                }
            val document = json.encodeToString(envelope)
            if (document.encodeToByteArray().size > MAX_BACKUP_DOCUMENT_BYTES) throw BackupDocumentTooLargeException()
            validatePayload(payload)
            return document
        }

        override fun isEncrypted(rawDocument: String): Boolean = decodeEnvelope(parseBackupDocument(rawDocument)).mode == BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS

        override suspend fun preview(
            rawDocument: String,
            password: String?,
            importMode: BackupImportMode,
        ): ConfigurationImportPreview {
            val decoded = decodeDocument(rawDocument, password)
            val plan = planImport(decoded.payload, importMode, db.networkDao().allNow())
            preflightPalettes(decoded.payload.settings.appearance, importMode)
            preflightWallpaper(decoded.payload, importMode, plan)
            return ConfigurationImportPreview(
                appVersion = decoded.envelope.appVersion,
                exportedAtEpochMillis = decoded.envelope.exportedAtEpochMillis,
                containsSecrets = decoded.envelope.mode == BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS,
                networkCount = decoded.payload.networks.size,
                addedNetworks = plan.added,
                updatedNetworks = plan.updated,
                removedNetworks = plan.removed,
                retainedLocalCredentials = plan.retainedLocalCredentials,
                missingCredentialNetworks = plan.missingCredentialNetworks,
                settingGroups = decoded.payload.settings.groupNames(),
                folderCount = decoded.payload.folders.size,
                folderAssignmentCount = decoded.payload.folderAssignments.size,
            )
        }

        override suspend fun import(
            rawDocument: String,
            password: String?,
            importMode: BackupImportMode,
        ): ConfigurationImportResult {
            val decoded = decodeDocument(rawDocument, password)
            val plan = planImport(decoded.payload, importMode, db.networkDao().allNow())
            val paletteRestore = preflightPalettes(decoded.payload.settings.appearance, importMode)

            val wallpaperRestore = preflightWallpaper(decoded.payload, importMode, plan)
            val includeSecrets = decoded.envelope.mode == BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS
            val replace = importMode == BackupImportMode.REPLACE

            suspend fun settings() {
                applySettings(decoded.payload.settings, includeSecrets, paletteRestore, decoded.launcherIcon)
            }
            lateinit var idMap: Map<String, Long>
            if (wallpaperRestore == null) {
                settings()
                idMap = restoreNetworks(decoded.payload, includeSecrets, importMode)
            } else {
                wallpaperStore.restore(
                    expected = wallpaperRestore.expected,
                    library = wallpaperRestore.library,
                    selection = wallpaperRestore.selection,
                    replace = replace,
                    beforeCommit = {
                        idMap =
                            restoreNetworks(decoded.payload, includeSecrets, importMode) { current ->
                                preflightWallpaperAssignments(wallpaperRestore, current, replace)
                            }
                    },
                    afterCommit = {
                        if (wallpaperRestore.library != null) {
                            buffers.restoreChatWallpaperAssignments(
                                wallpaperRestore.assignments.map { it.toRepository(idMap.getValue(it.networkExportId)) },
                                replace,
                                wallpaperRestore.library,
                            )
                        }
                    },
                )
                settings()
            }
            chatFolders.restore(
                folders = decoded.payload.folders.map(PortableFolder::toRepository),
                assignments =
                    decoded.payload.folderAssignments.mapNotNull { assignment ->
                        val networkId = idMap[assignment.networkExportId] ?: return@mapNotNull null
                        assignment.toRepository(networkId)
                    },
                ignored =
                    decoded.payload.ignoredAutoGroupPatterns.mapNotNull { ignored ->
                        idMap[ignored.networkExportId]?.let { IgnoredAutoGroupPatternEntity(it, ignored.normalizedPrefix) }
                    },
                replace = importMode == BackupImportMode.REPLACE,
            )
            decoded.payload.chatPlacements?.let { placements ->
                buffers.restoreChatPlacements(
                    placements =
                        placements.map { placement ->
                            placement.toRepository(idMap.getValue(placement.networkExportId))
                        },
                    replace = importMode == BackupImportMode.REPLACE,
                )
            }
            applyRemappedNetworkPrefs(decoded.payload, idMap, importMode)
            return ConfigurationImportResult(
                addedNetworks = plan.added,
                updatedNetworks = plan.updated,
                removedNetworks = plan.removed,
                missingCredentialNetworks = plan.missingCredentialNetworks,
            )
        }

        private suspend fun restoreNetworks(
            payload: BackupPayload,
            includeSecrets: Boolean,
            importMode: BackupImportMode,
            preflight: suspend (ImportPlan) -> Unit = {},
        ): Map<String, Long> =
            db.withTransaction {
                val current = db.networkDao().allNow()
                val plan = planImport(payload, importMode, current)
                preflight(plan)
                val existing = current.associateBy(NetworkEntity::id)
                val idMap = mutableMapOf<String, Long>()
                val byOrder = payload.networks.sortedBy(PortableNetwork::ordering)

                suspend fun restore(
                    portable: PortableNetwork,
                    parentId: Long?,
                ) {
                    val local = existing[plan.networkIds.getValue(portable.exportId)]
                    idMap[portable.exportId] = upsertResolvedNetwork(portable.toEntity(includeSecrets, parentId, local))
                }
                byOrder.filter { it.role != NetworkRole.BOUNCER_CHILD }.forEach { restore(it, null) }
                byOrder.filter { it.role == NetworkRole.BOUNCER_CHILD }.forEach { portable ->
                    restore(portable, idMap.getValue(requireNotNull(portable.parentExportId)))
                }
                if (importMode == BackupImportMode.REPLACE) {
                    val importedIds = idMap.values.toSet()
                    current
                        .asSequence()
                        .filter { it.id !in importedIds }
                        .filter { it.parentId == null || it.parentId !in importedIds }
                        .forEach { db.networkDao().deleteLocalTree(it.id) }
                }
                idMap
            }

        private suspend fun snapshotPayload(includeSecrets: Boolean): BackupPayload {
            val networks = db.networkDao().allNow().sortedWith(compareBy<NetworkEntity> { it.parentId ?: 0L }.thenBy { it.ordering })
            val exportIds = networks.associate { it.id to "network-${it.id}" }
            val zncIds = bouncerKindPrefs.zncNetworkIds.first()
            val folderSnapshot = chatFolders.backupSnapshot()
            val appearance = snapshotAppearance()
            val library = requireNotNull(appearance.wallpaperLibrary)
            val selfAvatars =
                networks.mapNotNull { network ->
                    val setting = avatarPrefs.selfSetting(network.id).first()
                    if (setting == SelfAvatarSetting.Unmanaged) {
                        null
                    } else {
                        PortableSelfAvatar(
                            networkExportId = exportIds.getValue(network.id),
                            setting = setting.toPortable(),
                        )
                    }
                }
            return BackupPayload(
                networks = networks.map { it.toPortable(exportIds, includeSecrets, zncIds) },
                chatPlacements =
                    buffers.chatPlacementSnapshot().mapNotNull { placement ->
                        exportIds[placement.networkId]?.let { PortablePlacement.fromRepository(placement, it) }
                    },
                chatWallpaperAssignments =
                    buffers.chatWallpaperSnapshot(library).mapNotNull { assignment ->
                        exportIds[assignment.networkId]?.let { PortableWallpaperAssignment.fromRepository(assignment, it) }
                    },
                folders = folderSnapshot.folders.map(PortableFolder::fromRepository),
                folderAssignments =
                    folderSnapshot.assignments.mapNotNull { assignment ->
                        exportIds[assignment.networkId]?.let { assignment.toPortable(it) }
                    },
                ignoredAutoGroupPatterns =
                    folderSnapshot.ignored.mapNotNull { ignored ->
                        exportIds[ignored.networkId]?.let { PortableIgnoredAutoGroupPattern(it, ignored.normalizedPrefix) }
                    },
                settings =
                    PortableSettings(
                        general = settingsRepository.settings.first(),
                        appearance = appearance,
                        contentPreviews = contentPreviewPrefs.config.first(),
                        chatSounds = chatSoundPrefs.config.first(),
                        replies = replyPrefs.config.first(),
                        attachments =
                            attachmentPrefs.config.first().let { config ->
                                val portable = config.copy(stripHostedImageMetadata = config.stripHostedImageMetadata == true)
                                if (includeSecrets) portable else portable.copy(username = "", password = "")
                            },
                        voice = voicePrefs.config.first(),
                        showSharedAvatars = avatarPrefs.config.first().showSharedAvatars,
                        selfAvatars = selfAvatars,
                        // The gesture lab's on/off flag stays out of backups; the menu graph is authored
                        // work and travels, but only once it differs from the built-in default.
                        gestureMenu = gesturePrefs.menu.first().takeIf { it != GestureMenuConfig() },
                    ),
            )
        }

        private fun canonicalPalette(palette: InstalledPalette): InstalledPalette {
            validateInstalledPalette(palette)
            return palette.copy(document = decodePalette(encodePalette(palette.document)))
        }

        private suspend fun snapshotAppearance(): AppearanceConfig {
            val appearance = appearancePrefs.config.first()
            appearance.wallpaperLibraryReadError?.let {
                throw BackupLocalWallpaperLibraryException(WallpaperLibraryException(it))
            }
            val library = requireNotNull(appearance.wallpaperLibrary)
            val selected = appearance.wallpaper.copy(localImageName = null)
            val wallpaper =
                if (selected.patternRef != null && (selected.preset != ChatWallpaperPreset.PATTERN || !selected.patternRef.isValidIn(library))) {
                    selected.copy(patternRef = null, intensity = DEFAULT_WALLPAPER_INTENSITY, outlineColor = null)
                } else {
                    selected
                }
            require(appearance.customPaletteReadError == null) { "Installed palettes cannot be read." }
            validateInstalledPalettes(appearance.customPalettes)
            val selection = appearance.paletteSelection
            val fallback =
                when (selection.mode) {
                    PaletteMode.LIGHT -> {
                        selection.light
                    }

                    PaletteMode.DARK -> {
                        selection.dark
                    }

                    PaletteMode.SYSTEM -> {
                        listOf(selection.light, selection.dark).firstOrNull {
                            it.supportsMode(false, appearance.customPalettes) && it.supportsMode(true, appearance.customPalettes)
                        } ?: PaletteChoice()
                    }
                }
            return appearance.copy(
                theme = fallback.builtin,
                customPaletteId = fallback.installedId,
                paletteMode = selection.mode,
                lightPalette = selection.light,
                darkPalette = selection.dark,
                customPalettes = appearance.customPalettes.map(::canonicalPalette),
                trueBlack = appearance.trueBlack || (appearance.paletteMode == null && appearance.theme == ColorThemePreset.AMOLED),
                wallpaper = wallpaper,
                wallpaperLibrary = library,
            )
        }

        private suspend fun preflightPalettes(
            appearance: AppearanceConfig?,
            importMode: BackupImportMode,
        ): PaletteRestore? {
            if (appearance == null) return null
            val modern = appearance.paletteMode != null || appearance.lightPalette != null
            val incoming = appearance.customPalettes.map(::canonicalPalette)
            val selection = appearance.paletteSelection
            if (modern && importMode == BackupImportMode.REPLACE) {
                return PaletteRestore(incoming, selection)
            }
            val current = appearancePrefs.config.first()
            require(current.customPaletteReadError == null) { "Installed palettes cannot be read." }
            validateInstalledPalettes(current.customPalettes)
            val target = current.customPalettes.map(::canonicalPalette).toMutableList()
            val remaps = mutableMapOf<String, String>()
            if (modern) {
                val reservedIds = (target + incoming).map { it.document.id }.toMutableSet()
                incoming.forEach { palette ->
                    val existing = target.find { it.document.id == palette.document.id }
                    when {
                        existing == null -> {
                            target += palette
                        }

                        existing != palette -> {
                            var id: String
                            do {
                                id = UUID.randomUUID().toString()
                            } while (!reservedIds.add(id))
                            remaps[palette.document.id] = id
                            target += palette.copy(document = palette.document.copy(id = id))
                        }
                    }
                }
            }
            validateInstalledPalettes(target)
            val remapped =
                selection.copy(
                    light = selection.light.copy(installedId = remaps[selection.light.installedId] ?: selection.light.installedId),
                    dark = selection.dark.copy(installedId = remaps[selection.dark.installedId] ?: selection.dark.installedId),
                )
            return PaletteRestore(target, normalizePaletteSelection(remapped, target))
        }

        private suspend fun preflightWallpaper(
            payload: BackupPayload,
            importMode: BackupImportMode,
            plan: ImportPlan,
        ): WallpaperRestore? {
            val appearance = payload.settings.appearance
            val incoming = appearance?.wallpaperLibrary
            val replace = importMode == BackupImportMode.REPLACE
            val expected = wallpaperStore.librarySnapshot()
            val current =
                if (replace && incoming != null) {
                    null // A validated modern replacement is the recovery path for a damaged local library.
                } else {
                    try {
                        expected.requireLibrary()
                    } catch (failure: WallpaperLibraryException) {
                        throw BackupLocalWallpaperLibraryException(failure)
                    }
                }
            if (appearance == null) return null
            val selection = appearance.wallpaper
            if (incoming == null) return WallpaperRestore(expected, null, selection, emptyList())

            try {
                val remaps = mutableMapOf<String, String>()
                val target =
                    when {
                        current == null -> incoming
                        incoming.designs.isEmpty() -> current
                        else -> mergeWallpaperLibraries(current, incoming, remaps)
                    }

                fun WallpaperPatternRef.remap(): WallpaperPatternRef = if (kind == WallpaperPatternRefKind.SAVED) remaps[id]?.let { copy(id = it) } ?: this else this
                val assignments = requireNotNull(payload.chatWallpaperAssignments)
                val restore =
                    if (remaps.isEmpty()) {
                        WallpaperRestore(expected, target, selection, assignments)
                    } else {
                        val remappedAssignments =
                            assignments.map { assignment ->
                                val ref = assignment.choice.pattern
                                val mapped = ref?.remap()
                                if (mapped === ref) assignment else assignment.copy(choice = assignment.choice.copy(pattern = mapped))
                            }
                        WallpaperRestore(expected, target, selection.copy(patternRef = selection.patternRef?.remap()), remappedAssignments)
                    }
                preflightWallpaperAssignments(restore, plan, replace)
                return restore
            } catch (failure: IllegalArgumentException) {
                if (!replace) throw BackupWallpaperMergeException(failure)
                throw BackupFormatException(failure)
            }
        }

        private fun mergeWallpaperLibraries(
            current: WallpaperLibrary,
            incoming: WallpaperLibrary,
            remaps: MutableMap<String, String>,
        ): WallpaperLibrary {
            val designs = current.designs.associateBy { it.id }.toMutableMap()
            val reserved = designs.keys.toMutableSet()
            incoming.designs.mapTo(reserved) { it.id }
            for (design in incoming.designs) {
                val previous = designs[design.id]
                if (previous == null) {
                    designs[design.id] = design
                } else if (previous != design) {
                    var id: String
                    do {
                        id = UUID.randomUUID().toString()
                    } while (!reserved.add(id))
                    remaps[design.id] = id
                    designs[id] = design.copy(id = id)
                }
            }
            val imports = current.imports.associateBy { it.id }.toMutableMap()
            for (icon in incoming.imports) {
                val previous = imports[icon.id]
                if (previous == null) {
                    imports[icon.id] = icon
                } else {
                    require(previous.svg == icon.svg) { "Wallpaper icon digest matches different SVG bytes." }
                }
            }
            return WallpaperLibrary(designs = designs.values.toList(), imports = imports.values.toList()).also {
                encodeWallpaperLibrary(it)
            }
        }

        private suspend fun preflightWallpaperAssignments(
            restore: WallpaperRestore,
            plan: ImportPlan,
            replace: Boolean,
        ) {
            val library = restore.library ?: return
            buffers.preflightChatWallpaperAssignments(
                restore.assignments.map { it.toRepository(plan.networkIds.getValue(it.networkExportId)) },
                replace,
                library,
            )
        }

        private fun validatePayloadTree(tree: JsonElement): JsonElement {
            try {
                validateWallpaperStateTree(tree as? JsonObject ?: throw BackupFormatException())
            } catch (failure: IllegalArgumentException) {
                throw BackupFormatException(failure)
            }
            return validatePaletteStateTree(tree)
        }

        private fun validateWallpaperStateTree(payload: JsonObject) {
            val appearance = (payload["settings"] as? JsonObject)?.get("appearance") as? JsonObject
            val library = appearance?.get("wallpaperLibrary")?.takeUnless { it == JsonNull }
            val assignments = payload["chatWallpaperAssignments"]?.takeUnless { it == JsonNull }
            require((library == null) == (assignments == null)) { "Wallpaper library and assignments must be present together." }
            if (library != null) {
                require(library is JsonObject)
                for ((key, limit) in listOf("designs" to MAX_WALLPAPER_DESIGNS, "imports" to MAX_WALLPAPER_IMPORTS)) {
                    library[key]?.let { require(it is JsonArray && it.size <= limit) }
                }
                decodeWallpaperValue<WallpaperLibrary>(library)
                require(assignments is JsonArray && assignments.size <= MAX_CHAT_WALLPAPER_ASSIGNMENTS)
                assignments.forEach { decodeWallpaperValue<PortableWallpaperAssignment>(it) }
            }
            appearance?.get("wallpaper")?.let { raw ->
                require(raw is JsonObject)
                val ref = raw["patternRef"]?.takeUnless { it == JsonNull }
                val preset = raw["preset"]
                require(ref == null || (library != null && preset == JsonPrimitive("PATTERN")))
                // The preset decoder intentionally accepts legacy and future names. Other fields
                // use the strict Workshop profile rather than JSON primitive coercion.
                val canonical =
                    if (preset == null) {
                        raw
                    } else {
                        require(preset is JsonPrimitive && preset.isString)
                        JsonObject(raw + ("preset" to JsonPrimitive(chatWallpaperPresetFromStored(preset.content).name)))
                    }
                decodeWallpaperValue<WallpaperSelection>(canonical)
            }
        }

        private fun parseBackupDocument(rawDocument: String): JsonObject {
            if (rawDocument.length > MAX_BACKUP_DOCUMENT_BYTES || rawDocument.encodeToByteArray().size > MAX_BACKUP_DOCUMENT_BYTES) {
                throw BackupDocumentTooLargeException()
            }
            return compactJson.parseToJsonElement(rawDocument) as? JsonObject ?: throw BackupFormatException()
        }

        private fun decodeEnvelope(root: JsonObject): BackupEnvelope {
            val plainPayload = root["payload"]
            val validated =
                if (plainPayload == null || plainPayload == JsonNull) {
                    root
                } else {
                    JsonObject(root + ("payload" to validatePayloadTree(plainPayload)))
                }
            val envelope = compactJson.decodeFromJsonElement<BackupEnvelope>(validated)
            if (envelope.formatVersion != FORMAT_VERSION) throw BackupFormatException()
            return envelope
        }

        private fun decodeDocument(
            rawDocument: String,
            password: String?,
        ): DecodedDocument {
            val root = parseBackupDocument(rawDocument)
            val plainPayload = root["payload"]
            val envelope = decodeEnvelope(root)
            val payloadTree: JsonElement?
            val payload =
                when (envelope.mode) {
                    BackupEnvelopeMode.CREDENTIALS_EXCLUDED -> {
                        payloadTree = plainPayload
                        envelope.payload ?: throw BackupFormatException()
                    }

                    BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS -> {
                        require(!password.isNullOrBlank())
                        val encryptedPayload = envelope.encryptedPayload ?: throw BackupFormatException()
                        val decrypted = decryptPayload(encryptedPayload, password, envelope.appVersion, envelope.exportedAtEpochMillis)
                        if (decrypted.size > MAX_DECRYPTED_BYTES) throw BackupDocumentTooLargeException()
                        payloadTree = compactJson.parseToJsonElement(decrypted.decodeToString(throwOnInvalidSequence = true))
                        compactJson.decodeFromJsonElement<BackupPayload>(validatePayloadTree(payloadTree))
                    }
                }
            validatePayload(payload)
            val settingsTree = (payloadTree as? JsonObject)?.get("settings") as? JsonObject
            val appearanceTree = settingsTree?.get("appearance") as? JsonObject
            val launcherIcon =
                payload.settings.appearance
                    ?.launcherIcon
                    ?.takeIf { appearanceTree?.containsKey("launcherIcon") == true }
            return DecodedDocument(envelope, payload, launcherIcon)
        }

        private fun validatePaletteStateTree(tree: JsonElement): JsonElement {
            try {
                val payload = tree as? JsonObject ?: throw BackupFormatException()
                val settings = payload["settings"] as? JsonObject ?: return tree
                val appearance = settings["appearance"] as? JsonObject ?: return tree
                val light = appearance["lightPalette"]
                val dark = appearance["darkPalette"]
                require((light == null) == (dark == null)) { "Both palette slots must be present together." }
                if (light != null) {
                    require(light is JsonObject && dark is JsonObject) { "Palette slots must be objects." }
                    require(appearance["paletteMode"] != null && appearance["paletteMode"] != JsonNull) {
                        "Palette slots require an explicit mode."
                    }
                    for (slot in listOf(light, dark)) {
                        for (key in listOf("builtin", "installedId")) {
                            val value = slot[key]
                            require(
                                value == null || (key == "installedId" && value == JsonNull) ||
                                    (value is JsonPrimitive && value.isString),
                            ) { "Invalid palette slot $key." }
                        }
                    }
                }
                for (key in listOf("paletteMode", "customPaletteId")) {
                    val value = appearance[key]
                    require(value == null || value == JsonNull || (value is JsonPrimitive && value.isString)) { "Invalid $key." }
                }
                val entries = appearance["customPalettes"]
                if (entries != null && entries != JsonNull) {
                    require(entries is JsonArray) { "Installed palettes must be an array." }
                    entries.forEach { element ->
                        val entry = element as? JsonObject ?: throw BackupFormatException()
                        for (key in listOf("catalogId", "sourceLink")) {
                            val value = entry[key]
                            require(value == null || value == JsonNull || (value is JsonPrimitive && value.isString)) { "Invalid $key." }
                        }
                        val revision = entry["catalogRevision"]
                        require(
                            revision == null || revision == JsonNull ||
                                (revision is JsonPrimitive && !revision.isString && catalogRevisionToken.matches(revision.content)),
                        ) {
                            "Invalid catalog revision."
                        }
                        val document = decodePaletteTree(entry["document"] ?: throw BackupFormatException())
                        val installed = Json.decodeFromJsonElement<InstalledPalette>(entry)
                        validateInstalledPalette(installed.copy(document = document))
                    }
                }
                if (entries != JsonNull) return tree
                require(appearance["paletteMode"] == null || appearance["paletteMode"] == JsonNull) {
                    "Modern palette state requires an installed palette array."
                }
                val normalized = JsonObject(appearance + ("customPalettes" to JsonArray(emptyList())))
                return JsonObject(payload + ("settings" to JsonObject(settings + ("appearance" to normalized))))
            } catch (failure: IllegalArgumentException) {
                throw BackupFormatException(failure)
            }
        }

        private fun validatePayload(payload: BackupPayload) {
            try {
                validatePayloadFields(payload)
            } catch (failure: IllegalArgumentException) {
                throw BackupFormatException(failure)
            }
        }

        private fun validatePayloadFields(payload: BackupPayload) {
            require(payload.version == FORMAT_VERSION) { "Unsupported payload version ${payload.version}." }
            payload.settings.appearance?.let { appearance ->
                require(normalizeEmojiSetId(appearance.emojiSetId) == appearance.emojiSetId) { "Backup contains an invalid emoji set id." }
                validateInstalledPalettes(appearance.customPalettes)
                appearance.paletteMode?.let { mode ->
                    val fallback = PaletteChoice(appearance.theme, appearance.customPaletteId)
                    require(
                        (mode == PaletteMode.DARK || fallback.supportsMode(false, appearance.customPalettes)) &&
                            (mode == PaletteMode.LIGHT || fallback.supportsMode(true, appearance.customPalettes)),
                    ) { "Selected fallback palette does not support this mode." }
                }
                require((appearance.lightPalette == null) == (appearance.darkPalette == null)) { "Both palette slots are required." }
                if (appearance.lightPalette != null) {
                    normalizePaletteSelection(
                        PaletteSelection(appearance.lightPalette, requireNotNull(appearance.darkPalette), requireNotNull(appearance.paletteMode)),
                        appearance.customPalettes,
                    )
                } else if (appearance.paletteMode == null) {
                    require(appearance.customPalettes.isEmpty() && appearance.customPaletteId == null) { "Palette state requires an explicit mode." }
                } else {
                    appearance.paletteSelection
                }
            }
            require(payload.networks.size <= MAX_NETWORKS) { "Too many networks in backup." }
            val networksById = payload.networks.associateBy(PortableNetwork::exportId)
            require(networksById.size == payload.networks.size) { "Backup contains duplicate network ids." }
            val idSet = networksById.keys
            val library = payload.settings.appearance?.wallpaperLibrary
            val assignments = payload.chatWallpaperAssignments
            require((library == null) == (assignments == null))
            if (library != null) encodeWallpaperLibrary(library)
            payload.settings.appearance?.wallpaper?.let { wallpaper ->
                require(
                    wallpaper.patternRef == null ||
                        (library != null && wallpaper.preset == ChatWallpaperPreset.PATTERN && wallpaper.patternRef.isValidIn(library)),
                ) { "Backup contains an invalid global wallpaper reference." }
            }
            require(assignments.orEmpty().size <= MAX_CHAT_WALLPAPER_ASSIGNMENTS) { "Too many chat wallpaper assignments in backup." }
            val wallpaperIdentities = mutableSetOf<List<Any>>()
            assignments.orEmpty().forEach { assignment ->
                require(assignment.networkExportId in idSet) { "Backup contains an invalid chat wallpaper network." }
                validatePortableChatIdentity(assignment.chatType, assignment.identityKind, assignment.identityValue)
                require(wallpaperIdentities.add(listOf(assignment.networkExportId, assignment.chatType, assignment.identityKind, assignment.identityValue))) {
                    "Backup contains duplicate chat wallpaper assignments."
                }
                require(assignment.choice.pattern?.isValidIn(library) != false) { "Backup contains an invalid chat wallpaper reference." }
            }
            payload.networks.forEach { network ->
                require(network.exportId.isNotBlank()) { "Backup contains a network without an id." }
                require(network.name.isNotBlank()) { "Backup contains a network without a name." }
                require(network.host.isNotBlank()) { "Backup contains a network without a host." }
                require(network.port in 1..65535) { "Backup contains an invalid port." }
                require(network.nick.isNotBlank()) { "Backup contains a network without a nick." }
                if (network.role == NetworkRole.BOUNCER_CHILD) {
                    require(networksById[network.parentExportId]?.role == NetworkRole.BOUNCER_ROOT) {
                        "Backup contains a bouncer child without a valid parent."
                    }
                    require(!network.bouncerNetId.isNullOrBlank()) {
                        "Backup contains a bouncer child without a bouncer network id."
                    }
                }
                network.wsUrl?.let { require(it.startsWith("wss://")) { "Backup contains an invalid WebSocket URL." } }
                network.proxyPort?.let { require(it in 1..65535) { "Backup contains an invalid proxy port." } }
                network.trustedFileHost?.let {
                    require(normalizeTrustedFileHost(it) == it) { "Backup contains an invalid trusted file host." }
                }
            }
            require(payload.chatPlacements.orEmpty().size <= MAX_CHAT_PLACEMENTS) { "Too many chat placements in backup." }
            val placementIdentities = mutableSetOf<List<Any>>()
            val anchorOrders = mutableSetOf<Int>()
            payload.chatPlacements.orEmpty().forEach { placement ->
                require(placement.networkExportId in idSet) { "Backup contains an invalid chat placement network." }
                validatePortableChatIdentity(placement.chatType, placement.identityKind, placement.identityValue)
                require(placementIdentities.add(listOf(placement.networkExportId, placement.chatType, placement.identityKind, placement.identityValue))) {
                    "Backup contains duplicate chat placements."
                }
                placement.anchorOrder?.let { order ->
                    require(order in 0 until MAX_CHAT_PLACEMENTS && anchorOrders.add(order)) { "Backup contains an invalid anchor order." }
                }
            }
            require(payload.folders.size <= MAX_FOLDERS) { "Too many folders in backup." }
            require(payload.folderAssignments.size <= MAX_FOLDER_ASSIGNMENTS) { "Too many folder assignments in backup." }
            require(payload.ignoredAutoGroupPatterns.size <= MAX_IGNORED_AUTO_GROUP_PATTERNS) { "Too many ignored Auto-group patterns in backup." }
            val folderIds = payload.folders.map(PortableFolder::exportId)
            require(folderIds.size == folderIds.distinct().size) { "Backup contains duplicate folder ids." }
            val normalizedFolderNames = mutableSetOf<String>()
            payload.folders.forEach { folder ->
                val name = folder.name.trim()
                require(name.length in 1..64 && name.none(Char::isISOControl)) { "Backup contains an invalid folder name." }
                require(normalizedFolderNames.add(name.lowercase())) { "Backup contains duplicate folder names." }
                require(folder.iconKey.isNotBlank() && folder.iconKey.length <= MAX_ICON_REFERENCE_LENGTH) { "Backup contains an invalid folder icon." }
            }
            val folderIdSet = folderIds.toSet()
            payload.folderAssignments.forEach { assignment ->
                require(assignment.networkExportId in idSet && assignment.folderExportId in folderIdSet) { "Backup contains an invalid folder assignment reference." }
                require(assignment.identityValue.isNotBlank() && assignment.identityValue.length <= MAX_PORTABLE_IDENTITY_LENGTH && assignment.identityValue.none(Char::isISOControl)) {
                    "Backup contains an invalid folder assignment identity."
                }
            }
            payload.ignoredAutoGroupPatterns.forEach { ignored ->
                require(ignored.networkExportId in idSet && ignored.normalizedPrefix.isNotBlank() && ignored.normalizedPrefix.length <= MAX_PORTABLE_IDENTITY_LENGTH && ignored.normalizedPrefix.none(Char::isISOControl)) {
                    "Backup contains an invalid ignored Auto-group pattern."
                }
            }
        }

        private fun planImport(
            payload: BackupPayload,
            importMode: BackupImportMode,
            current: List<NetworkEntity>,
        ): ImportPlan {
            var added = 0
            var updated = 0
            var retainedLocalCredentials = 0
            var missingCredentialNetworks = 0
            val networkIds = LinkedHashMap<String, Long>(payload.networks.size)

            fun record(
                portable: PortableNetwork,
                local: NetworkEntity?,
            ) {
                if (local == null) {
                    added++
                    networkIds[portable.exportId] = -added.toLong()
                } else {
                    updated++
                    networkIds[portable.exportId] = local.id
                    if (portable.retainsAnyLocalSecret(local)) retainedLocalCredentials++
                }
                if (portable.missingCredentials(local)) missingCredentialNetworks++
            }
            payload.networks.filter { it.role != NetworkRole.BOUNCER_CHILD }.forEach { portable ->
                record(portable, matchTopLevel(portable, current))
            }
            payload.networks.filter { it.role == NetworkRole.BOUNCER_CHILD }.forEach { portable ->
                val parentId = networkIds[portable.parentExportId]
                val local =
                    current.firstOrNull {
                        parentId != null && it.role == NetworkRole.BOUNCER_CHILD &&
                            it.parentId == parentId && it.bouncerNetId == portable.bouncerNetId
                    }
                record(portable, local)
            }
            val matched = networkIds.values.toSet()
            val removed = if (importMode == BackupImportMode.REPLACE) current.count { it.id !in matched } else 0
            return ImportPlan(added, updated, removed, retainedLocalCredentials, missingCredentialNetworks, networkIds)
        }

        private fun matchTopLevel(
            portable: PortableNetwork,
            current: List<NetworkEntity>,
        ): NetworkEntity? {
            val probe = portable.toEntity(includeSecrets = true, parentId = null, local = null)
            return current.firstOrNull { it.role == portable.role && it.role != NetworkRole.BOUNCER_CHILD && networkIdentityKey(it) == networkIdentityKey(probe) }
        }

        private suspend fun upsertResolvedNetwork(network: NetworkEntity): Long =
            if (network.id == 0L) {
                db.networkDao().insert(network)
            } else {
                db.networkDao().update(network)
                network.id
            }

        private suspend fun applySettings(
            settings: PortableSettings,
            includeSecrets: Boolean,
            paletteRestore: PaletteRestore?,
            launcherIcon: LauncherIcon?,
        ) {
            settings.general?.let {
                val current = settingsRepository.settings.first()
                settingsRepository.setThemeMode(it.themeMode)
                settingsRepository.setDynamicColor(it.dynamicColor)
                settingsRepository.setDeliveryMode(it.deliveryMode)
                settingsRepository.setLayoutDensity(it.layoutDensity)
                settingsRepository.setCompactNickBrackets(it.compactNickBrackets)
                settingsRepository.setShowChannelPrefixes(it.showChannelPrefixes)
                settingsRepository.setFolderDisplayMode(it.folderDisplayMode)
                settingsRepository.setShowFolderChatsInAll(it.showFolderChatsInAll)
                settingsRepository.setSeasonalWordmarksEnabled(it.seasonalWordmarksEnabled)
                settingsRepository.setChatListSwipeAction(it.chatListSwipeAction)
                settingsRepository.setMentionsEnabled(it.mentionsEnabled)
                settingsRepository.setMentionsPlacement(it.mentionsPlacement)
                settingsRepository.setNickColorsEnabled(it.nickColorsEnabled)
                settingsRepository.setNickColorPalette(it.nickColorPalette)
                settingsRepository.setNickMentionStyle(it.nickMentionStyle)
                settingsRepository.setNicknameTapAction(it.nicknameTapAction)
                current.nickColorOverrides.keys
                    .filter { nick -> nick !in it.nickColorOverrides }
                    .forEach { nick -> settingsRepository.setNickColorOverride(nick, null) }
                it.nickColorOverrides.forEach { (nick, value) -> settingsRepository.setNickColorOverride(nick, value) }
                current.friends
                    .filter { nick -> nick !in it.friends }
                    .forEach { nick -> settingsRepository.setFriend(nick, false) }
                it.friends.forEach { nick -> settingsRepository.setFriend(nick, true) }
                current.fools
                    .filter { nick -> nick !in it.fools }
                    .forEach { nick -> settingsRepository.setFool(nick, false) }
                it.fools.forEach { nick -> settingsRepository.setFool(nick, true) }
                // Legacy archives have no overrides: never retain destination notification opt-ins.
                settingsRepository.settings.first().fools.forEach { nick ->
                    settingsRepository.setFoolPreferences(
                        nick,
                        io.github.trevarj.motd.data.prefs
                            .FoolPreferences(),
                    )
                }
                it.foolPreferences.forEach { (nick, preferences) ->
                    settingsRepository.setFoolPreferences(nick, preferences)
                }
                settingsRepository.setFoolsMode(it.foolsMode)
                settingsRepository.setPresenceMode(it.restoredPresenceMode())
                settingsRepository.setCustomPresenceKinds(it.customPresenceKinds)
                settingsRepository.setShowRedactedMessages(it.showRedactedMessages)
                settingsRepository.setChatListMessagesOnly(it.chatListMessagesOnly)
                settingsRepository.setSendTypingIndicators(it.sendTypingIndicators)
                settingsRepository.setShowTypingIndicators(it.showTypingIndicators)
                settingsRepository.setTypingIndicatorPlacement(it.typingIndicatorPlacement)
                settingsRepository.setAvatarStyle(it.avatarStyle)
                settingsRepository.setChatWallpaper(it.chatWallpaper)
                settingsRepository.setShowComposerEmoji(it.showComposerEmoji)
                settingsRepository.setShowComposerFormattingTools(it.showComposerFormattingTools)
                settingsRepository.setShowVoiceMessageButton(it.showVoiceMessageButton)
                settingsRepository.setChatSoundsEnabled(it.chatSoundsEnabled)
                settingsRepository.setHistorySyncDepth(it.historySyncDepth)
                settingsRepository.setHistorySyncMode(it.historySyncMode)
                settingsRepository.setHistoryRetention(it.historyRetention)
                settingsRepository.setHistoryRetentionCustomRows(it.historyRetentionCustomRows)
                settingsRepository.setAutoCompactMb(it.autoCompactMb)
                settingsRepository.setAutoAwayEnabled(it.autoAwayEnabled)
                settingsRepository.setAutoAwayMinutes(it.autoAwayMinutes)
                settingsRepository.setAutoAwayMessage(it.autoAwayMessage)
            }
            settings.appearance?.let {
                val target = requireNotNull(paletteRestore)
                appearancePrefs.restorePalettes(target.palettes, target.selection)
                appearancePrefs.setTrueBlack(it.trueBlack || (it.paletteMode == null && it.theme == ColorThemePreset.AMOLED))
                appearancePrefs.setUseThemeTextColors(it.useThemeTextColors)
                appearancePrefs.setAdjustPaletteReadability(it.adjustPaletteReadability)
                appearancePrefs.setUiFontScale(it.uiFontScalePercent)
                appearancePrefs.setConversationFontScale(it.conversationFontScalePercent)
                appearancePrefs.setFontChoice(it.fontChoice)
                // Keep the selection even without its downloaded binary so settings can offer reinstall.
                appearancePrefs.setEmojiSetId(it.emojiSetId)
                appearancePrefs.setShowTimestamps(it.showTimestamps)
                appearancePrefs.setTimeFormat(it.timeFormat)
                appearancePrefs.setCustomTimeFormatPattern(it.customTimeFormatPattern)
                appearancePrefs.setMessageSpacing(it.messageSpacing)
                appearancePrefs.setComposerStyle(it.composerStyle)
                appearancePrefs.setInputCornerStyle(it.inputCornerStyle)
                appearancePrefs.setAvatarShape(it.avatarShape)
                appearancePrefs.setChatCardCornerStyle(it.chatCardCornerStyle)
                appearancePrefs.setCollapseTitleBar(it.collapseTitleBar)
                appearancePrefs.setMediaCornerStyle(it.mediaCornerStyle)
                appearancePrefs.setUiIconSet(it.uiIconSet)
                appearancePrefs.setBubbleCornerStyle(it.bubbleCornerStyle)
                appearancePrefs.setChatShadowsEnabled(it.chatShadowsEnabled)
                appearancePrefs.setNicknameBackgroundsEnabled(it.nicknameBackgroundsEnabled)
                appearancePrefs.restoreGlobalEffects(it)
                appearancePrefs.setSentBubbleColor(it.sentBubbleColor)
                appearancePrefs.setReceivedBubbleColor(it.receivedBubbleColor)
                if (launcherIcon != null) appearancePrefs.setLauncherIcon(launcherIcon)
                appearancePrefs.setSplashScreenAnimation(it.splashScreenAnimation)
                // The font binary itself is not backed up; only the display name travels. A restored
                // CUSTOM choice falls back to system until the user re-imports the file (see AppFonts).
                appearancePrefs.setCustomFontName(it.customFontName)
            }
            settings.contentPreviews?.let {
                contentPreviewPrefs.setShowImages(it.showImages)
                contentPreviewPrefs.setShowLinkPreviews(it.showLinkPreviews)
                contentPreviewPrefs.setAutoLoadOnUnmetered(it.autoLoadOnUnmetered)
                contentPreviewPrefs.setAutoLoadOnMetered(it.autoLoadOnMetered)
            }
            settings.chatSounds?.let { chatSoundPrefs.replace(it) }
            settings.replies?.let {
                replyPrefs.setVisibleChannelPrefix(it.visibleChannelPrefix)
                replyPrefs.setSwipeToReplyEnabled(it.swipeToReplyEnabled)
            }
            settings.attachments?.let { imported ->
                val resolved =
                    if (includeSecrets) {
                        imported
                    } else {
                        val local = attachmentPrefs.config.first()
                        val importedAuthority = credentialAuthority(imported.customEndpoint)
                        if (importedAuthority != null && importedAuthority == credentialAuthority(local.customEndpoint)) {
                            imported.copy(username = local.username, password = local.password)
                        } else {
                            imported.copy(username = "", password = "")
                        }
                    }
                attachmentPrefs.setConfig(resolved.copy(stripHostedImageMetadata = resolved.stripHostedImageMetadata ?: resolved.stripMetadata))
            }
            settings.voice?.let {
                voicePrefs.replace(it)
            }
            settings.showSharedAvatars?.let { avatarPrefs.setShowSharedAvatars(it) }
            settings.gestureMenu?.let { gesturePrefs.setMenu(it) }
        }

        private suspend fun applyRemappedNetworkPrefs(
            payload: BackupPayload,
            idMap: Map<String, Long>,
            importMode: BackupImportMode,
        ) {
            if (importMode == BackupImportMode.REPLACE) {
                bouncerKindPrefs.zncNetworkIds.first().forEach { bouncerKindPrefs.clear(it) }
            }
            payload.networks.forEach { portable ->
                val localId = idMap[portable.exportId] ?: return@forEach
                if (portable.znc) bouncerKindPrefs.markZnc(localId) else bouncerKindPrefs.clear(localId)
            }
            payload.settings.selfAvatars.forEach { entry ->
                val localId = idMap[entry.networkExportId] ?: return@forEach
                avatarPrefs.setSelfSetting(localId, entry.setting.toSelfAvatarSetting())
            }
        }

        private fun encryptPayload(
            plainText: ByteArray,
            password: String,
            appVersion: String,
            exportedAt: Long,
        ): EncryptedPayload {
            val salt = ByteArray(16).also(random::nextBytes)
            val nonce = ByteArray(12).also(random::nextBytes)
            val key = deriveKey(password, salt, PBKDF2_ITERATIONS)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(encryptionAad(appVersion, exportedAt).encodeToByteArray())
            val ciphertext = cipher.doFinal(plainText)
            return EncryptedPayload(
                kdf = "PBKDF2WithHmacSHA256",
                iterations = PBKDF2_ITERATIONS,
                salt = salt.b64(),
                cipher = "AES-256-GCM",
                nonce = nonce.b64(),
                ciphertext = ciphertext.b64(),
            )
        }

        private fun decryptPayload(
            encrypted: EncryptedPayload,
            password: String,
            appVersion: String,
            exportedAt: Long,
        ): ByteArray {
            if (encrypted.kdf != "PBKDF2WithHmacSHA256" || encrypted.cipher != "AES-256-GCM") {
                throw BackupFormatException()
            }
            if (encrypted.iterations !in 100_000..PBKDF2_ITERATIONS) throw BackupFormatException()
            val salt = encrypted.salt.fromB64()
            val nonce = encrypted.nonce.fromB64()
            val ciphertext = encrypted.ciphertext.fromB64()
            val key = deriveKey(password, salt, encrypted.iterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(encryptionAad(appVersion, exportedAt).encodeToByteArray())
            return cipher.doFinal(ciphertext)
        }

        private fun deriveKey(
            password: String,
            salt: ByteArray,
            iterations: Int,
        ): SecretKeySpec {
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, AES_KEY_BITS)
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            spec.clearPassword()
            return SecretKeySpec(bytes, "AES")
        }

        private fun encryptionAad(
            appVersion: String,
            exportedAt: Long,
        ): String = "motdconfig-v1|$appVersion|$exportedAt|${BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS.name}"
    }

private data class PaletteRestore(
    val palettes: List<InstalledPalette>,
    val selection: PaletteSelection,
)

private data class WallpaperRestore(
    val expected: WallpaperLibrarySnapshot,
    val library: WallpaperLibrary?,
    val selection: WallpaperSelection,
    val assignments: List<PortableWallpaperAssignment>,
)

@Serializable
private data class BackupEnvelope(
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String,
    val exportedAtEpochMillis: Long,
    val mode: BackupEnvelopeMode,
    val payload: BackupPayload? = null,
    val encryptedPayload: EncryptedPayload? = null,
)

@Serializable
private enum class BackupEnvelopeMode { CREDENTIALS_EXCLUDED, ENCRYPTED_WITH_CREDENTIALS }

private fun credentialAuthority(endpoint: String): String? =
    runCatching {
        val uri = URI(endpoint)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
        "${uri.host.lowercase()}:${if (uri.port >= 0) uri.port else 443}"
    }.getOrNull()

@Serializable
private data class EncryptedPayload(
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val cipher: String,
    val nonce: String,
    val ciphertext: String,
)

@Serializable
private data class BackupPayload(
    val version: Int = FORMAT_VERSION,
    val networks: List<PortableNetwork>,
    val settings: PortableSettings,
    val folders: List<PortableFolder> = emptyList(),
    val folderAssignments: List<PortableFolderAssignment> = emptyList(),
    val ignoredAutoGroupPatterns: List<PortableIgnoredAutoGroupPattern> = emptyList(),
    val chatPlacements: List<PortablePlacement>? = null,
    val chatWallpaperAssignments: List<PortableWallpaperAssignment>? = null,
)

@Serializable
private data class PortablePlacement(
    val networkExportId: String,
    val chatType: BufferType,
    val identityKind: FolderIdentityKind,
    val identityValue: String,
    val pinned: Boolean = false,
    val anchorOrder: Int? = null,
) {
    fun toRepository(networkId: Long) = PortableChatPlacement(networkId, chatType, identityKind, identityValue, pinned, anchorOrder)

    companion object {
        fun fromRepository(
            placement: PortableChatPlacement,
            networkExportId: String,
        ) = PortablePlacement(networkExportId, placement.chatType, placement.identityKind, placement.identityValue, placement.pinned, placement.anchorOrder)
    }
}

@Serializable
private data class PortableWallpaperAssignment(
    val networkExportId: String,
    val chatType: BufferType,
    val identityKind: FolderIdentityKind,
    val identityValue: String,
    val choice: WallpaperChatChoice,
) {
    fun toRepository(networkId: Long) = PortableChatWallpaper(networkId, chatType, identityKind, identityValue, choice)

    companion object {
        fun fromRepository(
            assignment: PortableChatWallpaper,
            networkExportId: String,
        ) = PortableWallpaperAssignment(networkExportId, assignment.chatType, assignment.identityKind, assignment.identityValue, assignment.choice)
    }
}

@Serializable
private data class PortableFolder(
    val exportId: String,
    val name: String,
    val iconKind: FolderIconKind = FolderIconKind.GENERIC,
    val iconKey: String = "folder",
    val ordering: Int = 0,
    val expanded: Boolean = true,
) {
    fun toRepository() = FolderPortableDefinition(exportId, name, FolderIconRef(iconKind, iconKey), ordering, expanded)

    companion object {
        fun fromRepository(folder: FolderPortableDefinition) = PortableFolder(folder.exportId, folder.name, folder.icon.kind, folder.icon.key, folder.ordering, folder.expanded)
    }
}

@Serializable
private data class PortableFolderAssignment(
    val networkExportId: String,
    val folderExportId: String,
    val chatType: BufferType,
    val identityKind: FolderIdentityKind,
    val identityValue: String,
) {
    fun toRepository(networkId: Long) = FolderPortableAssignment(networkId, folderExportId, chatType, identityKind, identityValue)
}

@Serializable
private data class PortableIgnoredAutoGroupPattern(
    val networkExportId: String,
    val normalizedPrefix: String,
)

@Serializable
private data class PortableSettings(
    val general: Settings? = null,
    val appearance: AppearanceConfig? = null,
    val contentPreviews: ContentPreviewConfig? = null,
    val chatSounds: ChatSoundConfig? = null,
    val replies: ReplyConfig? = null,
    val attachments: PasteBackendConfig? = null,
    val voice: VoiceConfig? = null,
    val showSharedAvatars: Boolean? = null,
    val selfAvatars: List<PortableSelfAvatar> = emptyList(),
    val gestureMenu: GestureMenuConfig? = null,
) {
    fun groupNames(): List<String> =
        buildList {
            if (general != null) add("general")
            if (appearance != null) add("appearance")
            if (contentPreviews != null) add("content previews")
            if (chatSounds != null) add("chat sounds")
            if (replies != null) add("replies")
            if (attachments != null) add("uploads")
            if (voice != null) add("voice")
            if (showSharedAvatars != null || selfAvatars.isNotEmpty()) add("avatars")
            if (gestureMenu != null) add("gesture menu")
        }
}

@Serializable
private data class PortableSelfAvatar(
    val networkExportId: String,
    val setting: PortableSelfAvatarSetting,
)

@Serializable
private data class PortableSelfAvatarSetting(
    val mode: PortableSelfAvatarMode,
    val url: String? = null,
)

@Serializable
private enum class PortableSelfAvatarMode { UNMANAGED, CLEARED, SET }

@Serializable
private data class PortableNetwork(
    val exportId: String,
    val name: String,
    val role: NetworkRole,
    val parentExportId: String? = null,
    val bouncerNetId: String? = null,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val nick: String,
    val username: String,
    val realname: String,
    val saslMechanism: String,
    val saslUser: String? = null,
    val saslPassword: String? = null,
    val hadSaslPassword: Boolean = false,
    val serverPassword: String? = null,
    val hadServerPassword: Boolean = false,
    val nickServPassword: String? = null,
    val hadNickServPassword: Boolean = false,
    val nickServIdentifySyntax: String? = null,
    val nickServRecoveryEnabled: Boolean = false,
    val nickServRecoverySequence: String? = null,
    val initialAwayMessage: String? = null,
    val onConnectCommands: String? = null,
    val hadClientCertificate: Boolean = false,
    val autoConnect: Boolean,
    val ordering: Int,
    val wsUrl: String? = null,
    val obfsMode: ObfsMode? = null,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    val obfsLink: String? = null,
    val hadObfsLink: Boolean = false,
    val trustedFileHost: String? = null,
    val znc: Boolean = false,
) {
    override fun toString(): String = "PortableNetwork(exportId=$exportId, name=$name, role=$role, host=$host:$port)"
}

private data class DecodedDocument(
    val envelope: BackupEnvelope,
    val payload: BackupPayload,
    val launcherIcon: LauncherIcon?,
)

private data class ImportPlan(
    val added: Int,
    val updated: Int,
    val removed: Int,
    val retainedLocalCredentials: Int,
    val missingCredentialNetworks: Int,
    val networkIds: Map<String, Long>,
)

private fun FolderPortableAssignment.toPortable(networkExportId: String) =
    PortableFolderAssignment(
        networkExportId = networkExportId,
        folderExportId = folderExportId,
        chatType = chatType,
        identityKind = identityKind,
        identityValue = identityValue,
    )

private fun NetworkEntity.toPortable(
    exportIds: Map<Long, String>,
    includeSecrets: Boolean,
    zncIds: Set<Long>,
): PortableNetwork =
    PortableNetwork(
        exportId = exportIds.getValue(id),
        name = name,
        role = role,
        parentExportId = parentId?.let(exportIds::get),
        bouncerNetId = bouncerNetId,
        host = host,
        port = port,
        tls = tls,
        nick = nick,
        username = username,
        realname = realname,
        saslMechanism = saslMechanism,
        onConnectCommands = onConnectCommands.takeIf { includeSecrets },
        saslUser = saslUser,
        saslPassword = saslPassword.takeIf { includeSecrets },
        hadSaslPassword = !saslPassword.isNullOrBlank(),
        serverPassword = serverPassword.takeIf { includeSecrets },
        hadServerPassword = !serverPassword.isNullOrBlank(),
        nickServPassword = nickServPassword.takeIf { includeSecrets },
        hadNickServPassword = !nickServPassword.isNullOrBlank(),
        nickServIdentifySyntax = nickServIdentifySyntax,
        nickServRecoveryEnabled = nickServRecoveryEnabled,
        nickServRecoverySequence = nickServRecoverySequence,
        initialAwayMessage = initialAwayMessage,
        hadClientCertificate = !clientCertAlias.isNullOrBlank(),
        autoConnect = restoreAutoConnect.takeIf { pendingCredentialRequirements != null } ?: autoConnect,
        ordering = ordering,
        wsUrl = wsUrl,
        obfsMode = obfsMode,
        proxyHost = proxyHost,
        proxyPort = proxyPort,
        obfsLink = obfsLink.takeIf { includeSecrets },
        hadObfsLink = !obfsLink.isNullOrBlank(),
        // FILEHOST is a credential authority. Plaintext backups must never be able to redirect a
        // locally retained SASL credential to a host supplied by an untrusted document.
        trustedFileHost = trustedFileHost.takeIf { includeSecrets },
        znc = id in zncIds,
    )

private fun PortableNetwork.toEntity(
    includeSecrets: Boolean,
    parentId: Long?,
    local: NetworkEntity?,
): NetworkEntity {
    val retainedSasl = if (!includeSecrets && saslPassword == null) local?.saslPassword else saslPassword
    val retainedServerPassword = if (!includeSecrets && serverPassword == null) local?.serverPassword else serverPassword
    val retainedNickServPassword =
        if (!includeSecrets && nickServPassword == null) local?.nickServPassword else nickServPassword
    val retainedObfsLink = if (!includeSecrets && obfsLink == null) local?.obfsLink else obfsLink
    val requirements =
        missingRequirements(
            localSaslPassword = retainedSasl,
            localServerPassword = retainedServerPassword,
            localNickServPassword = retainedNickServPassword,
            localObfsLink = retainedObfsLink,
        )
    return NetworkEntity(
        id = local?.id ?: 0L,
        name = name,
        role = role,
        parentId = parentId,
        bouncerNetId = bouncerNetId,
        host = host,
        port = port,
        tls = tls,
        nick = nick,
        username = username,
        realname = realname,
        saslMechanism = saslMechanism,
        saslUser = saslUser,
        onConnectCommands = if (includeSecrets) onConnectCommands.orEmpty() else local?.onConnectCommands.orEmpty(),
        saslPassword = retainedSasl,
        serverPassword = retainedServerPassword,
        nickServPassword = retainedNickServPassword,
        nickServIdentifySyntax = nickServIdentifySyntax,
        nickServRecoveryEnabled = nickServRecoveryEnabled,
        nickServRecoverySequence = nickServRecoverySequence,
        initialAwayMessage = initialAwayMessage,
        clientCertAlias = null,
        autoConnect = autoConnect && requirements.isEmpty(),
        ordering = ordering,
        wsUrl = wsUrl,
        obfsMode = obfsMode,
        proxyHost = proxyHost,
        proxyPort = proxyPort,
        obfsLink = retainedObfsLink,
        // A credentials-excluded merge preserves the local authority alongside its retained
        // credential. Encrypted backups authenticate this field and may explicitly clear it.
        trustedFileHost = if (includeSecrets) trustedFileHost else local?.trustedFileHost,
        pendingCredentialRequirements = requirements.takeIf { it.isNotEmpty() }?.joinToString(","),
        restoreAutoConnect = autoConnect,
    )
}

private fun PortableNetwork.retainsAnyLocalSecret(local: NetworkEntity): Boolean =
    (hadSaslPassword && saslPassword == null && !local.saslPassword.isNullOrBlank()) ||
        (hadServerPassword && serverPassword == null && !local.serverPassword.isNullOrBlank()) ||
        (hadNickServPassword && nickServPassword == null && !local.nickServPassword.isNullOrBlank()) ||
        (hadObfsLink && obfsLink == null && !local.obfsLink.isNullOrBlank())

private fun PortableNetwork.missingCredentials(local: NetworkEntity?): Boolean =
    missingRequirements(
        localSaslPassword = local?.saslPassword,
        localServerPassword = local?.serverPassword,
        localNickServPassword = local?.nickServPassword,
        localObfsLink = local?.obfsLink,
    ).isNotEmpty()

private fun PortableNetwork.missingRequirements(
    localSaslPassword: String?,
    localServerPassword: String?,
    localNickServPassword: String?,
    localObfsLink: String?,
): List<String> =
    buildList {
        if (hadSaslPassword && saslPassword.isNullOrBlank() && localSaslPassword.isNullOrBlank()) add("saslPassword")
        if (hadServerPassword && serverPassword.isNullOrBlank() && localServerPassword.isNullOrBlank()) add("serverPassword")
        if (hadNickServPassword && nickServPassword.isNullOrBlank() && localNickServPassword.isNullOrBlank()) {
            add("nickServPassword")
        }
        if (hadObfsLink && obfsLink.isNullOrBlank() && localObfsLink.isNullOrBlank()) add("obfsLink")
        if (hadClientCertificate) add("clientCertificate")
    }

private fun SelfAvatarSetting.toPortable(): PortableSelfAvatarSetting =
    when (this) {
        SelfAvatarSetting.Unmanaged -> PortableSelfAvatarSetting(PortableSelfAvatarMode.UNMANAGED)
        SelfAvatarSetting.ExplicitlyCleared -> PortableSelfAvatarSetting(PortableSelfAvatarMode.CLEARED)
        is SelfAvatarSetting.Set -> PortableSelfAvatarSetting(PortableSelfAvatarMode.SET, url)
    }

private fun PortableSelfAvatarSetting.toSelfAvatarSetting(): SelfAvatarSetting =
    when (mode) {
        PortableSelfAvatarMode.UNMANAGED -> SelfAvatarSetting.Unmanaged
        PortableSelfAvatarMode.CLEARED -> SelfAvatarSetting.ExplicitlyCleared
        PortableSelfAvatarMode.SET -> url?.let(SelfAvatarSetting::Set) ?: SelfAvatarSetting.Unmanaged
    }

/**
 * Archives written before presence modes existed carry only the former `showJoinPartQuit` boolean,
 * so an explicit hide/show choice survives a restore; anything newer already carries [presenceMode]
 * and the legacy field is absent.
 */
private fun Settings.restoredPresenceMode(): PresenceMode =
    when (showJoinPartQuit) {
        null -> presenceMode
        false -> PresenceMode.HIDDEN
        true -> PresenceMode.ALL
    }

private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)

private fun String.fromB64(): ByteArray = Base64.getDecoder().decode(this)
