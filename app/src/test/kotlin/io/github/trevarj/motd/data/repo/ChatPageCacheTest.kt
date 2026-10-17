package io.github.trevarj.motd.data.repo

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.CombinedLoadStates
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadState
import androidx.paging.LoadType
import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.ComposerDraftEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.service.AppVisibility
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalPagingApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatPageCacheTest {
    private val hosted = LinkedHashMap<ChatPageCache.Entry, HostedPresentation>()
    private var startHostCollections = true

    @Test
    fun alternatingRoomsReplaysMaterializedPagesWithoutAnotherLocalLoad() =
        cacheTest { fixture ->
            val rooms = fixture.seedRooms(2)
            val first = fixture.acquire(rooms[0])
            val firstView = present(first)
            val newest = checkNotNull(firstView.differ.peek(0)).id
            firstView.detach()
            val saved = fixture.acquire(rooms[1], index = 186)
            val savedView = present(saved)
            val savedId = checkNotNull(savedView.differ.peek(186)).id
            savedView.detach()
            val loads = fixture.localLoads.get()
            assertTrue("the fixture must observe both original native page queries", loads >= 2)

            val firstAgain = fixture.acquire(rooms[0], expectedId = newest)
            assertSame(first.pages, firstAgain.pages)
            first.updateViewport(ViewportRefreshAnchor.Parked(Long.MIN_VALUE))
            first.release()
            assertEquals(ViewportRefreshAnchor.Parked(newest), firstAgain.entry.viewport.value)
            assertTrue("a disposed visit cannot release its successor's lease", firstAgain.entry.active)
            val firstAgainView = present(firstAgain)
            assertSame(firstView.differ, firstAgainView.differ)
            assertEquals(newest, firstAgainView.differ.peek(0)?.id)
            firstAgainView.detach()
            val savedAgain = fixture.acquire(rooms[1], index = 186, expectedId = savedId)
            assertSame(saved.pages, savedAgain.pages)
            val savedAgainView = present(savedAgain)
            assertSame(savedView.differ, savedAgainView.differ)
            assertEquals(savedId, savedAgainView.differ.peek(186)?.id)
            assertEquals(
                600,
                savedAgainView.differ
                    .snapshot()
                    .items.size,
            )
            assertEquals("replay must not execute another Room LIMIT/OFFSET load", loads, fixture.localLoads.get())
            savedAgainView.detach()
        }

    @Test
    fun rootPresentationBootstrapsExactWindowBeforeDestinationAdoption() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1).single()
            val expected = fixture.db.messageDao().rawMessages(messagePagingQuery(room, MessageVisibilitySpec()))
            val targetIndex = 750
            val lease = fixture.acquire(room, index = targetIndex, expectedId = expected[targetIndex].id)
            advanceUntilIdle()
            val loads = fixture.localLoads.get()
            val remoteLoads = fixture.remoteLoads.get()
            assertTrue("the selected native page must load without a UI collector", loads > 0)

            // Match LazyPagingItems' synchronous cachedPagingData bootstrap, without collecting.
            val cached = (lease.pages as SharedFlow<PagingData<MessageEntity>>).replayCache.single()
            val snapshot = nativePresenter(cached).snapshot()
            val start = checkNotNull(entryAnchorPagingKey(targetIndex)).coerceAtMost(expected.size - MESSAGE_PAGING_CONFIG.initialLoadSize)
            assertEquals(expected.size, snapshot.size)
            assertEquals(start, snapshot.placeholdersBefore)
            assertEquals(expected.size - start - MESSAGE_PAGING_CONFIG.initialLoadSize, snapshot.placeholdersAfter)
            assertEquals(expected.drop(start).take(MESSAGE_PAGING_CONFIG.initialLoadSize), snapshot.items)
            assertEquals(expected[targetIndex], snapshot[targetIndex])

            val ui = present(lease)
            assertEquals(snapshot.items, ui.differ.snapshot().items)
            assertEquals("UI adoption must replay the same native load", loads, fixture.localLoads.get())
            assertEquals("warm presentation must add no boundary or access hints", remoteLoads, fixture.remoteLoads.get())
            ui.detach()
        }

    @Test
    fun emptyWarmRefreshBootstrapsAndUiAdoptionDoesNotLoadAgain() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1, rows = 0).single()
            val ordinary = nativePresenter()
            val ordinaryCollection =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.repository.messages(room, MessageVisibilitySpec()).collectLatest { ordinary.collectFrom(it) }
                }
            try {
                ordinary.loadStateFlow.first {
                    it?.refresh is LoadState.NotLoading && it.prepend is LoadState.NotLoading && it.append is LoadState.NotLoading
                }
                advanceUntilIdle()
            } finally {
                ordinaryCollection.cancelAndJoin()
            }
            val ordinaryLoads = fixture.localLoads.get()
            val ordinaryRemoteLoads = fixture.remoteLoads.get()
            val lease = fixture.acquire(room)
            advanceUntilIdle()
            val loads = fixture.localLoads.get()
            val remoteLoads = fixture.remoteLoads.get()
            assertTrue(loads > ordinaryLoads)
            assertEquals("warm collection must not add remote loads beyond an ordinary initial page", ordinaryRemoteLoads, remoteLoads - ordinaryRemoteLoads)
            val cached = (lease.pages as SharedFlow<PagingData<MessageEntity>>).replayCache.single()
            val bootstrap = nativePresenter(cached)
            assertTrue(bootstrap.snapshot().items.isEmpty())
            assertEquals(0, bootstrap.snapshot().size)
            assertTrue(
                bootstrap.loadStateFlow.value
                    ?.source
                    ?.refresh is LoadState.NotLoading,
            )

            val ui = present(lease)
            assertEquals(0, ui.differ.itemCount)
            assertEquals(loads, fixture.localLoads.get())
            assertEquals(remoteLoads, fixture.remoteLoads.get())
            ui.detach()
            assertFalse("a certified terminal-empty native presentation is safe to retain", lease.entry.retired)
        }

    @Test
    fun failedWarmRefreshReachesUiAndOnlyExplicitRetryLoadsRepairedRows() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1).single()
            val newest = checkNotNull(fixture.db.messageDao().newestMessage(room))
            // A real Room enum-conversion failure, not a fake PagingSource error.
            fixture.db.openHelper.writableDatabase.execSQL(
                "UPDATE messages SET kind = 'INVALID_TEST_KIND' WHERE id = ?",
                arrayOf(newest.id),
            )
            val lease = fixture.acquire(room)
            advanceUntilIdle()
            val failedLoads = fixture.localLoads.get()
            assertTrue("warming must have attempted the real page before UI collection", failedLoads > 0)
            val presenter = nativePresenter()
            val collecting =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    lease.pages.collectLatest { presenter.collectFrom(it) }
                }
            try {
                presenter.loadStateFlow.first { it?.source?.refresh is LoadState.Error }
                assertTrue(presenter.snapshot().items.isEmpty())
                fixture.db.openHelper.writableDatabase.execSQL(
                    "UPDATE messages SET kind = 'PRIVMSG' WHERE id = ?",
                    arrayOf(newest.id),
                )
                advanceUntilIdle()
                assertTrue(
                    presenter.loadStateFlow.value
                        ?.source
                        ?.refresh is LoadState.Error,
                )
                assertEquals("warming must not retry a failed initial page", failedLoads, fixture.localLoads.get())

                presenter.retry()
                presenter.loadStateFlow.first { it?.source?.refresh is LoadState.NotLoading }
                assertEquals(newest, presenter.snapshot().items.first())
                assertEquals(MESSAGE_PAGING_CONFIG.initialLoadSize, presenter.snapshot().items.size)
                assertTrue(fixture.localLoads.get() > failedLoads)
            } finally {
                collecting.cancelAndJoin()
                lease.release()
            }
        }

    @Test
    fun detachingUnpresentedWarmEntryWhileBackgroundedCannotLoadOnResume() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1).single()
            fixture.visible.value = false
            val localLoads = fixture.localLoads.get()
            val remoteLoads = fixture.remoteLoads.get()
            val lease = fixture.acquire(room)
            runCurrent()
            assertEquals(localLoads, fixture.localLoads.get())
            assertEquals(remoteLoads, fixture.remoteLoads.get())
            lease.release()
            fixture.visible.value = true
            advanceUntilIdle()
            assertTrue(lease.entry.retired)
            assertEquals(localLoads, fixture.localLoads.get())
            assertEquals(remoteLoads, fixture.remoteLoads.get())
        }

    @Test
    fun aTargetOutsideRetainedPagesLoadsItsOwnNativeWindow() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1).single()
            val first = fixture.acquire(room)
            present(first).detach()
            val loads = fixture.localLoads.get()
            val deep = fixture.acquire(room, index = 750)
            assertSame("changing the target refreshes the same native presentation slot", first.pages, deep.pages)
            val view = present(deep)
            assertEquals("row-150", view.differ.peek(750)?.text)
            assertTrue(fixture.localLoads.get() > loads)
            view.detach()
        }

    @Test
    fun committedEditsDeletesAccountsAndRedactionsMissBeforeObserverDelivery() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1).single()
            val mutations =
                listOf(
                    "UPDATE messages SET text = 'edited' WHERE id = ?",
                    "UPDATE messages SET senderAccount = 'hidden-account' WHERE id = ?",
                    "UPDATE messages SET kind = 'REDACTED', text = '' WHERE id = ?",
                    "DELETE FROM messages WHERE id = ?",
                )
            for (sql in mutations) {
                val original = fixture.acquire(room)
                val view = present(original)
                val id = checkNotNull(view.differ.peek(0)).id
                val oldSnapshot = checkNotNull(original.entry.snapshot)
                view.detach()
                val oldSource = checkNotNull(original.entry.source)
                // Bypass Room's write completion notification, not its tables/triggers. The commit
                // is real; the app-owned TEMP revision must reject it without a delivered callback.
                fixture.db.openHelper.writableDatabase
                    .execSQL(sql, arrayOf(id))
                assertFalse("the observer has deliberately not delivered yet", oldSource.invalid)
                val replacement = fixture.acquire(room)
                assertSame(original.pages, replacement.pages)
                assertFalse("a retained object is not permission to expose its old payload", replacement.admit(oldSnapshot))
                runCurrent()
                assertTrue(oldSource.invalid)
                val replacementView = present(replacement)
                val row = replacementView.differ.peek(0)
                when {
                    sql.contains("SET text") -> assertEquals("edited", row?.text)
                    sql.contains("senderAccount") -> assertEquals("hidden-account", row?.senderAccount)
                    sql.contains("kind") -> assertEquals("REDACTED", row?.kind?.name)
                    else -> assertTrue(row?.id != id)
                }
                replacementView.detach()
            }
        }

    @Test
    fun pendingAccountAndRedactionChangesCannotReplayPreviouslyVisiblePayloads() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1).single()
            fixture.settings.value =
                Settings(fools = setOf("hidden-account"), foolsMode = FoolsMode.HIDE, showRedactedMessages = false)
            runCurrent()
            val spec = MessageVisibilitySpec.from(fixture.settings.value)
            for (assignment in listOf("senderAccount = 'hidden-account'", "kind = 'REDACTED', text = ''")) {
                val original = fixture.acquire(room, spec = spec)
                val view = present(original)
                val hiddenId = checkNotNull(view.differ.peek(0)).id
                val oldSnapshot = checkNotNull(original.entry.snapshot)
                view.detach()
                fixture.db.openHelper.writableDatabase.execSQL(
                    "UPDATE messages SET $assignment WHERE id = ?",
                    arrayOf(hiddenId),
                )
                assertFalse(checkNotNull(original.entry.source).invalid)
                val replacement = fixture.acquire(room, spec = spec)
                assertSame(original.pages, replacement.pages)
                assertFalse("account/redaction commits reject the old native snapshot", replacement.admit(oldSnapshot))
                val replacementView = present(replacement)
                assertTrue(
                    replacementView.differ
                        .snapshot()
                        .items
                        .none { it.id == hiddenId },
                )
                replacementView.detach()
            }
        }

    @Test
    fun redirectedRoomAndChangedIdentityNeverAdoptOldPages() =
        cacheTest { fixture ->
            val rooms = fixture.seedRooms(2)
            val old = fixture.acquire(rooms[0])
            present(old).detach()
            fixture.db.openHelper.writableDatabase.execSQL(
                "UPDATE buffers SET redirectToRoomId = ? WHERE id = ?",
                arrayOf(rooms[1], rooms[0]),
            )
            val redirected = fixture.acquire(rooms[0])
            assertNotSame(old.pages, redirected.pages)
            val redirectedView = present(redirected)
            assertTrue(
                redirectedView.differ
                    .snapshot()
                    .items
                    .all { it.bufferId == rooms[1] },
            )
            redirectedView.detach()
            fixture.db.openHelper.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO network_identity (networkId, caseMapping, chanTypes, selfNick) VALUES (?, 'ascii', '#&', NULL)",
                arrayOf(fixture.networkId),
            )
            val newIdentity = fixture.acquire(rooms[1])
            assertNotSame(redirected.pages, newIdentity.pages)
            present(newIdentity).detach()
        }

    @Test
    fun changedVisibilityCannotReplayHiddenOrRedactedRows() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1).single()
            val original = fixture.acquire(room)
            present(original).detach()
            fixture.settings.value = Settings(fools = setOf("alice"), foolsMode = FoolsMode.HIDE, showRedactedMessages = false)
            runCurrent()
            val filtered = fixture.acquire(room, spec = MessageVisibilitySpec.from(fixture.settings.value))
            assertNotSame(original.pages, filtered.pages)
            val view = present(filtered)
            assertEquals(0, view.differ.itemCount)
            view.detach()
        }

    @Test
    fun detachedInvalidationPreservesPresentationWithoutStartingLocalOrRemoteLoads() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1).single()
            val original = fixture.acquire(room)
            present(original).detach()
            val oldSnapshot = checkNotNull(original.entry.snapshot)
            val local = fixture.localLoads.get()
            val remote = fixture.remoteLoads.get()
            fixture.db.messageDao().insertAll(listOf(message(room, "arrival", serverTime = 2_000, dedupKey = "arrival")))
            advanceUntilIdle()
            assertFalse("ordinary invalidation must not destroy the presentation slot", original.entry.retired)
            assertSame(oldSnapshot, original.entry.snapshot)
            assertEquals(local, fixture.localLoads.get())
            assertEquals(remote, fixture.remoteLoads.get())
            val replacement = fixture.acquire(room)
            assertSame(original.pages, replacement.pages)
            assertFalse(replacement.admit(oldSnapshot))
            val refreshed = present(replacement)
            assertEquals("arrival", refreshed.differ.peek(0)?.text)
            refreshed.detach()
        }

    @Test
    fun lruBackgroundAndOwnerDestructionRetireOnlyTheirOwnedPages() =
        cacheTest { fixture ->
            val rooms = fixture.seedRooms(3)
            val a = fixture.acquire(rooms[0])
            present(a).detach()
            val b = fixture.acquire(rooms[1])
            present(b).detach()
            val aAgain = fixture.acquire(rooms[0])
            assertSame(a.pages, aAgain.pages)
            present(aAgain).detach()
            val c = fixture.acquire(rooms[2])
            val cView = present(c)
            assertTrue(checkNotNull(b.entry.source).invalid)
            fixture.visible.value = false
            runCurrent()
            assertTrue(checkNotNull(a.entry.source).invalid)
            assertFalse("an attached presenter is not stranded by background eviction", checkNotNull(c.entry.source).invalid)
            val beforeBackgroundHint = fixture.localLoads.get()
            cView.differ.getItem(590)
            advanceUntilIdle()
            assertTrue("an attached presenter keeps baseline local paging while stopped", fixture.localLoads.get() > beforeBackgroundHint)
            fixture.visible.value = true
            advanceUntilIdle()
            assertTrue("the same native stream remains usable on resume", fixture.localLoads.get() > beforeBackgroundHint)
            assertEquals("row-280", cView.differ.peek(620)?.text)
            cView.detach()
            fixture.owner.cancel()
            advanceUntilIdle()
            assertTrue(checkNotNull(c.entry.source).invalid)
        }

    @Test
    fun concurrentAdoptersDoNotShareViewportAndDetachedMediatorWaitsForLease() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1).single()
            val entry = fixture.acquire(room)
            val view = present(entry)
            val concurrent = fixture.cache.acquire(room, MessageVisibilitySpec(), null, 0, null, null)
            assertFalse(concurrent.entry.retainable)
            assertNotSame(entry.pages, concurrent.pages)
            present(concurrent).detach()
            view.detach()
            var starts = 0
            val mediator =
                entry.entry.mediator(
                    object : RemoteMediator<Int, MessageEntity>() {
                        override suspend fun load(
                            loadType: LoadType,
                            state: PagingState<Int, MessageEntity>,
                        ): MediatorResult {
                            starts++
                            return MediatorResult.Success(true)
                        }
                    },
                )
            val pending =
                backgroundScope.launch {
                    mediator.load(LoadType.APPEND, PagingState(emptyList(), null, MESSAGE_PAGING_CONFIG, 0))
                }
            runCurrent()
            assertEquals(0, starts)
            fixture.visible.value = false
            runCurrent()
            assertEquals(0, starts)
            pending.cancelAndJoin()
        }

    @Test
    fun oversizedPayloadAndUnpresentedDescriptorsAreNotRetained() =
        cacheTest { fixture ->
            val rooms = fixture.seedRooms(2)
            startHostCollections = false
            val descriptor = fixture.acquire(rooms[0])
            descriptor.release()
            startHostCollections = true
            val retry = fixture.acquire(rooms[0])
            assertNotSame(descriptor.pages, retry.pages)
            present(retry).detach()
            fixture.db.openHelper.writableDatabase.execSQL(
                "UPDATE messages SET text = ? WHERE bufferId = ? AND serverTime = 900",
                arrayOf<Any>("x".repeat(1_048_577), rooms[1]),
            )
            val large = fixture.acquire(rooms[1])
            present(large).detach()
            assertTrue(checkNotNull(large.entry.source).invalid)
            val next = fixture.acquire(rooms[1])
            assertNotSame(large.pages, next.pages)
            next.release()
        }

    @Test
    fun nativePageDropsBoundTheActualRetainedWindowRatherThanLifetimeTraffic() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1, rows = 1_800).single()
            val entry = fixture.acquire(room)
            val view = present(entry)
            for (index in 590..1_490 step 50) {
                view.differ.getItem(index)
                advanceUntilIdle()
            }
            assertFalse("ordinary sequential paging must not invalidate the source", checkNotNull(entry.entry.source).invalid)
            assertTrue(
                view.differ
                    .snapshot()
                    .items.size <= MESSAGE_PAGING_CONFIG.maxSize,
            )
            assertTrue("dropped native pages must release their retention charge", entry.entry.eligible)
            val target = checkNotNull(view.differ.peek(1_490))
            view.detach()
            assertFalse(entry.entry.retired)
            val reopened = fixture.acquire(room, index = 1_490, expectedId = target.id)
            assertSame(entry.pages, reopened.pages)
            present(reopened).detach()
        }

    @Test
    fun inFlightRoomNotificationCannotMakeCommittedRowsLookCacheable() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1).single()
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val armed = AtomicBoolean(false)
            val blocker =
                object : InvalidationTracker.Observer("messages") {
                    override fun onInvalidated(tables: Set<String>) {
                        if (armed.compareAndSet(true, false)) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                }
            fixture.db.invalidationTracker.addObserver(blocker)
            val original = fixture.acquire(room)
            val view = present(original)
            val id = checkNotNull(view.differ.peek(0)).id
            val oldVersion = original.entry.version
            val oldSnapshot = checkNotNull(original.entry.snapshot)
            view.detach()
            fixture.databaseTasks.paused = true
            fixture.db.openHelper.writableDatabase
                .execSQL("UPDATE messages SET text = 'in-flight edit' WHERE id = ?", arrayOf(id))
            armed.set(true)
            fixture.db.invalidationTracker.refreshAsync()
            val refreshTask = fixture.databaseTasks.pending.receive()
            val refreshing = async(Dispatchers.IO) { refreshTask.run() }
            try {
                entered.await()
                // The refresh has claimed/reset its pending flag and released the writer, but
                // observer delivery is blocked and Room's Flow continuations are still queued.
                assertFalse(checkNotNull(original.entry.source).invalid)
                val replacement = fixture.acquire(room)
                assertSame(original.pages, replacement.pages)
                assertNotEquals(oldVersion.revision, replacement.entry.version.revision)
                assertFalse(replacement.admit(oldSnapshot))
                release.countDown()
                refreshing.await()
                fixture.databaseTasks.resume()
                val replacementView = present(replacement)
                assertEquals("in-flight edit", replacementView.differ.peek(0)?.text)
                replacementView.detach()
            } finally {
                release.countDown()
                refreshing.await()
                fixture.databaseTasks.resume()
                fixture.db.invalidationTracker.removeObserver(blocker)
            }
        }

    @Test
    fun externalWriterCommitChangesTheSamePrimaryConnectionsDataVersion() =
        cacheTest(onDisk = true) { fixture ->
            val room = fixture.seedRooms(1).single()
            val original = fixture.acquire(room)
            val view = present(original)
            val id = checkNotNull(view.differ.peek(0)).id
            val oldVersion = original.entry.version
            val oldSnapshot = checkNotNull(original.entry.snapshot)
            view.detach()
            val external = SQLiteDatabase.openDatabase(checkNotNull(fixture.databaseFile).path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                external.execSQL("UPDATE messages SET text = 'external edit' WHERE id = ?", arrayOf(id))
            } finally {
                external.close()
            }
            assertFalse("external writes have not delivered a Room callback", checkNotNull(original.entry.source).invalid)
            val replacement = fixture.acquire(room)
            assertSame(original.pages, replacement.pages)
            assertEquals(oldVersion.session, replacement.entry.version.session)
            assertEquals("the external writer has no access to this connection's TEMP triggers", oldVersion.revision, replacement.entry.version.revision)
            assertNotEquals(oldVersion.externalVersion, replacement.entry.version.externalVersion)
            assertFalse(replacement.admit(oldSnapshot))
            val replacementView = present(replacement)
            assertEquals("external edit", replacementView.differ.peek(0)?.text)
            replacementView.detach()
        }

    @Test
    fun lostTempSessionRejectsUntrackedEditEvenWhenRevisionResetsToZero() =
        cacheTest(onDisk = true) { fixture ->
            val room = fixture.seedRooms(1).single()
            val original = fixture.acquire(room)
            val view = present(original)
            val oldRow = checkNotNull(view.differ.peek(186))
            val oldVersion = original.entry.version
            val oldSnapshot = checkNotNull(original.entry.snapshot)
            view.detach()
            // Closing Room's openHelper directly leaves its compatibility pool holding a closed
            // database. Model complete loss of our connection-local state instead, without
            // invalidating Room's connection ownership or allowing its callbacks to mask the race.
            fixture.databaseTasks.paused = true
            try {
                fixture.db.withTransaction {
                    val writer = fixture.db.openHelper.writableDatabase
                    for (operation in listOf("insert", "update", "delete")) {
                        writer.execSQL("DROP TRIGGER temp.motd_chat_page_$operation")
                    }
                    writer.execSQL("DROP TABLE temp.motd_chat_page_version")
                    writer.execSQL(
                        "UPDATE messages SET text = 'session-loss edit' WHERE id = ?",
                        arrayOf<Any>(oldRow.id),
                    )
                }
                assertFalse("Room notification delivery is still held", checkNotNull(original.entry.source).invalid)
                assertEquals(oldRow.text, view.differ.peek(186)?.text)
                val replacement = fixture.acquire(room, index = 186, expectedId = oldRow.id)
                assertSame(original.pages, replacement.pages)
                assertEquals(0L, oldVersion.revision)
                assertEquals(0L, replacement.entry.version.revision)
                assertEquals(oldVersion.externalVersion, replacement.entry.version.externalVersion)
                assertNotEquals(oldVersion.session, replacement.entry.version.session)
                assertFalse(replacement.admit(oldSnapshot))
                fixture.databaseTasks.resume()
                val replacementView = present(replacement)
                assertEquals(oldRow.id, replacementView.differ.peek(186)?.id)
                assertEquals("session-loss edit", replacementView.differ.peek(186)?.text)
                replacementView.detach()
            } finally {
                fixture.databaseTasks.resume()
            }
        }

    @Test
    fun missingTempTriggerChangesSessionRatherThanTrustingAnUntrackedWrite() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1).single()
            val original = fixture.acquire(room)
            val view = present(original)
            val id = checkNotNull(view.differ.peek(0)).id
            val oldVersion = original.entry.version
            val oldSnapshot = checkNotNull(original.entry.snapshot)
            view.detach()
            fixture.db.openHelper.writableDatabase
                .execSQL("DROP TRIGGER temp.motd_chat_page_update")
            fixture.db.openHelper.writableDatabase
                .execSQL("UPDATE messages SET text = 'untracked edit' WHERE id = ?", arrayOf(id))
            val replacement = fixture.acquire(room)
            assertSame(original.pages, replacement.pages)
            assertNotEquals(oldVersion.session, replacement.entry.version.session)
            assertFalse(replacement.admit(oldSnapshot))
            val replacementView = present(replacement)
            assertEquals("untracked edit", replacementView.differ.peek(0)?.text)
            replacementView.detach()
        }

    @Test
    fun readMarkersAndDraftWritesDoNotInvalidateTheMessageEpoch() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1).single()
            val original = fixture.acquire(room)
            present(original).detach()
            val version = original.entry.version
            val loads = fixture.localLoads.get()
            fixture.db.withTransaction {
                val current = checkNotNull(fixture.db.bufferDao().observeById(room))
                fixture.db.bufferDao().update(current.copy(localReadAnchorTime = 900))
                fixture.db.composerDraftDao().upsert(ComposerDraftEntity(room, "draft", updatedAt = 1))
            }
            val replacement = fixture.acquire(room)
            assertEquals(version, replacement.entry.version)
            assertSame(original.pages, replacement.pages)
            present(replacement).detach()
            assertEquals(loads, fixture.localLoads.get())
        }

    @Test
    fun versionQueriesUseIoRatherThanTheUiDispatcher() =
        cacheTest(realCacheIo = true) { fixture ->
            val room = fixture.seedRooms(1).single()
            val original = fixture.acquire(room)
            present(original).detach()
            val replacement = fixture.acquire(room)
            assertSame(original.pages, replacement.pages)
            present(replacement).detach()
            assertTrue(fixture.epochQueries.get() > 0)
            assertFalse("TEMP schema/version SQL must never execute on Main", fixture.epochOnMain.get())
        }

    @Test
    fun allNonRetainedLeasesGateTheirFirstLocalAndRemoteLoadsInBackground() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1).single()
            val original = fixture.acquire(room)
            val originalView = present(original)

            suspend fun assertGated(acquire: suspend () -> ChatPageCache.Lease) {
                fixture.visible.value = false
                val lease = acquire()
                assertFalse(lease.entry.retainable)
                val local = fixture.localLoads.get()
                val remote = fixture.remoteLoads.get()
                val presenting = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { present(lease) }
                runCurrent()
                assertFalse(presenting.isCompleted)
                assertEquals(local, fixture.localLoads.get())
                assertEquals(remote, fixture.remoteLoads.get())
                fixture.visible.value = true
                val view = presenting.await()
                assertEquals("row-900", view.differ.peek(0)?.text)
                assertTrue(fixture.localLoads.get() > local)
                view.detach()
            }

            assertGated { fixture.cache.acquire(room, MessageVisibilitySpec(), null, 0, null, null) }
            assertGated { fixture.cache.acquire(room, MessageVisibilitySpec(), null, 0, null, null, allowRetention = false) }
            originalView.detach()
            fixture.visible.value = false
            runCurrent()
            assertGated { fixture.acquire(room) }
        }

    @Test
    fun attachedBackgroundPresenterRefreshesHiddenRowsBeforeResumeWithoutHistoryOrReadWrites() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1).single()
            fixture.settings.value = Settings(showRedactedMessages = false)
            runCurrent()
            val entry = fixture.acquire(room, spec = MessageVisibilitySpec.from(fixture.settings.value))
            val view = present(entry)
            val redacted = checkNotNull(view.differ.peek(0))
            val savedId = checkNotNull(view.differ.peek(186)).id
            val remote = fixture.remoteLoads.get()
            val readAnchor =
                fixture.db
                    .bufferDao()
                    .observeById(room)
                    ?.localReadAnchorTime
            fixture.visible.value = false
            fixture.db.messageDao().update(redacted.copy(kind = MessageKind.REDACTED, text = ""))
            advanceUntilIdle()
            assertTrue(
                view.differ
                    .snapshot()
                    .items
                    .none { it.id == redacted.id },
            )
            assertEquals(savedId, view.differ.peek(185)?.id)
            assertEquals(remote, fixture.remoteLoads.get())
            assertEquals(
                readAnchor,
                fixture.db
                    .bufferDao()
                    .observeById(room)
                    ?.localReadAnchorTime,
            )
            fixture.visible.value = true
            runCurrent()
            assertTrue(
                view.differ
                    .snapshot()
                    .items
                    .none { it.id == redacted.id },
            )
            assertEquals(savedId, view.differ.peek(185)?.id)
            view.detach()
        }

    @Test
    fun attachedBackgroundContextReplacementPresentsCurrentRoomAndIdentityWithoutRemoteOrReadWrites() =
        cacheTest { fixture ->
            val rooms = fixture.seedRooms(2)
            val hiddenIds =
                rooms.map { room ->
                    val row = checkNotNull(fixture.db.messageDao().newestMessage(room))
                    fixture.db.messageDao().update(row.copy(sender = "nick{", normalizedActor = "nick{"))
                    row.id
                }
            fixture.db.networkIdentityDao().upsert(NetworkIdentityEntity(fixture.networkId, caseMapping = "ascii"))
            fixture.settings.value = Settings(fools = setOf("nick["), foolsMode = FoolsMode.HIDE)
            runCurrent()
            var entry = fixture.acquire(rooms[0], spec = MessageVisibilitySpec.from(fixture.settings.value))
            var view = present(entry)
            assertEquals(hiddenIds[0], view.differ.peek(0)?.id)
            val remote = fixture.remoteLoads.get()
            val readAnchors =
                rooms.map {
                    fixture.db
                        .bufferDao()
                        .rawById(it)
                        ?.localReadAnchorTime
                }
            fixture.visible.value = false

            // This changes the entire Pager's context, not merely its PagingSource. RFC1459
            // equates '[' and '{', so the previously visible newest row must disappear while stopped.
            fixture.db.networkIdentityDao().upsert(NetworkIdentityEntity(fixture.networkId, caseMapping = "rfc1459"))
            advanceUntilIdle()
            assertTrue("query-identity changes retire the incompatible slot", entry.entry.retired)
            assertFalse(entry.isActive)
            entry =
                fixture.acquire(
                    rooms[0],
                    spec = MessageVisibilitySpec.from(fixture.settings.value),
                    continueLocalRefresh = entry.canContinueLocalRefresh,
                )
            view = present(entry)
            assertEquals(899, view.differ.itemCount)
            assertTrue(
                view.differ
                    .snapshot()
                    .items
                    .none { it.id == hiddenIds[0] },
            )
            assertEquals(rooms[0], view.differ.peek(0)?.bufferId)
            assertEquals(remote, fixture.remoteLoads.get())

            // Canonical redirection is another hard compatibility boundary. The continued attached
            // visit still refreshes locally while stopped, without granting remote/read permission.
            val requested = checkNotNull(fixture.db.bufferDao().observeById(rooms[0]))
            fixture.db.bufferDao().update(requested.copy(redirectToRoomId = rooms[1]))
            advanceUntilIdle()
            assertTrue(entry.entry.retired)
            assertFalse(entry.isActive)
            entry =
                fixture.acquire(
                    rooms[0],
                    spec = MessageVisibilitySpec.from(fixture.settings.value),
                    continueLocalRefresh = entry.canContinueLocalRefresh,
                )
            view = present(entry)
            assertEquals(899, view.differ.itemCount)
            val current = view.differ.snapshot().items
            assertTrue(current.isNotEmpty())
            assertTrue(current.all { it.bufferId == rooms[1] && it.id != hiddenIds[1] })
            assertEquals(remote, fixture.remoteLoads.get())
            assertEquals(
                readAnchors,
                rooms.map {
                    fixture.db
                        .bufferDao()
                        .rawById(it)
                        ?.localReadAnchorTime
                },
            )
            fixture.visible.value = true
            runCurrent()
            assertEquals(rooms[1], view.differ.peek(0)?.bufferId)
            assertTrue(
                view.differ
                    .snapshot()
                    .items
                    .none { it.id in hiddenIds },
            )
            view.detach()
        }

    @Test
    fun returnedRefreshCannotCertifyTheNativeUiSnapshotItHasNotReplaced() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1).single()
            val lease = fixture.acquire(room)
            val view = present(lease)
            val previous = checkNotNull(lease.entry.snapshot)
            val row = checkNotNull(view.differ.peek(186))
            val root = checkNotNull(hosted[lease.entry])
            root.pause()
            fixture.db.openHelper.writableDatabase.execSQL(
                "UPDATE messages SET text = 'fresh publication' WHERE id = ?",
                arrayOf(row.id),
            )
            lease.entry.requestRefresh()
            advanceUntilIdle()
            val incoming = nativePresenter()
            val collecting = backgroundScope.launch { lease.pages.collectLatest(incoming::collectFrom) }
            try {
                incoming.loadStateFlow.first {
                    it?.source?.refresh is LoadState.NotLoading && incoming.peek(186)?.text == "fresh publication"
                }
                assertTrue("the real source returned a pending REFRESH proof", lease.entry.refreshProof != null)
                assertEquals(row.text, root.differ.peek(186)?.text)
                assertFalse("an unchanged first row cannot certify a later edited payload", lease.admit(previous))
                val loads = fixture.localLoads.get()
                collecting.cancelAndJoin()
                root.start()
                val adopted = present(lease)
                assertSame(view.differ, adopted.differ)
                assertEquals("fresh publication", adopted.differ.peek(186)?.text)
                assertEquals("publication must not discard and reload an already fresh REFRESH", loads, fixture.localLoads.get())
                adopted.detach()
            } finally {
                collecting.cancelAndJoin()
            }
        }

    @Test
    fun detachDuringQueuedRefreshKeepsWindowAndSuccessorCannotReviveObsoleteSql() =
        cacheTest { fixture ->
            val rooms = fixture.seedRooms(2)
            val lease = fixture.acquire(rooms[0])
            val view = present(lease)
            val snapshot = checkNotNull(lease.entry.snapshot)
            val version = lease.entry.version
            val loads = fixture.localLoads.get()
            val counts = fixture.localCounts.get()
            fixture.databaseTasks.paused = true
            try {
                fixture.db.openHelper.writableDatabase.execSQL(
                    "UPDATE messages SET text = 'unrelated room' WHERE bufferId = ? AND serverTime = 900",
                    arrayOf(rooms[1]),
                )
                fixture.db.invalidationTracker.refreshAsync()
                // Deliver the real coarse invalidation, but retain the ensuing database-Job work.
                fixture.databaseTasks.pending
                    .receive()
                    .run()
                view.differ.loadStateFlow.first { it.source.refresh is LoadState.Loading }
                val pending = checkNotNull(lease.entry.source)
                assertEquals(loads, fixture.localLoads.get())
                assertEquals(counts, fixture.localCounts.get())
                view.detach()
                runCurrent()
                assertFalse("Loading is not a reason to throw away certified rows", lease.entry.retired)
                assertSame(snapshot, lease.entry.snapshot)
                assertTrue(pending.invalid)
                fixture.databaseTasks.resume()
                advanceUntilIdle()
                assertEquals("detached Room database-Job work must not execute a page", loads, fixture.localLoads.get())
                assertEquals("detached Room database-Job work must not execute COUNT", counts, fixture.localCounts.get())
                assertEquals(version, lease.entry.readVersion())

                fixture.databaseTasks.paused = true
                val second = fixture.acquire(rooms[0])
                assertSame(lease.pages, second.pages)
                assertTrue("unchanged rows are admissible while the replacement REFRESH is pending", second.admit(snapshot))
                val secondSource = checkNotNull(second.entry.source)
                second.release()
                runCurrent()
                val third = fixture.acquire(rooms[0])
                assertSame(lease.pages, third.pages)
                assertTrue(secondSource.invalid)
                assertNotSame(secondSource, third.entry.source)
                assertTrue(third.admit(snapshot))
                fixture.databaseTasks.resume()
                val reopened = present(third)
                assertSame(view.differ, reopened.differ)
                assertEquals("row-900", reopened.differ.peek(0)?.text)
                assertEquals("reattachment must not revive either old queued page", loads + 1, fixture.localLoads.get())
                assertEquals("only the successor's count may execute", counts + 1, fixture.localCounts.get())
                reopened.detach()
            } finally {
                fixture.databaseTasks.resume()
            }
        }

    @Test
    fun unrelatedRoomStormDoesNotReloadOrRetireTwoDetachedPresentations() =
        cacheTest { fixture ->
            val rooms = fixture.seedRooms(3)
            val first = fixture.acquire(rooms[0])
            val firstView = present(first)
            val firstVersion = first.entry.version
            firstView.detach()
            val second = fixture.acquire(rooms[1], index = 186)
            val secondView = present(second)
            val secondVersion = second.entry.version
            secondView.detach()
            val loads = fixture.localLoads.get()
            val counts = fixture.localCounts.get()
            val remote = fixture.remoteLoads.get()
            repeat(20) { ordinal ->
                fixture.db.messageDao().insertAll(
                    listOf(message(rooms[2], "other-room-$ordinal", serverTime = 2_000L + ordinal, dedupKey = "storm-$ordinal")),
                )
                advanceUntilIdle()
            }
            assertEquals(loads, fixture.localLoads.get())
            assertEquals(counts, fixture.localCounts.get())
            assertEquals(remote, fixture.remoteLoads.get())
            assertFalse(first.entry.retired)
            assertFalse(second.entry.retired)
            assertEquals(firstVersion, first.entry.readVersion())
            assertEquals(secondVersion, second.entry.readVersion())
            val reopened = fixture.acquire(rooms[0])
            assertSame(first.pages, reopened.pages)
            assertSame(firstView.differ, checkNotNull(hosted[reopened.entry]).differ)
            assertTrue(reopened.admit(checkNotNull(reopened.entry.snapshot)))
            present(reopened).detach()
            val saved = fixture.acquire(rooms[1], index = 186)
            assertSame(second.pages, saved.pages)
            assertSame(secondView.differ, checkNotNull(hosted[saved.entry]).differ)
            present(saved).detach()
        }

    @Test
    fun repeatedNativeRefreshesReleaseSupersededWindowCharges() =
        cacheTest { fixture ->
            val room = fixture.seedRooms(1).single()
            val lease = fixture.acquire(room)
            val view = present(lease)
            repeat(6) { ordinal ->
                val row = checkNotNull(view.differ.peek(0))
                fixture.db.messageDao().update(row.copy(text = "refresh-$ordinal"))
                advanceUntilIdle()
                assertEquals("refresh-$ordinal", view.differ.peek(0)?.text)
                assertEquals(
                    600,
                    view.differ
                        .snapshot()
                        .items.size,
                )
                assertTrue("refresh traffic is not resident memory", lease.entry.eligible)
                assertTrue(lease.admit(checkNotNull(lease.entry.snapshot)))
            }
            view.detach()
            assertFalse(lease.entry.retired)
            val again = fixture.acquire(room)
            assertSame(lease.pages, again.pages)
            assertSame(view.differ, present(again).differ)
            again.release()
        }

    private fun cacheTest(
        onDisk: Boolean = false,
        realCacheIo: Boolean = false,
        block: suspend TestScope.(Fixture) -> Unit,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseFile = if (onDisk) File.createTempFile("chat-page-cache-", ".db", context.cacheDir) else null
        val builder =
            if (databaseFile == null) {
                Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            } else {
                Room
                    .databaseBuilder(context, MotdDatabase::class.java, databaseFile.absolutePath)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            }
        val databaseTasks = HoldingExecutor()
        val loads = AtomicInteger()
        val counts = AtomicInteger()
        val epochQueries = AtomicInteger()
        val epochOnMain = AtomicBoolean()
        val db =
            builder
                .allowMainThreadQueries()
                .setQueryExecutor(databaseTasks)
                .setTransactionExecutor(Runnable::run)
                .setQueryCallback(
                    { sql, _ ->
                        val count = sql.trimStart().startsWith("SELECT COUNT(", ignoreCase = true)
                        if (sql.contains("messages") && !count && sql.contains("LIMIT") && sql.contains("OFFSET")) loads.incrementAndGet()
                        if (sql.contains("messages") && count) counts.incrementAndGet()
                        if (sql.contains("motd_chat_page_")) {
                            epochQueries.incrementAndGet()
                            if (Looper.myLooper() == Looper.getMainLooper()) epochOnMain.set(true)
                        }
                    },
                    Runnable::run,
                ).build()
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val remoteLoads = AtomicInteger()
        val repository =
            MessageRepositoryImpl(
                db.bufferDao(),
                db.networkIdentityDao(),
                db,
                db.reactionDao(),
                ChatHistoryMediatorFactory { _, _, _ ->
                    object : RemoteMediator<Int, MessageEntity>() {
                        override suspend fun initialize(): InitializeAction = InitializeAction.SKIP_INITIAL_REFRESH

                        override suspend fun load(
                            loadType: LoadType,
                            state: PagingState<Int, MessageEntity>,
                        ): MediatorResult {
                            remoteLoads.incrementAndGet()
                            return MediatorResult.Success(true)
                        }
                    }
                },
                db.historyGapDao(),
                ioDispatcher = dispatcher,
            )
        val visible = MutableStateFlow(true)
        val settings = MutableStateFlow(Settings())
        val cache = ChatPageCache(repository, db, if (realCacheIo) Dispatchers.IO else dispatcher)
        cache.start(
            owner,
            object : AppVisibility {
                override val onScreen = visible
            },
            settings,
        )
        startHostCollections = true
        owner.launch {
            cache.slots.collect { slots ->
                for (entry in hosted.keys.filter { it !in slots }) hosted.remove(entry)?.stop()
                for (entry in slots) {
                    if (entry !in hosted) {
                        val presentation = HostedPresentation(entry, newDiffer(), owner)
                        hosted[entry] = presentation
                        if (startHostCollections) presentation.start()
                    }
                }
            }
        }
        try {
            val fixture =
                Fixture(
                    db,
                    cache,
                    owner,
                    visible,
                    settings,
                    loads,
                    counts,
                    remoteLoads,
                    db.networkDao().insert(network()),
                    databaseFile,
                    databaseTasks,
                    epochQueries,
                    epochOnMain,
                    repository,
                )
            block(fixture)
        } finally {
            databaseTasks.resume()
            checkNotNull(owner.coroutineContext[Job]).cancelAndJoin()
            advanceUntilIdle()
            hosted.clear()
            db.close()
            databaseFile?.let(SQLiteDatabase::deleteDatabase)
            Dispatchers.resetMain()
        }
    }

    /** Pause only Room's async query/notification work; writer transactions remain independently usable. */
    private class HoldingExecutor : Executor {
        @Volatile
        var paused = false
        val pending = Channel<Runnable>(Channel.UNLIMITED)

        override fun execute(command: Runnable) {
            if (paused) check(pending.trySend(command).isSuccess) else command.run()
        }

        fun resume() {
            paused = false
            while (true) (pending.tryReceive().getOrNull() ?: return).run()
        }
    }

    private class Fixture(
        val db: MotdDatabase,
        val cache: ChatPageCache,
        val owner: CoroutineScope,
        val visible: MutableStateFlow<Boolean>,
        val settings: MutableStateFlow<Settings>,
        val localLoads: AtomicInteger,
        val localCounts: AtomicInteger,
        val remoteLoads: AtomicInteger,
        val networkId: Long,
        val databaseFile: File?,
        val databaseTasks: HoldingExecutor,
        val epochQueries: AtomicInteger,
        val epochOnMain: AtomicBoolean,
        val repository: MessageRepositoryImpl,
    ) {
        suspend fun seedRooms(
            count: Int,
            rows: Int = 900,
        ): List<Long> =
            (1..count).map { index ->
                val room = db.bufferDao().insert(buffer(networkId, "#room-$index"))
                db.messageDao().insertAll(
                    (1..rows).map { ordinal ->
                        message(room, "row-$ordinal", serverTime = ordinal.toLong(), dedupKey = "row-$ordinal", msgid = "row-$ordinal")
                    },
                )
                room
            }

        suspend fun acquire(
            room: Long,
            index: Int = 0,
            expectedId: Long? = null,
            spec: MessageVisibilitySpec = MessageVisibilitySpec(),
            continueLocalRefresh: Boolean = false,
        ): ChatPageCache.Lease =
            cache.acquire(
                room,
                spec,
                entryAnchorPagingKey(index),
                index,
                expectedId,
                expectedId?.let(ViewportRefreshAnchor::Parked),
                continueLocalRefresh = continueLocalRefresh,
            )
    }

    private fun TestScope.nativePresenter(cached: PagingData<MessageEntity>? = null): PagingDataPresenter<MessageEntity> =
        object : PagingDataPresenter<MessageEntity>(mainContext = UnconfinedTestDispatcher(testScheduler), cachedPagingData = cached) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<MessageEntity>) = Unit
        }

    private fun TestScope.newDiffer(): AsyncPagingDataDiffer<MessageEntity> {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val differ =
            AsyncPagingDataDiffer(
                diffCallback =
                    object : DiffUtil.ItemCallback<MessageEntity>() {
                        override fun areItemsTheSame(
                            oldItem: MessageEntity,
                            newItem: MessageEntity,
                        ): Boolean = oldItem.id == newItem.id

                        override fun areContentsTheSame(
                            oldItem: MessageEntity,
                            newItem: MessageEntity,
                        ): Boolean = oldItem == newItem
                    },
                updateCallback =
                    object : ListUpdateCallback {
                        override fun onInserted(
                            position: Int,
                            count: Int,
                        ) = Unit

                        override fun onRemoved(
                            position: Int,
                            count: Int,
                        ) = Unit

                        override fun onMoved(
                            fromPosition: Int,
                            toPosition: Int,
                        ) = Unit

                        override fun onChanged(
                            position: Int,
                            count: Int,
                            payload: Any?,
                        ) = Unit
                    },
                mainDispatcher = dispatcher,
                workerDispatcher = dispatcher,
            )
        return differ
    }

    private suspend fun TestScope.present(entry: ChatPageCache.Lease): Presentation {
        advanceUntilIdle()
        val root = checkNotNull(hosted[entry.entry])
        entry.changes.first {
            val states = root.states
            val snapshot = entry.entry.snapshot
            states?.source?.refresh is LoadState.NotLoading && states.source.prepend is LoadState.NotLoading &&
                states.source.append is LoadState.NotLoading && snapshot != null && entry.admit(snapshot)
        }
        return Presentation(entry, root.differ)
    }

    private class HostedPresentation(
        private val entry: ChatPageCache.Entry,
        val differ: AsyncPagingDataDiffer<MessageEntity>,
        private val scope: CoroutineScope,
    ) {
        var states: CombinedLoadStates? = null
            private set
        private var collection: Job? = null
        private val publisher = entry.attachPresenter()
        private var snapshot = differ.snapshot()
        private val loadListener: (CombinedLoadStates) -> Unit = {
            states = it
            entry.presented(publisher, snapshot, it)
        }
        private val pagesListener: () -> Unit = {
            snapshot = differ.snapshot()
            states?.let { entry.presented(publisher, snapshot, it) }
        }

        init {
            differ.addLoadStateListener(loadListener)
            differ.addOnPagesUpdatedListener(pagesListener)
        }

        fun start() {
            collection = scope.launch { entry.pages.collectLatest { differ.submitData(it) } }
        }

        suspend fun pause() {
            collection?.cancelAndJoin()
        }

        suspend fun stop() {
            pause()
            differ.removeLoadStateListener(loadListener)
            differ.removeOnPagesUpdatedListener(pagesListener)
            entry.detachPresenter(publisher)
        }
    }

    private class Presentation(
        private val entry: ChatPageCache.Lease,
        val differ: AsyncPagingDataDiffer<MessageEntity>,
    ) {
        fun detach() {
            entry.release()
        }
    }
}
