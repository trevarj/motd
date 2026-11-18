package io.github.trevarj.motd.ui.settings

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.trevarj.motd.R
import io.github.trevarj.motd.appearance.launcherAliasFqcn
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioCacheStatus
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.data.emoji.EmojiDownloadProgress
import io.github.trevarj.motd.data.emoji.EmojiSequenceCatalog
import io.github.trevarj.motd.data.emoji.EmojiSets
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.LauncherIcon
import io.github.trevarj.motd.data.prefs.PaletteMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.prefs.customPalette
import io.github.trevarj.motd.data.prefs.paletteSelection
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.ui.components.AudioAttachmentPlayers
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.LinkPreviewCard
import io.github.trevarj.motd.ui.components.LocalAutomaticRemoteMedia
import io.github.trevarj.motd.ui.components.LocalRemoteAvatars
import io.github.trevarj.motd.ui.components.RemoteAvatarState
import io.github.trevarj.motd.ui.components.rememberMessageTimeFormatter
import io.github.trevarj.motd.ui.theme.EmojiRenderer
import io.github.trevarj.motd.ui.theme.LocalEmojiRenderer
import io.github.trevarj.motd.ui.theme.LocalEmojiSequenceCatalog
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.LocalTimestampConfig
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.TimestampConfig
import io.github.trevarj.motd.ui.theme.UiDropdownTrailingIcon
import io.github.trevarj.motd.ui.theme.UiIcon
import io.github.trevarj.motd.ui.theme.resolvedMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import io.github.trevarj.motd.ui.components.EmojiText as Text

@Composable
internal fun EmojiSetComparisonSample(
    setId: String,
    file: File?,
) {
    val context = LocalContext.current
    val providedCatalog = LocalEmojiSequenceCatalog.current
    val catalog = remember(context, providedCatalog) { providedCatalog ?: EmojiSequenceCatalog(context.applicationContext) }
    key(setId, file) {
        var failed by remember { mutableStateOf(false) }
        val renderer by produceState<EmojiRenderer?>(null) {
            if (file == null || setId == EmojiSets.SYSTEM_ID) return@produceState
            try {
                value =
                    withContext(Dispatchers.IO) {
                        EmojiRenderer(catalog.load(), setId, FontFamily(Font(file)))
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CompositionLocalProvider(LocalEmojiRenderer provides null) {
                Text(stringResource(R.string.settings_emoji_system), style = MaterialTheme.typography.labelMedium)
                Text(
                    stringResource(R.string.settings_emoji_sample),
                    Modifier.testTag("settings_emoji_system_sample"),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            if (setId != EmojiSets.SYSTEM_ID) {
                Text(
                    EmojiSets.find(setId)?.displayName.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (renderer != null) {
                    CompositionLocalProvider(LocalEmojiRenderer provides renderer) {
                        Text(
                            stringResource(R.string.settings_emoji_sample),
                            Modifier.testTag("settings_emoji_sample"),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                } else {
                    Text(
                        stringResource(if (failed) R.string.settings_emoji_sample_error else R.string.settings_emoji_loading),
                        Modifier.testTag("settings_emoji_sample_status"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Preview(name = "Emoji sets – System")
@Composable
private fun EmojiSystemSettingsPreview() {
    MotdTheme(syncSystemBars = false) {
        EmojiSetPickerContent(EmojiSets.SYSTEM_ID, EmojiSettingsState(loading = false))
    }
}

@Preview(name = "Emoji sets – missing restored set")
@Composable
private fun EmojiMissingSettingsPreview() {
    MotdTheme(syncSystemBars = false) {
        EmojiSetPickerContent("noto", EmojiSettingsState(loading = false, error = true))
    }
}

@Preview(name = "Emoji sets – cancellable download")
@Composable
private fun EmojiDownloadingSettingsPreview() {
    MotdTheme(syncSystemBars = false) {
        EmojiSetPickerContent(
            EmojiSets.SYSTEM_ID,
            EmojiSettingsState(loading = false, downloadingId = "noto"),
            EmojiDownloadProgress("noto", 1_000_000L, 10_673_480L),
        )
    }
}

@Composable
internal fun AppearanceSampleTheme(
    settings: Settings,
    appearance: AppearanceConfig,
    font: File?,
    content: @Composable () -> Unit,
) {
    val avatars = remember { RemoteAvatarState() }
    val selection = appearance.paletteSelection
    val mode = selection.resolvedMode(isSystemInDarkTheme())
    val choice = selection.choiceFor(mode == PaletteMode.DARK)
    MotdTheme(
        themePreset = choice.builtin,
        paletteMode = mode,
        customPalette = appearance.customPalette(choice),
        trueBlack = appearance.trueBlack,
        dynamicColor = settings.dynamicColor,
        useThemeTextColors = appearance.useThemeTextColors,
        adjustPaletteReadability = appearance.adjustPaletteReadability,
        layoutDensity = settings.layoutDensity,
        compactNickBrackets = settings.compactNickBrackets,
        nickColorsEnabled = settings.nickColorsEnabled,
        nickMentionStyle = settings.nickMentionStyle,
        nickColorPalette = settings.nickColorPalette,
        nickColorOverrides = emptyMap(),
        avatarStyle = settings.avatarStyle,
        inputCornerStyle = appearance.inputCornerStyle,
        avatarShape = appearance.avatarShape,
        chatCardCornerStyle = appearance.chatCardCornerStyle,
        mediaCornerStyle = appearance.mediaCornerStyle,
        uiIconSet = appearance.uiIconSet,
        nicknameBackgroundsEnabled = appearance.nicknameBackgroundsEnabled,
        messageBackgroundOpacityPercent = appearance.messageBackgroundOpacityPercent,
        messageBackgroundBlur = appearance.messageBackgroundBlur,
        surfaceEffects = appearance.surfaceEffects,
        uiFontScalePercent = appearance.uiFontScalePercent,
        fontChoice = appearance.fontChoice,
        customFontFile = font,
        timestampConfig = TimestampConfig(appearance.showTimestamps, appearance.timeFormat, appearance.customTimeFormatPattern),
        syncSystemBars = false,
    ) {
        CompositionLocalProvider(LocalRemoteAvatars provides avatars, LocalAutomaticRemoteMedia provides false, content = content)
    }
}

@Composable
internal fun IconSetPreview() {
    Column(Modifier.testTag("settings_icon_set_preview"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_preview), style = MaterialTheme.typography.bodyMedium, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            UiIcon(Icons.Filled.Menu, null, Modifier.size(32.dp).testTag("settings_icon_set_preview_menu"))
            UiIcon(Icons.Outlined.Search, null, Modifier.size(32.dp).testTag("settings_icon_set_preview_search"))
            UiIcon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(32.dp).testTag("settings_icon_set_preview_send"))
            UiIcon(Icons.Outlined.Settings, null, Modifier.size(32.dp).testTag("settings_icon_set_preview_settings"))
            UiIcon(Icons.Filled.PlayArrow, null, Modifier.size(32.dp).testTag("settings_icon_set_preview_play"))
            UiDropdownTrailingIcon(false, Modifier.size(32.dp).testTag("settings_icon_set_preview_dropdown"))
        }
        Text(stringResource(R.string.settings_icon_set_scope), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun LauncherArtworkPreview(icon: LauncherIcon) {
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { 96.dp.roundToPx() }
    val bitmap =
        remember(context, icon, pixels) {
            val info = context.packageManager.getActivityInfo(ComponentName(context.packageName, launcherAliasFqcn(icon)), PackageManager.MATCH_DISABLED_COMPONENTS)
            info.loadIcon(context.packageManager).toBitmap(pixels, pixels).asImageBitmap()
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("settings_launcher_preview")) {
        Image(bitmap, contentDescription = stringResource(R.string.settings_app_icon_section), modifier = Modifier.size(96.dp))
        Text(stringResource(R.string.settings_launcher_preview_mask), style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AvatarStylePreview(style: AvatarStyle) {
    Column(Modifier.testTag("settings_avatar_preview_${style.name.lowercase()}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (style == AvatarStyle.NONE) {
            Text(stringResource(R.string.settings_avatar_none_desc))
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Avatar("Alex", size = 64.dp, modifier = Modifier.testTag("settings_avatar_preview_person"))
                    Text("Alex")
                }
                Column {
                    Avatar("#garden", size = 64.dp, isChannel = true, modifier = Modifier.testTag("settings_avatar_preview_channel"))
                    Text("#garden")
                }
            }
        }
        Text(stringResource(R.string.settings_avatar_preview_scope), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun MediaShapePreview() {
    val title = stringResource(R.string.settings_media_shape_preview_title)
    val description = stringResource(R.string.settings_media_shape_preview_desc)
    val link = remember(title, description) { LinkPreview("https://shape-preview.invalid/link", title, description, imageUrl = null, siteName = "shape-preview.invalid") }
    val audio =
        remember {
            AudioAttachment(
                url = "https://shape-preview.invalid/voice.ogg",
                mimeType = "audio/ogg",
                durationMs = 42000,
                voice = true,
                waveform = AudioWaveform(listOf(3, 12, 25, 31, 18, 8, 21, 5)),
            )
        }
    val attachments = remember(audio) { listOf(audio) }
    val cached = remember(audio) { mapOf(audio.playbackId to AudioCacheStatus.CACHED) }
    val playback = remember { AudioPlaybackState() }
    Column(Modifier.testTag("settings_media_shape_preview"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_preview), style = MaterialTheme.typography.bodyMedium, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        LinkPreviewCard(link, loading = false, onClick = {}, modifier = Modifier.testTag("settings_media_shape_link"))
        AudioAttachmentPlayers(
            attachments,
            playback,
            networkId = null,
            isSelf = false,
            onToggle = { _, _ -> },
            onSeek = { _, _ -> },
            cacheStatuses = cached,
            detailsEnabled = false,
        )
    }
}

private val paletteExampleNames = listOf("Alex", "Sam", "Morgan", "River", "Casey", "Robin")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NickPalettePreview(enabled: Boolean) {
    Column(Modifier.testTag("settings_palette_preview"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (name in paletteExampleNames) {
                Text(name, color = LocalNickColors.current.nick(name, MaterialTheme.colorScheme.onSurface))
            }
        }
        if (!enabled) Text(stringResource(R.string.settings_palette_preview_disabled), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun TimestampExample(
    appearance: AppearanceConfig,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val instant =
        remember(locale) {
            Calendar
                .getInstance(locale)
                .apply {
                    clear()
                    set(2024, Calendar.JANUARY, 2, 15, 4, 5)
                }.timeInMillis
        }
    CompositionLocalProvider(LocalTimestampConfig provides TimestampConfig(appearance.showTimestamps, appearance.timeFormat, appearance.customTimeFormatPattern)) {
        val formatted = rememberMessageTimeFormatter()(instant)
        Column(modifier.testTag("settings_time_preview"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_time_preview_context))
            Text(formatted, modifier = Modifier.testTag("settings_time_preview_value"))
            if (!appearance.showTimestamps) Text(stringResource(R.string.settings_time_preview_hidden), style = MaterialTheme.typography.bodySmall)
            if (appearance.timeFormat == TimeFormat.CUSTOM) Text(stringResource(R.string.settings_time_preview_custom_fallback), style = MaterialTheme.typography.bodySmall)
        }
    }
}
