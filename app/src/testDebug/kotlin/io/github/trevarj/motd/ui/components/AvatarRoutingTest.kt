package io.github.trevarj.motd.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.audio.NetworkMediaHttp
import io.github.trevarj.motd.audio.NetworkMediaHttpTest
import io.github.trevarj.motd.audio.NetworkMediaRoute
import io.github.trevarj.motd.avatar.AvatarRecord
import io.github.trevarj.motd.avatar.IrcSpriteV2Renderer
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.AvatarShape
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.MessageSpacing
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.dickord.DICKORD_PORTAL_DMS_KEY
import io.github.trevarj.motd.dickord.DickordChannelDescriptor
import io.github.trevarj.motd.dickord.DickordPortalContent
import io.github.trevarj.motd.dickord.DickordPortalGroup
import io.github.trevarj.motd.dickord.DickordPortalState
import io.github.trevarj.motd.dickord.presentDickordPortal
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.PinningTrustManager
import io.github.trevarj.motd.ui.chatlist.DrawerRow
import io.github.trevarj.motd.ui.chatlist.ServerDrawerContent
import io.github.trevarj.motd.ui.theme.LocalAvatarStyle
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.identityRamp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarRoutingTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun avatarShapesUpdateRealSpritesInitialsMonogramsAndChannelNetworkBadgesAndRestoreDefault() {
        val shape = mutableStateOf(AvatarShape.DEFAULT)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("This fixture must use the real sprite catalog", IrcSpriteV2Renderer.available(context))
        val people = listOf("sprite", "initials", "monogram")
        val channels = listOf("motd", "devicon", "channel", "channel_initials", "channel_monogram", "network")
        compose.setContent {
            MotdTheme(dynamicColor = false, themePreset = ColorThemePreset.LIGHT, avatarShape = shape.value) {
                Column(Modifier.background(Color.Magenta)) {
                    Row {
                        Avatar("alice", size = 64.dp, modifier = Modifier.testTag("sprite"))
                        CompositionLocalProvider(LocalAvatarStyle provides AvatarStyle.INITIALS) {
                            Avatar("alice", size = 64.dp, modifier = Modifier.testTag("initials"))
                        }
                        CompositionLocalProvider(LocalAvatarStyle provides AvatarStyle.MONOGRAM) {
                            Avatar("alice", size = 64.dp, modifier = Modifier.testTag("monogram"))
                        }
                    }
                    Row {
                        Avatar("#motd", isChannel = true, size = 64.dp, modifier = Modifier.testTag("motd"))
                        Avatar("#kotlin", isChannel = true, size = 64.dp, modifier = Modifier.testTag("devicon"))
                        Avatar("#ordinary", isChannel = true, size = 64.dp, modifier = Modifier.testTag("channel"))
                    }
                    Row {
                        CompositionLocalProvider(LocalAvatarStyle provides AvatarStyle.INITIALS) {
                            Avatar("#ordinary", isChannel = true, size = 64.dp, modifier = Modifier.testTag("channel_initials"))
                        }
                        CompositionLocalProvider(LocalAvatarStyle provides AvatarStyle.MONOGRAM) {
                            Avatar("#ordinary", isChannel = true, size = 64.dp, modifier = Modifier.testTag("channel_monogram"))
                        }
                        IrcNetworkBadge(64.dp, Modifier.testTag("network"))
                    }
                }
            }
        }
        compose.onNodeWithText("AL", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("A", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(
            "Generated artwork must not silently become initials",
            0,
            compose.onAllNodes(hasAnyAncestor(hasTestTag("sprite")) and hasText("AL"), useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag("motd_channel_logo", useUnmergedTree = true).assertIsDisplayed()
        val defaults = (people + channels).associateWith { compose.onNodeWithTag(it).captureToImage().asAndroidBitmap() }
        val bounds = (people + channels).associateWith { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        for (choice in listOf(AvatarShape.DEFAULT, AvatarShape.SQUARE, AvatarShape.SOFT, AvatarShape.ROUNDED, AvatarShape.CIRCLE, AvatarShape.DEFAULT)) {
            compose.runOnIdle { shape.value = choice }
            for (tag in people + channels) {
                assertAvatarMask(tag, choice, tag in channels, Color.Magenta)
                assertEquals("$tag bounds stay fixed for $choice", bounds.getValue(tag), compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
                if (choice == AvatarShape.DEFAULT) {
                    assertTrue("$tag restores exact Default pixels", defaults.getValue(tag).sameAs(compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()))
                }
            }
        }
    }

    @Test
    fun nondefaultMonogramAndChannelBordersMatchTheirSelectedOutline() {
        val shape = mutableStateOf(AvatarShape.SQUARE)
        val channel = mutableStateOf(false)
        val reference = mutableStateOf(false)
        compose.setContent {
            MotdTheme(dynamicColor = false, themePreset = ColorThemePreset.LIGHT, avatarShape = shape.value) {
                Box(Modifier.size(64.dp).background(Color.Magenta).testTag("border_sample")) {
                    val name = if (channel.value) "#kotlin" else "alice"
                    if (!reference.value) {
                        CompositionLocalProvider(LocalAvatarStyle provides if (channel.value) AvatarStyle.IRC_SPRITE else AvatarStyle.MONOGRAM) {
                            Avatar(name, isChannel = channel.value, size = 64.dp)
                        }
                    } else {
                        val expectedShape =
                            when (shape.value) {
                                AvatarShape.SQUARE -> RoundedCornerShape(percent = 0)
                                AvatarShape.SOFT -> RoundedCornerShape(percent = 10)
                                else -> RoundedCornerShape(percent = 30)
                            }
                        val nick = LocalNickColors.current.avatar(name)
                        val fill =
                            if (channel.value) {
                                Modifier.background(nick)
                            } else {
                                val base = MaterialTheme.colorScheme.surfaceContainerHigh
                                Modifier.background(Brush.verticalGradient(listOf(nick.copy(alpha = 0.18f).compositeOver(base), nick.copy(alpha = 0.07f).compositeOver(base))))
                            }
                        val border = if (channel.value) identityRamp(nick).highlight.copy(alpha = 0.90f) else nick.copy(alpha = 0.40f)
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(expectedShape)
                                .then(fill)
                                .border(1.dp, border, expectedShape),
                        )
                    }
                }
            }
        }
        for (isChannel in listOf(false, true)) {
            for (choice in listOf(AvatarShape.SQUARE, AvatarShape.SOFT, AvatarShape.ROUNDED)) {
                compose.runOnIdle {
                    channel.value = isChannel
                    shape.value = choice
                    reference.value = false
                }
                val actual = compose.onNodeWithTag("border_sample").captureToImage().toPixelMap()
                compose.runOnIdle { reference.value = true }
                val expected = compose.onNodeWithTag("border_sample").captureToImage().toPixelMap()
                // Compare the entire outside strip at the same origin, without the identity glyph.
                for (y in 0 until actual.height) {
                    for (x in 0 until actual.width) {
                        if (x < actual.width / 10 || y < actual.height / 10 ||
                            x >= actual.width * 9 / 10 || y >= actual.height * 9 / 10
                        ) {
                            assertEquals("channel=$isChannel $choice border at $x,$y", expected[x, y], actual[x, y])
                        }
                    }
                }
            }
        }
    }

    @Test
    fun inFlightImageKeepsSelectedFallbackGeometryAndRestoresDefault() {
        Fixture().use { fixture ->
            val release = java.util.concurrent.CountDownLatch(1)
            val arrived = java.util.concurrent.CountDownLatch(1)
            fixture.server.dispatcher =
                object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                        arrived.countDown()
                        release.await()
                        return fixture.imageResponse()
                    }
                }
            val shape = mutableStateOf(AvatarShape.DEFAULT)
            try {
                compose.setContent {
                    MotdTheme(dynamicColor = false, avatarStyle = AvatarStyle.INITIALS, avatarShape = shape.value) {
                        CompositionLocalProvider(LocalAutomaticRemoteMedia provides true, LocalNetworkMediaHttp provides fixture.http) {
                            Box(Modifier.background(Color.Magenta)) {
                                Avatar("alice", size = 64.dp, networkId = 7, conversationModel = fixture.url("loading-shape"), modifier = Modifier.testTag("loading_shape"))
                            }
                        }
                    }
                }
                compose.waitUntil(10_000) {
                    compose.runOnIdle {}
                    arrived.count == 0L
                }
                val default = compose.onNodeWithTag("loading_shape").captureToImage().asAndroidBitmap()
                for (choice in listOf(AvatarShape.DEFAULT, AvatarShape.SQUARE, AvatarShape.SOFT, AvatarShape.ROUNDED, AvatarShape.DEFAULT)) {
                    compose.runOnIdle { shape.value = choice }
                    compose.onNodeWithText("AL", useUnmergedTree = true).assertIsDisplayed()
                    assertAvatarMask("loading_shape", choice, isChannel = false, backdrop = Color.Magenta)
                    assertTrue("The response remains in flight", fixture.loaded.isEmpty() && fixture.errors.isEmpty())
                }
                assertTrue("Loading fallback restores exact Default pixels", default.sameAs(compose.onNodeWithTag("loading_shape").captureToImage().asAndroidBitmap()))
                assertEquals(1, fixture.server.requestCount)
            } finally {
                release.countDown()
            }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.contains(fixture.url("loading-shape")) } }
            compose.onNodeWithText("AL", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(emptyList<String>(), fixture.errors.toList())
            assertEquals(listOf(7L), fixture.selectedNetworks.toList())
        }
    }

    @Test
    fun missingSpriteCatalogKeepsSelectedInitialsFallbackAndRestoresDefault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = IrcSpriteV2Renderer.catalogForTest(context)
        assertTrue("The normal catalog must be restored after this fixture", catalog != null)
        // Simulate the renderer's cached missing-catalog result without altering packaged assets.
        ReflectionHelpers.setField(IrcSpriteV2Renderer, "catalog", null)
        ReflectionHelpers.setField(IrcSpriteV2Renderer, "catalogAttempted", true)
        try {
            assertFalse(IrcSpriteV2Renderer.available(context))
            val shape = mutableStateOf(AvatarShape.DEFAULT)
            compose.setContent {
                MotdTheme(dynamicColor = false, avatarStyle = AvatarStyle.IRC_SPRITE, avatarShape = shape.value) {
                    Box(Modifier.background(Color.Magenta)) {
                        Avatar("alice", size = 64.dp, modifier = Modifier.testTag("missing_catalog"))
                    }
                }
            }
            compose.onNodeWithText("AL", useUnmergedTree = true).assertIsDisplayed()
            val default = compose.onNodeWithTag("missing_catalog").captureToImage().asAndroidBitmap()
            for (choice in listOf(AvatarShape.DEFAULT, AvatarShape.SQUARE, AvatarShape.SOFT, AvatarShape.ROUNDED, AvatarShape.DEFAULT)) {
                compose.runOnIdle { shape.value = choice }
                compose.onNodeWithText("AL", useUnmergedTree = true).assertIsDisplayed()
                assertAvatarMask("missing_catalog", choice, isChannel = false, backdrop = Color.Magenta)
            }
            assertTrue("Missing-catalog fallback restores exact Default pixels", default.sameAs(compose.onNodeWithTag("missing_catalog").captureToImage().asAndroidBitmap()))
        } finally {
            ReflectionHelpers.setField(IrcSpriteV2Renderer, "catalog", catalog)
        }
    }

    @Test
    fun loadedLocalRemoteAndProposedImagesFollowPersonAndChannelShapesAndRestoreDefault() {
        Fixture().use { fixture ->
            val local = Uri.fromFile(temporaryFolder.newFile("shape-avatar.png").apply { writeBytes(PNG) })
            val shape = mutableStateOf(AvatarShape.DEFAULT)
            val channel = mutableStateOf(false)
            val branch = mutableStateOf(0)
            compose.setContent {
                MotdTheme(dynamicColor = false, themePreset = ColorThemePreset.LIGHT, avatarShape = shape.value) {
                    CompositionLocalProvider(LocalAutomaticRemoteMedia provides true, LocalNetworkMediaHttp provides fixture.http) {
                        Box(Modifier.background(Color.Magenta)) {
                            when (branch.value) {
                                0 -> Avatar("alice", isChannel = channel.value, size = 88.dp, conversationModel = local.toString(), modifier = Modifier.testTag("loaded_image"))
                                1 -> Avatar("alice", isChannel = channel.value, size = 88.dp, networkId = 7, conversationModel = fixture.url("shape"), modifier = Modifier.testTag("loaded_image"))
                                2 -> LocalAvatarProposal(local, isChannel = channel.value)
                                else -> ExplicitAvatarUrlPreview(fixture.url("proposal-shape"), 7, "nick:alice", isChannel = channel.value)
                            }
                        }
                    }
                }
            }
            for (imageBranch in 0..3) {
                if (imageBranch == 2) fixture.loaded.clear()
                if (imageBranch == 1) fixture.server.enqueue(fixture.imageResponse())
                compose.runOnIdle {
                    branch.value = imageBranch
                    channel.value = false
                    shape.value = AvatarShape.DEFAULT
                }
                for (isChannel in listOf(false, true)) {
                    compose.runOnIdle { channel.value = isChannel }
                    if (imageBranch == 3) {
                        fixture.loaded.clear()
                        fixture.server.enqueue(fixture.imageResponse())
                        compose.onNodeWithTag("avatar_url_preview").performClick()
                    }
                    val model =
                        when (imageBranch) {
                            0, 2 -> local.toString()
                            1 -> fixture.url("shape")
                            else -> fixture.url("proposal-shape")
                        }
                    compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.contains(model) } }
                    val tag =
                        when (imageBranch) {
                            0, 1 -> "loaded_image"
                            2 -> "avatar_local_proposal"
                            else -> "avatar_url_proposal"
                        }
                    val default = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
                    for (choice in listOf(AvatarShape.DEFAULT, AvatarShape.SQUARE, AvatarShape.SOFT, AvatarShape.ROUNDED, AvatarShape.CIRCLE, AvatarShape.DEFAULT)) {
                        compose.runOnIdle { shape.value = choice }
                        assertAvatarMask(tag, choice, isChannel, Color.Magenta)
                    }
                    assertTrue("branch=$imageBranch channel=$isChannel restores exact Default image", default.sameAs(compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()))
                }
            }
            assertEquals(emptyList<String>(), fixture.errors.toList())
            assertEquals(setOf(7L), fixture.selectedNetworks.toSet())
        }
    }

    @Test
    fun drawerGeneratedBouncerAndDownloadedNetworkArtworkShareShapeWithoutChangingStatusOrRow() {
        Fixture().use { fixture ->
            val shape = mutableStateOf(AvatarShape.DEFAULT)
            val branch = mutableStateOf(0)
            var backdrop = Color.Unspecified
            compose.setContent {
                MotdTheme(dynamicColor = false, themePreset = ColorThemePreset.LIGHT, avatarShape = shape.value) {
                    backdrop = MaterialTheme.colorScheme.secondaryContainer
                    CompositionLocalProvider(LocalAutomaticRemoteMedia provides true) {
                        Drawer(
                            iconUrl = if (branch.value == 2) fixture.url("shape-icon") else null,
                            networkId = 8,
                            role = if (branch.value == 1) NetworkRole.BOUNCER_ROOT else NetworkRole.DIRECT,
                        )
                    }
                }
            }
            for (artBranch in 0..2) {
                if (artBranch == 2) fixture.server.enqueue(fixture.imageResponse())
                compose.runOnIdle {
                    branch.value = artBranch
                    shape.value = AvatarShape.DEFAULT
                }
                if (artBranch == 2) compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.contains(fixture.url("shape-icon")) } }
                val icon = compose.onNodeWithTag("drawer_network_icon_8", useUnmergedTree = true)
                val default = icon.captureToImage().asAndroidBitmap()
                val bounds = icon.fetchSemanticsNode().boundsInRoot
                val status = compose.onNodeWithTag("drawer_network_status_8", useUnmergedTree = true)
                val statusDefault = status.captureToImage().toPixelMap()
                for (choice in listOf(AvatarShape.DEFAULT, AvatarShape.SQUARE, AvatarShape.SOFT, AvatarShape.ROUNDED, AvatarShape.CIRCLE, AvatarShape.DEFAULT)) {
                    compose.runOnIdle { shape.value = choice }
                    assertAvatarMask("drawer_network_icon_8", choice, isChannel = true, backdrop = backdrop)
                    assertEquals("drawer art bounds", bounds, icon.fetchSemanticsNode().boundsInRoot)
                    val pixels = status.captureToImage().toPixelMap()
                    for (y in pixels.height / 4 until pixels.height * 3 / 4) {
                        for (x in pixels.width / 4 until pixels.width * 3 / 4) {
                            assertEquals("Status artwork must not reshape", statusDefault[x, y], pixels[x, y])
                        }
                    }
                }
                assertTrue("branch=$artBranch restores exact Default artwork", default.sameAs(icon.captureToImage().asAndroidBitmap()))
            }
            assertEquals(emptyList<String>(), fixture.errors.toList())
            assertEquals(listOf(8L), fixture.selectedNetworks.toList())
        }
    }

    @Test
    fun failedLocalImageKeepsSelectedFallbackAndNoneStillRendersNothing() {
        Fixture().use { fixture ->
            val local = Uri.fromFile(temporaryFolder.newFile("broken-shape-avatar.png").apply { writeText("not an image") }).toString()
            val style = mutableStateOf(AvatarStyle.INITIALS)
            compose.setContent {
                MotdTheme(dynamicColor = false, avatarShape = AvatarShape.SQUARE, avatarStyle = style.value) {
                    Box(Modifier.background(Color.Magenta)) {
                        Avatar("alice", conversationModel = local, size = 64.dp, modifier = Modifier.testTag("failed_shape"))
                    }
                }
            }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.errors.isNotEmpty() } }
            compose.onNodeWithText("AL", useUnmergedTree = true).assertIsDisplayed()
            assertAvatarMask("failed_shape", AvatarShape.SQUARE, isChannel = false, backdrop = Color.Magenta)
            val requests = fixture.started.size
            compose.runOnIdle { style.value = AvatarStyle.NONE }
            compose.onNodeWithTag("failed_shape").assertDoesNotExist()
            compose.onNodeWithText("AL", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(requests, fixture.started.size)
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun comfortableOrdinaryAndActionAvatarShadowsMatchSelectedPersonOutline() {
        Fixture().use { fixture ->
            val file = temporaryFolder.newFile("transparent-shape-shadow.png")
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).let { bitmap ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val local = Uri.fromFile(file).toString()
            val shape = mutableStateOf(AvatarShape.DEFAULT)
            val kind = mutableStateOf(MessageKind.PRIVMSG)
            val reference = mutableStateOf(false)
            val avatarBounds = mutableStateOf<Rect?>(null)
            compose.setContent {
                MotdTheme(
                    dynamicColor = false,
                    themePreset = ColorThemePreset.LIGHT,
                    layoutDensity = LayoutDensity.COMFORTABLE,
                    avatarShape = shape.value,
                    chatShadowsEnabled = !reference.value,
                ) {
                    CompositionLocalProvider(
                        LocalAutomaticRemoteMedia provides true,
                        LocalRemoteAvatars provides RemoteAvatarState(true, listOf(fixture.avatar().copy(url = fixture.url("shadow")))),
                    ) {
                        Box(
                            Modifier
                                .width(320.dp)
                                .height(160.dp)
                                .background(Color.White)
                                .testTag("shadow_sample"),
                        ) {
                            MessageBubble("alice", "Hello", 0, isSelf = false, showSender = true, kind = kind.value, networkId = 7)
                            if (reference.value) {
                                avatarBounds.value?.let { bounds ->
                                    val density = LocalDensity.current
                                    val expectedShape =
                                        when (shape.value) {
                                            AvatarShape.DEFAULT, AvatarShape.CIRCLE -> CircleShape
                                            AvatarShape.SQUARE -> RoundedCornerShape(percent = 0)
                                            AvatarShape.SOFT -> RoundedCornerShape(percent = 10)
                                            AvatarShape.ROUNDED -> RoundedCornerShape(percent = 30)
                                        }
                                    Avatar(
                                        "alice",
                                        size = with(density) { bounds.width.toDp() },
                                        conversationModel = local,
                                        modifier =
                                            Modifier
                                                .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                                                .shadow(if (kind.value == MessageKind.ACTION) 1.dp else 2.dp, expectedShape)
                                                .testTag("reference_shadow_avatar"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            for (messageKind in listOf(MessageKind.PRIVMSG, MessageKind.ACTION)) {
                fixture.server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(file.readBytes())))
                if (messageKind == MessageKind.ACTION) fixture.loaded.clear()
                compose.runOnIdle { kind.value = messageKind }
                compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.contains(fixture.url("shadow")) } }
                val sample = compose.onNodeWithTag("shadow_sample")
                for (choice in AvatarShape.entries) {
                    compose.runOnIdle {
                        reference.value = false
                        shape.value = choice
                    }
                    val bounds = compose.onNodeWithTag("chat_sender_avatar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    val root = sample.fetchSemanticsNode().boundsInRoot
                    val actual = sample.captureToImage().toPixelMap()
                    fixture.loaded.clear()
                    compose.runOnIdle {
                        avatarBounds.value = bounds.translate(-root.left, -root.top)
                        reference.value = true
                    }
                    compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.contains(local) } }
                    val expected = sample.captureToImage().toPixelMap()
                    val left = (bounds.left - root.left).roundToInt()
                    val top = (bounds.top - root.top).roundToInt()
                    for (y in 0 until bounds.height.roundToInt()) {
                        for (x in 0 until bounds.width.roundToInt()) {
                            assertEquals("$messageKind $choice shadow at $x,$y", expected[left + x, top + y], actual[left + x, top + y])
                        }
                    }
                }
                compose.runOnIdle { reference.value = false }
            }
        }
    }

    private fun assertAvatarMask(
        tag: String,
        choice: AvatarShape,
        isChannel: Boolean,
        backdrop: Color,
    ) {
        val pixels = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        val percent =
            when (choice) {
                AvatarShape.DEFAULT -> if (isChannel) 30 else 50
                AvatarShape.SQUARE -> 0
                AvatarShape.SOFT -> 10
                AvatarShape.ROUNDED -> 30
                AvatarShape.CIRCLE -> 50
            }
        for ((index, fraction) in listOf(0.01f, 0.06f, 0.12f, 0.20f).withIndex()) {
            val x = (pixels.width * fraction).toInt()
            val y = (pixels.height * fraction).toInt()
            val clipped =
                when (percent) {
                    0 -> false
                    10 -> index == 0
                    30 -> index <= 1
                    else -> index <= 2
                }
            if (clipped) {
                assertEquals("$tag $choice clipped corner at $fraction", backdrop, pixels[x, y])
            } else {
                assertTrue("$tag $choice paints corner at $fraction", pixels[x, y] != backdrop)
            }
        }
    }

    @Test
    fun monogramGlyphRemainsPaintedAndCenteredAtLargeFontScales() {
        val size = mutableStateOf(20.dp)
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(surfaceContainerHigh = Color.White, onSurface = Color.Black)) {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale.value),
                    LocalAvatarStyle provides AvatarStyle.MONOGRAM,
                ) {
                    Box(Modifier.background(Color.White)) {
                        Avatar("trev", size = size.value, modifier = Modifier.testTag("monogram"))
                    }
                }
            }
        }
        for (avatarSize in listOf(20.dp, 40.dp)) {
            var baselineGlyph: Rect? = null
            var baselineInk: Rect? = null
            for (scale in listOf(1f, 1.5f, 2f)) {
                compose.runOnIdle {
                    size.value = avatarSize
                    fontScale.value = scale
                }
                val label = "$avatarSize, fontScale=$scale"
                val letter = compose.onNodeWithText("T", useUnmergedTree = true).assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                letter.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                val glyph = layout.getBoundingBox(0)
                val letterBounds = letter.fetchSemanticsNode().boundsInRoot
                val avatar = compose.onNodeWithTag("monogram")
                val avatarBounds = avatar.fetchSemanticsNode().boundsInRoot
                assertEquals(label, 1, layout.lineCount)
                assertFalse("$label must not overflow vertically", layout.didOverflowHeight)
                assertTrue("$label glyph must fit", glyph.top >= 0 && glyph.bottom <= letterBounds.height)
                assertEquals("$label horizontal layout center", avatarBounds.center.x, letterBounds.center.x, 1f)
                assertEquals("$label vertical layout center", avatarBounds.center.y, letterBounds.center.y, 1f)

                val pixels = avatar.captureToImage().toPixelMap()
                var minX = pixels.width
                var minY = pixels.height
                var maxX = -1
                var maxY = -1
                // Tiny antialiased strokes need not contain any fully ink-colored pixels.
                // Compare against the painted gradient on the same row, away from the ring.
                for (y in pixels.height / 4 until pixels.height * 3 / 4) {
                    val backdrop = pixels[pixels.width / 4, y]
                    for (x in pixels.width / 4 + 1 until pixels.width * 3 / 4) {
                        val color = pixels[x, y]
                        if (abs(color.red - backdrop.red) + abs(color.green - backdrop.green) +
                            abs(color.blue - backdrop.blue) > 0.12f
                        ) {
                            minX = minOf(minX, x)
                            minY = minOf(minY, y)
                            maxX = maxOf(maxX, x)
                            maxY = maxOf(maxY, y)
                        }
                    }
                }
                val ink = Rect(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
                assertTrue("$label must paint a letter, not an empty disc or sliver: $ink", ink.width >= pixels.width * 0.15f && ink.height >= pixels.height * 0.2f)
                assertEquals("$label horizontal painted center", pixels.width / 2f, ink.center.x, pixels.width * 0.1f)
                assertEquals("$label vertical painted center", pixels.height / 2f, ink.center.y, pixels.height * 0.1f)
                baselineGlyph?.let {
                    assertEquals("$label glyph width must retain its geometry", it.width, glyph.width, 1f)
                    assertEquals("$label glyph height must retain its geometry", it.height, glyph.height, 1f)
                }
                baselineInk?.let {
                    assertEquals("$label painted width", it.width, ink.width, 1f)
                    assertEquals("$label painted height", it.height, ink.height, 1f)
                }
                baselineGlyph = baselineGlyph ?: glyph
                baselineInk = baselineInk ?: ink
            }
        }
    }

    @Test
    fun defaultNetworkGlyphIsCenteredWithinItsRoundedSquareBackground() {
        compose.setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(surfaceContainerHighest = Color.White, onSurfaceVariant = Color.Black),
            ) {
                Box(Modifier.background(Color.White)) {
                    IrcNetworkBadge(size = 100.dp, modifier = Modifier.testTag("network_badge"))
                }
            }
        }
        val pixels = compose.onNodeWithTag("network_badge").captureToImage().toPixelMap()
        var minX = pixels.width
        var minY = pixels.height
        var maxX = -1
        var maxY = -1
        var inkX = 0.0
        var inkY = 0.0
        var inkWeight = 0.0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val weight = (1f - pixels[x, y].red).toDouble()
                if (weight > 0.5) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
                inkX += x * weight
                inkY += y * weight
                inkWeight += weight
            }
        }
        assertTrue("The rendered topology glyph must be visible", maxX >= minX && maxY >= minY)
        assertEquals("Horizontal glyph center", pixels.width / 2f, (minX + maxX + 1) / 2f, 1f)
        assertTrue("Glyph must fit inside the rounded-square background", minY > pixels.height * 0.15 && maxY < pixels.height * 0.85)
        assertEquals("Horizontal ink center", pixels.width / 2.0, inkX / inkWeight + 0.5, pixels.width * 0.01)
        assertEquals("Vertical ink center", pixels.height / 2.0, inkY / inkWeight + 0.5, pixels.height * 0.01)
    }

    @Test
    fun localAvatarProposalNeverStartsRemoteImagesAndReportsLocalDecodeFailure() {
        Fixture().use { fixture ->
            val uri = mutableStateOf(Uri.parse("http://media-test.invalid:${fixture.server.port}/unbound.png"))
            val local = Uri.fromFile(temporaryFolder.newFile("proposal.png").apply { writeBytes(PNG) })
            val broken = Uri.fromFile(temporaryFolder.newFile("broken-proposal.png").apply { writeText("not an image") })
            compose.setContent { MotdTheme(dynamicColor = false) { LocalAvatarProposal(uri.value, isChannel = false) } }
            for (remote in listOf("http://media-test.invalid:${fixture.server.port}/unbound.png", fixture.url("unbound"), "data:image/png;base64,AAAA", "unsupported://image")) {
                compose.runOnIdle { uri.value = Uri.parse(remote) }
                compose.onNodeWithTag("avatar_local_proposal").assertDoesNotExist()
                compose.onNodeWithTag("avatar_local_proposal_error").assertIsDisplayed()
                compose.runOnIdle { assertEquals(emptyList<String>(), fixture.started.toList()) }
            }
            compose.runOnIdle { uri.value = local }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.contains(local.toString()) } }
            compose.onNodeWithTag("avatar_local_proposal").assertIsDisplayed()
            compose.onNodeWithTag("avatar_local_proposal_error").assertDoesNotExist()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val resource = Uri.parse("android.resource://${context.packageName}/${io.github.trevarj.motd.R.drawable.motd_logo_mark}")
            compose.runOnIdle { uri.value = resource }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.contains(resource.toString()) } }
            compose.onNodeWithTag("avatar_local_proposal").assertIsDisplayed()
            compose.onNodeWithTag("avatar_local_proposal_error").assertDoesNotExist()
            compose.runOnIdle { uri.value = broken }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.errors.any { it.startsWith(broken.toString()) } } }
            compose.onNodeWithTag("avatar_local_proposal_error").assertIsDisplayed()
            assertEquals(0, fixture.server.requestCount)
            assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())
        }
    }

    @Test
    fun documentConfirmationRejectsRemoteUriBeforeImagePipeAndLoadsLocalImage() {
        Fixture().use { fixture ->
            val local = Uri.fromFile(temporaryFolder.newFile("document-proposal.png").apply { writeBytes(PNG) })
            val source =
                mutableStateOf(
                    io.github.trevarj.motd.attachment.AttachmentSource
                        .Document(Uri.parse(fixture.url("unbound-document")), "image.png", "image/png", null),
                )
            var uploads = 0
            compose.setContent {
                MotdTheme(dynamicColor = false) {
                    io.github.trevarj.motd.ui.chat
                        .ConfirmationSheet(
                            source.value,
                            io.github.trevarj.motd.attachment
                                .PasteBackendConfig(),
                            false,
                            {},
                            {},
                            { uploads++ },
                        )
                }
            }
            compose.onNodeWithTag("attachment_thumbnail").assertDoesNotExist()
            compose.onNodeWithTag("attachment_thumbnail_error").assertIsDisplayed()
            compose.onNodeWithTag("attachment_upload").assertIsNotEnabled()
            compose.runOnIdle {
                assertEquals(emptyList<String>(), fixture.started.toList())
                assertEquals(0, uploads)
            }
            compose.runOnIdle { source.value = source.value.copy(uri = local) }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.contains(local.toString()) } }
            compose.onNodeWithTag("attachment_thumbnail").assertIsDisplayed()
            compose.onNodeWithTag("attachment_upload").assertIsEnabled()
            compose.runOnIdle { assertEquals(0, uploads) }
            assertEquals(0, fixture.server.requestCount)
            assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())
        }
    }

    @Test
    fun explicitAvatarPreviewBindsConsentToSavedOwnerAndTargetAndFailsClosed() {
        Fixture().use { fixture ->
            val release = java.util.concurrent.CountDownLatch(1)
            val arrived = java.util.concurrent.CountDownLatch(1)
            fixture.server.dispatcher =
                object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                        if (request.path == "/first.png") {
                            arrived.countDown()
                            release.await(5, TimeUnit.SECONDS)
                        }
                        return fixture.imageResponse()
                    }
                }
            val url = mutableStateOf(fixture.url("first"))
            val owner = mutableStateOf<Long?>(null)
            val identity = mutableStateOf("first-person")
            val visible = mutableStateOf(true)
            compose.setContent {
                MotdTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalNetworkMediaHttp provides fixture.http) {
                        if (visible.value) ExplicitAvatarUrlPreview(url.value, owner.value, identity.value, isChannel = false)
                    }
                }
            }
            try {
                compose.onNodeWithTag("avatar_url_preview").assertIsNotEnabled()
                compose.runOnIdle { owner.value = 7 }
                compose.onNodeWithTag("avatar_url_preview").assertIsEnabled()
                compose.runOnIdle { assertEquals(emptyList<String>(), fixture.started.toList()) }
                compose.onNodeWithTag("avatar_url_preview").performClick()
                compose.waitUntil(10_000) {
                    compose.runOnIdle {}
                    arrived.count == 0L
                }

                // Hold the old response until after a different target and URL own the editor.
                compose.runOnIdle {
                    url.value = fixture.url("second")
                    owner.value = 8
                    identity.value = "second-person"
                }
                compose.onNodeWithTag("avatar_url_proposal").assertDoesNotExist()
                assertEquals(1, fixture.server.requestCount)
                release.countDown()
                compose.onNodeWithTag("avatar_url_preview").performClick()
                compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.contains(fixture.url("second")) } }
                compose.onNodeWithTag("avatar_url_proposal").assertIsDisplayed()
                assertEquals(setOf(7L, 8L), fixture.selectedNetworks.toSet())

                compose.runOnIdle {
                    owner.value = 9
                    url.value = fixture.url("broken")
                }
                compose.onNodeWithTag("avatar_url_proposal").assertDoesNotExist()
                compose.onNodeWithTag("avatar_url_preview").performClick()
                compose.waitUntil(10_000) { compose.runOnIdle { fixture.errors.any { it.contains("/broken.png") } } }
                assertEquals(2, fixture.server.requestCount)
                assertTrue(fixture.selectedNetworks.contains(9L))

                compose.runOnIdle { visible.value = false }
                compose.onNodeWithTag("avatar_url_proposal").assertDoesNotExist()
                compose.runOnIdle { visible.value = true }
                compose.onNodeWithTag("avatar_url_proposal").assertDoesNotExist()
                assertEquals(2, fixture.server.requestCount)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun fictionalLayoutSamplesKeepReadableTextWithoutActionsOrRealAvatarRequests() {
        Fixture().use { fixture ->
            val density = mutableStateOf(LayoutDensity.COMFORTABLE)
            val alex = fixture.avatar().copy(identity = "nick:alex", nick = "alex")
            compose.setContent {
                MotdTheme(dynamicColor = false) {
                    CompositionLocalProvider(
                        LocalAutomaticRemoteMedia provides true,
                        LocalRemoteAvatars provides RemoteAvatarState(true, listOf(alex)),
                    ) {
                        ChatLayoutPreview(
                            density = density.value,
                            messageSpacing = MessageSpacing.DEFAULT,
                            bubbleCorners = BubbleCornerStyle.ROUNDED,
                            wallpaper = WallpaperSelection(),
                            conversationFontScalePercent = 100,
                            tagPrefix = "fictional",
                        )
                    }
                }
            }
            for (style in LayoutDensity.entries) {
                compose.runOnIdle { density.value = style }
                compose.onNodeWithText("See you in the channel!", substring = true, useUnmergedTree = true).assertExists()
                val actions =
                    hasClickAction() or SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
                val sample = hasAnyAncestor(hasTestTag("fictional_configuration"))
                assertEquals(0, compose.onAllNodes(actions and sample, useUnmergedTree = true).fetchSemanticsNodes().size)
                compose.runOnIdle {
                    assertEquals(emptyList<String>(), fixture.started.toList())
                    assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())
                    assertEquals(0, fixture.server.requestCount)
                }
            }
        }
    }

    @Test
    fun automaticLoadingUsesSourceAvatarAndDrawerIconOwnersThroughRealSocksTls() {
        Fixture().use { fixture ->
            val automatic = mutableStateOf(false)
            val avatar = fixture.avatar()
            fixture.server.enqueue(fixture.imageResponse())
            fixture.server.enqueue(fixture.imageResponse())
            compose.setContent {
                MotdTheme(dynamicColor = false, avatarStyle = AvatarStyle.IRC_SPRITE) {
                    CompositionLocalProvider(
                        LocalAutomaticRemoteMedia provides automatic.value,
                        LocalRemoteAvatars provides RemoteAvatarState(true, listOf(avatar)),
                    ) {
                        Column {
                            // Global views retain the record's owner rather than borrowing the icon's network.
                            Avatar("alice", modifier = Modifier.testTag("avatar"))
                            Drawer(fixture.url("icon"), 8)
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.errors.size == 2 } }
            assertEquals(0, fixture.server.requestCount)
            assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())
            fixture.errors.clear()

            compose.runOnIdle { automatic.value = true }
            // Coil completes on Android's main looper; pump it while awaiting background HTTP/decode.
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.size == 2 || fixture.errors.isNotEmpty() } }
            assertEquals(emptyList<String>(), fixture.errors.toList())
            compose.onNodeWithTag("avatar").assertIsDisplayed()
            compose.onNodeWithTag("drawer_network_icon_8", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(setOf(7L, 8L), fixture.selectedNetworks.toSet())
            assertEquals(listOf("media-test.invalid"), fixture.avatarProxy.destinations.toList())
            assertEquals(listOf("media-test.invalid"), fixture.iconProxy.destinations.toList())
            val requests = List(2) { fixture.server.takeRequest(5, TimeUnit.SECONDS)!! }
            assertEquals(setOf("/avatar.png", "/icon.png"), requests.map { it.path }.toSet())
            requests.forEach { assertNull(it.getHeader("Authorization")) }
        }
    }

    @Test
    fun missingBrokenAndAmbiguousOwnersNeverFetchRemoteAvatarsOrIcons() {
        Fixture().use { fixture ->
            val avatar = fixture.avatar()
            compose.setContent {
                MotdTheme(dynamicColor = false, avatarStyle = AvatarStyle.IRC_SPRITE) {
                    CompositionLocalProvider(
                        LocalAutomaticRemoteMedia provides true,
                        LocalRemoteAvatars provides RemoteAvatarState(true, listOf(avatar, avatar.copy(networkId = 8))),
                    ) {
                        Column {
                            Avatar("alice") // Same URL across networks is not a unique owner.
                            Avatar("ownerless", conversationModel = fixture.url("ownerless"))
                            Avatar("missing", networkId = 404, conversationModel = fixture.url("missing"))
                            Drawer(fixture.url("broken"), 9)
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.errors.size == 3 } }
            assertEquals(setOf(404L, 9L), fixture.selectedNetworks.toSet())
            assertEquals(0, fixture.server.requestCount)
            assertEquals(emptyList<String>(), fixture.avatarProxy.destinations.toList())
            assertEquals(emptyList<String>(), fixture.iconProxy.destinations.toList())
            assertEquals(emptyList<String>(), fixture.loaded.toList())
        }
    }

    @Test
    fun importedLocalAvatarOverridesSharedImageWithoutNetworkOrAutomaticLoading() {
        Fixture().use { fixture ->
            val local = Uri.fromFile(temporaryFolder.newFile("avatar.png").apply { writeBytes(PNG) }).toString()
            compose.setContent {
                MotdTheme(dynamicColor = false) {
                    CompositionLocalProvider(
                        LocalAutomaticRemoteMedia provides false,
                        LocalRemoteAvatars provides RemoteAvatarState(true, listOf(fixture.avatar())),
                    ) {
                        Avatar("alice", conversationModel = local, modifier = Modifier.testTag("local_avatar"))
                    }
                }
            }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.contains(local) } }
            compose.onNodeWithTag("local_avatar").assertIsDisplayed()
            assertEquals(0, fixture.server.requestCount)
            assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())
        }
    }

    @Test
    fun transparentLocalAvatarReplacesGeneratedFallbackAfterLoading() {
        Fixture().use { fixture ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val file = File.createTempFile("transparent-avatar", ".png", context.filesDir)
            try {
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                val local = Uri.fromFile(file).toString()
                val background = Color.Magenta
                compose.setContent {
                    MotdTheme(dynamicColor = false, avatarStyle = AvatarStyle.INITIALS) {
                        CompositionLocalProvider(LocalAutomaticRemoteMedia provides false) {
                            Box(Modifier.background(background)) {
                                Avatar("alice", conversationModel = local, modifier = Modifier.testTag("transparent_avatar"))
                            }
                        }
                    }
                }
                compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.contains(local) } }
                val pixels = compose.onNodeWithTag("transparent_avatar").captureToImage().toPixelMap()
                assertEquals(background, pixels[pixels.width / 2, pixels.height / 2])
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun portalServerArtworkRoutesOnlyExplicitIconsAndKeepsNavigationClickable() {
        Fixture().use { fixture ->
            val automatic = mutableStateOf(true)
            val avatarStyle = mutableStateOf(AvatarStyle.IRC_SPRITE)
            val sharedImages = mutableStateOf(false)
            val iconUrl = mutableStateOf<String?>(fixture.url("portal-icon"))
            val selectedGroups = mutableListOf<String>()
            val unrelatedAvatar =
                AvatarRecord(
                    networkId = 8,
                    identity = "nick:example server",
                    nick = "example server",
                    account = null,
                    url = fixture.url("borrowed-avatar"),
                    updatedAt = 1,
                )
            compose.setContent {
                MotdTheme(dynamicColor = false, avatarStyle = avatarStyle.value) {
                    CompositionLocalProvider(
                        LocalAutomaticRemoteMedia provides automatic.value,
                        LocalRemoteAvatars provides RemoteAvatarState(sharedImages.value, listOf(unrelatedAvatar)),
                    ) {
                        DickordPortalContent(
                            state =
                                DickordPortalState(
                                    loading = false,
                                    enabled = true,
                                    groups =
                                        listOf(
                                            DickordPortalGroup(
                                                key = DICKORD_PORTAL_DMS_KEY,
                                                networkId = null,
                                                guildId = null,
                                                displayName = null,
                                                iconUrl = null,
                                                conversations = emptyList(),
                                            ),
                                            DickordPortalGroup(
                                                key = "guild:8:100",
                                                networkId = 8,
                                                guildId = "100",
                                                displayName = "Example Server",
                                                iconUrl = iconUrl.value,
                                                conversations = emptyList(),
                                            ),
                                        ),
                                    offline = false,
                                ),
                            onSelectGroup = selectedGroups::add,
                        )
                    }
                }
            }

            fun clickServer() {
                compose.onNodeWithTag("dickord_server_8_100").assertHasClickAction().performClick()
            }

            compose.waitForIdle()
            clickServer()
            assertNull(fixture.server.takeRequest(250, TimeUnit.MILLISECONDS))
            assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())

            compose.runOnIdle {
                sharedImages.value = true
                iconUrl.value = null
            }
            compose.waitForIdle()
            clickServer()
            assertNull(fixture.server.takeRequest(250, TimeUnit.MILLISECONDS))
            assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())

            compose.runOnIdle {
                automatic.value = false
                iconUrl.value = fixture.url("portal-icon")
            }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.errors.size == 1 } }
            clickServer()
            assertEquals(0, fixture.server.requestCount)
            assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())
            fixture.errors.clear()

            fixture.server.enqueue(fixture.imageResponse())
            compose.runOnIdle { automatic.value = true }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.size == 1 || fixture.errors.isNotEmpty() } }
            assertEquals(emptyList<String>(), fixture.errors.toList())
            assertEquals(listOf(fixture.url("portal-icon")), fixture.loaded.toList())
            assertEquals(listOf(8L), fixture.selectedNetworks.toList())
            assertEquals(listOf("media-test.invalid"), fixture.iconProxy.destinations.toList())
            val request = fixture.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/portal-icon.png", request.path)
            assertNull(request.getHeader("Authorization"))

            compose.runOnIdle { avatarStyle.value = AvatarStyle.NONE }
            compose.waitForIdle()
            clickServer()
            assertEquals(1, fixture.server.requestCount)
            assertEquals(List(4) { "guild:8:100" }, selectedGroups)
        }
    }

    @Test
    fun portalDirectAndGroupDmIconsDecodeAndRouteThroughTheOwningNetwork() {
        Fixture().use { fixture ->
            val automatic = mutableStateOf(true)
            val sharedImages = mutableStateOf(false)
            val explicitIcon = fixture.url("dm-icon")
            val unrelatedAvatar =
                AvatarRecord(
                    networkId = 8,
                    identity = "nick:alice smith",
                    nick = "alice smith",
                    account = null,
                    url = fixture.url("borrowed-dm"),
                    updatedAt = 1,
                )
            val conversation =
                ChatListRow(
                    bufferId = 81,
                    networkId = 8,
                    networkName = "Bridge",
                    displayName = "#discord.dm.alice",
                    type = BufferType.CHANNEL,
                    pinned = false,
                    muted = false,
                    lastMessageText = null,
                    lastMessageSender = null,
                    lastMessageTime = null,
                    unreadCount = 0,
                    mentionCount = 0,
                    archived = false,
                )
            val descriptor =
                mutableStateOf(
                    DickordChannelDescriptor(
                        v = 1,
                        guildId = null,
                        guildName = null,
                        channelId = "801",
                        channelType = 1,
                        parentId = null,
                        channelName = "Alice Smith",
                        channelIconUrl = explicitIcon,
                    ),
                )
            compose.setContent {
                MotdTheme(dynamicColor = false, avatarStyle = AvatarStyle.INITIALS) {
                    CompositionLocalProvider(
                        LocalAutomaticRemoteMedia provides automatic.value,
                        LocalRemoteAvatars provides RemoteAvatarState(sharedImages.value, listOf(unrelatedAvatar)),
                    ) {
                        DickordPortalContent(
                            state =
                                DickordPortalState(
                                    loading = false,
                                    enabled = true,
                                    groups =
                                        presentDickordPortal(
                                            listOf(conversation.copy(dickordChannelJson = Json.encodeToString(descriptor.value))),
                                            archived = false,
                                        ),
                                    selectedGroupKey = DICKORD_PORTAL_DMS_KEY,
                                    offline = false,
                                ),
                        )
                    }
                }
            }

            compose.waitForIdle()
            compose.onNodeWithTag("dickord_conversation_81").assertHasClickAction()
            compose.onNodeWithText("AS", useUnmergedTree = true).assertIsDisplayed()
            assertNull(fixture.server.takeRequest(250, TimeUnit.MILLISECONDS))
            assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())

            compose.runOnIdle {
                sharedImages.value = true
                descriptor.value = descriptor.value.copy(channelIconUrl = null)
            }
            compose.waitForIdle()
            assertNull(fixture.server.takeRequest(250, TimeUnit.MILLISECONDS))
            assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())

            compose.runOnIdle {
                automatic.value = false
                descriptor.value = descriptor.value.copy(channelIconUrl = explicitIcon)
            }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.errors.size == 1 } }
            assertEquals(0, fixture.server.requestCount)
            assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())
            compose.onNodeWithText("AS", useUnmergedTree = true).assertIsDisplayed()
            fixture.errors.clear()

            fixture.server.enqueue(fixture.imageResponse())
            compose.runOnIdle { automatic.value = true }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.size == 1 || fixture.errors.isNotEmpty() } }

            assertEquals(emptyList<String>(), fixture.errors.toList())
            assertEquals(listOf(explicitIcon), fixture.loaded.toList())
            assertEquals(listOf(8L), fixture.selectedNetworks.toList())
            assertEquals(listOf("media-test.invalid"), fixture.iconProxy.destinations.toList())
            val request = fixture.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/dm-icon.png", request.path)
            assertNull(request.getHeader("Authorization"))

            val groupIcon = fixture.url("group-dm-icon")
            compose.runOnIdle {
                sharedImages.value = false
                descriptor.value =
                    descriptor.value.copy(
                        channelType = 3,
                        channelName = "Weekend plans",
                        channelIconUrl = groupIcon,
                    )
            }
            compose.waitForIdle()
            compose.onNodeWithText("Weekend plans").assertIsDisplayed()
            compose.onNodeWithTag("dickord_conversation_81").assertHasClickAction()
            assertNull(fixture.server.takeRequest(250, TimeUnit.MILLISECONDS))
            assertEquals(listOf(8L), fixture.selectedNetworks.toList())

            fixture.server.enqueue(fixture.imageResponse())
            compose.runOnIdle { sharedImages.value = true }
            compose.waitUntil(10_000) { compose.runOnIdle { fixture.loaded.size == 2 || fixture.errors.isNotEmpty() } }
            assertEquals(emptyList<String>(), fixture.errors.toList())
            assertEquals(listOf(explicitIcon, groupIcon), fixture.loaded.toList())
            assertEquals(listOf(8L, 8L), fixture.selectedNetworks.toList())
            val groupRequest = fixture.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/group-dm-icon.png", groupRequest.path)
            assertNull(groupRequest.getHeader("Authorization"))
        }
    }

    @androidx.compose.runtime.Composable
    private fun Drawer(
        iconUrl: String?,
        networkId: Long,
        role: NetworkRole = NetworkRole.DIRECT,
    ) {
        ServerDrawerContent(
            drawerRows =
                listOf(
                    DrawerRow(
                        networkId = networkId,
                        name = "Network",
                        role = role,
                        depth = 0,
                        state = IrcClientState.Disconnected,
                        nick = null,
                        unread = 0,
                        mentions = 0,
                        iconUrl = iconUrl,
                    ),
                ),
            selectedNetworkId = networkId,
            allMentions = 0,
            scopedUnreadCount = 0,
            allOffline = false,
            onSelectNetwork = {},
            onConnect = {},
            onDisconnect = {},
            onServerMessages = {},
            onOpenNetworkSettings = {},
            onAddNetwork = {},
            onToggleOffline = {},
            onOpenSettings = {},
            onMarkAllRead = {},
        )
    }

    private class Fixture : AutoCloseable {
        val server = MockWebServer()
        private val certificate =
            KeyStore.getInstance("PKCS12").let { store ->
                val password = "media-test".toCharArray()
                AvatarRoutingTest::class.java.getResourceAsStream("/network-media.p12")!!.use { store.load(it, password) }
                val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
                server.useHttps(SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }.socketFactory, false)
                server.start()
                store.getCertificate("media") as X509Certificate
            }
        val avatarProxy = NetworkMediaHttpTest.SocksProxy(server.port)
        val iconProxy = NetworkMediaHttpTest.SocksProxy(server.port)
        val selectedNetworks = ConcurrentLinkedQueue<Long>()
        val loaded = ConcurrentLinkedQueue<String>()
        val errors = ConcurrentLinkedQueue<String>()
        val started = ConcurrentLinkedQueue<String>()
        val http =
            NetworkMediaHttp({ id ->
                selectedNetworks += id
                if (id !in 7L..9L) {
                    null
                } else {
                    NetworkMediaRoute(
                        networkId = id,
                        endpoint = NetworkEntity(name = "test", host = "media-test.invalid", port = 6697, role = NetworkRole.DIRECT, nick = "nick", username = "user", realname = "real"),
                        proxy = if (id == 7L) avatarProxy.proxy else iconProxy.proxy,
                        proxyError = if (id == 9L) "Broken route" else null,
                        authorizationHeader = "Basic must-not-leak",
                        endpointPinnedSha256 = PinningTrustManager.sha256Hex(certificate),
                    )
                }
            })
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val previousImageLoader = Coil.imageLoader(context)
        private val imageLoader =
            ImageLoader
                .Builder(context)
                .okHttpClient(http.client)
                .memoryCache(null)
                .diskCache(null)
                .eventListener(
                    object : EventListener {
                        override fun onStart(request: ImageRequest) {
                            started += request.data.toString()
                        }

                        override fun onSuccess(
                            request: ImageRequest,
                            result: SuccessResult,
                        ) {
                            loaded += request.data.toString()
                        }

                        override fun onError(
                            request: ImageRequest,
                            result: ErrorResult,
                        ) {
                            errors += "${request.data}: ${result.throwable}"
                        }
                    },
                ).build()

        init {
            Coil.setImageLoader(imageLoader)
        }

        fun url(name: String) = "https://media-test.invalid:${server.port}/$name.png"

        fun avatar() = AvatarRecord(7, "nick:alice", "alice", null, url("avatar"), 1)

        fun imageResponse() = MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(PNG))

        override fun close() {
            Coil.setImageLoader(previousImageLoader)
            imageLoader.shutdown()
            avatarProxy.close()
            iconProxy.close()
            server.shutdown()
        }
    }

    private companion object {
        val PNG: ByteArray =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            )
    }
}
