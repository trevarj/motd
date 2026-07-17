package io.github.trevarj.motd.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
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
    fun conversation_scale_keeps_the_material_baseline() {
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

        assertEquals(base.bodyLarge.fontSize.value * 1.4f, ui.bodyLarge.fontSize.value, 0.001f)
        assertEquals(base.bodyLarge.fontSize.value * 0.8f, conversation.bodyLarge.fontSize.value, 0.001f)
        assertNotEquals(
            ui.bodyLarge.fontSize.value * conversationTypographyScaleFactor(80),
            conversation.bodyLarge.fontSize.value,
            0.001f,
        )
    }

    @Test
    fun scaling_preserves_material_tracking_while_resizing_type_and_line_height() {
        val base = Typography()
        val scaled = scaledTypography(100, base)

        typographyStyles(base).zip(typographyStyles(scaled)).forEachIndexed { index, (baseStyle, scaledStyle) ->
            assertEquals(
                "font size for role $index",
                baseStyle.fontSize.value,
                scaledStyle.fontSize.value,
                0.001f,
            )
            assertEquals(
                "line height for role $index",
                baseStyle.lineHeight.value,
                scaledStyle.lineHeight.value,
                0.001f,
            )
            assertEquals(
                "letter spacing for role $index",
                baseStyle.letterSpacing,
                scaledStyle.letterSpacing,
            )
        }
    }

    private fun typographyStyles(typography: Typography): List<TextStyle> = listOf(
        typography.displayLarge,
        typography.displayMedium,
        typography.displaySmall,
        typography.headlineLarge,
        typography.headlineMedium,
        typography.headlineSmall,
        typography.titleLarge,
        typography.titleMedium,
        typography.titleSmall,
        typography.bodyLarge,
        typography.bodyMedium,
        typography.bodySmall,
        typography.labelLarge,
        typography.labelMedium,
        typography.labelSmall,
    )
}
