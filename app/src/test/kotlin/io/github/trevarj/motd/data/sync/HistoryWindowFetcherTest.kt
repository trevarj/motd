package io.github.trevarj.motd.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryWindowFetcherTest {
    @Test
    fun wireFailureWithoutProgressIsNotReportedAsUpToDate() {
        assertEquals(HistorySyncOutcome.Failed, historySyncOutcome(messages = 0, chats = 0, failedRooms = 1))
        assertEquals(HistorySyncOutcome.Failed, historySyncOutcome(messages = 0, chats = 1, failedRooms = 1))
        assertEquals(HistorySyncOutcome.Partial(12, 1), historySyncOutcome(messages = 12, chats = 1, failedRooms = 1))
        assertEquals(HistorySyncOutcome.Fetched(0, 0), historySyncOutcome(messages = 0, chats = 0, failedRooms = 0))
    }
}
