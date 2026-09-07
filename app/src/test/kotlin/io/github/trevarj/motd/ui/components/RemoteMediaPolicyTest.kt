package io.github.trevarj.motd.ui.components

import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteMediaPolicyTest {
    @Test
    fun automatic_policy_matrix_fails_closed_when_unavailable() {
        val both = ContentPreviewConfig()
        val neither = ContentPreviewConfig(autoLoadOnUnmetered = false, autoLoadOnMetered = false)
        val unmeteredOnly = ContentPreviewConfig(autoLoadOnMetered = false)
        val meteredOnly = ContentPreviewConfig(autoLoadOnUnmetered = false)

        assertTrue(automaticRemoteMediaAllowed(RemoteMediaNetwork.UNMETERED, both))
        assertTrue(automaticRemoteMediaAllowed(RemoteMediaNetwork.METERED, both))
        assertFalse(automaticRemoteMediaAllowed(RemoteMediaNetwork.UNAVAILABLE, both))
        assertFalse(automaticRemoteMediaAllowed(RemoteMediaNetwork.UNMETERED, neither))
        assertFalse(automaticRemoteMediaAllowed(RemoteMediaNetwork.METERED, neither))
        assertTrue(automaticRemoteMediaAllowed(RemoteMediaNetwork.UNMETERED, unmeteredOnly))
        assertFalse(automaticRemoteMediaAllowed(RemoteMediaNetwork.METERED, unmeteredOnly))
        assertFalse(automaticRemoteMediaAllowed(RemoteMediaNetwork.UNMETERED, meteredOnly))
        assertTrue(automaticRemoteMediaAllowed(RemoteMediaNetwork.METERED, meteredOnly))
    }

    @Test
    fun classification_requires_validated_internet_and_then_uses_metering() {
        assertEquals(
            RemoteMediaNetwork.UNAVAILABLE,
            classifyRemoteMediaNetwork(validatedInternet = false, unmetered = true),
        )
        assertEquals(
            RemoteMediaNetwork.METERED,
            classifyRemoteMediaNetwork(validatedInternet = true, unmetered = false),
        )
        assertEquals(
            RemoteMediaNetwork.UNMETERED,
            classifyRemoteMediaNetwork(validatedInternet = true, unmetered = true),
        )
    }
}
