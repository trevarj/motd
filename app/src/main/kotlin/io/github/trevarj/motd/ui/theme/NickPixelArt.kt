package io.github.trevarj.motd.ui.theme

import kotlin.math.abs

/**
 * Deterministic pixel-art identicon generator for nick avatars.
 *
 * A 5x5 grid is seeded from the nick's hash; the left 3 columns are generated and the right 2
 * mirror them, giving a vertically-symmetric sprite like classic GitHub identicons. The generated
 * grid and the nick color from LocalNickColors are all that Avatar needs to draw it.
 *
 * All functions here are pure so they are unit-testable without any Android dependency.
 */

// Grid dimensions: 5 wide, 5 tall, 3 unique columns (right 2 mirror cols 1 and 0).
private const val GRID_W = 5
private const val GRID_H = 5
private const val UNIQUE_COLS = 3  // columns 0, 1, 2; columns 3 and 4 mirror 1 and 0

/**
 * Return a [GRID_W * GRID_H] (row-major) boolean array where `true` means an "on" (filled) pixel.
 * The grid is horizontally symmetric (left half mirrors right half) and is seeded from [nick]
 * via a stable case-insensitive hash -- identical to the nick-color hash for consistency.
 *
 * Indices: `grid[row * GRID_W + col]`, row/col both 0-based.
 */
fun nickPixelGrid(nick: String): BooleanArray {
    val seed = nickGridSeed(nick)
    val grid = BooleanArray(GRID_W * GRID_H)
    // Each row's unique columns are driven by successive bits extracted from the seed.
    // Using a simple LCG to spread the seed across the 5*3 = 15 needed bits.
    var rng = seed
    for (row in 0 until GRID_H) {
        for (col in 0 until UNIQUE_COLS) {
            rng = lcgNext(rng)
            val on = (rng ushr 31) and 1 == 1  // top bit of the 32-bit state
            val mirrorCol = GRID_W - 1 - col   // col 0 <-> 4, col 1 <-> 3, col 2 stays at 2
            grid[row * GRID_W + col] = on
            grid[row * GRID_W + mirrorCol] = on
        }
    }
    return grid
}

/** Row and column count exposed so callers can size their canvas cells. */
val NICK_GRID_ROWS: Int get() = GRID_H
val NICK_GRID_COLS: Int get() = GRID_W

/**
 * Stable integer seed derived from a normalized nick string. Uses the same case-fold and hash
 * accumulator as the nick-color generator so the same nick always yields the same sprite.
 */
internal fun nickGridSeed(nick: String): Int {
    var hash = 0
    for (c in nick.lowercase()) {
        hash = hash * 31 + c.code
    }
    // Spread the hash to improve bit distribution across the low bits.
    return abs(hash) xor (abs(hash) ushr 16)
}

/**
 * Minimal 32-bit LCG (Numerical Recipes constants). Gives good low-bit randomness for small grids
 * without requiring java.util.Random (which is not pure/serializable here).
 */
private fun lcgNext(state: Int): Int = state * 1664525 + 1013904223
