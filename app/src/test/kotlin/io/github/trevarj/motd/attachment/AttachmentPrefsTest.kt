package io.github.trevarj.motd.attachment

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

            prefs.updateConfig { it.copy(stripMetadata = false) }
            prefs.updateConfig { it.copy(username = "camera-user") }
            prefs.updateConfig { it.copy(password = "camera-secret") }

            val reopened = AttachmentPrefsImpl(context).config.first()
            assertEquals("camera-user", reopened.username)
            assertEquals("camera-secret", reopened.password)
            assertFalse(reopened.stripMetadata)

            prefs.updateConfig { it.copy(stripMetadata = true) }
            val enabled = AttachmentPrefsImpl(context).config.first()
            assertTrue(enabled.stripMetadata)
            assertEquals("camera-user", enabled.username)
            assertEquals("camera-secret", enabled.password)
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

    @Test
    fun oldAndInvalidStoredMetadataPoliciesDefaultOnWithoutLosingOtherFields() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = AttachmentPrefsImpl(context)

            @Suppress("UNCHECKED_CAST")
            val store =
                AttachmentPrefsImpl::class.java
                    .getDeclaredField("store")
                    .apply { isAccessible = true }
                    .get(prefs) as DataStore<Preferences>
            for (field in listOf("", ",\"stripMetadata\":null", ",\"stripMetadata\":\"false\"", ",\"stripMetadata\":0", ",\"stripMetadata\":{}")) {
                store.edit { it[stringPreferencesKey("config_v1")] = """{"username":"legacy-user"$field}""" }
                val reopened = AttachmentPrefsImpl(context).config.first()
                assertTrue(reopened.stripMetadata)
                assertEquals("legacy-user", reopened.username)
            }
        }
}
