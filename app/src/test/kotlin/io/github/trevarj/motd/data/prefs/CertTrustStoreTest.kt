package io.github.trevarj.motd.data.prefs

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** CertTrustStore round-trip over the real DataStore: pin → pinnedFor/isPinned + change detection. */
@RunWith(RobolectricTestRunner::class)
class CertTrustStoreTest {

    private val store: CertTrustStore =
        DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun unpinned_returnsNull() = runTest {
        assertNull(store.pinnedFor("host.example", 6697))
        assertFalse(store.isPinned("host.example", 6697, "abc"))
    }

    @Test
    fun pin_thenPinnedFor_andIsPinned() = runTest {
        store.pin("Host.Example", 6697, "A1B2C3")
        // Stored lowercase; host key is case-insensitive.
        assertEquals("a1b2c3", store.pinnedFor("host.example", 6697))
        assertTrue(store.isPinned("host.example", 6697, "A1B2C3"))
        assertTrue(store.isPinned("host.example", 6697, "a1b2c3"))
    }

    @Test
    fun pin_isScopedByPort() = runTest {
        store.pin("h.example", 6697, "aaaa")
        assertNull(store.pinnedFor("h.example", 7000))
    }

    @Test
    fun changeDetection_mismatchIsNotPinned() = runTest {
        store.pin("h2.example", 6697, "aaaa")
        assertFalse(store.isPinned("h2.example", 6697, "bbbb"))
    }

    @Test
    fun rePin_replacesFingerprint() = runTest {
        store.pin("h3.example", 6697, "aaaa")
        store.pin("h3.example", 6697, "cccc")
        assertEquals("cccc", store.pinnedFor("h3.example", 6697))
    }

    @Test
    fun unpin_removesPin() = runTest {
        store.pin("h4.example", 6697, "aaaa")
        store.unpin("h4.example", 6697)
        assertNull(store.pinnedFor("h4.example", 6697))
    }
}
