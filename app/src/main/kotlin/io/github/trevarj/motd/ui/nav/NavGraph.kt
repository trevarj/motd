package io.github.trevarj.motd.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.trevarj.motd.ui.about.AboutScreen
import io.github.trevarj.motd.ui.channelinfo.ChannelInfoScreen
import io.github.trevarj.motd.ui.channellist.ChannelListScreen
import io.github.trevarj.motd.ui.chat.ChatScreen
import io.github.trevarj.motd.ui.chatlist.ChatListScreen
import io.github.trevarj.motd.ui.imageviewer.ImageViewerScreen
import io.github.trevarj.motd.ui.onboarding.OnboardingScreen
import io.github.trevarj.motd.ui.search.SearchScreen
import io.github.trevarj.motd.ui.settings.AppearanceSettingsScreen
import io.github.trevarj.motd.ui.settings.ChatSettingsScreen
import io.github.trevarj.motd.ui.settings.DeliverySettingsScreen
import io.github.trevarj.motd.ui.settings.ManageNicksScreen
import io.github.trevarj.motd.ui.settings.NetworkSettingsScreen
import io.github.trevarj.motd.ui.settings.NetworksSettingsScreen
import io.github.trevarj.motd.ui.settings.NickListKind
import io.github.trevarj.motd.ui.settings.SettingsScreen
import io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkScreen
import io.github.trevarj.motd.ui.settings.bouncer.BouncerNetworksScreen
import io.github.trevarj.motd.ui.theme.MotdMotion

/**
 * App navigation graph. Routes come from [Routes.kt] (frozen). Each destination is wired to its
 * screen composable; WP7/WP8 fill in their own screen bodies behind these signatures.
 */
// Material shared-axis X feel: forward pushes the new screen in from the right and the old one out
// to the left; back reverses it. Chat uses a drawer-style transition: only the chat surface moves,
// while the adjacent destination stays stationary beneath it. This avoids transforming two full
// Compose trees while the first Room and Paging emissions arrive.
@Composable
fun MotdNavGraph(
    navController: NavHostController = rememberNavController(),
    // Notification-tap deep-link: open the buffer and jump to the message. Null when absent.
    notificationTarget: NotificationTarget? = null,
    onNotificationTargetHandled: () -> Unit = {},
) {
    // Route a notification tap to ChatRoute so the existing jump path (local resolve → CHATHISTORY
    // AROUND fallback) scrolls to and highlights the message. Runs for both cold start (target
    // seeded before first composition) and warm start (target updated by onNewIntent). Clearing the
    // target after navigating lets a subsequent identical tap re-trigger (null → value transition).
    LaunchedEffect(notificationTarget) {
        val target = notificationTarget ?: return@LaunchedEffect
        navController.navigate(
            ChatRoute(
                target.bufferId,
                target.jumpToMsgid,
                target.jumpToTime,
                target.jumpToEventId,
            ),
        ) {
            launchSingleTop = true
        }
        onNotificationTargetHandled()
    }
    NavHost(
        navController = navController,
        startDestination = ChatListRoute,
        enterTransition = {
            if (isChatTarget()) {
                slideIntoContainer(SlideDirection.Start, MotdMotion.navigationDrawerSpatial)
            } else {
                slideIntoContainer(SlideDirection.Start, tween(MotdMotion.NavigationDurationMs))
            }
        },
        exitTransition = {
            if (isChatTarget()) {
                // Keep the current screen in place until the incoming chat finishes, mirroring
                // ModalNavigationDrawer's single moving surface over stationary content.
                ExitTransition.KeepUntilTransitionsFinished
            } else {
                slideOutOfContainer(SlideDirection.Start, tween(MotdMotion.NavigationDurationMs))
            }
        },
        popEnterTransition = {
            if (isChatInitial()) {
                // The destination is already visible beneath the outgoing chat surface.
                EnterTransition.None
            } else {
                slideIntoContainer(SlideDirection.End, tween(MotdMotion.NavigationDurationMs))
            }
        },
        popExitTransition = {
            if (isChatInitial()) {
                slideOutOfContainer(SlideDirection.End, MotdMotion.chatBackSpatial)
            } else {
                slideOutOfContainer(SlideDirection.End, tween(MotdMotion.NavigationDurationMs))
            }
        },
    ) {
        composable<ChatListRoute> {
            ChatListScreen(
                onOpenBuffer = { navController.navigate(ChatRoute(it)) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenSearch = { navController.navigate(SearchRoute()) },
                onOpenOnboarding = { navController.navigate(OnboardingRoute) },
                // Round 5: drawer network-management + browse entry points.
                onOpenNetworkSettings = { navController.navigate(NetworkSettingsRoute(it)) },
                onOpenAddNetwork = { navController.navigate(AddNetworkRoute) },
                onOpenChannelList = { navController.navigate(ChannelListRoute(it)) },
            )
        }
        composable<ChatRoute> { entry ->
            val route = entry.toRoute<ChatRoute>()
            ChatScreen(
                bufferId = route.bufferId,
                onBack = { navController.popBackStack() },
                onOpenChannelInfo = { navController.navigate(ChannelInfoRoute(it)) },
                onOpenSearch = { navController.navigate(SearchRoute(it)) },
                onOpenImage = { navController.navigate(ImageViewerRoute(it)) },
                // /msg and /query navigate to the resolved QUERY buffer.
                onOpenBuffer = { navController.navigate(ChatRoute(it)) },
                // Round 5: /list opens the channel browser for the current network.
                onOpenChannelList = { navController.navigate(ChannelListRoute(it)) },
            )
        }
        composable<OnboardingRoute> {
            // Finish lands on a fresh ChatList and clears onboarding (plus any duplicate
            // onboarding entries) from the backstack; a bare popBackStack could fall back to
            // the Welcome step instead of the chat list.
            OnboardingScreen(onDone = {
                navController.navigate(ChatListRoute) {
                    popUpTo<ChatListRoute> { inclusive = true }
                    launchSingleTop = true
                }
            })
        }
        composable<SettingsRoute> {
            // Top-level Settings: category rows opening the focused sub-screens below.
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenAppearance = { navController.navigate(AppearanceSettingsRoute) },
                onOpenChat = { navController.navigate(ChatSettingsRoute) },
                onOpenDelivery = { navController.navigate(DeliverySettingsRoute) },
                onOpenNetworks = { navController.navigate(NetworksSettingsRoute) },
                onOpenAbout = { navController.navigate(AboutRoute) },
            )
        }
        composable<AppearanceSettingsRoute> {
            AppearanceSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenNickColors = { navController.navigate(NickColorsRoute) },
            )
        }
        composable<ChatSettingsRoute> {
            ChatSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenFriends = { navController.navigate(FriendsRoute) },
                onOpenFools = { navController.navigate(FoolsRoute) },
            )
        }
        composable<DeliverySettingsRoute> {
            DeliverySettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<NetworksSettingsRoute> {
            NetworksSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenNetwork = { navController.navigate(NetworkSettingsRoute(it)) },
                onOpenAddNetwork = { navController.navigate(AddNetworkRoute) },
            )
        }
        composable<FriendsRoute> {
            ManageNicksScreen(NickListKind.FRIENDS, onBack = { navController.popBackStack() })
        }
        composable<FoolsRoute> {
            ManageNicksScreen(NickListKind.FOOLS, onBack = { navController.popBackStack() })
        }
        composable<NickColorsRoute> {
            ManageNicksScreen(NickListKind.COLORS, onBack = { navController.popBackStack() })
        }
        composable<NetworkSettingsRoute> { entry ->
            val route = entry.toRoute<NetworkSettingsRoute>()
            NetworkSettingsScreen(
                networkId = route.networkId,
                onBack = { navController.popBackStack() },
                // Round 5: soju root -> bouncer manager; "Server messages" -> the SERVER buffer.
                onOpenBouncerNetworks = { navController.navigate(BouncerNetworksRoute(it)) },
                onOpenBuffer = { navController.navigate(ChatRoute(it)) },
            )
        }
        composable<SearchRoute> { entry ->
            val route = entry.toRoute<SearchRoute>()
            SearchScreen(
                bufferId = route.bufferId,
                onBack = { navController.popBackStack() },
                onOpenHit = { bufferId, msgid, time, eventId ->
                    navController.navigate(ChatRoute(bufferId, msgid, time, eventId))
                },
            )
        }
        composable<ChannelInfoRoute> { entry ->
            val route = entry.toRoute<ChannelInfoRoute>()
            ChannelInfoScreen(
                bufferId = route.bufferId,
                onBack = { navController.popBackStack() },
                // Member "Message" action opens the DM's QUERY buffer.
                onOpenBuffer = { navController.navigate(ChatRoute(it)) },
            )
        }
        composable<ImageViewerRoute>(
            // Full-screen image reads better appearing/dismissing in place than sliding sideways.
            enterTransition = { fadeIn(tween(MotdMotion.NavigationDurationMs)) },
            exitTransition = { fadeOut(tween(MotdMotion.NavigationDurationMs)) },
            popEnterTransition = { fadeIn(tween(MotdMotion.NavigationDurationMs)) },
            popExitTransition = { fadeOut(tween(MotdMotion.NavigationDurationMs)) },
        ) { entry ->
            val route = entry.toRoute<ImageViewerRoute>()
            ImageViewerScreen(url = route.url, onBack = { navController.popBackStack() })
        }
        composable<AboutRoute> {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        // Round 5 (plans/16 §5.1): app-shell / network-management destinations.
        composable<AddNetworkRoute> {
            AddNetworkScreen(
                onBack = { navController.popBackStack() },
                onOpenBouncerNetworks = { rootId ->
                    navController.navigate(BouncerNetworksRoute(rootId)) {
                        // The add-flow is replaced by the manager once the soju root exists.
                        popUpTo<AddNetworkRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<BouncerNetworksRoute> { entry ->
            val route = entry.toRoute<BouncerNetworksRoute>()
            BouncerNetworksScreen(
                rootNetworkId = route.rootNetworkId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<ChannelListRoute> { entry ->
            val route = entry.toRoute<ChannelListRoute>()
            ChannelListScreen(
                networkId = route.networkId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isChatTarget(): Boolean =
    isChatRoutePattern(targetState.destination.route)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isChatInitial(): Boolean =
    isChatRoutePattern(initialState.destination.route)

internal fun isChatRoutePattern(route: String?): Boolean {
    val chatRouteName = ChatRoute::class.qualifiedName ?: return false
    return route == chatRouteName ||
        route?.startsWith("$chatRouteName/") == true ||
        route?.startsWith("$chatRouteName?") == true
}
