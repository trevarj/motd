package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.event.IrcClientState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the self-healing reconcile decision [shouldRebuildActor] (#43). A plain reconcile
 * must revive an actor that died/parked in the background (Doze / network drop leaves it terminally
 * Failed with a completed job) so the app reconnects on foreground, WITHOUT disturbing a healthy,
 * connecting, or actively-retrying actor (no reconnect storms) and WITHOUT re-looping an actor
 * parked awaiting cert trust (handled by trustCert). Pure-function testing style matching
 * [ConnectionIntentsTest] / [TrustReconnectTest] / [ChildReconnectTest].
 */
class ActorRebuildTest {

    private val ready = IrcClientState.Ready(nick = "motd", caps = emptySet(), isupport = emptyMap())
    private val fatal = IrcClientState.Failed("sasl fail", fatal = true)

    @Test
    fun `healthy connected actor is not rebuilt`() {
        assertFalse(
            shouldRebuildActor(
                fingerprintChanged = false,
                actorAlive = true,
                lastState = ready,
                awaitingCertTrust = false,
            ),
        )
    }

    @Test
    fun `actively retrying actor (alive, last Failed non-fatal) is not rebuilt`() {
        // A live loop backing off between retries owns its own recovery; leaving it alone avoids storms.
        assertFalse(
            shouldRebuildActor(
                fingerprintChanged = false,
                actorAlive = true,
                lastState = IrcClientState.Failed("io", fatal = false),
                awaitingCertTrust = false,
            ),
        )
    }

    @Test
    fun `dead parked wanted actor is rebuilt`() {
        // Loop finished on a fatal Failed: the actor sits with a completed job and cannot self-recover.
        assertTrue(
            shouldRebuildActor(
                fingerprintChanged = false,
                actorAlive = false,
                lastState = fatal,
                awaitingCertTrust = false,
            ),
        )
    }

    @Test
    fun `actor awaiting cert trust is never rebuilt even when its loop is dead`() {
        // The cert park is owned by trustCert/dismiss; rebuilding would re-loop the handshake / re-spam.
        assertFalse(
            shouldRebuildActor(
                fingerprintChanged = false,
                actorAlive = false,
                lastState = IrcClientState.Failed("certificate not trusted", fatal = false),
                awaitingCertTrust = true,
            ),
        )
    }

    @Test
    fun `fingerprint change rebuilds even a live healthy actor`() {
        // Config edit must restart the socket regardless of liveness (pre-existing behavior preserved).
        assertTrue(
            shouldRebuildActor(
                fingerprintChanged = true,
                actorAlive = true,
                lastState = ready,
                awaitingCertTrust = false,
            ),
        )
    }

    @Test
    fun `fingerprint change never overrides an awaiting-cert-trust park`() {
        // Cert-trust guard wins: don't storm the handshake even if the row was edited while parked.
        assertFalse(
            shouldRebuildActor(
                fingerprintChanged = true,
                actorAlive = false,
                lastState = IrcClientState.Failed("certificate not trusted", fatal = false),
                awaitingCertTrust = true,
            ),
        )
    }
}
