package io.github.trevarj.motd.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.avatar.LocalAvatarStore
import io.github.trevarj.motd.data.db.MemberDao
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BufferRepositoryMembersTest {
    @Test
    fun readAnchorAdvanceKeepsMemberQueriesSubscribedWhileRosterChangesAndRedirectsStillEmit() =
        runTest {
            val db = inMemoryDb(directCommit = true)
            try {
                val bufferDao = db.bufferDao()
                val memberDao = db.memberDao()
                val networkId = db.networkDao().insert(network())
                val source = bufferDao.insert(buffer(networkId, "#source"))
                val target = bufferDao.insert(buffer(networkId, "#target"))
                val original = listOf(MemberEntity(source, "alice"))
                val updated = listOf(MemberEntity(source, "amy"))
                val redirected = listOf(MemberEntity(target, "bob"))
                memberDao.insertAll(original + redirected)

                val memberSubscriptions = mutableListOf<Long>()
                val nickSubscriptions = mutableListOf<Long>()
                val trackedMemberDao =
                    object : MemberDao by memberDao {
                        override fun observe(bufferId: Long) = memberDao.observe(bufferId).onStart { memberSubscriptions += bufferId }

                        override fun observeNicks(bufferId: Long) = memberDao.observeNicks(bufferId).onStart { nickSubscriptions += bufferId }
                    }
                val context = ApplicationProvider.getApplicationContext<Context>()
                val repository =
                    BufferRepositoryImpl(
                        bufferDao,
                        trackedMemberDao,
                        db.messageDao(),
                        DataStoreSettingsRepository(context),
                        MessageVisibilityReader(db),
                        LocalAvatarStore(context),
                        bufferStore = BufferStore(db),
                    )
                val members = mutableListOf<List<MemberEntity>>()
                val nicks = mutableListOf<List<String>>()
                backgroundScope.launch { repository.observeMembers(source).take(3).toList(members) }
                backgroundScope.launch { repository.observeMemberNicks(source).take(3).toList(nicks) }
                runCurrent()
                assertEquals(listOf(original), members)
                assertEquals(listOf(listOf("alice")), nicks)

                bufferDao.advanceLocalReadAnchor(source, serverTime = 10, eventId = 1)
                runCurrent()
                assertEquals(listOf(source), memberSubscriptions)
                assertEquals(listOf(source), nickSubscriptions)

                memberDao.replaceAll(source, updated)
                runCurrent()
                db.roomAliasDao().markRedirect(source, target)
                runCurrent()
                assertEquals(listOf(source, target), memberSubscriptions)
                assertEquals(listOf(source, target), nickSubscriptions)
                assertEquals(listOf(original, updated, redirected), members)
                assertEquals(listOf(listOf("alice"), listOf("amy"), listOf("bob")), nicks)
            } finally {
                db.close()
            }
        }
}
