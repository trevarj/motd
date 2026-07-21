package io.github.trevarj.motd.ui.about

import org.junit.Assert.assertEquals
import org.junit.Test

class AboutScreenTest {
    @Test
    fun buildLabelIncludesVersionAndSourceCommit() {
        assertEquals("1.2.3 (abc123)", aboutBuildLabel("1.2.3", "abc123"))
    }

    @Test
    fun buildLabelPreservesUnknownSourceCommit() {
        assertEquals("1.2.3 (unknown)", aboutBuildLabel("1.2.3", "unknown"))
    }
}
