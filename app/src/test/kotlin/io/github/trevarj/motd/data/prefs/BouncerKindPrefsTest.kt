package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BouncerKindPrefsTest {
    @Test
    fun `ZNC classification survives a new repository instance and can be cleared`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val networkId = System.nanoTime()
        val first: BouncerKindPrefs = BouncerKindPrefsImpl(context)

        first.markZnc(networkId)
        assertTrue(networkId in BouncerKindPrefsImpl(context).zncNetworkIds.first())

        first.clear(networkId)
        assertFalse(networkId in BouncerKindPrefsImpl(context).zncNetworkIds.first())
    }
}
