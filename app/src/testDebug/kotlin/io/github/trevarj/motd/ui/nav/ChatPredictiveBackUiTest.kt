package io.github.trevarj.motd.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.theme.MotdMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ChatPredictiveBackUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun predictiveBackSlidesChatWithoutShrinkingAndCanCancelOrComplete() {
        lateinit var controller: NavHostController
        lateinit var chatCoordinates: LayoutCoordinates
        lateinit var listCoordinates: LayoutCoordinates
        val input = DirectNavigationEventInput()
        compose.setContent {
            val dispatcher = checkNotNull(LocalNavigationEventDispatcherOwner.current).navigationEventDispatcher
            DisposableEffect(dispatcher) {
                dispatcher.addInput(input)
                onDispose { dispatcher.removeInput(input) }
            }
            controller = rememberNavController()
            NavHost(
                navController = controller,
                startDestination = ChatListRoute,
                modifier = Modifier.size(240.dp, 400.dp),
                enterTransition = { slideIntoContainer(SlideDirection.Start, MotdMotion.navigationDrawerSpatial) },
                exitTransition = { ExitTransition.KeepUntilTransitionsFinished },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { slideOutOfContainer(SlideDirection.End, MotdMotion.chatBackSpatial) },
                predictivePopEnterTransition = { motdPredictivePopEnterTransition(it) },
                predictivePopExitTransition = { motdPredictivePopExitTransition(it) },
            ) {
                composable<ChatListRoute> {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { listCoordinates = it })
                }
                composable<ChatRoute> {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { chatCoordinates = it })
                }
            }
        }
        compose.runOnIdle { controller.navigate(ChatRoute(1)) }
        compose.waitForIdle()
        val initial = compose.runOnIdle { chatCoordinates.unclippedBoundsInRoot() }

        fun startGesture() {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                input.backStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT))
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnUiThread {
                input.backProgressed(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = 0.35f))
            }
            // NavHost starts seeking in a LaunchedEffect; let composition and layout both run.
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }

        startGesture()
        compose.runOnUiThread {
            val moving = chatCoordinates.unclippedBoundsInRoot()
            val list = listCoordinates.unclippedBoundsInRoot()
            assertTrue("Chat must slide right during predictive back", moving.left > initial.left)
            assertEquals(initial.width, moving.width, 0.5f)
            assertEquals(initial.height, moving.height, 0.5f)
            assertEquals(initial.top, moving.top, 0.5f)
            assertEquals(initial.left, list.left, 0.5f)
            assertEquals(initial.width, list.width, 0.5f)
            assertEquals(initial.height, list.height, 0.5f)
        }

        compose.runOnUiThread { input.backCancelled() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnUiThread {
            assertTrue(isChatRoutePattern(controller.currentDestination?.route))
            assertEquals(initial.left, chatCoordinates.unclippedBoundsInRoot().left, 0.5f)
        }

        startGesture()
        compose.runOnUiThread { input.backCompleted() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnUiThread {
            assertFalse(isChatRoutePattern(controller.currentDestination?.route))
            assertEquals(ChatListRoute::class.qualifiedName, controller.currentDestination?.route)
        }
    }

    @Test
    fun predictiveBackSlidesSiblingScreensWithoutShrinking() {
        lateinit var controller: NavHostController
        lateinit var screenCoordinates: LayoutCoordinates
        lateinit var listCoordinates: LayoutCoordinates
        val input = DirectNavigationEventInput()
        compose.setContent {
            val dispatcher = checkNotNull(LocalNavigationEventDispatcherOwner.current).navigationEventDispatcher
            DisposableEffect(dispatcher) {
                dispatcher.addInput(input)
                onDispose { dispatcher.removeInput(input) }
            }
            controller = rememberNavController()
            NavHost(
                navController = controller,
                startDestination = ChatListRoute,
                modifier = Modifier.size(240.dp, 400.dp),
                enterTransition = { slideIntoContainer(SlideDirection.Start, MotdMotion.navigationDrawerSpatial) },
                exitTransition = { ExitTransition.KeepUntilTransitionsFinished },
                popEnterTransition = { slideIntoContainer(SlideDirection.End, MotdMotion.navigationDrawerSpatial) },
                popExitTransition = { slideOutOfContainer(SlideDirection.End, MotdMotion.navigationDrawerSpatial) },
                predictivePopEnterTransition = { motdPredictivePopEnterTransition(it) },
                predictivePopExitTransition = { motdPredictivePopExitTransition(it) },
            ) {
                composable<ChatListRoute> {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { listCoordinates = it })
                }
                composable<SettingsRoute> {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { screenCoordinates = it })
                }
            }
        }
        compose.runOnIdle { controller.navigate(SettingsRoute()) }
        compose.waitForIdle()
        val initial = compose.runOnIdle { screenCoordinates.unclippedBoundsInRoot() }

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            input.backStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnUiThread {
            input.backProgressed(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = 0.35f))
        }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        compose.runOnUiThread {
            val moving = screenCoordinates.unclippedBoundsInRoot()
            // Must slide right, not scale out (the Navigation 2.10 predictive default shrinks).
            assertTrue("Settings must slide right during predictive back", moving.left > initial.left)
            assertEquals(initial.width, moving.width, 0.5f)
            assertEquals(initial.height, moving.height, 0.5f)
        }

        compose.runOnUiThread { input.backCancelled() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnUiThread {
            assertEquals(
                SettingsRoute::class.qualifiedName,
                controller.currentDestination?.route?.substringBefore('?'),
            )
        }
    }

    private fun LayoutCoordinates.unclippedBoundsInRoot(): Rect =
        Rect(
            localToRoot(Offset.Zero),
            localToRoot(Offset(size.width.toFloat(), size.height.toFloat())),
        )
}
