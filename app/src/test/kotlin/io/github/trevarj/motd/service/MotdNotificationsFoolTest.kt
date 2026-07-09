package io.github.trevarj.motd.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.di.ForegroundBufferTrackerImpl
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Regression for the "adding a nick to fools crashes the app" report. The events collector runs on
 * Dispatchers.Main, and [MotdNotifications.onIncoming] used to read Room + the fools DataStore via
 * `runBlocking { suspend query }`, which blocks the main thread and crashes/deadlocks once a
 * freshly-added fool's message arrives (same class of bug as the findSelfEchoCandidate main-thread
 * fix). onIncoming is now a plain suspend call.
 *
 * This exercises the real read path end to end from the main dispatcher: a fool's message must post
 * no notification (silenced), a normal sender's must post one — without blocking the main thread.
 */
@RunWith(RobolectricTestRunner::class)
class MotdNotificationsFoolTest {

    private lateinit var db: MotdDatabase
    private lateinit var repo: DataStoreSettingsRepository
    private lateinit var notifications: MotdNotifications
    private var bufferId: Long = 0

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val networkId = db.networkDao().insert(
            NetworkEntity(
                name = "libera", role = NetworkRole.DIRECT, host = "irc.libera.chat",
                port = 6697, nick = "me", username = "me", realname = "Me",
            ),
        )
        // A DM buffer keyed by the sender so (DM || mention) qualifies upstream.
        bufferId = db.bufferDao().insert(
            BufferEntity(
                networkId = networkId, name = "troll", displayName = "troll",
                type = BufferType.QUERY,
            ),
        )
        // Grant POST_NOTIFICATIONS so the control (non-fool) case posts regardless of test SDK level.
        shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        repo = DataStoreSettingsRepository(context)
        notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun chat(nick: String, text: String) = IrcEvent.ChatMessage(
        ctx = MessageContext(msgid = null, serverTime = 1000, account = null, batchId = null, label = null),
        kind = IrcEvent.ChatKind.PRIVMSG,
        source = Prefix(nick),
        target = "me",
        text = text,
        isSelf = false,
        replyToMsgid = null,
    )

    private fun postedCount(): Int =
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .activeNotifications.size

    /**
     * The crash repro path: add a fool, then run the notification pipeline for that fool's message.
     * onIncoming reads the fools DataStore + the buffer via suspend calls (no runBlocking on the
     * main-thread events collector), so it completes cleanly and posts nothing (fools are silenced).
     */
    @Test
    fun addedFool_incomingMessage_isSilenced_andDoesNotCrash() = runTest {
        repo.setFool("Troll", true)
        notifications.onIncoming(
            networkId = 1, bufferId = bufferId, type = BufferType.QUERY,
            hasMention = false, message = chat("troll", "hey"),
        )
        assertEquals(0, postedCount())
    }

    /** Control: a non-fool DM still posts a notification through the same suspend read path. */
    @Test
    fun nonFool_incomingMessage_posts() = runTest {
        notifications.onIncoming(
            networkId = 1, bufferId = bufferId, type = BufferType.QUERY,
            hasMention = false, message = chat("troll", "hey"),
        )
        assertEquals(1, postedCount())
    }
}
