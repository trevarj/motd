package io.github.trevarj.motd.ui.components

import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.ui.theme.spacingFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoLineLayoutTest {
    @Test
    fun body_column_begins_after_the_reserved_avatar_and_header_gap() {
        val spacing = spacingFor(LayoutDensity.TWO_LINE)

        assertTrue(spacing.twoLine)
        assertFalse(spacing.compact)
        assertEquals(20.dp, spacing.bubbleAvatar)
        assertEquals(26.dp, twoLineBodyIndent(spacing))
    }
}
