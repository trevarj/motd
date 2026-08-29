package io.github.trevarj.motd.sidecar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SidecarPrefsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clear() {
        context.filesDir.resolve("datastore/sidecar_labs.preferences_pb").delete()
    }

    @Test
    fun providerUiIntentCannotEscapePinnedPackage() {
        val provider = ComponentName("provider.example", "provider.example.Service")
        Intent().setClassName("provider.example", "provider.example.Pair").requireSidecarProvider(provider)
        assertThrows(IllegalArgumentException::class.java) {
            Intent().setClassName("other.example", "other.example.Pair").requireSidecarProvider(provider)
        }
    }

    @Test
    fun labDefaultsOffAndTrustIsRevocable() =
        runTest {
            val prefs = SidecarPrefsImpl(context)
            val trust = SidecarTrustStoreImpl(context)
            val component = ComponentName("provider.example", "provider.example.Service")
            val digest = "a".repeat(64)

            assertFalse(prefs.enabled.first())
            assertNull(trust.pinnedSigner(component))

            prefs.setEnabled(true)
            trust.trust(component, digest)
            assertEquals(true, prefs.enabled.first())
            assertEquals(digest, trust.pinnedSigner(component))

            trust.revoke(component)
            assertNull(trust.pinnedSigner(component))
        }
}
