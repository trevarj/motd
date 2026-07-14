package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.avatar.canonicalAvatarNick
import io.github.trevarj.motd.ui.theme.LocalNickColors
import kotlin.math.max

/** A person and a network have distinct deterministic sprite seeds. */
internal enum class GeneratedAvatarSubject { USER, NETWORK }

internal enum class AvatarDetail { MINI, STANDARD, FULL;
    companion object {
        fun forSize(size: Dp): AvatarDetail = when {
            size < 24.dp -> MINI
            size < 32.dp -> STANDARD
            else -> FULL
        }
    }
}

internal enum class GenericBadge {
    TERMINAL,
    CODE,
    CHIP,
    NETWORK,
    DATABASE,
    SECURITY,
    BOT,
    PACKAGE,
}

/**
 * Contextual nick hints select a small generic chest emblem. They never render a project logo,
 * which keeps generated people visually uniform and avoids implying an affiliation.
 */
internal enum class ProjectMark(
    val aliases: Set<String>,
    val fallback: GenericBadge,
) {
    GNU(setOf("gnu", "gnulinux"), GenericBadge.PACKAGE),
    EMACS(setOf("emacs", "emacsen", "spacemacs", "doomemacs"), GenericBadge.CODE),
    VIM(setOf("vim", "vimscript"), GenericBadge.TERMINAL),
    NEOVIM(setOf("nvim", "neovim"), GenericBadge.TERMINAL),
    LINUX(setOf("linux", "tux", "kernel"), GenericBadge.CHIP),
    GIT(setOf("git"), GenericBadge.CODE),
    GITHUB(setOf("github", "octocat", "gh"), GenericBadge.CODE),
    RUST(setOf("rust", "rustacean", "cargo"), GenericBadge.CODE),
    PYTHON(setOf("python", "cpython", "py"), GenericBadge.CODE),
    GO(setOf("golang", "gopher", "go"), GenericBadge.CODE),
    NIXOS(setOf("nixos", "nixpkgs", "nixops", "nix"), GenericBadge.PACKAGE),
    GUIX(setOf("guix", "guixsd"), GenericBadge.PACKAGE),
    TOR(setOf("torproject", "onion", "tor"), GenericBadge.NETWORK),
    DOCKER(setOf("docker", "moby"), GenericBadge.PACKAGE),
    KUBERNETES(setOf("kubernetes", "kubectl", "kube", "k8s"), GenericBadge.NETWORK),
    ANDROID(setOf("android", "droid", "aosp"), GenericBadge.BOT),
}

@Immutable
internal data class GeneratedAvatarTraits(
    val head: Int,
    val visor: Int,
    val accessory: Int,
    val expression: Int,
    val genericBadge: GenericBadge,
    val projectMark: ProjectMark?,
)

/** Deterministic traits with no Android, network, persistence, or current-theme dependency. */
internal fun generatedAvatarTraits(
    subject: GeneratedAvatarSubject,
    name: String,
    networkId: Long? = null,
): GeneratedAvatarTraits {
    val seed = avatarSeed(subject, name, networkId)
    val project = if (subject == GeneratedAvatarSubject.USER) matchedProjectMark(name) else null
    return GeneratedAvatarTraits(
        head = seededIndex(seed, 1, 6),
        visor = seededIndex(seed, 2, 6),
        accessory = seededIndex(seed, 3, 6),
        expression = seededIndex(seed, 4, 5),
        genericBadge = project?.fallback ?: GenericBadge.entries[seededIndex(seed, 5, GenericBadge.entries.size)],
        projectMark = project,
    )
}

private fun avatarSeed(subject: GeneratedAvatarSubject, name: String, networkId: Long?): Long {
    val scope = when (subject) {
        GeneratedAvatarSubject.USER -> "user"
        GeneratedAvatarSubject.NETWORK -> "network:${networkId ?: 0L}"
    }
    return fnv1a64("$scope:${canonicalAvatarNick(name)}")
}

private fun fnv1a64(value: String): Long {
    var hash = -3750763034362895579L // FNV-1a's unsigned 64-bit offset basis.
    for (byte in value.encodeToByteArray()) {
        hash = (hash xor (byte.toLong() and 0xffL)) * 1099511628211L
    }
    return hash
}

private fun seededIndex(seed: Long, salt: Int, bound: Int): Int {
    var value = seed + -7046029254386353131L * (salt + 1L)
    value = (value xor (value ushr 30)) * -4658895280553007687L
    value = (value xor (value ushr 27)) * -7723592293110705685L
    value = value xor (value ushr 31)
    return ((value ushr 1) % bound.toLong()).toInt()
}

/**
 * Conservative nick parsing: separators, camel-case, and digit transitions split tokens. Long
 * aliases may be a token prefix/suffix (`rustacean`, `dockerfan`); short aliases need an exact
 * token so names such as `mango` never accidentally claim Go.
 */
internal fun matchedProjectMark(nick: String): ProjectMark? {
    val tokens = nickTokens(nick)
    return ProjectMark.entries
        .flatMap { mark -> mark.aliases.map { alias -> ProjectMatch(mark, alias) } }
        .filter { match -> tokens.any { token -> tokenMatchesAlias(token, match.alias) } }
        .maxWithOrNull(compareBy<ProjectMatch> { it.alias.length }.thenBy { it.mark.ordinal })
        ?.mark
}

private data class ProjectMatch(val mark: ProjectMark, val alias: String)

internal fun nickTokens(nick: String): List<String> {
    val stripped = nick.trim().trimStart('~', '&', '@', '%', '+')
    if (stripped.isEmpty()) return emptyList()
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var previous: Char? = null

    fun flush() {
        if (current.isNotEmpty()) {
            tokens += current.toString().lowercase()
            current.clear()
        }
    }

    for (char in stripped) {
        if (!char.isLetterOrDigit()) {
            flush()
            previous = null
            continue
        }
        val previousChar = previous
        val boundary = previousChar != null && (
            (previousChar.isLowerCase() && char.isUpperCase()) ||
                (previousChar.isLetter() && char.isDigit()) ||
                (previousChar.isDigit() && char.isLetter())
            )
        if (boundary) flush()
        current.append(char)
        previous = char
    }
    flush()
    return tokens
}

internal fun tokenMatchesAlias(token: String, alias: String): Boolean =
    token == alias || (alias.length >= 4 && (token.startsWith(alias) || token.endsWith(alias)))

/** Use an actual Font Awesome mark when it is both available and still legible at chest scale. */
internal fun ProjectMark.fontAwesomeGlyph(): FontAwesomeGlyph? = when (this) {
    ProjectMark.RUST -> FontAwesomeGlyph.RUST
    ProjectMark.PYTHON -> FontAwesomeGlyph.PYTHON
    ProjectMark.GO -> FontAwesomeGlyph.GOLANG
    ProjectMark.GIT -> FontAwesomeGlyph.GIT_ALT
    ProjectMark.GITHUB -> FontAwesomeGlyph.GITHUB
    ProjectMark.LINUX -> FontAwesomeGlyph.LINUX
    ProjectMark.DOCKER -> FontAwesomeGlyph.DOCKER
    ProjectMark.ANDROID -> FontAwesomeGlyph.ANDROID
    else -> null
}

/** Avatar for people. The nick-color scheme is intentionally shared with sender labels. */
@Composable
internal fun IrcSpriteAvatar(
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    GeneratedAvatar(
        subject = GeneratedAvatarSubject.USER,
        name = name,
        networkId = null,
        avatarSize = size,
        primary = LocalNickColors.current.avatar(name),
        modifier = modifier,
    )
}

/** Deterministic robot avatar for a server-drawer row; [status] remains visible as an outer ring. */
@Composable
internal fun IrcNetworkBadge(
    name: String,
    networkId: Long,
    status: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    GeneratedAvatar(
        subject = GeneratedAvatarSubject.NETWORK,
        name = name,
        networkId = networkId,
        avatarSize = size,
        primary = LocalNickColors.current.avatar(name),
        modifier = modifier,
        statusRing = status,
    )
}

@Composable
private fun GeneratedAvatar(
    subject: GeneratedAvatarSubject,
    name: String,
    networkId: Long?,
    avatarSize: Dp,
    primary: Color,
    modifier: Modifier,
    statusRing: Color? = null,
) {
    val traits = remember(subject, name, networkId) { generatedAvatarTraits(subject, name, networkId) }
    val scheme = MaterialTheme.colorScheme
    val dark = isAppliedThemeDark()
    val detail = remember(avatarSize) { AvatarDetail.forSize(avatarSize) }
    val palette = remember(primary, scheme.surfaceContainerHigh, scheme.surfaceContainerLowest, dark) {
        SpritePalette.from(
            primary = primary,
            base = scheme.surfaceContainerHigh,
            panel = scheme.surfaceContainerLowest,
            dark = dark,
        )
    }
    Canvas(modifier = modifier.size(avatarSize).clip(CircleShape)) {
        val canvasSize = size
        // The modifier owns the circular clip. Scale the 24×24 scene from its top-left origin;
        // DrawTransform defaults to the canvas centre, which would otherwise push the logical
        // scene out of the circle at 32dp+ while leaving only the separately drawn ring visible.
        drawRect(palette.base)
        val sceneScale = canvasSize.minDimension / GRID
        withTransform({ scale(sceneScale, sceneScale, pivot = Offset.Zero) }) {
            drawUserSprite(traits, detail, palette)
        }
        val ring = statusRing ?: palette.primary.copy(alpha = 0.52f)
        val ringWidth = if (subject == GeneratedAvatarSubject.NETWORK) {
            max(1.5.dp.toPx(), 1.75f)
        } else {
            max(1.dp.toPx(), 1.25f)
        }
        val ringInset = max(0.75.dp.toPx(), ringWidth / 2f + 0.35.dp.toPx())
        drawCircle(
            color = ring,
            radius = canvasSize.minDimension / 2f - ringInset,
            style = Stroke(width = ringWidth),
        )
    }
}

@Immutable
private data class SpritePalette(
    val base: Color,
    val shade: Color,
    val primary: Color,
    val highlight: Color,
    val ink: Color,
    val panel: Color,
) {
    companion object {
        fun from(primary: Color, base: Color, panel: Color, dark: Boolean): SpritePalette {
            // The actual Material palette supplies the neutral layers, so terminal themes and
            // AMOLED remain coherent. The nick hue is the one deliberate color identity.
            val shade = primary.copy(alpha = if (dark) 0.36f else 0.24f).compositeOver(base)
            val highlight = primary.copy(alpha = if (dark) 0.80f else 0.72f).compositeOver(base)
            return SpritePalette(
                base = base,
                shade = shade,
                primary = primary,
                highlight = highlight,
                ink = onColorFor(shade),
                panel = panel,
            )
        }
    }
}

private const val GRID = 24f

private fun DrawScope.drawUserSprite(
    traits: GeneratedAvatarTraits,
    detail: AvatarDetail,
    colors: SpritePalette,
) {
    drawBody(traits.head, colors)
    drawHead(traits.head, colors)
    drawVisor(traits.visor, colors)
    if (detail != AvatarDetail.MINI) {
        drawAccessory(traits.accessory, colors)
        drawExpression(traits.expression, colors)
        drawChestEmblem(traits, colors)
    }
}

private fun DrawScope.drawBody(head: Int, colors: SpritePalette) {
    val top = if (head % 2 == 0) 15f else 14f
    drawRoundRect(colors.shade, p(5f, top), s(14f, 10f), CornerRadius(4f, 4f))
    drawRoundRect(colors.primary.copy(alpha = 0.34f), p(7f, top + 2f), s(10f, 8f), CornerRadius(3f, 3f))
    drawLine(colors.ink.copy(alpha = 0.20f), p(12f, top + 3f), p(12f, 22f), 0.65f)
}

private fun DrawScope.drawHead(variant: Int, colors: SpritePalette) {
    when (variant) {
        0 -> drawRoundRect(colors.highlight, p(5f, 5f), s(14f, 13f), CornerRadius(5f, 5f))
        1 -> drawRoundRect(colors.highlight, p(4f, 6f), s(16f, 12f), CornerRadius(3f, 3f))
        2 -> drawCircle(colors.highlight, 7f, p(12f, 11f))
        3 -> {
            drawRoundRect(colors.highlight, p(5f, 4f), s(14f, 14f), CornerRadius(2f, 2f))
            drawRoundRect(colors.shade, p(4f, 7f), s(16f, 7f), CornerRadius(2f, 2f))
        }
        4 -> {
            drawRoundRect(colors.highlight, p(6f, 5f), s(12f, 13f), CornerRadius(4f, 4f))
            drawCircle(colors.highlight, 3f, p(6f, 12f))
            drawCircle(colors.highlight, 3f, p(18f, 12f))
        }
        else -> {
            drawRoundRect(colors.highlight, p(5f, 6f), s(14f, 12f), CornerRadius(6f, 6f))
            drawRoundRect(colors.shade, p(7f, 4f), s(10f, 3f), CornerRadius(2f, 2f))
        }
    }
}

private fun DrawScope.drawVisor(variant: Int, colors: SpritePalette) {
    val visor = colors.panel.copy(alpha = 0.93f)
    when (variant) {
        0 -> drawRoundRect(visor, p(7f, 9f), s(10f, 3.5f), CornerRadius(1.5f, 1.5f))
        1 -> {
            drawRoundRect(visor, p(6f, 9f), s(5f, 3.5f), CornerRadius(1.5f, 1.5f))
            drawRoundRect(visor, p(13f, 9f), s(5f, 3.5f), CornerRadius(1.5f, 1.5f))
        }
        2 -> {
            drawCircle(visor, 2.2f, p(8.5f, 10.5f))
            drawCircle(visor, 2.2f, p(15.5f, 10.5f))
        }
        3 -> drawRoundRect(visor, p(6f, 8.5f), s(12f, 4.5f), CornerRadius(0.8f, 0.8f))
        4 -> {
            drawRoundRect(visor, p(7f, 9f), s(4f, 3.5f), CornerRadius(1.5f, 1.5f))
            drawRoundRect(visor, p(13f, 9f), s(4f, 3.5f), CornerRadius(1.5f, 1.5f))
            drawLine(visor, p(11f, 10.75f), p(13f, 10.75f), 0.8f)
        }
        else -> drawRoundRect(visor, p(7f, 9.5f), s(10f, 2.4f), CornerRadius(1.2f, 1.2f))
    }
    drawLine(colors.primary.copy(alpha = 0.86f), p(8f, 10.7f), p(16f, 10.7f), 0.55f, StrokeCap.Round)
}

private fun DrawScope.drawAccessory(variant: Int, colors: SpritePalette) {
    when (variant) {
        0 -> { // antenna
            drawLine(colors.ink.copy(alpha = 0.75f), p(12f, 5f), p(12f, 2.1f), 0.8f, StrokeCap.Round)
            drawCircle(colors.primary, 1.1f, p(12f, 1.9f))
        }
        1 -> { // headphones
            drawStrokeArc(colors.ink.copy(alpha = 0.70f), 210f, 120f, Rect(4f, 4f, 20f, 19f), 1.1f)
            drawRoundRect(colors.ink.copy(alpha = 0.72f), p(3.5f, 10f), s(2f, 5f), CornerRadius(0.8f, 0.8f))
            drawRoundRect(colors.ink.copy(alpha = 0.72f), p(18.5f, 10f), s(2f, 5f), CornerRadius(0.8f, 0.8f))
        }
        2 -> drawRoundRect(colors.ink.copy(alpha = 0.68f), p(5.5f, 4f), s(13f, 2.4f), CornerRadius(1.2f, 1.2f)) // cap
        3 -> { // hood
            drawStrokeArc(colors.ink.copy(alpha = 0.55f), 190f, 160f, Rect(3.8f, 3.8f, 20.2f, 20.2f), 1.4f)
        }
        4 -> { // side mic
            drawLine(colors.ink.copy(alpha = 0.74f), p(18f, 13f), p(21f, 15f), 0.8f, StrokeCap.Round)
            drawCircle(colors.primary, 0.9f, p(21f, 15f))
        }
        else -> { // circuit temples
            drawLine(colors.ink.copy(alpha = 0.62f), p(5.2f, 9f), p(3.2f, 7.5f), 0.7f)
            drawLine(colors.ink.copy(alpha = 0.62f), p(18.8f, 9f), p(20.8f, 7.5f), 0.7f)
            drawCircle(colors.primary, 0.72f, p(3f, 7.3f))
            drawCircle(colors.primary, 0.72f, p(21f, 7.3f))
        }
    }
}

private fun DrawScope.drawExpression(variant: Int, colors: SpritePalette) {
    val color = colors.ink.copy(alpha = 0.72f)
    when (variant) {
        0 -> drawLine(color, p(10f, 14.5f), p(14f, 14.5f), 0.7f, StrokeCap.Round)
        1 -> drawStrokeArc(color, 15f, 150f, Rect(9f, 13f, 15f, 17f), 0.7f)
        2 -> {
            drawCircle(color, 0.6f, p(10.5f, 14.5f))
            drawCircle(color, 0.6f, p(13.5f, 14.5f))
        }
        3 -> drawRoundRect(color, p(10f, 13.8f), s(4f, 1.4f), CornerRadius(0.7f, 0.7f))
        else -> {
            drawLine(color, p(9.8f, 15f), p(12f, 13.8f), 0.65f, StrokeCap.Round)
            drawLine(color, p(12f, 13.8f), p(14.2f, 15f), 0.65f, StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawChestEmblem(traits: GeneratedAvatarTraits, colors: SpritePalette) {
    val glyph = traits.projectMark?.fontAwesomeGlyph() ?: FontAwesomeGlyph.forBadge(traits.genericBadge)
    val maxSize = 5.35f
    val scale = maxSize / max(glyph.viewBoxWidth, glyph.viewBoxHeight)
    val width = glyph.viewBoxWidth * scale
    val height = glyph.viewBoxHeight * scale
    // Render the glyph directly on the torso: no panel, outline, or app-icon-shaped badge.
    withTransform({
        translate(12f - width / 2f, 19.1f - height / 2f)
        scale(scale, scale)
    }) {
        drawPath(glyph.path, colors.ink.copy(alpha = 0.88f))
    }
}

private fun DrawScope.drawStrokeArc(
    color: Color,
    startAngle: Float,
    sweepAngle: Float,
    bounds: Rect,
    strokeWidth: Float,
) {
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = bounds.topLeft,
        size = bounds.size,
        style = Stroke(strokeWidth),
    )
}

private fun p(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)
private fun s(width: Float, height: Float) = androidx.compose.ui.geometry.Size(width, height)

/**
 * Font Awesome Free 6.7.2 solid SVG path data, rendered directly as tiny chest emblems rather
 * than as a font or an app-icon-shaped badge. Attribution and the source mapping live in
 * docs/assets/avatar-icons/README.md and THIRD_PARTY_NOTICES.md.
 */
internal enum class FontAwesomeGlyph(
    val viewBoxWidth: Float,
    val viewBoxHeight: Float,
    private val vectorData: String,
) {
    TERMINAL(
        576f,
        512f,
        "M9.4 86.6C-3.1 74.1-3.1 53.9 9.4 41.4s32.8-12.5 45.3 0l192 192c12.5 12.5 12.5 32.8 0 45.3l-192 192c-12.5 12.5-32.8 12.5-45.3 0s-12.5-32.8 0-45.3L178.7 256 9.4 86.6zM256 416l288 0c17.7 0 32 14.3 32 32s-14.3 32-32 32l-288 0c-17.7 0-32-14.3-32-32s14.3-32 32-32z",
    ),
    CODE(
        640f,
        512f,
        "M392.8 1.2c-17-4.9-34.7 5-39.6 22l-128 448c-4.9 17 5 34.7 22 39.6s34.7-5 39.6-22l128-448c4.9-17-5-34.7-22-39.6zm80.6 120.1c-12.5 12.5-12.5 32.8 0 45.3L562.7 256l-89.4 89.4c-12.5 12.5-12.5 32.8 0 45.3s32.8 12.5 45.3 0l112-112c12.5-12.5 12.5-32.8 0-45.3l-112-112c-12.5-12.5-32.8-12.5-45.3 0zm-306.7 0c-12.5-12.5-32.8-12.5-45.3 0l-112 112c-12.5 12.5-12.5 32.8 0 45.3l112 112c12.5 12.5 32.8 12.5 45.3 0s12.5-32.8 0-45.3L77.3 256l89.4-89.4c12.5-12.5 12.5-32.8 0-45.3z",
    ),
    CHIP(
        512f,
        512f,
        "M176 24c0-13.3-10.7-24-24-24s-24 10.7-24 24l0 40c-35.3 0-64 28.7-64 64l-40 0c-13.3 0-24 10.7-24 24s10.7 24 24 24l40 0 0 56-40 0c-13.3 0-24 10.7-24 24s10.7 24 24 24l40 0 0 56-40 0c-13.3 0-24 10.7-24 24s10.7 24 24 24l40 0c0 35.3 28.7 64 64 64l0 40c0 13.3 10.7 24 24 24s24-10.7 24-24l0-40 56 0 0 40c0 13.3 10.7 24 24 24s24-10.7 24-24l0-40 56 0 0 40c0 13.3 10.7 24 24 24s24-10.7 24-24l0-40c35.3 0 64-28.7 64-64l40 0c13.3 0 24-10.7 24-24s-10.7-24-24-24l-40 0 0-56 40 0c13.3 0 24-10.7 24-24s-10.7-24-24-24l-40 0 0-56 40 0c13.3 0 24-10.7 24-24s-10.7-24-24-24l-40 0c0-35.3-28.7-64-64-64l0-40c0-13.3-10.7-24-24-24s-24 10.7-24 24l0 40-56 0 0-40c0-13.3-10.7-24-24-24s-24 10.7-24 24l0 40-56 0 0-40zM160 128l192 0c17.7 0 32 14.3 32 32l0 192c0 17.7-14.3 32-32 32l-192 0c-17.7 0-32-14.3-32-32l0-192c0-17.7 14.3-32 32-32zm192 32l-192 0 0 192 192 0 0-192z",
    ),
    NETWORK(
        640f,
        512f,
        "M256 64l128 0 0 64-128 0 0-64zM240 0c-26.5 0-48 21.5-48 48l0 96c0 26.5 21.5 48 48 48l48 0 0 32L32 224c-17.7 0-32 14.3-32 32s14.3 32 32 32l96 0 0 32-48 0c-26.5 0-48 21.5-48 48l0 96c0 26.5 21.5 48 48 48l160 0c26.5 0 48-21.5 48-48l0-96c0-26.5-21.5-48-48-48l-48 0 0-32 256 0 0 32-48 0c-26.5 0-48 21.5-48 48l0 96c0 26.5 21.5 48 48 48l160 0c26.5 0 48-21.5 48-48l0-96c0-26.5-21.5-48-48-48l-48 0 0-32 96 0c17.7 0 32-14.3 32-32s-14.3-32-32-32l-256 0 0-32 48 0c26.5 0 48-21.5 48-48l0-96c0-26.5-21.5-48-48-48L240 0zM96 448l0-64 128 0 0 64L96 448zm320-64l128 0 0 64-128 0 0-64z",
    ),
    DATABASE(
        448f,
        512f,
        "M448 80l0 48c0 44.2-100.3 80-224 80S0 172.2 0 128L0 80C0 35.8 100.3 0 224 0S448 35.8 448 80zM393.2 214.7c20.8-7.4 39.9-16.9 54.8-28.6L448 288c0 44.2-100.3 80-224 80S0 332.2 0 288L0 186.1c14.9 11.8 34 21.2 54.8 28.6C99.7 230.7 159.5 240 224 240s124.3-9.3 169.2-25.3zM0 346.1c14.9 11.8 34 21.2 54.8 28.6C99.7 390.7 159.5 400 224 400s124.3-9.3 169.2-25.3c20.8-7.4 39.9-16.9 54.8-28.6l0 85.9c0 44.2-100.3 80-224 80S0 476.2 0 432l0-85.9z",
    ),
    SECURITY(
        512f,
        512f,
        "M256 0c4.6 0 9.2 1 13.4 2.9L457.7 82.8c22 9.3 38.4 31 38.3 57.2c-.5 99.2-41.3 280.7-213.6 363.2c-16.7 8-36.1 8-52.8 0C57.3 420.7 16.5 239.2 16 140c-.1-26.2 16.3-47.9 38.3-57.2L242.7 2.9C246.8 1 251.4 0 256 0zm0 66.8l0 378.1C394 378 431.1 230.1 432 141.4L256 66.8s0 0 0 0z",
    ),
    BOT(
        640f,
        512f,
        "M320 0c17.7 0 32 14.3 32 32l0 64 120 0c39.8 0 72 32.2 72 72l0 272c0 39.8-32.2 72-72 72l-304 0c-39.8 0-72-32.2-72-72l0-272c0-39.8 32.2-72 72-72l120 0 0-64c0-17.7 14.3-32 32-32zM208 384c-8.8 0-16 7.2-16 16s7.2 16 16 16l32 0c8.8 0 16-7.2 16-16s-7.2-16-16-16l-32 0zm96 0c-8.8 0-16 7.2-16 16s7.2 16 16 16l32 0c8.8 0 16-7.2 16-16s-7.2-16-16-16l-32 0zm96 0c-8.8 0-16 7.2-16 16s7.2 16 16 16l32 0c8.8 0 16-7.2 16-16s-7.2-16-16-16l-32 0zM264 256a40 40 0 1 0 -80 0 40 40 0 1 0 80 0zm152 40a40 40 0 1 0 0-80 40 40 0 1 0 0 80zM48 224l16 0 0 192-16 0c-26.5 0-48-21.5-48-48l0-96c0-26.5 21.5-48 48-48zm544 0c26.5 0 48 21.5 48 48l0 96c0 26.5-21.5 48-48 48l-16 0 0-192 16 0z",
    ),
    PACKAGE(
        640f,
        512f,
        "M58.9 42.1c3-6.1 9.6-9.6 16.3-8.7L320 64 564.8 33.4c6.7-.8 13.3 2.7 16.3 8.7l41.7 83.4c9 17.9-.6 39.6-19.8 45.1L439.6 217.3c-13.9 4-28.8-1.9-36.2-14.3L320 64 236.6 203c-7.4 12.4-22.3 18.3-36.2 14.3L37.1 170.6c-19.3-5.5-28.8-27.2-19.8-45.1L58.9 42.1zM321.1 128l54.9 91.4c14.9 24.8 44.6 36.6 72.5 28.6L576 211.6l0 167c0 22-15 41.2-36.4 46.6l-204.1 51c-10.2 2.6-20.9 2.6-31 0l-204.1-51C79 419.7 64 400.5 64 378.5l0-167L191.6 248c27.8 8 57.6-3.8 72.5-28.6L318.9 128l2.2 0z",
    ),
    RUST(
        512f,
        512f,
        "M508.52,249.75,486.7,236.24c-.17-2-.34-3.93-.55-5.88l18.72-17.5a7.35,7.35,0,0,0-2.44-12.25l-24-9c-.54-1.88-1.08-3.78-1.67-5.64l15-20.83a7.35,7.35,0,0,0-4.79-11.54l-25.42-4.15c-.9-1.73-1.79-3.45-2.73-5.15l10.68-23.42a7.35,7.35,0,0,0-6.95-10.39l-25.82.91q-1.79-2.22-3.61-4.4L439,81.84A7.36,7.36,0,0,0,430.16,73L405,78.93q-2.17-1.83-4.4-3.61l.91-25.82a7.35,7.35,0,0,0-10.39-7L367.7,53.23c-1.7-.94-3.43-1.84-5.15-2.73L358.4,25.08a7.35,7.35,0,0,0-11.54-4.79L326,35.26c-1.86-.59-3.75-1.13-5.64-1.67l-9-24a7.35,7.35,0,0,0-12.25-2.44l-17.5,18.72c-1.95-.21-3.91-.38-5.88-.55L262.25,3.48a7.35,7.35,0,0,0-12.5,0L236.24,25.3c-2,.17-3.93.34-5.88.55L212.86,7.13a7.35,7.35,0,0,0-12.25,2.44l-9,24c-1.89.55-3.79,1.08-5.66,1.68l-20.82-15a7.35,7.35,0,0,0-11.54,4.79l-4.15,25.41c-1.73.9-3.45,1.79-5.16,2.73L120.88,42.55a7.35,7.35,0,0,0-10.39,7l.92,25.81c-1.49,1.19-3,2.39-4.42,3.61L81.84,73A7.36,7.36,0,0,0,73,81.84L78.93,107c-1.23,1.45-2.43,2.93-3.62,4.41l-25.81-.91a7.42,7.42,0,0,0-6.37,3.26,7.35,7.35,0,0,0-.57,7.13l10.66,23.41c-.94,1.7-1.83,3.43-2.73,5.16L25.08,153.6a7.35,7.35,0,0,0-4.79,11.54l15,20.82c-.59,1.87-1.13,3.77-1.68,5.66l-24,9a7.35,7.35,0,0,0-2.44,12.25l18.72,17.5c-.21,1.95-.38,3.91-.55,5.88L3.48,249.75a7.35,7.35,0,0,0,0,12.5L25.3,275.76c.17,2,.34,3.92.55,5.87L7.13,299.13a7.35,7.35,0,0,0,2.44,12.25l24,9c.55,1.89,1.08,3.78,1.68,5.65l-15,20.83a7.35,7.35,0,0,0,4.79,11.54l25.42,4.15c.9,1.72,1.79,3.45,2.73,5.14L42.56,391.12a7.35,7.35,0,0,0,.57,7.13,7.13,7.13,0,0,0,6.37,3.26l25.83-.91q1.77,2.22,3.6,4.4L73,430.16A7.36,7.36,0,0,0,81.84,439L107,433.07q2.18,1.83,4.41,3.61l-.92,25.82a7.35,7.35,0,0,0,10.39,6.95l23.43-10.68c1.69.94,3.42,1.83,5.14,2.73l4.15,25.42a7.34,7.34,0,0,0,11.54,4.78l20.83-15c1.86.6,3.76,1.13,5.65,1.68l9,24a7.36,7.36,0,0,0,12.25,2.44l17.5-18.72c1.95.21,3.92.38,5.88.55l13.51,21.82a7.35,7.35,0,0,0,12.5,0l13.51-21.82c2-.17,3.93-.34,5.88-.56l17.5,18.73a7.36,7.36,0,0,0,12.25-2.44l9-24c1.89-.55,3.78-1.08,5.65-1.68l20.82,15a7.34,7.34,0,0,0,11.54-4.78l4.15-25.42c1.72-.9,3.45-1.79,5.15-2.73l23.42,10.68a7.35,7.35,0,0,0,10.39-6.95l-.91-25.82q2.22-1.79,4.4-3.61L430.16,439a7.36,7.36,0,0,0,8.84-8.84L433.07,405q1.83-2.17,3.61-4.4l25.82.91a7.23,7.23,0,0,0,6.37-3.26,7.35,7.35,0,0,0,.58-7.13L458.77,367.7c.94-1.7,1.83-3.43,2.73-5.15l25.42-4.15a7.35,7.35,0,0,0,4.79-11.54l-15-20.83c.59-1.87,1.13-3.76,1.67-5.65l24-9a7.35,7.35,0,0,0,2.44-12.25l-18.72-17.5c.21-1.95.38-3.91.55-5.87l21.82-13.51a7.35,7.35,0,0,0,0-12.5Zm-151,129.08A13.91,13.91,0,0,0,341,389.51l-7.64,35.67A187.51,187.51,0,0,1,177,424.44l-7.64-35.66a13.87,13.87,0,0,0-16.46-10.68l-31.51,6.76a187.38,187.38,0,0,1-16.26-19.21H258.3c1.72,0,2.89-.29,2.89-1.91V309.55c0-1.57-1.17-1.91-2.89-1.91H213.47l.05-34.35H262c4.41,0,23.66,1.28,29.79,25.87,1.91,7.55,6.17,32.14,9.06,40,2.89,8.82,14.6,26.46,27.1,26.46H407a187.3,187.3,0,0,1-17.34,20.09Zm25.77,34.49A15.24,15.24,0,1,1,368,398.08h.44A15.23,15.23,0,0,1,383.24,413.32Zm-225.62-.68a15.24,15.24,0,1,1-15.25-15.25h.45A15.25,15.25,0,0,1,157.62,412.64ZM69.57,234.15l32.83-14.6a13.88,13.88,0,0,0,7.06-18.33L102.69,186h26.56V305.73H75.65A187.65,187.65,0,0,1,69.57,234.15ZM58.31,198.09a15.24,15.24,0,0,1,15.23-15.25H74a15.24,15.24,0,1,1-15.67,15.24Zm155.16,24.49.05-35.32h63.26c3.28,0,23.07,3.77,23.07,18.62,0,12.29-15.19,16.7-27.68,16.7ZM399,306.71c-9.8,1.13-20.63-4.12-22-10.09-5.78-32.49-15.39-39.4-30.57-51.4,18.86-11.95,38.46-29.64,38.46-53.26,0-25.52-17.49-41.59-29.4-49.48-16.76-11-35.28-13.23-40.27-13.23H116.32A187.49,187.49,0,0,1,221.21,70.06l23.47,24.6a13.82,13.82,0,0,0,19.6.44l26.26-25a187.51,187.51,0,0,1,128.37,91.43l-18,40.57A14,14,0,0,0,408,220.43l34.59,15.33a187.12,187.12,0,0,1,.4,32.54H423.71c-1.91,0-2.69,1.27-2.69,3.13v8.82C421,301,409.31,305.58,399,306.71ZM240,60.21A15.24,15.24,0,0,1,255.21,45h.45A15.24,15.24,0,1,1,240,60.21ZM436.84,214a15.24,15.24,0,1,1,0-30.48h.44a15.24,15.24,0,0,1-.44,30.48Z",
    ),
    PYTHON(
        448f,
        512f,
        "M439.8 200.5c-7.7-30.9-22.3-54.2-53.4-54.2h-40.1v47.4c0 36.8-31.2 67.8-66.8 67.8H172.7c-29.2 0-53.4 25-53.4 54.3v101.8c0 29 25.2 46 53.4 54.3 33.8 9.9 66.3 11.7 106.8 0 26.9-7.8 53.4-23.5 53.4-54.3v-40.7H226.2v-13.6h160.2c31.1 0 42.6-21.7 53.4-54.2 11.2-33.5 10.7-65.7 0-108.6zM286.2 404c11.1 0 20.1 9.1 20.1 20.3 0 11.3-9 20.4-20.1 20.4-11 0-20.1-9.2-20.1-20.4.1-11.3 9.1-20.3 20.1-20.3zM167.8 248.1h106.8c29.7 0 53.4-24.5 53.4-54.3V91.9c0-29-24.4-50.7-53.4-55.6-35.8-5.9-74.7-5.6-106.8.1-45.2 8-53.4 24.7-53.4 55.6v40.7h106.9v13.6h-147c-31.1 0-58.3 18.7-66.8 54.2-9.8 40.7-10.2 66.1 0 108.6 7.6 31.6 25.7 54.2 56.8 54.2H101v-48.8c0-35.3 30.5-66.4 66.8-66.4zm-6.7-142.6c-11.1 0-20.1-9.1-20.1-20.3.1-11.3 9-20.4 20.1-20.4 11 0 20.1 9.2 20.1 20.4s-9 20.3-20.1 20.3z",
    ),
    GOLANG(
        640f,
        512f,
        "M400.1 194.8C389.2 197.6 380.2 199.1 371 202.4C363.7 204.3 356.3 206.3 347.8 208.5L347.2 208.6C343 209.8 342.6 209.9 338.7 205.4C334 200.1 330.6 196.7 324.1 193.5C304.4 183.9 285.4 186.7 267.7 198.2C246.5 211.9 235.6 232.2 235.9 257.4C236.2 282.4 253.3 302.9 277.1 306.3C299.1 309.1 316.9 301.7 330.9 285.8C333 283.2 334.9 280.5 337 277.5V277.5L337 277.5C337.8 276.5 338.5 275.4 339.3 274.2H279.2C272.7 274.2 271.1 270.2 273.3 264.9C277.3 255.2 284.8 239 289.2 230.9C290.1 229.1 292.3 225.1 296.1 225.1H397.2C401.7 211.7 409 198.2 418.8 185.4C441.5 155.5 468.1 139.9 506 133.4C537.8 127.8 567.7 130.9 594.9 149.3C619.5 166.1 634.7 188.9 638.8 218.8C644.1 260.9 631.9 295.1 602.1 324.4C582.4 345.3 557.2 358.4 528.2 364.3C522.6 365.3 517.1 365.8 511.7 366.3C508.8 366.5 506 366.8 503.2 367.1C474.9 366.5 449 358.4 427.2 339.7C411.9 326.4 401.3 310.1 396.1 291.2C392.4 298.5 388.1 305.6 382.1 312.3C360.5 341.9 331.2 360.3 294.2 365.2C263.6 369.3 235.3 363.4 210.3 344.7C187.3 327.2 174.2 304.2 170.8 275.5C166.7 241.5 176.7 210.1 197.2 184.2C219.4 155.2 248.7 136.8 284.5 130.3C313.8 124.1 341.8 128.4 367.1 145.6C383.6 156.5 395.4 171.4 403.2 189.5C405.1 192.3 403.8 193.9 400.1 194.8zM48.3 200.4C47.05 200.4 46.74 199.8 47.36 198.8L53.91 190.4C54.53 189.5 56.09 188.9 57.34 188.9H168.6C169.8 188.9 170.1 189.8 169.5 190.7L164.2 198.8C163.6 199.8 162 200.7 161.1 200.7L48.3 200.4zM1.246 229.1C0 229.1-.3116 228.4 .3116 227.5L6.855 219.1C7.479 218.2 9.037 217.5 10.28 217.5H152.4C153.6 217.5 154.2 218.5 153.9 219.4L151.4 226.9C151.1 228.1 149.9 228.8 148.6 228.8L1.246 229.1zM75.72 255.9C75.1 256.8 75.41 257.7 76.65 257.7L144.6 258C145.5 258 146.8 257.1 146.8 255.9L147.4 248.4C147.4 247.1 146.8 246.2 145.5 246.2H83.2C81.95 246.2 80.71 247.1 80.08 248.1L75.72 255.9zM577.2 237.9C577 235.3 576.9 233.1 576.5 230.9C570.9 200.1 542.5 182.6 512.9 189.5C483.9 196 465.2 214.4 458.4 243.7C452.8 268 464.6 292.6 487 302.6C504.2 310.1 521.3 309.2 537.8 300.7C562.4 287.1 575.8 268 577.4 241.2C577.3 240 577.3 238.9 577.2 237.9z",
    ),
    GIT_ALT(
        448f,
        512f,
        "M439.55 236.05L244 40.45a28.87 28.87 0 0 0-40.81 0l-40.66 40.63 51.52 51.52c27.06-9.14 52.68 16.77 43.39 43.68l49.66 49.66c34.23-11.8 61.18 31 35.47 56.69-26.49 26.49-70.21-2.87-56-37.34L240.22 199v121.85c25.3 12.54 22.26 41.85 9.08 55a34.34 34.34 0 0 1-48.55 0c-17.57-17.6-11.07-46.91 11.25-56v-123c-20.8-8.51-24.6-30.74-18.64-45L142.57 101 8.45 235.14a28.86 28.86 0 0 0 0 40.81l195.61 195.6a28.86 28.86 0 0 0 40.8 0l194.69-194.69a28.86 28.86 0 0 0 0-40.81z",
    ),
    GITHUB(
        496f,
        512f,
        "M165.9 397.4c0 2-2.3 3.6-5.2 3.6-3.3.3-5.6-1.3-5.6-3.6 0-2 2.3-3.6 5.2-3.6 3-.3 5.6 1.3 5.6 3.6zm-31.1-4.5c-.7 2 1.3 4.3 4.3 4.9 2.6 1 5.6 0 6.2-2s-1.3-4.3-4.3-5.2c-2.6-.7-5.5.3-6.2 2.3zm44.2-1.7c-2.9.7-4.9 2.6-4.6 4.9.3 2 2.9 3.3 5.9 2.6 2.9-.7 4.9-2.6 4.6-4.6-.3-1.9-3-3.2-5.9-2.9zM244.8 8C106.1 8 0 113.3 0 252c0 110.9 69.8 205.8 169.5 239.2 12.8 2.3 17.3-5.6 17.3-12.1 0-6.2-.3-40.4-.3-61.4 0 0-70 15-84.7-29.8 0 0-11.4-29.1-27.8-36.6 0 0-22.9-15.7 1.6-15.4 0 0 24.9 2 38.6 25.8 21.9 38.6 58.6 27.5 72.9 20.9 2.3-16 8.8-27.1 16-33.7-55.9-6.2-112.3-14.3-112.3-110.5 0-27.5 7.6-41.3 23.6-58.9-2.6-6.5-11.1-33.3 2.6-67.9 20.9-6.5 69 27 69 27 20-5.6 41.5-8.5 62.8-8.5s42.8 2.9 62.8 8.5c0 0 48.1-33.6 69-27 13.7 34.7 5.2 61.4 2.6 67.9 16 17.7 25.8 31.5 25.8 58.9 0 96.5-58.9 104.2-114.8 110.5 9.2 7.9 17 22.9 17 46.4 0 33.7-.3 75.4-.3 83.6 0 6.5 4.6 14.4 17.3 12.1C428.2 457.8 496 362.9 496 252 496 113.3 383.5 8 244.8 8zM97.2 352.9c-1.3 1-1 3.3.7 5.2 1.6 1.6 3.9 2.3 5.2 1 1.3-1 1-3.3-.7-5.2-1.6-1.6-3.9-2.3-5.2-1zm-10.8-8.1c-.7 1.3.3 2.9 2.3 3.9 1.6 1 3.6.7 4.3-.7.7-1.3-.3-2.9-2.3-3.9-2-.6-3.6-.3-4.3.7zm32.4 35.6c-1.6 1.3-1 4.3 1.3 6.2 2.3 2.3 5.2 2.6 6.5 1 1.3-1.3.7-4.3-1.3-6.2-2.2-2.3-5.2-2.6-6.5-1zm-11.4-14.7c-1.6 1-1.6 3.6 0 5.9 1.6 2.3 4.3 3.3 5.6 2.3 1.6-1.3 1.6-3.9 0-6.2-1.4-2.3-4-3.3-5.6-2z",
    ),
    LINUX(
        448f,
        512f,
        "M220.8 123.3c1 .5 1.8 1.7 3 1.7 1.1 0 2.8-.4 2.9-1.5.2-1.4-1.9-2.3-3.2-2.9-1.7-.7-3.9-1-5.5-.1-.4.2-.8.7-.6 1.1.3 1.3 2.3 1.1 3.4 1.7zm-21.9 1.7c1.2 0 2-1.2 3-1.7 1.1-.6 3.1-.4 3.5-1.6.2-.4-.2-.9-.6-1.1-1.6-.9-3.8-.6-5.5.1-1.3.6-3.4 1.5-3.2 2.9.1 1 1.8 1.5 2.8 1.4zM420 403.8c-3.6-4-5.3-11.6-7.2-19.7-1.8-8.1-3.9-16.8-10.5-22.4-1.3-1.1-2.6-2.1-4-2.9-1.3-.8-2.7-1.5-4.1-2 9.2-27.3 5.6-54.5-3.7-79.1-11.4-30.1-31.3-56.4-46.5-74.4-17.1-21.5-33.7-41.9-33.4-72C311.1 85.4 315.7.1 234.8 0 132.4-.2 158 103.4 156.9 135.2c-1.7 23.4-6.4 41.8-22.5 64.7-18.9 22.5-45.5 58.8-58.1 96.7-6 17.9-8.8 36.1-6.2 53.3-6.5 5.8-11.4 14.7-16.6 20.2-4.2 4.3-10.3 5.9-17 8.3s-14 6-18.5 14.5c-2.1 3.9-2.8 8.1-2.8 12.4 0 3.9.6 7.9 1.2 11.8 1.2 8.1 2.5 15.7.8 20.8-5.2 14.4-5.9 24.4-2.2 31.7 3.8 7.3 11.4 10.5 20.1 12.3 17.3 3.6 40.8 2.7 59.3 12.5 19.8 10.4 39.9 14.1 55.9 10.4 11.6-2.6 21.1-9.6 25.9-20.2 12.5-.1 26.3-5.4 48.3-6.6 14.9-1.2 33.6 5.3 55.1 4.1.6 2.3 1.4 4.6 2.5 6.7v.1c8.3 16.7 23.8 24.3 40.3 23 16.6-1.3 34.1-11 48.3-27.9 13.6-16.4 36-23.2 50.9-32.2 7.4-4.5 13.4-10.1 13.9-18.3.4-8.2-4.4-17.3-15.5-29.7zM223.7 87.3c9.8-22.2 34.2-21.8 44-.4 6.5 14.2 3.6 30.9-4.3 40.4-1.6-.8-5.9-2.6-12.6-4.9 1.1-1.2 3.1-2.7 3.9-4.6 4.8-11.8-.2-27-9.1-27.3-7.3-.5-13.9 10.8-11.8 23-4.1-2-9.4-3.5-13-4.4-1-6.9-.3-14.6 2.9-21.8zM183 75.8c10.1 0 20.8 14.2 19.1 33.5-3.5 1-7.1 2.5-10.2 4.6 1.2-8.9-3.3-20.1-9.6-19.6-8.4.7-9.8 21.2-1.8 28.1 1 .8 1.9-.2-5.9 5.5-15.6-14.6-10.5-52.1 8.4-52.1zm-13.6 60.7c6.2-4.6 13.6-10 14.1-10.5 4.7-4.4 13.5-14.2 27.9-14.2 7.1 0 15.6 2.3 25.9 8.9 6.3 4.1 11.3 4.4 22.6 9.3 8.4 3.5 13.7 9.7 10.5 18.2-2.6 7.1-11 14.4-22.7 18.1-11.1 3.6-19.8 16-38.2 14.9-3.9-.2-7-1-9.6-2.1-8-3.5-12.2-10.4-20-15-8.6-4.8-13.2-10.4-14.7-15.3-1.4-4.9 0-9 4.2-12.3zm3.3 334c-2.7 35.1-43.9 34.4-75.3 18-29.9-15.8-68.6-6.5-76.5-21.9-2.4-4.7-2.4-12.7 2.6-26.4v-.2c2.4-7.6.6-16-.6-23.9-1.2-7.8-1.8-15 .9-20 3.5-6.7 8.5-9.1 14.8-11.3 10.3-3.7 11.8-3.4 19.6-9.9 5.5-5.7 9.5-12.9 14.3-18 5.1-5.5 10-8.1 17.7-6.9 8.1 1.2 15.1 6.8 21.9 16l19.6 35.6c9.5 19.9 43.1 48.4 41 68.9zm-1.4-25.9c-4.1-6.6-9.6-13.6-14.4-19.6 7.1 0 14.2-2.2 16.7-8.9 2.3-6.2 0-14.9-7.4-24.9-13.5-18.2-38.3-32.5-38.3-32.5-13.5-8.4-21.1-18.7-24.6-29.9s-3-23.3-.3-35.2c5.2-22.9 18.6-45.2 27.2-59.2 2.3-1.7.8 3.2-8.7 20.8-8.5 16.1-24.4 53.3-2.6 82.4.6-20.7 5.5-41.8 13.8-61.5 12-27.4 37.3-74.9 39.3-112.7 1.1.8 4.6 3.2 6.2 4.1 4.6 2.7 8.1 6.7 12.6 10.3 12.4 10 28.5 9.2 42.4 1.2 6.2-3.5 11.2-7.5 15.9-9 9.9-3.1 17.8-8.6 22.3-15 7.7 30.4 25.7 74.3 37.2 95.7 6.1 11.4 18.3 35.5 23.6 64.6 3.3-.1 7 .4 10.9 1.4 13.8-35.7-11.7-74.2-23.3-84.9-4.7-4.6-4.9-6.6-2.6-6.5 12.6 11.2 29.2 33.7 35.2 59 2.8 11.6 3.3 23.7.4 35.7 16.4 6.8 35.9 17.9 30.7 34.8-2.2-.1-3.2 0-4.2 0 3.2-10.1-3.9-17.6-22.8-26.1-19.6-8.6-36-8.6-38.3 12.5-12.1 4.2-18.3 14.7-21.4 27.3-2.8 11.2-3.6 24.7-4.4 39.9-.5 7.7-3.6 18-6.8 29-32.1 22.9-76.7 32.9-114.3 7.2zm257.4-11.5c-.9 16.8-41.2 19.9-63.2 46.5-13.2 15.7-29.4 24.4-43.6 25.5s-26.5-4.8-33.7-19.3c-4.7-11.1-2.4-23.1 1.1-36.3 3.7-14.2 9.2-28.8 9.9-40.6.8-15.2 1.7-28.5 4.2-38.7 2.6-10.3 6.6-17.2 13.7-21.1.3-.2.7-.3 1-.5.8 13.2 7.3 26.6 18.8 29.5 12.6 3.3 30.7-7.5 38.4-16.3 9-.3 15.7-.9 22.6 5.1 9.9 8.5 7.1 30.3 17.1 41.6 10.6 11.6 14 19.5 13.7 24.6zM173.3 148.7c2 1.9 4.7 4.5 8 7.1 6.6 5.2 15.8 10.6 27.3 10.6 11.6 0 22.5-5.9 31.8-10.8 4.9-2.6 10.9-7 14.8-10.4s5.9-6.3 3.1-6.6-2.6 2.6-6 5.1c-4.4 3.2-9.7 7.4-13.9 9.8-7.4 4.2-19.5 10.2-29.9 10.2s-18.7-4.8-24.9-9.7c-3.1-2.5-5.7-5-7.7-6.9-1.5-1.4-1.9-4.6-4.3-4.9-1.4-.1-1.8 3.7 1.7 6.5z",
    ),
    DOCKER(
        640f,
        512f,
        "M349.9 236.3h-66.1v-59.4h66.1v59.4zm0-204.3h-66.1v60.7h66.1V32zm78.2 144.8H362v59.4h66.1v-59.4zm-156.3-72.1h-66.1v60.1h66.1v-60.1zm78.1 0h-66.1v60.1h66.1v-60.1zm276.8 100c-14.4-9.7-47.6-13.2-73.1-8.4-3.3-24-16.7-44.9-41.1-63.7l-14-9.3-9.3 14c-18.4 27.8-23.4 73.6-3.7 103.8-8.7 4.7-25.8 11.1-48.4 10.7H2.4c-8.7 50.8 5.8 116.8 44 162.1 37.1 43.9 92.7 66.2 165.4 66.2 157.4 0 273.9-72.5 328.4-204.2 21.4.4 67.6.1 91.3-45.2 1.5-2.5 6.6-13.2 8.5-17.1l-13.3-8.9zm-511.1-27.9h-66v59.4h66.1v-59.4zm78.1 0h-66.1v59.4h66.1v-59.4zm78.1 0h-66.1v59.4h66.1v-59.4zm-78.1-72.1h-66.1v60.1h66.1v-60.1z",
    ),
    ANDROID(
        576f,
        512f,
        "M420.55,301.93a24,24,0,1,1,24-24,24,24,0,0,1-24,24m-265.1,0a24,24,0,1,1,24-24,24,24,0,0,1-24,24m273.7-144.48,47.94-83a10,10,0,1,0-17.27-10h0l-48.54,84.07a301.25,301.25,0,0,0-246.56,0L116.18,64.45a10,10,0,1,0-17.27,10h0l47.94,83C64.53,202.22,8.24,285.55,0,384H576c-8.24-98.45-64.54-181.78-146.85-226.55",
    ),
    ;

    val path: Path by lazy { PathParser().parsePathString(vectorData).toPath() }

    companion object {
        fun forBadge(kind: GenericBadge): FontAwesomeGlyph = when (kind) {
            GenericBadge.TERMINAL -> TERMINAL
            GenericBadge.CODE -> CODE
            GenericBadge.CHIP -> CHIP
            GenericBadge.NETWORK -> NETWORK
            GenericBadge.DATABASE -> DATABASE
            GenericBadge.SECURITY -> SECURITY
            GenericBadge.BOT -> BOT
            GenericBadge.PACKAGE -> PACKAGE
        }
    }
}
