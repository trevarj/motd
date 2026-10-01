package io.github.trevarj.motd.ai.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class KokoroOutputTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun outputOwnershipAcceptsTempPlaceholdersWithoutOverwritingExistingData() {
        val absent = File(temporary.root, "absent.wav")
        validateKokoroOutputFile(absent)
        assertFalse(absent.exists())
        val placeholder = temporary.newFile("placeholder.wav")
        validateKokoroOutputFile(placeholder)
        assertEquals(0L, placeholder.length())
        val existing = temporary.newFile("existing.wav")
        val original = byteArrayOf(1, 2, 3, 4)
        existing.writeBytes(original)
        assertThrows(IllegalArgumentException::class.java) { validateKokoroOutputFile(existing) }
        assertArrayEquals(original, existing.readBytes())
        assertThrows(IllegalArgumentException::class.java) { validateKokoroOutputFile(temporary.newFolder("directory.wav")) }
        val link = File(temporary.root, "link.wav")
        Files.createSymbolicLink(link.toPath(), placeholder.toPath())
        assertThrows(IllegalArgumentException::class.java) { validateKokoroOutputFile(link) }
        assertEquals(0L, placeholder.length())
    }
}
