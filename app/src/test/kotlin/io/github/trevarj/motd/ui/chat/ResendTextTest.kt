package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ResendTextTest {

    @Test fun `ACTION re-prefixes the me marker so it resends as an action`() {
        // Stored display text has the me marker stripped; retry must restore it (plans/15 #10).
        assertEquals("/me waves at everyone", resendText(MessageKind.ACTION, "waves at everyone"))
    }

    @Test fun `PRIVMSG resends verbatim`() {
        assertEquals("hello there", resendText(MessageKind.PRIVMSG, "hello there"))
    }

    @Test fun `NOTICE resends verbatim`() {
        assertEquals("heads up", resendText(MessageKind.NOTICE, "heads up"))
    }
}
