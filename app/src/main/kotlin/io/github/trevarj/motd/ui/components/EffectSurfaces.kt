package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import io.github.trevarj.motd.data.prefs.EffectSurface
import io.github.trevarj.motd.ui.theme.LocalSurfaceEffects
import androidx.compose.material3.DropdownMenu as NativeDropdownMenu
import androidx.compose.material3.ModalBottomSheet as NativeModalBottomSheet

internal val LocalPopupBackdrop = staticCompositionLocalOf<SurfaceBackdropSource?> { null }

@Composable
internal fun SurfaceBackdropRoot(
    content: @Composable () -> Unit,
    overlays: @Composable () -> Unit,
) {
    val fallback = rememberSurfaceBackdropSource()
    CompositionLocalProvider(LocalPopupBackdrop provides fallback, LocalSurfaceBackdrop provides null) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().captureSurfaceBackdrop(fallback)) { content() }
            CompositionLocalProvider(LocalSurfaceBackdrop provides fallback, LocalPopupBackdrop provides fallback) { overlays() }
        }
    }
}

@Composable
internal fun SurfaceBackdropPage(
    source: SurfaceBackdropSource,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSurfaceBackdrop provides source, LocalPopupBackdrop provides source, content = content)
}

internal val LocalSurfaceBackdrop = staticCompositionLocalOf<SurfaceBackdropSource?> { null }

@Composable
internal fun Modifier.effectSurface(
    surface: EffectSurface,
    tint: Color,
    shape: Shape = androidx.compose.ui.graphics.RectangleShape,
): Modifier {
    val effects = LocalSurfaceEffects.current
    val settings = effects[surface]
    val source = LocalSurfaceBackdrop.current ?: rememberSurfaceBackdropSource()
    return surfaceBackdrop(source, tint, settings.opacityPercent, settings.blurPercent, effects.enabled, shape)
}

@Composable
internal fun Modifier.popupEffectSurface(
    tint: Color,
    shape: Shape = MaterialTheme.shapes.extraSmall,
): Modifier {
    val source = LocalPopupBackdrop.current ?: LocalSurfaceBackdrop.current ?: rememberSurfaceBackdropSource()
    val effects = LocalSurfaceEffects.current
    val settings = effects[EffectSurface.MENU]
    return surfaceBackdrop(source, tint, settings.opacityPercent, settings.blurPercent, effects.enabled, shape)
}

/** Keep M3's popup window, focus, dismissal, scrolling and transition behavior. */
@Composable
internal fun EffectDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = MaterialTheme.shapes.extraSmall,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    tonalElevation: Dp = androidx.compose.material3.MenuDefaults.TonalElevation,
    shadowElevation: Dp = androidx.compose.material3.MenuDefaults.ShadowElevation,
    border: androidx.compose.foundation.BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = LocalPopupBackdrop.current ?: LocalSurfaceBackdrop.current ?: rememberSurfaceBackdropSource()
    val effects = LocalSurfaceEffects.current
    val settings = effects[EffectSurface.MENU]
    NativeDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.surfaceBackdrop(source, containerColor, settings.opacityPercent, settings.blurPercent, effects.enabled, shape),
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = shape,
        containerColor = Color.Transparent,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
        content = content,
    )
}

/** Paint at the native moving Surface, not at the public modifier's pre-drag plane. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EffectModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = androidx.compose.material3.contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val parent = LocalPopupBackdrop.current ?: LocalSurfaceBackdrop.current ?: rememberSurfaceBackdropSource()
    val source = rememberSurfaceBackdropSource(parent)
    val target = rememberSurfaceBackdropTarget()
    val effects = LocalSurfaceEffects.current
    val settings = effects[EffectSurface.SHEET]
    // Inline controls sample the parent; only independent popups consume this host's capture.
    CompositionLocalProvider(LocalSurfaceBackdrop provides parent, LocalPopupBackdrop provides source) {
        NativeModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier.captureSurfaceBackdrop(source).surfaceBackdrop(parent, containerColor, settings.opacityPercent, settings.blurPercent, effects.enabled, shape, target),
            sheetState = sheetState,
            sheetMaxWidth = sheetMaxWidth,
            sheetGesturesEnabled = sheetGesturesEnabled,
            shape = shape,
            containerColor = Color.Transparent,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            scrimColor = scrimColor,
            dragHandle = dragHandle,
            contentWindowInsets = contentWindowInsets,
            properties = properties,
        ) {
            Spacer(Modifier.fillMaxWidth().height(0.dp).surfaceBackdropTargetMarker(target))
            content()
        }
    }
}

private val TranslucentFabElevation =
    androidx.compose.material3.FloatingActionButtonDefaults
        .bottomAppBarFabElevation()

@Composable
internal fun EffectFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = androidx.compose.material3.FloatingActionButtonDefaults.shape,
    containerColor: Color = androidx.compose.material3.FloatingActionButtonDefaults.containerColor,
    contentColor: Color = androidx.compose.material3.contentColorFor(containerColor),
    elevation: androidx.compose.material3.FloatingActionButtonElevation =
        androidx.compose.material3.FloatingActionButtonDefaults
            .elevation(),
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val effects = LocalSurfaceEffects.current
    val opacity = effects[EffectSurface.FLOATING_BUTTON].opacityPercent.coerceIn(0, 100)
    val opaque = opacity == 100 || (!effects.enabled && opacity > 0)
    // A transparent elevated native Surface can composite its shadow through the glass backing.
    androidx.compose.material3.FloatingActionButton(
        onClick = onClick,
        modifier = if (opaque) modifier else modifier.effectSurface(EffectSurface.FLOATING_BUTTON, containerColor, shape),
        shape = shape,
        containerColor = if (opaque) containerColor.copy(alpha = 1f) else Color.Transparent,
        contentColor = contentColor,
        elevation = if (opaque) elevation else TranslucentFabElevation,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
internal fun EffectModalDrawerSheet(
    modifier: Modifier = Modifier,
    drawerShape: Shape = androidx.compose.material3.DrawerDefaults.shape,
    drawerContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    drawerContentColor: Color = androidx.compose.material3.contentColorFor(drawerContainerColor),
    drawerTonalElevation: Dp = 0.dp,
    windowInsets: WindowInsets = androidx.compose.material3.DrawerDefaults.windowInsets,
    content: @Composable ColumnScope.() -> Unit,
) {
    val parent = LocalSurfaceBackdrop.current ?: rememberSurfaceBackdropSource()
    val source = rememberSurfaceBackdropSource(parent)
    CompositionLocalProvider(LocalSurfaceBackdrop provides parent, LocalPopupBackdrop provides source) {
        val settings = LocalSurfaceEffects.current
        val effect = settings[EffectSurface.DRAWER]
        androidx.compose.material3.ModalDrawerSheet(
            modifier = modifier.captureSurfaceBackdrop(source).surfaceBackdrop(parent, drawerContainerColor, effect.opacityPercent, effect.blurPercent, settings.enabled, drawerShape),
            drawerShape = drawerShape,
            drawerContainerColor = Color.Transparent,
            drawerContentColor = drawerContentColor,
            drawerTonalElevation = drawerTonalElevation,
            windowInsets = windowInsets,
            content = content,
        )
    }
}
