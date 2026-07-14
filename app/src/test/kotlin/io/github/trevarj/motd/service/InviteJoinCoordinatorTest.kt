package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.InviteState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class InviteJoinCoordinatorTest {
    @Test fun `concurrent stale action deliveries send exactly one join`() = runTest {
        val lock = Mutex()
        var state = InviteState.PENDING
        var sends = 0

        List(20) {
            async {
                performInviteJoin(
                    initialState = InviteState.PENDING,
                    claim = { from ->
                        lock.withLock {
                            if (state == from) {
                                state = InviteState.JOINING
                                true
                            } else false
                        }
                    },
                    awaitReady = { true },
                    stillJoining = { state == InviteState.JOINING },
                    sendJoin = { sends++ },
                    fail = { state = InviteState.FAILED },
                )
            }
        }.awaitAll()

        assertEquals(1, sends)
        assertEquals(InviteState.JOINING, state)
    }

    @Test fun `timeout has no delayed side effect and remains retryable`() = runTest {
        var state = InviteState.PENDING
        var sends = 0
        suspend fun attempt(ready: Boolean) = performInviteJoin(
            initialState = state,
            claim = { from ->
                if (state == from) {
                    state = InviteState.JOINING
                    true
                } else false
            },
            awaitReady = { ready },
            stillJoining = { state == InviteState.JOINING },
            sendJoin = { sends++ },
            fail = { state = InviteState.FAILED },
        )

        attempt(ready = false)
        assertEquals(InviteState.FAILED, state)
        assertEquals(0, sends)

        // Merely becoming ready later cannot resume the completed first attempt.
        assertEquals(0, sends)
        attempt(ready = true)
        assertEquals(1, sends)
    }

    @Test fun `dismiss during connection wait prevents wire write`() = runTest {
        var state = InviteState.PENDING
        var sends = 0

        performInviteJoin(
            initialState = state,
            claim = {
                state = InviteState.JOINING
                true
            },
            awaitReady = {
                state = InviteState.DISMISSED
                true
            },
            stillJoining = { state == InviteState.JOINING },
            sendJoin = { sends++ },
            fail = { state = InviteState.FAILED },
        )

        assertEquals(InviteState.DISMISSED, state)
        assertEquals(0, sends)
    }
}
