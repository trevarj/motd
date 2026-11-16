package io.github.trevarj.motd

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.avatar.IrcSpriteV2Renderer
import io.github.trevarj.motd.avatar.IrcSpriteV2Theme
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.PaletteColors
import io.github.trevarj.motd.data.prefs.PaletteDocument
import io.github.trevarj.motd.data.prefs.PaletteMode
import io.github.trevarj.motd.data.prefs.isDark
import io.github.trevarj.motd.ui.components.IrcSpriteV2Avatar
import io.github.trevarj.motd.ui.components.isAppliedThemeDark
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.NickColorScheme
import io.github.trevarj.motd.ui.theme.accessibleColorScheme
import io.github.trevarj.motd.ui.theme.fixedThemeScheme
import io.github.trevarj.motd.ui.theme.paletteNickFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ThemeStateRetentionUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun switchingFromDynamicToFixedPalette_preservesStatefulContent() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        lateinit var selectTheme: (ColorThemePreset) -> Unit
        lateinit var selectTextColors: (Boolean) -> Unit
        compose.setContent {
            var theme by remember { mutableStateOf(ColorThemePreset.SYSTEM) }
            var useThemeTextColors by remember { mutableStateOf(false) }
            selectTheme = { theme = it }
            selectTextColors = { useThemeTextColors = it }
            MotdTheme(themePreset = theme, dynamicColor = true, useThemeTextColors = useThemeTextColors) {
                var marker by remember { mutableStateOf("initial") }
                Button(
                    onClick = { marker = "retained" },
                    modifier = Modifier.testTag("theme_state_marker"),
                ) {
                    Text(marker)
                }
            }
        }

        compose.onNodeWithTag("theme_state_marker").performClick().assertTextEquals("retained")
        compose.runOnUiThread { selectTheme(ColorThemePreset.AYU_DARK) }
        compose.waitForIdle()

        compose.onNodeWithTag("theme_state_marker").assertTextEquals("retained")
        for (useThemeTextColors in listOf(true, false)) {
            compose.runOnUiThread { selectTextColors(useThemeTextColors) }
            compose.waitForIdle()
            compose.onNodeWithTag("theme_state_marker").assertTextEquals("retained")
        }
    }

    @Test
    fun spriteV2_recomposesWithTheResolvedLightAndDarkPalette() {
        lateinit var selectTheme: (ColorThemePreset) -> Unit
        var expected: Bitmap? = null
        compose.setContent {
            var theme by remember { mutableStateOf(ColorThemePreset.LIGHT) }
            selectTheme = { theme = it }
            // Keep the nickname hue fixed so this test isolates the sprite palette transition.
            val nickColors =
                remember {
                    NickColorScheme(
                        enabled = true,
                        palette = NickColorPalette.CLASSIC,
                        overrides = emptyMap(),
                        isDark = false,
                    )
                }
            MotdTheme(themePreset = theme, dynamicColor = false) {
                androidx.compose.runtime.CompositionLocalProvider(LocalNickColors provides nickColors) {
                    val context = LocalContext.current
                    val density = LocalDensity.current
                    val scheme = MaterialTheme.colorScheme
                    val dark = isAppliedThemeDark()
                    val accent = LocalNickColors.current.avatar("alice")
                    val size = 48.dp
                    val sizePx = with(density) { size.roundToPx() }
                    val base = if (dark) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh
                    SideEffect {
                        expected =
                            IrcSpriteV2Renderer.render(
                                context = context,
                                name = "alice",
                                accent = accent.toArgb(),
                                sizePx = sizePx,
                                baseColor = base.toArgb(),
                                ringColor = accent.copy(alpha = 0.52f).toArgb(),
                                theme = if (dark) IrcSpriteV2Theme.DARK else IrcSpriteV2Theme.LIGHT,
                            )
                    }
                    IrcSpriteV2Avatar("alice", size, Modifier.testTag("sprite_v2_theme"))
                }
            }
        }

        fun assertAvatarMatchesExpected() {
            compose.waitForIdle()
            val actual = compose.onNodeWithTag("sprite_v2_theme").captureToImage().asAndroidBitmap()
            val rendered = requireNotNull(expected)
            assertEquals(rendered.width, actual.width)
            assertEquals(rendered.height, actual.height)
            // The circular clip only changes edge anti-aliasing; the center proves the renderer
            // received the active palette rather than a stale dark cache entry.
            assertEquals(rendered.getPixel(rendered.width / 2, rendered.height / 2), actual.getPixel(actual.width / 2, actual.height / 2))
        }

        assertAvatarMatchesExpected()
        compose.runOnUiThread { selectTheme(ColorThemePreset.DARK) }
        assertAvatarMatchesExpected()
        compose.runOnUiThread { selectTheme(ColorThemePreset.LIGHT) }
        assertAvatarMatchesExpected()
    }

    @Test
    fun customApplyModeAndDefinitionEdits_keepComposerScrollAndAvatarCurrent() {
        val initial = customPalette()
        var palette by mutableStateOf<PaletteDocument?>(null)
        var mode by mutableStateOf(PaletteMode.DARK)
        var trueBlack by mutableStateOf(false)
        var themeText by mutableStateOf(false)
        lateinit var scheme: ColorScheme
        var nick = Color.Unspecified
        lateinit var listState: LazyListState
        var expectedAvatar: Bitmap? = null
        compose.setContent {
            MotdTheme(
                themePreset = ColorThemePreset.AYU_DARK,
                paletteMode = mode,
                customPalette = palette,
                trueBlack = trueBlack,
                useThemeTextColors = themeText,
                dynamicColor = true,
                syncSystemBars = false,
            ) {
                var draft by remember { mutableStateOf("") }
                val list = rememberLazyListState()
                val currentScheme = MaterialTheme.colorScheme
                val currentNick = LocalNickColors.current.avatar("alice")
                val dark = isAppliedThemeDark()
                val context = LocalContext.current
                val sizePx = with(LocalDensity.current) { 48.dp.roundToPx() }
                SideEffect {
                    scheme = currentScheme
                    nick = currentNick
                    listState = list
                    expectedAvatar =
                        IrcSpriteV2Renderer.render(
                            context = context,
                            name = "alice",
                            accent = currentNick.toArgb(),
                            sizePx = sizePx,
                            baseColor = (if (dark) currentScheme.surfaceContainerHighest else currentScheme.surfaceContainerHigh).toArgb(),
                            ringColor = currentNick.copy(alpha = 0.52f).toArgb(),
                            theme = if (dark) IrcSpriteV2Theme.DARK else IrcSpriteV2Theme.LIGHT,
                        )
                }
                Surface(color = currentScheme.background, modifier = Modifier.testTag("custom_sample")) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("#palette · native preview", style = MaterialTheme.typography.titleLarge)
                        IrcSpriteV2Avatar("alice", 48.dp, Modifier.testTag("custom_avatar"))
                        Text("alice", color = LocalNickColors.current.nick("alice", currentScheme.onSurface))
                        Text("A palette update must keep this conversation.", color = currentScheme.onSurface)
                        LazyColumn(state = list, modifier = Modifier.height(120.dp).testTag("retained_timeline")) {
                            items(40) { Text("Message $it", Modifier.height(30.dp)) }
                        }
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = { Text("Composer") },
                            modifier = Modifier.testTag("retained_composer"),
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("retained_composer").performTextInput("unsent message")
        compose.onNodeWithTag("retained_timeline").performScrollToIndex(12)

        fun assertRetainedAndRendered(name: String? = null) {
            compose.waitForIdle()
            compose.onNodeWithTag("retained_composer").assertTextEquals("Composer", "unsent message")
            compose.runOnIdle { assertEquals(12, listState.firstVisibleItemIndex) }
            val avatar = compose.onNodeWithTag("custom_avatar").captureToImage().asAndroidBitmap()
            val expected = requireNotNull(expectedAvatar)
            assertEquals(expected.width, avatar.width)
            assertEquals(expected.height, avatar.height)
            assertEquals(expected.getPixel(expected.width / 2, expected.height / 2), avatar.getPixel(avatar.width / 2, avatar.height / 2))
            if (name != null) {
                val image = compose.onNodeWithTag("custom_sample").captureToImage().asAndroidBitmap()
                assertEquals("The native sample paints the resolved canvas", scheme.background.toArgb(), image.getPixel(0, 0))
                val module = if (File("app/src").isDirectory) File("app") else File(".")
                val proof = File(module, "build/reports/palettes/$name.png")
                requireNotNull(proof.parentFile).mkdirs()
                proof.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        }

        assertRetainedAndRendered()
        compose.runOnUiThread { palette = initial }
        assertRetainedAndRendered("custom-dark")
        assertEquals(Color(0xFF18212B), scheme.background)
        assertEquals(Color(0xFFEEAA44), nick)

        compose.runOnUiThread { mode = PaletteMode.LIGHT }
        assertRetainedAndRendered("custom-light")
        assertEquals(Color(0xFFF4F0E8), scheme.background)
        assertEquals(Color(0xFF007788), nick)

        val previousPrimary = scheme.primary
        compose.runOnUiThread {
            palette = initial.copy(light = requireNotNull(initial.light).copy(primary = "#884488", nicknameColors = listOf("#CC3366")))
        }
        assertRetainedAndRendered()
        assertNotEquals(previousPrimary, scheme.primary)
        assertEquals(Color(0xFFCC3366), nick)

        val beforeNicknameEdit = scheme
        compose.runOnUiThread {
            palette = requireNotNull(palette).copy(light = requireNotNull(palette?.light).copy(nicknameColors = listOf("#228844")))
        }
        assertRetainedAndRendered()
        assertSchemeMatches(beforeNicknameEdit, scheme)
        assertEquals(Color(0xFF228844), nick)

        compose.runOnUiThread {
            palette = requireNotNull(palette).copy(light = requireNotNull(palette?.light).copy(nicknameColors = emptyList()))
        }
        assertRetainedAndRendered()
        assertEquals(
            paletteNickFill("alice", false, NickColorPalette.THEME, listOf(scheme.primary, scheme.tertiary, scheme.secondary).distinct()),
            nick,
        )
        for (useThemeTextColors in listOf(true, false)) {
            compose.runOnUiThread { themeText = useThemeTextColors }
            assertRetainedAndRendered()
        }
        compose.runOnUiThread {
            mode = PaletteMode.DARK
            trueBlack = true
        }
        assertRetainedAndRendered("custom-true-black")
        assertEquals(Color.Black, scheme.background)
        assertEquals(Color.Black, scheme.surface)
    }

    @Test
    fun deviceExplicitModesUseMatchingDynamicScheme_namedSystemAndDirectDefaultsStayFixed() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        var preset by mutableStateOf(ColorThemePreset.SYSTEM)
        var mode by mutableStateOf<PaletteMode?>(PaletteMode.LIGHT)
        var osDark by mutableStateOf(true)
        lateinit var actual: ColorScheme
        lateinit var dynamicLight: ColorScheme
        lateinit var dynamicDark: ColorScheme
        var appliedDark = false
        compose.setContent {
            val context = LocalContext.current
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (osDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
            dynamicLight = accessibleColorScheme(dynamicLightColorScheme(context), false)
            dynamicDark = accessibleColorScheme(dynamicDarkColorScheme(context), true)
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MotdTheme(themePreset = preset, paletteMode = mode, dynamicColor = true, syncSystemBars = false) {
                    val current = MaterialTheme.colorScheme
                    val dark = isAppliedThemeDark()
                    SideEffect {
                        actual = current
                        appliedDark = dark
                    }
                    Text("Mode preview")
                }
            }
        }
        compose.waitForIdle()
        assertFalse(appliedDark)
        assertSchemeMatches(dynamicLight, actual)
        compose.runOnUiThread {
            mode = PaletteMode.DARK
            osDark = false
        }
        compose.waitForIdle()
        assertTrue(appliedDark)
        assertSchemeMatches(dynamicDark, actual)

        compose.runOnUiThread {
            preset = ColorThemePreset.AYU_DARK
            mode = PaletteMode.SYSTEM
        }
        compose.waitForIdle()
        assertFalse(appliedDark)
        assertSchemeMatches(fixedThemeScheme(ColorThemePreset.AYU_LIGHT), actual)
        compose.runOnUiThread { osDark = true }
        compose.waitForIdle()
        assertTrue(appliedDark)
        assertSchemeMatches(fixedThemeScheme(ColorThemePreset.AYU_DARK), actual)

        for (directPreset in listOf(ColorThemePreset.AYU_LIGHT, ColorThemePreset.AYU_DARK, ColorThemePreset.DRACULA_LIGHT)) {
            compose.runOnUiThread {
                preset = directPreset
                mode = null
                osDark = !directPreset.isDark
            }
            compose.waitForIdle()
            assertEquals(directPreset.isDark, appliedDark)
            assertSchemeMatches(fixedThemeScheme(directPreset), actual)
        }
        compose.runOnUiThread {
            preset = ColorThemePreset.SYSTEM
            osDark = false
        }
        compose.waitForIdle()
        assertFalse(appliedDark)
        assertSchemeMatches(dynamicLight, actual)
        compose.runOnUiThread { osDark = true }
        compose.waitForIdle()
        assertTrue(appliedDark)
        assertSchemeMatches(dynamicDark, actual)
    }

    private fun assertSchemeMatches(
        expected: ColorScheme?,
        actual: ColorScheme,
    ) {
        fun roles(scheme: ColorScheme) =
            with(scheme) {
                listOf(
                    primary,
                    secondary,
                    tertiary,
                    onPrimary,
                    primaryContainer,
                    background,
                    surface,
                    surfaceContainer,
                    surfaceContainerHigh,
                    surfaceContainerHighest,
                    onBackground,
                    onSurface,
                    onSurfaceVariant,
                    outline,
                    outlineVariant,
                )
            }
        assertEquals(roles(requireNotNull(expected)), roles(actual))
    }

    private fun customPalette() =
        PaletteDocument(
            id = "retention-preview",
            name = "Retention preview",
            revision = 1,
            light =
                PaletteColors(
                    primary = "#006677",
                    secondary = "#885599",
                    tertiary = "#AA5544",
                    background = "#F4F0E8",
                    surface = "#F4F0E8",
                    surfaceContainer = "#E8E2D8",
                    onBackground = "#222222",
                    onSurface = "#222222",
                    onSurfaceVariant = "#444444",
                    outlineVariant = "#777777",
                    nicknameColors = listOf("#007788"),
                ),
            dark =
                PaletteColors(
                    primary = "#88CCDD",
                    secondary = "#CC99DD",
                    tertiary = "#EEAA88",
                    background = "#18212B",
                    surface = "#18212B",
                    surfaceContainer = "#26313E",
                    onBackground = "#EEEEEE",
                    onSurface = "#EEEEEE",
                    onSurfaceVariant = "#BBBBBB",
                    outlineVariant = "#888888",
                    nicknameColors = listOf("#EEAA44"),
                ),
        )
}
