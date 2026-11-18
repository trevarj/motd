package io.github.trevarj.motd.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.avatar.LocalAvatarStore
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NickSuggestionRepositoryTest {
    @Test
    fun prefixPatternEscapesSqlWildcardsLiterally() {
        assertEquals("a\\%\\_\\\\%", nickSuggestionLikePattern("a%_\\"))
    }

    @Test
    fun memberQueryScopesUnionsDeduplicatesSortsLimitsAndExcludesSelf() =
        runTest {
            val db = inMemoryDb(directCommit = true)
            val firstNetwork = db.networkDao().insert(network("one"))
            val otherNetwork = db.networkDao().insert(network("two"))
            val channel = db.bufferDao().insert(buffer(firstNetwork, "#one"))
            val otherChannel = db.bufferDao().insert(buffer(otherNetwork, "#two"))
            db.bufferDao().insert(query(firstNetwork, "alfred", "Alfred"))
            db.bufferDao().insert(query(firstNetwork, "alice", "ALICE"))
            db.memberDao().insertAll(
                listOf(
                    MemberEntity(channel, "Alice"),
                    MemberEntity(channel, "albert"),
                    MemberEntity(channel, "me"),
                    MemberEntity(channel, "a%literal"),
                    MemberEntity(channel, "a_literal"),
                    MemberEntity(channel, "a\\literal"),
                    MemberEntity(channel, "[anna"),
                    MemberEntity(channel, "{ANNA"),
                    MemberEntity(channel, "{me"),
                    MemberEntity(otherChannel, "alpine"),
                ),
            )

            assertEquals(
                listOf("albert", "Alfred", "ALICE"),
                db.memberDao().observeNickSuggestions(firstNetwork, nickSuggestionLikePattern("al"), "me", "ascii", 10).first(),
            )
            assertEquals(
                listOf("albert", "Alfred"),
                db.memberDao().observeNickSuggestions(firstNetwork, nickSuggestionLikePattern("al"), "me", "ascii", 2).first(),
            )
            assertEquals(
                listOf("a%literal"),
                db.memberDao().observeNickSuggestions(firstNetwork, nickSuggestionLikePattern("a%"), "me", "ascii", 10).first(),
            )
            assertEquals(
                listOf("a_literal"),
                db.memberDao().observeNickSuggestions(firstNetwork, nickSuggestionLikePattern("a_"), "me", "ascii", 10).first(),
            )
            assertEquals(
                listOf("a\\literal"),
                db.memberDao().observeNickSuggestions(firstNetwork, nickSuggestionLikePattern("a\\"), "me", "ascii", 10).first(),
            )
            assertEquals(
                listOf("[anna"),
                db.memberDao().observeNickSuggestions(firstNetwork, nickSuggestionLikePattern("{a"), "{me", "rfc1459", 10).first(),
            )
            assertEquals(
                emptyList<String>(),
                db.memberDao().observeNickSuggestions(firstNetwork, nickSuggestionLikePattern("{m"), "{me", "rfc1459", 10).first(),
            )
        }

    @Test
    fun repositoryUsesPersistedIrcIdentityRulesForPrefixSelfAndDedupe() =
        runTest {
            val db = inMemoryDb(directCommit = true)
            val networkId = db.networkDao().insert(network())
            val channel = db.bufferDao().insert(buffer(networkId, "#one"))
            db.networkIdentityDao().upsert(NetworkIdentityEntity(networkId, caseMapping = "rfc1459", selfNick = "[me"))
            db.memberDao().insertAll(listOf(MemberEntity(channel, "[anna"), MemberEntity(channel, "{ANNA"), MemberEntity(channel, "{me")))
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository =
                BufferRepositoryImpl(
                    db.bufferDao(),
                    db.memberDao(),
                    db.messageDao(),
                    DataStoreSettingsRepository(context),
                    MessageVisibilityReader(db),
                    LocalAvatarStore(context),
                    db.networkIdentityDao(),
                    bufferStore =
                        io.github.trevarj.motd.data.sync
                            .BufferStore(db),
                )

            assertEquals(listOf("[anna"), repository.observeNickSuggestions(networkId, "[a", "configured-me").first())
            assertEquals(emptyList<String>(), repository.observeNickSuggestions(networkId, "[m", "configured-me").first())
        }

    @Test
    fun memberQueryReactsToCachedRosterUpdates() =
        runTest {
            val db = inMemoryDb(directCommit = true)
            val networkId = db.networkDao().insert(network())
            val channel = db.bufferDao().insert(buffer(networkId, "#one"))
            val update =
                async {
                    db
                        .memberDao()
                        .observeNickSuggestions(networkId, nickSuggestionLikePattern("am"), "me", "ascii", 10)
                        .first { it == listOf("amy") }
                }
            yield()

            db.memberDao().upsert(MemberEntity(channel, "amy"))

            assertEquals(listOf("amy"), update.await())
        }

    private fun query(
        networkId: Long,
        name: String,
        displayName: String,
    ): BufferEntity = buffer(networkId, name, BufferType.QUERY).copy(displayName = displayName)
}
