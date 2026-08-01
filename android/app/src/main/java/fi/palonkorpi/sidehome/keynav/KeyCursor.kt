package fi.palonkorpi.sidehome.keynav

/**
 * Pure per-surface selection-cursor math (SID-84). Key-nav's cursor is an app-owned index, NOT
 * Compose's native focus — native focus can't traverse the off-screen items of a `LazyColumn`, so
 * every navigable surface holds a plain Int and moves it through here. Framework-free and total: an
 * empty surface or an out-of-range start never throws, it clamps.
 */

/**
 * Move a linear cursor by [delta] within [count] items, clamping at the ends (no wrap). A wrap would
 * make "down past the last favourite" jump to the top, which reads as a glitch on a 4-item home; the
 * ends are walls. Returns 0 for an empty surface.
 */
fun moveCursor(current: Int, delta: Int, count: Int): Int {
    if (count <= 0) return 0
    return (current + delta).coerceIn(0, count - 1)
}

/**
 * Move within a fixed-width grid (folders): up/down step a whole row, left/right step one cell. The
 * last row may be partial, so a move that lands past the final item clamps back to it rather than
 * onto an empty cell. [cols] is the grid width; [count] the item total.
 */
fun moveGridCursor(current: Int, dRow: Int, dCol: Int, count: Int, cols: Int): Int {
    if (count <= 0 || cols <= 0) return 0
    val cur = current.coerceIn(0, count - 1)
    val rows = (count + cols - 1) / cols
    val newRow = (cur / cols + dRow).coerceIn(0, rows - 1)
    val newCol = (cur % cols + dCol).coerceIn(0, cols - 1)
    return (newRow * cols + newCol).coerceIn(0, count - 1)
}

/**
 * Resolve a 1-based direct index ([KeyIntent.QuickIndex]) to a 0-based item, or null when it is out
 * of the badged 1–9 window or past the surface's item count. Null means "no target" — the key is
 * inert rather than clamping to some nearest item, because a direct index that hit the wrong app
 * would be worse than one that did nothing.
 */
fun quickIndexTarget(n: Int, count: Int): Int? {
    if (n < 1 || n > 9) return null
    val idx = n - 1
    return if (idx < count) idx else null
}
