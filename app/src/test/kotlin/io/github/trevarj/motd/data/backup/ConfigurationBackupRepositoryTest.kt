package io.github.trevarj.motd.data.backup

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.AttachmentPrefsImpl
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.audio.VoicePrefs
import io.github.trevarj.motd.avatar.AvatarPrefsImpl
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.prefs.AppearancePrefsImpl
import io.github.trevarj.motd.data.prefs.AvatarShape
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.BouncerKindPrefsImpl
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.ChatListSwipeAction
import io.github.trevarj.motd.data.prefs.ChatSoundConfig
import io.github.trevarj.motd.data.prefs.ChatSoundMelody
import io.github.trevarj.motd.data.prefs.ChatSoundPrefs
import io.github.trevarj.motd.data.prefs.ChatSoundTone
import io.github.trevarj.motd.data.prefs.ChatSoundVoice
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.ComfortableBubbleColor
import io.github.trevarj.motd.data.prefs.ComfortableBubbleColorRole
import io.github.trevarj.motd.data.prefs.CompactNickBrackets
import io.github.trevarj.motd.data.prefs.ComposerStyle
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefsImpl
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.FolderDisplayMode
import io.github.trevarj.motd.data.prefs.FontChoice
import io.github.trevarj.motd.data.prefs.FoolPreferences
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.HistorySyncMode
import io.github.trevarj.motd.data.prefs.InstalledPalette
import io.github.trevarj.motd.data.prefs.LauncherIcon
import io.github.trevarj.motd.data.prefs.MAX_INSTALLED_PALETTE_BYTES
import io.github.trevarj.motd.data.prefs.MentionsPlacement
import io.github.trevarj.motd.data.prefs.MessageSpacing
import io.github.trevarj.motd.data.prefs.NickMentionStyle
import io.github.trevarj.motd.data.prefs.PaletteChoice
import io.github.trevarj.motd.data.prefs.PaletteColors
import io.github.trevarj.motd.data.prefs.PaletteDocument
import io.github.trevarj.motd.data.prefs.PaletteMode
import io.github.trevarj.motd.data.prefs.PaletteReadError
import io.github.trevarj.motd.data.prefs.PaletteSelection
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.ReplyPrefsImpl
import io.github.trevarj.motd.data.prefs.SplashScreenAnimation
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.prefs.UiCornerStyle
import io.github.trevarj.motd.data.prefs.UiIconSet
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.data.prefs.appearanceDataStore
import io.github.trevarj.motd.data.prefs.canonicalPalette
import io.github.trevarj.motd.data.prefs.paletteSelection
import io.github.trevarj.motd.data.prefs.settingsDataStore
import io.github.trevarj.motd.data.prefs.supportsMode
import io.github.trevarj.motd.data.prefs.validateInstalledPalettes
import io.github.trevarj.motd.data.repo.ChatFolderRepository
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.gesture.GestureMenuConfig
import io.github.trevarj.motd.gesture.GestureNode
import io.github.trevarj.motd.gesture.GesturePrefsImpl
import io.github.trevarj.motd.gesture.removeNode
import io.github.trevarj.motd.gesture.updateNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
    fun channelPrefixesRoundTripAndOldSettingsDefaultToFalse() =
        runTest {
            val settings = DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())
            val original = settings.settings.first().showChannelPrefixes
            val db = inMemoryDb()
            try {
                val backup = repository(db)
                for (selected in listOf(true, false)) {
                    settings.setShowChannelPrefixes(selected)
                    val raw = backup.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                    assertTrue(raw.contains("\"showChannelPrefixes\": $selected"))

                    settings.setShowChannelPrefixes(!selected)
                    backup.import(raw, importMode = BackupImportMode.MERGE)
                    assertEquals(selected, settings.settings.first().showChannelPrefixes)

                    val legacy = raw.replace(Regex(""",?\s*"showChannelPrefixes"\s*:\s*$selected"""), "")
                    assertFalse(legacy.contains("showChannelPrefixes"))
                    settings.setShowChannelPrefixes(true)
                    backup.import(legacy, importMode = BackupImportMode.MERGE)
                    assertFalse(settings.settings.first().showChannelPrefixes)
                }
            } finally {
                settings.setShowChannelPrefixes(original)
                db.close()
            }
        }

    @Test
    fun nickMentionStylesRoundTripAndOldSettingsDefaultToBold() =
        runTest {
            val settings = DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())
            val original = settings.settings.first().nickMentionStyle
            val db = inMemoryDb()
            try {
                val backup = repository(db)
                for (selected in listOf(NickMentionStyle.NONE, NickMentionStyle.CHIP)) {
                    settings.setNickMentionStyle(selected)
                    val raw = backup.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)

                    settings.setNickMentionStyle(NickMentionStyle.BOLD)
                    backup.import(raw, importMode = BackupImportMode.MERGE)
                    assertEquals(selected, settings.settings.first().nickMentionStyle)

                    val legacy = raw.replace(Regex(""",?\s*"nickMentionStyle"\s*:\s*"${selected.name}""""), "")
                    backup.import(legacy, importMode = BackupImportMode.MERGE)
                    assertEquals(NickMentionStyle.BOLD, settings.settings.first().nickMentionStyle)
                }
            } finally {
                settings.setNickMentionStyle(original)
                db.close()
            }
        }

    @Test
    fun compactNickBracketsRoundTripAndOldSettingsDefaultToAngle() =
        runTest {
            val settings = DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())
            val original = settings.settings.first().compactNickBrackets
            val db = inMemoryDb()
            try {
                val backup = repository(db)
                for (selected in listOf(CompactNickBrackets.SQUARE, CompactNickBrackets.ROUND)) {
                    settings.setCompactNickBrackets(selected)
                    val raw = backup.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                    assertTrue(raw.contains("\"compactNickBrackets\": \"${selected.name}\""))

                    settings.setCompactNickBrackets(CompactNickBrackets.ANGLE)
                    backup.import(raw, importMode = BackupImportMode.MERGE)
                    assertEquals(selected, settings.settings.first().compactNickBrackets)

                    val legacy = raw.replace(Regex(""",?\s*"compactNickBrackets"\s*:\s*"${selected.name}""""), "")
                    assertFalse(legacy.contains("compactNickBrackets"))
                    backup.import(legacy, importMode = BackupImportMode.MERGE)
                    assertEquals(CompactNickBrackets.ANGLE, settings.settings.first().compactNickBrackets)
                }
            } finally {
                settings.setCompactNickBrackets(original)
                db.close()
            }
        }

    @Test
    fun customPresenceSelectionsRoundTripIncludingEmpty() =
        runTest {
            val settings = DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())
            val original = settings.settings.first()
            val db = inMemoryDb()
            try {
                val backup = repository(db)
                for (selected in listOf(setOf(MessageKind.JOIN, MessageKind.NETJOIN), emptySet())) {
                    settings.setPresenceMode(PresenceMode.CUSTOM)
                    settings.setCustomPresenceKinds(selected)
                    val raw = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                    settings.setPresenceMode(PresenceMode.HIDDEN)
                    settings.setCustomPresenceKinds(setOf(MessageKind.QUIT))
                    backup.import(raw, importMode = BackupImportMode.MERGE)
                    val restored = settings.settings.first()
                    assertEquals(PresenceMode.CUSTOM, restored.presenceMode)
                    assertEquals(selected, restored.customPresenceKinds)
                }
            } finally {
                settings.setPresenceMode(original.presenceMode)
                settings.setCustomPresenceKinds(original.customPresenceKinds)
                db.close()
            }
        }

    @Test
    fun mixedFoolPreferencesRoundTripAndLegacyImportClearsDestinationOptIns() =
        runTest {
            val settings = DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())
            val db = inMemoryDb()
            try {
                val backup = repository(db)
                val selected =
                    mapOf(
                        "b" to FoolPreferences(FoolsMode.BLUR, notifyMentions = true),
                        "c" to FoolPreferences(FoolsMode.HIDE, notifyDirectMessages = true),
                    )
                for (mode in listOf(FoolsMode.BLUR, FoolsMode.COLLAPSE, FoolsMode.HIDE)) {
                    for (nick in listOf("a", "b", "c")) settings.setFool(nick, true)
                    settings.setFoolsMode(mode)
                    settings.setFoolPreferences("a", FoolPreferences())
                    selected.forEach { (nick, preferences) -> settings.setFoolPreferences(nick, preferences) }
                    val raw = backup.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                    settings.setFoolPreferences("a", FoolPreferences(notifyMentions = true, notifyDirectMessages = true))
                    settings.setFoolPreferences("b", FoolPreferences(FoolsMode.DIMMED, notifyDirectMessages = true))
                    settings.setFoolsMode(FoolsMode.DIMMED)
                    backup.import(raw, importMode = BackupImportMode.MERGE)
                    assertEquals(selected, settings.settings.first().foolPreferences)
                    assertEquals(mode, settings.settings.first().foolsMode)

                    val envelope = Json.parseToJsonElement(raw).jsonObject
                    val payload = envelope.getValue("payload").jsonObject
                    val portable = payload.getValue("settings").jsonObject
                    val general = portable.getValue("general").jsonObject
                    val legacy =
                        JsonObject(
                            envelope + (
                                "payload" to
                                    JsonObject(
                                        payload + (
                                            "settings" to
                                                JsonObject(
                                                    portable + ("general" to JsonObject(general - "foolPreferences")),
                                                )
                                        ),
                                    )
                            ),
                        ).toString()
                    assertFalse(legacy.contains("foolPreferences"))
                    settings.setFoolPreferences("b", FoolPreferences(notifyMentions = true, notifyDirectMessages = true))
                    backup.import(legacy, importMode = BackupImportMode.MERGE)
                    assertEquals(setOf("a", "b", "c"), settings.settings.first().fools)
                    assertTrue(
                        settings.settings
                            .first()
                            .foolPreferences
                            .isEmpty(),
                    )
                    assertEquals(mode, settings.settings.first().foolsMode)
                    val policy =
                        io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy(
                            io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
                                .from(settings.settings.first()),
                        )
                    assertFalse(policy.allowsNotification(null, "b", directMessage = false, hasMention = true))
                    assertFalse(policy.allowsNotification(null, "b", directMessage = true, hasMention = true))
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun plaintextBackupCannotReplaceTrustedFileHostAndMalformedImportFailsClosed() =
        runTest {
            val sourceDb = inMemoryDb()
            val source = repository(sourceDb)
            sourceDb.networkDao().insert(secretNetwork(clientCertAlias = null).copy(trustedFileHost = "files.example"))
            val raw = source.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
            assertFalse(raw.contains("files.example"))
            assertTrue(raw.contains("\"trustedFileHost\": null"))

            val targetDb = inMemoryDb()
            val target = repository(targetDb)
            targetDb.networkDao().insert(secretNetwork(clientCertAlias = null).copy(saslPassword = "retained-secret", trustedFileHost = "local.example"))
            val malicious = raw.replace("\"trustedFileHost\": null", "\"trustedFileHost\": \"attacker.example\"")
            target.import(malicious, importMode = BackupImportMode.MERGE)
            assertEquals(
                "local.example",
                targetDb
                    .networkDao()
                    .allNow()
                    .single()
                    .trustedFileHost,
            )
            assertEquals(
                "retained-secret",
                targetDb
                    .networkDao()
                    .allNow()
                    .single()
                    .saslPassword,
            )

            try {
                target.preview(
                    raw.replace("\"trustedFileHost\": null", "\"trustedFileHost\": \"https://evil.example\""),
                    importMode = BackupImportMode.MERGE,
                )
                fail("malformed trusted host was accepted")
            } catch (_: BackupFormatException) {
                // Expected: imports never turn a URL into a credential authority.
            } finally {
                sourceDb.close()
                targetDb.close()
            }
        }

    @Test
    fun chatSoundConfigRoundTripsAndLegacyBackupLeavesExistingConfigUntouched() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = ChatSoundPrefs(context)
            val selected =
                ChatSoundConfig(
                    masterVolume = 42,
                    receiveMelody = ChatSoundMelody.VICTORY,
                    send = ChatSoundConfig().send.copy(voice = ChatSoundVoice.TERMINAL_TICK, tone = ChatSoundTone.WARM, pitch = -2),
                )
            prefs.replace(selected)
            val db = inMemoryDb()
            val source = repository(db)
            val raw = source.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
            prefs.replace(ChatSoundConfig(masterVolume = 99))
            source.import(raw, importMode = BackupImportMode.MERGE)
            assertEquals(selected, prefs.config.first())

            val legacy = raw.replace(Regex(",?\\s*\"chatSounds\"\\s*:\\s*\\{.*?\\}(?=,\\s*\"replies\")", RegexOption.DOT_MATCHES_ALL), "")
            prefs.replace(ChatSoundConfig(masterVolume = 23, receiveMelody = ChatSoundMelody.BEACON))
            source.import(legacy, importMode = BackupImportMode.MERGE)
            assertEquals(23, prefs.config.first().masterVolume)
            assertEquals(ChatSoundMelody.BEACON, prefs.config.first().receiveMelody)
            db.close()
        }

    @Test
    fun swipeReplyPreferenceRoundTripsAndOldReplyConfigDefaultsToEnabled() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = ReplyPrefsImpl(context)
            val db = inMemoryDb()
            try {
                val backup = repository(db)
                prefs.setVisibleChannelPrefix(true)
                prefs.setSwipeToReplyEnabled(false)
                val raw = backup.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                prefs.setVisibleChannelPrefix(false)
                prefs.setSwipeToReplyEnabled(true)
                backup.import(raw, importMode = BackupImportMode.MERGE)
                assertEquals(ReplyConfig(visibleChannelPrefix = true, swipeToReplyEnabled = false), prefs.config.first())

                val legacy = raw.replace(Regex(""",\s*"swipeToReplyEnabled"\s*:\s*false"""), "")
                assertFalse(legacy.contains("swipeToReplyEnabled"))
                backup.import(legacy, importMode = BackupImportMode.MERGE)
                assertEquals(ReplyConfig(visibleChannelPrefix = true, swipeToReplyEnabled = true), prefs.config.first())
            } finally {
                prefs.setVisibleChannelPrefix(false)
                prefs.setSwipeToReplyEnabled(true)
                db.close()
            }
        }

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
    fun plaintextBackupOmitsCommandsAndCannotReplaceLocalCommands() =
        runTest {
            val sourceDb = inMemoryDb()
            val source = repository(sourceDb)
            sourceDb.networkDao().insert(
                secretNetwork(clientCertAlias = null).copy(onConnectCommands = "/oper me script-secret"),
            )
            val raw = source.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
            assertTrue(raw.contains("\"onConnectCommands\": null"))
            assertFalse(raw.contains("script-secret"))

            val tampered = raw.replace("\"onConnectCommands\": null", "\"onConnectCommands\": \"/oper me attacker-secret\"")
            val targetDb = inMemoryDb()
            targetDb.networkDao().insert(secretNetwork(clientCertAlias = null).copy(onConnectCommands = "/mode +i"))
            repository(targetDb).import(tampered, importMode = BackupImportMode.MERGE)
            assertEquals(
                "/mode +i",
                targetDb
                    .networkDao()
                    .allNow()
                    .single()
                    .onConnectCommands,
            )

            val newDb = inMemoryDb()
            repository(newDb).import(tampered, importMode = BackupImportMode.MERGE)
            assertEquals(
                "",
                newDb
                    .networkDao()
                    .allNow()
                    .single()
                    .onConnectCommands,
            )
            sourceDb.close()
            targetDb.close()
            newDb.close()
        }

    @Test
    fun legacyBackupWithoutCommandsPreservesLocalAndDefaultsNewNetworkToEmpty() =
        runTest {
            val sourceDb = inMemoryDb()
            sourceDb.networkDao().insert(secretNetwork(clientCertAlias = null))
            val legacy =
                repository(sourceDb)
                    .exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                    .lineSequence()
                    .filterNot { it.contains("\"onConnectCommands\":") }
                    .joinToString("\n")
            assertFalse(legacy.contains("\"onConnectCommands\""))

            val targetDb = inMemoryDb()
            targetDb.networkDao().insert(secretNetwork(clientCertAlias = null).copy(onConnectCommands = "/mode +i"))
            repository(targetDb).import(legacy, importMode = BackupImportMode.MERGE)
            assertEquals(
                "/mode +i",
                targetDb
                    .networkDao()
                    .allNow()
                    .single()
                    .onConnectCommands,
            )

            val newDb = inMemoryDb()
            repository(newDb).import(legacy, importMode = BackupImportMode.MERGE)
            assertEquals(
                "",
                newDb
                    .networkDao()
                    .allNow()
                    .single()
                    .onConnectCommands,
            )
            sourceDb.close()
            targetDb.close()
            newDb.close()
        }

    @Test
    fun encryptedExportRoundTripsCredentials() =
        runTest {
            val sourceDb = inMemoryDb()
            val source = repository(sourceDb)
            val commands = "/mode +i\n/oper me script-secret\n/msg Gatekeeper script-secret"
            sourceDb.networkDao().insert(
                secretNetwork(clientCertAlias = null).copy(trustedFileHost = "files.example", onConnectCommands = commands),
            )

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
            assertFalse(raw.contains("script-secret"))

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
            assertEquals("files.example", imported.trustedFileHost)
            assertEquals(commands, imported.onConnectCommands)
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

    /** Appearance fields travel with a backup, including the independent IRC input style. */
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
            appearancePrefs.setComposerStyle(ComposerStyle.COMPACT)
            appearancePrefs.setCollapseTitleBar(false)
            appearancePrefs.setBubbleCornerStyle(BubbleCornerStyle.SQUARE)
            appearancePrefs.setChatShadowsEnabled(false)
            appearancePrefs.setNicknameBackgroundsEnabled(false)
            appearancePrefs.setMessageBackgroundOpacity(37)
            appearancePrefs.setMessageBackgroundBlur(63)
            val effects =
                io.github.trevarj.motd.data.prefs.SurfaceEffects(
                    enabled = false,
                    surfaces =
                        io.github.trevarj.motd.data.prefs.EffectSurface.entries
                            .associateWith {
                                io.github.trevarj.motd.data.prefs
                                    .SurfaceEffect(it.ordinal * 10, 100 - it.ordinal * 10)
                            },
                )
            appearancePrefs.restoreGlobalEffects(appearancePrefs.config.first().copy(surfaceEffects = effects))
            appearancePrefs.setLinkGlobalOpacity(true)
            appearancePrefs.setLinkGlobalBlur(true)
            appearancePrefs.setSentBubbleColor(ComfortableBubbleColor(ComfortableBubbleColorRole.PRIMARY))
            appearancePrefs.setReceivedBubbleColor(ComfortableBubbleColor(ComfortableBubbleColorRole.CUSTOM, 0xff12ab34.toInt()))
            appearancePrefs.setUseThemeTextColors(true)
            appearancePrefs.setLauncherIcon(LauncherIcon.GRUVBOX)
            appearancePrefs.setSplashScreenAnimation(SplashScreenAnimation.SOFT_WASH)
            appearancePrefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.RETRO_CHAT, 73, "12345678-1234-1234-1234-123456789abc.image", 0xff12ab34.toInt()))
            // Only the display name travels; the font binary itself is not part of the backup payload.
            appearancePrefs.setCustomFontName("Iosevka Term.ttf")

            val raw =
                source
                    .exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                    .replace("\"RETRO_CHAT\"", "\"CHATTER\"")
            assertFalse(raw.contains("12345678-1234-1234-1234-123456789abc.image"))
            assertFalse(raw.contains("localImageName"))

            appearancePrefs.setFontChoice(FontChoice.SYSTEM)
            appearancePrefs.setShowTimestamps(true)
            appearancePrefs.setTimeFormat(TimeFormat.AUTO)
            appearancePrefs.setCustomTimeFormatPattern("HH:mm")
            appearancePrefs.setMessageSpacing(MessageSpacing.DEFAULT)
            appearancePrefs.setComposerStyle(ComposerStyle.COMFORTABLE)
            appearancePrefs.setCollapseTitleBar(true)
            appearancePrefs.setBubbleCornerStyle(BubbleCornerStyle.ROUNDED)
            appearancePrefs.setChatShadowsEnabled(true)
            appearancePrefs.setNicknameBackgroundsEnabled(true)
            appearancePrefs.setMessageBackgroundOpacity(100)
            appearancePrefs.setMessageBackgroundBlur(0)
            appearancePrefs.restoreGlobalEffects(
                appearancePrefs.config.first().copy(
                    surfaceEffects =
                        io.github.trevarj.motd.data.prefs
                            .SurfaceEffects(),
                ),
            )
            appearancePrefs.setSentBubbleColor(ComfortableBubbleColor())
            appearancePrefs.setReceivedBubbleColor(ComfortableBubbleColor())
            appearancePrefs.setUseThemeTextColors(false)
            appearancePrefs.setLauncherIcon(LauncherIcon.DEFAULT)
            appearancePrefs.setSplashScreenAnimation(SplashScreenAnimation.STATIC)
            appearancePrefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.MEMES, 20))
            appearancePrefs.setCustomFontName("")

            source.import(raw, importMode = BackupImportMode.MERGE)

            val config = appearancePrefs.config.first()
            assertEquals(FontChoice.JETBRAINS_MONO, config.fontChoice)
            assertEquals(false, config.showTimestamps)
            assertEquals(TimeFormat.CUSTOM, config.timeFormat)
            assertEquals("yyyy-MM-dd HH:mm:ss", config.customTimeFormatPattern)
            assertEquals(MessageSpacing.RELAXED, config.messageSpacing)
            assertEquals(ComposerStyle.COMPACT, config.composerStyle)
            assertEquals(false, config.collapseTitleBar)
            assertEquals(BubbleCornerStyle.SQUARE, config.bubbleCornerStyle)
            assertEquals(false, config.chatShadowsEnabled)
            assertEquals(false, config.nicknameBackgroundsEnabled)
            assertEquals(37, config.messageBackgroundOpacityPercent)
            assertEquals(63, config.messageBackgroundBlur)
            assertEquals(effects, config.surfaceEffects)
            assertEquals(true, config.linkGlobalOpacity)
            assertEquals(true, config.linkGlobalBlur)
            appearancePrefs.setLinkGlobalOpacity(false)
            appearancePrefs.setLinkGlobalBlur(false)
            assertEquals(ComfortableBubbleColor(ComfortableBubbleColorRole.PRIMARY), config.sentBubbleColor)
            assertEquals(ComfortableBubbleColor(ComfortableBubbleColorRole.CUSTOM, 0xff12ab34.toInt()), config.receivedBubbleColor)
            assertEquals(true, config.useThemeTextColors)
            assertEquals(LauncherIcon.GRUVBOX, config.launcherIcon)
            assertEquals(SplashScreenAnimation.SOFT_WASH, config.splashScreenAnimation)
            assertEquals("Iosevka Term.ttf", config.customFontName)
            assertEquals(WallpaperSelection(ChatWallpaperPreset.RETRO_CHAT, 73, outlineColor = 0xff12ab34.toInt()), config.wallpaper)
            assertTrue(
                source
                    .exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 2_000L)
                    .contains("\"RETRO_CHAT\""),
            )
        }

    @Test
    fun olderAppearanceBackupDefaultsComposerStyleAndTextColors() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val appearancePrefs = AppearancePrefsImpl(context)
            val db = inMemoryDb()
            try {
                val backup = repository(db)
                appearancePrefs.setComposerStyle(ComposerStyle.COMPACT)
                appearancePrefs.setUseThemeTextColors(true)
                appearancePrefs.setNicknameBackgroundsEnabled(false)
                appearancePrefs.setMessageBackgroundOpacity(37)
                appearancePrefs.setMessageBackgroundBlur(63)
                appearancePrefs.setSplashScreenAnimation(SplashScreenAnimation.TRAVELING_PULSE)
                appearancePrefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.DEEP_SPACE, 80, outlineColor = 0xffffffff.toInt()))
                val raw = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                val oldRaw =
                    raw
                        .replace(Regex(""",\s*"composerStyle"\s*:\s*"COMPACT""""), "")
                        .replace(Regex(""",\s*"useThemeTextColors"\s*:\s*true"""), "")
                        .replace(Regex(""",\s*"nicknameBackgroundsEnabled"\s*:\s*false"""), "")
                        .replace(Regex(""",\s*"messageBackgroundOpacityPercent"\s*:\s*37"""), "")
                        .replace(Regex(""",\s*"messageBackgroundBlur"\s*:\s*63"""), "")
                        .replace(Regex(""",\s*"splashScreenAnimation"\s*:\s*"TRAVELING_PULSE""""), "")
                        .replace(Regex(""",\s*"outlineColor"\s*:\s*-?\d+"""), "")
                        .let { document ->
                            val envelope = Json.parseToJsonElement(document).jsonObject
                            val payload = envelope.getValue("payload").jsonObject
                            val settings = payload.getValue("settings").jsonObject
                            val appearance = settings.getValue("appearance").jsonObject
                            JsonObject(envelope + ("payload" to JsonObject(payload + ("settings" to JsonObject(settings + ("appearance" to JsonObject(appearance - "surfaceEffects" - "linkGlobalOpacity" - "linkGlobalBlur"))))))).toString()
                        }
                assertFalse(oldRaw.contains("composerStyle"))
                assertFalse(oldRaw.contains("useThemeTextColors"))
                assertFalse(oldRaw.contains("nicknameBackgroundsEnabled"))
                assertFalse(oldRaw.contains("messageBackgroundOpacityPercent"))
                assertFalse(oldRaw.contains("messageBackgroundBlur"))
                assertFalse(oldRaw.contains("surfaceEffects"))
                assertFalse(oldRaw.contains("splashScreenAnimation"))
                assertFalse(oldRaw.contains("outlineColor"))

                appearancePrefs.setLinkGlobalOpacity(true)
                appearancePrefs.setLinkGlobalBlur(true)
                appearancePrefs.setSurfaceEffectsEnabled(false)
                backup.import(oldRaw, importMode = BackupImportMode.MERGE)
                assertEquals(ComposerStyle.COMFORTABLE, appearancePrefs.config.first().composerStyle)
                assertEquals(false, appearancePrefs.config.first().useThemeTextColors)
                assertEquals(true, appearancePrefs.config.first().nicknameBackgroundsEnabled)
                assertEquals(100, AppearancePrefsImpl(context).config.first().messageBackgroundOpacityPercent)
                assertEquals(0, AppearancePrefsImpl(context).config.first().messageBackgroundBlur)
                assertEquals(false, appearancePrefs.config.first().linkGlobalOpacity)
                assertEquals(false, appearancePrefs.config.first().linkGlobalBlur)
                assertEquals(
                    io.github.trevarj.motd.data.prefs
                        .SurfaceEffects(),
                    appearancePrefs.config.first().surfaceEffects,
                )
                assertEquals(SplashScreenAnimation.EDGE_TRACE, appearancePrefs.config.first().splashScreenAnimation)
                assertEquals(WallpaperSelection(ChatWallpaperPreset.DEEP_SPACE, 80), appearancePrefs.config.first().wallpaper)
                for (percent in listOf(-12, 130)) {
                    backup.import(
                        raw.replace(Regex(""""messageBackgroundOpacityPercent"\s*:\s*37"""), """"messageBackgroundOpacityPercent":$percent"""),
                        importMode = BackupImportMode.MERGE,
                    )
                    assertEquals(percent.coerceIn(0, 100), AppearancePrefsImpl(context).config.first().messageBackgroundOpacityPercent)
                    backup.import(
                        raw.replace(Regex(""""messageBackgroundBlur"\s*:\s*63"""), """"messageBackgroundBlur":$percent"""),
                        importMode = BackupImportMode.MERGE,
                    )
                    assertEquals(percent.coerceIn(0, 100), AppearancePrefsImpl(context).config.first().messageBackgroundBlur)
                }

                val withoutAppearance =
                    raw.replace(
                        Regex(""",?\s*"appearance"\s*:\s*\{.*?\}(?=,\s*"contentPreviews")""", RegexOption.DOT_MATCHES_ALL),
                        "",
                    )
                assertFalse(withoutAppearance.contains("\"appearance\""))
                appearancePrefs.setComposerStyle(ComposerStyle.LARGE)
                appearancePrefs.setUseThemeTextColors(true)
                appearancePrefs.setNicknameBackgroundsEnabled(false)
                appearancePrefs.setMessageBackgroundOpacity(27)
                appearancePrefs.setMessageBackgroundBlur(41)
                appearancePrefs.setSplashScreenAnimation(SplashScreenAnimation.STATIC)
                val preservedEffects =
                    io.github.trevarj.motd.data.prefs
                        .SurfaceEffects(enabled = false)
                appearancePrefs.restoreGlobalEffects(appearancePrefs.config.first().copy(surfaceEffects = preservedEffects))
                appearancePrefs.setLinkGlobalOpacity(true)
                appearancePrefs.setLinkGlobalBlur(true)
                backup.import(withoutAppearance, importMode = BackupImportMode.MERGE)
                assertEquals(ComposerStyle.LARGE, appearancePrefs.config.first().composerStyle)
                assertEquals(true, appearancePrefs.config.first().useThemeTextColors)
                assertEquals(false, appearancePrefs.config.first().nicknameBackgroundsEnabled)
                assertEquals(27, AppearancePrefsImpl(context).config.first().messageBackgroundOpacityPercent)
                assertEquals(41, AppearancePrefsImpl(context).config.first().messageBackgroundBlur)
                assertEquals(preservedEffects, appearancePrefs.config.first().surfaceEffects)
                assertEquals(true, appearancePrefs.config.first().linkGlobalOpacity)
                assertEquals(true, appearancePrefs.config.first().linkGlobalBlur)
                assertEquals(SplashScreenAnimation.STATIC, appearancePrefs.config.first().splashScreenAnimation)
            } finally {
                appearancePrefs.setLinkGlobalOpacity(false)
                appearancePrefs.setLinkGlobalBlur(false)
                appearancePrefs.setComposerStyle(ComposerStyle.COMFORTABLE)
                appearancePrefs.setUseThemeTextColors(false)
                appearancePrefs.setNicknameBackgroundsEnabled(true)
                appearancePrefs.setMessageBackgroundOpacity(100)
                appearancePrefs.setMessageBackgroundBlur(0)
                appearancePrefs.restoreGlobalEffects(
                    appearancePrefs.config.first().copy(
                        surfaceEffects =
                            io.github.trevarj.motd.data.prefs
                                .SurfaceEffects(),
                    ),
                )
                appearancePrefs.setSplashScreenAnimation(SplashScreenAnimation.EDGE_TRACE)
                appearancePrefs.setWallpaper(WallpaperSelection())
                db.close()
            }
        }

    @Test
    fun surfaceShapesRoundTripAndRespectLegacyAndAbsentAppearance() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = AppearancePrefsImpl(context)
            val original = prefs.config.first()
            val db = inMemoryDb()
            try {
                val backup = repository(db)
                prefs.setInputCornerStyle(UiCornerStyle.SQUARE)
                prefs.setAvatarShape(AvatarShape.CIRCLE)
                prefs.setChatCardCornerStyle(UiCornerStyle.SOFT)
                prefs.setMediaCornerStyle(UiCornerStyle.ROUNDED)
                val raw = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                prefs.setInputCornerStyle(UiCornerStyle.ROUNDED)
                prefs.setAvatarShape(AvatarShape.SOFT)
                prefs.setChatCardCornerStyle(UiCornerStyle.SQUARE)
                prefs.setMediaCornerStyle(UiCornerStyle.SOFT)
                backup.import(raw, importMode = BackupImportMode.MERGE)
                val restored = AppearancePrefsImpl(context).config.first()
                assertEquals(UiCornerStyle.SQUARE, restored.inputCornerStyle)
                assertEquals(AvatarShape.CIRCLE, restored.avatarShape)
                assertEquals(UiCornerStyle.SOFT, restored.chatCardCornerStyle)
                assertEquals(UiCornerStyle.ROUNDED, restored.mediaCornerStyle)

                val oldRaw =
                    raw.replace(
                        Regex(""",\s*"(inputCornerStyle|avatarShape|chatCardCornerStyle|mediaCornerStyle)"\s*:\s*"[^"]*""""),
                        "",
                    )
                for (field in listOf("inputCornerStyle", "avatarShape", "chatCardCornerStyle", "mediaCornerStyle")) {
                    assertFalse(oldRaw.contains("\"$field\""))
                }
                backup.import(oldRaw, importMode = BackupImportMode.MERGE)
                val legacy = AppearancePrefsImpl(context).config.first()
                assertEquals(UiCornerStyle.DEFAULT, legacy.inputCornerStyle)
                assertEquals(AvatarShape.DEFAULT, legacy.avatarShape)
                assertEquals(UiCornerStyle.DEFAULT, legacy.chatCardCornerStyle)
                assertEquals(UiCornerStyle.DEFAULT, legacy.mediaCornerStyle)

                val withoutAppearance =
                    raw.replace(
                        Regex(""",?\s*"appearance"\s*:\s*\{.*?\}(?=,\s*"contentPreviews")""", RegexOption.DOT_MATCHES_ALL),
                        "",
                    )
                assertFalse(withoutAppearance.contains("\"appearance\""))
                prefs.setInputCornerStyle(UiCornerStyle.SOFT)
                prefs.setAvatarShape(AvatarShape.SQUARE)
                prefs.setChatCardCornerStyle(UiCornerStyle.ROUNDED)
                prefs.setMediaCornerStyle(UiCornerStyle.SQUARE)
                val local = prefs.config.first()
                backup.import(withoutAppearance, importMode = BackupImportMode.MERGE)
                assertEquals(local, AppearancePrefsImpl(context).config.first())
            } finally {
                prefs.setInputCornerStyle(original.inputCornerStyle)
                prefs.setAvatarShape(original.avatarShape)
                prefs.setChatCardCornerStyle(original.chatCardCornerStyle)
                prefs.setMediaCornerStyle(original.mediaCornerStyle)
                db.close()
            }
        }

    @Test
    fun uiIconSetRestoresExplicitLegacyAndAbsentAppearance() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = AppearancePrefsImpl(context)
            val original = prefs.config.first().uiIconSet
            val db = inMemoryDb()
            try {
                val backup = repository(db)
                prefs.setUiIconSet(UiIconSet.LUCIDE)
                val raw = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                prefs.setUiIconSet(UiIconSet.MATERIAL)
                backup.import(raw, importMode = BackupImportMode.MERGE)
                assertEquals(UiIconSet.LUCIDE, AppearancePrefsImpl(context).config.first().uiIconSet)

                val legacy = raw.replace(Regex(""",\s*"uiIconSet"\s*:\s*"LUCIDE""""), "")
                assertFalse(legacy.contains("\"uiIconSet\""))
                backup.import(legacy, importMode = BackupImportMode.MERGE)
                assertEquals(UiIconSet.MATERIAL, AppearancePrefsImpl(context).config.first().uiIconSet)

                val absent =
                    raw.replace(
                        Regex(""",?\s*"appearance"\s*:\s*\{.*?\}(?=,\s*"contentPreviews")""", RegexOption.DOT_MATCHES_ALL),
                        "",
                    )
                assertFalse(absent.contains("\"appearance\""))
                prefs.setUiIconSet(UiIconSet.LUCIDE)
                backup.import(absent, importMode = BackupImportMode.MERGE)
                assertEquals(UiIconSet.LUCIDE, AppearancePrefsImpl(context).config.first().uiIconSet)
            } finally {
                prefs.setUiIconSet(original)
                db.close()
            }
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
    fun typingIndicatorPreferencesRestoreExplicitly() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val settings = DataStoreSettingsRepository(context)
            val backup = repository(inMemoryDb())

            settings.setSendTypingIndicators(false)
            settings.setShowTypingIndicators(false)
            val raw = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)

            settings.setSendTypingIndicators(true)
            settings.setShowTypingIndicators(true)
            backup.import(raw, importMode = BackupImportMode.MERGE)

            assertFalse(settings.settings.first().sendTypingIndicators)
            assertFalse(settings.settings.first().showTypingIndicators)
        }

    @Test
    fun contentPreviewAutomaticLoadingRoundTripsAndOldBackupsDefaultOn() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = ContentPreviewPrefsImpl(context)
            val backup = repository(inMemoryDb())

            prefs.setShowImages(false)
            prefs.setShowLinkPreviews(false)
            prefs.setAutoLoadOnUnmetered(false)
            prefs.setAutoLoadOnMetered(false)
            val raw = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
            assertFalse(raw.contains("directMediaOnProxiedNetworks"))

            prefs.setAutoLoadOnUnmetered(true)
            prefs.setAutoLoadOnMetered(true)
            backup.import(raw, importMode = BackupImportMode.MERGE)
            assertFalse(prefs.config.first().autoLoadOnUnmetered)
            assertFalse(prefs.config.first().autoLoadOnMetered)

            val legacyRaw =
                raw.replace(
                    "\"autoLoadOnMetered\": false",
                    "\"autoLoadOnMetered\": false, \"directMediaOnProxiedNetworks\": true",
                )
            assertTrue(legacyRaw.contains("\"directMediaOnProxiedNetworks\": true"))
            prefs.setShowImages(true)
            prefs.setShowLinkPreviews(true)
            prefs.setAutoLoadOnUnmetered(true)
            prefs.setAutoLoadOnMetered(true)
            backup.import(legacyRaw, importMode = BackupImportMode.MERGE)
            assertEquals(
                ContentPreviewConfig(
                    showImages = false,
                    showLinkPreviews = false,
                    autoLoadOnUnmetered = false,
                    autoLoadOnMetered = false,
                ),
                prefs.config.first(),
            )
            val reexported = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 2_000L)
            assertFalse(reexported.contains("directMediaOnProxiedNetworks"))

            val oldRaw =
                legacyRaw
                    .replace(Regex("""\s*\"autoLoadOnUnmetered\": false,"""), "")
                    .replace(Regex("""\s*\"autoLoadOnMetered\": false,"""), "")
            prefs.setAutoLoadOnUnmetered(false)
            prefs.setAutoLoadOnMetered(false)
            backup.import(oldRaw, importMode = BackupImportMode.MERGE)
            assertTrue(prefs.config.first().autoLoadOnUnmetered)
            assertTrue(prefs.config.first().autoLoadOnMetered)
        }

    @Test
    fun uploadMetadataPolicyRoundTripsAndOldBackupsDefaultOn() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = AttachmentPrefsImpl(context)
            val backup = repository(inMemoryDb())
            prefs.setConfig(PasteBackendConfig(stripMetadata = false))
            val raw = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
            assertTrue(raw.contains("\"stripMetadata\": false"))
            prefs.updateConfig { it.copy(stripMetadata = true) }
            backup.import(raw, importMode = BackupImportMode.MERGE)
            assertFalse(AttachmentPrefsImpl(context).config.first().stripMetadata)

            val envelope = Json.parseToJsonElement(raw).jsonObject
            val payload = envelope.getValue("payload").jsonObject
            val settings = payload.getValue("settings").jsonObject
            val attachments = settings.getValue("attachments").jsonObject
            val legacy = JsonObject(envelope + ("payload" to JsonObject(payload + ("settings" to JsonObject(settings + ("attachments" to JsonObject(attachments - "stripMetadata"))))))).toString()
            backup.import(legacy, importMode = BackupImportMode.MERGE)
            assertTrue(AttachmentPrefsImpl(context).config.first().stripMetadata)
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
    fun retiredSpriteStyleRestoresAndExportsWithCanonicalName() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val settings = DataStoreSettingsRepository(context)
            val backup = repository(inMemoryDb())
            try {
                settings.setAvatarStyle(AvatarStyle.IRC_SPRITE)
                val raw = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                val legacyRaw = raw.replace("\"avatarStyle\": \"IRC_SPRITE\"", "\"avatarStyle\": \"IRC_SPRITE_V2\"")
                assertTrue(legacyRaw.contains("\"avatarStyle\": \"IRC_SPRITE_V2\""))

                settings.setAvatarStyle(AvatarStyle.INITIALS)
                backup.import(legacyRaw, importMode = BackupImportMode.MERGE)
                assertEquals(AvatarStyle.IRC_SPRITE, settings.settings.first().avatarStyle)

                val reexported = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 2_000L)
                assertTrue(reexported.contains("\"avatarStyle\": \"IRC_SPRITE\""))
                assertFalse(reexported.contains("IRC_SPRITE_V2"))
            } finally {
                settings.setAvatarStyle(AvatarStyle.IRC_SPRITE)
            }
        }

    @Test
    fun chatListSettingsRoundTripThroughSettingsBackup() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val settings = DataStoreSettingsRepository(context)
            val backup = repository(inMemoryDb())
            try {
                settings.setFolderDisplayMode(FolderDisplayMode.TABS)
                settings.setShowFolderChatsInAll(false)
                settings.setChatListSwipeAction(ChatListSwipeAction.MARK_READ)
                val raw = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)

                settings.setFolderDisplayMode(FolderDisplayMode.INLINE)
                settings.setShowFolderChatsInAll(true)
                settings.setChatListSwipeAction(ChatListSwipeAction.ARCHIVE)
                backup.import(raw, importMode = BackupImportMode.MERGE)
                assertEquals(FolderDisplayMode.TABS, settings.settings.first().folderDisplayMode)
                assertFalse(settings.settings.first().showFolderChatsInAll)
                assertEquals(ChatListSwipeAction.MARK_READ, settings.settings.first().chatListSwipeAction)

                val oldRaw =
                    raw
                        .replace(Regex(",\\s*\"folderDisplayMode\"\\s*:\\s*\"TABS\""), "")
                        .replace(Regex(",\\s*\"showFolderChatsInAll\"\\s*:\\s*false"), "")
                        .replace(Regex(",\\s*\"chatListSwipeAction\"\\s*:\\s*\"MARK_READ\""), "")
                assertFalse(oldRaw.contains("folderDisplayMode"))
                assertFalse(oldRaw.contains("showFolderChatsInAll"))
                backup.import(oldRaw, importMode = BackupImportMode.MERGE)
                assertEquals(FolderDisplayMode.INLINE, settings.settings.first().folderDisplayMode)
                assertTrue(settings.settings.first().showFolderChatsInAll)
                assertEquals(ChatListSwipeAction.ARCHIVE, settings.settings.first().chatListSwipeAction)
            } finally {
                settings.setFolderDisplayMode(FolderDisplayMode.INLINE)
                settings.setShowFolderChatsInAll(true)
                settings.setChatListSwipeAction(ChatListSwipeAction.ARCHIVE)
            }
        }

    @Test
    fun historySyncModeRoundTripsAndOlderBackupsDefaultToBalanced() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val settings = DataStoreSettingsRepository(context)
            val backup = repository(inMemoryDb())
            try {
                settings.setHistorySyncMode(HistorySyncMode.AGGRESSIVE)
                val raw = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)

                settings.setHistorySyncMode(HistorySyncMode.LAZY)
                backup.import(raw, importMode = BackupImportMode.MERGE)
                assertEquals(HistorySyncMode.AGGRESSIVE, settings.settings.first().historySyncMode)

                val oldRaw = raw.replace(Regex(""",?\s*"historySyncMode"\s*:\s*"AGGRESSIVE""""), "")
                assertFalse(oldRaw.contains("historySyncMode"))
                settings.setHistorySyncMode(HistorySyncMode.LAZY)
                backup.import(oldRaw, importMode = BackupImportMode.MERGE)
                assertEquals(HistorySyncMode.BALANCED, settings.settings.first().historySyncMode)
            } finally {
                settings.setHistorySyncMode(HistorySyncMode.BALANCED)
            }
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
    fun mentionsSettingsRoundTripThroughSettingsBackup() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val settings = DataStoreSettingsRepository(context)
            val backup = repository(inMemoryDb())

            try {
                settings.setMentionsEnabled(true)
                settings.setMentionsPlacement(MentionsPlacement.FOLDER_TAB)
                val raw = backup.exportToString(mode = BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)

                settings.setMentionsEnabled(false)
                settings.setMentionsPlacement(MentionsPlacement.CHAT_LIST)
                backup.import(raw, importMode = BackupImportMode.MERGE)

                val restored = settings.settings.first()
                assertTrue(restored.mentionsEnabled)
                assertEquals(MentionsPlacement.FOLDER_TAB, restored.mentionsPlacement)
            } finally {
                settings.setMentionsEnabled(false)
                settings.setMentionsPlacement(MentionsPlacement.DRAWER)
            }
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

    @Test
    fun distinctPalettePairRoundTripsPlainAndEncryptedAndReplaceRemovesObsoleteDefinitions() =
        runTest {
            withPaletteBackupState { context ->
                val prefs = AppearancePrefsImpl(context)
                val selected = backupPalette("installed-copy", tracked = true)
                val dark = backupPalette("dark-copy")
                val definitions = listOf(selected, dark)
                val selection =
                    PaletteSelection(
                        PaletteChoice(installedId = selected.document.id),
                        PaletteChoice(installedId = dark.document.id),
                        PaletteMode.SYSTEM,
                    )
                prefs.restorePalettes(definitions, selection)
                val sourceDb = inMemoryDb()
                val targetDb = inMemoryDb()
                try {
                    val backup = repository(sourceDb)
                    val raw = backup.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                    prefs.restorePalettes(listOf(backupPalette("obsolete")), backupSelection(ColorThemePreset.SYSTEM, null, PaletteMode.SYSTEM))
                    repository(targetDb).import(raw, importMode = BackupImportMode.REPLACE)
                    val restored = AppearancePrefsImpl(context).config.first()
                    assertEquals(definitions, restored.customPalettes)
                    assertEquals(selection, restored.paletteSelection)
                    val encrypted =
                        repository(targetDb).exportToString(
                            BackupExportMode.ENCRYPTED_WITH_CREDENTIALS,
                            password = "palette backup password",
                            nowEpochMillis = 1_000L,
                        )
                    assertTrue(backup.isEncrypted(encrypted))
                    prefs.restorePalettes(emptyList(), backupSelection(ColorThemePreset.DARK, null, PaletteMode.DARK))
                    repository(targetDb).import(encrypted, password = "palette backup password", importMode = BackupImportMode.REPLACE)
                    assertEquals(definitions, AppearancePrefsImpl(context).config.first().customPalettes)
                    assertEquals(selection, prefs.config.first().paletteSelection)
                    val legacy = editBackupAppearance(raw) { JsonObject(it - "lightPalette" - "darkPalette") }
                    for (mode in BackupImportMode.entries) {
                        prefs.restorePalettes(emptyList(), PaletteSelection())
                        repository(targetDb).import(legacy, importMode = mode)
                        assertEquals(
                            PaletteSelection(selection.light, selection.light, PaletteMode.SYSTEM),
                            prefs.config.first().paletteSelection,
                        )
                    }
                } finally {
                    sourceDb.close()
                    targetDb.close()
                }
            }
        }

    @Test
    fun paletteMergeRekeysConflictsOncePreservesOriginsAndReusesIdenticalRecords() =
        runTest {
            withPaletteBackupState { context ->
                val prefs = AppearancePrefsImpl(context)
                val incoming = backupPalette("shared-id", tracked = true)
                val identical = backupPalette("identical", tracked = true)
                val originOnly = backupPalette("same-document", tracked = true)
                val localOrigin = originOnly.copy(catalogId = "local-origin", sourceLink = "https://local.example/hosted.motd-palette.json")
                val local =
                    incoming.copy(
                        document = incoming.document.copy(name = "Local authored version", revision = 8),
                        catalogId = "another-upstream",
                        catalogRevision = 8,
                        sourceLink = "https://local.example/original.motd-palette.json",
                    )
                val db = inMemoryDb()
                try {
                    val backup = repository(db)
                    val definitions = listOf(incoming, identical, originOnly)
                    for (shared in listOf(false, true)) {
                        val selection =
                            PaletteSelection(
                                PaletteChoice(installedId = incoming.document.id),
                                PaletteChoice(installedId = if (shared) incoming.document.id else originOnly.document.id),
                                PaletteMode.DARK,
                            )
                        prefs.restorePalettes(definitions, selection)
                        val raw = backup.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                        prefs.restorePalettes(listOf(local, identical, localOrigin), PaletteSelection())
                        backup.import(raw, importMode = BackupImportMode.MERGE)
                        val restored = AppearancePrefsImpl(context).config.first()
                        assertEquals(5, restored.customPalettes.size)
                        assertEquals(local, restored.customPalettes.single { it.document.id == "shared-id" })
                        assertEquals(identical, restored.customPalettes.single { it.document.id == "identical" })
                        assertEquals(localOrigin, restored.customPalettes.single { it.document.id == "same-document" })
                        val rekeyed = restored.customPalettes.single { it.document.name == incoming.document.name }
                        val rekeyedOrigin = restored.customPalettes.single { it.document.id != "same-document" && it.document.name == originOnly.document.name }
                        assertEquals(originOnly, rekeyedOrigin.copy(document = rekeyedOrigin.document.copy(id = originOnly.document.id)))
                        assertEquals(incoming, rekeyed.copy(document = rekeyed.document.copy(id = incoming.document.id)))
                        assertEquals(
                            selection.copy(
                                light = PaletteChoice(installedId = rekeyed.document.id),
                                dark = PaletteChoice(installedId = if (shared) rekeyed.document.id else rekeyedOrigin.document.id),
                            ),
                            restored.paletteSelection,
                        )
                    }
                } finally {
                    db.close()
                }
            }
        }

    @Test
    fun singleModeLocalPaletteBackupRestoresAuthoredValuesAndExplicitMode() =
        runTest {
            withPaletteBackupState { context ->
                val prefs = AppearancePrefsImpl(context)
                val base = backupPalette("dark-only")
                val local = base.copy(document = base.document.copy(light = null))
                val selection = PaletteSelection(dark = PaletteChoice(installedId = "dark-only"), mode = PaletteMode.DARK)
                val db = inMemoryDb()
                try {
                    prefs.restorePalettes(listOf(local), selection)
                    val backup = repository(db)
                    val raw = backup.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                    prefs.restorePalettes(emptyList(), backupSelection(ColorThemePreset.SYSTEM, null, PaletteMode.SYSTEM))
                    backup.import(raw, importMode = BackupImportMode.REPLACE)
                    val restored = AppearancePrefsImpl(context).config.first()
                    assertEquals(listOf(local), restored.customPalettes)
                    assertEquals(selection, restored.paletteSelection)
                    val legacy = editBackupAppearance(raw) { JsonObject(it - "lightPalette" - "darkPalette") }
                    for (mode in BackupImportMode.entries) {
                        prefs.restorePalettes(emptyList(), PaletteSelection())
                        backup.import(legacy, importMode = mode)
                        assertEquals(selection, prefs.config.first().paletteSelection)
                    }
                } finally {
                    db.close()
                }
            }
        }

    @Test
    fun legacyPaletteBackupsPreserveDefinitionsAndMigrateHistoricalModes() =
        runTest {
            withPaletteBackupState { context ->
                val prefs = AppearancePrefsImpl(context)
                val local = backupPalette("keep-local")
                val db = inMemoryDb()
                try {
                    val backup = repository(db)
                    val raw = backup.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                    val cases =
                        listOf(
                            Triple(ColorThemePreset.NORD_LIGHT, true, backupSelection(ColorThemePreset.NORD, null, PaletteMode.SYSTEM)),
                            Triple(ColorThemePreset.NORD_LIGHT, false, backupSelection(ColorThemePreset.NORD, null, PaletteMode.LIGHT)),
                            Triple(ColorThemePreset.DRACULA, true, backupSelection(ColorThemePreset.DRACULA, null, PaletteMode.DARK)),
                            Triple(ColorThemePreset.TOKYO_NIGHT, true, backupSelection(ColorThemePreset.TOKYO_NIGHT, null, PaletteMode.DARK)),
                            Triple(ColorThemePreset.AMOLED, false, backupSelection(ColorThemePreset.DARK, null, PaletteMode.DARK)),
                        )
                    for (mode in BackupImportMode.entries) {
                        for ((theme, followSystem, expected) in cases) {
                            prefs.restorePalettes(listOf(local), backupSelection(ColorThemePreset.SYSTEM, "keep-local", PaletteMode.LIGHT))
                            val legacy =
                                editBackupAppearance(raw) {
                                    JsonObject(
                                        (it - "paletteMode" - "customPaletteId" - "customPalettes" - "trueBlack" - "lightPalette" - "darkPalette") +
                                            mapOf(
                                                "theme" to JsonPrimitive(theme.name),
                                                "followSystem" to JsonPrimitive(followSystem),
                                            ),
                                    )
                                }
                            val nullableLegacy =
                                editBackupAppearance(legacy) {
                                    JsonObject(
                                        it +
                                            mapOf(
                                                "paletteMode" to JsonNull,
                                                "customPaletteId" to JsonNull,
                                                "customPalettes" to JsonNull,
                                            ),
                                    )
                                }
                            val emptyLegacy =
                                editBackupAppearance(nullableLegacy) {
                                    JsonObject(it + ("customPalettes" to JsonArray(emptyList())))
                                }
                            for (document in listOf(legacy, nullableLegacy, emptyLegacy)) {
                                backup.import(document, importMode = mode)
                                val restored = AppearancePrefsImpl(context).config.first()
                                assertEquals(listOf(local), restored.customPalettes)
                                assertEquals(expected, restored.paletteSelection)
                                assertEquals(theme == ColorThemePreset.AMOLED, restored.trueBlack)
                            }
                        }
                    }
                } finally {
                    db.close()
                }
            }
        }

    @Test
    fun invalidPaletteBackupFieldsRejectBeforeGeneralAppearanceOrNetworkMutation() =
        runTest {
            withPaletteBackupState { context ->
                val prefs = AppearancePrefsImpl(context)
                val db = inMemoryDb()
                try {
                    prefs.restorePalettes(listOf(backupPalette("incoming")), backupSelection(ColorThemePreset.NORD, "incoming", PaletteMode.LIGHT))
                    val backup = repository(db)
                    DataStoreSettingsRepository(context).setNickMentionStyle(NickMentionStyle.CHIP)
                    db.networkDao().insert(secretNetwork(null).copy(name = "imported", host = "incoming.example"))
                    val raw = backup.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                    val mutations: List<(JsonObject) -> JsonObject> =
                        listOf(
                            { JsonObject(it - "lightPalette") },
                            { JsonObject(it - "darkPalette") },
                            { JsonObject(it + ("lightPalette" to JsonNull)) },
                            { JsonObject(it + ("darkPalette" to JsonPrimitive("incoming"))) },
                            { JsonObject(it + ("lightPalette" to JsonObject(mapOf("installedId" to JsonPrimitive("missing"))))) },
                            { JsonObject(it + ("darkPalette" to JsonObject(mapOf("installedId" to JsonPrimitive("missing"))))) },
                            { JsonObject(it + ("lightPalette" to JsonObject(mapOf("builtin" to JsonPrimitive(true))))) },
                            { JsonObject(it + ("darkPalette" to JsonObject(mapOf("installedId" to JsonPrimitive(42))))) },
                            { appearance ->
                                val darkOnly = mutateBackupDocument(appearance) { doc -> JsonObject(doc + ("light" to JsonNull)) }
                                JsonObject(darkOnly + ("paletteMode" to JsonPrimitive("DARK")))
                            },
                            { JsonObject(it + ("customPaletteId" to JsonPrimitive("missing"))) },
                            { JsonObject((it - "customPaletteId") + ("customPalettes" to JsonNull)) },
                            { JsonObject(it + ("paletteMode" to JsonPrimitive("SIDEWAYS"))) },
                            { JsonObject(it + ("paletteMode" to JsonNull)) },
                            { JsonObject(it - "paletteMode") },
                            { JsonObject(it + ("paletteMode" to JsonPrimitive(true))) },
                            { mutateBackupRecord(it) { record -> JsonObject(record + ("surprise" to JsonPrimitive(true))) } },
                            { mutateBackupRecord(it) { record -> JsonObject(record + ("catalogRevision" to JsonPrimitive("3"))) } },
                            { mutateBackupRecord(it) { record -> JsonObject(record + ("catalogId" to JsonPrimitive("unpaired-tracking"))) } },
                            {
                                mutateBackupRecord(it) { record ->
                                    JsonObject(record + mapOf("catalogId" to JsonPrimitive("upstream"), "catalogRevision" to JsonPrimitive(4)))
                                }
                            },
                            {
                                mutateBackupRecord(it) { record ->
                                    val document = JsonObject(record.getValue("document").jsonObject + ("dark" to JsonNull))
                                    JsonObject(record + mapOf("document" to document, "catalogId" to JsonPrimitive("upstream"), "catalogRevision" to JsonPrimitive(3)))
                                }
                            },
                            { mutateBackupDocument(it) { doc -> JsonObject(doc + ("unknownRole" to JsonPrimitive("#123456"))) } },
                            { mutateBackupDocument(it) { doc -> JsonObject(doc + ("revision" to JsonPrimitive(true))) } },
                            { mutateBackupDocument(it) { doc -> JsonObject(doc + ("version" to JsonPrimitive(99))) } },
                            { mutateBackupDocument(it) { doc -> JsonObject(doc + ("light" to JsonNull)) } },
                            { mutateBackupDocument(it) { doc -> JsonObject(doc + ("sourceUrl" to JsonPrimitive("http://insecure.example/palette"))) } },
                            {
                                mutateBackupDocument(it) { doc ->
                                    JsonObject(doc + ("light" to JsonObject(doc.getValue("light").jsonObject + ("unsupported" to JsonPrimitive("#123456")))))
                                }
                            },
                            {
                                mutateBackupDocument(it) { doc ->
                                    JsonObject(doc + ("light" to JsonObject(doc.getValue("light").jsonObject - "primary")))
                                }
                            },
                            {
                                mutateBackupDocument(it) { doc ->
                                    JsonObject(doc + ("light" to JsonObject(doc.getValue("light").jsonObject + ("primary" to JsonPrimitive("#1234")))))
                                }
                            },
                            { appearance ->
                                val record =
                                    appearance
                                        .getValue("customPalettes")
                                        .jsonArray
                                        .single()
                                        .jsonObject
                                val records =
                                    (0..64).map { n ->
                                        JsonObject(record + ("document" to JsonObject(record.getValue("document").jsonObject + ("id" to JsonPrimitive("overflow-$n")))))
                                    }
                                JsonObject(appearance + ("customPalettes" to JsonArray(records)) + ("customPaletteId" to JsonPrimitive("overflow-0")))
                            },
                            { appearance -> JsonObject(appearance + ("customPalettes" to JsonArray(emptyList()))) },
                        )
                    DataStoreSettingsRepository(context).setNickMentionStyle(NickMentionStyle.NONE)
                    prefs.restorePalettes(listOf(backupPalette("destination")), backupSelection(ColorThemePreset.DARK, "destination", PaletteMode.DARK))
                    db.networkDao().deleteLocalTree(
                        db
                            .networkDao()
                            .allNow()
                            .single()
                            .id,
                    )
                    db.networkDao().insert(secretNetwork(null).copy(name = "retained", host = "retained.example"))
                    for (mutation in mutations) {
                        for (mode in BackupImportMode.entries) {
                            assertPaletteImportRejectedWithoutMutation(context, db, editBackupAppearance(raw, mutation), mode)
                        }
                    }
                } finally {
                    db.close()
                }
            }
        }

    @Test
    fun paletteMergeCountAndByteLimitsFailBeforeAnySettingsOrNetworksChange() =
        runTest {
            withPaletteBackupState { context ->
                val prefs = AppearancePrefsImpl(context)
                val sourceDb = inMemoryDb()
                val targetDb = inMemoryDb()
                try {
                    val source = repository(sourceDb)
                    sourceDb.networkDao().insert(secretNetwork(null).copy(host = "incoming.example"))
                    DataStoreSettingsRepository(context).setNickMentionStyle(NickMentionStyle.CHIP)
                    prefs.restorePalettes(listOf(backupPalette("new")), backupSelection(ColorThemePreset.NORD, "new", PaletteMode.LIGHT))
                    val countRaw = source.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                    val largeIncoming = (0 until 32).map { largeBackupPalette("incoming-$it") }
                    prefs.restorePalettes(largeIncoming, backupSelection(ColorThemePreset.NORD, "incoming-0", PaletteMode.LIGHT))
                    val byteRaw = source.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                    DataStoreSettingsRepository(context).setNickMentionStyle(NickMentionStyle.NONE)
                    targetDb.networkDao().insert(secretNetwork(null).copy(name = "retained", host = "retained.example"))
                    val full = (0 until 64).map { backupPalette("local-$it") }
                    prefs.restorePalettes(full, backupSelection(ColorThemePreset.DARK, "local-0", PaletteMode.DARK))
                    assertPaletteImportRejectedWithoutMutation(context, targetDb, countRaw, BackupImportMode.MERGE)
                    val largeLocal = (0 until 32).map { largeBackupPalette("local-$it") }
                    prefs.restorePalettes(largeLocal, backupSelection(ColorThemePreset.DARK, "local-0", PaletteMode.DARK))
                    assertTrue(runCatching { validateInstalledPalettes(largeLocal + largeIncoming) }.isFailure)
                    assertPaletteImportRejectedWithoutMutation(context, targetDb, byteRaw, BackupImportMode.MERGE)
                    val oversizedIncoming =
                        editBackupAppearance(byteRaw) { appearance ->
                            val original = appearance.getValue("customPalettes").jsonArray
                            val extra =
                                original.mapIndexed { index, element ->
                                    val record = element.jsonObject
                                    JsonObject(record + ("document" to JsonObject(record.getValue("document").jsonObject + ("id" to JsonPrimitive("extra-$index")))))
                                }
                            JsonObject(appearance + ("customPalettes" to JsonArray(original + extra)))
                        }
                    assertPaletteImportRejectedWithoutMutation(context, targetDb, oversizedIncoming, BackupImportMode.REPLACE)
                } finally {
                    sourceDb.close()
                    targetDb.close()
                }
            }
        }

    @Test
    fun corruptPaletteCollectionRefusesExportAndMergeButModernReplaceRecovers() =
        runTest {
            withPaletteBackupState { context ->
                val prefs = AppearancePrefsImpl(context)
                val db = inMemoryDb()
                try {
                    val installed = backupPalette("recovered", tracked = true)
                    val selection = backupSelection(ColorThemePreset.NORD, "recovered", PaletteMode.LIGHT)
                    prefs.restorePalettes(listOf(installed), selection)
                    val backup = repository(db)
                    DataStoreSettingsRepository(context).setNickMentionStyle(NickMentionStyle.CHIP)
                    db.networkDao().insert(secretNetwork(null).copy(name = "imported", host = "incoming.example"))
                    val raw = backup.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L)
                    val legacy = editBackupAppearance(raw) { JsonObject(it - "paletteMode" - "customPaletteId" - "customPalettes" - "lightPalette" - "darkPalette") }
                    for ((corrupt, error) in listOf(
                        "{broken" to PaletteReadError.INVALID,
                        " ".repeat(MAX_INSTALLED_PALETTE_BYTES + 1) to PaletteReadError.TOO_LARGE,
                    )) {
                        DataStoreSettingsRepository(context).setNickMentionStyle(NickMentionStyle.NONE)
                        db.networkDao().allNow().forEach { db.networkDao().deleteLocalTree(it.id) }
                        db.networkDao().insert(secretNetwork(null).copy(name = "retained", host = "retained.example"))
                        context.appearanceDataStore.edit { it[stringPreferencesKey("custom_palettes_v1")] = corrupt }
                        assertEquals(error, AppearancePrefsImpl(context).config.first().customPaletteReadError)
                        assertTrue(runCatching { backup.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED) }.isFailure)
                        assertTrue(runCatching { backup.exportToString(BackupExportMode.ENCRYPTED_WITH_CREDENTIALS, password = "password") }.isFailure)
                        assertPaletteImportRejectedWithoutMutation(context, db, raw, BackupImportMode.MERGE)
                        assertPaletteImportRejectedWithoutMutation(context, db, legacy, BackupImportMode.REPLACE)
                        backup.import(raw, importMode = BackupImportMode.REPLACE)
                        val recovered = AppearancePrefsImpl(context).config.first()
                        assertNull(recovered.customPaletteReadError)
                        assertEquals(listOf(installed), recovered.customPalettes)
                        assertEquals(selection, recovered.paletteSelection)
                        assertEquals(NickMentionStyle.CHIP, DataStoreSettingsRepository(context).settings.first().nickMentionStyle)
                        assertEquals(
                            "incoming.example",
                            db
                                .networkDao()
                                .allNow()
                                .single()
                                .host,
                        )
                    }
                } finally {
                    db.close()
                }
            }
        }

    @Test
    fun absentAppearancePreservesHealthyOrCorruptPaletteStateWhileRestoringGeneralSettings() =
        runTest {
            withPaletteBackupState { context ->
                val prefs = AppearancePrefsImpl(context)
                val settings = DataStoreSettingsRepository(context)
                val db = inMemoryDb()
                try {
                    settings.setNickMentionStyle(NickMentionStyle.CHIP)
                    val backup = repository(db)
                    val raw =
                        editBackupAppearance(
                            backup.exportToString(BackupExportMode.CREDENTIALS_EXCLUDED, nowEpochMillis = 1_000L),
                        ) { null }
                    prefs.restorePalettes(listOf(backupPalette("retained")), backupSelection(ColorThemePreset.NORD, "retained", PaletteMode.DARK))
                    prefs.setTrueBlack(true)
                    prefs.setFontChoice(FontChoice.JETBRAINS_MONO)
                    for (corrupt in listOf(false, true)) {
                        if (corrupt) context.appearanceDataStore.edit { it[stringPreferencesKey("custom_palettes_v1")] = "{broken" }
                        for (mode in BackupImportMode.entries) {
                            settings.setNickMentionStyle(NickMentionStyle.NONE)
                            val before = context.appearanceDataStore.data.first()
                            val config = prefs.config.first()
                            backup.import(raw, importMode = mode)
                            assertEquals(NickMentionStyle.CHIP, settings.settings.first().nickMentionStyle)
                            assertEquals(before, context.appearanceDataStore.data.first())
                            assertEquals(config, AppearancePrefsImpl(context).config.first())
                        }
                    }
                } finally {
                    db.close()
                }
            }
        }

    private fun backupSelection(
        builtin: ColorThemePreset,
        installedId: String?,
        mode: PaletteMode,
    ): PaletteSelection {
        val choice = PaletteChoice(if (installedId == null) builtin.canonicalPalette else ColorThemePreset.SYSTEM, installedId)
        return PaletteSelection(
            light = if (installedId != null || choice.supportsMode(false)) choice else PaletteChoice(),
            dark = if (installedId != null || choice.supportsMode(true)) choice else PaletteChoice(),
            mode = mode,
        )
    }

    private suspend fun withPaletteBackupState(block: suspend (Context) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appearance = context.appearanceDataStore.data.first()
        val settings = context.settingsDataStore.data.first()
        try {
            context.appearanceDataStore.edit { it.clear() }
            context.settingsDataStore.edit { it.clear() }
            block(context)
        } finally {
            context.appearanceDataStore.updateData { appearance.toMutablePreferences() }
            context.settingsDataStore.updateData { settings.toMutablePreferences() }
        }
    }

    private fun backupPalette(
        id: String,
        tracked: Boolean = false,
    ): InstalledPalette {
        val dark =
            PaletteColors(
                "#AABBCC",
                "#DDAACC",
                "#33BB99",
                "#111111",
                "#222222",
                "#333333",
                "#EEEEEE",
                "#FFFFFF",
                "#BBBBBB",
                "#777777",
                listOf("#FF7766", "#66BBCC"),
            )
        val light = dark.copy(background = "#FAFAFA", surface = "#EFEFEF", onBackground = "#141414", onSurface = "#151515")
        return InstalledPalette(
            PaletteDocument(
                id,
                "Authored $id",
                3,
                light,
                dark,
                sourceUrl = "https://source.example/palette",
                license = "Existing license",
                notices = "Preserved supplied notice",
            ),
            catalogId = if (tracked) "distinct-upstream-id" else null,
            catalogRevision = if (tracked) 3 else null,
            sourceLink = "https://files.example/$id.motd-palette.json",
        )
    }

    private fun largeBackupPalette(id: String): InstalledPalette {
        val palette = backupPalette(id)
        return palette.copy(
            document =
                palette.document.copy(
                    notices = "n".repeat(4096),
                    sourceUrl = "https://source.example/" + "s".repeat(1970),
                    license = "l".repeat(128),
                ),
            sourceLink = "https://files.example/" + "p".repeat(1970),
        )
    }

    private fun editBackupAppearance(
        raw: String,
        transform: (JsonObject) -> JsonObject?,
    ): String {
        val envelope = Json.parseToJsonElement(raw).jsonObject
        val payload = envelope.getValue("payload").jsonObject
        val settings = payload.getValue("settings").jsonObject
        val replacement = transform(settings.getValue("appearance").jsonObject)
        val updated = if (replacement == null) settings - "appearance" else settings + ("appearance" to replacement)
        return JsonObject(envelope + ("payload" to JsonObject(payload + ("settings" to JsonObject(updated))))).toString()
    }

    private fun mutateBackupRecord(
        appearance: JsonObject,
        transform: (JsonObject) -> JsonObject,
    ): JsonObject {
        val records = appearance.getValue("customPalettes").jsonArray
        return JsonObject(appearance + ("customPalettes" to JsonArray(listOf(transform(records.single().jsonObject)))))
    }

    private fun mutateBackupDocument(
        appearance: JsonObject,
        transform: (JsonObject) -> JsonObject,
    ): JsonObject =
        mutateBackupRecord(appearance) { record ->
            JsonObject(record + ("document" to transform(record.getValue("document").jsonObject)))
        }

    private suspend fun assertPaletteImportRejectedWithoutMutation(
        context: Context,
        db: io.github.trevarj.motd.data.db.MotdDatabase,
        raw: String,
        mode: BackupImportMode,
    ) {
        val appearance = context.appearanceDataStore.data.first()
        val settings = context.settingsDataStore.data.first()
        val networks = db.networkDao().allNow()
        assertTrue(runCatching { repository(db).preview(raw, importMode = mode) }.isFailure)
        val result = runCatching { repository(db).import(raw, importMode = mode) }
        assertTrue("Expected $mode palette preflight rejection", result.isFailure)
        assertEquals(appearance, context.appearanceDataStore.data.first())
        assertEquals(settings, context.settingsDataStore.data.first())
        assertEquals(networks, db.networkDao().allNow())
    }

    private fun repository(db: io.github.trevarj.motd.data.db.MotdDatabase): ConfigurationBackupRepositoryImpl {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = DataStoreSettingsRepository(context)
        return ConfigurationBackupRepositoryImpl(
            db = db,
            settingsRepository = settings,
            appearancePrefs = AppearancePrefsImpl(context),
            contentPreviewPrefs = ContentPreviewPrefsImpl(context),
            chatSoundPrefs = ChatSoundPrefs(context),
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
