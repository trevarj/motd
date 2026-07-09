package io.github.trevarj.motd.ui.nav

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
@Composable
fun MotdNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = ChatListRoute) {
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
            OnboardingScreen(onDone = { navController.popBackStack() })
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
        composable<ImageViewerRoute> { entry ->
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
