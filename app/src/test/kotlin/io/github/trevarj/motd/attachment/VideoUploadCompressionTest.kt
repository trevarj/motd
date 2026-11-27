package io.github.trevarj.motd.attachment

import android.content.Context
import android.content.ContextWrapper
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoUploadCompressionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun reservesContainerSpaceAndBudgetsAudioOnlyWhenPresent() {
        assertEquals(UploadVideoBitrates(1_302_102, 96_000), uploadVideoBitrates(1_048_576, 5_000_000, hasAudio = true))
        assertEquals(UploadVideoBitrates(163_840, 0), uploadVideoBitrates(96 * 1024, 4_000_000, hasAudio = false))
        assertNull(uploadVideoBitrates(96 * 1024, 4_000_000, hasAudio = true))
    }

    @Test
    fun rejectsUnusableBudgetsWithoutIntegerOverflow() {
        assertNull(uploadVideoBitrates(0, 5_000_000, hasAudio = false))
        assertNull(uploadVideoBitrates(1_048_576, 0, hasAudio = false))
        assertNull(uploadVideoBitrates(8 * 1024, 10, hasAudio = false))
        assertNull(uploadVideoBitrates(1_048_576, Long.MAX_VALUE, hasAudio = true))
        assertEquals(UploadVideoBitrates(Int.MAX_VALUE, 96_000), uploadVideoBitrates(Long.MAX_VALUE, 1, hasAudio = true))
    }

    @Test
    fun measuredOvershootReducesVideoWhileKeepingAudio() {
        val requested = UploadVideoBitrates(1_300_000, 96_000)
        val retry = requested.calibrateForOversize(actualBytes = 1_500_000, limitBytes = 1_000_000)
        assertEquals(UploadVideoBitrates(741_600, 96_000), retry)
        assertNull(requested.calibrateForOversize(actualBytes = 1_000_000, limitBytes = 1_000_000))
        assertNull(requested.calibrateForOversize(actualBytes = 9_000_000, limitBytes = 1_000_000))
    }

    @Test
    fun onlyAcceptsLocalUrisWithoutFragments() {
        assertTrue("content://picker/video/23?token=local".toUri().isVideoCompressionInputUri())
        assertTrue("android.resource://test.package/17".toUri().isVideoCompressionInputUri())
        assertTrue("file:///storage/emulated/0/clip%23one.mp4".toUri().isVideoCompressionInputUri())
        assertFalse("https://host/clip.mp4".toUri().isVideoCompressionInputUri())
        assertFalse("content:picker".toUri().isVideoCompressionInputUri())
        assertFalse("content:///video/23".toUri().isVideoCompressionInputUri())
        assertFalse("content://picker/video/23#clip".toUri().isVideoCompressionInputUri())
        assertFalse("file://host/clip.mp4".toUri().isVideoCompressionInputUri())
        assertFalse("file:///storage/clip.mp4?query=1".toUri().isVideoCompressionInputUri())
    }

    @Test
    fun malformedLocalVideoDoesNotCreateAnOutputOrModifyInput() {
        val cache = temporary.newFolder()
        val context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                override fun getCacheDir(): File = cache
            }
        val original = temporary.newFile("not-a-video.mp4").apply { writeText("not video data") }
        val source = AttachmentSource.LocalFile(original, original.name, "video/mp4")
        assertThrows(IOException::class.java) {
            runBlocking { compressUploadVideo(context, source, 1_048_576).close() }
        }
        assertEquals("not video data", original.readText())
        assertEquals(emptyList<File>(), cache.listFiles()?.toList())
    }
}
