package io.github.trevarj.motd.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class VoiceCryptoTest {
    @Test fun encryptedVoiceRoundTripsThroughKeyFragment() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val crypto =
                VoiceCrypto(
                    AudioCacheStore(
                        context,
                        AudioMediaCache(context),
                        AudioWaveformRepository(context),
                    ),
                )
            val plain =
                File.createTempFile("voice-plain-", ".opus", context.cacheDir).apply {
                    writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
                }

            val encrypted = crypto.encrypt(plain)
            val output = File.createTempFile("voice-decrypted-", ".opus", context.cacheDir)
            val decrypted = crypto.decrypt(encrypted.file, encrypted.keyFragment, output)

            assertTrue(encrypted.keyFragment.startsWith("motd-key="))
            assertArrayEquals(plain.readBytes(), decrypted.readBytes())
        }
}
