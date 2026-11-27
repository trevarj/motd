package io.github.trevarj.motd.attachment

import android.content.ContentResolver
import android.content.ContextWrapper
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class VideoUploadCompressionInstrumentedTest {
    @Test
    fun unknownLengthPipeFailureRemovesStagedInput() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = Files.createTempDirectory(target.cacheDir.toPath(), "video-pipe-").toFile()
        try {
            val (readEnd, writeEnd) = ParcelFileDescriptor.createReliablePipe()
            readEnd.use { input ->
                val providerError = "Video provider stopped before finishing"
                writeEnd.use { output ->
                    val data = byteArrayOf(1, 2, 3)
                    assertEquals(data.size, Os.write(output.fileDescriptor, data, 0, data.size))
                    output.closeWithError(providerError)
                }
                val provider =
                    object : FileProvider() {
                        override fun openAssetFile(
                            uri: Uri,
                            mode: String,
                        ): AssetFileDescriptor = AssetFileDescriptor(input, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
                    }
                val resolver = ContentResolver.wrap(provider)
                val context =
                    object : ContextWrapper(target) {
                        override fun getCacheDir(): File = cache

                        override fun getContentResolver(): ContentResolver = resolver
                    }
                val source = AttachmentSource.Document(Uri.parse("content://pipe.test/video"), "pipe.mp4", "video/mp4", size = null)
                val failure =
                    assertThrows(IOException::class.java) {
                        runBlocking { compressUploadVideo(context, source, 1_048_576).close() }
                    }
                val causeMessage = failure.cause?.message.orEmpty()
                assertTrue(causeMessage.contains(providerError))
                assertEquals(emptyList<File>(), File(cache, "upload-video-compression").listFiles()?.toList())
            }
        } finally {
            cache.deleteRecursively()
        }
    }
}
