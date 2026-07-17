package io.github.trevarj.motd.ui.theme

import androidx.compose.material3.Typography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FontScaleTest {
    @Test
    fun scale_factor_clamps_and_rounds_to_supported_steps() {
        assertEquals(0.88f, typographyScaleFactor(-1), 0.001f)
        assertEquals(0.935f, typographyScaleFactor(83), 0.001f)
        assertEquals(1.1f, typographyScaleFactor(100), 0.001f)
        assertEquals(1.54f, typographyScaleFactor(999), 0.001f)
    }

    @Test
    fun conversation_scale_keeps_the_original_baseline() {
        assertEquals(0.8f, conversationTypographyScaleFactor(-1), 0.001f)
        assertEquals(0.85f, conversationTypographyScaleFactor(83), 0.001f)
        assertEquals(1f, conversationTypographyScaleFactor(100), 0.001f)
        assertEquals(1.4f, conversationTypographyScaleFactor(999), 0.001f)
    }

    @Test
    fun independent_derivations_always_start_from_unscaled_base_tokens() {
        val base = Typography()
        val ui = scaledTypography(140, base)
        val conversation = scaledConversationTypography(80, base)

        assertEquals(base.bodyLarge.fontSize.value * 1.54f, ui.bodyLarge.fontSize.value, 0.001f)
        assertEquals(base.bodyLarge.fontSize.value * 0.8f, conversation.bodyLarge.fontSize.value, 0.001f)
        assertNotEquals(
            ui.bodyLarge.fontSize.value * conversationTypographyScaleFactor(80),
            conversation.bodyLarge.fontSize.value,
            0.001f,
        )
    }
}
