package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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

    @Before
    fun resetPreferences() =
        runTest {
            ApplicationProvider.getApplicationContext<Context>().settingsDataStore.edit { it.clear() }
        }

    @Test
    fun freshDefaultsAndFirstAddedFoolUseDimmed() =
        runTest {
            assertEquals(FoolsMode.DIMMED, Settings().foolsMode)
            assertEquals(FoolsMode.DIMMED, repo.settings.first().foolsMode)
            repo.setFool("alice", true)
            val settings = repo.settings.first()
            assertEquals(FoolsMode.DIMMED, settings.foolsMode)
            assertTrue(settings.foolPreferences.isEmpty())
        }

    @Test
    fun legacyPopulatedFallbackAndValidSavedModesSurvive() =
        runTest {
            val store = ApplicationProvider.getApplicationContext<Context>().settingsDataStore
            for (saved in listOf(null, "broken", "COLLAPSE", "HIDE", "DIMMED")) {
                store.edit {
                    it[PrefKeys.FOOL_NICKS] = encodeNickSet(setOf("alice"))
                    if (saved == null) it.remove(PrefKeys.FOOLS_MODE) else it[PrefKeys.FOOLS_MODE] = saved
                }
                val expected = saved?.let { runCatching { FoolsMode.valueOf(it) }.getOrNull() } ?: FoolsMode.COLLAPSE
                assertEquals(expected, repo.settings.first().foolsMode)
                repo.setFool("bob", true)
                assertEquals(expected, repo.settings.first().foolsMode)
            }
            store.edit {
                it.remove(PrefKeys.FOOL_NICKS)
                it[PrefKeys.FOOLS_MODE] = "broken"
            }
            assertEquals(FoolsMode.DIMMED, repo.settings.first().foolsMode)
        }

    @Test
    fun preferencesAreSparseIndependentAndMembershipOwned() =
        runTest {
            val selected = FoolPreferences(FoolsMode.COLLAPSE, notifyMentions = true)
            repo.setFoolPreferences("missing", selected)
            assertTrue(
                repo.settings
                    .first()
                    .fools
                    .isEmpty(),
            )
            assertTrue(
                repo.settings
                    .first()
                    .foolPreferences
                    .isEmpty(),
            )
            repo.setFool("Alice", true)
            repo.setFoolPreferences(" ALICE ", selected)
            repo.setFool("alice", true)
            assertEquals(selected, repo.settings.first().foolPreferences["alice"])
            val dmOnly = FoolPreferences(notifyDirectMessages = true)
            repo.setFoolPreferences("alice", dmOnly)
            assertEquals(dmOnly, repo.settings.first().foolPreferences["alice"])
            repo.setFoolPreferences("alice", FoolPreferences())
            assertTrue(
                repo.settings
                    .first()
                    .foolPreferences
                    .isEmpty(),
            )
            repo.setFoolPreferences("alice", selected)
            repo.setFool("alice", false)
            assertTrue(
                repo.settings
                    .first()
                    .foolPreferences
                    .isEmpty(),
            )
            repo.setFool("alice", true)
            assertTrue(
                repo.settings
                    .first()
                    .foolPreferences
                    .isEmpty(),
            )
            repo.setFoolPreferences("alice", selected)
            repo.setFriend("alice", true)
            assertTrue(
                repo.settings
                    .first()
                    .foolPreferences
                    .isEmpty(),
            )
            assertFalse("alice" in repo.settings.first().fools)
        }

    @Test
    fun inheritedModeRemainsLiveAndExplicitModeSurvivesGlobalChanges() =
        runTest {
            repo.setFool("alice", true)
            repo.setFool("bob", true)
            repo.setFoolPreferences("alice", FoolPreferences(notifyMentions = true))
            repo.setFoolPreferences("bob", FoolPreferences(FoolsMode.COLLAPSE))
            for (mode in FoolsMode.entries) {
                repo.setFoolsMode(mode)
                val policy =
                    io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy(
                        io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
                            .from(repo.settings.first()),
                    )
                assertEquals(mode, policy.foolPreferences(null, "alice")?.visibility)
                assertEquals(FoolsMode.COLLAPSE, policy.foolPreferences(null, "bob")?.visibility)
            }
        }

    @Test
    fun rulesAwareEditingAndRekeyMergeConservativelyInEitherOrder() =
        runTest {
            val rules = IrcIdentityRules(IrcCaseMapping.Rfc1459)
            for (reverse in listOf(false, true)) {
                val keys = if (reverse) listOf("nick{", "nick[") else listOf("nick[", "nick{")
                for (key in keys) repo.setFool(key, true)
                repo.setFoolPreferences("nick[", FoolPreferences(FoolsMode.HIDE, notifyMentions = true))
                repo.setFoolPreferences("nick{", FoolPreferences(FoolsMode.DIMMED, notifyDirectMessages = true))
                repo.setFool("NICK[", true, rules)
                assertEquals(setOf("nick["), repo.settings.first().fools)
                assertEquals(FoolPreferences(FoolsMode.HIDE), repo.settings.first().foolPreferences["nick["])
                val selected = FoolPreferences(FoolsMode.COLLAPSE, notifyMentions = true)
                repo.setFoolPreferences("NICK{", selected, rules)
                assertEquals(mapOf("nick[" to selected), repo.settings.first().foolPreferences)
                repo.setFool("NICK{", true, rules)
                assertEquals(mapOf("nick{" to selected), repo.settings.first().foolPreferences)
                repo.setFriend("nick[", true, rules)
                assertTrue(
                    repo.settings
                        .first()
                        .foolPreferences
                        .isEmpty(),
                )
                assertTrue(
                    repo.settings
                        .first()
                        .fools
                        .isEmpty(),
                )
                repo.setFool("inherit[", true)
                val inherited = FoolPreferences(notifyMentions = true)
                repo.setFoolPreferences("inherit[", inherited)
                repo.setFool("inherit{", true, rules)
                assertEquals(mapOf("inherit{" to inherited), repo.settings.first().foolPreferences)
                repo.setFool("inherit[", false, rules)
                assertTrue(
                    repo.settings
                        .first()
                        .foolPreferences
                        .isEmpty(),
                )
            }
        }

    @Test
    fun corruptPreferencesFailClosedPerRecordAndUnknownVisibilityInherits() =
        runTest {
            val store = ApplicationProvider.getApplicationContext<Context>().settingsDataStore
            store.edit {
                it[PrefKeys.FOOL_NICKS] = encodeNickSet(setOf("alice", "bob", "carol", "dave"))
                it[PrefKeys.FOOL_PREFERENCES] =
                    """{"alice":{"visibility":"FUTURE","notifyMentions":true},"bob":{"notifyMentions":"true"},"carol":false,"dave":{"notifyDirectMessages":true,"notifyMentions":17},"orphan":{"notifyMentions":true}}"""
            }
            assertEquals(mapOf("alice" to FoolPreferences(notifyMentions = true)), repo.settings.first().foolPreferences)
            for (raw in listOf(
                """{" ALICE ":{"notifyMentions":true},"alice":{"notifyMentions":"true"}}""",
                """{"alice":{"notifyMentions":"true"}," ALICE ":{"notifyMentions":true}}""",
            )) {
                store.edit { it[PrefKeys.FOOL_PREFERENCES] = raw }
                assertTrue(
                    repo.settings
                        .first()
                        .foolPreferences
                        .isEmpty(),
                )
            }
            store.edit { it[PrefKeys.FOOL_PREFERENCES] = """["bad top level"]""" }
            assertTrue(
                repo.settings
                    .first()
                    .foolPreferences
                    .isEmpty(),
            )
            store.edit { it[PrefKeys.FOOL_PREFERENCES] = "{" }
            assertTrue(
                repo.settings
                    .first()
                    .foolPreferences
                    .isEmpty(),
            )
        }

    @Test
    fun addFool_thenObserved() =
        runTest {
            repo.setFool("Alice", true)
            assertTrue("alice" in repo.settings.first().fools)
        }

    /** Adding the same fool twice must be idempotent, not crash or duplicate. */
    @Test
    fun addSameFoolTwice_isIdempotent() =
        runTest {
            repo.setFool("Bob", true)
            repo.setFool("bob", true) // same nick, un-normalized second time
            val fools = repo.settings.first().fools
            assertEquals(setOf("bob"), fools)
        }

    /** Un-normalized input is stored normalized so membership checks match the UI's normalizeNick. */
    @Test
    fun addFool_normalizesNick() =
        runTest {
            repo.setFool("  CaRoL  ", true)
            assertTrue("carol" in repo.settings.first().fools)
        }

    /** Adding a fool drops it from friends (disjoint invariant), and vice versa. */
    @Test
    fun foolAndFriendAreDisjoint() =
        runTest {
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
    fun removeMissingFool_isNoop() =
        runTest {
            repo.setFool("ghost", false)
            assertFalse("ghost" in repo.settings.first().fools)
        }

    @Test
    fun rulesAwareMutationRemovesEquivalentStoredEntryAndKeepsSetsDisjoint() =
        runTest {
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
