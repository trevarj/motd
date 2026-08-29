package io.github.trevarj.motd.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.AttachmentPrefsImpl
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.audio.VoicePrefs
import io.github.trevarj.motd.avatar.AvatarPrefsImpl
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ConnectionTransport
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.prefs.AppearancePrefsImpl
import io.github.trevarj.motd.data.prefs.BouncerKindPrefsImpl
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefsImpl
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.FontChoice
import io.github.trevarj.motd.data.prefs.LauncherIcon
import io.github.trevarj.motd.data.prefs.MessageSpacing
import io.github.trevarj.motd.data.prefs.ReplyPrefsImpl
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.repo.ChatFolderRepository
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.gesture.GestureMenuConfig
import io.github.trevarj.motd.gesture.GestureNode
import io.github.trevarj.motd.gesture.GesturePrefsImpl
import io.github.trevarj.motd.gesture.removeNode
import io.github.trevarj.motd.gesture.updateNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigurationBackupRepositoryTest {
    @Test
    fun credentialsExcludedExportOmitsSecretsAndImportsAsPendingCredentials() =
        runTest {
            val sourceDb = inMemoryDb()
            val source = repository(sourceDb)
            sourceDb.networkDao().insert(secretNetwork(clientCertAlias = "device-cert"))

            val raw =
                source.exportToString(
                    mode = BackupExportMode.CREDENTIALS_EXCLUDED,
                    nowEpochMillis = 1_000L,
                )

            assertFalse(raw.contains("sasl-secret"))
            assertFalse(raw.contains("server-secret"))
            assertFalse(raw.contains("nickserv-secret"))
            assertFalse(raw.contains("vless://secret"))

            val targetDb = inMemoryDb()
            val target = repository(targetDb)
            val preview = target.preview(raw, importMode = BackupImportMode.MERGE)
            assertEquals(1, preview.addedNetworks)
            assertEquals(1, preview.missingCredentialNetworks)

            target.import(raw, importMode = BackupImportMode.MERGE)

            val imported = targetDb.networkDao().allNow().single()
            assertNull(imported.saslPassword)
            assertNull(imported.serverPassword)
            assertNull(imported.nickServPassword)
            assertEquals("PASSWORD_NICK", imported.nickServIdentifySyntax)
            assertTrue(imported.nickServRecoveryEnabled)
            assertEquals("GHOST,REGAIN", imported.nickServRecoverySequence)
            assertNull(imported.obfsLink)
            assertEquals(
                "saslPassword,serverPassword,nickServPassword,obfsLink,clientCertificate",
                imported.pendingCredentialRequirements,
            )
            assertFalse(imported.autoConnect)
            assertEquals(true, imported.restoreAutoConnect)
        }

    @Test
    fun sidecarBackupKeepsProviderIdentityButRequiresLocalAccountPairing() =
        runTest {
            val sourceDb = inMemoryDb()
            sourceDb.networkDao().insert(
                NetworkEntity(
                    name = "XMPP",
                    role = NetworkRole.DIRECT,
                    host = "sidecar",
                    port = 0,
                    tls = false,
                    nick = "motd",
                    username = "motd",
                    realname = "motd",
                    connectionTransport = ConnectionTransport.SIDECAR,
                    sidecarPackage = "provider.example",
                    sidecarService = "provider.example.Service",
                    sidecarAccountId = "device-local-account",
                ),
            )
            val raw =
                repository(sourceDb)
                    .exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)

            assertTrue(raw.contains("provider.example"))
            assertFalse(raw.contains("device-local-account"))

            val targetDb = inMemoryDb()
            val target = repository(targetDb)
            assertEquals(
                1,
                target.preview(raw, importMode = BackupImportMode.MERGE).missingCredentialNetworks,
            )
            target.import(raw, importMode = BackupImportMode.MERGE)

            val imported = targetDb.networkDao().allNow().single()
            assertEquals(ConnectionTransport.SIDECAR, imported.connectionTransport)
            assertEquals("provider.example", imported.sidecarPackage)
            assertNull(imported.sidecarAccountId)
            assertEquals("sidecarAccount", imported.pendingCredentialRequirements)
            assertFalse(imported.autoConnect)
        }

    @Test
    fun encryptedExportRoundTripsCredentials() =
        runTest {
            val sourceDb = inMemoryDb()
            val source = repository(sourceDb)
            sourceDb.networkDao().insert(secretNetwork(clientCertAlias = null))

            val raw =
                source.exportToString(
                    mode = BackupExportMode.ENCRYPTED_WITH_CREDENTIALS,
                    password = "correct horse battery",
                    nowEpochMillis = 1_000L,
                )

            assertFalse(raw.contains("sasl-secret"))
            assertFalse(raw.contains("server-secret"))
            assertFalse(raw.contains("nickserv-secret"))
            assertFalse(raw.contains("vless://secret"))

            val targetDb = inMemoryDb()
            val target = repository(targetDb)
            val preview =
                target.preview(
                    raw,
                    password = "correct horse battery",
                    importMode = BackupImportMode.MERGE,
                )
            assertEquals(true, preview.containsSecrets)
            assertEquals(0, preview.missingCredentialNetworks)

            target.import(raw, password = "correct horse battery", importMode = BackupImportMode.MERGE)

            val imported = targetDb.networkDao().allNow().single()
            assertEquals("sasl-secret", imported.saslPassword)
            assertEquals("server-secret", imported.serverPassword)
            assertEquals("nickserv-secret", imported.nickServPassword)
            assertEquals("PASSWORD_NICK", imported.nickServIdentifySyntax)
            assertTrue(imported.nickServRecoveryEnabled)
            assertEquals("GHOST,REGAIN", imported.nickServRecoverySequence)
            assertEquals("vless://secret", imported.obfsLink)
            assertNull(imported.pendingCredentialRequirements)
            assertEquals(true, imported.autoConnect)
        }

    @Test
    fun wrongPasswordRejectsEncryptedImportWithoutMutation() =
        runTest {
            val sourceDb = inMemoryDb()
            val source = repository(sourceDb)
            sourceDb.networkDao().insert(secretNetwork(clientCertAlias = null))
            val raw =
                source.exportToString(
                    mode = BackupExportMode.ENCRYPTED_WITH_CREDENTIALS,
                    password = "correct horse battery",
                    nowEpochMillis = 1_000L,
                )

            val targetDb = inMemoryDb()
            val target = repository(targetDb)

            try {
                target.import(raw, password = "wrong horse battery", importMode = BackupImportMode.MERGE)
                fail("wrong password must reject encrypted import")
            } catch (_: Exception) {
                // Expected: GCM authentication fails before any import mutation.
            }
            assertEquals(emptyList<NetworkEntity>(), targetDb.networkDao().allNow())
        }

    /**
     * The gesture lab's on/off flag never travels, but an authored menu does — and only once it
     * differs from the shipped tree, so a backup from an untouched install cannot pin a stale menu
     * onto the device it is restored to.
     */
    @Test
    fun gestureMenuTravelsOnlyWhenItDiffersFromTheDefault() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val gesturePrefs = GesturePrefsImpl(context)
            val sourceDb = inMemoryDb()
            val source = repository(sourceDb)

            gesturePrefs.setMenu(GestureMenuConfig())
            val untouched = source.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
            assertFalse(
                source.preview(untouched, importMode = BackupImportMode.MERGE).settingGroups.contains("gesture menu"),
            )

            val edited =
                GestureMenuConfig()
                    .updateNode("default-away") { (it as GestureNode.Leaf).copy(label = "Step out") }
                    .removeNode("default-networks")
            gesturePrefs.setMenu(edited)
            val raw = source.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)

            assertTrue(raw.contains("Step out"))
            val target = repository(inMemoryDb())
            assertTrue(target.preview(raw, importMode = BackupImportMode.MERGE).settingGroups.contains("gesture menu"))

            gesturePrefs.setMenu(GestureMenuConfig())
            target.import(raw, importMode = BackupImportMode.MERGE)

            assertEquals(edited, gesturePrefs.menu.first())
            gesturePrefs.setMenu(GestureMenuConfig())
        }

    /** Stage-1 appearance fields (font, timestamps, spacing, bubbles, launcher icon) travel with a backup. */
    @Test
    fun newAppearanceFieldsRoundTripThroughExportAndImport() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val appearancePrefs = AppearancePrefsImpl(context)
            val source = repository(inMemoryDb())

            appearancePrefs.setFontChoice(FontChoice.JETBRAINS_MONO)
            appearancePrefs.setShowTimestamps(false)
            appearancePrefs.setTimeFormat(TimeFormat.CUSTOM)
            appearancePrefs.setCustomTimeFormatPattern("yyyy-MM-dd HH:mm:ss")
            appearancePrefs.setMessageSpacing(MessageSpacing.RELAXED)
            appearancePrefs.setBubbleCornerStyle(BubbleCornerStyle.SQUARE)
            appearancePrefs.setLauncherIcon(LauncherIcon.GRUVBOX)
            // Only the display name travels; the font binary itself is not part of the backup payload.
            appearancePrefs.setCustomFontName("Iosevka Term.ttf")

            val raw = source.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)

            appearancePrefs.setFontChoice(FontChoice.SYSTEM)
            appearancePrefs.setShowTimestamps(true)
            appearancePrefs.setTimeFormat(TimeFormat.AUTO)
            appearancePrefs.setCustomTimeFormatPattern("HH:mm")
            appearancePrefs.setMessageSpacing(MessageSpacing.DEFAULT)
            appearancePrefs.setBubbleCornerStyle(BubbleCornerStyle.ROUNDED)
            appearancePrefs.setLauncherIcon(LauncherIcon.DEFAULT)
            appearancePrefs.setCustomFontName("")

            source.import(raw, importMode = BackupImportMode.MERGE)

            val config = appearancePrefs.config.first()
            assertEquals(FontChoice.JETBRAINS_MONO, config.fontChoice)
            assertEquals(false, config.showTimestamps)
            assertEquals(TimeFormat.CUSTOM, config.timeFormat)
            assertEquals("yyyy-MM-dd HH:mm:ss", config.customTimeFormatPattern)
            assertEquals(MessageSpacing.RELAXED, config.messageSpacing)
            assertEquals(BubbleCornerStyle.SQUARE, config.bubbleCornerStyle)
            assertEquals(LauncherIcon.GRUVBOX, config.launcherIcon)
            assertEquals("Iosevka Term.ttf", config.customFontName)
        }

    @Test
    fun oldSettingsBackupDefaultsComposerFormattingToolsOn() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val settings = DataStoreSettingsRepository(context)
            val backup = repository(inMemoryDb())

            settings.setShowComposerFormattingTools(false)
            val raw = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
            val oldRaw = raw.replace(Regex(""""showComposerFormattingTools"\s*:\s*false\s*,"""), "")
            assertFalse(oldRaw.contains("showComposerFormattingTools"))

            settings.setShowComposerFormattingTools(true)
            backup.import(oldRaw, importMode = BackupImportMode.MERGE)
            assertTrue(settings.settings.first().showComposerFormattingTools)
        }

    @Test
    fun uploadCredentialsOnlyRoundTripInEncryptedCredentialBackups() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = AttachmentPrefsImpl(context)
            val backup = repository(inMemoryDb())
            val configured =
                PasteBackendConfig(
                    backend = AttachmentBackend.CUSTOM_0X0,
                    endpoint = "https://share.example/upload",
                    customEndpoint = "https://share.example/upload",
                    username = "camera-user",
                    password = "camera-secret",
                )
            prefs.setConfig(configured)

            val plain = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
            prefs.setConfig(
                configured.copy(
                    backend = AttachmentBackend.CRAFTERBIN,
                    endpoint = AttachmentBackend.CRAFTERBIN.endpoint!!,
                    username = "local-user",
                    password = "local-secret",
                ),
            )
            backup.import(plain, importMode = BackupImportMode.MERGE)
            assertEquals("local-user", prefs.config.first().username)
            assertEquals("local-secret", prefs.config.first().password)

            prefs.setConfig(
                configured.copy(
                    endpoint = "https://other.example/upload",
                    customEndpoint = "https://other.example/upload",
                    username = "other-user",
                    password = "other-secret",
                ),
            )
            backup.import(plain, importMode = BackupImportMode.MERGE)
            assertEquals("", prefs.config.first().username)
            assertEquals("", prefs.config.first().password)

            prefs.setConfig(configured)
            val encrypted =
                backup.exportToString(
                    mode = BackupExportMode.ENCRYPTED_WITH_CREDENTIALS,
                    password = "backup-password",
                    nowEpochMillis = 2_000L,
                )
            prefs.setConfig(PasteBackendConfig())
            backup.import(encrypted, password = "backup-password", importMode = BackupImportMode.MERGE)
            assertEquals("camera-user", prefs.config.first().username)
            assertEquals("camera-secret", prefs.config.first().password)
        }

    @Test
    fun composerFormattingToolsRoundTripThroughSettingsBackup() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val settings = DataStoreSettingsRepository(context)
            val backup = repository(inMemoryDb())

            settings.setShowComposerEmoji(false)
            settings.setShowComposerFormattingTools(false)
            settings.setShowRedactedMessages(false)
            val raw = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)

            settings.setShowComposerEmoji(true)
            settings.setShowComposerFormattingTools(true)
            settings.setShowRedactedMessages(true)
            backup.import(raw, importMode = BackupImportMode.MERGE)

            val restored = settings.settings.first()
            assertFalse(restored.showComposerEmoji)
            assertFalse(restored.showComposerFormattingTools)
            assertFalse(restored.showRedactedMessages)

            settings.setShowComposerEmoji(true)
            settings.setShowComposerFormattingTools(true)
            settings.setShowRedactedMessages(true)
        }

    @Test
    fun malformedFolderIconIsRejectedAtPreviewBoundary() =
        runTest {
            val db = inMemoryDb()
            ChatFolderRepository(db).create("Dev")
            val raw = repository(db).exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
            val malformed = raw.replace("\"iconKey\": \"folder\"", "\"iconKey\": \"${"x".repeat(129)}\"")

            assertTrue(runCatching { repository(inMemoryDb()).preview(malformed, importMode = BackupImportMode.MERGE) }.isFailure)
        }

    @Test
    fun oldV1PayloadWithoutFolderFieldsStillImports() =
        runTest {
            val sourceDb = inMemoryDb()
            sourceDb.networkDao().insert(secretNetwork(clientCertAlias = null))
            val raw =
                repository(sourceDb)
                    .exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                    .lineSequence()
                    .filterNot { line ->
                        line.contains("\"folders\":") ||
                            line.contains("\"folderAssignments\":") ||
                            line.contains("\"ignoredAutoGroupPatterns\":")
                    }.joinToString("\n")
                    .replace("\n        },\n    },\n    \"encryptedPayload\"", "\n        }\n    },\n    \"encryptedPayload\"")

            val target = repository(inMemoryDb())
            val preview = target.preview(raw, importMode = BackupImportMode.MERGE)
            assertEquals(0, preview.folderCount)
            assertEquals(0, preview.folderAssignmentCount)
        }

    @Test
    fun foldersRoundTripAndDeferredChannelAssignmentClaimsOnCreation() =
        runTest {
            val sourceDb = inMemoryDb()
            val networkId = sourceDb.networkDao().insert(secretNetwork(clientCertAlias = null))
            val room = BufferStore(sourceDb).getOrCreate(networkId, "#kotlin", "#Kotlin", BufferType.CHANNEL)
            val sourceFolders = ChatFolderRepository(sourceDb)
            val folderId = sourceFolders.create("Development")
            sourceFolders.assign(listOf(room.id), folderId)
            sourceFolders.rejectAutoGroup(networkId, "old.prefix")
            val raw = repository(sourceDb).exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)

            val targetDb = inMemoryDb()
            val target = repository(targetDb)
            val preview = target.preview(raw, importMode = BackupImportMode.REPLACE)
            assertEquals(1, preview.folderCount)
            assertEquals(1, preview.folderAssignmentCount)
            target.import(raw, importMode = BackupImportMode.REPLACE)

            val restoredFolders = ChatFolderRepository(targetDb)
            val restoredFolder = restoredFolders.folders().single()
            val restoredNetwork = targetDb.networkDao().allNow().single()
            val restoredRoom = BufferStore(targetDb).getOrCreate(restoredNetwork.id, "#kotlin", "#Kotlin", BufferType.CHANNEL)
            assertEquals(restoredFolder.id, targetDb.bufferDao().observeById(restoredRoom.id)?.folderId)
            assertEquals(
                "old.prefix",
                restoredFolders
                    .backupSnapshot()
                    .ignored
                    .single()
                    .normalizedPrefix,
            )
        }

    private fun repository(db: io.github.trevarj.motd.data.db.MotdDatabase): ConfigurationBackupRepositoryImpl {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = DataStoreSettingsRepository(context)
        return ConfigurationBackupRepositoryImpl(
            db = db,
            settingsRepository = settings,
            appearancePrefs = AppearancePrefsImpl(context),
            contentPreviewPrefs = ContentPreviewPrefsImpl(context),
            replyPrefs = ReplyPrefsImpl(context),
            attachmentPrefs = AttachmentPrefsImpl(context),
            voicePrefs = VoicePrefs(context),
            avatarPrefs = AvatarPrefsImpl(context),
            bouncerKindPrefs = BouncerKindPrefsImpl(context),
            gesturePrefs = GesturePrefsImpl(context),
        )
    }

    private fun secretNetwork(clientCertAlias: String?): NetworkEntity =
        NetworkEntity(
            name = "libera",
            role = NetworkRole.DIRECT,
            host = "irc.libera.chat",
            port = 6697,
            tls = true,
            nick = "me",
            username = "me",
            realname = "Me",
            saslMechanism = "PLAIN",
            saslUser = "me",
            saslPassword = "sasl-secret",
            serverPassword = "server-secret",
            nickServPassword = "nickserv-secret",
            nickServIdentifySyntax = "PASSWORD_NICK",
            nickServRecoveryEnabled = true,
            nickServRecoverySequence = "GHOST,REGAIN",
            clientCertAlias = clientCertAlias,
            autoConnect = true,
            obfsMode = ObfsMode.EMBEDDED_REALITY,
            obfsLink = "vless://secret",
        )
}
