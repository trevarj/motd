package io.github.trevarj.motd.ui.nav

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavGraphTest {
    private val chatRouteName = requireNotNull(ChatRoute::class.qualifiedName)

    @Test
    fun `chat destination patterns use the drawer style transition`() {
        assertTrue(isChatRoutePattern(chatRouteName))
        assertTrue(isChatRoutePattern("$chatRouteName/{bufferId}"))
        assertTrue(isChatRoutePattern("$chatRouteName?bufferId={bufferId}"))
    }

    @Test
    fun `other and missing destinations retain the shared axis transition`() {
        assertFalse(isChatRoutePattern(ChatListRoute::class.qualifiedName))
        assertFalse(isChatRoutePattern("${ChatRoute::class.qualifiedName}Extra/{bufferId}"))
        assertFalse(isChatRoutePattern(null))
    }

    @Test
    fun `settings routes retain typed targets and concrete network identity`() {
        assertEquals(SettingsTarget.THEME, AppearanceSettingsRoute(SettingsTarget.THEME).target)
        assertEquals(SettingsTarget.EXPORT_BACKUP, BackupRestoreRoute(SettingsTarget.EXPORT_BACKUP).target)
        assertEquals(
            NetworkSettingsRoute(42, NetworkSettingsTarget.OBFUSCATION),
            NetworkSettingsRoute(networkId = 42, target = NetworkSettingsTarget.OBFUSCATION),
        )
    }

    @Test
    fun `voice settings destinations preserve typed targets and model library route`() {
        val controller =
            NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                setLifecycleOwner(ResumedOwner())
                setViewModelStore(ViewModelStore())
                navigatorProvider.addNavigator(ComposeNavigator())
                graph =
                    createGraph(startDestination = SettingsRoute()) {
                        composable<SettingsRoute> {}
                        composable<AiLabsRoute> {}
                        composable<AiModelLibraryRoute> {}
                    }
            }

        controller.navigate(AiLabsRoute(SettingsTarget.AI_TRANSCRIPTION))
        assertEquals(SettingsTarget.AI_TRANSCRIPTION, controller.currentBackStackEntry!!.toRoute<AiLabsRoute>().target)
        controller.navigate(AiModelLibraryRoute)
        assertEquals(AiModelLibraryRoute, controller.currentBackStackEntry!!.toRoute<AiModelLibraryRoute>())
    }

    @Test
    fun `opening another chat pushes its route and keeps the previous chat for back`() {
        val controller =
            NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                setLifecycleOwner(ResumedOwner())
                setViewModelStore(ViewModelStore())
                navigatorProvider.addNavigator(ComposeNavigator())
                graph =
                    createGraph(startDestination = ChatListRoute) {
                        composable<ChatListRoute> {}
                        composable<ChatRoute> {}
                    }
            }

        controller.navigate(ChatRoute(5))
        controller.openChat(ChatRoute(7), replaceCurrentChat = false)

        val chatIds =
            controller.currentBackStack.value.mapNotNull { entry ->
                entry
                    .takeIf { isChatRoutePattern(it.destination.route) }
                    ?.toRoute<ChatRoute>()
                    ?.bufferId
            }
        assertEquals(listOf(5L, 7L), chatIds)
    }

    @Test
    fun `context handoff preserves source draft for same and different destinations`() {
        for (destination in listOf(5L, 7L)) {
            val controller =
                NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                    setLifecycleOwner(ResumedOwner())
                    setViewModelStore(ViewModelStore())
                    navigatorProvider.addNavigator(ComposeNavigator())
                    graph =
                        createGraph(startDestination = ChatListRoute) {
                            composable<ChatListRoute> {}
                            composable<ChatRoute> {}
                            composable<SharePickerRoute> {}
                        }
                }
            controller.navigate(ChatRoute(5))
            controller.currentBackStackEntry!!.savedStateHandle["draft"] = "unsent original"
            controller.navigate(SharePickerRoute)
            controller.completeShareNavigation(destination, preserveSourceChat = true)
            assertEquals(destination, controller.currentBackStackEntry!!.toRoute<ChatRoute>().bufferId)
            if (destination != 5L) assertTrue(controller.popBackStack())
            assertEquals(5L, controller.currentBackStackEntry!!.toRoute<ChatRoute>().bufferId)
            assertEquals("unsent original", controller.currentBackStackEntry!!.savedStateHandle.get<String>("draft"))
        }
    }

    private class ResumedOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle = registry
    }
}
