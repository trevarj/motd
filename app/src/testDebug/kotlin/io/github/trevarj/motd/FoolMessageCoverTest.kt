package io.github.trevarj.motd

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Region
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioCacheStatus
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickMentionStyle
import io.github.trevarj.motd.data.prefs.SurfaceEffects
import io.github.trevarj.motd.ui.chat.InlineMessageTranslation
import io.github.trevarj.motd.ui.chat.InlineTranslation
import io.github.trevarj.motd.ui.chat.VoiceTranscriptState
import io.github.trevarj.motd.ui.components.AudioAttachmentPlayers
import io.github.trevarj.motd.ui.components.FOOL_BODY_RANGE
import io.github.trevarj.motd.ui.components.FoolBodyConcealment
import io.github.trevarj.motd.ui.components.FoolBodyText
import io.github.trevarj.motd.ui.components.FoolMessageScope
import io.github.trevarj.motd.ui.components.LocalAutomaticRemoteMedia
import io.github.trevarj.motd.ui.components.MessageBubble
import io.github.trevarj.motd.ui.components.ReactionChip
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme
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
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28, 35], qualifiers = "w411dp-h891dp")
class FoolMessageCoverTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun overflowingAccessibilityActionsStillReachEveryExactLink() {
        val urls = List(36) { "https://example.org/p$it?ref=a%2Fb#last" }
        val body =
            buildAnnotatedString {
                repeat(36) {
                    withLink(LinkAnnotation.Clickable("mention:peer$it", linkInteractionListener = {})) { append("@peer$it") }
                    append(' ')
                }
                for (url in urls) {
                    withLink(LinkAnnotation.Url(url)) { append(url) }
                    append(' ')
                }
            }
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                FoolBodyText(body, Modifier.testTag("overflowing_links"), concealment = FoolBodyConcealment(), onMentionLongClick = {})
            }
        }
        val actions =
            compose
                .onNodeWithTag("overflowing_links")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
        assertEquals(31, actions.size)
        val linkLabel = RuntimeEnvironment.getApplication().getString(R.string.chat_link_actions)
        compose.runOnIdle { assertTrue(actions.single { it.label == linkLabel }.action()) }

        compose.onNodeWithTag("link_action_choices").performScrollToIndex(urls.lastIndex)
        val lastChoice = hasText(urls.last()) and hasAnyAncestor(hasTestTag("link_action_sheet"))
        compose.onNode(lastChoice).performClick()
        compose.onNodeWithTag("link_action_choices").assertDoesNotExist()
        compose.onNodeWithTag("link_action_copy").assertIsDisplayed()
        compose.onNode(lastChoice).assertIsDisplayed()
    }

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
                    val textBounds = text.fetchSemanticsNode().boundsInRoot
                    val sampleBounds = sample.fetchSemanticsNode().boundsInRoot
                    val origin = textBounds.topLeft - sampleBounds.topLeft
                    val visible = sample.captureToImage().toPixelMap()
                    val path = layout.getPathForRange(start, end).asAndroidPath()
                    path.offset(origin.x, origin.y)
                    // Paragraph selection paths can extend past the text layer; drawRect cannot.
                    // Only fully covered pixels inside its exclusive right/bottom edges are body samples.
                    val textClip =
                        Region(
                            ceil(origin.x).toInt(),
                            ceil(origin.y).toInt(),
                            floor(origin.x + layout.size.width).toInt(),
                            floor(origin.y + layout.size.height).toInt(),
                        ).apply { op(Region(0, 0, visible.width, visible.height), Region.Op.INTERSECT) }
                    val region = Region().apply { setPath(path, textClip) }
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
                                assertEquals(
                                    "non-body pixel at $x,$y ($mode/$messageKind/$enabled, SDK ${Build.VERSION.SDK_INT}); " +
                                        "origin=$origin, layout=${layout.size}, textBounds=$textBounds, sampleBounds=$sampleBounds",
                                    visible[x, y],
                                    hidden[x, y],
                                )
                            }
                            if (region.contains(x - 1, y) && region.contains(x + 1, y) && region.contains(x, y - 1) && region.contains(x, y + 1)) {
                                originalEdges += abs(visible[x, y].luminance() - visible[x - 1, y].luminance())
                                concealedEdges += abs(hidden[x, y].luminance() - hidden[x - 1, y].luminance())
                                if (!enabled || Build.VERSION.SDK_INT < 31) {
                                    assertEquals(
                                        "opaque body fallback at $x,$y ($mode/$messageKind/$enabled, SDK ${Build.VERSION.SDK_INT}); " +
                                            "visible=${visible[x, y]}, origin=$origin, layout=${layout.size}, " +
                                            "textBounds=$textBounds, sampleBounds=$sampleBounds",
                                        fallback,
                                        hidden[x, y],
                                    )
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
    fun boundaryMentionChipsNeverLeakColoredPaddingWhileConcealed() {
        val concealed = mutableStateOf(false)
        val density = mutableStateOf(LayoutDensity.COMFORTABLE)
        val kind = mutableStateOf(MessageKind.PRIVMSG)
        val effects = mutableStateOf(false)
        val captured = mutableStateOf<TextLayoutResult?>(null)
        val inlineAnnotationTag =
            buildAnnotatedString { appendInlineContent("fixture", "x") }
                .getStringAnnotations(0, 1)
                .single()
                .tag
        val body = "bob wrapped body שלום العربية keeps the native author prefix and ends with bob"
        var chipColor = Color.Unspecified
        var fallback = Color.Unspecified
        compose.setContent {
            MotdTheme(
                dynamicColor = false,
                layoutDensity = density.value,
                surfaceEffects = SurfaceEffects(enabled = effects.value),
                nickMentionStyle = NickMentionStyle.CHIP,
                nickColorOverrides = mapOf("bob" to 300, "fool" to 120),
            ) {
                chipColor = LocalNickColors.current.mention("bob")
                fallback = MaterialTheme.colorScheme.surfaceContainerHighest
                Column(Modifier.width(290.dp).background(MaterialTheme.colorScheme.background).testTag("chip_sample")) {
                    val original = captured.value
                    if (original == null) {
                        MessageBubble("fool", body, 1000L, false, kind.value, true, formattedTime = "12:34", knownNicks = setOf("bob"))
                    } else {
                        val annotated =
                            original.layoutInput.text.flatMapAnnotations { annotation ->
                                if (annotation.tag == inlineAnnotationTag) emptyList() else listOf(annotation)
                            }
                        FoolBodyText(
                            text = annotated,
                            style = original.layoutInput.style,
                            modifier = Modifier.width(with(LocalDensity.current) { original.size.width.toDp() }),
                            concealment = FoolBodyConcealment(concealed.value, { concealed.value = false }, "reveal"),
                        )
                    }
                }
            }
        }
        val proofs = File("build/reports/nick-mention-concealment").apply { mkdirs() }
        for (mode in LayoutDensity.entries) {
            for (messageKind in listOf(MessageKind.PRIVMSG, MessageKind.ACTION)) {
                for (enabled in listOf(false, true)) {
                    val label = "$mode-$messageKind-effects-$enabled-sdk-${Build.VERSION.SDK_INT}"
                    compose.runOnIdle {
                        density.value = mode
                        kind.value = messageKind
                        effects.value = enabled
                        captured.value = null
                        concealed.value = false
                    }
                    val production = compose.onNodeWithText(body, substring = true, useUnmergedTree = true).layout()
                    val annotated = production.layoutInput.text
                    val ranges = annotated.getStringAnnotations(FOOL_BODY_RANGE, 0, annotated.length)
                    compose.runOnIdle { captured.value = production }
                    val node = compose.onNodeWithText(body, substring = true, useUnmergedTree = true)
                    val layout = node.layout()
                    val sample = compose.onNodeWithTag("chip_sample")
                    val textBounds = node.fetchSemanticsNode().boundsInRoot
                    val origin = textBounds.topLeft - sample.fetchSemanticsNode().boundsInRoot.topLeft
                    val start = ranges.minOfOrNull { it.start } ?: 0
                    val end = ranges.maxOfOrNull { it.end } ?: annotated.length
                    val bodyPath = layout.getPathForRange(start, end).asAndroidPath().apply { offset(origin.x, origin.y) }
                    val visibleImage = sample.captureToImage()
                    compose.runOnIdle { concealed.value = true }
                    val hiddenBounds = compose.onNodeWithTag("reveal", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    val hiddenImage = sample.captureToImage()
                    for ((state, image) in listOf(
                        "visible" to visibleImage,
                        "concealed" to hiddenImage,
                    )) {
                        File(proofs, "$label-$state.png").outputStream().use {
                            assertTrue(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    }
                    assertEquals(
                        "$label retains the production body boundary",
                        body,
                        annotated.text.substring(ranges.minOfOrNull { it.start } ?: 0, ranges.maxOfOrNull { it.end } ?: annotated.length),
                    )
                    assertTrue("$label fixture must wrap", production.lineCount > 1)
                    val visible = visibleImage.toPixelMap()
                    val hidden = hiddenImage.toPixelMap()
                    assertEquals("$label preserves concealed native text bounds", textBounds, hiddenBounds)
                    assertTrue("$label uses real inline chip placeholders", layout.placeholderRects.count { it != null } >= 2)
                    assertTrue(
                        "$label renders colored chip pixels",
                        (0 until visible.height).sumOf { y ->
                            (0 until visible.width).count { x -> visible[x, y] == chipColor }
                        } > 3,
                    )
                    assertEquals(visible.width, hidden.width)
                    assertEquals(visible.height, hidden.height)
                    var originalEdges = 0.0
                    var concealedEdges = 0.0
                    var opaquePixels = 0
                    val pixel = Path()
                    val intersection = Path()
                    val outside = Path()
                    for (y in 0 until hidden.height) {
                        for (x in 0 until hidden.width) {
                            assertFalse("$label leaks chip color at $x,$y", hidden[x, y] == chipColor)
                            pixel.rewind()
                            pixel.addRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, Path.Direction.CW)
                            intersection.op(bodyPath, pixel, Path.Op.INTERSECT)
                            if (intersection.isEmpty) {
                                assertEquals("$label wholly outside native body at $x,$y", visible[x, y], hidden[x, y])
                            } else {
                                pixel.rewind()
                                pixel.addRect(x - 1f, y - 1f, x + 2f, y + 2f, Path.Direction.CW)
                                outside.op(pixel, bodyPath, Path.Op.DIFFERENCE)
                                if (outside.isEmpty &&
                                    x - 1f >= origin.x && y - 1f >= origin.y &&
                                    x + 2f <= origin.x + layout.size.width &&
                                    y + 2f <= origin.y + layout.size.height
                                ) {
                                    originalEdges += abs(visible[x, y].luminance() - visible[x - 1, y].luminance())
                                    concealedEdges += abs(hidden[x, y].luminance() - hidden[x - 1, y].luminance())
                                    if (!enabled || Build.VERSION.SDK_INT < 31) {
                                        assertEquals("$label opaque body fill at $x,$y", fallback, hidden[x, y])
                                        opaquePixels++
                                    }
                                }
                            }
                        }
                    }
                    assertTrue("$label visible body has readable edges", originalEdges > 1.0)
                    if (enabled && Build.VERSION.SDK_INT >= 31) {
                        assertTrue("$label native blur obscures chip and glyph edges", concealedEdges < originalEdges * 0.35)
                    } else {
                        assertTrue("$label samples real opaque body pixels", opaquePixels > 100)
                    }
                    compose.onNodeWithText(body, substring = true, useUnmergedTree = true).assertDoesNotExist()
                    compose.runOnIdle { concealed.value = false }
                    val restored = node.layout()
                    assertEquals(annotated.text, restored.layoutInput.text.text)
                    for (candidate in listOf(layout, restored)) {
                        assertEquals("$label preserves native width", production.size, candidate.size)
                        assertEquals("$label preserves native wrapping", production.lineCount, candidate.lineCount)
                        assertEquals("$label preserves native inline cells", production.placeholderRects, candidate.placeholderRects)
                        for (offset in annotated.text.indices) {
                            assertEquals(
                                "$label preserves native offset $offset",
                                production.getBoundingBox(offset),
                                candidate.getBoundingBox(offset),
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun actionAuthorRemainsNativeAndFirstBodyGestureNeverRunsRowOrLinkAction() {
        val concealed = mutableStateOf(false)
        val density = mutableStateOf(LayoutDensity.COMFORTABLE)
        val style = mutableStateOf(NickMentionStyle.BOLD)
        val body = "waves @bob https://example.org/hello"
        var authors = 0
        var rows = 0
        val opened = mutableListOf<String>()
        val profiles = mutableListOf<String>()
        compose.setContent {
            MotdTheme(dynamicColor = false, layoutDensity = density.value, nickMentionStyle = style.value, nickColorsEnabled = false) {
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
                                knownNicks = setOf("bob"),
                                onMentionClick = { profiles += it },
                                onClick = { rows++ },
                                onLongPress = { rows++ },
                            )
                        }
                    }
                }
            }
        }
        for ((mode, mentionStyle) in LayoutDensity.entries.flatMap { mode -> NickMentionStyle.entries.map { mode to it } }) {
            compose.runOnIdle {
                density.value = mode
                style.value = mentionStyle
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
            val mentionPoint = layout.getBoundingBox(text.text.indexOf("@bob") + 2).center + origin
            compose.runOnIdle { concealed.value = true }
            compose.onNodeWithTag("sample").performTouchInput { click(authorPoint) }
            compose.runOnIdle { assertTrue(concealed.value) }
            compose.onNodeWithText(body, substring = true, useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("sample").performTouchInput { click(mentionPoint) }
            compose.runOnIdle {
                assertFalse(concealed.value)
                assertTrue(profiles.isEmpty())
                assertEquals(0, rows)
                assertTrue(opened.isEmpty())
            }
            compose.onNodeWithTag("sample").performTouchInput { click(mentionPoint) }
            compose.runOnIdle {
                assertEquals(listOf("bob"), profiles)
                profiles.clear()
                assertEquals(0, rows)
                concealed.value = true
            }
            val hiddenActions =
                revealBody()
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsActions.CustomActions)
                    .orEmpty()
            assertTrue(hiddenActions.none { it.label.contains("https://") })
            compose.onNodeWithTag("sample").performTouchInput { longClick(bodyPoint) }
            compose.onNodeWithTag("link_action_sheet").assertDoesNotExist()
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
        compose.runOnIdle { assertEquals(LayoutDensity.entries.size * NickMentionStyle.entries.size, authors) }
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
