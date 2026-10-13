package io.github.trevarj.motd.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.room.Room
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.ui.chat.ChatViewModel
import io.github.trevarj.motd.ui.chat.ChatViewModelTest
import io.github.trevarj.motd.ui.chat.InlineMessageTranslation
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
    fun predictiveBackSlidesChatWithoutShrinkingAndCanCancelOrComplete() =
        withTranslationSession { owners, source ->
            val owner = owners.getValue(source.bufferId)
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
                    composable<ChatRoute> { entry ->
                        ChatTranslationSessionEffect(controller, entry, owner::clearExternalTranslationSession)
                        val translations by owner.externalTranslations.collectAsState()
                        Box(Modifier.fillMaxSize().onGloballyPositioned { chatCoordinates = it }) {
                            MotdTheme {
                                Column {
                                    Text(source.text)
                                    translations.values.forEach { translation ->
                                        InlineMessageTranslation(translation) { owner.dismissExternalTranslation(translation.eventId) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            compose.runOnIdle { controller.navigate(ChatRoute(1)) }
            compose.waitForIdle()
            acceptTranslation(owner, source)
            compose.onNodeWithTag("message_translation_88").assertIsDisplayed()
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
            compose.runOnUiThread { assertEquals("Hello", owner.externalTranslations.value[source.id]?.text) }

            compose.runOnUiThread { input.backCancelled() }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.runOnUiThread {
                assertTrue(isChatRoutePattern(controller.currentDestination?.route))
                assertEquals(initial.left, chatCoordinates.unclippedBoundsInRoot().left, 0.5f)
            }
            compose.onNodeWithTag("message_translation_88").assertIsDisplayed()
            compose.runOnIdle { assertEquals("Hello", owner.externalTranslations.value[source.id]?.text) }

            startGesture()
            compose.runOnUiThread { input.backCompleted() }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.runOnUiThread {
                assertFalse(isChatRoutePattern(controller.currentDestination?.route))
                assertEquals(ChatListRoute::class.qualifiedName, controller.currentDestination?.route)
            }
            compose.runOnIdle { assertTrue(owner.externalTranslations.value.isEmpty()) }
            compose.onNodeWithTag("message_translation_88").assertDoesNotExist()
            compose.runOnIdle { controller.navigate(ChatRoute(source.bufferId)) }
            compose.waitForIdle()
            compose.onNodeWithTag("message_translation_88").assertDoesNotExist()
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

    @Test
    fun retainedChatDepartureClearsTranslationWhileUnchangedEntryRemountRetainsIt() =
        withTranslationSession { owners, source ->
            lateinit var controller: NavHostController
            var mountEffect by mutableStateOf(true)
            var revision by mutableIntStateOf(0)
            compose.setContent {
                controller = rememberNavController()
                NavHost(
                    navController = controller,
                    startDestination = ChatListRoute,
                    modifier = Modifier.size(240.dp, 400.dp),
                    enterTransition = { slideIntoContainer(SlideDirection.Start, MotdMotion.navigationDrawerSpatial) },
                    exitTransition = { ExitTransition.KeepUntilTransitionsFinished },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { slideOutOfContainer(SlideDirection.End, MotdMotion.chatBackSpatial) },
                ) {
                    composable<ChatListRoute> { Box(Modifier.fillMaxSize()) }
                    composable<ChatRoute> { entry ->
                        val roomId = entry.toRoute<ChatRoute>().bufferId
                        val owner = owners.getValue(roomId)
                        if (mountEffect) ChatTranslationSessionEffect(controller, entry, owner::clearExternalTranslationSession)
                        val translations by owner.externalTranslations.collectAsState()
                        MotdTheme {
                            Column(Modifier.fillMaxSize().testTag("chat_visit_$roomId")) {
                                Text("Revision $revision")
                                translations.values.forEach { translation ->
                                    InlineMessageTranslation(translation) { owner.dismissExternalTranslation(translation.eventId) }
                                }
                            }
                        }
                    }
                }
            }
            compose.runOnIdle { controller.navigate(ChatRoute(source.bufferId)) }
            compose.waitForIdle()
            val firstEntry = compose.runOnIdle { checkNotNull(controller.currentBackStackEntry) }
            val firstOwner = owners.getValue(source.bufferId)
            acceptTranslation(firstOwner, source)
            compose.onNodeWithTag("message_translation_88").assertIsDisplayed()

            compose.runOnIdle { ++revision }
            compose.onNodeWithTag("message_translation_88").assertIsDisplayed()
            compose.runOnIdle { mountEffect = false }
            compose.onNodeWithTag("message_translation_88").assertIsDisplayed()
            compose.runOnIdle { mountEffect = true }
            compose.onNodeWithTag("message_translation_88").assertIsDisplayed()
            compose.runOnIdle { assertEquals(firstEntry, controller.currentBackStackEntry) }

            compose.runOnIdle { controller.navigate(ChatRoute(2)) }
            compose.waitForIdle()
            compose.onNodeWithTag("chat_visit_2").assertIsDisplayed()
            compose.runOnIdle { assertTrue(firstOwner.externalTranslations.value.isEmpty()) }
            compose.runOnIdle { assertTrue(controller.popBackStack()) }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(firstEntry, controller.currentBackStackEntry) }
            compose.onNodeWithTag("chat_visit_1").assertIsDisplayed()
            compose.onNodeWithTag("message_translation_88").assertDoesNotExist()
        }

    private fun acceptTranslation(
        owner: ChatViewModel,
        source: MessageEntity,
    ) {
        compose.runOnIdle {
            owner.viewModelScope.launch {
                checkNotNull(owner.beginExternalTranslation(source))
                owner.completeExternalTranslation("Hello")
            }
        }
        compose.waitUntil(5_000) { owner.externalTranslations.value[source.id]?.text == "Hello" }
    }

    private fun withTranslationSession(block: (Map<Long, ChatViewModel>, MessageEntity) -> Unit) {
        val db =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MotdDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        val buffers =
            listOf(
                BufferEntity(id = 1, networkId = 1, name = "#first", displayName = "#first", type = BufferType.CHANNEL, joined = true),
                BufferEntity(id = 2, networkId = 1, name = "#second", displayName = "#second", type = BufferType.CHANNEL, joined = true),
            )
        val source = MessageEntity(id = 88, bufferId = 1, serverTime = 1, sender = "alice", kind = MessageKind.PRIVMSG, text = "Bonjour", dedupKey = "nav-translation")
        runBlocking {
            db.networkDao().insert(NetworkEntity(id = 1, name = "test", role = NetworkRole.DIRECT, host = "irc.example", port = 6697, nick = "me", username = "me", realname = "Me"))
            buffers.forEach { db.bufferDao().insert(it) }
            db.messageDao().insertAll(listOf(source))
        }
        // Retain test owners across pops so empty state proves destination policy, not VM destruction.
        val store = ViewModelStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val owners =
            compose.runOnIdle {
                buffers.associate { buffer ->
                    val owner = ChatViewModelTest.externalTranslationFixture(db, buffer, scope)
                    store.put("chat-${buffer.id}", owner)
                    buffer.id to owner
                }
            }
        val jobs = owners.values.mapNotNull { it.viewModelScope.coroutineContext[Job] }
        try {
            block(owners, source)
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle {
                store.clear()
                scope.cancel()
            }
            compose.waitForIdle()
            runBlocking {
                jobs.forEach { it.join() }
                scope.coroutineContext[Job]?.join()
            }
            db.close()
        }
    }

    private fun LayoutCoordinates.unclippedBoundsInRoot(): Rect =
        Rect(
            localToRoot(Offset.Zero),
            localToRoot(Offset(size.width.toFloat(), size.height.toFloat())),
        )
}
