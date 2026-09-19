package io.github.trevarj.motd.ui.settings

import android.content.Context
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.HistorySyncMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.sync.HistoryPruner
import io.github.trevarj.motd.ui.components.HistorySyncModeSheet
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class HistorySyncSettingsUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val persisted = DataStoreSettingsRepository(context)
    private var db: MotdDatabase? = null

    private val viewModels = ViewModelStore()

    @After
    fun tearDown() {
        viewModels.clear()
        db?.close()
    }

    @Test
    fun globalEditorUsesPersistedStateAndTargetsTheControl() {
        runBlocking { persisted.setHistorySyncMode(HistorySyncMode.BALANCED) }
        compose.setContent {
            val saved by persisted.settings.collectAsStateWithLifecycle(initialValue = Settings())
            val scope = rememberCoroutineScope()
            MotdTheme(dynamicColor = false) {
                HistorySettingsContent(
                    settings = saved,
                    databaseSizeBytes = 0,
                    databaseProfile = null,
                    onBack = {},
                    onHistoryRetention = {},
                    onHistorySyncMode = { mode -> scope.launch { persisted.setHistorySyncMode(mode) } },
                    onHistoryRetentionCustomRows = {},
                    onCompactDatabase = {},
                    target = SettingsTarget.HISTORY_SYNC_MODE,
                )
            }
        }

        compose.onNodeWithTag("settings_target_highlight_HISTORY_SYNC_MODE").assertIsDisplayed()
        compose.onNodeWithTag("settings_history_sync_mode").assertTextContains("Balanced").performClick()
        compose.onNodeWithTag("settings_history_sync_sheet").assertIsDisplayed()
        compose.onNodeWithTag("settings_history_sync_balanced").assertIsSelected()
        compose
            .onAllNodes(hasText(DISCLOSURE) and hasAnyAncestor(hasTestTag("settings_history_sync_sheet")))[0]
            .assertIsDisplayed()
        compose.onNodeWithTag("settings_history_sync_aggressive").performClick()
        compose.onNodeWithTag("settings_history_sync_mode").assertTextContains("Aggressive")
    }

    @Test
    fun failedGlobalWriteShowsAnErrorWithoutChangingTheSavedChoice() {
        val settings =
            FailableSettingsRepository(
                persisted,
                Settings(historySyncMode = HistorySyncMode.AGGRESSIVE),
            ).apply { failWrites = true }
        val model = HistorySettingsViewModel(settings, HistoryPruner.Noop)
        viewModels.put("history-failure", model)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                HistorySettingsScreen(viewModel = model)
            }
        }

        compose.onNodeWithTag("settings_history_sync_mode").assertTextContains("Aggressive").performClick()
        compose.onNodeWithTag("settings_history_sync_lazy").performClick()
        compose.onNodeWithText("Could not save history sync setting.").assertIsDisplayed()
        compose.onNodeWithTag("settings_history_sync_mode").assertTextContains("Aggressive")
    }

    @Test
    fun directMessageOverrideSurvivesGlobalChangesAndCanReturnToInheritance() {
        val database =
            Room
                .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        db = database
        val networkId =
            runBlocking {
                database.networkDao().insert(
                    NetworkEntity(
                        name = "test",
                        role = NetworkRole.DIRECT,
                        host = "irc.example",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            }
        val roomId =
            runBlocking {
                database.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "alice",
                        displayName = "Alice",
                        type = BufferType.QUERY,
                        historySyncModeOverride = HistorySyncMode.BALANCED,
                    ),
                )
            }
        runBlocking { persisted.setHistorySyncMode(HistorySyncMode.AGGRESSIVE) }

        compose.setContent {
            val settings by persisted.settings.collectAsStateWithLifecycle(
                initialValue =
                    io.github.trevarj.motd.data.prefs
                        .Settings(),
            )
            val room by database.bufferDao().observe(roomId).collectAsStateWithLifecycle(initialValue = null)
            val scope = rememberCoroutineScope()
            var open by remember { mutableStateOf(true) }
            Button(onClick = { open = true }, modifier = Modifier.testTag("open_history_sync")) { Text("Open") }
            if (open) {
                HistorySyncModeSheet(
                    selected = room?.historySyncModeOverride,
                    global = settings.historySyncMode,
                    includeInherit = true,
                    onSelect = { mode -> scope.launch { database.bufferDao().setHistorySyncModeOverride(roomId, mode) } },
                    onDismiss = { open = false },
                    tag = "chat_history_sync_sheet",
                )
            }
        }

        compose.onNodeWithTag("chat_history_sync_balanced").assertIsSelected()
        runBlocking { persisted.setHistorySyncMode(HistorySyncMode.LAZY) }
        compose.onNodeWithTag("chat_history_sync_inherit").assertTextContains("Use global (Lazy)")
        compose.onNodeWithTag("chat_history_sync_balanced").assertIsSelected()
        compose.onNodeWithTag("chat_history_sync_inherit").performClick()
        compose.waitForIdle()
        assertEquals(null, runBlocking { database.bufferDao().observeById(roomId)?.historySyncModeOverride })
        compose.onNodeWithTag("open_history_sync").performClick()
        compose.onNodeWithTag("chat_history_sync_inherit").assertIsSelected().assertTextContains("Use global (Lazy)")
        assertEquals(BufferType.QUERY, runBlocking { database.bufferDao().observeById(roomId)?.type })
    }

    private class FailableSettingsRepository(
        private val delegate: SettingsRepository,
        initialSettings: Settings? = null,
    ) : SettingsRepository by delegate {
        override val settings: Flow<Settings> = initialSettings?.let(::MutableStateFlow) ?: delegate.settings
        var failWrites = false

        override suspend fun setHistorySyncMode(mode: HistorySyncMode) {
            if (failWrites) throw IOException("failed")
            try {
                delegate.setHistorySyncMode(mode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    private companion object {
        const val DISCLOSURE =
            "History sync does not change live notifications. Messages recovered only from history stay silent. Missing-history indicators remain until coverage or read state changes."
    }
}
