package io.github.trevarj.motd.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.ui.components.MetadataPill
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import io.github.trevarj.motd.ui.theme.ensureContrast
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import io.github.trevarj.motd.ui.components.EffectModalBottomSheet as ModalBottomSheet
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

/** Shared Material 3 shell for settings-style pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    topActions: @Composable RowScope.() -> Unit = {},
    status: (@Composable () -> Unit)? = null,
    scroll: Boolean = true,
    pagePadding: Boolean = true,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.onboarding_back))
                    }
                },
                actions = topActions,
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val base =
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .imePadding()
                    .testTag("settings_scroll")
                    .then(if (pagePadding) Modifier.padding(horizontal = 16.dp, vertical = 12.dp) else Modifier)
            Column(
                modifier = if (scroll) base.verticalScroll(rememberScrollState()) else base.fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                status?.invoke()
                content()
            }
        }
    }
}

@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        title?.let { SectionHeader(it) }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) { Column { content() } }
    }
}

@Composable
internal fun SettingsNavigationRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    requestedTarget: String? = null,
    targetName: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    val row: @Composable (Modifier) -> Unit = { targetModifier ->
        ListItem(
            leadingContent = icon?.let { image -> ({ Icon(image, contentDescription = null, tint = iconTint) }) },
            headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
            supportingContent =
                if (summary != null || value != null) {
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            summary?.let { Text(it) }
                            value?.let { MetadataPill(it) }
                        }
                    }
                } else {
                    null
                },
            trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier =
                modifier
                    .then(targetModifier)
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = enabled, onClick = onClick)
                    .semantics { role = Role.Button },
        )
    }
    if (targetName != null) SettingsTarget(requestedTarget, targetName, row) else row(Modifier)
}

@Composable
internal fun SettingsValueRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    compactValue: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                summary?.let { Text(it) }
                if (compactValue) MetadataPill(value) else Text(value)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun SettingsActionRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(title, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
        },
        supportingContent = summary?.let { text -> ({ Text(text) }) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).semantics { role = Role.Button },
    )
}

@Composable
internal fun RadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    supportingContent: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val targetColor = LocalSettingsTargetColor.current
    val contentColor =
        if (selected && targetColor.alpha > 0f) {
            val fill = targetColor.compositeOver(scheme.secondaryContainer)
            remember(scheme.onSecondaryContainer, fill) { ensureContrast(scheme.onSecondaryContainer, listOf(fill)) }
        } else if (selected) {
            scheme.onSecondaryContainer
        } else {
            scheme.onSurface
        }
    ListItem(
        modifier =
            Modifier
                .background(if (selected) scheme.secondaryContainer else Color.Transparent)
                .then(modifier)
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        leadingContent = {
            RadioButton(
                selected,
                onClick = null,
                enabled = enabled,
                colors = RadioButtonDefaults.colors(selectedColor = contentColor),
            )
        },
        headlineContent = {
            Text(label, color = contentColor.copy(alpha = if (enabled) 1f else 0.38f))
        },
        supportingContent =
            if (subtitle != null || supportingContent != null) {
                {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        supportingContent?.invoke()
                    }
                }
            } else {
                null
            },
        colors =
            ListItemDefaults.colors(
                containerColor = Color.Transparent,
                supportingColor = if (selected) contentColor else scheme.onSurfaceVariant,
            ),
    )
}

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    switchTag: String? = null,
    enabled: Boolean = true,
    disabledExplanation: String? = null,
    requestedTarget: String? = null,
    targetName: String? = null,
) {
    val row: @Composable (Modifier) -> Unit = { targetModifier ->
        Row(
            modifier =
                modifier
                    .then(targetModifier)
                    .then(if (switchTag != null) Modifier.testTag("${switchTag}_row") else Modifier)
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
                Text(
                    if (!enabled && disabledExplanation != null) disabledExplanation else subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                modifier = switchTag?.let(Modifier::testTag) ?: Modifier,
            )
        }
    }
    if (targetName != null) SettingsTarget(requestedTarget, targetName, row) else row(Modifier)
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp).semantics { heading() },
    )
}

@Composable
internal fun SubLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp).semantics { heading() },
    )
}

@Composable
internal fun PersistentStatusNotice(
    text: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    if (error) error(text)
                },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text)
            if ((actionLabel != null && onAction != null) || onDismiss != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
                    if (onDismiss != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
                }
            }
        }
    }
}

internal data class ChoiceOption<T>(
    val value: T,
    val label: String,
    val summary: String? = null,
    val tag: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SingleChoiceSheet(
    title: String,
    selected: T,
    options: List<ChoiceOption<T>>,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    tag: String,
    dismissOnSelect: Boolean = true,
    preview: (@Composable () -> Unit)? = null,
    initiallyShowFooter: Boolean = false,
    footer: @Composable () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = preview != null),
        modifier = Modifier.testTag(tag),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        SheetSystemBars()
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (initiallyShowFooter) options.size + (if (preview == null) 1 else 2) else 0)
        LaunchedEffect(initiallyShowFooter) {
            if (initiallyShowFooter) listState.scrollToItem(options.size + if (preview == null) 1 else 2)
        }
        LazyColumn(Modifier.fillMaxWidth().selectableGroup().testTag("${tag}_options"), state = listState) {
            item { Box(Modifier.padding(horizontal = 12.dp)) { SectionHeader(title) } }
            if (preview != null) {
                item { Column(Modifier.fillMaxWidth().padding(16.dp)) { preview() } }
            }
            items(options) { option ->
                RadioRow(
                    label = option.label,
                    subtitle = option.summary,
                    selected = selected == option.value,
                    enabled = true,
                    onClick = {
                        onSelect(option.value)
                        if (dismissOnSelect) onDismiss()
                    },
                    modifier = option.tag?.let(Modifier::testTag) ?: Modifier,
                )
            }
            item { footer() }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
internal fun MessageBackgroundOpacitySlider(
    value: Int,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "settings_message_background_opacity",
) = BackingSlider(
    value,
    onValue,
    stringResource(R.string.settings_message_background_opacity),
    stringResource(R.string.settings_message_background_opacity_off),
    modifier,
    tag,
)

@Composable
internal fun MessageBackgroundBlurSlider(
    value: Int,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "settings_message_background_blur",
) {
    Column(modifier) {
        BackingSlider(
            value,
            onValue,
            stringResource(R.string.settings_message_background_blur),
            stringResource(R.string.settings_message_background_blur_off),
            Modifier,
            tag,
        )
        Text(
            stringResource(R.string.settings_message_background_blur_desc),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Keep the native touch/semantics target; only the visible track is slimmed down. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackingSlider(
    value: Int,
    onValue: (Int) -> Unit,
    title: String,
    offLabel: String,
    modifier: Modifier,
    tag: String,
    accessibilityLabel: String = title,
) {
    val bounded = value.coerceIn(0, 100)
    val interactionSource = remember { MutableInteractionSource() }
    val percent =
        if (bounded == 0) offLabel else stringResource(R.string.settings_font_size_percent, bounded)
    Column(modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(percent, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = bounded.toFloat(),
            onValueChange = { onValue(it.roundToInt().coerceIn(0, 100)) },
            valueRange = 0f..100f,
            interactionSource = interactionSource,
            thumb = {
                Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                    SliderDefaults.Thumb(interactionSource = interactionSource, thumbSize = DpSize(20.dp, 20.dp))
                }
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(4.dp),
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 2.dp,
                )
            },
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).semantics {
                    contentDescription = accessibilityLabel
                    stateDescription = percent
                },
        )
    }
}

private val LocalSettingsTargetColor = compositionLocalOf { Color.Transparent }

/** Brings matching control into view and briefly highlights it. */
@Composable
internal fun SettingsTarget(
    requested: String?,
    target: String,
    content: @Composable (Modifier) -> Unit,
) {
    val requester = remember { BringIntoViewRequester() }
    var highlighted by remember(requested, target) { mutableStateOf(false) }
    val color by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent,
        label = "settings_target_highlight",
    )
    LaunchedEffect(requested, target) {
        if (requested == target) {
            requester.bringIntoView()
            highlighted = true
            delay(1_500)
            highlighted = false
        }
    }
    CompositionLocalProvider(LocalSettingsTargetColor provides color) {
        content(
            Modifier
                .bringIntoViewRequester(requester)
                .background(color)
                .then(if (requested == target) Modifier.testTag("settings_target_highlight_$target") else Modifier),
        )
    }
}

/** "host:port" plus bouncer role context. */
@Composable
internal fun networkSupporting(
    network: NetworkEntity,
    all: List<NetworkEntity>,
    zncNetworkIds: Set<Long> = emptySet(),
): String {
    val base = "${network.host}:${network.port}"
    return when (network.role) {
        NetworkRole.BOUNCER_ROOT -> {
            stringResource(R.string.settings_network_soju_suffix, base)
        }

        NetworkRole.BOUNCER_CHILD -> {
            val rootName = all.firstOrNull { it.id == network.parentId }?.name
            if (rootName != null) stringResource(R.string.settings_network_via_suffix, base, rootName) else base
        }

        NetworkRole.DIRECT -> {
            if (network.id in zncNetworkIds) stringResource(R.string.settings_network_znc_suffix, base) else base
        }
    }
}

@Composable
internal fun countText(count: Int): (@Composable () -> Unit)? = if (count > 0) ({ Text(pluralStringResource(R.plurals.settings_nick_count, count, count)) }) else null
