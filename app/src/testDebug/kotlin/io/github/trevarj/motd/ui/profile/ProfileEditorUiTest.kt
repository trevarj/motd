package io.github.trevarj.motd.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.profile.ProfileAccess
import io.github.trevarj.motd.profile.ProfileEditorState
import io.github.trevarj.motd.profile.ProfileKey
import io.github.trevarj.motd.profile.ProfileTarget
import io.github.trevarj.motd.profile.ProfileWriteResult
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ProfileEditorUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun publishAndRemoveAreExplicitAndDenialPreservesTheDraft() {
        val writes = mutableListOf<Pair<ProfileKey, String?>>()
        val target = ProfileTarget.Self(7)
        val state = ProfileEditorState("alice", 7, ProfileAccess.WRITABLE, mapOf(ProfileKey.DISPLAY_NAME to "Original"))
        compose.setContent {
            MotdTheme {
                var writeState by remember { mutableStateOf(ProfileWriteState()) }
                ProfileEditorContent(
                    target = target,
                    state = state,
                    writeState = writeState,
                    onBack = {},
                    onOpenNetworkSettings = {},
                    onClearResult = { writeState = ProfileWriteState() },
                    onWrite = { key, value ->
                        writes += key to value
                        writeState = ProfileWriteState(result = ProfileWriteResult.REJECTED, binding = state.binding(target))
                    },
                )
            }
        }

        compose.onNodeWithTag("profile_field_display-name").performScrollTo().performClick()
        val value = compose.onNodeWithTag("profile_value")
        value.performScrollTo().performTextReplacement("Unpublished draft")
        compose.runOnIdle { assertEquals(emptyList<Pair<ProfileKey, String?>>(), writes) }
        compose.onNodeWithTag("profile_publish").performScrollTo().performClick()
        compose.onNodeWithText("The server denied the change.").performScrollTo().assertIsDisplayed()
        value.performScrollTo().assertTextContains("Unpublished draft")
        compose.onNodeWithText("Original").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("profile_remove").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(ProfileKey.DISPLAY_NAME to "Unpublished draft", ProfileKey.DISPLAY_NAME to null), writes)
        }
    }

    @Test
    fun unsupportedConnectionDisablesProfileFields() {
        compose.setContent {
            MotdTheme {
                ProfileEditorContent(
                    target = ProfileTarget.Self(7),
                    state = ProfileEditorState("alice", 7, ProfileAccess.UNSUPPORTED, emptyMap()),
                    writeState = ProfileWriteState(),
                    onBack = {},
                    onOpenNetworkSettings = {},
                    onClearResult = {},
                    onWrite = { _, _ -> error("Unsupported connection cannot publish or remove values") },
                )
            }
        }

        compose.onNodeWithText("This connection does not support IRCv3 profile metadata.").assertIsDisplayed()
        for (field in ProfileKey.entries) {
            compose.onNodeWithTag("profile_field_${field.wireName}").assertIsNotEnabled()
        }
    }

    @Test
    fun revokingMetadataDisablesAvatarActionsAndRemovesPreview() {
        var access by mutableStateOf(ProfileAccess.WRITABLE)
        compose.setContent {
            MotdTheme {
                ProfileEditorContent(
                    target = ProfileTarget.Self(7),
                    state = ProfileEditorState("alice", 7, access, mapOf(ProfileKey.AVATAR to "https://example.org/avatar.png")),
                    writeState = ProfileWriteState(),
                    onBack = {},
                    onOpenNetworkSettings = {},
                    onClearResult = {},
                    onWrite = { _, _ -> error("Revoked access cannot publish or remove values") },
                )
            }
        }

        compose.onNodeWithTag("profile_field_avatar").performClick()
        compose.onNodeWithTag("avatar_url_preview").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { access = ProfileAccess.UNSUPPORTED }

        compose.onNodeWithTag("avatar_url_preview").assertDoesNotExist()
        compose.onNodeWithText("Unavailable").assertIsDisplayed()
        compose.onNodeWithText("Not set").assertDoesNotExist()
        compose.onNodeWithTag("profile_publish").assertIsNotEnabled()
        compose.onNodeWithTag("profile_remove").assertIsNotEnabled()
        compose.onNodeWithText("Upload image").assertIsNotEnabled()
    }

    @Test
    fun ordinaryChannelMemberSeesTheCurrentValueWithoutWriteControls() {
        compose.setContent {
            MotdTheme {
                ProfileEditorContent(
                    target = ProfileTarget.Channel(17),
                    state = ProfileEditorState("#motd", 7, ProfileAccess.READ_ONLY, mapOf(ProfileKey.DISPLAY_NAME to "MOTD Chat")),
                    writeState = ProfileWriteState(),
                    onBack = {},
                    onOpenNetworkSettings = {},
                    onClearResult = {},
                    onWrite = { _, _ -> error("Read-only channel cannot publish or remove values") },
                )
            }
        }

        compose.onNodeWithTag("profile_field_avatar").assertIsDisplayed()
        compose.onNodeWithTag("profile_field_status").assertDoesNotExist()
        compose.onNodeWithTag("profile_field_display-name").performScrollTo().performClick()
        compose.onNodeWithText("MOTD Chat").assertIsDisplayed()
        compose.onNodeWithTag("profile_publish").assertDoesNotExist()
        compose.onNodeWithTag("profile_remove").assertDoesNotExist()
    }

    @Test
    fun leavingAJoinedReadOnlyChannelHidesItsAvatarAndDisablesFieldNavigation() {
        val avatar = "https://example.org/channel.png"
        var state by mutableStateOf(ProfileEditorState("#motd", 7, ProfileAccess.READ_ONLY, mapOf(ProfileKey.AVATAR to avatar)))
        compose.setContent {
            MotdTheme {
                ProfileEditorContent(
                    target = ProfileTarget.Channel(17),
                    state = state,
                    writeState = ProfileWriteState(),
                    onBack = {},
                    onOpenNetworkSettings = {},
                    onClearResult = {},
                    onWrite = { _, _ -> error("Read-only channel cannot write") },
                )
            }
        }

        compose.onNodeWithTag("profile_field_avatar").performClick()
        compose.onNodeWithTag("avatar_url_preview").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state = state.copy(readable = false, values = emptyMap()) }
        compose.onNodeWithTag("avatar_url_preview").assertDoesNotExist()
        compose.onNodeWithText("Unavailable").assertIsDisplayed()
        compose.onNodeWithText("Not set").assertDoesNotExist()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("profile_field_avatar").assertIsNotEnabled()
        compose.onNodeWithTag("profile_field_display-name").assertIsNotEnabled()
        compose.onNodeWithText("Not set").assertDoesNotExist()
    }

    @Test
    fun restoresDraftForTheSameIdentityButDiscardsItAfterChannelRename() {
        val restoration = StateRestorationTester(compose)
        var state by mutableStateOf(ProfileEditorState("#old", 7, ProfileAccess.WRITABLE, emptyMap()))
        restoration.setContent {
            MotdTheme {
                ProfileEditorContent(
                    target = ProfileTarget.Channel(17),
                    state = state,
                    writeState = ProfileWriteState(),
                    onBack = {},
                    onOpenNetworkSettings = {},
                    onClearResult = {},
                    onWrite = { _, _ -> error("A restored draft cannot publish itself") },
                )
            }
        }

        val field = compose.onNodeWithTag("profile_field_display-name")
        val draft = compose.onNodeWithTag("profile_value")
        field.performClick()
        draft.performTextReplacement("Unpublished draft")
        restoration.emulateSavedInstanceStateRestore()
        draft.assertTextContains("Unpublished draft")

        compose.runOnIdle { state = state.copy(identity = "#OLD") }
        draft.assertTextContains("Unpublished draft")
        compose.runOnIdle {
            state = ProfileEditorState("#new", 7, ProfileAccess.WRITABLE, mapOf(ProfileKey.DISPLAY_NAME to "New name"))
        }
        draft.assertDoesNotExist()
        field.performClick()
        draft.assertTextContains("New name")
        compose.onNodeWithText("Unpublished draft").assertDoesNotExist()
    }

    @Test
    fun renamedChannelCannotWriteUntilPriorWriteFinishesAndDoesNotShowItsResult() {
        val target = ProfileTarget.Channel(17)
        var state by mutableStateOf(ProfileEditorState("#old", 7, ProfileAccess.WRITABLE, mapOf(ProfileKey.DISPLAY_NAME to "Old name")))
        var writeState by mutableStateOf(ProfileWriteState())
        compose.setContent {
            MotdTheme {
                ProfileEditorContent(
                    target = target,
                    state = state,
                    writeState = writeState,
                    onBack = {},
                    onOpenNetworkSettings = {},
                    onClearResult = { writeState = writeState.copy(result = null) },
                    onWrite = { _, _ -> writeState = ProfileWriteState(pending = true, binding = state.binding(target)) },
                )
            }
        }

        val field = compose.onNodeWithTag("profile_field_display-name")
        val publish = compose.onNodeWithTag("profile_publish")
        val remove = compose.onNodeWithTag("profile_remove")
        field.performClick()
        publish.performScrollTo().performClick()
        compose.runOnIdle {
            state = ProfileEditorState("#new", 7, ProfileAccess.WRITABLE, mapOf(ProfileKey.DISPLAY_NAME to "New name"))
        }
        field.performClick()
        publish.performScrollTo().assertIsNotEnabled()
        remove.assertIsNotEnabled()

        compose.runOnIdle {
            writeState = writeState.copy(pending = false, result = ProfileWriteResult.CONFIRMED)
        }
        publish.assertIsEnabled()
        remove.assertIsEnabled()
        compose.onNodeWithText("The server confirmed the change.").assertDoesNotExist()
        compose.onNodeWithTag("profile_value").performScrollTo().performTextReplacement("New draft")
        compose.onNodeWithTag("profile_value").assertTextContains("New draft")
    }

    @Test
    fun capturedAvatarCompletionSurvivesARecreatedConsumer() =
        runTest {
            val target = ProfileTarget.Channel(17)
            val state = ProfileEditorState("#motd", 7, ProfileAccess.WRITABLE, emptyMap())
            val validation = CompletableDeferred<Boolean>()
            val upload = ProfileEditorAvatarUpload(backgroundScope, { MutableStateFlow(state) }) { _, _, _ -> validation.await() }
            upload.open(target, state)
            val opened = checkNotNull(upload.request.value)
            val onUploadComplete = { url: String ->
                upload.complete(opened, url)
                upload.dismiss(opened)
            }
            val url = "https://example.org/upload.png"
            onUploadComplete(url)
            val completed = checkNotNull(upload.request.value)
            val previousConsumer = launch { upload.take(completed) }
            runCurrent()
            previousConsumer.cancelAndJoin()

            validation.complete(true)
            assertEquals(url, upload.take(completed))
            assertNull(upload.take(completed))
        }

    @Test
    fun avatarCompletionCannotApplyAfterRevocationOrToARenamedChannel() =
        runTest {
            val target = ProfileTarget.Channel(17)
            val state = ProfileEditorState("#motd", 7, ProfileAccess.WRITABLE, emptyMap())
            val live = MutableStateFlow(state)
            val upload = ProfileEditorAvatarUpload(backgroundScope, { live }) { _, _, _ -> false }
            upload.open(target, state)
            val revoked = checkNotNull(upload.request.value)
            runCurrent()
            live.value = state.copy(access = ProfileAccess.READ_ONLY)
            runCurrent()
            upload.complete(revoked, "https://example.org/revoked.png")
            assertNull(upload.request.value)

            live.value = state
            upload.open(target, state)
            val oldTarget = checkNotNull(upload.request.value)
            runCurrent()
            val renamed = state.copy(identity = "#new", normalizedIdentity = "#new")
            live.value = renamed
            runCurrent()
            assertNull(upload.request.value)
            upload.open(target, renamed)
            val newTarget = checkNotNull(upload.request.value)
            upload.complete(oldTarget, "https://example.org/old-target.png")
            assertEquals(newTarget, upload.request.value)

            // The authoritative route can revoke writes before the observer has published the change.
            upload.complete(newTarget, "https://example.org/no-longer-writable.png")
            assertNull(upload.take(checkNotNull(upload.request.value)))
            assertNull(upload.request.value)
        }
}
