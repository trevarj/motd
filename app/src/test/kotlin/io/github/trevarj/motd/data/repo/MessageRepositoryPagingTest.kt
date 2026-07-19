package io.github.trevarj.motd.data.repo

import androidx.paging.PagingSource
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageRepositoryPagingTest {
    private lateinit var db: MotdDatabase
    private lateinit var reader: MessageVisibilityReader
    private var bufferId = 0L

    @Before
    fun setUp() = runTest {
        db = inMemoryDb()
        reader = MessageVisibilityReader(db)
        val networkId = db.networkDao().insert(network())
        bufferId = db.bufferDao().insert(buffer(networkId, "#paging"))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun pagingConfigIsPlaceholderAwareAndBounded() {
        assertEquals(50, MESSAGE_PAGING_CONFIG.pageSize)
        assertEquals(25, MESSAGE_PAGING_CONFIG.prefetchDistance)
        assertTrue(MESSAGE_PAGING_CONFIG.enablePlaceholders)
        assertEquals(500, MESSAGE_PAGING_CONFIG.maxSize)
        assertEquals(250, MESSAGE_PAGING_CONFIG.jumpThreshold)
    }

    @Test
    fun fiftyThousandRowsKeepDeepRefreshCountAndSavedAnchorExact() = runTest {
        val totalRows = 50_000
        val targetOrdinal = 12_347
        for (start in 1..totalRows step 500) {
            db.messageDao().insertAll(
                (start until minOf(start + 500, totalRows + 1)).map { ordinal ->
                    message(
                        bufferId = bufferId,
                        text = "row-$ordinal",
                        sender = if (ordinal % 17 == 0) "fool" else "person",
                        serverTime = ordinal.toLong(),
                        dedupKey = "row-$ordinal",
                        msgid = "row-$ordinal",
                        kind = if (ordinal % 19 == 0) MessageKind.JOIN else MessageKind.PRIVMSG,
                    )
                },
            )
        }
        val spec = MessageVisibilitySpec(
            showJoinPartQuit = false,
            fools = setOf("fool"),
            foolsMode = FoolsMode.HIDE,
        )
        val target = checkNotNull(db.messageDao().byMsgid(bufferId, "row-$targetOrdinal"))
        val targetIndex = reader.countTimelineNewer(bufferId, target.serverTime, target.id, spec)
        val source = db.messageDao().pagingSource(messagePagingQuery(bufferId, spec))
        val result = source.load(
            PagingSource.LoadParams.Refresh(
                key = targetIndex,
                loadSize = MESSAGE_PAGING_CONFIG.pageSize,
                placeholdersEnabled = true,
            ),
        ) as PagingSource.LoadResult.Page

        val localTargetIndex = targetIndex - result.itemsBefore
        assertTrue(localTargetIndex in result.data.indices)
        assertEquals(target.id, result.data[localTargetIndex].id)
        assertEquals(targetIndex, reader.countTimelineNewer(bufferId, target.serverTime, target.id, spec))
        assertEquals(
            target.id,
            reader.resolveSavedAnchor(
                bufferId,
                target.msgid,
                target.serverTime,
                target.id,
                spec,
            )?.id,
        )
        assertTrue(result.data.size <= MESSAGE_PAGING_CONFIG.pageSize)
        val expectedVisible = (1..totalRows).count { it % 17 != 0 && it % 19 != 0 }
        assertEquals(expectedVisible, result.itemsBefore + result.data.size + result.itemsAfter)
    }

    @Test
    fun foolSetPastBindLimitIsCompleteAndEmptySetProducesValidSql() = runTest {
        db.messageDao().insertAll(
            listOf(
                message(bufferId, "good", sender = "person", serverTime = 1, dedupKey = "good"),
                message(bufferId, "hidden", sender = "fool-1499", serverTime = 2, dedupKey = "hidden"),
                message(
                    bufferId,
                    "own",
                    sender = "fool-1499",
                    serverTime = 3,
                    dedupKey = "own",
                    isSelf = true,
                ),
                message(
                    bufferId,
                    "quoted fool",
                    sender = "fool-' OR 1=1 --",
                    serverTime = 4,
                    dedupKey = "quoted-fool",
                ),
            ),
        )
        val largeSpec = MessageVisibilitySpec(
            fools = buildSet {
                repeat(1_500) { add("fool-$it") }
                add("fool-' OR 1=1 --")
            },
            foolsMode = FoolsMode.HIDE,
        )
        val query = messagePagingQuery(bufferId, largeSpec)
        assertEquals(1, query.argCount)
        assertFalse(query.sql.contains("OFFSET", ignoreCase = true))
        assertFalse(query.sql.contains("OR 1=1"))
        val largePage = db.messageDao().pagingSource(query).load(
            PagingSource.LoadParams.Refresh(null, 50, true),
        ) as PagingSource.LoadResult.Page
        assertEquals(listOf("own", "good"), largePage.data.map { it.text })

        val emptyQuery = messagePagingQuery(
            bufferId,
            MessageVisibilitySpec(fools = emptySet(), foolsMode = FoolsMode.HIDE),
        )
        assertFalse(emptyQuery.sql.contains("IN ()"))
        val emptyPage = db.messageDao().pagingSource(emptyQuery).load(
            PagingSource.LoadParams.Refresh(null, 50, true),
        ) as PagingSource.LoadResult.Page
        assertEquals(4, emptyPage.data.size)
    }

    @Test
    fun accountFoolFilteringDoesNotHideUnmatchedNullAccounts() = runTest {
        db.messageDao().insertAll(
            listOf(
                message(bufferId, "account fool", "new-nick", 2, "account")
                    .copy(senderAccount = "stable-account"),
                message(bufferId, "ordinary", "person", 1, "ordinary"),
            ),
        )
        val page = db.messageDao().pagingSource(
            messagePagingQuery(
                bufferId,
                MessageVisibilitySpec(
                    fools = setOf("stable-account"),
                    foolsMode = FoolsMode.HIDE,
                ),
            ),
        ).load(PagingSource.LoadParams.Refresh(null, 50, true)) as PagingSource.LoadResult.Page

        assertEquals(listOf("ordinary"), page.data.map { it.text })
    }
}
