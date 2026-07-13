package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContentPreviewPrefsTest {
    @Test
    fun defaults_and_independent_values_round_trip() = runTest {
        val prefs: ContentPreviewPrefs = ContentPreviewPrefsImpl(
            ApplicationProvider.getApplicationContext<Context>(),
        )

        assertEquals(ContentPreviewConfig(), prefs.config.first())

        prefs.setShowImages(false)
        assertEquals(ContentPreviewConfig(showImages = false), prefs.config.first())

        prefs.setShowLinkPreviews(false)
        assertEquals(
            ContentPreviewConfig(showImages = false, showLinkPreviews = false),
            prefs.config.first(),
        )

        prefs.setShowImages(true)
        assertEquals(
            ContentPreviewConfig(showImages = true, showLinkPreviews = false),
            prefs.config.first(),
        )
    }
}
