package io.github.trevarj.motd.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.di.ForegroundBufferTrackerImpl
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Phone-only failure notices and durable reply bookkeeping across receiver cancellation. */
@RunWith(RobolectricTestRunner::class)
class ReplyFailureNotificationTest {
    private lateinit var db: MotdDatabase
    private lateinit var repo: DataStoreSettingsRepository
    private lateinit var notifications: MotdNotifications
    private var bufferId: Long = 0

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val shadowManager
        get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before
    fun setUp() =
        runTest {
            db =
                Room
                    .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                    .allowMainThreadQueries()
                    .setQueryExecutor { it.run() }
                    .setTransactionExecutor { it.run() }
                    .build()
            val networkId =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "libera",
                        role = NetworkRole.DIRECT,
                        host = "irc.libera.chat",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            bufferId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#motd",
                        displayName = "#motd",
                        type = BufferType.CHANNEL,
                    ),
                )
            shadowOf(context as android.app.Application)
                .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            repo = DataStoreSettingsRepository(context)
            notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)
        }

    @After
    fun tearDown() {
        NotificationManagerCompat.from(context).cancelAll()
        db.close()
    }

    @Test
    fun rejectedReplyKeepsTextInPhoneOnlyNoticeWithExplicitRetry() =
        runTest {
            notifications.onReplyFailed(bufferId, "lost text", SendRejectionReason.NOT_IN_CHANNEL)

            val posted = shadowManager.activeNotifications.single()
            assertEquals(MotdNotifications.sendFailureNotificationId(bufferId), posted.id)
            assertEquals(MotdNotifications.CHANNEL_SEND_FAILURES, posted.notification.channelId)
            val notification = posted.notification
            assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
            assertNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))
            assertTrue(
                notification.extras
                    .getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)
                    .toString()
                    .contains("lost text"),
            )
            val retry = shadowOf(notification.actions.single().actionIntent).savedIntent
            assertEquals(ReplyReceiver.ACTION_RETRY_REPLY, retry.action)
            assertEquals(bufferId, retry.getLongExtra(ReplyReceiver.EXTRA_BUFFER_ID, -1))
            assertEquals("lost text", retry.getStringExtra(ReplyReceiver.EXTRA_REPLY_TEXT))
            val open = shadowOf(notification.contentIntent).savedIntent
            assertEquals(MotdNotifications.ACTION_OPEN_BUFFER, open.action)
            assertEquals(bufferId, open.getLongExtra(MotdNotifications.EXTRA_BUFFER_ID, -1))
        }

    @Test
    fun failureNoticeIsRetiredWhenTheRetryLands() =
        runTest {
            notifications.onReplyFailed(bufferId, "lost text", SendRejectionReason.PERSISTENCE_FAILED)
            assertEquals(1, shadowManager.activeNotifications.size)

            notifications.onReplyFailureResolved(bufferId)

            assertEquals(0, shadowManager.activeNotifications.size)
        }

    @Test
    fun failureNoticeIdCannotAliasTheStatusOrOtherNotificationRanges() {
        val id = MotdNotifications.sendFailureNotificationId(bufferId)
        assertTrue(id in 0x20000000..0x2fffffff)
        assertTrue(id != IrcForegroundService.STATUS_ID)
        assertTrue(id != MotdNotifications.messageNotificationId(bufferId))
        assertTrue(id != MotdNotifications.invitationNotificationId(bufferId))
        assertTrue(id != MotdNotifications.transferNotificationId(bufferId))
    }

    @Test
    fun aRejectionForAnUnknownBufferStillSurfacesTheText() =
        runTest {
            notifications.onReplyFailed(4_242, "orphan text", SendRejectionReason.BUFFER_NOT_FOUND)

            val posted = shadowManager.activeNotifications.single()
            val body =
                posted.notification.extras
                    .getCharSequence(NotificationCompat.EXTRA_TEXT)
                    ?.toString()
                    .orEmpty()
            assertTrue(body, body.contains("orphan text"))
        }

    @Test
    fun uncertainDeliveryReplacesRetryWithReviewOnlyAndKeepsOriginalText() =
        runTest {
            notifications.onReplyFailed(bufferId, "lost text", SendRejectionReason.CONNECTION_UNAVAILABLE)

            notifications.onReplyDeliveryUncertain(bufferId, "lost text")

            val notification = shadowManager.activeNotifications.single().notification
            assertEquals("Send status unknown", notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
            assertTrue(
                notification.extras
                    .getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)
                    .toString()
                    .endsWith("\nlost text"),
            )
            assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
            assertTrue(notification.actions.isNullOrEmpty())
            assertNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))
            val open = shadowOf(notification.contentIntent).savedIntent
            assertEquals(MotdNotifications.ACTION_OPEN_BUFFER, open.action)
            assertEquals(bufferId, open.getLongExtra(MotdNotifications.EXTRA_BUFFER_ID, -1))
        }

    @Test
    fun rejectedAndUncertainRetriesNeverDuplicateThePreservedTextOrClearTheNotice() =
        runTest {
            val store = ComposerDraftStore(db)
            store.saveDraft(bufferId, "typing", replyToEventId = 7L)
            var sends = 0
            deliver {
                sends++
                SendAcceptance.Rejected(SendRejectionReason.CONNECTION_UNAVAILABLE)
            }
            deliver(retry = true) {
                sends++
                SendAcceptance.Rejected(SendRejectionReason.NOT_IN_CHANNEL)
            }
            val durableIds = mutableListOf<Long>()
            for (wireAcceptance in listOf(ImmediateWireAcceptance.DISCONNECTED, ImmediateWireAcceptance.FAILED)) {
                deliver(retry = true) {
                    sends++
                    val ids = persistReply("lost text", wireAcceptance.name)
                    durableIds += ids
                    SendAcceptance.Accepted(ids, wireAcceptance)
                }
                assertEquals("typing\nlost text", store.loadDraft(bufferId)?.text)
                assertEquals(7L, store.loadDraft(bufferId)?.replyToEventId)
                assertTrue(
                    shadowManager.activeNotifications
                        .single()
                        .notification.actions
                        .isNullOrEmpty(),
                )
            }
            assertEquals(4, sends)
            assertEquals(durableIds, db.messageDao().byIds(durableIds).map { it.id })
            assertEquals(2, db.messageDao().countForBuffer(bufferId))
        }

    @Test
    @Config(sdk = [33])
    fun deniedNotificationPermissionStillPreservesRejectedAndDurableUncertainText() =
        runTest {
            shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            deliver(text = "rejected text") { SendAcceptance.Rejected(SendRejectionReason.CONNECTION_UNAVAILABLE) }
            val ids = persistReply("uncertain text", "denied-permission")
            deliver(text = "uncertain text") { SendAcceptance.Accepted(ids, ImmediateWireAcceptance.FAILED) }

            assertTrue(shadowManager.activeNotifications.isEmpty())
            assertEquals("rejected text\nuncertain text", ComposerDraftStore(db).loadDraft(bufferId)?.text)
            assertEquals(
                "uncertain text",
                db
                    .messageDao()
                    .byIds(ids)
                    .single()
                    .text,
            )
        }

    @Test
    fun vanishedRoomStillLeavesUncertainTextInTheReviewNotice() =
        runTest {
            db.bufferDao().deleteBuffer(bufferId)

            deliver { SendAcceptance.Accepted(listOf(7L), ImmediateWireAcceptance.DISCONNECTED) }

            assertNull(ComposerDraftStore(db).loadDraft(bufferId))
            val notification = shadowManager.activeNotifications.single().notification
            assertTrue(
                notification.extras
                    .getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)
                    .toString()
                    .endsWith("\nlost text"),
            )
            assertTrue(notification.actions.isNullOrEmpty())
        }

    @Test
    fun cancellationAfterCommitKeepsAcceptedIdsAndPreservesUncertainDraftBeforeFinishingOnce() =
        runTest {
            assertPostCommitCancellation(ImmediateWireAcceptance.FAILED, retry = false)
        }

    @Test
    fun cancellationAfterCommitKeepsAcceptedIdsAndRemovesSuccessfulRetryCopyBeforeFinishingOnce() =
        runTest {
            assertPostCommitCancellation(ImmediateWireAcceptance.ACCEPTED, retry = true)
        }

    private suspend fun assertPostCommitCancellation(
        wireAcceptance: ImmediateWireAcceptance,
        retry: Boolean,
    ) = coroutineScope {
        val store = ComposerDraftStore(db)
        store.saveDraft(bufferId, "typing", replyToEventId = 7L)
        if (retry) {
            store.appendNotificationReply(bufferId, "lost text")
            notifications.onReplyFailed(bufferId, "lost text", SendRejectionReason.CONNECTION_UNAVAILABLE)
        }
        val wireMutex = Mutex(locked = true)
        val committed = CompletableDeferred<List<Long>>()
        val accepted = CompletableDeferred<SendAcceptance.Accepted>()
        val failures = mutableListOf<Throwable>()
        var finishes = 0
        var timeouts = 0
        val receiver =
            launch {
                runBoundedReceiverWork(
                    timeoutMs = 9_000,
                    onTimeout = { timeouts++ },
                    onFailure = failures::add,
                    finish = { finishes++ },
                ) {
                    deliver(retry = retry) {
                        val ids = persistReply("lost text", "cancel-after-commit")
                        val result =
                            completeDurableAcceptance(
                                eventIds = ids,
                                transition = {
                                    committed.complete(ids)
                                    wireMutex.withLock { wireAcceptance }
                                },
                                secondaryEffect = {},
                            )
                        accepted.complete(result)
                        result
                    }
                }
            }
        val ids = committed.await()
        receiver.cancel()
        assertFalse(accepted.isCompleted)
        assertEquals(0, finishes)
        wireMutex.unlock()
        receiver.join()

        assertEquals(SendAcceptance.Accepted(ids, wireAcceptance), accepted.await())
        assertEquals(ids, db.messageDao().byIds(ids).map { it.id })
        assertEquals(
            "lost text",
            db
                .messageDao()
                .byIds(ids)
                .single()
                .text,
        )
        assertEquals(1, finishes)
        assertEquals(0, timeouts)
        assertTrue(failures.isEmpty())
        assertEquals(if (retry) "typing" else "typing\nlost text", store.loadDraft(bufferId)?.text)
        assertEquals(7L, store.loadDraft(bufferId)?.replyToEventId)
        if (retry) {
            assertTrue(shadowManager.activeNotifications.isEmpty())
        } else {
            val notification = shadowManager.activeNotifications.single().notification
            assertTrue(notification.actions.isNullOrEmpty())
            assertTrue(
                notification.extras
                    .getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)
                    .toString()
                    .endsWith("\nlost text"),
            )
        }
    }

    private suspend fun persistReply(
        text: String,
        key: String,
    ): List<Long> =
        db.withTransaction {
            db.messageDao().insertAll(
                listOf(message(bufferId, text, sender = "me", serverTime = 100, dedupKey = key, isSelf = true)),
            )
        }

    private suspend fun deliver(
        retry: Boolean = false,
        text: String = "lost text",
        send: suspend () -> SendAcceptance,
    ) = deliverNotificationReply(
        retry = retry,
        send = send,
        preserveDraft = { ComposerDraftStore(db).appendNotificationReply(bufferId, text) },
        releaseDraft = { ComposerDraftStore(db).removeNotificationReply(bufferId, text) },
        notifyFailed = { notifications.onReplyFailed(bufferId, text, it) },
        notifyUncertain = { notifications.onReplyDeliveryUncertain(bufferId, text) },
        notifyResolved = { notifications.onReplyFailureResolved(bufferId) },
    )
}
