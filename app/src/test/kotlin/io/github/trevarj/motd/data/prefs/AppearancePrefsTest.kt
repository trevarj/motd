package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppearancePrefsTest {
    private val prefs: AppearancePrefs = AppearancePrefsImpl(ApplicationProvider.getApplicationContext<Context>())
    private val jsonWithDefaults = Json { encodeDefaults = true }

    @Test fun defaults_areSystemAndMotdAtFifty() {
        assertEquals(
            AppearanceConfig(
                ColorThemePreset.SYSTEM,
                WallpaperSelection(ChatWallpaperPreset.MOTD, 50),
                100,
                100,
            ),
            AppearanceConfig(),
        )
        // New stage-1 fields keep sane defaults when absent from the store (data-class defaults,
        // not read through prefs.config here since the DataStore singleton is shared across the
        // test methods in this class).
        assertEquals(FontChoice.SYSTEM, AppearanceConfig().fontChoice)
        assertEquals(true, AppearanceConfig().showTimestamps)
        assertEquals(TimeFormat.AUTO, AppearanceConfig().timeFormat)
        assertEquals(MessageSpacing.DEFAULT, AppearanceConfig().messageSpacing)
        assertEquals(BubbleCornerStyle.ROUNDED, AppearanceConfig().bubbleCornerStyle)
        assertEquals(false, AppearanceConfig().chatShadowsEnabled)
        assertEquals(false, AppearanceConfig().useThemeTextColors)
        assertEquals(LauncherIcon.DEFAULT, AppearanceConfig().launcherIcon)
        assertEquals(SplashScreenAnimation.EDGE_TRACE, AppearanceConfig().splashScreenAnimation)
        assertEquals("", AppearanceConfig().customFontName)
    }

    @Test fun newAppearanceFields_roundTripNonDefaultValues() =
        runTest {
            prefs.setFontChoice(FontChoice.JETBRAINS_MONO)
            prefs.setShowTimestamps(false)
            prefs.setTimeFormat(TimeFormat.H24)
            prefs.setMessageSpacing(MessageSpacing.RELAXED)
            prefs.setBubbleCornerStyle(BubbleCornerStyle.SQUARE)
            prefs.setLauncherIcon(LauncherIcon.GRUVBOX)
            prefs.setSplashScreenAnimation(SplashScreenAnimation.TRAVELING_PULSE)

            val config = prefs.config.first()
            assertEquals(FontChoice.JETBRAINS_MONO, config.fontChoice)
            assertEquals(false, config.showTimestamps)
            assertEquals(TimeFormat.H24, config.timeFormat)
            assertEquals(MessageSpacing.RELAXED, config.messageSpacing)
            assertEquals(BubbleCornerStyle.SQUARE, config.bubbleCornerStyle)
            assertEquals(LauncherIcon.GRUVBOX, config.launcherIcon)
            assertEquals(SplashScreenAnimation.TRAVELING_PULSE, config.splashScreenAnimation)
        }

    @Test fun legacySplashSelectionsStillPersistAndDecode() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val original = prefs.config.first().splashScreenAnimation
            try {
                assertEquals(SplashScreenAnimation.EDGE_TRACE, Json.decodeFromString<AppearanceConfig>("""{}""").splashScreenAnimation)
                SplashScreenAnimation.entries.forEach { animation ->
                    prefs.setSplashScreenAnimation(animation)
                    val reloaded = AppearancePrefsImpl(context).config.first()
                    assertEquals(animation, reloaded.splashScreenAnimation)
                    assertEquals(animation, Json.decodeFromString<AppearanceConfig>(jsonWithDefaults.encodeToString(reloaded)).splashScreenAnimation)
                }
            } finally {
                prefs.setSplashScreenAnimation(original)
            }
        }

    @Test fun composerStyleRoundTripsAndFallsBackToComfortable() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val key = stringPreferencesKey("composer_style_v1")
            context.appearanceDataStore.edit { it.remove(key) }
            assertEquals(ComposerStyle.COMFORTABLE, prefs.config.first().composerStyle)
            assertEquals(ComposerStyle.COMFORTABLE, Json.decodeFromString<AppearanceConfig>("""{}""").composerStyle)

            try {
                prefs.setComposerStyle(ComposerStyle.LARGE)
                assertEquals(ComposerStyle.LARGE, AppearancePrefsImpl(context).config.first().composerStyle)
                prefs.setComposerStyle(ComposerStyle.COMPACT)
                assertEquals(ComposerStyle.COMPACT, AppearancePrefsImpl(context).config.first().composerStyle)
                assertEquals(ComposerStyle.COMPACT.name, context.appearanceDataStore.data.first()[key])

                context.appearanceDataStore.edit { it[key] = "not-a-composer-style" }
                assertEquals(ComposerStyle.COMFORTABLE, prefs.config.first().composerStyle)
            } finally {
                context.appearanceDataStore.edit { it.remove(key) }
            }
        }

    @Test fun uiIconSetRoundTripsReloadsAndDefaultsForMissingInvalidAndOldJson() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val key = stringPreferencesKey("ui_icon_set_v1")
            val original = context.appearanceDataStore.data.first()[key]
            try {
                context.appearanceDataStore.edit { it.remove(key) }
                assertEquals(UiIconSet.MATERIAL, AppearancePrefsImpl(context).config.first().uiIconSet)
                assertEquals(UiIconSet.MATERIAL, Json.decodeFromString<AppearanceConfig>("""{"composerStyle":"LARGE"}""").uiIconSet)
                for (iconSet in listOf(UiIconSet.MATERIAL, UiIconSet.LUCIDE, UiIconSet.MATERIAL)) {
                    prefs.setUiIconSet(iconSet)
                    val reloaded = AppearancePrefsImpl(context).config.first()
                    assertEquals(iconSet, reloaded.uiIconSet)
                    assertEquals(iconSet.name, context.appearanceDataStore.data.first()[key])
                    assertEquals(reloaded, Json.decodeFromString<AppearanceConfig>(jsonWithDefaults.encodeToString(reloaded)))
                }
                context.appearanceDataStore.edit { it[key] = "unknown-icon-set" }
                assertEquals(UiIconSet.MATERIAL, AppearancePrefsImpl(context).config.first().uiIconSet)
            } finally {
                context.appearanceDataStore.edit {
                    if (original == null) it.remove(key) else it[key] = original
                }
            }
        }

    @Test fun surfaceShapesRoundTripEveryValueIndependentlyAndFallBackToDefault() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val inputKey = stringPreferencesKey("input_corner_style_v1")
            val avatarKey = stringPreferencesKey("avatar_shape_v1")
            val chatCardKey = stringPreferencesKey("chat_card_corner_style_v1")
            val mediaKey = stringPreferencesKey("media_corner_style_v1")
            val keys = listOf(inputKey, avatarKey, chatCardKey, mediaKey)
            context.appearanceDataStore.edit { stored -> keys.forEach { stored.remove(it) } }
            try {
                val baseline = AppearancePrefsImpl(context).config.first()
                assertEquals(UiCornerStyle.DEFAULT, baseline.inputCornerStyle)
                assertEquals(AvatarShape.DEFAULT, baseline.avatarShape)
                assertEquals(UiCornerStyle.DEFAULT, baseline.chatCardCornerStyle)
                assertEquals(UiCornerStyle.DEFAULT, baseline.mediaCornerStyle)
                var expected = baseline
                for (style in UiCornerStyle.entries) {
                    prefs.setInputCornerStyle(style)
                    expected = expected.copy(inputCornerStyle = style)
                    assertEquals(expected, AppearancePrefsImpl(context).config.first())
                    assertEquals(style.name, context.appearanceDataStore.data.first()[inputKey])
                    prefs.setChatCardCornerStyle(style)
                    expected = expected.copy(chatCardCornerStyle = style)
                    assertEquals(expected, AppearancePrefsImpl(context).config.first())
                    assertEquals(style.name, context.appearanceDataStore.data.first()[chatCardKey])
                    prefs.setMediaCornerStyle(style)
                    expected = expected.copy(mediaCornerStyle = style)
                    assertEquals(expected, AppearancePrefsImpl(context).config.first())
                    assertEquals(style.name, context.appearanceDataStore.data.first()[mediaKey])
                }
                for (shape in AvatarShape.entries) {
                    prefs.setAvatarShape(shape)
                    expected = expected.copy(avatarShape = shape)
                    assertEquals(expected, AppearancePrefsImpl(context).config.first())
                    assertEquals(shape.name, context.appearanceDataStore.data.first()[avatarKey])
                }
                context.appearanceDataStore.edit { stored -> keys.forEach { stored[it] = "not-a-shape" } }
                assertEquals(baseline, AppearancePrefsImpl(context).config.first())
            } finally {
                context.appearanceDataStore.edit { stored -> keys.forEach { stored.remove(it) } }
            }
        }

    @Test fun surfaceShapeJsonDefaultsForOldAppearanceAndPreservesExplicitChoices() {
        val old = Json.decodeFromString<AppearanceConfig>("""{"composerStyle":"LARGE","bubbleCornerStyle":"SQUARE"}""")
        assertEquals(ComposerStyle.LARGE, old.composerStyle)
        assertEquals(BubbleCornerStyle.SQUARE, old.bubbleCornerStyle)
        assertEquals(UiCornerStyle.DEFAULT, old.inputCornerStyle)
        assertEquals(AvatarShape.DEFAULT, old.avatarShape)
        assertEquals(UiCornerStyle.DEFAULT, old.chatCardCornerStyle)
        assertEquals(UiCornerStyle.DEFAULT, old.mediaCornerStyle)
        val selected =
            old.copy(
                inputCornerStyle = UiCornerStyle.SQUARE,
                avatarShape = AvatarShape.CIRCLE,
                chatCardCornerStyle = UiCornerStyle.SOFT,
                mediaCornerStyle = UiCornerStyle.ROUNDED,
            )
        assertEquals(selected, Json.decodeFromString<AppearanceConfig>(jsonWithDefaults.encodeToString(selected)))
    }

    @Test fun customFontName_roundTrips() =
        runTest {
            // The data-class default (empty) is covered by defaults_areSystemAndMotdAtFifty; this
            // DataStore instance is shared across every test method in this class (see that test's
            // comment), so a leading "reads empty" assertion here would be order-dependent.
            prefs.setCustomFontName("Iosevka Term.ttf")
            assertEquals("Iosevka Term.ttf", prefs.config.first().customFontName)
            prefs.setFontChoice(FontChoice.CUSTOM)
            assertEquals(FontChoice.CUSTOM, prefs.config.first().fontChoice)
            prefs.setCustomFontName("")
            assertEquals("", prefs.config.first().customFontName)
        }

    @Test fun chatShadowsEnabled_roundTripsAndDefaultsWhenMissing() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.appearanceDataStore.edit {
                it.remove(booleanPreferencesKey("chat_shadows_enabled_v1"))
            }
            assertEquals(false, prefs.config.first().chatShadowsEnabled)
            prefs.setChatShadowsEnabled(true)
            assertEquals(true, prefs.config.first().chatShadowsEnabled)
            prefs.setChatShadowsEnabled(false)
            assertEquals(false, prefs.config.first().chatShadowsEnabled)
        }

    @Test fun nicknameBackgroundsRoundTripAndDefaultOnForMissingPreferences() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val key = booleanPreferencesKey("nickname_backgrounds_enabled_v1")
            context.appearanceDataStore.edit { it.remove(key) }
            try {
                assertEquals(true, prefs.config.first().nicknameBackgroundsEnabled)
                assertEquals(true, Json.decodeFromString<AppearanceConfig>("{}").nicknameBackgroundsEnabled)
                prefs.setNicknameBackgroundsEnabled(false)
                assertEquals(false, AppearancePrefsImpl(context).config.first().nicknameBackgroundsEnabled)
                assertEquals(false, context.appearanceDataStore.data.first()[key])
                val encoded = jsonWithDefaults.encodeToString(prefs.config.first())
                assertEquals(false, Json.decodeFromString<AppearanceConfig>(encoded).nicknameBackgroundsEnabled)
                prefs.setNicknameBackgroundsEnabled(true)
                assertEquals(true, AppearancePrefsImpl(context).config.first().nicknameBackgroundsEnabled)
            } finally {
                context.appearanceDataStore.edit { it.remove(key) }
            }
        }

    @Test fun messageBackgroundOpacityRoundTripsClampsReloadsAndDefaultsForOldJson() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val key = intPreferencesKey("message_background_opacity_v1")
            context.appearanceDataStore.edit { it.remove(key) }
            try {
                assertEquals(100, AppearancePrefsImpl(context).config.first().messageBackgroundOpacityPercent)
                assertEquals(100, Json.decodeFromString<AppearanceConfig>("{}").messageBackgroundOpacityPercent)
                for (percent in listOf(0, 37, 100, -12, 130)) {
                    val expected = percent.coerceIn(0, 100)
                    prefs.setMessageBackgroundOpacity(percent)
                    val reloaded = AppearancePrefsImpl(context).config.first()
                    assertEquals(expected, reloaded.messageBackgroundOpacityPercent)
                    assertEquals(expected, context.appearanceDataStore.data.first()[key])
                    assertEquals(expected, Json.decodeFromString<AppearanceConfig>(jsonWithDefaults.encodeToString(reloaded)).messageBackgroundOpacityPercent)
                }
                for (percent in listOf(-12, 130)) {
                    context.appearanceDataStore.edit { it[key] = percent }
                    assertEquals(percent.coerceIn(0, 100), AppearancePrefsImpl(context).config.first().messageBackgroundOpacityPercent)
                }
            } finally {
                context.appearanceDataStore.edit { it.remove(key) }
            }
        }

    @Test fun messageBackgroundBlurRoundTripsClampsAndDefaultsWithoutChangingOpacity() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val key = intPreferencesKey("message_background_blur_v1")
            val originalOpacity = prefs.config.first().messageBackgroundOpacityPercent
            context.appearanceDataStore.edit { it.remove(key) }
            try {
                assertEquals(0, AppearancePrefsImpl(context).config.first().messageBackgroundBlur)
                assertEquals(0, Json.decodeFromString<AppearanceConfig>("{}").messageBackgroundBlur)
                for (intensity in listOf(0, 1, 37, 100, -12, 130)) {
                    val expected = intensity.coerceIn(0, 100)
                    prefs.setMessageBackgroundBlur(intensity)
                    val reloaded = AppearancePrefsImpl(context).config.first()
                    assertEquals(expected, reloaded.messageBackgroundBlur)
                    assertEquals(expected, context.appearanceDataStore.data.first()[key])
                    assertEquals(expected, Json.decodeFromString<AppearanceConfig>(jsonWithDefaults.encodeToString(reloaded)).messageBackgroundBlur)
                    assertEquals(originalOpacity, reloaded.messageBackgroundOpacityPercent)
                }
                for (intensity in listOf(-12, 130)) {
                    context.appearanceDataStore.edit { it[key] = intensity }
                    assertEquals(intensity.coerceIn(0, 100), AppearancePrefsImpl(context).config.first().messageBackgroundBlur)
                }
            } finally {
                context.appearanceDataStore.edit { it.remove(key) }
            }
        }

    @Test fun comfortableBubbleColors_roundTripWithCustomOpaqueColorAndDefaultFallback() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val sentRole = stringPreferencesKey("sent_bubble_color_role_v1")
            val receivedRole = stringPreferencesKey("received_bubble_color_role_v1")
            val sentCustom = intPreferencesKey("sent_bubble_color_custom_v1")
            val receivedCustom = intPreferencesKey("received_bubble_color_custom_v1")
            context.appearanceDataStore.edit {
                it.remove(sentRole)
                it.remove(receivedRole)
                it.remove(sentCustom)
                it.remove(receivedCustom)
            }
            try {
                assertEquals(ComfortableBubbleColor(), prefs.config.first().sentBubbleColor)
                assertEquals(ComfortableBubbleColor(), prefs.config.first().receivedBubbleColor)
                prefs.setSentBubbleColor(ComfortableBubbleColor(ComfortableBubbleColorRole.TERTIARY_CONTAINER))
                prefs.setReceivedBubbleColor(ComfortableBubbleColor(ComfortableBubbleColorRole.CUSTOM, 0x0012ab34))
                val config = AppearancePrefsImpl(context).config.first()
                assertEquals(ComfortableBubbleColor(ComfortableBubbleColorRole.TERTIARY_CONTAINER), config.sentBubbleColor)
                assertEquals(ComfortableBubbleColor(ComfortableBubbleColorRole.CUSTOM, 0xff12ab34.toInt()), config.receivedBubbleColor)
                context.appearanceDataStore.edit { it[receivedRole] = "bad-role" }
                assertEquals(ComfortableBubbleColor(), AppearancePrefsImpl(context).config.first().receivedBubbleColor)
            } finally {
                context.appearanceDataStore.edit {
                    it.remove(sentRole)
                    it.remove(receivedRole)
                    it.remove(sentCustom)
                    it.remove(receivedCustom)
                }
            }
        }

    @Test fun appearanceJsonPreservesExplicitShadowPreference() {
        assertEquals(false, Json.decodeFromString<AppearanceConfig>("""{}""").chatShadowsEnabled)
        assertEquals(true, Json.decodeFromString<AppearanceConfig>("""{"chatShadowsEnabled":true}""").chatShadowsEnabled)
    }

    @Test fun themeTextColorsDefaultsToStaticAndPersistsAfterReload() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val key = booleanPreferencesKey("use_theme_text_colors_v1")
            context.appearanceDataStore.edit { it.remove(key) }
            try {
                assertEquals(false, prefs.config.first().useThemeTextColors)
                assertEquals(false, Json.decodeFromString<AppearanceConfig>("""{}""").useThemeTextColors)
                prefs.setUseThemeTextColors(true)
                assertEquals(true, AppearancePrefsImpl(context).config.first().useThemeTextColors)
                assertEquals(true, context.appearanceDataStore.data.first()[key])
                val saved = jsonWithDefaults.encodeToString(prefs.config.first())
                assertEquals(true, Json.decodeFromString<AppearanceConfig>(saved).useThemeTextColors)
                prefs.setUseThemeTextColors(false)
                assertEquals(false, AppearancePrefsImpl(context).config.first().useThemeTextColors)
            } finally {
                context.appearanceDataStore.edit { it.remove(key) }
            }
        }

    @Test fun garbageStoredEnumStrings_decodeToDefaults() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.appearanceDataStore.edit {
                it[stringPreferencesKey("font_choice_v1")] = "not-a-font"
                it[stringPreferencesKey("time_format_v1")] = "not-a-format"
                it[stringPreferencesKey("message_spacing_v1")] = "not-a-spacing"
                it[stringPreferencesKey("bubble_corner_style_v1")] = "not-a-style"
                it[stringPreferencesKey("launcher_icon_v1")] = "not-an-icon"
                it[stringPreferencesKey("splash_screen_animation_v1")] = "not-an-animation"
            }

            val config = prefs.config.first()
            assertEquals(FontChoice.SYSTEM, config.fontChoice)
            assertEquals(TimeFormat.AUTO, config.timeFormat)
            assertEquals(MessageSpacing.DEFAULT, config.messageSpacing)
            assertEquals(BubbleCornerStyle.ROUNDED, config.bubbleCornerStyle)
            assertEquals(LauncherIcon.DEFAULT, config.launcherIcon)
            assertEquals(SplashScreenAnimation.EDGE_TRACE, config.splashScreenAnimation)
        }

    @Test fun themeAndWallpaper_roundTrip() =
        runTest {
            prefs.setTheme(ColorThemePreset.KANAGAWA_WAVE)
            prefs.setTrueBlack(true)
            prefs.setFollowSystem(true)
            prefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.INTERNET_ODDITIES, 73))
            assertEquals(ColorThemePreset.KANAGAWA_WAVE, prefs.config.first().theme)
            assertEquals(true, prefs.config.first().trueBlack)
            assertEquals(true, prefs.config.first().followSystem)
            assertEquals(WallpaperSelection(ChatWallpaperPreset.INTERNET_ODDITIES, 73), prefs.config.first().wallpaper)
        }

    @Test fun wallpaperOutlineColorPersistsNormalizesAndResetsWithoutChangingTheSelection() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val selection = WallpaperSelection(ChatWallpaperPreset.LINUX, 73, outlineColor = 0x0012ab34)
            prefs.setWallpaper(selection)
            assertEquals(selection.copy(outlineColor = 0xff12ab34.toInt()), AppearancePrefsImpl(context).config.first().wallpaper)
            prefs.setWallpaper(selection.copy(outlineColor = null))
            assertEquals(selection.copy(outlineColor = null), AppearancePrefsImpl(context).config.first().wallpaper)
            assertEquals(null, context.appearanceDataStore.data.first()[intPreferencesKey("wallpaper_outline_color_v1")])
            // A pre-feature store has no color key, just like resetting to Default.
            context.appearanceDataStore.edit { it.remove(intPreferencesKey("wallpaper_outline_color_v1")) }
            assertEquals(
                null,
                AppearancePrefsImpl(context)
                    .config
                    .first()
                    .wallpaper.outlineColor,
            )
        }

    @Test fun scenicWallpaperControlsDefaultForLegacyStoresAndPersistTogether() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.appearanceDataStore.edit {
                it.remove(booleanPreferencesKey("wallpaper_scenic_follow_system_v1"))
                it.remove(booleanPreferencesKey("wallpaper_scenic_animation_v1"))
            }
            assertEquals(
                true,
                AppearancePrefsImpl(context)
                    .config
                    .first()
                    .wallpaper.scenicFollowSystem,
            )
            assertEquals(
                true,
                AppearancePrefsImpl(context)
                    .config
                    .first()
                    .wallpaper.scenicAnimationEnabled,
            )
            val selection =
                WallpaperSelection(
                    preset = ChatWallpaperPreset.JAPANESE_COUNTRYSIDE,
                    intensity = 73,
                    scenicFollowSystem = false,
                    scenicAnimationEnabled = false,
                )
            prefs.setWallpaper(selection)
            assertEquals(selection, AppearancePrefsImpl(context).config.first().wallpaper)
            assertEquals(false, context.appearanceDataStore.data.first()[booleanPreferencesKey("wallpaper_scenic_follow_system_v1")])
            assertEquals(false, context.appearanceDataStore.data.first()[booleanPreferencesKey("wallpaper_scenic_animation_v1")])
        }

    @Test fun followSystem_defaultsFalseAndRoundTrips() =
        runTest {
            assertEquals(false, prefs.config.first().followSystem)
            prefs.setFollowSystem(true)
            assertEquals(true, prefs.config.first().followSystem)
            prefs.setFollowSystem(false)
            assertEquals(false, prefs.config.first().followSystem)
        }

    @Test fun legacyAmoledTheme_migratesToAutomaticFamilyWithTrueBlack() {
        assertEquals(
            StoredThemeResolution(ColorThemePreset.DARK, true),
            resolveStoredTheme(ColorThemePreset.AMOLED.name, explicitTrueBlack = null),
        )
        assertEquals(
            StoredThemeResolution(ColorThemePreset.DARK, false),
            resolveStoredTheme(ColorThemePreset.AMOLED.name, explicitTrueBlack = false),
        )
    }

    @Test fun legacyAmoledSetter_preservesCompatibility() =
        runTest {
            prefs.setTheme(ColorThemePreset.AMOLED)
            assertEquals(ColorThemePreset.DARK, prefs.config.first().theme)
            assertEquals(true, prefs.config.first().trueBlack)
        }

    @Test fun storedDarkSibling_remainsAnExplicitSelection() {
        assertEquals(
            StoredThemeResolution(ColorThemePreset.CATPPUCCIN_MOCHA, false),
            resolveStoredTheme(ColorThemePreset.CATPPUCCIN_MOCHA.name, explicitTrueBlack = false),
        )
    }

    @Test fun wallpaperIntensity_isClampedAtomically() =
        runTest {
            prefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.RADIO_CLUB, 500))
            assertEquals(WallpaperSelection(ChatWallpaperPreset.RADIO_CLUB, 100), prefs.config.first().wallpaper)
            prefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.RETRO_GAMING, -9))
            assertEquals(WallpaperSelection(ChatWallpaperPreset.RETRO_GAMING, 0), prefs.config.first().wallpaper)
        }

    @Test fun customWallpaperPersistsAtomicallyAndBuiltinSelectionClearsLocalReference() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val selection = WallpaperSelection(ChatWallpaperPreset.RETRO_CHAT, 500, "12345678-1234-1234-1234-123456789abc.image")
            prefs.setWallpaper(selection)
            assertEquals(selection.copy(intensity = 100), AppearancePrefsImpl(context).config.first().wallpaper)
            for (preset in listOf(ChatWallpaperPreset.MEMES, ChatWallpaperPreset.NONE)) {
                prefs.setWallpaper(selection)
                prefs.setWallpaper(selection.copy(preset = preset, intensity = 73, localImageName = null))
                assertEquals(WallpaperSelection(preset, 73), AppearancePrefsImpl(context).config.first().wallpaper)
                assertEquals(null, context.appearanceDataStore.data.first()[stringPreferencesKey("wallpaper_local_image_v1")])
            }
        }

    @Test fun storedWallpaperAliasesAndUnknownsResolveToTheNewCollection() {
        val expected =
            mapOf(
                "MOTD" to ChatWallpaperPreset.MOTD,
                "CHATTER" to ChatWallpaperPreset.RETRO_CHAT,
                "CHANNELS" to ChatWallpaperPreset.RETRO_CHAT,
                "TERMINAL" to ChatWallpaperPreset.MOTD,
                "NIGHT_SHIFT" to ChatWallpaperPreset.MOTD,
                "RELAY" to ChatWallpaperPreset.INTERNET_ODDITIES,
                "SIGNALS" to ChatWallpaperPreset.RADIO_CLUB,
                "PIXELS" to ChatWallpaperPreset.RETRO_GAMING,
                "NONE" to ChatWallpaperPreset.NONE,
                "MEMES" to ChatWallpaperPreset.MEMES,
                "LINUX" to ChatWallpaperPreset.LINUX,
                "SYSTEMCRAFTERS" to ChatWallpaperPreset.SYSTEMCRAFTERS,
            )
        expected.forEach { (stored, preset) -> assertEquals(stored, preset, chatWallpaperPresetFromStored(stored)) }
        assertEquals(ChatWallpaperPreset.MOTD, chatWallpaperPresetFromStored(null))
        assertEquals(ChatWallpaperPreset.MOTD, chatWallpaperPresetFromStored("future-wallpaper"))
    }

    @Test fun wallpaperJsonDecodesLegacyNamesAndWritesCurrentNames() {
        assertEquals(
            WallpaperSelection(ChatWallpaperPreset.RETRO_CHAT, 73),
            Json.decodeFromString<WallpaperSelection>("""{"preset":"CHATTER","intensity":73}"""),
        )
        assertEquals(
            """{"preset":"RETRO_CHAT","intensity":73,"outlineColor":null,"scenicFollowSystem":true,"scenicAnimationEnabled":true}""",
            jsonWithDefaults.encodeToString(WallpaperSelection(ChatWallpaperPreset.RETRO_CHAT, 73, "private-wallpaper.image")),
        )
        assertEquals(
            WallpaperSelection(ChatWallpaperPreset.LINUX, 73),
            Json.decodeFromString<WallpaperSelection>("""{"preset":"LINUX","intensity":73}"""),
        )
        assertEquals(
            """{"preset":"LINUX","intensity":73,"outlineColor":null,"scenicFollowSystem":true,"scenicAnimationEnabled":true}""",
            jsonWithDefaults.encodeToString(WallpaperSelection(ChatWallpaperPreset.LINUX, 73)),
        )
        assertEquals(
            WallpaperSelection(ChatWallpaperPreset.SYSTEMCRAFTERS, 73),
            Json.decodeFromString<WallpaperSelection>("""{"preset":"SYSTEMCRAFTERS","intensity":73}"""),
        )
        assertEquals(
            """{"preset":"SYSTEMCRAFTERS","intensity":73,"outlineColor":null,"scenicFollowSystem":true,"scenicAnimationEnabled":true}""",
            jsonWithDefaults.encodeToString(WallpaperSelection(ChatWallpaperPreset.SYSTEMCRAFTERS, 73)),
        )
        for (color in listOf(0xffffffff.toInt(), 0xff000000.toInt(), 0xff12ab34.toInt())) {
            val selection = WallpaperSelection(ChatWallpaperPreset.RETRO_CHAT, 73, outlineColor = color)
            assertEquals(selection, Json.decodeFromString<WallpaperSelection>(jsonWithDefaults.encodeToString(selection)))
        }
        for (
        preset in
        listOf(
            ChatWallpaperPreset.JAPANESE_COUNTRYSIDE,
            ChatWallpaperPreset.RAINY_CAFE,
            ChatWallpaperPreset.MOTD_ARCADE,
            ChatWallpaperPreset.MIDNIGHT_TERMINAL,
            ChatWallpaperPreset.CYBERPUNK_CANAL,
        )
        ) {
            val selection = WallpaperSelection(preset, 73, scenicFollowSystem = false, scenicAnimationEnabled = false)
            assertEquals(selection, Json.decodeFromString<WallpaperSelection>(jsonWithDefaults.encodeToString(selection)))
        }
    }

    @Test fun fontScales_areIndependentRoundedAndClamped() =
        runTest {
            prefs.setUiFontScale(83)
            prefs.setConversationFontScale(999)
            assertEquals(85, prefs.config.first().uiFontScalePercent)
            assertEquals(140, prefs.config.first().conversationFontScalePercent)

            prefs.setUiFontScale(-20)
            prefs.setConversationFontScale(117)
            assertEquals(80, prefs.config.first().uiFontScalePercent)
            assertEquals(115, prefs.config.first().conversationFontScalePercent)

            prefs.setUiFontScale(DEFAULT_FONT_SCALE_PERCENT)
            prefs.setConversationFontScale(DEFAULT_FONT_SCALE_PERCENT)
            assertEquals(DEFAULT_FONT_SCALE_PERCENT, prefs.config.first().uiFontScalePercent)
            assertEquals(DEFAULT_FONT_SCALE_PERCENT, prefs.config.first().conversationFontScalePercent)
        }
}
