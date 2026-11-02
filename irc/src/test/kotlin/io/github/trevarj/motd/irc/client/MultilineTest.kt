package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IRC_COLOR
import io.github.trevarj.motd.irc.format.IRC_RESET
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultilineTest {
    @Test
    fun `formatted components fit and render independently`() {
        val raw = "$IRC_BOLD${IRC_COLOR}04" + "alpha beta gamma\ndelta$IRC_RESET"
        val plan =
            planChatMessage(
                target = "#test",
                text = raw,
                replyToMsgid = null,
                label = "motd-test",
                multilineLimits = MultilineLimits(maxBytes = 200, maxLines = 20),
                maxComponentBytes = 14,
            ) as MultilineSendPlan.Batch

        val parts = plan.components.map { it.params.last() }
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= 14 })
        assertTrue(parts.all { parseIrcFormatting(it).activeState.isDefault })
        val reconstructed =
            plan.components
                .mapIndexed { index, component ->
                    val separator = if (index > 0 && MULTILINE_CONCAT_TAG !in component.tags) "\n" else ""
                    separator + parseIrcFormatting(component.params.last()).visibleText
                }.joinToString("")
        assertEquals("alpha beta gamma\ndelta", reconstructed)
        assertTrue(parseIrcFormatting(parts.last()).runs.all { it.state.bold })
    }

    @Test
    fun `formatting overhead counts against multiline byte limit`() {
        val raw = "$IRC_BOLD${IRC_COLOR}04" + "alpha beta gamma$IRC_RESET"
        assertEquals(
            null,
            planChatMessage(
                target = "#test",
                text = raw,
                replyToMsgid = null,
                label = "motd-test",
                multilineLimits = MultilineLimits(maxBytes = 10, maxLines = null),
                maxComponentBytes = 12,
            ),
        )
    }

    @Test
    fun `multiline byte limit includes logical line separator`() {
        val text = "a\nb"
        assertTrue(
            planChatMessage(
                target = "#test",
                text = text,
                replyToMsgid = null,
                label = "motd-test",
                multilineLimits = MultilineLimits(maxBytes = 3, maxLines = null),
            ) is MultilineSendPlan.Batch,
        )
        assertEquals(
            null,
            planChatMessage(
                target = "#test",
                text = text,
                replyToMsgid = null,
                label = "motd-test",
                multilineLimits = MultilineLimits(maxBytes = 2, maxLines = null),
            ),
        )
    }

    @Test
    fun `multiline byte limit counts UTF-8 and line separators`() {
        val text = "é\nx"
        assertTrue(
            planChatMessage(
                target = "#test",
                text = text,
                replyToMsgid = null,
                label = "motd-test",
                multilineLimits = MultilineLimits(maxBytes = 4, maxLines = null),
            ) is MultilineSendPlan.Batch,
        )
        assertEquals(
            null,
            planChatMessage(
                target = "#test",
                text = text,
                replyToMsgid = null,
                label = "motd-test",
                multilineLimits = MultilineLimits(maxBytes = 3, maxLines = null),
            ),
        )
    }

    @Test
    fun `multiline byte limit counts blank line separators`() {
        val text = "a\n\nb"
        assertTrue(
            planChatMessage(
                target = "#test",
                text = text,
                replyToMsgid = null,
                label = "motd-test",
                multilineLimits = MultilineLimits(maxBytes = 4, maxLines = null),
            ) is MultilineSendPlan.Batch,
        )
        assertEquals(
            null,
            planChatMessage(
                target = "#test",
                text = text,
                replyToMsgid = null,
                label = "motd-test",
                multilineLimits = MultilineLimits(maxBytes = 3, maxLines = null),
            ),
        )
    }

    @Test
    fun `multiline byte limit excludes separators before concat fragments`() {
        val text = "abcd"
        assertTrue(
            planChatMessage(
                target = "#test",
                text = text,
                replyToMsgid = null,
                label = "motd-test",
                multilineLimits = MultilineLimits(maxBytes = 4, maxLines = null),
                maxComponentBytes = 2,
            ) is MultilineSendPlan.Batch,
        )
        assertEquals(
            null,
            planChatMessage(
                target = "#test",
                text = text,
                replyToMsgid = null,
                label = "motd-test",
                multilineLimits = MultilineLimits(maxBytes = 3, maxLines = null),
                maxComponentBytes = 2,
            ),
        )
    }
}
