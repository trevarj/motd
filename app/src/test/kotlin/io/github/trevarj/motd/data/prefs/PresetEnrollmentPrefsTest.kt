package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PresetEnrollmentPrefsTest {
    @Test
    fun claim_is_atomic_and_survives_a_new_repository_instance() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val networkId = System.nanoTime()
        val first: PresetEnrollmentPrefs = PresetEnrollmentPrefsImpl(context)

        assertFalse(first.claimLiberaMotdJoin(networkId))
        first.markLiberaEligible(networkId)
        assertTrue(first.claimLiberaMotdJoin(networkId))

        val afterProcessDeath: PresetEnrollmentPrefs = PresetEnrollmentPrefsImpl(context)
        assertFalse(afterProcessDeath.claimLiberaMotdJoin(networkId))
        afterProcessDeath.markLiberaEligible(networkId)
        assertFalse(afterProcessDeath.claimLiberaMotdJoin(networkId))
    }

    @Test
    fun revoking_an_unclaimed_row_prevents_the_join() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val networkId = System.nanoTime()
        val prefs: PresetEnrollmentPrefs = PresetEnrollmentPrefsImpl(context)

        prefs.markLiberaEligible(networkId)
        prefs.revokeLiberaEligibility(networkId)

        assertFalse(prefs.claimLiberaMotdJoin(networkId))
    }
}
