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
import org.junit.Assert.assertNull
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
            prefs.clearUploadHistory()
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

            for (field in listOf("", ",\"stripMetadata\":null", ",\"stripMetadata\":\"false\"", ",\"stripMetadata\":0", ",\"stripMetadata\":{}")) {
                store(prefs).edit { it[stringPreferencesKey("config_v1")] = """{"username":"legacy-user"$field}""" }
                val reopened = AttachmentPrefsImpl(context).config.first()
                assertTrue(reopened.stripMetadata)
                assertEquals("legacy-user", reopened.username)
            }
        }

    @Test
    fun localPreviewsArePersistedAndRemovedWithTheirHistoryRecords() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = AttachmentPrefsImpl(context)
            val history = stringPreferencesKey("history_v1")
            val script = java.io.File.createTempFile("preview-", ".sh", context.cacheDir)
            try {
                // The last emoji straddles the byte scan; a partial code point does not discard the snippet.
                val prefix = "\u202eecho\u001b\tcafé 😀\nnext"
                script.writeText(prefix + " ".repeat(958 - prefix.toByteArray().size) + "😀unscanned")
                val preview = checkNotNull(localUploadPreview(AttachmentSource.LocalFile(script, "short.sh", "application/x-shellscript")))
                assertEquals(LocalUploadPreview(textSnippet = "echo café 😀 next"), preview)
                val record = UploadRecord("https://files.example/snippet", AttachmentBackend.CRAFTERBIN, "short.sh", preview = preview)
                prefs.clearUploadHistory()
                prefs.addUpload(record)
                assertEquals(listOf(record), AttachmentPrefsImpl(context).recentUploads.first())
                assertTrue(store(prefs).data.first()[history]!!.contains("echo café 😀 next"))
                assertFalse(store(prefs).data.first()[history]!!.contains(script.path))

                repeat(MAX_UPLOAD_HISTORY) { index ->
                    prefs.addUpload(UploadRecord("https://files.example/$index", AttachmentBackend.CRAFTERBIN, "$index"))
                }
                assertEquals(MAX_UPLOAD_HISTORY, prefs.recentUploads.first().size)
                assertFalse(store(prefs).data.first()[history]!!.contains("echo café 😀 next"))

                prefs.addUpload(record)
                prefs.removeUpload(record.url)
                assertFalse(prefs.recentUploads.first().any { it.url == record.url })
                assertFalse(store(prefs).data.first()[history]!!.contains("echo café 😀 next"))

                prefs.addUpload(record)
                prefs.clearUploadHistory()
                assertNull(store(prefs).data.first()[history])
            } finally {
                script.delete()
            }
        }

    @Test
    fun legacyAndMalformedPreviewsKeepTheirUploadRecords() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = AttachmentPrefsImpl(context)
            try {
                store(prefs).edit {
                    it[stringPreferencesKey("history_v1")] =
                        """[{"url":"https://files.example/legacy"},{"url":"https://files.example/malformed","preview":{"thumbnailBase64":[]}}]"""
                }
                val records = prefs.recentUploads.first()
                assertEquals(listOf("https://files.example/legacy", "https://files.example/malformed"), records.map { it.url })
                assertTrue(records.all { it.preview == null })
            } finally {
                prefs.clearUploadHistory()
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun store(prefs: AttachmentPrefsImpl): DataStore<Preferences> =
        AttachmentPrefsImpl::class.java
            .getDeclaredField("store")
            .apply { isAccessible = true }
            .get(prefs) as DataStore<Preferences>

    @Test
    fun textPreviewCapsSnippetWithoutBreakingSurrogatePairs() {
        val text = "x".repeat(239) + "😀trailing"
        val source = AttachmentSource.Text(text)
        val expectedSize = text.toByteArray().size
        assertEquals(expectedSize.toLong(), source.sizeOrNull())
        assertEquals("x".repeat(239), localUploadPreview(source)?.textSnippet)
    }
}
