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
class OnboardingPrefsTest {
    @Test
    fun completionDefaultsFalseAndPersists() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs: OnboardingPrefs = OnboardingPrefsImpl(context)

            assertFalse(prefs.completed.first())

            prefs.markCompleted()

            assertTrue(OnboardingPrefsImpl(context).completed.first())
        }

    @Test
    fun communityInvitationDefaultsFalseAndPersistsIndependentlyOfCompletion() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs: OnboardingPrefs = OnboardingPrefsImpl(context)

            assertFalse(prefs.communityInvitationHandled.first())
            prefs.markCompleted()
            assertFalse(prefs.communityInvitationHandled.first())

            prefs.markCommunityInvitationHandled()

            val restored: OnboardingPrefs = OnboardingPrefsImpl(context)
            assertTrue(restored.communityInvitationHandled.first())
            assertTrue(restored.completed.first())
        }
}
