package io.github.trevarj.motd.data.backup

import androidx.room.withTransaction
import io.github.trevarj.motd.BuildConfig
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.audio.VoiceConfig
import io.github.trevarj.motd.audio.VoicePrefs
import io.github.trevarj.motd.avatar.AvatarPrefs
import io.github.trevarj.motd.avatar.SelfAvatarSetting
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ConnectionTransport
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
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.ReplyPrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.ChatFolderRepository
import io.github.trevarj.motd.data.repo.FolderIconRef
import io.github.trevarj.motd.data.repo.FolderPortableAssignment
import io.github.trevarj.motd.data.repo.FolderPortableDefinition
import io.github.trevarj.motd.data.repo.networkIdentityKey
import io.github.trevarj.motd.gesture.GestureMenuConfig
import io.github.trevarj.motd.gesture.GesturePrefs
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private const val FORMAT_VERSION = 1
private const val MAX_DOCUMENT_BYTES = 4 * 1024 * 1024
private const val MAX_DECRYPTED_BYTES = 2 * 1024 * 1024
private const val MAX_NETWORKS = 512
private const val MAX_FOLDERS = 512
private const val MAX_FOLDER_ASSIGNMENTS = 100_000
private const val MAX_IGNORED_AUTO_GROUP_PATTERNS = 10_000
private const val MAX_PORTABLE_IDENTITY_LENGTH = 512
private const val MAX_ICON_REFERENCE_LENGTH = 128
private const val PBKDF2_ITERATIONS = 600_000
private const val AES_KEY_BITS = 256
private const val GCM_TAG_BITS = 128

enum class BackupExportMode { CREDENTIALS_EXCLUDED, ENCRYPTED_WITH_CREDENTIALS }

enum class BackupImportMode { MERGE, REPLACE }

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
        private val replyPrefs: ReplyPrefs,
        private val attachmentPrefs: AttachmentPrefs,
        private val voicePrefs: VoicePrefs,
        private val avatarPrefs: AvatarPrefs,
        private val bouncerKindPrefs: BouncerKindPrefs,
        private val gesturePrefs: GesturePrefs,
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
                    val encryptedPayload =
                        encryptPayload(
                            compactJson.encodeToString(payload).encodeToByteArray(),
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
            return json.encodeToString(envelope)
        }

        override fun isEncrypted(rawDocument: String): Boolean = decodeEnvelope(rawDocument).mode == BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS

        override suspend fun preview(
            rawDocument: String,
            password: String?,
            importMode: BackupImportMode,
        ): ConfigurationImportPreview {
            val decoded = decodeDocument(rawDocument, password)
            val plan = planImport(decoded.payload, importMode)
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
            validatePayload(decoded.payload)
            val plan = planImport(decoded.payload, importMode)

            applySettings(
                decoded.payload.settings,
                includeSecrets = decoded.envelope.mode == BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS,
            )
            val idMap = mutableMapOf<String, Long>()
            val importedIds = mutableSetOf<Long>()
            db.withTransaction {
                val current = db.networkDao().allNow()
                val rootsAndDirect =
                    decoded.payload.networks
                        .filter { it.role != NetworkRole.BOUNCER_CHILD }
                        .sortedBy { it.ordering }
                rootsAndDirect.forEach { portable ->
                    val resolved =
                        portable.toEntity(
                            includeSecrets = decoded.envelope.mode == BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS,
                            parentId = null,
                            local = matchTopLevel(portable, current),
                        )
                    val id = upsertResolvedNetwork(resolved)
                    idMap[portable.exportId] = id
                    importedIds += id
                }
                decoded.payload.networks
                    .filter { it.role == NetworkRole.BOUNCER_CHILD }
                    .sortedBy { it.ordering }
                    .forEach { portable ->
                        val parentId = idMap[portable.parentExportId] ?: return@forEach
                        val local =
                            current.firstOrNull {
                                it.role == NetworkRole.BOUNCER_CHILD &&
                                    it.parentId == parentId &&
                                    it.bouncerNetId == portable.bouncerNetId
                            }
                        val resolved =
                            portable.toEntity(
                                includeSecrets = decoded.envelope.mode == BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS,
                                parentId = parentId,
                                local = local,
                            )
                        val id = upsertResolvedNetwork(resolved)
                        idMap[portable.exportId] = id
                        importedIds += id
                    }
                if (importMode == BackupImportMode.REPLACE) {
                    current
                        .asSequence()
                        .filter { it.id !in importedIds }
                        .filter { it.parentId == null || it.parentId !in importedIds }
                        .forEach { db.networkDao().deleteLocalTree(it.id) }
                }
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
            applyRemappedNetworkPrefs(decoded.payload, idMap, importMode)
            return ConfigurationImportResult(
                addedNetworks = plan.added,
                updatedNetworks = plan.updated,
                removedNetworks = plan.removed,
                missingCredentialNetworks = plan.missingCredentialNetworks,
            )
        }

        private suspend fun snapshotPayload(includeSecrets: Boolean): BackupPayload {
            val networks = db.networkDao().allNow().sortedWith(compareBy<NetworkEntity> { it.parentId ?: 0L }.thenBy { it.ordering })
            val exportIds = networks.associate { it.id to "network-${it.id}" }
            val zncIds = bouncerKindPrefs.zncNetworkIds.first()
            val folderSnapshot = chatFolders.backupSnapshot()
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
                        appearance = appearancePrefs.config.first(),
                        contentPreviews = contentPreviewPrefs.config.first(),
                        replies = replyPrefs.config.first(),
                        attachments =
                            attachmentPrefs.config.first().let { config ->
                                if (includeSecrets) config else config.copy(username = "", password = "")
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

        private fun decodeDocument(
            rawDocument: String,
            password: String?,
        ): DecodedDocument {
            val envelope = decodeEnvelope(rawDocument)
            require(envelope.formatVersion == FORMAT_VERSION) { "Unsupported backup format ${envelope.formatVersion}." }
            val payload =
                when (envelope.mode) {
                    BackupEnvelopeMode.CREDENTIALS_EXCLUDED -> {
                        envelope.payload
                            ?: error("Backup payload is missing.")
                    }

                    BackupEnvelopeMode.ENCRYPTED_WITH_CREDENTIALS -> {
                        require(!password.isNullOrBlank()) { "This backup requires its export password." }
                        val encryptedPayload = envelope.encryptedPayload ?: error("Encrypted backup payload is missing.")
                        val decrypted = decryptPayload(encryptedPayload, password, envelope.appVersion, envelope.exportedAtEpochMillis)
                        require(decrypted.size <= MAX_DECRYPTED_BYTES) { "Backup payload is too large." }
                        compactJson.decodeFromString<BackupPayload>(decrypted.decodeToString())
                    }
                }
            validatePayload(payload)
            return DecodedDocument(envelope, payload)
        }

        private fun decodeEnvelope(rawDocument: String): BackupEnvelope {
            require(rawDocument.encodeToByteArray().size <= MAX_DOCUMENT_BYTES) { "Backup file is too large." }
            return compactJson.decodeFromString<BackupEnvelope>(rawDocument)
        }

        private fun validatePayload(payload: BackupPayload) {
            require(payload.version == FORMAT_VERSION) { "Unsupported payload version ${payload.version}." }
            require(payload.networks.size <= MAX_NETWORKS) { "Too many networks in backup." }
            val ids = payload.networks.map { it.exportId }
            require(ids.size == ids.toSet().size) { "Backup contains duplicate network ids." }
            val idSet = ids.toSet()
            payload.networks.forEach { network ->
                require(network.exportId.isNotBlank()) { "Backup contains a network without an id." }
                require(network.name.isNotBlank()) { "Backup contains a network without a name." }
                if (network.connectionTransport == ConnectionTransport.SIDECAR) {
                    require(!network.sidecarPackage.isNullOrBlank() && !network.sidecarService.isNullOrBlank()) {
                        "Backup contains a companion network without a provider."
                    }
                    require(network.port == 0) { "Backup contains an invalid companion port." }
                } else {
                    require(network.host.isNotBlank()) { "Backup contains a network without a host." }
                    require(network.port in 1..65535) { "Backup contains an invalid port." }
                }
                require(network.nick.isNotBlank()) { "Backup contains a network without a nick." }
                if (network.role == NetworkRole.BOUNCER_CHILD) {
                    require(!network.parentExportId.isNullOrBlank() && network.parentExportId in idSet) {
                        "Backup contains a bouncer child without a valid parent."
                    }
                    require(!network.bouncerNetId.isNullOrBlank()) {
                        "Backup contains a bouncer child without a bouncer network id."
                    }
                }
                network.wsUrl?.let { require(it.startsWith("wss://")) { "Backup contains an invalid WebSocket URL." } }
                network.proxyPort?.let { require(it in 1..65535) { "Backup contains an invalid proxy port." } }
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

        private suspend fun planImport(
            payload: BackupPayload,
            importMode: BackupImportMode,
        ): ImportPlan {
            val current = db.networkDao().allNow()
            val matched = mutableSetOf<Long>()
            var added = 0
            var updated = 0
            var retainedLocalCredentials = 0
            var missingCredentialNetworks = 0
            val parentMatches = mutableMapOf<String, Long>()
            payload.networks.filter { it.role != NetworkRole.BOUNCER_CHILD }.forEach { portable ->
                val local = matchTopLevel(portable, current)
                if (local == null) {
                    added++
                } else {
                    updated++
                    matched += local.id
                    parentMatches[portable.exportId] = local.id
                    if (portable.retainsAnyLocalSecret(local)) retainedLocalCredentials++
                }
                if (portable.missingCredentials(local)) missingCredentialNetworks++
            }
            payload.networks.filter { it.role == NetworkRole.BOUNCER_CHILD }.forEach { portable ->
                val parentId = parentMatches[portable.parentExportId]
                val local =
                    current.firstOrNull {
                        parentId != null && it.role == NetworkRole.BOUNCER_CHILD &&
                            it.parentId == parentId && it.bouncerNetId == portable.bouncerNetId
                    }
                if (local == null) {
                    added++
                } else {
                    updated++
                    matched += local.id
                    if (portable.retainsAnyLocalSecret(local)) retainedLocalCredentials++
                }
                if (portable.missingCredentials(local)) missingCredentialNetworks++
            }
            val removed = if (importMode == BackupImportMode.REPLACE) current.count { it.id !in matched } else 0
            return ImportPlan(added, updated, removed, retainedLocalCredentials, missingCredentialNetworks)
        }

        private fun matchTopLevel(
            portable: PortableNetwork,
            current: List<NetworkEntity>,
        ): NetworkEntity? {
            if (portable.connectionTransport == ConnectionTransport.SIDECAR) {
                return current.firstOrNull {
                    it.role == portable.role &&
                        it.connectionTransport == ConnectionTransport.SIDECAR &&
                        it.sidecarPackage == portable.sidecarPackage &&
                        it.sidecarService == portable.sidecarService &&
                        it.name == portable.name
                }
            }
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
        ) {
            settings.general?.let {
                val current = settingsRepository.settings.first()
                settingsRepository.setThemeMode(it.themeMode)
                settingsRepository.setDynamicColor(it.dynamicColor)
                settingsRepository.setDeliveryMode(it.deliveryMode)
                settingsRepository.setLayoutDensity(it.layoutDensity)
                settingsRepository.setNickColorsEnabled(it.nickColorsEnabled)
                settingsRepository.setNickColorPalette(it.nickColorPalette)
                current.nickColorOverrides.keys
                    .filter { nick -> nick !in it.nickColorOverrides }
                    .forEach { nick -> settingsRepository.setNickColorOverride(nick, null) }
                it.nickColorOverrides.forEach { (nick, hue) -> settingsRepository.setNickColorOverride(nick, hue) }
                current.friends
                    .filter { nick -> nick !in it.friends }
                    .forEach { nick -> settingsRepository.setFriend(nick, false) }
                it.friends.forEach { nick -> settingsRepository.setFriend(nick, true) }
                current.fools
                    .filter { nick -> nick !in it.fools }
                    .forEach { nick -> settingsRepository.setFool(nick, false) }
                it.fools.forEach { nick -> settingsRepository.setFool(nick, true) }
                settingsRepository.setFoolsMode(it.foolsMode)
                settingsRepository.setPresenceMode(it.restoredPresenceMode())
                settingsRepository.setShowRedactedMessages(it.showRedactedMessages)
                settingsRepository.setAvatarStyle(it.avatarStyle)
                settingsRepository.setChatWallpaper(it.chatWallpaper)
                settingsRepository.setShowComposerEmoji(it.showComposerEmoji)
                settingsRepository.setShowComposerFormattingTools(it.showComposerFormattingTools)
                settingsRepository.setChatSoundsEnabled(it.chatSoundsEnabled)
                settingsRepository.setHistorySyncDepth(it.historySyncDepth)
                settingsRepository.setAutoAwayEnabled(it.autoAwayEnabled)
                settingsRepository.setAutoAwayMinutes(it.autoAwayMinutes)
                settingsRepository.setAutoAwayMessage(it.autoAwayMessage)
            }
            settings.appearance?.let {
                appearancePrefs.setTheme(it.theme)
                appearancePrefs.setTrueBlack(it.trueBlack)
                appearancePrefs.setFollowSystem(it.followSystem)
                appearancePrefs.setWallpaper(it.wallpaper)
                appearancePrefs.setUiFontScale(it.uiFontScalePercent)
                appearancePrefs.setConversationFontScale(it.conversationFontScalePercent)
                appearancePrefs.setFontChoice(it.fontChoice)
                appearancePrefs.setShowTimestamps(it.showTimestamps)
                appearancePrefs.setTimeFormat(it.timeFormat)
                appearancePrefs.setCustomTimeFormatPattern(it.customTimeFormatPattern)
                appearancePrefs.setMessageSpacing(it.messageSpacing)
                appearancePrefs.setBubbleCornerStyle(it.bubbleCornerStyle)
                appearancePrefs.setLauncherIcon(it.launcherIcon)
                // The font binary itself is not backed up; only the display name travels. A restored
                // CUSTOM choice falls back to system until the user re-imports the file (see AppFonts).
                appearancePrefs.setCustomFontName(it.customFontName)
            }
            settings.contentPreviews?.let {
                contentPreviewPrefs.setShowImages(it.showImages)
                contentPreviewPrefs.setShowLinkPreviews(it.showLinkPreviews)
                contentPreviewPrefs.setDirectMediaOnProxiedNetworks(it.directMediaOnProxiedNetworks)
            }
            settings.replies?.let { replyPrefs.setVisibleChannelPrefix(it.visibleChannelPrefix) }
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
                attachmentPrefs.setConfig(resolved)
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
            require(encrypted.kdf == "PBKDF2WithHmacSHA256" && encrypted.cipher == "AES-256-GCM") {
                "Unsupported backup encryption."
            }
            require(encrypted.iterations in 100_000..PBKDF2_ITERATIONS) { "Unsupported backup work factor." }
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
)

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
    val hadClientCertificate: Boolean = false,
    val autoConnect: Boolean,
    val ordering: Int,
    val wsUrl: String? = null,
    val obfsMode: ObfsMode? = null,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    val obfsLink: String? = null,
    val hadObfsLink: Boolean = false,
    val znc: Boolean = false,
    val connectionTransport: ConnectionTransport = ConnectionTransport.NETWORK,
    val sidecarPackage: String? = null,
    val sidecarService: String? = null,
) {
    override fun toString(): String = "PortableNetwork(exportId=$exportId, name=$name, role=$role, host=$host:$port)"
}

private data class DecodedDocument(
    val envelope: BackupEnvelope,
    val payload: BackupPayload,
)

private data class ImportPlan(
    val added: Int,
    val updated: Int,
    val removed: Int,
    val retainedLocalCredentials: Int,
    val missingCredentialNetworks: Int,
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
        znc = id in zncIds,
        connectionTransport = connectionTransport,
        sidecarPackage = sidecarPackage,
        sidecarService = sidecarService,
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
        ) + if (connectionTransport == ConnectionTransport.SIDECAR && local?.sidecarAccountId.isNullOrBlank()) listOf("sidecarAccount") else emptyList()
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
        pendingCredentialRequirements = requirements.takeIf { it.isNotEmpty() }?.joinToString(","),
        restoreAutoConnect = autoConnect,
        connectionTransport = connectionTransport,
        sidecarPackage = sidecarPackage,
        sidecarService = sidecarService,
        // Provider account ids and signer trust are intentionally device-local.
        sidecarAccountId = local?.sidecarAccountId,
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
    ).isNotEmpty() || (connectionTransport == ConnectionTransport.SIDECAR && local?.sidecarAccountId.isNullOrBlank())

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
