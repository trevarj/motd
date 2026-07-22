package io.github.trevarj.motd.e2e.robots

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.ui.chat.timelineMessageTag

internal class ChatListRobot(compose: ComposeTestRule) : BaseRobot(compose) {
    fun open(bufferId: Long) = click("chatlist_row_$bufferId")
}

internal class ChatRobot(compose: ComposeTestRule) : BaseRobot(compose) {
    fun send(text: String) {
        replace("chat_composer_field", text)
        click("chat_composer_send")
    }
}

internal class TimelineRobot(private val rule: ComposeTestRule) : BaseRobot(rule) {
    fun assertMessage(message: MessageEntity, text: String) {
        val tag = timelineMessageTag(message.msgid, message.id)
        scrollContainerTo("chat_timeline", tag)
        rule.onNodeWithTag(tag, useUnmergedTree = true).assertTextContains(text, substring = true)
    }
}
