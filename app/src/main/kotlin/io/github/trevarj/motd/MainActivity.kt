package io.github.trevarj.motd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.ui.channelinfo.ChannelInfoScreen
import io.github.trevarj.motd.ui.chat.ChatScreen
import io.github.trevarj.motd.ui.chatlist.ChatListScreen
import io.github.trevarj.motd.ui.imageviewer.ImageViewerScreen
import io.github.trevarj.motd.ui.nav.ChannelInfoRoute
import io.github.trevarj.motd.ui.nav.ChatListRoute
import io.github.trevarj.motd.ui.nav.ChatRoute
import io.github.trevarj.motd.ui.nav.ImageViewerRoute
import io.github.trevarj.motd.ui.nav.NetworkSettingsRoute
import io.github.trevarj.motd.ui.nav.OnboardingRoute
import io.github.trevarj.motd.ui.nav.SearchRoute
import io.github.trevarj.motd.ui.nav.SettingsRoute
import io.github.trevarj.motd.ui.onboarding.OnboardingScreen
import io.github.trevarj.motd.ui.search.SearchScreen
import io.github.trevarj.motd.ui.settings.NetworkSettingsScreen
import io.github.trevarj.motd.ui.settings.SettingsScreen
import io.github.trevarj.motd.ui.theme.MotdTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MotdTheme {
                MotdNavHost()
            }
        }
    }
}

// Placeholder NavHost wiring every route in Routes.kt to a placeholder screen.
// WP6 extracts this into ui/nav/NavGraph.kt; WP10 finalizes glue.
@Composable
private fun MotdNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = ChatListRoute) {
        composable<ChatListRoute> {
            ChatListScreen(
                onOpenBuffer = { nav.navigate(ChatRoute(it)) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
                onOpenSearch = { nav.navigate(SearchRoute()) },
                onOpenOnboarding = { nav.navigate(OnboardingRoute) },
            )
        }
        composable<ChatRoute> { entry ->
            val route = entry.toRoute<ChatRoute>()
            ChatScreen(
                bufferId = route.bufferId,
                onBack = { nav.popBackStack() },
                onOpenChannelInfo = { nav.navigate(ChannelInfoRoute(it)) },
                onOpenSearch = { nav.navigate(SearchRoute(it)) },
                onOpenImage = { nav.navigate(ImageViewerRoute(it)) },
            )
        }
        composable<OnboardingRoute> {
            OnboardingScreen(onDone = { nav.popBackStack() })
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenNetwork = { nav.navigate(NetworkSettingsRoute(it)) },
            )
        }
        composable<NetworkSettingsRoute> { entry ->
            val route = entry.toRoute<NetworkSettingsRoute>()
            NetworkSettingsScreen(networkId = route.networkId, onBack = { nav.popBackStack() })
        }
        composable<SearchRoute> { entry ->
            val route = entry.toRoute<SearchRoute>()
            SearchScreen(
                bufferId = route.bufferId,
                onBack = { nav.popBackStack() },
                onOpenBuffer = { nav.navigate(ChatRoute(it)) },
            )
        }
        composable<ChannelInfoRoute> { entry ->
            val route = entry.toRoute<ChannelInfoRoute>()
            ChannelInfoScreen(bufferId = route.bufferId, onBack = { nav.popBackStack() })
        }
        composable<ImageViewerRoute> { entry ->
            val route = entry.toRoute<ImageViewerRoute>()
            ImageViewerScreen(url = route.url, onBack = { nav.popBackStack() })
        }
    }
}
