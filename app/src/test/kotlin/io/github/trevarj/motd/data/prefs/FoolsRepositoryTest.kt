package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fool-set round-trip over the real DataStore repository (mirrors CertTrustStoreTest infra).
 * Regression cover for the add-to-fools crash: adding the same nick twice, adding an
 * un-normalized nick, and the friends/fools disjoint invariant must all be crash-free and stable.
 */
@RunWith(RobolectricTestRunner::class)
class FoolsRepositoryTest {

    private val repo: SettingsRepository =
        DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun addFool_thenObserved() = runTest {
        repo.setFool("Alice", true)
        assertTrue("alice" in repo.settings.first().fools)
    }

    /** Adding the same fool twice must be idempotent, not crash or duplicate. */
    @Test
    fun addSameFoolTwice_isIdempotent() = runTest {
        repo.setFool("Bob", true)
        repo.setFool("bob", true) // same nick, un-normalized second time
        val fools = repo.settings.first().fools
        assertEquals(setOf("bob"), fools)
    }

    /** Un-normalized input is stored normalized so membership checks match the UI's normalizeNick. */
    @Test
    fun addFool_normalizesNick() = runTest {
        repo.setFool("  CaRoL  ", true)
        assertTrue("carol" in repo.settings.first().fools)
    }

    /** Adding a fool drops it from friends (disjoint invariant), and vice versa. */
    @Test
    fun foolAndFriendAreDisjoint() = runTest {
        repo.setFriend("dave", true)
        repo.setFool("dave", true)
        val s = repo.settings.first()
        assertTrue("dave" in s.fools)
        assertFalse("dave" in s.friends)

        repo.setFriend("dave", true)
        val s2 = repo.settings.first()
        assertTrue("dave" in s2.friends)
        assertFalse("dave" in s2.fools)
    }

    /** Removing a fool that was never added must not crash and leaves the set empty. */
    @Test
    fun removeMissingFool_isNoop() = runTest {
        repo.setFool("ghost", false)
        assertFalse("ghost" in repo.settings.first().fools)
    }

    @Test
    fun rulesAwareMutationRemovesEquivalentStoredEntryAndKeepsSetsDisjoint() = runTest {
        val rfc = IrcIdentityRules(IrcCaseMapping.Rfc1459)
        val strict = IrcIdentityRules(IrcCaseMapping.Rfc1459Strict)
        repo.setFriend("Social[Fixture", true)
        repo.setFriend("Social{Fixture", true)

        repo.setFool("social{fixture", true, rfc)

        val moved = repo.settings.first()
        assertFalse(moved.friends.any { rfc.normalize(it) == rfc.normalize("social[fixture") })
        assertTrue(rfc.matchesConfiguredNick("social[fixture", moved.fools))

        repo.setFool("SOCIAL[FIXTURE", false, rfc)
        assertFalse(rfc.matchesConfiguredNick("social{fixture", repo.settings.first().fools))

        repo.setFriend("Strict~Fixture", true, strict)
        repo.setFool("strict^fixture", true, strict)
        val distinct = repo.settings.first()
        assertTrue(strict.matchesConfiguredNick("strict~fixture", distinct.friends))
        assertTrue(strict.matchesConfiguredNick("strict^fixture", distinct.fools))
    }
}
