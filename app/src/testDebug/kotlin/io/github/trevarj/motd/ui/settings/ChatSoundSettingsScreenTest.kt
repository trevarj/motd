package io.github.trevarj.motd.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.prefs.ChatSoundConfig
import io.github.trevarj.motd.data.prefs.ChatSoundMelody
import io.github.trevarj.motd.data.prefs.ChatSoundTone
import io.github.trevarj.motd.data.prefs.ChatSoundVariation
import io.github.trevarj.motd.data.prefs.ChatSoundVoice
import io.github.trevarj.motd.ui.nav.ChatSoundCue
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatSoundSettingsScreenTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun overviewKeepsMasterOffAndNavigatesToSeparatedEditors() {
        val config = mutableStateOf(ChatSoundConfig(receive = ChatSoundConfig().receive.copy(enabled = false)))
        val enabled = mutableStateOf(false)
        val opened = mutableListOf<ChatSoundCue>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatSoundSettingsContent(
                    enabled = enabled.value,
                    config = config.value,
                    onBack = {},
                    onEnabled = { enabled.value = it },
                    onConfig = { transform -> config.value = transform(config.value) },
                    onOpenCue = opened::add,
                )
            }
        }

        compose.onNodeWithTag("chat_sound_settings_enabled_row").assertIsOff()
        compose.onNodeWithTag("chat_sound_settings_variation").performClick()
        compose.onNodeWithTag("chat_sound_settings_variation_musical").assertIsSelected()
        compose.onNodeWithTag("chat_sound_settings_variation_natural").performClick()
        compose.onNodeWithTag("chat_sound_settings_send_editor").performScrollTo().performClick()
        compose.onNodeWithTag("chat_sound_settings_receive_editor").performScrollTo().performClick()
        compose.onNodeWithTag("chat_sound_settings_reset").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(listOf(ChatSoundCue.SEND, ChatSoundCue.RECEIVE), opened)
            assertEquals(ChatSoundConfig(), config.value)
            assertFalse(enabled.value)
        }
    }

    @Test
    fun sendEditorChangesOnlySendAndStopsPreviewOnBack() {
        val config = mutableStateOf(ChatSoundConfig())
        var previews = 0
        var stops = 0
        var backs = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatSoundCueEditorContent(
                    cueKind = ChatSoundCue.SEND,
                    config = config.value,
                    onBack = { backs++ },
                    onConfig = { transform -> config.value = transform(config.value) },
                    onPreview = { previews++ },
                    onStop = { stops++ },
                )
            }
        }

        compose.onNodeWithTag("chat_sound_send_voice").performClick()
        compose.onNodeWithTag("chat_sound_send_voice_terminal_tick").performClick()
        compose.onNodeWithTag("chat_sound_send_tone").performClick()
        compose.onNodeWithTag("chat_sound_send_tone_bright").performClick()
        compose.onNodeWithTag("chat_sound_receive_melody").assertDoesNotExist()
        compose.onNodeWithTag("chat_sound_send_preview").performScrollTo().performClick()
        compose.onNodeWithTag("chat_sound_send_stop").performScrollTo().performClick()
        compose.onNodeWithTag("settings_back").performClick()

        compose.runOnIdle {
            assertEquals(ChatSoundVoice.TERMINAL_TICK, config.value.send.voice)
            assertEquals(ChatSoundTone.BRIGHT, config.value.send.tone)
            assertEquals(ChatSoundVoice.SOFT_GLASS, config.value.receive.voice)
            assertEquals(1, previews)
            assertEquals(2, stops)
            assertEquals(1, backs)
        }
    }

    @Test
    fun receiveEditorOwnsMelodyAndLeavesSendSelectionUntouched() {
        val config = mutableStateOf(ChatSoundConfig(send = ChatSoundConfig().send.copy(voice = ChatSoundVoice.ARCADE_PLUCK)))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatSoundCueEditorContent(
                    cueKind = ChatSoundCue.RECEIVE,
                    config = config.value,
                    onBack = {},
                    onConfig = { transform -> config.value = transform(config.value) },
                    onPreview = {},
                )
            }
        }

        compose.onNodeWithTag("chat_sound_receive_voice").performClick()
        compose.onNodeWithTag("chat_sound_receive_voice_synth_16_bit").performClick()
        compose.onNodeWithTag("chat_sound_receive_melody").performScrollTo().performClick()
        compose.onNodeWithTag("chat_sound_receive_melody_victory").performClick()

        compose.runOnIdle {
            assertEquals(ChatSoundVoice.ARCADE_PLUCK, config.value.send.voice)
            assertEquals(ChatSoundVoice.SYNTH_16_BIT, config.value.receive.voice)
            assertEquals(ChatSoundMelody.VICTORY, config.value.receiveMelody)
        }
    }

    @Test
    fun receiveMelodyIsDisabledOutsideMusicalVariation() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatSoundCueEditorContent(
                    cueKind = ChatSoundCue.RECEIVE,
                    config = ChatSoundConfig(variation = ChatSoundVariation.FIXED),
                    onBack = {},
                    onConfig = {},
                    onPreview = {},
                )
            }
        }

        compose.onNodeWithTag("chat_sound_receive_melody").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Choose Musical variation to use a receive melody.").assertExists()
    }
}
