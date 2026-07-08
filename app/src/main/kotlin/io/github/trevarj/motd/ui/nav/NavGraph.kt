package io.github.trevarj.motd.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.trevarj.motd.ui.channelinfo.ChannelInfoScreen
import io.github.trevarj.motd.ui.chat.ChatScreen
import io.github.trevarj.motd.ui.chatlist.ChatListScreen
import io.github.trevarj.motd.ui.imageviewer.ImageViewerScreen
import io.github.trevarj.motd.ui.onboarding.OnboardingScreen
import io.github.trevarj.motd.ui.search.SearchScreen
import io.github.trevarj.motd.ui.settings.NetworkSettingsScreen
import io.github.trevarj.motd.ui.settings.SettingsScreen

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
            )
        }
        composable<OnboardingRoute> {
            OnboardingScreen(onDone = { navController.popBackStack() })
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenNetwork = { navController.navigate(NetworkSettingsRoute(it)) },
            )
        }
        composable<NetworkSettingsRoute> { entry ->
            val route = entry.toRoute<NetworkSettingsRoute>()
            NetworkSettingsScreen(networkId = route.networkId, onBack = { navController.popBackStack() })
        }
        composable<SearchRoute> { entry ->
            val route = entry.toRoute<SearchRoute>()
            SearchScreen(
                bufferId = route.bufferId,
                onBack = { navController.popBackStack() },
                onOpenBuffer = { navController.navigate(ChatRoute(it)) },
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
    }
}
