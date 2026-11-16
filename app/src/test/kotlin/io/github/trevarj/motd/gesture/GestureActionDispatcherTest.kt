package io.github.trevarj.motd.gesture

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.InstalledPalette
import io.github.trevarj.motd.data.prefs.PaletteColors
import io.github.trevarj.motd.data.prefs.PaletteDocument
import io.github.trevarj.motd.data.prefs.PaletteMode
import io.github.trevarj.motd.data.prefs.PaletteSelection
import io.github.trevarj.motd.data.prefs.paletteSelection
import io.github.trevarj.motd.ui.chat.AttachmentRequestStore
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric only because [ComposerDraftStore] is Room-backed; nothing here touches a table. The
 * prefill queue the dispatcher writes to is process-local.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GestureActionDispatcherTest {
    private lateinit var db: MotdDatabase

    @Before fun setUp() {
        db = inMemoryDb()
    }

    @After fun tearDown() = db.close()

    // --- navigation ---

    @Test fun `opening a stored chat resolves a durable redirect first`() =
        runTest {
            val world = world()
            world.buffers.redirects[5L] = 9L
            val nav = world.navLog(this)

            assertEquals(GestureActionResult.Done, world.dispatcher.execute(GestureAction.OpenChat(5L)))
            runCurrent()

            assertEquals(listOf(GestureNavRequest.OpenChat(9L)), nav)
        }

    @Test fun `a chat that no longer exists is unavailable rather than navigated to`() =
        runTest {
            val world = world()
            world.buffers.missing += 5L
            val nav = world.navLog(this)

            assertEquals(GestureActionResult.Unavailable, world.dispatcher.execute(GestureAction.OpenChat(5L)))
            runCurrent()

            assertTrue(nav.isEmpty())
        }

    @Test fun `search and chat list are plain navigation requests`() =
        runTest {
            val world = world()
            val nav = world.navLog(this)

            world.dispatcher.execute(GestureAction.OpenSearch)
            world.dispatcher.execute(GestureAction.OpenChatList)
            runCurrent()

            assertEquals(listOf(GestureNavRequest.OpenSearch, GestureNavRequest.OpenChatList), nav)
        }

    @Test fun `next unread takes the first unread chat and ignores muted, server and read rows`() =
        runTest {
            val world = world()
            world.buffers.chats.value =
                listOf(
                    chatRow(1L, unreadCount = 0),
                    chatRow(2L, muted = true, unreadCount = 4),
                    chatRow(3L, type = BufferType.SERVER, unreadCount = 7),
                    chatRow(4L, unreadCount = 1),
                    chatRow(5L, unreadCount = 9),
                )
            val nav = world.navLog(this)

            assertEquals(GestureActionResult.Done, world.dispatcher.execute(GestureAction.NextUnread))
            runCurrent()

            assertEquals(listOf(GestureNavRequest.OpenChat(4L)), nav)
        }

    @Test fun `next unread with nothing unread is unavailable`() =
        runTest {
            val world = world()
            world.buffers.chats.value = listOf(chatRow(1L, unreadCount = 0))

            assertEquals(GestureActionResult.Unavailable, world.dispatcher.execute(GestureAction.NextUnread))
        }

    // --- current-chat actions ---

    @Test fun `a mention is queued for the canonical current chat and asked to be shown`() =
        runTest {
            val world = world(currentBuffer = 5L)
            world.buffers.redirects[5L] = 9L
            val nav = world.navLog(this)

            assertEquals(
                GestureActionResult.Done,
                world.dispatcher.execute(GestureAction.InsertMention("alice")),
            )
            runCurrent()

            assertEquals("alice: ", world.drafts.consume(9L))
            assertEquals(listOf(GestureNavRequest.OpenChat(9L)), nav)
        }

    @Test fun `a snippet is queued verbatim`() =
        runTest {
            val world = world(currentBuffer = 5L)

            world.dispatcher.execute(GestureAction.InsertSnippet("on my way"))

            assertEquals("on my way", world.drafts.consume(5L))
        }

    @Test fun `attach queues an attachment request without navigating anywhere`() =
        runTest {
            val world = world(currentBuffer = 5L)
            val nav = world.navLog(this)

            assertEquals(GestureActionResult.Done, world.dispatcher.execute(GestureAction.AttachCurrent))
            runCurrent()

            assertTrue(world.attachments.consume(5L))
            assertTrue(nav.isEmpty())
        }

    @Test fun `channel info opens for the chat behind the orb`() =
        runTest {
            val world = world(currentBuffer = 5L)
            val nav = world.navLog(this)

            world.dispatcher.execute(GestureAction.ChannelInfoCurrent)
            runCurrent()

            assertEquals(listOf(GestureNavRequest.OpenChannelInfo(5L)), nav)
        }

    @Test fun `chat-scoped actions report a missing chat instead of guessing one`() =
        runTest {
            val world = world(currentBuffer = null)

            listOf(
                GestureAction.ChannelInfoCurrent,
                GestureAction.InsertMention("alice"),
                GestureAction.InsertSnippet("hi"),
                GestureAction.AttachCurrent,
            ).forEach { action ->
                assertEquals(action.toString(), GestureActionResult.NeedsChatContext, world.dispatcher.execute(action))
            }
            assertNull(world.drafts.consume(5L))
        }

    // --- IRC actions ---

    @Test fun `away marks every connected network away with the leaf's message`() =
        runTest {
            val world = world()
            world.connections.states.value = mapOf(1L to readyState(), 2L to readyState())

            assertEquals(GestureActionResult.Done, world.dispatcher.execute(GestureAction.ToggleAway("brb")))

            assertEquals(listOf(1L to "brb", 2L to "brb"), world.connections.awayWrites)
        }

    @Test fun `a leaf with no message falls back to the localized default`() =
        runTest {
            val world = world()
            world.connections.states.value = mapOf(1L to readyState())

            world.dispatcher.execute(GestureAction.ToggleAway())

            assertEquals(listOf(1L to "Away"), world.connections.awayWrites)
        }

    @Test fun `one confirmed away turns the whole gesture into a back`() =
        runTest {
            val world = world()
            world.connections.states.value = mapOf(1L to readyState(), 2L to readyState())
            world.connections.away.value = mapOf(1L to "brb")

            world.dispatcher.execute(GestureAction.ToggleAway("brb"))

            assertEquals(listOf(1L to null), world.connections.awayWrites)
        }

    @Test fun `away states on a disconnected network do not make the gesture a back`() =
        runTest {
            val world = world()
            world.connections.states.value = mapOf(1L to readyState())
            // Stale entry for a network that is no longer connected; it must not steer network 1.
            world.connections.away.value = mapOf(7L to "gone")

            world.dispatcher.execute(GestureAction.ToggleAway("brb"))

            assertEquals(listOf(1L to "brb"), world.connections.awayWrites)
        }

    @Test fun `away with nothing connected is unavailable`() =
        runTest {
            val world = world()

            assertEquals(GestureActionResult.Unavailable, world.dispatcher.execute(GestureAction.ToggleAway("brb")))
            assertTrue(world.connections.awayWrites.isEmpty())
        }

    @Test fun `a query opens the buffer the connection manager ensures`() =
        runTest {
            val world = world(networks = listOf(testNetwork(1L)))
            world.connections.queryBufferId = 77L
            val nav = world.navLog(this)

            assertEquals(
                GestureActionResult.Done,
                world.dispatcher.execute(GestureAction.StartQuery(1L, "alice")),
            )
            runCurrent()

            assertEquals(listOf(1L to "alice"), world.connections.queries)
            assertEquals(listOf(GestureNavRequest.OpenChat(77L)), nav)
        }

    @Test fun `a query on a deleted network is refused before it reaches the wire`() =
        runTest {
            val world = world(networks = listOf(testNetwork(1L)))

            assertEquals(
                GestureActionResult.Unavailable,
                world.dispatcher.execute(GestureAction.StartQuery(2L, "alice")),
            )
            assertTrue(world.connections.queries.isEmpty())
        }

    @Test fun `network actions run through the connection manager`() =
        runTest {
            val world = world(networks = listOf(testNetwork(1L)))

            world.dispatcher.execute(GestureAction.ReconnectNetwork(1L))
            world.dispatcher.execute(GestureAction.DisconnectNetwork(1L))
            world.dispatcher.execute(GestureAction.JoinChannel(1L, "#motd", "sekrit"))

            assertEquals(listOf(1L), world.connections.connected)
            assertEquals(listOf(1L), world.connections.disconnected)
            assertEquals(listOf(Triple(1L, "#motd", "sekrit")), world.connections.joins)
        }

    @Test fun `network actions for a deleted network do nothing`() =
        runTest {
            val world = world(networks = emptyList())

            listOf(
                GestureAction.ReconnectNetwork(1L),
                GestureAction.DisconnectNetwork(1L),
                GestureAction.JoinChannel(1L, "#motd"),
            ).forEach { action ->
                assertEquals(action.toString(), GestureActionResult.Unavailable, world.dispatcher.execute(action))
            }
            assertTrue(world.connections.connected.isEmpty())
            assertTrue(world.connections.disconnected.isEmpty())
            assertTrue(world.connections.joins.isEmpty())
        }

    // --- read state ---

    @Test fun `mark all read advances every unread chat and skips rows with no boundary`() =
        runTest {
            val world = world(readMarkers = FakeReadMarkers(withoutBoundary = setOf(4L)))
            world.buffers.chats.value =
                listOf(
                    chatRow(1L, unreadCount = 2),
                    chatRow(2L, muted = true, unreadCount = 3),
                    chatRow(4L, unreadCount = 1),
                )

            assertEquals(GestureActionResult.Done, world.dispatcher.execute(GestureAction.MarkAllRead))

            assertEquals(listOf(1L), world.connections.marked.map { it.first })
        }

    @Test fun `mark all read with nothing unread is unavailable`() =
        runTest {
            val world = world()
            world.buffers.chats.value = listOf(chatRow(1L, unreadCount = 0))

            assertEquals(GestureActionResult.Unavailable, world.dispatcher.execute(GestureAction.MarkAllRead))
            assertTrue(world.connections.marked.isEmpty())
        }

    // --- appearance ---

    @Test fun `theme toggle swaps to the paired palette and stops following the system`() =
        runTest {
            val world =
                world(
                    appearance = AppearanceConfig(theme = ColorThemePreset.GRUVBOX_LIGHT, followSystem = true),
                )

            assertEquals(GestureActionResult.Done, world.dispatcher.execute(GestureAction.ToggleTheme))

            assertEquals(
                PaletteSelection(ColorThemePreset.GRUVBOX_DARK, null, PaletteMode.DARK),
                world.appearance.state.value.paletteSelection,
            )
        }

    @Test fun `theme toggle swaps away from the palette the OS is currently resolving to`() =
        runTest {
            // Stored GRUVBOX_LIGHT under a dark OS with following on *displays* GRUVBOX_DARK, so the
            // toggle has to land on the light side rather than on the partner of the stored preset.
            val world =
                world(
                    appearance = AppearanceConfig(theme = ColorThemePreset.GRUVBOX_LIGHT, followSystem = true),
                    systemDark = true,
                )

            assertEquals(GestureActionResult.Done, world.dispatcher.execute(GestureAction.ToggleTheme))

            assertEquals(
                PaletteSelection(ColorThemePreset.GRUVBOX_DARK, null, PaletteMode.LIGHT),
                world.appearance.state.value.paletteSelection,
            )
        }

    @Test fun `a palette with no light-dark partner keeps what it has`() =
        runTest {
            val world = world(appearance = AppearanceConfig(theme = ColorThemePreset.KANAGAWA_DRAGON))

            assertEquals(GestureActionResult.Unavailable, world.dispatcher.execute(GestureAction.ToggleTheme))
            assertEquals(ColorThemePreset.KANAGAWA_DRAGON, world.appearance.state.value.theme)
        }

    @Test fun `the stock system preset pins the opposite of whatever the OS is showing`() =
        runTest {
            val dark = world(appearance = AppearanceConfig(theme = ColorThemePreset.SYSTEM), systemDark = true)
            assertEquals(GestureActionResult.Done, dark.dispatcher.execute(GestureAction.ToggleTheme))
            assertEquals(
                PaletteSelection(ColorThemePreset.SYSTEM, null, PaletteMode.LIGHT),
                dark.appearance.state.value.paletteSelection,
            )

            val light = world(appearance = AppearanceConfig(theme = ColorThemePreset.SYSTEM), systemDark = false)
            assertEquals(GestureActionResult.Done, light.dispatcher.execute(GestureAction.ToggleTheme))
            assertEquals(
                PaletteSelection(ColorThemePreset.SYSTEM, null, PaletteMode.DARK),
                light.appearance.state.value.paletteSelection,
            )
        }

    @Test fun `newly paired palettes toggle without changing family`() =
        runTest {
            listOf(ColorThemePreset.DRACULA, ColorThemePreset.TOKYO_NIGHT).forEach { builtin ->
                val legacy = world(appearance = AppearanceConfig(theme = builtin, followSystem = true))
                assertEquals(GestureActionResult.Done, legacy.dispatcher.execute(GestureAction.ToggleTheme))
                assertEquals(PaletteSelection(builtin, null, PaletteMode.LIGHT), legacy.appearance.state.value.paletteSelection)
                listOf(false, true).forEach { systemDark ->
                    val world =
                        world(
                            appearance = AppearanceConfig(theme = builtin, paletteMode = PaletteMode.SYSTEM),
                            systemDark = systemDark,
                        )
                    assertEquals(GestureActionResult.Done, world.dispatcher.execute(GestureAction.ToggleTheme))
                    val expectedMode = if (systemDark) PaletteMode.LIGHT else PaletteMode.DARK
                    assertEquals(PaletteSelection(builtin, null, expectedMode), world.appearance.state.value.paletteSelection)
                }
            }
        }

    @Test fun `custom system and explicit modes toggle atomically preserving installed identity`() =
        runTest {
            val colors =
                PaletteColors(
                    primary = "#336699",
                    secondary = "#663399",
                    tertiary = "#996633",
                    background = "#101010",
                    surface = "#202020",
                    surfaceContainer = "#303030",
                    onBackground = "#FFFFFF",
                    onSurface = "#FFFFFF",
                    onSurfaceVariant = "#CCCCCC",
                    outlineVariant = "#888888",
                )
            val installed = InstalledPalette(PaletteDocument("custom", "Custom", 1, light = colors, dark = colors))
            listOf(PaletteMode.SYSTEM, PaletteMode.LIGHT, PaletteMode.DARK).forEach { mode ->
                val config =
                    AppearanceConfig(
                        theme = ColorThemePreset.NORD,
                        paletteMode = mode,
                        customPaletteId = "custom",
                        customPalettes = listOf(installed),
                    )
                val world = world(appearance = config, systemDark = true)
                val emissions = mutableListOf<AppearanceConfig>()
                val collector =
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        world.appearance.state.collect { emissions += it }
                    }
                assertEquals(GestureActionResult.Done, world.dispatcher.execute(GestureAction.ToggleTheme))
                val expectedMode = if (mode == PaletteMode.LIGHT) PaletteMode.DARK else PaletteMode.LIGHT
                assertEquals(PaletteSelection(ColorThemePreset.NORD, "custom", expectedMode), emissions.last().paletteSelection)
                assertEquals(listOf(installed), emissions.last().customPalettes)
                assertEquals(2, emissions.size)
                collector.cancel()
            }
            listOf(installed.document.copy(light = null), installed.document.copy(dark = null)).forEach { document ->
                val config =
                    AppearanceConfig(
                        customPaletteId = "custom",
                        paletteMode = PaletteMode.SYSTEM,
                        customPalettes = listOf(InstalledPalette(document)),
                    )
                val world = world(appearance = config)
                assertEquals(GestureActionResult.Unavailable, world.dispatcher.execute(GestureAction.ToggleTheme))
                assertEquals(config, world.appearance.state.value)
            }
        }

    @Test fun `an action from a newer build is inert`() =
        runTest {
            val world = world()
            val nav = world.navLog(this)

            val unknown = decodeAction("""{"type":"summonPony","name":"pinkie"}""")

            assertEquals(GestureActionResult.Unavailable, world.dispatcher.execute(unknown))
            runCurrent()
            assertTrue(nav.isEmpty())
        }

    // --- fixtures ---

    private fun decodeAction(json: String): GestureAction = gestureMenuJson.decodeFromString(GestureActionSerializer, json)

    private inner class World(
        val dispatcher: GestureActionDispatcher,
        val connections: FakeConnections,
        val buffers: FakeBuffers,
        val drafts: ComposerDraftStore,
        val attachments: AttachmentRequestStore,
        val appearance: FakeAppearance,
    ) {
        /** Subscribes before the case acts: a request with no listener is dropped, by design. */
        fun navLog(scope: TestScope): List<GestureNavRequest> {
            val log = mutableListOf<GestureNavRequest>()
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                dispatcher.navRequests.collect { log += it }
            }
            return log
        }
    }

    private fun world(
        currentBuffer: Long? = null,
        networks: List<io.github.trevarj.motd.data.db.NetworkEntity> = emptyList(),
        appearance: AppearanceConfig = AppearanceConfig(),
        readMarkers: FakeReadMarkers = FakeReadMarkers(),
        systemDark: Boolean = false,
    ): World {
        val connections = FakeConnections()
        val buffers = FakeBuffers()
        val drafts = ComposerDraftStore(db)
        val attachments = AttachmentRequestStore()
        val appearancePrefs = FakeAppearance(appearance)
        val dispatcher =
            GestureActionDispatcher(
                connections = connections,
                buffers = buffers,
                networks = FakeNetworks(networks),
                drafts = drafts,
                attachments = attachments,
                appearance = appearancePrefs,
                foregroundBuffer = FakeForegroundBuffer(currentBuffer),
                readMarkers = readMarkers,
                defaultAwayMessage = { "Away" },
                systemDark = { systemDark },
            )
        return World(dispatcher, connections, buffers, drafts, attachments, appearancePrefs)
    }
}
