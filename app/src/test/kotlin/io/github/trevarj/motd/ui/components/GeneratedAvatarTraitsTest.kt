package io.github.trevarj.motd.ui.components

import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.avatarStyleFromPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeneratedAvatarTraitsTest {
    @Test fun casemapped_nicks_keep_the_same_operator_sprite() {
        assertEquals(
            generatedAvatarTraits(GeneratedAvatarSubject.USER, "Alice"),
            generatedAvatarTraits(GeneratedAvatarSubject.USER, "alice"),
        )
        assertEquals(
            generatedAvatarTraits(GeneratedAvatarSubject.USER, "foo{bar"),
            generatedAvatarTraits(GeneratedAvatarSubject.USER, "foo[bar"),
        )
    }

    @Test fun network_target_and_network_id_are_part_of_the_seed() {
        assertNotEquals(
            generatedAvatarTraits(GeneratedAvatarSubject.USER, "linux"),
            generatedAvatarTraits(GeneratedAvatarSubject.NETWORK, "linux", networkId = 7),
        )
        assertNotEquals(
            generatedAvatarTraits(GeneratedAvatarSubject.NETWORK, "Libera", networkId = 1),
            generatedAvatarTraits(GeneratedAvatarSubject.NETWORK, "Libera", networkId = 2),
        )
    }

    @Test fun project_hints_are_conservative_and_choose_the_longest_matching_alias() {
        assertEquals(ProjectMark.RUST, matchedProjectMark("rustacean"))
        assertEquals(ProjectMark.EMACS, matchedProjectMark("doomEmacs42"))
        assertEquals(ProjectMark.NEOVIM, matchedProjectMark("nvim-user"))
        assertEquals(ProjectMark.GO, matchedProjectMark("golang"))
        assertNull(matchedProjectMark("mango"))
        assertNull(matchedProjectMark("trusty"))
    }

    @Test fun developer_hints_use_brand_glyphs_or_a_technical_fallback() {
        assertEquals(FontAwesomeGlyph.RUST, ProjectMark.RUST.fontAwesomeGlyph())
        assertEquals(FontAwesomeGlyph.PYTHON, ProjectMark.PYTHON.fontAwesomeGlyph())
        assertEquals(FontAwesomeGlyph.GOLANG, ProjectMark.GO.fontAwesomeGlyph())
        assertEquals(FontAwesomeGlyph.GIT_ALT, ProjectMark.GIT.fontAwesomeGlyph())
        assertEquals(FontAwesomeGlyph.GITHUB, ProjectMark.GITHUB.fontAwesomeGlyph())
        assertEquals(FontAwesomeGlyph.DOCKER, ProjectMark.DOCKER.fontAwesomeGlyph())
        assertEquals(FontAwesomeGlyph.ANDROID, ProjectMark.ANDROID.fontAwesomeGlyph())
        assertNull(ProjectMark.EMACS.fontAwesomeGlyph())
        assertNull(ProjectMark.NEOVIM.fontAwesomeGlyph())
        assertNull(ProjectMark.NIXOS.fontAwesomeGlyph())
    }

    @Test fun all_size_tiers_preserve_a_legible_detail_budget() {
        assertEquals(AvatarDetail.MINI, AvatarDetail.forSize(20f.dp))
        assertEquals(AvatarDetail.STANDARD, AvatarDetail.forSize(24f.dp))
        assertEquals(AvatarDetail.STANDARD, AvatarDetail.forSize(31f.dp))
        assertEquals(AvatarDetail.FULL, AvatarDetail.forSize(32f.dp))
    }

    @Test fun sprite_style_is_default_without_overwriting_saved_choices() {
        assertEquals(AvatarStyle.IRC_SPRITE, Settings().avatarStyle)
        assertEquals(AvatarStyle.IRC_SPRITE, avatarStyleFromPreference(null))
        assertEquals(AvatarStyle.IRC_SPRITE, avatarStyleFromPreference("unknown"))
        assertEquals(AvatarStyle.MONOGRAM, avatarStyleFromPreference("MONOGRAM"))
        assertEquals(AvatarStyle.INITIALS, avatarStyleFromPreference("INITIALS"))
    }

    @Test fun network_badges_use_a_prominent_network_symbol() {
        assertNull(prominentAvatarGlyph(GeneratedAvatarSubject.USER))
        assertEquals(FontAwesomeGlyph.NETWORK, prominentAvatarGlyph(GeneratedAvatarSubject.NETWORK))
    }
}
