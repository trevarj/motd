package io.github.trevarj.motd.e2e.robots

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText

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
    fun assertMessage(text: String) {
        scrollContainerTo("chat_timeline", hasText(text, substring = true))
        rule.onNodeWithText(text, substring = true, useUnmergedTree = true).assertTextContains(text, substring = true)
    }
}
