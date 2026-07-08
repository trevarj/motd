package io.github.trevarj.motd.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-4 nick-set / hue-override JSON codecs + normalizeNick (pure, no DataStore). */
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
    fun hueOverrides_roundTrips() {
        val map = mapOf("alice" to 0, "bob" to 210, "carol" to 359)
        assertEquals(map, decodeHueOverrides(encodeHueOverrides(map)))
    }

    @Test
    fun hueOverrides_nullAndGarbage_giveEmpty() {
        assertEquals(emptyMap<String, Int>(), decodeHueOverrides(null))
        assertEquals(emptyMap<String, Int>(), decodeHueOverrides("[]")) // array, not object
        assertEquals(emptyMap<String, Int>(), decodeHueOverrides("garbage"))
    }

    @Test
    fun hueOverrides_coerceIntoRange() {
        // Encoding clamps; decoding also clamps so stray out-of-range JSON is tolerated.
        val encoded = encodeHueOverrides(mapOf("a" to 400, "b" to -30, "c" to 180))
        val decoded = decodeHueOverrides(encoded)
        assertEquals(359, decoded["a"])
        assertEquals(0, decoded["b"])
        assertEquals(180, decoded["c"])

        // Direct decode of hand-written out-of-range hues also coerces.
        val raw = decodeHueOverrides("{\"x\":500,\"y\":-5}")
        assertEquals(359, raw["x"])
        assertEquals(0, raw["y"])
    }

    @Test
    fun normalizeNick_trimsAndLowercases() {
        assertEquals("alice", normalizeNick("  Alice  "))
        assertEquals("bob", normalizeNick("BOB"))
        assertTrue(normalizeNick("Alice") == normalizeNick("alice"))
    }
}
