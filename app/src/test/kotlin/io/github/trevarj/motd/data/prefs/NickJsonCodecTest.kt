package io.github.trevarj.motd.data.prefs

import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nick list and color override JSON codecs + normalizeNick (pure, no DataStore). */
class NickJsonCodecTest {
    @Test
    fun nickSet_roundTrips() {
        val set = setOf("alice", "bob", "carol")
        assertEquals(set, decodeNickSet(encodeNickSet(set)))
    }

    @Test
    fun nickSet_emptyRoundTrips() {
        assertEquals(emptySet<String>(), decodeNickSet(encodeNickSet(emptySet())))
    }

    @Test
    fun nickSet_nullAndGarbage_giveEmpty() {
        assertEquals(emptySet<String>(), decodeNickSet(null))
        assertEquals(emptySet<String>(), decodeNickSet("not json"))
        assertEquals(emptySet<String>(), decodeNickSet("{\"nope\":1}")) // object, not array
    }

    @Test
    fun nickColorOverrides_roundTripHuesAndExactColors() {
        val argb = 0xFF123456.toInt()
        val exact = NickColorOverride.fromArgb(argb)
        val map = mapOf("alice" to 0, "bob" to 210, "carol" to 359, "dave" to exact)
        val decoded = decodeNickColorOverrides(encodeNickColorOverrides(map))
        assertEquals(map, decoded)
        assertEquals(argb, decoded["dave"]?.let(NickColorOverride::toArgb))
    }

    @Test
    fun nickColorOverrides_nullAndGarbage_giveEmpty() {
        assertEquals(emptyMap<String, Int>(), decodeNickColorOverrides(null))
        assertEquals(emptyMap<String, Int>(), decodeNickColorOverrides("[]")) // array, not object
        assertEquals(emptyMap<String, Int>(), decodeNickColorOverrides("garbage"))
    }

    @Test
    fun nickColorOverrides_clampUntaggedValues() {
        // Encoding clamps; decoding also clamps so stray out-of-range JSON is tolerated.
        val encoded = encodeNickColorOverrides(mapOf("a" to 400, "b" to -30, "c" to 180))
        val decoded = decodeNickColorOverrides(encoded)
        assertEquals(359, decoded["a"])
        assertEquals(0, decoded["b"])
        assertEquals(180, decoded["c"])

        // Direct decode of hand-written out-of-range hues also coerces.
        val raw = decodeNickColorOverrides("{\"x\":500,\"y\":-5}")
        assertEquals(359, raw["x"])
        assertEquals(0, raw["y"])
    }

    @Test
    fun normalizeNick_trimsAndLowercases() {
        assertEquals("alice", normalizeNick("  Alice  "))
        assertEquals("bob", normalizeNick("BOB"))
        assertTrue(normalizeNick("Alice") == normalizeNick("alice"))
    }

    @Test
    fun configuredNicksAreComparedPerNetworkWithoutRewritingSerializedValues() {
        val configured = linkedSetOf("  Friend~  ", "[Helper]")
        val encoded = encodeNickSet(configured)
        assertEquals(configured, decodeNickSet(encoded))

        val rfc = IrcIdentityRules(caseMapping = IrcCaseMapping.Rfc1459)
        assertTrue(rfc.matchesConfiguredNick("friend^", configured))
        assertTrue(rfc.matchesConfiguredNick("{helper}", configured))

        val strict = IrcIdentityRules(caseMapping = IrcCaseMapping.Rfc1459Strict)
        assertTrue(strict.matchesConfiguredNick("FRIEND~", configured))
        assertTrue(!strict.matchesConfiguredNick("friend^", configured))
    }
}
