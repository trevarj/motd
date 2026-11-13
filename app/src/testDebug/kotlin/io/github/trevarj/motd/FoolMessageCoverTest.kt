package io.github.trevarj.motd

import android.graphics.Region
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioCacheStatus
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.SurfaceEffects
import io.github.trevarj.motd.ui.chat.InlineMessageTranslation
import io.github.trevarj.motd.ui.chat.InlineTranslation
import io.github.trevarj.motd.ui.chat.VoiceTranscriptState
import io.github.trevarj.motd.ui.components.AudioAttachmentPlayers
import io.github.trevarj.motd.ui.components.FOOL_BODY_RANGE
import io.github.trevarj.motd.ui.components.FoolMessageScope
import io.github.trevarj.motd.ui.components.LocalAutomaticRemoteMedia
import io.github.trevarj.motd.ui.components.MessageBubble
import io.github.trevarj.motd.ui.components.ReactionChip
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28, 35], qualifiers = "w411dp-h891dp")
class FoolMessageCoverTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun onlyBodyPixelsChangeWithIdenticalWrapAcrossDensitiesActionsAndEffects() {
        val concealed = mutableStateOf(false)
        val density = mutableStateOf(LayoutDensity.COMFORTABLE)
        val kind = mutableStateOf(MessageKind.PRIVMSG)
        val effects = mutableStateOf(true)
        val body = "wrapped body שלום العربية 123 keeps its original paragraph and author boundary"
        var fallback = Color.Unspecified
        compose.setContent {
            MotdTheme(dynamicColor = false, layoutDensity = density.value, surfaceEffects = SurfaceEffects(enabled = effects.value)) {
                fallback = MaterialTheme.colorScheme.surfaceContainerHighest
                Column(Modifier.width(290.dp).background(MaterialTheme.colorScheme.background).testTag("sample")) {
                    FoolMessageScope(concealed.value, { concealed.value = false }, tag = "reveal") {
                        MessageBubble("fool", body, 1000L, false, kind.value, true, formattedTime = "12:34")
                    }
                }
            }
        }
        for (mode in LayoutDensity.entries) {
            for (messageKind in listOf(MessageKind.PRIVMSG, MessageKind.ACTION)) {
                for (enabled in listOf(false, true)) {
                    compose.runOnIdle {
                        density.value = mode
                        kind.value = messageKind
                        effects.value = enabled
                        concealed.value = false
                    }
                    val text = compose.onNodeWithText(body, substring = true, useUnmergedTree = true)
                    val layout = text.layout()
                    assertTrue("fixture must wrap", layout.lineCount > 1)
                    val annotations = layout.layoutInput.text.getStringAnnotations(FOOL_BODY_RANGE, 0, layout.layoutInput.text.length)
                    val start = annotations.minOfOrNull { it.start } ?: 0
                    val end = annotations.maxOfOrNull { it.end } ?: layout.layoutInput.text.length
                    val sample = compose.onNodeWithTag("sample")
                    val origin = text.fetchSemanticsNode().boundsInRoot.topLeft - sample.fetchSemanticsNode().boundsInRoot.topLeft
                    val visible = sample.captureToImage().toPixelMap()
                    val path = layout.getPathForRange(start, end).asAndroidPath()
                    path.offset(origin.x, origin.y)
                    val region = Region().apply { setPath(path, Region(0, 0, visible.width, visible.height)) }
                    compose.runOnIdle { concealed.value = true }
                    compose.onNodeWithText(body, substring = true, useUnmergedTree = true).assertDoesNotExist()
                    compose.onNodeWithText("12:34", useUnmergedTree = true).assertIsDisplayed()
                    val hidden = sample.captureToImage().toPixelMap()
                    assertEquals(visible.width, hidden.width)
                    assertEquals(visible.height, hidden.height)
                    var originalEdges = 0.0
                    var concealedEdges = 0.0
                    var opaquePixels = 0
                    for (y in 1 until visible.height - 1) {
                        for (x in 1 until visible.width - 1) {
                            val nearBody = (-1..1).any { dx -> (-1..1).any { dy -> region.contains(x + dx, y + dy) } }
                            if (!nearBody) {
                                // Includes the actual avatar, author prefix/header, timestamp and bubble backing.
                                assertEquals("non-body pixel at $x,$y ($mode/$messageKind/$enabled)", visible[x, y], hidden[x, y])
                            }
                            if (region.contains(x - 1, y) && region.contains(x + 1, y) && region.contains(x, y - 1) && region.contains(x, y + 1)) {
                                originalEdges += abs(visible[x, y].luminance() - visible[x - 1, y].luminance())
                                concealedEdges += abs(hidden[x, y].luminance() - hidden[x - 1, y].luminance())
                                if (!enabled || Build.VERSION.SDK_INT < 31) {
                                    assertEquals("opaque body fallback", fallback, hidden[x, y])
                                    opaquePixels++
                                }
                            }
                        }
                    }
                    assertTrue("readable baseline has glyph edges", originalEdges > 1.0)
                    if (enabled && Build.VERSION.SDK_INT >= 31) {
                        assertTrue("40dp blur must remove sharp body glyph edges", concealedEdges < originalEdges * 0.35)
                    } else {
                        assertTrue("fallback sampled actual body pixels", opaquePixels > 100)
                    }
                    compose.runOnIdle { concealed.value = false }
                    val restored = text.layout()
                    assertEquals(layout.lineCount, restored.lineCount)
                    for (offset in 0 until layout.layoutInput.text.length) {
                        assertEquals(layout.getBoundingBox(offset), restored.getBoundingBox(offset))
                    }
                }
            }
        }
    }

    @Test
    fun actionAuthorRemainsNativeAndFirstBodyGestureNeverRunsRowOrLinkAction() {
        val concealed = mutableStateOf(false)
        val density = mutableStateOf(LayoutDensity.COMFORTABLE)
        val body = "waves https://example.org/hello"
        var authors = 0
        var rows = 0
        val opened = mutableListOf<String>()
        compose.setContent {
            MotdTheme(dynamicColor = false, layoutDensity = density.value) {
                CompositionLocalProvider(
                    LocalUriHandler provides
                        object : UriHandler {
                            override fun openUri(uri: String) {
                                opened += uri
                            }
                        },
                ) {
                    Column(Modifier.width(290.dp).testTag("sample")) {
                        FoolMessageScope(concealed.value, { concealed.value = false }, tag = "reveal") {
                            MessageBubble(
                                "fool",
                                body,
                                1000L,
                                false,
                                MessageKind.ACTION,
                                true,
                                onSenderClick = { authors++ },
                                onClick = { rows++ },
                                onLongPress = { rows++ },
                            )
                        }
                    }
                }
            }
        }
        for (mode in LayoutDensity.entries) {
            compose.runOnIdle {
                density.value = mode
                concealed.value = false
            }
            val node = compose.onNodeWithText(body, substring = true, useUnmergedTree = true)
            val layout = node.layout()
            val origin =
                node.fetchSemanticsNode().boundsInRoot.topLeft -
                    compose
                        .onNodeWithTag("sample")
                        .fetchSemanticsNode()
                        .boundsInRoot.topLeft
            val text = layout.layoutInput.text
            val nick = text.text.indexOf("fool")
            val url = text.text.indexOf("https://")
            val authorPoint = layout.getBoundingBox(nick + 1).center + origin
            val bodyPoint = layout.getBoundingBox(url + 1).center + origin
            compose.runOnIdle { concealed.value = true }
            compose.onNodeWithTag("sample").performTouchInput { click(authorPoint) }
            compose.runOnIdle { assertTrue(concealed.value) }
            compose.onNodeWithText(body, substring = true, useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("sample").performTouchInput { click(bodyPoint) }
            compose.runOnIdle {
                assertFalse(concealed.value)
                assertEquals(0, rows)
                assertTrue(opened.isEmpty())
            }
            compose.onNodeWithTag("sample").performTouchInput { click(bodyPoint) }
            compose.runOnIdle {
                assertEquals(listOf("https://example.org/hello"), opened)
                opened.clear()
                concealed.value = true
            }
            revealBody().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onRoot().performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
            compose.runOnIdle {
                assertFalse(concealed.value)
                assertEquals(0, rows)
                assertTrue(opened.isEmpty())
            }
        }
        compose.runOnIdle { assertEquals(LayoutDensity.entries.size, authors) }
    }

    @Test
    fun replyMediaReactionsAndTranslationDismissStayNativeWhileBodyTextIsConcealed() {
        val concealed = mutableStateOf(true)
        var replies = 0
        var dismisses = 0
        var mounts = 0
        var mediaActions = 0
        var reactions = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalAutomaticRemoteMedia provides false) {
                    FoolMessageScope(concealed.value, { concealed.value = false }, tag = "reveal") {
                        DisposableEffect(Unit) {
                            mounts++
                            onDispose {}
                        }
                        MessageBubble(
                            "fool",
                            "Original body",
                            1000L,
                            false,
                            MessageKind.PRIVMSG,
                            true,
                            reply = ReplyPreviewData("other", "Visible reply card"),
                            onReplyClick = { replies++ },
                            mediaUrls = listOf("https://media.example/preview.png"),
                            onLongPress = { mediaActions++ },
                            reactions = listOf(ReactionChip("👍", 1, false)),
                            onReact = { reactions++ },
                            trailingContent = { color ->
                                InlineMessageTranslation(InlineTranslation(1, 2, "Original body", "Translated body"), color) { dismisses++ }
                            },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Original body", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Translated body", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Visible reply card", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithTag("message_translation_dismiss_2").performClick()
        compose.onNodeWithTag("inline_media_awaiting", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag("chat_reaction_chip_👍", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(1, mediaActions)
            assertEquals(1, reactions)
            assertTrue(concealed.value)
        }
        compose.runOnIdle {
            assertEquals(1, replies)
            assertEquals(1, dismisses)
            assertTrue(concealed.value)
        }
        // There are two independently bounded text reveal targets, sharing one stable row owner.
        compose.runOnIdle { concealed.value = false }
        compose.onNodeWithText("Original body", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Translated body", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { concealed.value = true }
        compose.onNodeWithText("Translated body", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, mounts) }
    }

    @Test
    fun audioControlsAndDetailsStayNativeButReadyTranscriptRequiresItsOwnFirstTap() {
        val concealed = mutableStateOf(true)
        val attachment = AudioAttachment(url = "https://files.example/voice.opus", voice = true)
        var plays = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                FoolMessageScope(concealed.value, { concealed.value = false }, tag = "reveal") {
                    AudioAttachmentPlayers(
                        attachments = listOf(attachment),
                        playbackState = AudioPlaybackState(),
                        cacheStatuses = mapOf(attachment.playbackId to AudioCacheStatus.CACHED),
                        networkId = 7,
                        isSelf = false,
                        onToggle = { _, _ -> plays++ },
                        onSeek = { _, _ -> },
                        transcripts = mapOf(attachment.playbackId to VoiceTranscriptState.Ready("Private transcript", cached = true)),
                        transcriptionEnabled = true,
                        transcriptionReady = true,
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Play audio").performClick()
        compose.runOnIdle {
            assertEquals(1, plays)
            assertTrue(concealed.value)
        }
        compose.onNodeWithTag("audio_player_details").performClick()
        compose.onNodeWithText("Private transcript", useUnmergedTree = true).assertDoesNotExist()
        revealBody().performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("Private transcript", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { concealed.value = true }
        compose.onNodeWithText("Private transcript", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun concealedParagraphGrowthAndPreviewUrlRestorationUseTheFreshLayout() {
        val concealed = mutableStateOf(true)
        val density = mutableStateOf(LayoutDensity.COMPACT)
        val kind = mutableStateOf(MessageKind.PRIVMSG)
        val body = mutableStateOf("short")
        val preview = mutableStateOf(true)
        val url = "https://media.example/preview.png"
        compose.setContent {
            MotdTheme(dynamicColor = false, layoutDensity = density.value) {
                CompositionLocalProvider(LocalAutomaticRemoteMedia provides false) {
                    FoolMessageScope(concealed.value, { concealed.value = false }, tag = "reveal") {
                        MessageBubble(
                            "fool",
                            body.value,
                            1000L,
                            false,
                            kind.value,
                            true,
                            formattedTime = "12:34",
                            mediaUrls = if (preview.value) listOf(url) else emptyList(),
                        )
                    }
                }
            }
        }
        for (mode in LayoutDensity.entries) {
            for (messageKind in listOf(MessageKind.PRIVMSG, MessageKind.ACTION)) {
                compose.runOnIdle {
                    density.value = mode
                    kind.value = messageKind
                    body.value = "short"
                    preview.value = true
                    concealed.value = true
                }
                revealBody().assertIsDisplayed()
                compose.runOnIdle {
                    body.value = "A much longer concealed paragraph שלום العربية grows past the previous native layout $url"
                }
                revealBody().assertIsDisplayed()
                compose.onNodeWithText("A much longer concealed paragraph", substring = true, useUnmergedTree = true).assertDoesNotExist()
                // Restoring the URL grows the rendered AnnotatedString without changing stored text.
                compose.runOnIdle { preview.value = false }
                revealBody().assertIsDisplayed()
                compose.onNodeWithText(url, substring = true, useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithText("12:34", useUnmergedTree = true).assertIsDisplayed()
                revealBody().performClick()
                compose.onNodeWithText(url, substring = true, useUnmergedTree = true).assertIsDisplayed()
                compose.runOnIdle {
                    concealed.value = true
                    preview.value = true
                }
                revealBody().assertIsDisplayed()
            }
        }
    }

    @Test
    fun mediaOnlyInlineParagraphsKeepPrefixesSharpAndActionSenderNative() {
        val concealed = mutableStateOf(true)
        val density = mutableStateOf(LayoutDensity.COMPACT)
        val kind = mutableStateOf(MessageKind.PRIVMSG)
        val url = "https://media.example/preview.png"
        var authors = 0
        compose.setContent {
            MotdTheme(dynamicColor = false, layoutDensity = density.value) {
                CompositionLocalProvider(LocalAutomaticRemoteMedia provides false) {
                    Column(Modifier.width(290.dp).testTag("sample")) {
                        FoolMessageScope(concealed.value, { concealed.value = false }, tag = "reveal") {
                            MessageBubble(
                                "fool",
                                url,
                                1000L,
                                false,
                                kind.value,
                                true,
                                mediaUrls = listOf(url),
                                formattedTime = "12:34",
                                onSenderClick = { authors++ },
                            )
                        }
                    }
                }
            }
        }
        for (mode in LayoutDensity.entries) {
            for (messageKind in listOf(MessageKind.PRIVMSG, MessageKind.ACTION)) {
                if (messageKind != MessageKind.ACTION && mode != LayoutDensity.COMPACT) continue
                compose.runOnIdle {
                    density.value = mode
                    kind.value = messageKind
                    concealed.value = false
                }
                val sample = compose.onNodeWithTag("sample")
                val shown = sample.captureToImage().toPixelMap()
                compose.runOnIdle { concealed.value = true }
                revealBody().assertDoesNotExist()
                val prefix =
                    if (messageKind == MessageKind.ACTION) {
                        compose.onNodeWithTag("chat_action_text", useUnmergedTree = true)
                    } else {
                        compose.onNodeWithText("<fool>", useUnmergedTree = true)
                    }
                prefix.assertIsDisplayed()
                compose.onNodeWithText("12:34", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithTag("inline_media_awaiting", useUnmergedTree = true).assertIsDisplayed()
                val hidden = sample.captureToImage().toPixelMap()
                assertEquals(shown.width, hidden.width)
                assertEquals(shown.height, hidden.height)
                for (y in 0 until shown.height) {
                    for (x in 0 until shown.width) assertEquals("media-only native pixel $x,$y", shown[x, y], hidden[x, y])
                }
                if (messageKind == MessageKind.ACTION) {
                    val layout = prefix.layout()
                    val nick =
                        layout.layoutInput.text.text
                            .indexOf("fool")
                    val before = compose.runOnIdle { authors }
                    prefix.performTouchInput { click(layout.getBoundingBox(nick + 1).center) }
                    compose.runOnIdle {
                        assertEquals(before + 1, authors)
                        assertTrue(concealed.value)
                    }
                }
            }
        }
    }

    private fun revealBody() =
        compose.onNodeWithContentDescription(
            org.robolectric.RuntimeEnvironment
                .getApplication()
                .getString(R.string.chat_fool_reveal),
            substring = true,
            useUnmergedTree = true,
        )

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
}
