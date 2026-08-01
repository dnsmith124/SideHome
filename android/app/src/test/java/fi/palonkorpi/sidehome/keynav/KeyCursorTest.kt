package fi.palonkorpi.sidehome.keynav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The per-surface cursor math (SID-84): linear moves clamp at the ends, grid moves respect a partial
 * last row, and direct-index resolves only inside the badged 1–9 window.
 */
class KeyCursorTest {

    // ---- linear cursor ---------------------------------------------------------------------

    @Test fun `linear move steps and clamps at both ends`() {
        assertEquals(1, moveCursor(current = 0, delta = 1, count = 4))
        assertEquals(3, moveCursor(current = 3, delta = 1, count = 4)) // wall at the bottom, no wrap
        assertEquals(0, moveCursor(current = 0, delta = -1, count = 4)) // wall at the top
    }

    @Test fun `linear move on an empty surface is inert`() {
        assertEquals(0, moveCursor(current = 0, delta = 1, count = 0))
    }

    // ---- grid cursor (folders) -------------------------------------------------------------

    @Test fun `grid move steps a whole row down and one cell across`() {
        // 7 items, 4 cols → row0: 0..3, row1: 4..6.
        assertEquals(4, moveGridCursor(current = 0, dRow = 1, dCol = 0, count = 7, cols = 4))
        assertEquals(1, moveGridCursor(current = 0, dRow = 0, dCol = 1, count = 7, cols = 4))
    }

    @Test fun `grid move into a partial last row clamps to the final item`() {
        // From index 3 (row0, col3) down would be index 7, which doesn't exist → clamp to 6.
        assertEquals(6, moveGridCursor(current = 3, dRow = 1, dCol = 0, count = 7, cols = 4))
    }

    @Test fun `grid move clamps at the edges`() {
        assertEquals(0, moveGridCursor(current = 0, dRow = -1, dCol = 0, count = 7, cols = 4)) // above row0
        assertEquals(0, moveGridCursor(current = 0, dRow = 0, dCol = -1, count = 7, cols = 4)) // left of col0
    }

    // ---- direct index ----------------------------------------------------------------------

    @Test fun `direct index resolves 1-based to 0-based within range`() {
        assertEquals(0, quickIndexTarget(n = 1, count = 5))
        assertEquals(4, quickIndexTarget(n = 5, count = 5))
    }

    @Test fun `direct index past the item count is inert`() {
        assertNull(quickIndexTarget(n = 6, count = 5))
    }

    @Test fun `direct index outside the badged 1-9 window is inert`() {
        assertNull(quickIndexTarget(n = 0, count = 20))
        assertNull(quickIndexTarget(n = 10, count = 20))
    }
}
