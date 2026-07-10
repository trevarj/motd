package io.github.trevarj.motd.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pixel-art identicon generator (pure functions, no Android deps). */
class NickPixelArtTest {

    // -- Determinism --

    @Test
    fun grid_isDeterministic_samNick() {
        val a = nickPixelGrid("alice")
        val b = nickPixelGrid("alice")
        assertTrue("Same nick must produce identical grid", a.contentEquals(b))
    }

    @Test
    fun grid_isDeterministic_multipleNicks() {
        val nicks = listOf("bob", "Carol", "dave|away", "#libera", "z_z_z", "x")
        for (nick in nicks) {
            val g1 = nickPixelGrid(nick)
            val g2 = nickPixelGrid(nick)
            assertTrue("Grid must be identical across calls for '$nick'", g1.contentEquals(g2))
        }
    }

    @Test
    fun grid_caseInsensitive() {
        // Same visual nick should produce the same grid (normalised to lowercase internally).
        val lower = nickPixelGrid("alice")
        val upper = nickPixelGrid("ALICE")
        val mixed = nickPixelGrid("AlIcE")
        assertTrue(lower.contentEquals(upper))
        assertTrue(lower.contentEquals(mixed))
    }

    @Test
    fun grid_differentNicks_differentGrids() {
        // Very likely distinct grids for different nicks (not a 100% guarantee, but should hold
        // for common names whose hashes differ widely).
        val nicks = listOf("alice", "bob", "carol", "dave", "eve", "frank")
        val grids = nicks.map { nickPixelGrid(it) }
        for (i in grids.indices) {
            for (j in (i + 1) until grids.size) {
                assertFalse(
                    "Expected different grids for '${nicks[i]}' and '${nicks[j]}'",
                    grids[i].contentEquals(grids[j]),
                )
            }
        }
    }

    // -- Symmetry --

    @Test
    fun grid_isHorizontallySymmetric() {
        // Column 0 must mirror column 4, column 1 must mirror column 3.
        val nicks = listOf("alice", "bob", "carol_", "#chan", "zz", "a")
        for (nick in nicks) {
            val grid = nickPixelGrid(nick)
            for (row in 0 until NICK_GRID_ROWS) {
                assertEquals(
                    "Row $row col 0 must mirror col 4 for nick '$nick'",
                    grid[row * NICK_GRID_COLS + 0],
                    grid[row * NICK_GRID_COLS + 4],
                )
                assertEquals(
                    "Row $row col 1 must mirror col 3 for nick '$nick'",
                    grid[row * NICK_GRID_COLS + 1],
                    grid[row * NICK_GRID_COLS + 3],
                )
            }
        }
    }

    @Test
    fun grid_hasCorrectSize() {
        val grid = nickPixelGrid("alice")
        assertEquals(NICK_GRID_ROWS * NICK_GRID_COLS, grid.size)
    }

    @Test
    fun grid_hasCorrectDimensions() {
        assertEquals(5, NICK_GRID_ROWS)
        assertEquals(5, NICK_GRID_COLS)
    }

    // -- Seed stability --

    @Test
    fun seed_isDeterministicAndCaseInsensitive() {
        assertEquals(nickGridSeed("alice"), nickGridSeed("Alice"))
        assertEquals(nickGridSeed("alice"), nickGridSeed("ALICE"))
    }

    @Test
    fun seed_differsBetweenNicks() {
        // Seeds may collide in rare cases, but common names should differ.
        assertNotEquals(nickGridSeed("alice"), nickGridSeed("bob"))
        assertNotEquals(nickGridSeed("carol"), nickGridSeed("dave"))
    }

    @Test
    fun grid_notAllSameValue() {
        // A reasonable generator should produce at least some on-cells and some off-cells for
        // typical nicks -- purely all-true or all-false grids would be ugly identicons.
        val nicks = listOf("alice", "bob", "carol", "dave", "x", "trevarj")
        for (nick in nicks) {
            val grid = nickPixelGrid(nick)
            val anyOn = grid.any { it }
            val anyOff = grid.any { !it }
            assertTrue("Nick '$nick' grid should have at least one on-cell", anyOn)
            assertTrue("Nick '$nick' grid should have at least one off-cell", anyOff)
        }
    }
}
