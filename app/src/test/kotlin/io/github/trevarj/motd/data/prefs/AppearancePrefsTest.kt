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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppearancePrefsTest {
    private val prefs: AppearancePrefs = AppearancePrefsImpl(ApplicationProvider.getApplicationContext<Context>())
    private val jsonWithDefaults = Json { encodeDefaults = true }

    @Test fun collapseTitleBarDefaultsOnAndPersistsInConfiguration() =
        runTest {
            val original = prefs.config.first().collapseTitleBar
            try {
                assertEquals(true, AppearanceConfig().collapseTitleBar)
                assertEquals(true, Json.decodeFromString<AppearanceConfig>("{}").collapseTitleBar)
                prefs.setCollapseTitleBar(false)
                val reloaded = AppearancePrefsImpl(ApplicationProvider.getApplicationContext<Context>()).config.first()
                assertEquals(false, reloaded.collapseTitleBar)
                assertEquals(false, Json.decodeFromString<AppearanceConfig>(jsonWithDefaults.encodeToString(reloaded)).collapseTitleBar)
                prefs.setCollapseTitleBar(true)
                assertEquals(true, prefs.config.first().collapseTitleBar)
            } finally {
                prefs.setCollapseTitleBar(original)
            }
        }

    @Test fun linkedGlobalEditsBroadcastOnlyTheirDimensionFromEveryEntryPoint() =
        runTest {
            val original = prefs.config.first()
            val mixed = AppearanceConfig(surfaceEffects = SurfaceEffects(surfaces = EffectSurface.entries.associateWith { SurfaceEffect(it.ordinal * 10, 100 - it.ordinal * 10) }), messageBackgroundOpacityPercent = 31, messageBackgroundBlur = 17)
            try {
                prefs.restoreGlobalEffects(mixed)
                prefs.setLinkGlobalOpacity(true)
                assertEquals(mixed.copy(linkGlobalOpacity = true).surfaceEffects, prefs.config.first().surfaceEffects)
                assertEquals(31, prefs.config.first().messageBackgroundOpacityPercent)
                for (surface in EffectSurface.entries) {
                    prefs.setSurfaceOpacity(surface, 42 + surface.ordinal)
                    val saved = prefs.config.first()
                    assertEquals(42 + surface.ordinal, saved.messageBackgroundOpacityPercent)
                    for (other in EffectSurface.entries) assertEquals(SurfaceEffect(42 + surface.ordinal, mixed.surfaceEffects[other].blurPercent), saved.surfaceEffects[other])
                }
                prefs.setMessageBackgroundOpacity(25)
                assertEquals(
                    List(8) { 25 },
                    EffectSurface.entries.map {
                        prefs.config
                            .first()
                            .surfaceEffects[it]
                            .opacityPercent
                    },
                )
                prefs.setLinkGlobalOpacity(false)
                prefs.setLinkGlobalBlur(true)
                for (surface in EffectSurface.entries) {
                    prefs.setSurfaceBlur(surface, surface.ordinal)
                    val saved = prefs.config.first()
                    assertEquals(surface.ordinal, saved.messageBackgroundBlur)
                    for (other in EffectSurface.entries) assertEquals(SurfaceEffect(25, surface.ordinal), saved.surfaceEffects[other])
                }
                prefs.setMessageBackgroundBlur(68)
                assertEquals(
                    List(8) { 68 },
                    EffectSurface.entries.map {
                        prefs.config
                            .first()
                            .surfaceEffects[it]
                            .blurPercent
                    },
                )
                prefs.setLinkGlobalBlur(false)
                prefs.setSurfaceOpacity(EffectSurface.MENU, 9)
                prefs.setSurfaceBlur(EffectSurface.MENU, 7)
                assertEquals(SurfaceEffect(9, 7), prefs.config.first().surfaceEffects[EffectSurface.MENU])
                assertEquals(SurfaceEffect(25, 68), prefs.config.first().surfaceEffects[EffectSurface.SHEET])
                prefs.setSurfaceEffectsEnabled(false)
                assertEquals(SurfaceEffect(9, 7), prefs.config.first().surfaceEffects[EffectSurface.MENU])
                prefs.setSurfaceEffectsEnabled(true)
                assertEquals(SurfaceEffect(9, 7), prefs.config.first().surfaceEffects[EffectSurface.MENU])
                val linkedMixed = mixed.copy(linkGlobalOpacity = true, linkGlobalBlur = true)
                prefs.restoreGlobalEffects(linkedMixed)
                val reloaded = AppearancePrefsImpl(ApplicationProvider.getApplicationContext<Context>()).config.first()
                assertEquals(linkedMixed.surfaceEffects, reloaded.surfaceEffects)
                assertEquals(31, reloaded.messageBackgroundOpacityPercent)
                assertEquals(17, reloaded.messageBackgroundBlur)
                assertEquals(true, reloaded.linkGlobalOpacity)
                assertEquals(true, reloaded.linkGlobalBlur)
                assertEquals(false, Json.decodeFromString<AppearanceConfig>("{}").linkGlobalOpacity)
                assertEquals(false, Json.decodeFromString<AppearanceConfig>("{}").linkGlobalBlur)
            } finally {
                prefs.restoreGlobalEffects(original)
            }
        }

    @Test fun surfaceEffectsPersistIndependentlyAndMasterPreservesSavedValues() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val original = prefs.config.first()
            try {
                val saved = SurfaceEffects(surfaces = EffectSurface.entries.associateWith { SurfaceEffect(it.ordinal * 10, 100 - it.ordinal * 10) })
                prefs.restoreGlobalEffects(original.copy(surfaceEffects = saved, linkGlobalOpacity = false, linkGlobalBlur = false))
                prefs.setMessageBackgroundOpacity(37)
                prefs.setMessageBackgroundBlur(63)
                assertEquals(saved, AppearancePrefsImpl(context).config.first().surfaceEffects)
                prefs.setSurfaceEffectsEnabled(false)
                val off = AppearancePrefsImpl(context).config.first()
                assertEquals(saved.surfaces, off.surfaceEffects.surfaces)
                assertEquals(37, off.messageBackgroundOpacityPercent)
                assertEquals(63, off.messageBackgroundBlur)
                prefs.setSurfaceEffectsEnabled(true)
                assertEquals(saved, prefs.config.first().surfaceEffects)
                assertEquals(SurfaceEffect(60, 100), Json.decodeFromString<AppearanceConfig>("""{}""").surfaceEffects[EffectSurface.CHAT_LIST])
                assertEquals(SurfaceEffect(), SurfaceEffects()[EffectSurface.SHEET])
                prefs.restoreGlobalEffects(prefs.config.first().copy(surfaceEffects = SurfaceEffects(surfaces = mapOf(EffectSurface.MENU to SurfaceEffect(-1, 101)))))
                assertEquals(SurfaceEffect(0, 100), prefs.config.first().surfaceEffects[EffectSurface.MENU])
            } finally {
                prefs.restoreGlobalEffects(original)
            }
        }

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
        assertEquals(LauncherIcon.MONO, AppearanceConfig().launcherIcon)
        assertEquals(SplashScreenAnimation.EDGE_TRACE, AppearanceConfig().splashScreenAnimation)
        assertEquals("", AppearanceConfig().customFontName)
    }

    @Test fun missingLauncherChoiceDefaultsToMonoButLegacyDefaultTokenStaysTeal() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val key = stringPreferencesKey("launcher_icon_v1")
            val original = context.appearanceDataStore.data.first()[key]
            try {
                context.appearanceDataStore.edit { it.remove(key) }
                assertEquals(LauncherIcon.MONO, prefs.config.first().launcherIcon)

                context.appearanceDataStore.edit { it[key] = "DEFAULT" }
                assertEquals(LauncherIcon.DEFAULT, AppearancePrefsImpl(context).config.first().launcherIcon)
            } finally {
                context.appearanceDataStore.edit { if (original == null) it.remove(key) else it[key] = original }
            }
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

    @Test fun emojiSetPersistsReloadsAndRejectsMalformedIdsWithoutChangingUiFont() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val key = stringPreferencesKey("emoji_set_id_v1")
            val keys = listOf(key, stringPreferencesKey("font_choice_v1"), stringPreferencesKey("custom_font_name_v1"))
            val original = context.appearanceDataStore.data.first()
            try {
                context.appearanceDataStore.edit { it.remove(key) }
                prefs.setFontChoice(FontChoice.CUSTOM)
                prefs.setCustomFontName("UI font.ttf")
                val baseline = AppearancePrefsImpl(context).config.first()
                assertEquals("system", AppearanceConfig().emojiSetId)
                assertEquals("system", baseline.emojiSetId)
                assertEquals("system", Json.decodeFromString<AppearanceConfig>("""{"fontChoice":"CUSTOM","customFontName":"UI font.ttf"}""").emojiSetId)
                assertEquals(FontChoice.CUSTOM, baseline.fontChoice)
                assertEquals("UI font.ttf", baseline.customFontName)

                for (id in listOf("noto", "openmoji", "future-pack_2", "a" + "0".repeat(63), "z", "system")) {
                    prefs.setEmojiSetId(id)
                    val reloaded = AppearancePrefsImpl(context).config.first()
                    assertEquals(id, context.appearanceDataStore.data.first()[key])
                    assertEquals(baseline.copy(emojiSetId = id), reloaded)
                    assertEquals(reloaded, Json.decodeFromString<AppearanceConfig>(jsonWithDefaults.encodeToString(reloaded)))
                }

                val malformed = listOf("", " ", "Noto", "1noto", "noto/color", "noto.color", "noto\n", "😀", "a".repeat(65))
                for (id in malformed) {
                    context.appearanceDataStore.edit { it[key] = id }
                    assertEquals(baseline, AppearancePrefsImpl(context).config.first())
                }

                prefs.setEmojiSetId("noto")
                for (id in malformed) {
                    val failure = runCatching { prefs.setEmojiSetId(id) }.exceptionOrNull()
                    assertTrue("Expected invalid emoji ID to be rejected: $id", failure is IllegalArgumentException)
                    assertEquals("noto", context.appearanceDataStore.data.first()[key])
                    assertEquals(baseline.copy(emojiSetId = "noto"), AppearancePrefsImpl(context).config.first())
                }
            } finally {
                context.appearanceDataStore.edit { stored ->
                    for (savedKey in keys) {
                        val value = original[savedKey]
                        if (value == null) stored.remove(savedKey) else stored[savedKey] = value
                    }
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

    @Test fun paletteReadabilityDefaultsOffAndPreservesSavedTextChoiceAcrossReload() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val policyKey = booleanPreferencesKey("adjust_palette_readability_v1")
            val textKey = booleanPreferencesKey("use_theme_text_colors_v1")
            val previous = context.appearanceDataStore.data.first()
            context.appearanceDataStore.edit { it.remove(policyKey) }
            try {
                assertEquals(false, AppearancePrefsImpl(context).config.first().adjustPaletteReadability)
                assertEquals(false, Json.decodeFromString<AppearanceConfig>("""{}""").adjustPaletteReadability)
                for (savedText in listOf(false, true)) {
                    prefs.setUseThemeTextColors(savedText)
                    for (adjust in listOf(true, false)) {
                        prefs.setAdjustPaletteReadability(adjust)
                        val reloaded = AppearancePrefsImpl(context).config.first()
                        assertEquals(adjust, reloaded.adjustPaletteReadability)
                        assertEquals(adjust, context.appearanceDataStore.data.first()[policyKey])
                        assertEquals(savedText, reloaded.useThemeTextColors)
                        assertEquals(adjust, Json.decodeFromString<AppearanceConfig>(jsonWithDefaults.encodeToString(reloaded)).adjustPaletteReadability)
                    }
                }
            } finally {
                context.appearanceDataStore.edit {
                    if (previous[policyKey] == null) it.remove(policyKey) else it[policyKey] = previous[policyKey]!!
                    if (previous[textKey] == null) it.remove(textKey) else it[textKey] = previous[textKey]!!
                }
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
            assertEquals(LauncherIcon.MONO, config.launcherIcon)
            assertEquals(SplashScreenAnimation.EDGE_TRACE, config.splashScreenAnimation)
        }

    @Test fun themeAndWallpaper_roundTrip() =
        runTest {
            prefs.selectPalette(PaletteSelection(light = PaletteChoice(ColorThemePreset.KANAGAWA_WAVE, null), dark = PaletteChoice(ColorThemePreset.KANAGAWA_WAVE, null), mode = PaletteMode.SYSTEM))
            prefs.setTrueBlack(true)
            prefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.INTERNET_ODDITIES, 73))
            assertEquals(ColorThemePreset.KANAGAWA_WAVE, prefs.config.first().theme)
            assertEquals(true, prefs.config.first().trueBlack)
            assertEquals(
                PaletteMode.SYSTEM,
                prefs.config
                    .first()
                    .paletteSelection.mode,
            )
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

    @Test fun legacyModesPreservePreviouslyDisplayedVariant() =
        runTest {
            withPaletteStore {
                val context = ApplicationProvider.getApplicationContext<Context>()
                for (theme in ColorThemePreset.entries) {
                    for (follow in listOf(false, true)) {
                        context.appearanceDataStore.edit {
                            it[stringPreferencesKey("theme_preset_v1")] = theme.name
                            it[booleanPreferencesKey("follow_system_v1")] = follow
                        }
                        val saved = prefs.config.first()
                        val expectedMode =
                            when {
                                theme == ColorThemePreset.SYSTEM -> PaletteMode.SYSTEM

                                follow && (theme.systemPartner != null || theme == ColorThemePreset.AMOLED) &&
                                    theme !in listOf(ColorThemePreset.DRACULA, ColorThemePreset.TOKYO_NIGHT) -> PaletteMode.SYSTEM

                                theme.isDark -> PaletteMode.DARK

                                else -> PaletteMode.LIGHT
                            }
                        assertEquals(theme.canonicalPalette, saved.paletteSelection.choiceFor(theme.isDark).builtin)
                        assertEquals(expectedMode, saved.paletteSelection.mode)
                        assertEquals(theme == ColorThemePreset.AMOLED, saved.trueBlack)
                    }
                }
                context.appearanceDataStore.edit { it[stringPreferencesKey("theme_preset_v1")] = "unknown" }
                assertEquals(PaletteSelection(light = PaletteChoice(ColorThemePreset.SYSTEM, null), dark = PaletteChoice(ColorThemePreset.SYSTEM, null), mode = PaletteMode.SYSTEM), prefs.config.first().paletteSelection)
            }
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

    @Test fun amoledSelectionNormalizesAndRetainsTrueBlack() =
        runTest {
            withPaletteStore {
                prefs.selectPalette(PaletteSelection(light = PaletteChoice(ColorThemePreset.AMOLED, null), dark = PaletteChoice(ColorThemePreset.AMOLED, null), mode = PaletteMode.DARK))
                assertEquals(ColorThemePreset.DARK, prefs.config.first().theme)
                assertEquals(true, prefs.config.first().trueBlack)
            }
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

    private val paletteKeys = listOf("theme_preset_v1", "palette_mode_v1", "custom_palette_id_v1", "custom_palettes_v1", "light_palette_v1", "dark_palette_v1").map(::stringPreferencesKey)
    private val legacyPaletteKeys = listOf("true_black_v1", "follow_system_v1").map(::booleanPreferencesKey)

    private suspend fun withPaletteStore(block: suspend () -> Unit) {
        val store = ApplicationProvider.getApplicationContext<Context>().appearanceDataStore
        val original = store.data.first()
        store.edit { values ->
            paletteKeys.forEach { values.remove(it) }
            legacyPaletteKeys.forEach { values.remove(it) }
        }
        try {
            block()
        } finally {
            store.edit { values ->
                paletteKeys.forEach { key -> original[key]?.let { values[key] = it } ?: values.remove(key) }
                legacyPaletteKeys.forEach { key -> original[key]?.let { values[key] = it } ?: values.remove(key) }
            }
        }
    }

    private fun palette(
        id: String = "local",
        paired: Boolean = true,
    ): InstalledPalette {
        val colors = PaletteColors("#aabbcc", "#112233", "#445566", "#111111", "#222222", "#333333", "#eeeeee", "#ffffff", "#bbbbbb", "#777777")
        return InstalledPalette(PaletteDocument(id, "  Local  ", 1, light = if (paired) colors else null, dark = colors))
    }

    private suspend fun rejectedWithoutMutation(block: suspend () -> Unit) {
        val store = ApplicationProvider.getApplicationContext<Context>().appearanceDataStore
        val before = store.data.first()
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Operation must reject", rejected)
        assertEquals(before, store.data.first())
    }

    @Test fun pairedFamiliesAndSingleModeSelectionsResolveWithoutDynamicIdentityConfusion() {
        for (builtin in ColorThemePreset.entries) {
            val selected = AppearanceConfig(theme = builtin, followSystem = true).paletteSelection
            for (dark in listOf(false, true)) {
                val choice = selected.choiceFor(dark)
                assertTrue(choice.supportsMode(dark))
                if (builtin.canonicalPalette.systemPartner != null || builtin == ColorThemePreset.SYSTEM || builtin.isDark == dark) {
                    assertEquals(builtin.canonicalPalette, choice.builtin)
                } else {
                    assertEquals(PaletteChoice(), choice)
                }
            }
        }
        val pair = PaletteSelection(PaletteChoice(ColorThemePreset.AYU_DARK), PaletteChoice(ColorThemePreset.MONOKAI))
        for (mode in PaletteMode.entries) {
            val selected = normalizePaletteSelection(pair.copy(mode = mode), emptyList())
            assertEquals(pair.light, selected.choiceFor(false))
            assertEquals(pair.dark, selected.choiceFor(true))
            assertEquals(ColorThemePreset.AYU_LIGHT, resolvePalettePreset(selected.light.builtin, PaletteMode.LIGHT, true))
            assertEquals(ColorThemePreset.MONOKAI, resolvePalettePreset(selected.dark.builtin, PaletteMode.DARK, false))
        }
        val darkOnly = palette(paired = false)
        assertEquals(PaletteMode.DARK, AppearanceConfig(customPalettes = listOf(darkOnly), customPaletteId = "local", paletteMode = PaletteMode.LIGHT).paletteSelection.mode)
        assertThrows(IllegalArgumentException::class.java) {
            normalizePaletteSelection(pair.copy(light = PaletteChoice(ColorThemePreset.MONOKAI)), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizePaletteSelection(pair.copy(light = PaletteChoice(installedId = "local")), listOf(darkOnly))
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizePaletteSelection(pair.copy(dark = PaletteChoice(installedId = "missing")), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceConfig(lightPalette = PaletteChoice(ColorThemePreset.MONOKAI)).paletteSelection
        }
    }

    @Test fun legacyCustomModesMigrateAndAssignmentsPreserveOtherSlotAndDisplayMode() =
        runTest {
            withPaletteStore {
                val store = ApplicationProvider.getApplicationContext<Context>().appearanceDataStore
                for (document in listOf(palette(), palette(paired = false), palette().let { it.copy(document = it.document.copy(dark = null)) })) {
                    store.edit {
                        it[stringPreferencesKey("custom_palettes_v1")] = jsonWithDefaults.encodeToString(listOf(document))
                        it[stringPreferencesKey("custom_palette_id_v1")] = "local"
                        it[stringPreferencesKey("palette_mode_v1")] = PaletteMode.SYSTEM.name
                        it.remove(stringPreferencesKey("light_palette_v1"))
                        it.remove(stringPreferencesKey("dark_palette_v1"))
                    }
                    val migrated = prefs.config.first().paletteSelection
                    val expected =
                        PaletteSelection(
                            light = if (document.document.light != null) PaletteChoice(installedId = "local") else PaletteChoice(),
                            dark = if (document.document.dark != null) PaletteChoice(installedId = "local") else PaletteChoice(),
                            mode =
                                if (document.document.light == null) {
                                    PaletteMode.DARK
                                } else if (document.document.dark == null) {
                                    PaletteMode.LIGHT
                                } else {
                                    PaletteMode.SYSTEM
                                },
                        )
                    assertEquals(expected, migrated)
                    val backup = AppearanceConfig(customPalettes = listOf(document), customPaletteId = "local", paletteMode = PaletteMode.SYSTEM)
                    assertEquals(expected, Json.decodeFromString<AppearanceConfig>(jsonWithDefaults.encodeToString(backup)).paletteSelection)
                    prefs.selectPalette(migrated)
                    assertEquals(expected, AppearancePrefsImpl(ApplicationProvider.getApplicationContext<Context>()).config.first().paletteSelection)
                }
                val darkOnly = palette(paired = false)
                val base = PaletteSelection(light = PaletteChoice(ColorThemePreset.AYU_DARK), mode = PaletteMode.LIGHT)
                val assigned = base.withChoice(PaletteChoice(installedId = "local"), PaletteMode.SYSTEM, listOf(darkOnly))
                assertEquals(base.light, assigned.light)
                assertEquals(base.mode, assigned.mode)
                assertTrue(assigned.usesInstalled("local"))
                assertEquals(PaletteChoice(installedId = "local"), assigned.dark)
                assertEquals(base.dark, base.withChoice(PaletteChoice(ColorThemePreset.DRACULA), PaletteMode.LIGHT).dark)
            }
        }

    @Test fun unsupportedExplicitSlotsRejectAtomicallyAndUpdatesRepairOnlyLostMode() =
        runTest {
            withPaletteStore {
                val local = palette()
                val custom = PaletteChoice(installedId = "local")
                for (mode in PaletteMode.entries) {
                    for (lostDark in listOf(false, true)) {
                        val selected = PaletteSelection(custom, custom, mode)
                        prefs.restorePalettes(listOf(local), selected)
                        val single =
                            local.copy(
                                document =
                                    local.document.copy(
                                        light = local.document.light.takeUnless { !lostDark },
                                        dark = local.document.dark.takeUnless { lostDark },
                                    ),
                            )
                        prefs.savePalette(single, false, PaletteMode.SYSTEM)
                        val repaired = if (lostDark) selected.copy(dark = PaletteChoice()) else selected.copy(light = PaletteChoice())
                        assertEquals(repaired, prefs.config.first().paletteSelection)
                        rejectedWithoutMutation { prefs.selectPalette(selected) }
                        rejectedWithoutMutation { prefs.restorePalettes(listOf(single), selected) }
                        prefs.removePalette("local")
                        assertEquals(PaletteSelection(mode = mode), prefs.config.first().paletteSelection)
                    }
                }
                rejectedWithoutMutation { prefs.selectPalette(PaletteSelection(light = PaletteChoice(ColorThemePreset.MONOKAI))) }
            }
        }

    @Test fun corruptStoredSlotsFallBackIndependentlyWithoutRewritingRawPreferences() =
        runTest {
            withPaletteStore {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val store = context.appearanceDataStore
                for (dark in listOf(false, true)) {
                    val local =
                        palette().let {
                            it.copy(document = if (dark) it.document.copy(dark = null) else it.document.copy(light = null))
                        }
                    val selected =
                        PaletteSelection(
                            light = PaletteChoice(ColorThemePreset.AYU_DARK),
                            dark = PaletteChoice(ColorThemePreset.DRACULA),
                            mode = PaletteMode.DARK,
                        )
                    prefs.restorePalettes(listOf(local), selected)
                    val key = stringPreferencesKey(if (dark) "dark_palette_v1" else "light_palette_v1")
                    for (raw in listOf(
                        "not-json",
                        "null",
                        """{"builtin":"UNKNOWN"}""",
                        """{"installedId":123}""",
                        """{"installedId":"missing"}""",
                        """{"installedId":"local"}""",
                    )) {
                        store.edit { it[key] = raw }
                        val before = store.data.first()
                        val expected = if (dark) selected.copy(dark = PaletteChoice()) else selected.copy(light = PaletteChoice())
                        assertEquals(expected, AppearancePrefsImpl(context).config.first().paletteSelection)
                        assertEquals(expected, prefs.config.first().paletteSelection)
                        assertEquals(before, store.data.first())
                    }
                    prefs.savePalette(palette("other"), false, PaletteMode.SYSTEM)
                    assertEquals(
                        selected.mode,
                        prefs.config
                            .first()
                            .paletteSelection.mode,
                    )
                }
            }
        }

    @Test fun saveReloadEditRemoveAndSelectionAreAtomic() =
        runTest {
            withPaletteStore {
                val context = ApplicationProvider.getApplicationContext<Context>()
                prefs.setTrueBlack(true)
                val local = palette()
                prefs.savePalette(local, apply = false, mode = PaletteMode.LIGHT)
                assertEquals(PaletteSelection(), prefs.config.first().paletteSelection)
                val pair =
                    PaletteSelection(
                        light = PaletteChoice(ColorThemePreset.AYU_DARK),
                        dark = PaletteChoice(installedId = "local"),
                        mode = PaletteMode.LIGHT,
                    )
                prefs.selectPalette(pair)
                val reloaded = AppearancePrefsImpl(context).config.first()
                assertEquals(pair, reloaded.paletteSelection)
                assertEquals("Local", reloaded.customPalette(pair.dark)?.name)
                assertEquals("#AABBCC", reloaded.customPalette(pair.dark)?.dark?.primary)
                assertEquals(pair, Json.decodeFromString<AppearanceConfig>(jsonWithDefaults.encodeToString(reloaded)).paletteSelection)
                prefs.savePalette(local, true, PaletteMode.LIGHT)
                assertEquals(pair.copy(light = pair.dark), prefs.config.first().paletteSelection)
                val edited = local.copy(document = prepareLocalPaletteSave(local.document, local.document.copy(light = null)))
                prefs.savePalette(edited, apply = false, mode = PaletteMode.SYSTEM)
                assertEquals(pair.copy(light = PaletteChoice()), prefs.config.first().paletteSelection)
                assertEquals(
                    2,
                    prefs.config
                        .first()
                        .customPalette(pair.dark)
                        ?.revision,
                )
                prefs.savePalette(edited, apply = false, mode = PaletteMode.SYSTEM)
                assertEquals(
                    2,
                    prefs.config
                        .first()
                        .customPalette(pair.dark)
                        ?.revision,
                )
                rejectedWithoutMutation { prefs.savePalette(edited, true, PaletteMode.LIGHT) }
                prefs.selectPalette(pair)
                prefs.savePalette(palette("other"), true, PaletteMode.LIGHT)
                val mixed = pair.copy(light = PaletteChoice(installedId = "other"))
                assertEquals(mixed, prefs.config.first().paletteSelection)
                prefs.removePalette("local")
                assertEquals(mixed.copy(dark = PaletteChoice()), AppearancePrefsImpl(context).config.first().paletteSelection)
                prefs.removePalette("other")
                assertEquals(PaletteSelection(mode = PaletteMode.LIGHT), prefs.config.first().paletteSelection)
                assertEquals(true, prefs.config.first().trueBlack)
                prefs.savePalette(edited, true, PaletteMode.SYSTEM)
                assertEquals(pair.copy(light = PaletteChoice()), prefs.config.first().paletteSelection)
            }
        }

    @Test fun corruptStorageIsVisiblePreservedAndOnlyExplicitRestoreRecovers() =
        runTest {
            withPaletteStore {
                val store = ApplicationProvider.getApplicationContext<Context>().appearanceDataStore
                val key = stringPreferencesKey("custom_palettes_v1")
                for ((raw, error) in listOf(
                    "not-json" to PaletteReadError.INVALID,
                    "{}" to PaletteReadError.INVALID,
                    " ".repeat(MAX_INSTALLED_PALETTE_BYTES + 1) to PaletteReadError.TOO_LARGE,
                    jsonWithDefaults.encodeToString(List(65) { palette("p-$it") }) to PaletteReadError.TOO_LARGE,
                    jsonWithDefaults.encodeToString(listOf(palette(), palette())) to PaletteReadError.INVALID,
                    jsonWithDefaults.encodeToString(listOf(palette())).replace("\"revision\":1", "\"revision\":true") to PaletteReadError.INVALID,
                    jsonWithDefaults.encodeToString(listOf(palette())).replace("\"format\":\"motd-palette\",", "") to PaletteReadError.INVALID,
                )) {
                    store.edit {
                        it.remove(stringPreferencesKey("light_palette_v1"))
                        it.remove(stringPreferencesKey("dark_palette_v1"))
                        it[key] = raw
                        it[stringPreferencesKey("custom_palette_id_v1")] = "local"
                    }
                    val config = prefs.config.first()
                    assertEquals(error, config.customPaletteReadError)
                    assertEquals(emptyList<InstalledPalette>(), config.customPalettes)
                    assertEquals(PaletteSelection(light = PaletteChoice(ColorThemePreset.SYSTEM, null), dark = PaletteChoice(ColorThemePreset.SYSTEM, null), mode = PaletteMode.SYSTEM), config.paletteSelection)
                    rejectedWithoutMutation { prefs.savePalette(palette(), false, PaletteMode.DARK) }
                    rejectedWithoutMutation { prefs.removePalette("local") }
                    rejectedWithoutMutation { prefs.selectPalette(PaletteSelection(light = PaletteChoice(ColorThemePreset.SYSTEM, "local"), dark = PaletteChoice(ColorThemePreset.SYSTEM, "local"), mode = PaletteMode.DARK)) }
                    prefs.selectPalette(PaletteSelection(light = PaletteChoice(ColorThemePreset.NORD, null), dark = PaletteChoice(ColorThemePreset.NORD, null), mode = PaletteMode.LIGHT))
                    assertEquals(
                        ColorThemePreset.NORD,
                        prefs.config
                            .first()
                            .paletteSelection.light.builtin,
                    )
                    assertEquals(raw, store.data.first()[key])
                    assertEquals(error, prefs.config.first().customPaletteReadError)
                    assertEquals(null, Json.decodeFromString<AppearanceConfig>(jsonWithDefaults.encodeToString(config)).customPaletteReadError)
                    prefs.restorePalettes(listOf(palette()), PaletteSelection(light = PaletteChoice(ColorThemePreset.SYSTEM, "local"), dark = PaletteChoice(ColorThemePreset.SYSTEM, "local"), mode = PaletteMode.LIGHT))
                    assertEquals(null, prefs.config.first().customPaletteReadError)
                    assertEquals(
                        "local",
                        prefs.config
                            .first()
                            .paletteSelection.dark.installedId,
                    )
                }
                store.edit {
                    it.remove(stringPreferencesKey("light_palette_v1"))
                    it.remove(stringPreferencesKey("dark_palette_v1"))
                    it[stringPreferencesKey("custom_palette_id_v1")] = "missing"
                }
                assertEquals(PaletteSelection(light = PaletteChoice(ColorThemePreset.SYSTEM, null), dark = PaletteChoice(ColorThemePreset.SYSTEM, null), mode = PaletteMode.SYSTEM), prefs.config.first().paletteSelection)
            }
        }

    @Test fun limitsTrackingAndReferencesRejectBeforeAnyMutation() =
        runTest {
            withPaletteStore {
                val selection = PaletteSelection(light = PaletteChoice(ColorThemePreset.SYSTEM, null), dark = PaletteChoice(ColorThemePreset.SYSTEM, null), mode = PaletteMode.SYSTEM)
                prefs.restorePalettes(List(64) { palette("p-$it") }, selection)
                rejectedWithoutMutation { prefs.savePalette(palette(), true, PaletteMode.LIGHT) }
                rejectedWithoutMutation { prefs.restorePalettes(listOf(palette(), palette()), selection) }
                rejectedWithoutMutation { prefs.restorePalettes(emptyList(), selection.copy(dark = PaletteChoice(installedId = "missing"))) }
                rejectedWithoutMutation { prefs.restorePalettes(listOf(palette().copy(catalogId = "catalog")), selection) }
                rejectedWithoutMutation { prefs.restorePalettes(listOf(palette(paired = false).copy(catalogId = "catalog", catalogRevision = 1)), selection) }
                rejectedWithoutMutation { prefs.restorePalettes(listOf(palette().copy(catalogId = "catalog", catalogRevision = 2)), selection) }
                rejectedWithoutMutation { prefs.restorePalettes(listOf(palette().copy(sourceLink = "http://example.com/a")), selection) }
                val large =
                    List(64) { index ->
                        palette("p-$index").let { it.copy(document = it.document.copy(notices = "😀".repeat(2100))) }
                    }
                rejectedWithoutMutation { prefs.restorePalettes(large, selection) }
                prefs.restorePalettes(listOf(palette().copy(catalogId = "upstream", catalogRevision = 1)), selection.copy(dark = PaletteChoice(installedId = "local")))
                assertEquals(
                    "upstream",
                    prefs.config
                        .first()
                        .customPalettes
                        .single()
                        .catalogId,
                )
                prefs.savePalette(palette().copy(document = palette().document.copy(name = "Replacement")), true, PaletteMode.LIGHT)
                assertEquals(
                    null,
                    prefs.config
                        .first()
                        .customPalettes
                        .single()
                        .catalogId,
                )
                val maximum = palette().copy(document = palette().document.copy(revision = Int.MAX_VALUE))
                prefs.restorePalettes(listOf(maximum), selection)
                prefs.savePalette(maximum, false, PaletteMode.SYSTEM)
                prefs.savePalette(maximum.copy(document = maximum.document.copy(name = "Imported replacement", revision = 3)), false, PaletteMode.SYSTEM)
                assertEquals(
                    3,
                    prefs.config
                        .first()
                        .customPalettes
                        .single()
                        .document.revision,
                )
            }
        }

    @Test fun callerChosenForksAndConflictsKeepIdentityAndMetadataSeparate() =
        runTest {
            withPaletteStore {
                val original =
                    palette().copy(
                        document = palette().document.copy(license = "Original", notices = "Retain this"),
                        catalogId = "catalog-origin",
                        catalogRevision = 1,
                        sourceLink = "https://example.com/palette.motd-palette.json",
                    )
                prefs.savePalette(original, true, PaletteMode.SYSTEM)
                prefs.savePalette(original, false, PaletteMode.LIGHT)
                assertEquals(
                    1,
                    prefs.config
                        .first()
                        .customPalettes.size,
                )
                assertEquals(
                    PaletteMode.SYSTEM,
                    prefs.config
                        .first()
                        .paletteSelection.mode,
                )
                val fork = InstalledPalette(original.document.copy(id = "local-copy", name = "Local (copy)", revision = 1))
                prefs.savePalette(fork, false, PaletteMode.LIGHT)
                assertEquals(
                    2,
                    prefs.config
                        .first()
                        .customPalettes.size,
                )
                assertEquals(
                    "local",
                    prefs.config
                        .first()
                        .paletteSelection.dark.installedId,
                )
                assertEquals(
                    "Retain this",
                    prefs.config
                        .first()
                        .customPalettes
                        .last()
                        .document.notices,
                )
                assertEquals(
                    null,
                    prefs.config
                        .first()
                        .customPalettes
                        .last()
                        .catalogId,
                )
                assertEquals(
                    null,
                    prefs.config
                        .first()
                        .customPalettes
                        .last()
                        .sourceLink,
                )
                val updated = original.copy(document = original.document.copy(revision = 2, name = "Updated"), catalogRevision = 2)
                prefs.savePalette(updated, false, PaletteMode.DARK)
                assertEquals(
                    2,
                    prefs.config
                        .first()
                        .customPalettes.size,
                )
                assertEquals(
                    2,
                    prefs.config
                        .first()
                        .customPalette(
                            prefs.config
                                .first()
                                .paletteSelection.dark,
                        )?.revision,
                )
                assertEquals(
                    PaletteMode.SYSTEM,
                    prefs.config
                        .first()
                        .paletteSelection.mode,
                )
                val lightOnly = fork.copy(document = fork.document.copy(light = fork.document.dark, dark = null))
                rejectedWithoutMutation { prefs.savePalette(lightOnly, true, PaletteMode.DARK) }
                prefs.savePalette(lightOnly, true, PaletteMode.LIGHT)
                assertEquals(
                    PaletteMode.SYSTEM,
                    prefs.config
                        .first()
                        .paletteSelection.mode,
                )
                prefs.savePalette(fork, false, PaletteMode.SYSTEM)
                assertEquals(
                    PaletteMode.SYSTEM,
                    prefs.config
                        .first()
                        .paletteSelection.mode,
                )
            }
        }

    @Test fun explicitUpdateTargetMustStillExistInsideTransaction() =
        runTest {
            withPaletteStore {
                val installed = palette()
                rejectedWithoutMutation {
                    prefs.savePalette(installed, true, PaletteMode.LIGHT, targetInstalledId = "local")
                }
                prefs.savePalette(installed, true, PaletteMode.SYSTEM)
                rejectedWithoutMutation {
                    prefs.savePalette(installed, true, PaletteMode.LIGHT, targetInstalledId = "different")
                }
                prefs.savePalette(installed.copy(document = installed.document.copy(name = "Updated")), false, PaletteMode.DARK, targetInstalledId = "local")
                assertEquals(
                    "Updated",
                    prefs.config
                        .first()
                        .customPalette(
                            prefs.config
                                .first()
                                .paletteSelection.dark,
                        )?.name,
                )
                prefs.removePalette("local")
                rejectedWithoutMutation {
                    prefs.savePalette(installed, true, PaletteMode.LIGHT, targetInstalledId = "local")
                }
                assertEquals(emptyList<InstalledPalette>(), prefs.config.first().customPalettes)
                assertEquals(PaletteSelection(light = PaletteChoice(ColorThemePreset.SYSTEM, null), dark = PaletteChoice(ColorThemePreset.SYSTEM, null), mode = PaletteMode.SYSTEM), prefs.config.first().paletteSelection)
            }
        }

    @Test fun guardedCatalogUpdateRejectsChangedUntrackedOrAdvancedTargetWithoutMutation() =
        runTest {
            withPaletteStore {
                val original = palette().copy(catalogId = "upstream", catalogRevision = 1)
                prefs.savePalette(original, true, PaletteMode.LIGHT)
                val snapshot =
                    prefs.config
                        .first()
                        .customPalettes
                        .single()
                val update = snapshot.copy(document = snapshot.document.copy(revision = 2, name = "Update"), catalogRevision = 2)
                val selection = prefs.config.first().paletteSelection
                for (changed in listOf(
                    snapshot.copy(catalogId = null, catalogRevision = null),
                    snapshot.copy(catalogId = "other-catalog"),
                    snapshot.copy(document = snapshot.document.copy(revision = 3), catalogRevision = 3),
                    snapshot.copy(sourceLink = "https://example.com/changed.motd-palette.json"),
                )) {
                    prefs.restorePalettes(listOf(changed), selection)
                    val before = prefs.config.first()
                    rejectedWithoutMutation {
                        prefs.savePalette(update, true, PaletteMode.DARK, targetInstalledId = "local", expectedTarget = snapshot)
                    }
                    assertEquals(before, prefs.config.first())
                }
            }
        }

    @Test fun guardedCatalogUpdateMatchesOnceAndPreservesSelectionAndMode() =
        runTest {
            withPaletteStore {
                prefs.savePalette(palette().copy(catalogId = "upstream", catalogRevision = 1), true, PaletteMode.LIGHT)
                val before = prefs.config.first()
                val snapshot = before.customPalettes.single()
                val update = snapshot.copy(document = snapshot.document.copy(revision = 2, name = "Updated"), catalogRevision = 2)
                rejectedWithoutMutation {
                    prefs.savePalette(update, false, PaletteMode.DARK, expectedTarget = snapshot)
                }
                prefs.savePalette(update, false, PaletteMode.DARK, targetInstalledId = "local", expectedTarget = snapshot)
                assertEquals(listOf(update), prefs.config.first().customPalettes)
                assertEquals(before.paletteSelection, prefs.config.first().paletteSelection)
                val updated = prefs.config.first()
                rejectedWithoutMutation {
                    prefs.savePalette(update, false, PaletteMode.DARK, targetInstalledId = "local", expectedTarget = snapshot)
                }
                assertEquals(updated, prefs.config.first())
                prefs.removePalette("local")
                rejectedWithoutMutation {
                    prefs.savePalette(update, true, PaletteMode.DARK, targetInstalledId = "local", expectedTarget = snapshot)
                }
            }
        }

    @Test fun ordinaryGalleryReplaceCanReplaceUntrackedOrDifferentlyTrackedSameId() =
        runTest {
            withPaletteStore {
                val incoming =
                    palette().copy(
                        document = palette().document.copy(name = "Gallery replacement", revision = 2),
                        catalogId = "upstream",
                        catalogRevision = 2,
                    )
                val selection = PaletteSelection(light = PaletteChoice(installedId = "local"), dark = PaletteChoice(installedId = "local"), mode = PaletteMode.LIGHT)
                for (existing in listOf(palette(), palette().copy(catalogId = "other-catalog", catalogRevision = 1))) {
                    prefs.restorePalettes(listOf(existing), selection)
                    prefs.savePalette(incoming, false, PaletteMode.DARK, targetInstalledId = "local")
                    val actual = prefs.config.first()
                    assertEquals(
                        "Gallery replacement",
                        actual.customPalettes
                            .single()
                            .document.name,
                    )
                    assertEquals(
                        2,
                        actual.customPalettes
                            .single()
                            .document.revision,
                    )
                    assertEquals("upstream", actual.customPalettes.single().catalogId)
                    assertEquals(2, actual.customPalettes.single().catalogRevision)
                    assertEquals(selection, actual.paletteSelection)
                }
            }
        }

    @Test fun identicalUntrackedGalleryInstallGainsTrackingWithoutDuplicatingOrChangingSelection() =
        runTest {
            withPaletteStore {
                prefs.savePalette(palette(), true, PaletteMode.LIGHT)
                val before = prefs.config.first()
                val original = before.customPalettes.single()
                val tracked = original.copy(catalogId = "upstream", catalogRevision = original.document.revision)
                prefs.savePalette(tracked, false, PaletteMode.DARK, targetInstalledId = original.document.id)
                assertEquals(listOf(tracked), prefs.config.first().customPalettes)
                assertEquals(before.paletteSelection, prefs.config.first().paletteSelection)
            }
        }

    @Test fun localEditorRevisionChangesOnceAndImportsRetainSnapshotRevision() {
        val original = palette().document.copy(license = "Original license", notices = "Retain notices")
        val canonical = decodePalette(encodePalette(original))
        assertEquals(canonical, prepareLocalPaletteSave(original, canonical.copy(revision = 99)))
        val changed = prepareLocalPaletteSave(original, original.copy(name = "Changed", revision = 99))
        assertEquals(2, changed.revision)
        assertEquals(original.license, changed.license)
        assertEquals(original.notices, changed.notices)
        assertEquals(changed, prepareLocalPaletteSave(changed, changed))
        val maximum = original.copy(revision = Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, prepareLocalPaletteSave(maximum, maximum).revision)
        assertThrows(IllegalArgumentException::class.java) {
            prepareLocalPaletteSave(maximum, maximum.copy(name = "Changed"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            prepareLocalPaletteSave(original, original.copy(id = "other"))
        }
    }
}
