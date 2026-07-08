package io.github.trevarj.motd.ui.channelinfo

import io.github.trevarj.motd.data.db.MemberEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberSectioningTest {

    private fun member(nick: String, prefixes: String = "") =
        MemberEntity(bufferId = 1, nick = nick, prefixes = prefixes)

    @Test
    fun `groups by highest prefix in default order`() {
        val members = listOf(
            member("op", "@"),
            member("voice", "+"),
            member("regular"),
            member("owner", "~"),
            member("halfop", "%"),
        )
        val sections = sectionMembers(members)
        assertEquals(listOf('~', '@', '%', '+', null), sections.map { it.prefix })
        assertEquals("owner", sections[0].members.single().nick)
        assertEquals("regular", sections.last().members.single().nick)
    }

    @Test
    fun `member with multiple prefixes sections under highest`() {
        val sections = sectionMembers(listOf(member("boss", "@+")))
        assertEquals(listOf('@'), sections.map { it.prefix })
        assertEquals("boss", sections.single().members.single().nick)
    }

    @Test
    fun `within a section nicks sort case-insensitively`() {
        val sections = sectionMembers(
            listOf(member("Zed"), member("alice"), member("Bob")),
        )
        assertEquals(1, sections.size)
        assertNull(sections.single().prefix)
        assertEquals(listOf("alice", "Bob", "Zed"), sections.single().members.map { it.nick })
    }

    @Test
    fun `empty members yields no sections`() {
        assertEquals(emptyList<MemberSection>(), sectionMembers(emptyList()))
    }

    @Test
    fun `only regular members yields single null section`() {
        val sections = sectionMembers(listOf(member("a"), member("b")))
        assertEquals(listOf<Char?>(null), sections.map { it.prefix })
    }

    @Test
    fun `unknown prefix glyph falls into regular section`() {
        // "!" is not in the default order -> treated as unprefixed.
        val sections = sectionMembers(listOf(member("weird", "!")))
        assertEquals(listOf<Char?>(null), sections.map { it.prefix })
    }

    @Test
    fun `custom prefix order respected`() {
        // Network where '+' outranks '@' (contrived) — order drives sectioning.
        val sections = sectionMembers(
            listOf(member("op", "@"), member("voice", "+")),
            prefixOrder = "+@",
        )
        assertEquals(listOf('+', '@'), sections.map { it.prefix })
    }

    @Test
    fun `empty prefix order falls back to default`() {
        val sections = sectionMembers(listOf(member("op", "@")), prefixOrder = "")
        assertEquals(listOf('@'), sections.map { it.prefix })
    }

    @Test
    fun `prefixOrderFrom maps isupport prefix modes to glyph order`() {
        val order = prefixOrderFrom(listOf('o' to '@', 'v' to '+'))
        assertEquals("@+", order)
    }

    @Test
    fun `prefixOrderFrom empty yields default`() {
        assertEquals(DEFAULT_PREFIX_ORDER, prefixOrderFrom(emptyList()))
    }

    // -- sectionMembersSocial (Round 4, plans/13 §3.6) --

    @Test
    fun `sectionMembersSocial with empty fools reproduces plain sectioning`() {
        val members = listOf(member("op", "@"), member("alice"), member("bob"))
        val social = sectionMembersSocial(members, fools = emptySet())
        assertEquals(sectionMembers(members), social.sections)
        assertEquals(emptyList<MemberEntity>(), social.fools)
    }

    @Test
    fun `fools are extracted from prefix sections into their own bucket`() {
        val members = listOf(member("op", "@"), member("alice"), member("troll"))
        val social = sectionMembersSocial(members, fools = setOf("troll"))
        // troll pulled out; remaining members sectioned normally.
        assertEquals(listOf("troll"), social.fools.map { it.nick })
        val allSectioned = social.sections.flatMap { it.members }.map { it.nick }
        assertEquals(setOf("op", "alice"), allSectioned.toSet())
        assertTrue(allSectioned.none { it == "troll" })
    }

    @Test
    fun `fool extraction is case-insensitive and sorts fools`() {
        val members = listOf(member("Zed"), member("Ann"))
        val social = sectionMembersSocial(members, fools = setOf("zed", "ann"))
        assertEquals(listOf("Ann", "Zed"), social.fools.map { it.nick })
        assertEquals(emptyList<MemberSection>(), social.sections)
    }

    @Test
    fun `friend membership does not move a member between sections`() {
        // Friends are not passed to sectioning; sections are identical to no-friends case.
        val members = listOf(member("op", "@"), member("alice"))
        val social = sectionMembersSocial(members, fools = emptySet())
        assertEquals(sectionMembers(members), social.sections)
    }
}
