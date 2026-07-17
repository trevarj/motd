package io.github.trevarj.motd.fuzz

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import io.github.trevarj.motd.ui.chat.InlineTextSegment
import io.github.trevarj.motd.ui.chat.extractUrls
import io.github.trevarj.motd.ui.chat.isImageUrl
import io.github.trevarj.motd.ui.chat.messageUrls
import io.github.trevarj.motd.ui.chat.parseInlineCode
import io.github.trevarj.motd.ui.components.linkifiedBody
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePresentationGenerativeTest {
    @Test
    fun arbitraryMessageTextProducesDeterministicBoundedPresentationModels() {
        SeededFuzz.run(
            target = "message-presentation",
            version = 1,
            prCases = 2_000,
            nightlyCases = 75_000,
            replayTest = javaClass.name,
        ) { fuzz ->
            val text = fuzz.random.presentationText()
            fuzz.record("text=${text.traceSummary()}")

            val segments = parseInlineCode(text)
            val expectedBody = segments.joinToString("") { it.text }
            val urls = extractUrls(text)
            val classified = messageUrls(text)
            val body = linkifiedBody(
                text = text,
                linkColor = Color.Blue,
                mentionColor = { token -> if (token.equals("bob", ignoreCase = true)) Color.Red else null },
                codeBackground = Color.DarkGray,
                codeColor = Color.White,
            )

            assertEquals(expectedBody, body.text)
            assertEquals(segments, parseInlineCode(text))
            assertEquals(urls, extractUrls(text))
            assertEquals(classified, messageUrls(text))
            assertTrue(segments.isNotEmpty())
            assertTrue(segments.zipWithNext().none { (a, b) -> a is InlineTextSegment.Plain && b is InlineTextSegment.Plain })
            assertTrue(segments.filterIsInstance<InlineTextSegment.Code>().none { it.text.isEmpty() })

            var previous = -1
            urls.forEach { url ->
                assertTrue(url.startsWith("http://") || url.startsWith("https://"))
                val next = text.indexOf(url, previous.coerceAtLeast(0))
                assertTrue("URL order changed for $url", next >= previous)
                previous = next
            }
            assertEquals(urls.firstOrNull(::isImageUrl), classified.imageUrl)
            assertEquals(urls.firstOrNull { !isImageUrl(it) }, classified.linkUrl)

            body.spanStyles.forEach { range ->
                assertTrue(range.start in 0..body.length)
                assertTrue(range.end in range.start..body.length)
            }
            val annotations = body.getLinkAnnotations(0, body.length)
            annotations.forEach { range ->
                assertTrue(range.start in 0..body.length)
                assertTrue(range.end in range.start..body.length)
            }
            val linkedUrls = annotations.map { it.item }
                .filterIsInstance<LinkAnnotation.Url>()
                .map { it.url }
            assertEquals(urls, linkedUrls)
        }
    }
}

private fun Random.presentationText(): String {
    val length = if (nextInt(128) == 0) 65_536 else nextInt(0, 384)
    val pieces = mutableListOf<String>()
    var remaining = length
    val injected = listOf(
        " https://example.com/path?q=1 ",
        " http://images.example/pic.webp), ",
        " `https://code.example @bob` ",
        " `inline code' ",
        " @bob ",
        "\u0002bold\u000f \u0001ACTION\u0001 \u202eRTL\u2069 ",
    )
    while (remaining > 0) {
        val piece = if (nextInt(7) == 0) {
            injected.random(this)
        } else {
            arbitraryUtf16(nextInt(1, minOf(remaining, 48) + 1))
        }
        pieces += piece
        remaining -= piece.length
    }
    return pieces.joinToString("").take(length)
}

private fun Random.arbitraryUtf16(length: Int): String = buildString(length) {
    val controls = listOf('\u0000', '\u0001', '\u0002', '\u000f', '\u202a', '\u202e', '\u2066', '\u2069')
    repeat(length) {
        append(
            when (nextInt(12)) {
                0 -> controls.random(this@arbitraryUtf16)
                1 -> nextInt(0xd800, 0xe000).toChar()
                else -> nextInt(0x20, 0xd800).toChar()
            },
        )
    }
}

private fun String.traceSummary(limit: Int = 180): String = buildString {
    append('"')
    this@traceSummary.take(limit).forEach { char ->
        when (char) {
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
    if (this@traceSummary.length > limit) append("…(${this@traceSummary.length})")
    append('"')
}
