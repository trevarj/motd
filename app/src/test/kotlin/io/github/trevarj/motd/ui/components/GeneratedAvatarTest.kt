package io.github.trevarj.motd.ui.components

import io.github.trevarj.motd.avatar.ircSpriteV2Traits
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.avatarStyleFromPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedAvatarTest {
    @Test fun sprite_style_is_default_without_overwriting_saved_choices() {
        assertEquals(AvatarStyle.IRC_SPRITE, Settings().avatarStyle)
        assertEquals(AvatarStyle.IRC_SPRITE, avatarStyleFromPreference(null))
        assertEquals(AvatarStyle.IRC_SPRITE, avatarStyleFromPreference("unknown"))
        assertEquals(AvatarStyle.IRC_SPRITE, avatarStyleFromPreference("IRC_SPRITE"))
        assertEquals(AvatarStyle.IRC_SPRITE, avatarStyleFromPreference("IRC_SPRITE_V2"))
        assertEquals(AvatarStyle.MONOGRAM, avatarStyleFromPreference("MONOGRAM"))
        assertEquals(AvatarStyle.INITIALS, avatarStyleFromPreference("INITIALS"))
        assertEquals(AvatarStyle.NONE, avatarStyleFromPreference("NONE"))
    }

    @Test fun v2_traits_are_casemapped_independent_and_cover_each_catalog_axis() {
        assertEquals(ircSpriteV2Traits("Alice"), ircSpriteV2Traits("alice"))
        assertEquals(ircSpriteV2Traits("foo{bar"), ircSpriteV2Traits("foo[bar"))

        val traits = (0..400).map { ircSpriteV2Traits("operator-$it") }
        assertEquals((0 until 3).toSet(), traits.map { it.body }.toSet())
        assertEquals((0 until 8).toSet(), traits.map { it.head }.toSet())
        assertEquals((0 until 7).toSet(), traits.map { it.face }.toSet())
        assertEquals((0 until 10).toSet(), traits.map { it.accessory }.toSet())
        assertTrue(traits.zipWithNext().any { (first, second) -> first.body == second.body && first.head != second.head })
    }
}
