package io.github.trevarj.motd.attachment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentPrefsTest {
    @Test
    fun atomicUpdatesPersistUsernameAndPasswordTogether() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = AttachmentPrefsImpl(context)
            prefs.setConfig(PasteBackendConfig())

            prefs.updateConfig { it.copy(username = "camera-user") }
            prefs.updateConfig { it.copy(password = "camera-secret") }

            val reopened = AttachmentPrefsImpl(context).config.first()
            assertEquals("camera-user", reopened.username)
            assertEquals("camera-secret", reopened.password)
        }

    @Test
    fun clearUploadHistoryPersistsWithoutChangingConfigurationOrFutureUploads() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = AttachmentPrefsImpl(context)
            val config = PasteBackendConfig(username = "keep-user", password = "keep-secret")
            prefs.setConfig(config)
            prefs.addUpload(UploadRecord("https://files.example/one", AttachmentBackend.CRAFTERBIN, "one", deletionToken = "token"))
            prefs.addUpload(UploadRecord("https://files.example/two", AttachmentBackend.X0_AT, "two"))
            assertEquals(2, prefs.recentUploads.first().size)

            prefs.clearUploadHistory()
            prefs.clearUploadHistory()

            val reopened = AttachmentPrefsImpl(context)
            assertEquals(emptyList<UploadRecord>(), reopened.recentUploads.first())
            assertEquals(config, reopened.config.first())
            val completed = UploadRecord("https://files.example/three", AttachmentBackend.CRAFTERBIN, "three")
            reopened.addUpload(completed)
            assertEquals(listOf(completed), prefs.recentUploads.first())
        }
}
