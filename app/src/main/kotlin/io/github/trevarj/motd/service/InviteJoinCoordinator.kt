package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.InviteState
import kotlinx.coroutines.CancellationException

internal class InviteJoinFailure(val userReason: String) : Exception(userReason)

/** Durable CAS -> bounded readiness -> state recheck -> one wire write. */
internal suspend fun performInviteJoin(
    initialState: InviteState?,
    claim: suspend (InviteState) -> Boolean,
    awaitReady: suspend () -> Boolean,
    stillJoining: suspend () -> Boolean,
    sendJoin: suspend () -> Unit,
    fail: suspend (String) -> Unit,
) {
    val fromState = initialState?.takeIf { it == InviteState.PENDING || it == InviteState.FAILED }
        ?: return
    if (!claim(fromState)) return
    try {
        if (!awaitReady()) {
            fail("connection timed out")
            return
        }
        // Dismiss, process recreation, or another resolver may have changed durable state while
        // the connection was starting. This is the last check before the only wire write.
        if (!stillJoining()) return
        sendJoin()
    } catch (cancelled: CancellationException) {
        fail("join cancelled")
        throw cancelled
    } catch (failure: InviteJoinFailure) {
        fail(failure.userReason)
    } catch (_: Exception) {
        fail("send failed")
    }
}
