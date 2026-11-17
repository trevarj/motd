package io.github.trevarj.motd.gesture.radial

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.PaletteMode
import io.github.trevarj.motd.data.prefs.customPalette
import io.github.trevarj.motd.data.prefs.paletteSelection
import io.github.trevarj.motd.data.prefs.resolvePalettePreset
import io.github.trevarj.motd.gesture.GestureAction
import io.github.trevarj.motd.gesture.GestureNavRequest
import io.github.trevarj.motd.ui.nav.ChannelInfoRoute
import io.github.trevarj.motd.ui.nav.ChatListRoute
import io.github.trevarj.motd.ui.nav.ChatRoute
import io.github.trevarj.motd.ui.nav.SearchRoute
import io.github.trevarj.motd.ui.nav.isChatRoutePattern
import io.github.trevarj.motd.ui.nav.openChat
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.resolvedMode
import io.github.trevarj.motd.ui.theme.themeIdentityPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How long the tab stays fully opaque after it was last touched. */
private const val ORB_IDLE_FADE_DELAY_MILLIS = 4_000L

/** Resting opacity once the tab has been left alone: present, but not part of the screen. */
private const val ORB_IDLE_ALPHA = 0.35f

/**
 * Mounts the gesture orb above the navigation graph.
 *
 * Lives outside the `NavHost` on purpose: the orb belongs to the app rather than to any destination,
 * and the navigation the menu asks for is performed here, where a `NavController` is in scope, from
 * requests the dispatcher published without one (the notification-target split in `MainActivity`).
 */
@Composable
fun GestureOrbHost(
    navController: NavHostController,
    viewModel: GestureOrbViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, navController) {
        viewModel.navRequests.collect { navController.perform(it) }
    }
    GestureOrbSurface(
        enabled = state.enabled,
        placement = state.placement,
        accent = gestureAccent(state.appearance),
        resolveMenu = viewModel::resolveMenu,
        onExecute = viewModel::execute,
        onPlacementChange = viewModel::setPlacement,
    )
}

/**
 * The orb and everything it can open, driven purely by its parameters.
 *
 * [resolveMenu] is called once per hold rather than collected: the ring has to be a snapshot of the
 * moment the gesture armed, or slices would move under a finger already travelling towards one.
 */
@Composable
internal fun GestureOrbSurface(
    enabled: Boolean,
    placement: OrbPlacement,
    accent: Color,
    resolveMenu: suspend () -> RadialEntry,
    onExecute: (GestureAction) -> Unit,
    onPlacementChange: (OrbPlacement) -> Unit,
    accessible: Boolean = rememberTouchExplorationEnabled(),
) {
    if (!enabled) return
    val metrics = rememberRadialMetrics()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val moreLabel = stringResource(R.string.gesture_menu_more)

    var menu by remember { mutableStateOf<RadialMenuState?>(null) }
    // Kept apart from [menu] so the ring can fade out while its last frame is still drawable.
    var menuVisible by remember { mutableStateOf(false) }
    var dragPlacement by remember { mutableStateOf<OrbPlacement?>(null) }
    var accessibleRoot by remember { mutableStateOf<RadialEntry?>(null) }
    var touches by remember { mutableIntStateOf(0) }
    var idle by remember { mutableStateOf(false) }
    // Identifies the hold a resolution belongs to. A hold released before its menu resolved must not
    // have that menu appear afterwards, with no finger left to close it.
    var holdId by remember { mutableIntStateOf(0) }

    LaunchedEffect(touches, menuVisible) {
        idle = false
        if (menuVisible) return@LaunchedEffect
        delay(ORB_IDLE_FADE_DELAY_MILLIS)
        idle = true
    }

    // A back arriving while the ring is open (3-button nav, or an edge swipe the exclusion rect
    // could not cover) must close the menu, never navigate underneath it: a pop racing the action a
    // release is about to perform is how the NavHost transition was corrupted into a blank screen.
    BackHandler(enabled = menuVisible) {
        holdId++
        menuVisible = false
    }
    val orbAlpha by animateFloatAsState(
        targetValue = if (idle) ORB_IDLE_ALPHA else 1f,
        animationSpec = MotdMotion.fadeOut,
        label = "gesture_orb_alpha",
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenSize = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val orbSize =
            with(density) {
                Size(RadialDimens.OrbWidth.toPx(), RadialDimens.OrbHeight.toPx())
            }
        val restPlacement = dragPlacement ?: placement
        val visualTopLeft = orbTopLeft(restPlacement, screenSize, orbSize)
        OrbTab(
            edge = restPlacement.edge,
            alpha = if (menuVisible) 0f else orbAlpha,
            modifier =
                Modifier.offset {
                    IntOffset(visualTopLeft.x.roundToInt(), visualTopLeft.y.roundToInt())
                },
        )

        val touchTopLeft =
            orbCenter(placement, screenSize, orbSize).let { center ->
                with(density) {
                    Offset(
                        center.x - RadialDimens.OrbTouchWidth.toPx() / 2f,
                        center.y - RadialDimens.OrbTouchHeight.toPx() / 2f,
                    )
                }
            }
        val touchModifier =
            Modifier.offset {
                IntOffset(touchTopLeft.x.roundToInt(), touchTopLeft.y.roundToInt())
            }
        if (accessible) {
            AccessibleGestureOrb(
                contentDescription = stringResource(R.string.gesture_orb_open),
                onClick = { scope.launch { accessibleRoot = resolveMenu() } },
                modifier = touchModifier,
            )
        } else {
            GestureOrbTouchTarget(
                onHoldStart = { position ->
                    touches++
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    holdId++
                    val hold = holdId
                    scope.launch {
                        val root = resolveMenu()
                        if (holdId != hold) return@launch
                        menu = openRadialMenu(root, position, screenSize, metrics, moreLabel)
                        menuVisible = true
                    }
                },
                onHoldMove = { position ->
                    val open = menu?.takeIf { menuVisible } ?: return@GestureOrbTouchTarget
                    val update = onRadialPointer(open, position, screenSize, metrics, moreLabel)
                    menu = update.state
                    haptics.perform(update)
                },
                onHoldEnd = {
                    val open = menu?.takeIf { menuVisible }
                    holdId++
                    menuVisible = false
                    when (val release = open?.let(::onRadialRelease)) {
                        is RadialRelease.Execute -> {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            release.entry.action?.let(onExecute)
                        }

                        // A hold released with nothing selected, or one that never resolved at all.
                        else -> {
                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                        }
                    }
                },
                onDragMove = { position ->
                    touches++
                    dragPlacement = placementForDrag(position, screenSize, orbSize)
                },
                onDragEnd = { position ->
                    val settled = placementForDrag(position, screenSize, orbSize)
                    dragPlacement = null
                    onPlacementChange(settled)
                },
                modifier = touchModifier,
            )
        }

        AnimatedVisibility(
            visible = menuVisible,
            enter = fadeIn(MotdMotion.fadeIn),
            exit = fadeOut(MotdMotion.fadeOut),
        ) {
            menu?.let { RadialMenu(state = it, metrics = metrics, accent = accent) }
        }
    }

    accessibleRoot?.let { root ->
        AccessibleGestureMenuDialog(
            root = root,
            onExecute = onExecute,
            onDismiss = { accessibleRoot = null },
        )
    }
}

/** Use the selected palette's identity rather than a stale builtin accent. */
@Composable
internal fun gestureAccent(appearance: AppearanceConfig): Color {
    val systemDark = isSystemInDarkTheme()
    val selection = appearance.paletteSelection
    val mode = selection.resolvedMode(systemDark)
    val dark = mode == PaletteMode.DARK
    val choice = selection.choiceFor(dark)
    val custom = appearance.customPalette(choice)
    if (custom != null) {
        val colors = if (dark) custom.dark else custom.light
        return remember(colors) {
            colors?.let { Color((it.nicknameColors.firstOrNull() ?: it.primary).toColorInt()) }
        } ?: MaterialTheme.colorScheme.primary
    }
    val shown = resolvePalettePreset(choice.builtin, mode, systemDark)
    val palette = remember(shown) { themeIdentityPalette(shown) }
    return palette.firstOrNull() ?: MaterialTheme.colorScheme.primary
}

/**
 * Whether a screen reader is exploring by touch.
 *
 * Observed rather than sampled once: turning TalkBack on while the app is open has to swap the orb
 * to its button form immediately, not at the next process start.
 */
@Composable
private fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    return produceState(initialValue = false, context) {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (manager == null) {
            value = false
            return@produceState
        }
        value = manager.isTouchExplorationEnabled
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { value = it }
        manager.addTouchExplorationStateChangeListener(listener)
        awaitDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }.value
}

private fun HapticFeedback.perform(update: RadialUpdate) {
    when (update.effect) {
        RadialEffect.FOCUS_CHANGED -> {
            // Only entering a slice ticks; leaving one for empty space is not an event worth feeling.
            if (update.state.focusedEntry != null) performHapticFeedback(HapticFeedbackType.SegmentTick)
        }

        RadialEffect.DESCENDED -> {
            performHapticFeedback(HapticFeedbackType.Confirm)
        }

        RadialEffect.POPPED -> {
            performHapticFeedback(HapticFeedbackType.SegmentTick)
        }

        RadialEffect.NONE -> {}
    }
}

/** Perform one gesture navigation request on the graph. */
private fun NavHostController.perform(request: GestureNavRequest) {
    when (request) {
        // A jump to the chat already on screen is a no-op (prefill delivery arrives through
        // ComposerDraftStore.prefillPushes, not re-entry). Any other chat must replace the open
        // one: a launchSingleTop re-navigation to an on-top ChatRoute never updates its
        // arguments, so a non-replacing jump from inside a chat would be silently swallowed.
        is GestureNavRequest.OpenChat -> {
            if (shouldPerformChatJump(currentChatBufferId(), request.bufferId)) {
                openChat(ChatRoute(request.bufferId), replaceCurrentChat = true)
            }
        }

        GestureNavRequest.OpenSearch -> {
            navigate(SearchRoute())
        }

        is GestureNavRequest.OpenChannelInfo -> {
            navigate(ChannelInfoRoute(request.bufferId))
        }

        GestureNavRequest.OpenChatList -> {
            navigate(ChatListRoute) {
                popUpTo<ChatListRoute> { inclusive = true }
            }
        }
    }
}

/** The buffer of the ChatRoute on top of the back stack, or null when another screen is on top. */
private fun NavHostController.currentChatBufferId(): Long? {
    val entry = currentBackStackEntry ?: return null
    if (!isChatRoutePattern(entry.destination.route)) return null
    return runCatching { entry.toRoute<ChatRoute>().bufferId }.getOrNull()
}

/** False only when the requested chat is the one already on top — then navigating is pure churn. */
internal fun shouldPerformChatJump(
    currentChatBufferId: Long?,
    targetBufferId: Long,
): Boolean = currentChatBufferId != targetBufferId
