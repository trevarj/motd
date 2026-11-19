package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.service.NotificationPreview
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationPreviewSettingsTest {
    @Test
    fun previewChoicePersistsAndAnUnknownValueDoesNotExposeContent() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = DataStoreSettingsRepository(context)
            assertEquals(NotificationPreview.FULL, repository.notificationPreview.first())
            repository.setNotificationPreview(NotificationPreview.IDENTITY)
            assertEquals(NotificationPreview.IDENTITY, DataStoreSettingsRepository(context).notificationPreview.first())

            context.settingsDataStore.edit { it[PrefKeys.NOTIFICATION_PREVIEW] = "UNRECOGNIZED" }
            assertEquals(NotificationPreview.GENERIC, repository.notificationPreview.first())
        }
}
