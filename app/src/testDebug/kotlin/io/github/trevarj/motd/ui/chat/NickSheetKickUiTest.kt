package io.github.trevarj.motd.ui.chat

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.FoolPreferences
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.settingsDataStore
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.components.ReasonPresetChips
import io.github.trevarj.motd.ui.settings.FoolPreferencesEditor
import io.github.trevarj.motd.ui.settings.FoolSettingsSheet
import io.github.trevarj.motd.ui.settings.ManageNicksScreen
import io.github.trevarj.motd.ui.settings.ManageNicksViewModel
import io.github.trevarj.motd.ui.settings.NickListKind
import io.github.trevarj.motd.ui.settings.foolEditorPreferences
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class NickSheetKickUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun existing_fool_profile_opens_shared_persisted_editor_with_network_identity_rules() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = DataStoreSettingsRepository(context)
        val editor = FoolPreferencesEditor(repo)
        val rules = IrcIdentityRules()
        runBlocking {
            context.settingsDataStore.edit { it.clear() }
            repo.setFoolsMode(FoolsMode.DIMMED)
            repo.setFool("Nick[", true, rules)
        }
        var profile by mutableStateOf(true)
        var editing by mutableStateOf(false)
        compose.setContent {
            val settings by repo.settings.collectAsState(Settings())
            val scope = rememberCoroutineScope()
            MotdTheme(dynamicColor = false) {
                if (profile) {
                    NickActionSheet(
                        nick = "NICK{",
                        isSelf = false,
                        isFriend = false,
                        isFool = true,
                        canModerate = false,
                        whois = null,
                        onDismiss = { profile = false },
                        onMessage = {},
                        onMention = {},
                        onToggleFriend = {},
                        onToggleFool = {},
                        onOpenFoolSettings = {
                            profile = false
                            editing = true
                        },
                        onOp = {},
                        onVoice = {},
                        onKick = {},
                        onBan = { _, _ -> },
                    )
                }
                val preferences = foolEditorPreferences("NICK{", settings.foolsMode, settings.fools, settings.foolPreferences, rules)
                if (editing && preferences != null) {
                    FoolSettingsSheet(
                        nick = "NICK{",
                        globalMode = settings.foolsMode,
                        preferences = preferences,
                        onChange = { transform -> scope.launch { editor.update("NICK{", rules, transform) } },
                        onDismiss = { editing = false },
                    )
                }
            }
        }
        compose.onNodeWithTag("nick_sheet_fool_settings").performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithTag("fool_settings_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("nick_sheet").assertDoesNotExist()
        compose.onNodeWithTag("fool_visibility_hide").performClick()
        awaitProfilePreferences(repo, FoolPreferences(FoolsMode.HIDE))
        compose.onNodeWithTag("fool_notify_mentions").performClick()
        awaitProfilePreferences(repo, FoolPreferences(FoolsMode.HIDE, notifyMentions = true))
        compose.onNodeWithTag("fool_notify_direct_messages").assertIsOff().performClick()
        awaitProfilePreferences(repo, FoolPreferences(FoolsMode.HIDE, notifyMentions = true, notifyDirectMessages = true))
        val sheet = hasTestTag("fool_settings_sheet")
        compose
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and (sheet or hasAnyAncestor(sheet)), useUnmergedTree = true)[0]
            .performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("fool_settings_sheet").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { profile = true }
        compose.onNodeWithTag("nick_sheet_fool_settings").performClick()
        compose.onNodeWithTag("fool_visibility_hide").assertIsSelected()
        compose.onNodeWithTag("fool_notify_mentions").assertIsOn().performClick()
        awaitProfilePreferences(repo, FoolPreferences(FoolsMode.HIDE, notifyDirectMessages = true))
        compose.onNodeWithTag("fool_visibility_inherit").performClick()
        awaitProfilePreferences(repo, FoolPreferences(notifyDirectMessages = true))
        compose.onNodeWithTag("fool_visibility_inherit").assertIsSelected()
        runBlocking { repo.setFool("NICK{", false, rules) }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithTag("fool_settings_sheet").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun stateless_sheet_preserves_rapid_distinct_controls_before_delayed_settings_emission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = DataStoreSettingsRepository(context)
        runBlocking {
            context.settingsDataStore.edit { it.clear() }
            repo.setFool("bot", true)
        }
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<FoolPreferences>()
        val delayed =
            object : SettingsRepository by repo {
                override suspend fun setFoolPreferences(
                    nick: String,
                    preferences: FoolPreferences,
                    identityRules: IrcIdentityRules?,
                ) {
                    writes += preferences
                    gate.await()
                    repo.setFoolPreferences(nick, preferences, identityRules)
                }
            }
        val vm = ManageNicksViewModel(delayed)
        val store = androidx.lifecycle.ViewModelStore().apply { put("fool-editor", vm) }
        try {
            compose.setContent {
                MotdTheme(dynamicColor = false) {
                    ManageNicksScreen(kind = NickListKind.FOOLS, viewModel = vm)
                }
            }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                compose.onAllNodesWithTag("manage_nick_bot").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("manage_nick_bot").performClick()
            compose.onNodeWithTag("fool_notify_mentions").performScrollTo().performClick()
            compose.onNodeWithTag("fool_notify_direct_messages").performScrollTo().performClick()
            compose.onNodeWithTag("fool_visibility_collapse").performScrollTo().performClick()
            assertEquals(listOf(FoolPreferences(notifyMentions = true)), writes)
            assertEquals(emptyMap<String, FoolPreferences>(), runBlocking { repo.settings.first() }.foolPreferences)

            gate.complete(Unit)
            val saved = FoolPreferences(FoolsMode.COLLAPSE, notifyMentions = true, notifyDirectMessages = true)
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                runBlocking { repo.settings.first() }.foolPreferences["bot"] == saved
            }
            compose.onNodeWithTag("fool_visibility_collapse").assertIsSelected()
            compose.onNodeWithTag("fool_notify_mentions").performScrollTo().assertIsOn()
            compose.onNodeWithTag("fool_notify_direct_messages").performScrollTo().assertIsOn()
            assertEquals(
                listOf(FoolPreferences(notifyMentions = true), FoolPreferences(notifyMentions = true, notifyDirectMessages = true), saved),
                writes,
            )
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }

    private fun awaitProfilePreferences(
        repo: DataStoreSettingsRepository,
        expected: FoolPreferences,
    ) {
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            runBlocking { repo.settings.first() }.foolPreferences.values.singleOrNull() == expected
        }
        assertEquals(setOf("nick{"), runBlocking { repo.settings.first() }.fools.map { IrcIdentityRules().normalize(it) }.toSet())
    }

    @Test
    fun defaultIdentity_exposesExistingActions() {
        val rawNick = "Alice/discord"
        var invites = 0
        compose.setContent {
            MotdTheme {
                NickActionSheet(
                    nick = rawNick,
                    isSelf = false,
                    isFriend = false,
                    isFool = false,
                    canModerate = true,
                    whois = WhoisInfo(nick = rawNick, realname = "Alice Example"),
                    onDismiss = {},
                    onMessage = {},
                    onMention = {},
                    onToggleFriend = {},
                    onToggleFool = {},
                    onOpenFoolSettings = {},
                    onInviteToChannel = { invites++ },
                    onOp = {},
                    onVoice = {},
                    onKick = {},
                    onBan = { _, _ -> },
                    canEditConversationAvatar = true,
                )
            }
        }

        compose.onNodeWithText(rawNick).assertExists()
        compose.onNodeWithText("Alice Example").assertExists()
        listOf(
            "Message",
            "Mention",
            "Add to friends",
            "Add to fools",
            "Ignore on this network",
            "Give op",
            "Take op",
            "Give voice",
            "Take voice",
            "Kick",
            "Ban",
        ).forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithTag("nick_sheet_avatar").assertExists()
        compose.onNodeWithTag("nick_sheet_edit_avatar").assertExists()
        compose.onNodeWithTag("nick_sheet_invite_to_channel").performClick()
        compose.runOnIdle { assertEquals(1, invites) }
    }

    @Test
    fun relayIdentity_exposesOnlyUsableActionsWithRawCallbacks() {
        val rawNick = "Alice/discord"
        val callbacks = mutableListOf<Pair<String, String>>()
        compose.setContent {
            MotdTheme {
                NickActionSheet(
                    nick = rawNick,
                    isSelf = false,
                    isFriend = false,
                    isFool = false,
                    canModerate = true,
                    whois = WhoisInfo(nick = rawNick, realname = "Hidden relay WHOIS"),
                    onDismiss = {},
                    onMessage = { callbacks += "message" to rawNick },
                    onMention = { callbacks += "mention" to rawNick },
                    onToggleFriend = { callbacks += "friend" to rawNick },
                    onToggleFool = { callbacks += "fool" to rawNick },
                    onOpenFoolSettings = {},
                    onIgnoreNetwork = { callbacks += "ignore" to rawNick },
                    onInviteToChannel = { callbacks += "invite" to rawNick },
                    onOp = { callbacks += "op" to rawNick },
                    onVoice = { callbacks += "voice" to rawNick },
                    onKick = { callbacks += "kick" to rawNick },
                    onBan = { _, _ -> callbacks += "ban" to rawNick },
                    canEditConversationAvatar = true,
                    onEditConversationAvatar = { callbacks += "avatar" to rawNick },
                    relayIdentity = true,
                )
            }
        }

        compose.onNodeWithText("Alice").assertExists()
        compose.onNodeWithText(rawNick).assertDoesNotExist()
        compose.onNodeWithText("Discord relay identity").assertExists()
        listOf("Mention", "Add to friends", "Add to fools", "Ignore on this network").forEach {
            compose.onNodeWithText(it).performClick()
        }
        compose.runOnIdle {
            assertEquals(
                listOf(
                    "mention" to rawNick,
                    "friend" to rawNick,
                    "fool" to rawNick,
                    "ignore" to rawNick,
                ),
                callbacks,
            )
        }

        compose.onNodeWithText("Hidden relay WHOIS").assertDoesNotExist()
        compose.onNodeWithTag("nick_sheet_avatar").assertDoesNotExist()
        compose.onNodeWithTag("nick_sheet_edit_avatar").assertDoesNotExist()
        compose.onNodeWithTag("nick_sheet_invite_to_channel").assertDoesNotExist()
        listOf(
            "Message",
            "Invite to channel",
            "Edit chat avatar",
            "Give op",
            "Take op",
            "Give voice",
            "Take voice",
            "Kick",
            "Ban",
        ).forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun inviteToChannelAction_isHiddenForSelf() {
        compose.setContent {
            MotdTheme {
                NickActionSheet(
                    nick = "me",
                    isSelf = true,
                    isFriend = false,
                    isFool = true,
                    canModerate = false,
                    whois = null,
                    onDismiss = {},
                    onMessage = {},
                    onMention = {},
                    onToggleFriend = {},
                    onToggleFool = {},
                    onOpenFoolSettings = {},
                    onInviteToChannel = {},
                    onOp = {},
                    onVoice = {},
                    onKick = {},
                    onBan = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("nick_sheet_invite_to_channel").assertDoesNotExist()
        compose.onNodeWithTag("nick_sheet_fool_settings").assertDoesNotExist()
    }

    @Test
    fun kickDialog_presetChipSelectsTheReason() {
        var reason = ""
        compose.setContent {
            MotdTheme {
                ReasonPresetChips(
                    current = reason,
                    onSelect = { reason = it },
                    tagPrefix = "nick_sheet_kick_chip",
                )
            }
        }

        compose.onNodeWithTag("nick_sheet_kick_chip_flooding").performClick()
        assertEquals("Flooding", reason)
    }
}
