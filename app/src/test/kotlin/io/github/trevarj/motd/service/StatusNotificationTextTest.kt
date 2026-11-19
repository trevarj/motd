package io.github.trevarj.motd.service

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.MainActivity
import io.github.trevarj.motd.irc.event.IrcClientState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en")
class StatusNotificationTextTest {
    private fun ready(nick: String) = IrcClientState.Ready(nick, emptySet(), emptyMap())

    @Test
    fun partialRecoveryShowsConnectedAndConnectingTogether() {
        val shape =
            statusNotificationShape(
                ConnectionActivitySnapshot(states = mapOf(1L to ready("me"), 2L to IrcClientState.Registering)),
                mapOf(1L to "Libera", 2L to "OFTC"),
                hasDefaultNetwork = true,
            )
        val notification = build(shape)

        assertEquals("1 connected · 1 connecting", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals("Libera: Connected\nOFTC: Connecting", notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertEquals("Disconnect", notification.actions.single().title)
        val disconnect = shadowOf(notification.actions.single().actionIntent).savedIntent
        assertEquals(IrcForegroundService.ACTION_STOP, disconnect.action)
        assertEquals(IrcForegroundService::class.java.name, disconnect.component?.className)
        val open = shadowOf(notification.contentIntent).savedIntent
        assertEquals(Intent.ACTION_MAIN, open.action)
        assertEquals(MainActivity::class.java.name, open.component?.className)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun waitingOfflineAndErrorsAreDistinctAndPublicVersionIsGeneric() {
        val activity =
            ConnectionActivitySnapshot(
                states =
                    mapOf(
                        1L to IrcClientState.Connecting,
                        2L to IrcClientState.Failed("server replied with a secret", fatal = true),
                    ),
            )
        val names = mapOf(1L to "Waiting net", 2L to "Failed net", 3L to "Stopped net")
        val notification = build(statusNotificationShape(activity, names, hasDefaultNetwork = false))

        assertEquals("1 waiting · 1 offline · 1 error", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(
            "Waiting net: Waiting for connectivity\nFailed net: Connection error\nStopped net: Offline",
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        )
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val publicVersion = requireNotNull(notification.publicVersion)
        assertEquals("IRC connection service", publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNull(publicVersion.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertTrue(publicVersion.actions.isNullOrEmpty())
        assertEquals(Notification.VISIBILITY_PUBLIC, publicVersion.visibility)
    }

    @Test
    fun stableRowsDeduplicateProtocolDetailsButNotStatusOrNameChanges() {
        val names = mapOf(1L to "Libera", 2L to "OFTC", 3L to "Other")
        val activity =
            ConnectionActivitySnapshot(
                states =
                    mapOf(
                        1L to ready("me"),
                        2L to IrcClientState.Connecting,
                        3L to IrcClientState.Failed("first reason", fatal = false),
                    ),
            )
        val stable =
            activity.copy(
                states =
                    mapOf(
                        3L to IrcClientState.Failed("different reason", fatal = true),
                        2L to IrcClientState.Registering,
                        1L to ready("changed_nick").copy(caps = setOf("batch")),
                    ),
                historyCatchUpPending = setOf(1L),
            )
        val shape = statusNotificationShape(activity, names, hasDefaultNetwork = true)

        assertEquals(shape, statusNotificationShape(stable, names, hasDefaultNetwork = null))
        assertNotEquals(shape, statusNotificationShape(stable, names + (1L to "Renamed"), hasDefaultNetwork = true))
        assertNotEquals(
            shape,
            statusNotificationShape(
                activity.copy(states = activity.states + mapOf(1L to IrcClientState.Registering, 2L to ready("me"))),
                names,
                hasDefaultNetwork = true,
            ),
        )
    }

    @Test
    fun startupIsOnlyShownBeforeInitialReconcileWithNoLiveState() {
        val initial = ConnectionActivitySnapshot(initializationComplete = false)
        val names = mapOf(1L to "Libera")

        assertTrue(statusNotificationShape(initial, names, hasDefaultNetwork = true).starting)
        val live = initial.copy(states = mapOf(1L to ready("me")))
        assertFalse(statusNotificationShape(live, names, hasDefaultNetwork = true).starting)
        assertFalse(statusNotificationShape(initial.copy(initializationComplete = true), names, hasDefaultNetwork = true).starting)
    }

    private fun build(shape: StatusNotificationShape): Notification = buildStatusNotification(ApplicationProvider.getApplicationContext<Context>(), shape)
}
