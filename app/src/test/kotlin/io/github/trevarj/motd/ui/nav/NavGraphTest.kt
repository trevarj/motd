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
    fun `restored image viewer keeps the selected network and exact URL`() {
        fun controller() =
            NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                setLifecycleOwner(ResumedOwner())
                setViewModelStore(ViewModelStore())
                navigatorProvider.addNavigator(ComposeNavigator())
            }

        fun NavHostController.installGraph() {
            graph =
                createGraph(startDestination = ChatListRoute) {
                    composable<ChatListRoute> {}
                    composable<ImageViewerRoute> {}
                }
        }

        val original = controller().apply { installGraph() }
        val image = ImageViewerRoute("https://irc.trevs.site:9443/upload/user/account/123-cat%20photo.png?x=1&y=2", networkId = 42)
        original.navigate(image)
        val restored =
            controller().apply {
                restoreState(original.saveState())
                installGraph()
            }

        assertEquals(image, restored.currentBackStackEntry!!.toRoute<ImageViewerRoute>())
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

    @Test
    fun `replacement request over portal preserves portal and earlier chat with all jump fields`() {
        val controller = portalController()
        val earlier = ChatRoute(5)
        val target =
            ChatRoute(
                bufferId = 7,
                jumpToMsgid = "msgid-7",
                jumpToTime = 1_725_000_000_000,
                jumpToEventId = 77,
            )

        controller.navigate(earlier)
        controller.navigate(DickordPortalRoute)
        controller.openChat(target, replaceCurrentChat = true)

        assertEquals(target, controller.currentBackStackEntry!!.toRoute<ChatRoute>())
        assertTrue(controller.popBackStack())
        assertEquals(DickordPortalRoute, controller.currentBackStackEntry!!.toRoute<DickordPortalRoute>())
        assertTrue(controller.popBackStack())
        assertEquals(earlier, controller.currentBackStackEntry!!.toRoute<ChatRoute>())
    }

    @Test
    fun `share completion over portal preserves portal and earlier chat`() {
        val controller = portalController()
        controller.navigate(ChatRoute(5))
        controller.navigate(DickordPortalRoute)
        controller.navigate(SharePickerRoute)

        controller.completeShareNavigation(bufferId = 7, preserveSourceChat = false)

        assertEquals(7L, controller.currentBackStackEntry!!.toRoute<ChatRoute>().bufferId)
        assertTrue(controller.popBackStack())
        assertEquals(DickordPortalRoute, controller.currentBackStackEntry!!.toRoute<DickordPortalRoute>())
        assertTrue(controller.popBackStack())
        assertEquals(5L, controller.currentBackStackEntry!!.toRoute<ChatRoute>().bufferId)
    }

    @Test
    fun `portal chat returns to its immediately preceding navigator`() {
        val controller = portalController()
        controller.navigate(DickordPortalRoute)
        controller.openChat(ChatRoute(7), replaceCurrentChat = false)

        controller.openDickordNavigator()

        assertEquals(DickordPortalRoute, controller.currentBackStackEntry!!.toRoute<DickordPortalRoute>())
        assertTrue(controller.popBackStack())
        assertEquals(ChatListRoute, controller.currentBackStackEntry!!.toRoute<ChatListRoute>())
    }

    @Test
    fun `opening navigator replaces only current chat across an unrelated source`() {
        val controller = portalController()
        controller.navigate(ChatRoute(5))
        controller.navigate(SearchRoute())
        controller.navigate(ChatRoute(7, jumpToMsgid = "search-hit", jumpToTime = 99, jumpToEventId = 11))

        controller.openDickordNavigator()

        assertEquals(DickordPortalRoute, controller.currentBackStackEntry!!.toRoute<DickordPortalRoute>())
        assertTrue(controller.popBackStack())
        assertEquals(SearchRoute(), controller.currentBackStackEntry!!.toRoute<SearchRoute>())
        assertTrue(controller.popBackStack())
        assertEquals(5L, controller.currentBackStackEntry!!.toRoute<ChatRoute>().bufferId)
    }

    @Test
    fun `disabled portal restoration returns to chat list root`() {
        val controller = portalController()
        controller.navigate(ChatRoute(5))
        controller.navigate(DickordPortalRoute)

        controller.returnToChatList()

        assertEquals(ChatListRoute, controller.currentBackStackEntry!!.toRoute<ChatListRoute>())
        assertFalse(controller.popBackStack())
    }

    @Test
    fun `search jump keeps every target field and returns to search`() {
        val controller = portalController()
        val target = ChatRoute(7, jumpToMsgid = "search-hit", jumpToTime = 99, jumpToEventId = 11)
        controller.navigate(SearchRoute())

        controller.navigate(target)

        assertEquals(target, controller.currentBackStackEntry!!.toRoute<ChatRoute>())
        assertTrue(controller.popBackStack())
        assertEquals(SearchRoute(), controller.currentBackStackEntry!!.toRoute<SearchRoute>())
    }

    private fun portalController() =
        NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
            setLifecycleOwner(ResumedOwner())
            setViewModelStore(ViewModelStore())
            navigatorProvider.addNavigator(ComposeNavigator())
            graph =
                createGraph(startDestination = ChatListRoute) {
                    composable<ChatListRoute> {}
                    composable<ChatRoute> {}
                    composable<DickordPortalRoute> {}
                    composable<SearchRoute> {}
                    composable<SharePickerRoute> {}
                }
        }

    private class ResumedOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle = registry
    }
}
