package io.github.trevarj.motd.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.trevarj.motd.irc.format.IrcColor
import io.github.trevarj.motd.irc.format.IrcFormatState
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import io.github.trevarj.motd.ui.theme.ensureContrast

/** mIRC 0-98 palette used by current mIRC/HexChat-compatible clients. */
internal val MIRC_COLORS =
    intArrayOf(
        0xFFFFFF,
        0x000000,
        0x00007F,
        0x009300,
        0xFF0000,
        0x7F0000,
        0x9C009C,
        0xFC7F00,
        0xFFFF00,
        0x00FC00,
        0x009393,
        0x00FFFF,
        0x0000FC,
        0xFF00FF,
        0x7F7F7F,
        0xD2D2D2,
        0x470000,
        0x472100,
        0x474700,
        0x324700,
        0x004700,
        0x00472C,
        0x004747,
        0x002747,
        0x000047,
        0x2E0047,
        0x470047,
        0x47002A,
        0x740000,
        0x743A00,
        0x747400,
        0x517400,
        0x007400,
        0x007449,
        0x007474,
        0x004074,
        0x000074,
        0x4B0074,
        0x740074,
        0x740045,
        0xB50000,
        0xB56300,
        0xB5B500,
        0x7DB500,
        0x00B500,
        0x00B571,
        0x00B5B5,
        0x0063B5,
        0x0000B5,
        0x7500B5,
        0xB500B5,
        0xB5006B,
        0xFF0000,
        0xFF8C00,
        0xFFFF00,
        0xB2FF00,
        0x00FF00,
        0x00FFA0,
        0x00FFFF,
        0x008CFF,
        0x0000FF,
        0xA500FF,
        0xFF00FF,
        0xFF0098,
        0xFF5959,
        0xFFB459,
        0xFFFF71,
        0xCFFF60,
        0x6FFF6F,
        0x65FFC9,
        0x6DFFFF,
        0x59B4FF,
        0x5959FF,
        0xC459FF,
        0xFF66FF,
        0xFF59BC,
        0xFF9C9C,
        0xFFD39C,
        0xFFFF9C,
        0xE2FF9C,
        0x9CFF9C,
        0x9CFFDB,
        0x9CFFFF,
        0x9CD3FF,
        0x9C9CFF,
        0xDC9CFF,
        0xFF9CFF,
        0xFF94D3,
        0x000000,
        0x131313,
        0x282828,
        0x363636,
        0x4D4D4D,
        0x656565,
        0x818181,
        0x9F9F9F,
        0xBCBCBC,
        0xE2E2E2,
        0xFFFFFF,
    )

private fun ircColor(color: IrcColor?): Color? =
    when (color) {
        is IrcColor.Numeric -> MIRC_COLORS.getOrNull(color.code)?.let { Color(0xFF000000 or it.toLong()) }
        is IrcColor.Hex -> Color(0xFF000000 or color.rgb.toLong())
        null -> null
    }

internal data class MircRun(
    val text: String,
    val style: SpanStyle,
)

internal fun mircFormattedText(
    text: String,
    container: Color = Color.Unspecified,
    inheritedColor: Color = Color.Unspecified,
): AnnotatedString {
    val parsed = parseIrcFormatting(text)
    if (parsed.runs.all { it.state.isDefault }) return AnnotatedString(parsed.visibleText)
    return buildAnnotatedString {
        append(parsed.visibleText)
        parsed.runs.forEach { run -> addStyle(run.state.toSpanStyle(container, inheritedColor), run.start, run.end) }
    }
}

/** Compatibility run projection retained for shared rich-text rendering tests. */
internal fun parseMircFormatting(text: String): List<MircRun> {
    val parsed = parseIrcFormatting(text)
    if (parsed.visibleText.isEmpty()) return emptyList()
    return parsed.runs.map { run ->
        MircRun(parsed.visibleText.substring(run.start, run.end), run.state.toSpanStyle())
    }
}

internal fun IrcFormatState.toSpanStyle(
    container: Color = Color.Unspecified,
    inheritedColor: Color = Color.Unspecified,
): SpanStyle {
    var foreground = if (reverse) background else foreground
    val effectiveBackground = if (reverse) this.foreground else background
    if (container == Color.Unspecified && foreground != null && foreground == effectiveBackground) {
        foreground = contrastColor(effectiveBackground)
    }
    val backgroundColor = ircColor(effectiveBackground) ?: container
    val foregroundColor = ircColor(foreground)
    val sourceColor = foregroundColor ?: if (effectiveBackground != null) inheritedColor else Color.Unspecified
    val color =
        if (sourceColor != Color.Unspecified && backgroundColor != Color.Unspecified) {
            ensureContrast(sourceColor, listOf(backgroundColor))
        } else {
            sourceColor
        }
    val decorations =
        buildList {
            if (underline) add(TextDecoration.Underline)
            if (strikethrough) add(TextDecoration.LineThrough)
        }
    return SpanStyle(
        color = if (foregroundColor == null && color == inheritedColor) Color.Unspecified else color,
        background = ircColor(effectiveBackground) ?: Color.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = decorations.takeIf(List<TextDecoration>::isNotEmpty)?.let(TextDecoration::combine),
        fontFamily = if (monospace) FontFamily.Monospace else null,
    )
}

private fun contrastColor(background: IrcColor): IrcColor.Hex {
    val rgb =
        when (background) {
            is IrcColor.Numeric -> MIRC_COLORS.getOrElse(background.code) { 0 }
            is IrcColor.Hex -> background.rgb
        }
    val red = rgb shr 16 and 0xFF
    val green = rgb shr 8 and 0xFF
    val blue = rgb and 0xFF
    return IrcColor.Hex(if (red * 299 + green * 587 + blue * 114 > 128_000) 0x000000 else 0xFFFFFF)
}
