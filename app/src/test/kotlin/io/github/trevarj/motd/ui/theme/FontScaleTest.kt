package io.github.trevarj.motd.ui.theme

import androidx.compose.material3.Typography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FontScaleTest {
    @Test
    fun scale_factor_clamps_and_rounds_to_supported_steps() {
        assertEquals(0.8f, typographyScaleFactor(-1), 0.001f)
        assertEquals(0.85f, typographyScaleFactor(83), 0.001f)
        assertEquals(1f, typographyScaleFactor(100), 0.001f)
        assertEquals(1.4f, typographyScaleFactor(999), 0.001f)
    }

    @Test
    fun independent_derivations_always_start_from_unscaled_base_tokens() {
        val base = Typography()
        val ui = scaledTypography(140, base)
        val conversation = scaledTypography(80, base)

        assertEquals(base.bodyLarge.fontSize * 1.4f, ui.bodyLarge.fontSize)
        assertEquals(base.bodyLarge.fontSize * 0.8f, conversation.bodyLarge.fontSize)
        assertNotEquals(ui.bodyLarge.fontSize * 0.8f, conversation.bodyLarge.fontSize)
    }
}
