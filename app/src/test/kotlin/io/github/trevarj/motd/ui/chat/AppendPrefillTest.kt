package io.github.trevarj.motd.ui.chat

import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class AppendPrefillTest {

    @Test fun empty_current_no_separator() {
        val out = appendPrefill(TextFieldValue(""), "alice: ")
        assertEquals("alice: ", out.text)
        assertEquals(out.text.length, out.selection.start)
        assertEquals(out.text.length, out.selection.end)
    }

    @Test fun non_empty_no_trailing_space_inserts_single_space() {
        val out = appendPrefill(TextFieldValue("hey"), "alice: ")
        assertEquals("hey alice: ", out.text)
    }

    @Test fun trailing_space_not_duplicated() {
        val out = appendPrefill(TextFieldValue("hey "), "alice: ")
        assertEquals("hey alice: ", out.text)
    }

    @Test fun cursor_lands_at_end() {
        val out = appendPrefill(TextFieldValue("hey"), "alice: ")
        assertEquals(out.text.length, out.selection.start)
        assertEquals(out.text.length, out.selection.end)
    }
}
