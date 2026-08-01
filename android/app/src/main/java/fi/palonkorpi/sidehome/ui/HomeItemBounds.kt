package fi.palonkorpi.sidehome.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * SID-112: the one place a home row's on-screen bounds live.
 *
 * Before, the same measured rects were copied into three separately-maintained maps — the arbiter's
 * `homeRowBounds` ("is a swipe starting on an item?"), HomePage's `rowRects` ("is a background
 * long-press on an item?"), and [PlacementState]'s own `rowBounds` ("where does a drop land?") — all
 * fed from the same `HomeRow.onGloballyPositioned` and all able to drift out of step. This is that
 * single source: the rows [report] into it, and every reader (arbiter, the theme-chooser long-press
 * gate, placement, the key-nav strip anchor) asks it instead of keeping a private copy.
 *
 * Root coordinates throughout — the convention the rows and the in-hand layer already share.
 */
@Stable
internal class HomeItemBounds {
    private val bounds = mutableStateMapOf<Int, Rect>()

    /**
     * A row reports where it sits. `HomeRow` already fires this only on real movement, so it is a
     * plain write — a redundant one would needlessly re-derive [PlacementState.targets].
     */
    fun report(index: Int, rect: Rect) {
        bounds[index] = rect
    }

    /**
     * Drop rows that no longer exist. A removed favourite otherwise leaves a stale rect, and a stale
     * rect over now-empty space makes the arbiter yield there and swallow a page swipe (SID-26).
     */
    fun retain(indices: Set<Int>) {
        bounds.keys.retainAll(indices)
    }

    /** The bounds of one row, for the strip anchor and the placement targets. */
    fun boundsOf(index: Int): Rect? = bounds[index]

    /**
     * Which home item is under [pos] (root coords), or null for bare space — the hit-test the arbiter
     * and the long-press gate share. Pure geometry: the one reader that must not match a paged-away
     * pane (the arbiter) keeps its own "is the home centred?" guard, because a translated pane's rows
     * still report centre-screen bounds, so the guard belongs with paging, not here.
     */
    fun hitTest(pos: Offset): Int? =
        bounds.entries.firstOrNull { it.value.contains(pos) }?.key
}
