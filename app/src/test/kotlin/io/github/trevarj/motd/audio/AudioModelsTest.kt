package io.github.trevarj.motd.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioModelsTest {
    @Test fun parsesAudioLinksByExtensionAndSkipsInlineCode() {
        val attachments =
            parseAudioAttachments(
                "listen https://cdn.example/a/show.mp3 and `https://cdn.example/a/hidden.opus` plus https://cdn.example/a/take.opus?dl=1",
            )

        assertEquals(
            listOf("https://cdn.example/a/show.mp3", "https://cdn.example/a/take.opus?dl=1"),
            attachments.map { it.url },
        )
        assertEquals("audio/mpeg", attachments[0].mimeType)
        assertEquals("audio/ogg", attachments[1].mimeType)
    }

    @Test fun parsesCanonicalVoiceFallbackMetadata() {
        assertEquals(
            listOf(0L, 3_599_000L, 3_600_000L),
            listOf("0:00", "59:59", "1:00:00").map(::parseAudioDuration),
        )

        val waveform = AudioWaveform.fromAmplitudes(List(96) { it * 100 })
        val url =
            appendAudioWaveform(
                "https://files.example/voice.motdvoice#motd-key=abc",
                waveform,
            )
        val text = "[voice encrypted 1:00:00 audio/ogg expires=2026-08-28T12:00:00.123Z] $url"
        val attachment = parseAudioAttachments(text).single()

        assertTrue(isCanonicalVoiceFallback(text))
        assertTrue(attachment.voice)
        assertTrue(attachment.encrypted)
        assertEquals(3_600_000L, attachment.durationMs)
        assertEquals("audio/ogg", attachment.mimeType)
        assertEquals("2026-08-28T12:00:00.123Z", attachment.expiry)
        assertEquals(url, attachment.url)
        assertEquals(waveform, attachment.waveform)
        assertEquals("Voice message", attachment.title)
    }

    @Test fun keepsLegacyVoiceExpiryReadable() {
        listOf("72h", "3-100d").forEach { expiry ->
            val text = "[VOICE  0:01 audio/ogg expires=$expiry]  http://files.example/voice.ogg"
            val attachment = parseAudioAttachments(text).single()

            assertTrue(attachment.voice)
            assertEquals(expiry, attachment.expiry)
            assertFalse(isCanonicalVoiceFallback(text))
        }
    }

    @Test fun rejectsMalformedVoiceFallbackMetadata() {
        val malformed =
            listOf(
                "1:99 audio/ogg",
                "1:2 audio/ogg",
                "7 audio/ogg",
                "0:59:59 audio/ogg",
                "60:00 audio/ogg",
                "1:no:02 audio/ogg",
                "9223372036854775808:00 audio/ogg",
                "2562047788016:00:00 audio/ogg",
                "1:02 audio/ogg;",
                "1:02 audio/",
                "1:02 text/plain",
            ).map { metadata -> "[voice $metadata] https://files.example/voice.ogg" }

        malformed.forEach { text ->
            assertFalse(isCanonicalVoiceFallback(text))
            assertFalse(parseAudioAttachments(text).single().voice)
        }
        assertFalse(isCanonicalVoiceFallback("before [voice 0:01 audio/ogg] https://files.example/voice.ogg"))
    }

    @Test fun hidesPureVoiceFallbackText() {
        val text = "[voice 0:03 audio/mp4] https://files.example/voice.m4a"
        val attachments = parseAudioAttachments(text)

        assertEquals("", displayTextForAudioMessage(text, attachments))
        assertEquals("before $text", displayTextForAudioMessage("before $text", attachments))
    }

    @Test fun hidesStandaloneAudioUrlOnlyWhenRequested() {
        val url = "https://files.example/clip.mp3"
        val attachments = parseAudioAttachments(url)

        assertEquals(url, displayTextForAudioMessage(url, attachments))
        assertEquals("", displayTextForAudioMessage(url, attachments, suppressStandaloneUrl = true))
        assertEquals("", displayTextForAudioMessage(" \n$url\t ", attachments, suppressStandaloneUrl = true))
        listOf("listen $url", "$url caption", "`$url`", "$url $url").forEach { text ->
            assertEquals(text, displayTextForAudioMessage(text, attachments, suppressStandaloneUrl = true))
        }
        assertEquals(url, displayTextForAudioMessage(url, emptyList(), suppressStandaloneUrl = true))
        assertEquals(
            url,
            displayTextForAudioMessage(
                url,
                attachments + AudioAttachment("https://files.example/other.mp3"),
                suppressStandaloneUrl = true,
            ),
        )
        assertEquals(
            url,
            displayTextForAudioMessage(
                url,
                listOf(AudioAttachment("https://files.example/other.mp3")),
                suppressStandaloneUrl = true,
            ),
        )
    }

    @Test fun hidesWrappedStandaloneAudioUrlOnlyWhenRequested() {
        val url = "https://files.example/voice.ogg"
        val wrapped = "<$url>"
        val attachments = parseAudioAttachments(wrapped)

        assertEquals(listOf(url), attachments.map { it.url })
        assertEquals(wrapped, displayTextForAudioMessage(wrapped, attachments))
        assertEquals("", displayTextForAudioMessage(wrapped, attachments, suppressStandaloneUrl = true))
        val caption = "listen $wrapped"
        assertEquals(caption, displayTextForAudioMessage(caption, attachments, suppressStandaloneUrl = true))
        val mismatch = "<https://files.example/other.ogg>"
        assertEquals(mismatch, displayTextForAudioMessage(mismatch, attachments, suppressStandaloneUrl = true))
    }

    @Test fun findsOnlyExtensionlessHttpsHeadCandidates() {
        val candidates =
            extensionlessAudioCandidates(
                "https://files.example/abc http://files.example/def https://files.example/a.mp3 https://files.example/path/",
            )

        assertEquals(listOf("https://files.example/abc"), candidates)
        assertFalse(candidates.any { it.startsWith("http://") })
    }

    @Test fun downloadProgressUsesActualCachedBytes() {
        assertEquals(0.25f, audioDownloadFraction(cachedBytes = 250, totalBytes = 1_000))
        assertEquals(0f, audioDownloadFraction(cachedBytes = -1, totalBytes = 1_000))
        assertEquals(1f, audioDownloadFraction(cachedBytes = 2_000, totalBytes = 1_000))
        assertNull(audioDownloadFraction(cachedBytes = 250, totalBytes = -1))
    }
}
