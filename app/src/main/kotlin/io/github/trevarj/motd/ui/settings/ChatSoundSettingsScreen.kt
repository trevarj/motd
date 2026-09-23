package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.ChatSoundConfig
import io.github.trevarj.motd.data.prefs.ChatSoundCueConfig
import io.github.trevarj.motd.data.prefs.ChatSoundMelody
import io.github.trevarj.motd.data.prefs.ChatSoundPrefs
import io.github.trevarj.motd.data.prefs.ChatSoundTone
import io.github.trevarj.motd.data.prefs.ChatSoundVariation
import io.github.trevarj.motd.data.prefs.ChatSoundVoice
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.service.AndroidChatSoundPlayer
import io.github.trevarj.motd.ui.nav.ChatSoundCue
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.absoluteValue

data class ChatSoundSettingsUiState(
    val enabled: Boolean = false,
    val config: ChatSoundConfig = ChatSoundConfig(),
)

@HiltViewModel
class ChatSoundSettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val chatSoundPrefs: ChatSoundPrefs,
        private val chatSoundPlayer: AndroidChatSoundPlayer,
    ) : ViewModel() {
        val state =
            combine(settingsRepository.settings, chatSoundPrefs.config) { settings, config ->
                ChatSoundSettingsUiState(enabled = settings.chatSoundsEnabled, config = config)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatSoundSettingsUiState())

        fun setEnabled(value: Boolean) = viewModelScope.launch { settingsRepository.setChatSoundsEnabled(value) }

        fun update(transform: (ChatSoundConfig) -> ChatSoundConfig) =
            viewModelScope.launch {
                chatSoundPlayer.stopPreview()
                chatSoundPrefs.update(transform)
            }

        fun preview(
            cue: ChatSoundCue,
            config: ChatSoundConfig,
        ) {
            when (cue) {
                ChatSoundCue.SEND -> chatSoundPlayer.previewSend(config)
                ChatSoundCue.RECEIVE -> chatSoundPlayer.previewReceiveMelody(config)
            }
        }

        fun stopPreview() = chatSoundPlayer.stopPreview()
    }

@Composable
fun ChatSoundSettingsScreen(
    onBack: () -> Unit = {},
    onOpenCue: (ChatSoundCue) -> Unit = {},
    viewModel: ChatSoundSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChatSoundSettingsContent(
        enabled = state.enabled,
        config = state.config,
        onBack = onBack,
        onEnabled = viewModel::setEnabled,
        onConfig = viewModel::update,
        onOpenCue = onOpenCue,
        onStop = viewModel::stopPreview,
    )
}

@Composable
fun ChatSoundSettingsContent(
    enabled: Boolean,
    config: ChatSoundConfig,
    onBack: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onConfig: ((ChatSoundConfig) -> ChatSoundConfig) -> Unit,
    onOpenCue: (ChatSoundCue) -> Unit,
    onStop: () -> Unit = {},
) {
    var variationSheetOpen by remember { mutableStateOf(false) }
    StopChatSoundPreviewsOnLeave(onStop)
    SettingsScaffold(
        title = stringResource(R.string.settings_chat_sounds),
        onBack = {
            onStop()
            onBack()
        },
    ) {
        SettingsGroup(title = stringResource(R.string.settings_chat_sounds_playback)) {
            SwitchRow(
                title = stringResource(R.string.settings_chat_sounds),
                subtitle = stringResource(R.string.settings_chat_sounds_desc),
                checked = enabled,
                onCheckedChange = onEnabled,
                switchTag = "chat_sound_settings_enabled",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SoundSlider(
                label = stringResource(R.string.settings_chat_sounds_master_volume),
                value = config.masterVolume,
                range = 0..100,
                tag = "chat_sound_settings_master_volume",
            ) { value -> onConfig { it.copy(masterVolume = value) } }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.settings_chat_sounds_variation),
                value = chatSoundVariationLabel(config.variation),
                modifier = Modifier.testTag("chat_sound_settings_variation"),
                onClick = { variationSheetOpen = true },
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_chat_sounds_cues)) {
            SettingsNavigationRow(
                title = stringResource(R.string.settings_chat_sounds_send),
                summary = chatSoundCueSummary(config.send),
                modifier = Modifier.testTag("chat_sound_settings_send_editor"),
                onClick = {
                    onStop()
                    onOpenCue(ChatSoundCue.SEND)
                },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.settings_chat_sounds_receive),
                summary = chatSoundCueSummary(config.receive),
                value = if (config.variation == ChatSoundVariation.MUSICAL) chatSoundMelodyLabel(config.receiveMelody) else null,
                modifier = Modifier.testTag("chat_sound_settings_receive_editor"),
                onClick = {
                    onStop()
                    onOpenCue(ChatSoundCue.RECEIVE)
                },
            )
        }
        SettingsGroup {
            SettingsActionRow(
                title = stringResource(R.string.action_reset),
                summary = stringResource(R.string.settings_chat_sounds_reset_desc),
                modifier = Modifier.testTag("chat_sound_settings_reset"),
                onClick = { onConfig { ChatSoundConfig() } },
            )
        }
    }
    if (variationSheetOpen) {
        SingleChoiceSheet(
            title = stringResource(R.string.settings_chat_sounds_variation),
            selected = config.variation,
            options =
                ChatSoundVariation.entries.map { variation ->
                    ChoiceOption(
                        variation,
                        chatSoundVariationLabel(variation),
                        chatSoundVariationDescription(variation),
                        tag = "chat_sound_settings_variation_${variation.name.lowercase()}",
                    )
                },
            onSelect = { variation -> onConfig { it.copy(variation = variation) } },
            onDismiss = { variationSheetOpen = false },
            tag = "chat_sound_settings_variation_sheet",
        )
    }
}

@Composable
fun ChatSoundCueEditorScreen(
    cue: ChatSoundCue,
    onBack: () -> Unit = {},
    viewModel: ChatSoundSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChatSoundCueEditorContent(
        cueKind = cue,
        config = state.config,
        onBack = onBack,
        onConfig = viewModel::update,
        onPreview = { viewModel.preview(cue, state.config) },
        onStop = viewModel::stopPreview,
    )
}

@Composable
fun ChatSoundCueEditorContent(
    cueKind: ChatSoundCue,
    config: ChatSoundConfig,
    onBack: () -> Unit,
    onConfig: ((ChatSoundConfig) -> ChatSoundConfig) -> Unit,
    onPreview: () -> Unit,
    onStop: () -> Unit = {},
) {
    val cue = if (cueKind == ChatSoundCue.SEND) config.send else config.receive
    var voiceSheetOpen by remember { mutableStateOf(false) }
    var toneSheetOpen by remember { mutableStateOf(false) }
    var melodySheetOpen by remember { mutableStateOf(false) }

    fun updateCue(transform: (ChatSoundCueConfig) -> ChatSoundCueConfig) {
        onConfig {
            if (cueKind == ChatSoundCue.SEND) it.copy(send = transform(it.send)) else it.copy(receive = transform(it.receive))
        }
    }
    StopChatSoundPreviewsOnLeave(onStop)
    SettingsScaffold(
        title = if (cueKind == ChatSoundCue.SEND) stringResource(R.string.settings_chat_sounds_send) else stringResource(R.string.settings_chat_sounds_receive),
        onBack = {
            onStop()
            onBack()
        },
    ) {
        SettingsGroup(title = stringResource(R.string.settings_chat_sounds_cue)) {
            SwitchRow(
                title = stringResource(R.string.settings_chat_sounds_enabled),
                subtitle = stringResource(R.string.settings_chat_sounds_cue_enabled_desc),
                checked = cue.enabled,
                onCheckedChange = { value -> updateCue { it.copy(enabled = value) } },
                switchTag = "chat_sound_${cueKind.name.lowercase()}_enabled",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.settings_chat_sounds_voice),
                value = chatSoundVoiceLabel(cue.voice),
                modifier = Modifier.testTag("chat_sound_${cueKind.name.lowercase()}_voice"),
                onClick = { voiceSheetOpen = true },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.settings_chat_sounds_tone),
                value = chatSoundToneLabel(cue.tone),
                modifier = Modifier.testTag("chat_sound_${cueKind.name.lowercase()}_tone"),
                onClick = { toneSheetOpen = true },
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_chat_sounds_tuning)) {
            SoundSlider(
                label = stringResource(R.string.settings_chat_sounds_cue_volume),
                value = cue.volume,
                range = 0..100,
                tag = "chat_sound_${cueKind.name.lowercase()}_volume",
            ) { value -> updateCue { it.copy(volume = value) } }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SoundSlider(
                label = stringResource(R.string.settings_chat_sounds_pitch),
                value = cue.pitch,
                range = -4..4,
                tag = "chat_sound_${cueKind.name.lowercase()}_pitch",
                valueLabel = { value ->
                    pluralStringResource(
                        R.plurals.settings_chat_sounds_pitch_value,
                        value.absoluteValue,
                        value,
                    )
                },
            ) { value -> updateCue { it.copy(pitch = value) } }
        }
        if (cueKind == ChatSoundCue.RECEIVE) {
            SettingsGroup(title = stringResource(R.string.settings_chat_sounds_receive_phrase)) {
                ListItem(headlineContent = { Text(stringResource(R.string.settings_chat_sounds_burst_hint)) })
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavigationRow(
                    title = stringResource(R.string.settings_chat_sounds_melody),
                    summary = if (config.variation == ChatSoundVariation.MUSICAL) null else stringResource(R.string.settings_chat_sounds_melody_musical_hint),
                    value = chatSoundMelodyLabel(config.receiveMelody),
                    enabled = config.variation == ChatSoundVariation.MUSICAL,
                    modifier = Modifier.testTag("chat_sound_receive_melody"),
                    onClick = { melodySheetOpen = true },
                )
            }
        }
        SettingsGroup(title = stringResource(R.string.settings_chat_sounds_preview)) {
            SettingsActionRow(
                title = stringResource(if (cueKind == ChatSoundCue.SEND) R.string.settings_chat_sounds_preview_send else R.string.settings_chat_sounds_preview_receive),
                modifier = Modifier.testTag("chat_sound_${cueKind.name.lowercase()}_preview"),
                onClick = onPreview,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsActionRow(
                title = stringResource(R.string.action_stop),
                modifier = Modifier.testTag("chat_sound_${cueKind.name.lowercase()}_stop"),
                onClick = onStop,
            )
        }
    }
    if (voiceSheetOpen) {
        SingleChoiceSheet(
            title = stringResource(R.string.settings_chat_sounds_voice),
            selected = cue.voice,
            options = ChatSoundVoice.entries.map { voice -> ChoiceOption(voice, chatSoundVoiceLabel(voice), tag = "chat_sound_${cueKind.name.lowercase()}_voice_${voice.name.lowercase()}") },
            onSelect = { voice -> updateCue { it.copy(voice = voice) } },
            onDismiss = { voiceSheetOpen = false },
            tag = "chat_sound_${cueKind.name.lowercase()}_voice_sheet",
        )
    }
    if (toneSheetOpen) {
        SingleChoiceSheet(
            title = stringResource(R.string.settings_chat_sounds_tone),
            selected = cue.tone,
            options = ChatSoundTone.entries.map { tone -> ChoiceOption(tone, chatSoundToneLabel(tone), tag = "chat_sound_${cueKind.name.lowercase()}_tone_${tone.name.lowercase()}") },
            onSelect = { tone -> updateCue { it.copy(tone = tone) } },
            onDismiss = { toneSheetOpen = false },
            tag = "chat_sound_${cueKind.name.lowercase()}_tone_sheet",
        )
    }
    if (melodySheetOpen) {
        SingleChoiceSheet(
            title = stringResource(R.string.settings_chat_sounds_melody),
            selected = config.receiveMelody,
            options = ChatSoundMelody.entries.map { melody -> ChoiceOption(melody, chatSoundMelodyLabel(melody), tag = "chat_sound_receive_melody_${melody.name.lowercase()}") },
            onSelect = { melody -> onConfig { it.copy(receiveMelody = melody) } },
            onDismiss = { melodySheetOpen = false },
            tag = "chat_sound_receive_melody_sheet",
        )
    }
}

@Composable
private fun SoundSlider(
    label: String,
    value: Int,
    range: IntRange,
    tag: String,
    valueLabel: @Composable (Int) -> String = { item -> stringResource(R.string.settings_chat_sounds_volume_value, item) },
    onValue: (Int) -> Unit,
) {
    ListItem(
        headlineContent = { Text("$label: ${valueLabel(value)}") },
        supportingContent = {
            Slider(
                value = value.toFloat(),
                onValueChange = { onValue(it.toInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first - 1).coerceAtLeast(0),
                modifier = Modifier.testTag(tag).semantics { contentDescription = label },
            )
        },
    )
}

@Composable
private fun chatSoundCueSummary(cue: ChatSoundCueConfig): String =
    if (!cue.enabled) {
        stringResource(R.string.settings_chat_sounds_disabled)
    } else {
        stringResource(
            R.string.settings_chat_sounds_cue_summary,
            chatSoundVoiceLabel(cue.voice),
            chatSoundToneLabel(cue.tone),
            stringResource(R.string.settings_chat_sounds_volume_value, cue.volume),
            pluralStringResource(
                R.plurals.settings_chat_sounds_pitch_value,
                cue.pitch.absoluteValue,
                cue.pitch,
            ),
        )
    }

@Composable
private fun chatSoundVoiceLabel(value: ChatSoundVoice): String =
    stringResource(
        when (value) {
            ChatSoundVoice.SOFT_GLASS -> R.string.settings_chat_sounds_voice_soft_glass
            ChatSoundVoice.TERMINAL_TICK -> R.string.settings_chat_sounds_voice_terminal_tick
            ChatSoundVoice.ARCADE_PLUCK -> R.string.settings_chat_sounds_voice_arcade_pluck
            ChatSoundVoice.SYNTH_16_BIT -> R.string.settings_chat_sounds_voice_16bit_synth
        },
    )

@Composable
private fun chatSoundToneLabel(value: ChatSoundTone): String =
    stringResource(
        when (value) {
            ChatSoundTone.WARM -> R.string.settings_chat_sounds_tone_warm
            ChatSoundTone.BALANCED -> R.string.settings_chat_sounds_tone_balanced
            ChatSoundTone.BRIGHT -> R.string.settings_chat_sounds_tone_bright
        },
    )

@Composable
private fun chatSoundVariationLabel(value: ChatSoundVariation): String =
    stringResource(
        when (value) {
            ChatSoundVariation.MUSICAL -> R.string.settings_chat_sounds_variation_musical
            ChatSoundVariation.NATURAL -> R.string.settings_chat_sounds_variation_natural
            ChatSoundVariation.FIXED -> R.string.settings_chat_sounds_variation_fixed
        },
    )

@Composable
private fun chatSoundVariationDescription(value: ChatSoundVariation): String =
    stringResource(
        when (value) {
            ChatSoundVariation.MUSICAL -> R.string.settings_chat_sounds_variation_musical_desc
            ChatSoundVariation.NATURAL -> R.string.settings_chat_sounds_variation_natural_desc
            ChatSoundVariation.FIXED -> R.string.settings_chat_sounds_variation_fixed_desc
        },
    )

@Composable
private fun chatSoundMelodyLabel(value: ChatSoundMelody): String =
    stringResource(
        when (value) {
            ChatSoundMelody.HOMECOMING -> R.string.settings_chat_sounds_melody_homecoming
            ChatSoundMelody.CLIMB -> R.string.settings_chat_sounds_melody_climb
            ChatSoundMelody.RELAY -> R.string.settings_chat_sounds_melody_relay
            ChatSoundMelody.BEACON -> R.string.settings_chat_sounds_melody_beacon
            ChatSoundMelody.VICTORY -> R.string.settings_chat_sounds_melody_victory
        },
    )

@Composable
private fun StopChatSoundPreviewsOnLeave(onStop: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnStop by rememberUpdatedState(onStop)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) latestOnStop() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            latestOnStop()
        }
    }
}
