package io.github.trevarj.motd.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioPlaybackLeaseHandoffTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `handoff releases the player before closing the old lease`() {
        val events = mutableListOf<String>()
        val currentFile = temporaryFolder.newFile("current.media")
        val replacementFile = temporaryFolder.newFile("replacement.media")
        val current =
            LocalAudioLease(
                currentFile,
                owned = true,
                managed =
                    AudioFileLease(currentFile) { file ->
                        events += "close"
                        check(file.delete())
                    },
            )
        val replacement = LocalAudioLease(replacementFile, owned = true)

        val active =
            handoffAudioLease(current, replacement) {
                events += "stop"
                assertTrue(currentFile.exists())
                events += "clear"
            }

        assertSame(replacement, active)
        assertEquals(listOf("stop", "clear", "close"), events)
        assertTrue(!currentFile.exists())
        assertTrue(replacementFile.exists())
        active.close()
    }

    @Test
    fun `failed player release retains both leases`() {
        val currentFile = temporaryFolder.newFile("current-failure.media")
        val replacementFile = temporaryFolder.newFile("replacement-failure.media")
        val current = LocalAudioLease(currentFile, owned = true)
        val replacement = LocalAudioLease(replacementFile, owned = true)
        var failed = false

        try {
            handoffAudioLease(current, replacement) { error("stop failed") }
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        assertTrue(currentFile.exists())
        assertTrue(replacementFile.exists())
        current.close()
        replacement.close()
    }
}
