package io.github.trevarj.motd.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
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
import io.github.trevarj.motd.ui.settings.ManageNicksScreen
import io.github.trevarj.motd.ui.settings.NetworkSettingsScreen
import io.github.trevarj.motd.ui.settings.NickListKind
import io.github.trevarj.motd.ui.settings.SettingsScreen
import io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkScreen
import io.github.trevarj.motd.ui.settings.bouncer.BouncerNetworksScreen

/**
 * App navigation graph. Routes come from [Routes.kt] (frozen). Each destination is wired to its
 * screen composable; WP7/WP8 fill in their own screen bodies behind these signatures.
 */
// Material shared-axis X feel: forward pushes the new screen in from the right and the old one out
// to the left; back reverses it. 300ms tween is the standard container-transform duration. These are
// set at the NavHost level so every composable<Route> inherits them; the pop transitions are also
// what the Android 13+ predictive-back scrim animates.
private const val SLIDE_DURATION_MS = 300

@Composable
fun MotdNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = ChatListRoute,
        enterTransition = { slideIntoContainer(SlideDirection.Start, tween(SLIDE_DURATION_MS)) },
        exitTransition = { slideOutOfContainer(SlideDirection.Start, tween(SLIDE_DURATION_MS)) },
        popEnterTransition = { slideIntoContainer(SlideDirection.End, tween(SLIDE_DURATION_MS)) },
        popExitTransition = { slideOutOfContainer(SlideDirection.End, tween(SLIDE_DURATION_MS)) },
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
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenNetwork = { navController.navigate(NetworkSettingsRoute(it)) },
                onOpenAbout = { navController.navigate(AboutRoute) },
                onOpenFriends = { navController.navigate(FriendsRoute) },
                onOpenFools = { navController.navigate(FoolsRoute) },
                onOpenNickColors = { navController.navigate(NickColorsRoute) },
                // Round 5: add-network entry from Settings.
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
                onOpenHit = { bufferId, msgid, time ->
                    navController.navigate(ChatRoute(bufferId, msgid, time))
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
            enterTransition = { fadeIn(tween(SLIDE_DURATION_MS)) },
            exitTransition = { fadeOut(tween(SLIDE_DURATION_MS)) },
            popEnterTransition = { fadeIn(tween(SLIDE_DURATION_MS)) },
            popExitTransition = { fadeOut(tween(SLIDE_DURATION_MS)) },
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
