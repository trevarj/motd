package io.github.trevarj.motd.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.notes.NickNoteKey
import io.github.trevarj.motd.data.notes.NickNoteKind
import io.github.trevarj.motd.data.notes.NickNotesRepository
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class NickNoteEditorUiTest {
    @get:Rule(order = 0)
    val dispatcher = UiDispatcherResetRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun disclosesIdentityRestoresOnlyItsOwnDraftSavesDeletesAndPreservesRejectedWrites() {
        val db = inMemoryDb(directCommit = true)
        try {
            val repository = NickNotesRepository(db)
            val networkId = runBlocking { db.networkDao().insert(network()) }
            val account = NickNoteTarget(NickNoteKey(networkId, NickNoteKind.ACCOUNT, "alice"), "alice")
            val nickname = NickNoteTarget(account.key.copy(kind = NickNoteKind.NICKNAME), "Alice")
            val viewModel = NickNoteEditorViewModel(repository)
            val draft = "First line\nSecond line"
            runBlocking { repository.save(account.key, "Original note") }
            var target by mutableStateOf<NickNoteTarget?>(account)
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                MotdTheme(dynamicColor = false) {
                    target?.let { NickNoteEditor(it, onDismiss = { target = null }, viewModel = viewModel) }
                }
            }

            awaitField()
            compose.onNodeWithText("Verified account: alice").assertIsDisplayed()
            assertField("Original note")
            compose.onNodeWithTag("nick_note_field").performTextReplacement(draft)
            restoration.emulateSavedInstanceStateRestore()
            awaitField()
            assertField(draft)

            compose.runOnIdle { target = nickname }
            awaitField()
            compose.onNodeWithText("Nickname only: Alice").assertIsDisplayed()
            assertField("")
            compose.runOnIdle { target = account }
            awaitField()
            assertField("Original note")
            compose.onNodeWithTag("nick_note_field").performTextReplacement(draft)
            compose.onNodeWithText("Save").performClick()
            awaitDismissal()
            assertEquals(draft, runBlocking { repository.read(account.key) })

            compose.runOnIdle { target = account }
            awaitField()
            assertField(draft)
            compose.onNodeWithText("Delete").performClick()
            awaitDismissal()
            assertNull(runBlocking { repository.read(account.key) })

            compose.runOnIdle { target = account }
            awaitField()
            compose.onNodeWithTag("nick_note_field").performTextReplacement(draft)
            runBlocking { db.networkDao().deleteLocalTree(networkId) }
            compose.onNodeWithText("Save").performClick()
            val writeError = "Couldn’t save changes to this note. Try again."
            compose.waitUntil(5_000) { compose.onAllNodesWithText(writeError).fetchSemanticsNodes().isNotEmpty() }
            compose
                .onNodeWithText(writeError)
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            assertField(draft)
            compose.onNodeWithText("Cancel").performClick()
            awaitDismissal()
        } finally {
            db.close()
        }
    }

    private fun awaitField() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("nick_note_field").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitDismissal() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("nick_note_field").fetchSemanticsNodes().isEmpty() }
    }

    private fun assertField(text: String) {
        compose.onNodeWithTag("nick_note_field").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)),
        )
    }
}
