package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongDraftTest {
    @Test fun fourLinesAreIntercepted() { assertTrue(isLongDraft("a\nb\nc\nd")) }
    @Test fun twelveHundredCharactersAreIntercepted() { assertTrue(isLongDraft("x".repeat(1_200))) }
    @Test fun ordinaryDraftIsNotIntercepted() { assertFalse(isLongDraft("hello\nworld")) }
}
