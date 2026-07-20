package io.github.trevarj.motd.ui.chatlist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatListRemovalCopyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun queryExplainsThatOnlyDownloadedHistoryIsForgotten() {
        val copy = chatRemovalCopy(BufferType.QUERY)

        assertEquals("Forget chat", context.getString(copy.actionLabel))
        assertEquals("Forget chat?", context.getString(copy.confirmTitle))
        assertEquals(
            "Remove this chat and its downloaded messages from this device. " +
                "Your bouncer may keep its own history. The chat will return only if you start " +
                "a new conversation or receive a new message.",
            context.getString(copy.message),
        )
        assertEquals("Forget", context.getString(copy.confirmAction))
        assertFalse(copy.messageFormatsDisplayName)
    }

    @Test
    fun channelKeepsLeaveAndDeleteCopy() {
        val copy = chatRemovalCopy(BufferType.CHANNEL)

        assertEquals(R.string.chatlist_delete, copy.actionLabel)
        assertEquals(R.string.chatlist_delete_confirm_title, copy.confirmTitle)
        assertEquals(R.string.chatlist_delete_confirm_channel, copy.message)
        assertEquals(R.string.action_delete, copy.confirmAction)
        assertTrue(copy.messageFormatsDisplayName)
    }
}
