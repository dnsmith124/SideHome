package fi.palonkorpi.sidehome.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import fi.palonkorpi.sidehome.apps.Category
import fi.palonkorpi.sidehome.apps.AppRepository
import fi.palonkorpi.sidehome.apps.FolderItem
import fi.palonkorpi.sidehome.apps.HomeItem

/**
 * SID-29 (SPEC §1): what is *in hand*, and what a release would mean. The holder for the whole
 * pick-up-&-place model — the state, plus the one commit point every landing goes through.
 *
 * Split out of `HomeScreen.kt`: this is where the §1 entry paths land — SID-30's tap-to-place
 * ([takeFromLibrary]) and library drag-up ([liftFromLibrary]) both resolve into [place], the same
 * one a home row's reorder does — and keeping it in the launcher's 1400-line scaffold meant every
 * change to placement and every change to the pane scaffold contended for one file.
 *
 * Inputs it doesn't own — the home list, the container size — are read through lambdas rather than
 * copied in, so the derived targets below stay correct as the home changes underneath a carry
 * (an app uninstalls, a reload lands) without this holder having to be rebuilt and lose the hand.
 */

/**
 * What is currently in hand, and how it got there. One physical act covers reorder, grouping,
 * replace, removal — and, since SID-30, adding: an app on its way in from the library is carried by
 * the same hand and lands through the same commit point as a row being shuffled, which is the whole
 * reason "add to home" cannot dead-end. What it came from is [source]'s to say.
 *
 * [byFinger] is how it got here: a long-press-then-move carries it under a finger (from a home row,
 * or up out of the drawer); the menu's Move and Add-to-home cells hand it over with no finger at
 * all, so it waits for a tap on a target instead.
 */
internal data class InHand(val source: DropSource, val byFinger: Boolean)

/** The measured row bounds, in the pure drop layer's own unit-agnostic terms. */
internal fun Rect.toDropRect() = DropRect(left = left, top = top, right = right, bottom = bottom)

@Stable
internal class PlacementState(
    private val density: Density,
    private val homeItems: () -> List<HomeItem>,
    private val containerW: () -> Float,
    private val containerH: () -> Float,
    private val categoryOf: (String) -> Category?,
    private val onCommit: (List<HomeItem>) -> Unit,
    // SID-112: row bounds are no longer this holder's private copy — they come from the one shared
    // registry every reader now shares, so a drop resolves against the same rects the arbiter and the
    // long-press gate hit-test. The rows report into it directly; placement only reads.
    private val homeBounds: HomeItemBounds,
) {
    var hand by mutableStateOf<InHand?>(null)
        private set

    /** Where the finger holding the item is, in root coords — the convention the rows report in. */
    var pointer by mutableStateOf(Offset.Zero)
        private set

    /**
     * A finger lifted this out of a drawer row and is still carrying it. The drawer must stay
     * mounted for as long as this holds, however far it has melted away: Compose delivers the rest
     * of a gesture to the node that took the pointer down, so unmounting that row mid-carry would
     * cancel the very finger doing the carrying. (Add-to-home's carry has no finger, so it doesn't
     * pin anything — the drawer is free to go.)
     */
    val fingerCarryFromLibrary: Boolean
        get() = hand?.let { it.byFinger && it.source is DropSource.Library } == true

    /**
     * Whether a row that isn't there yet could exist. Only an arrival can ask — it is the one carry
     * that adds a row — and it is the *home's* fact, not the drawn rows': a row that hasn't reported
     * its bounds yet is still on the home and still filling a slot.
     */
    private fun homeHasRoom(): Boolean = homeItems().size < AppRepository.MAX_HOME

    // The targets and the drop they resolve to are derived, not stored: the pointer moves every few
    // ms, and only the *answer* changing (a different gap, a different row) may recompose the layer.
    val targets: List<HomeRowTarget> by derivedStateOf {
        homeRowTargets(
            rows = homeItems().mapIndexedNotNull { i, item ->
                homeBounds.boundsOf(i)?.let { b -> HomeRowInput(i, item is FolderItem, b.toDropRect()) }
            },
            sourceIndex = hand?.source?.homeIndex,
            containerWidth = containerW(),
            replaceHitPx = with(density) { REPLACE_HIT.toPx() },
            replaceMarginPx = with(density) { REPLACE_MARGIN.toPx() },
        )
    }

    val removeZone: DropRect by derivedStateOf {
        removeZoneRect(
            containerWidth = containerW(),
            containerHeight = containerH(),
            widthPx = with(density) { REMOVE_WIDTH.toPx() },
            heightPx = with(density) { REMOVE_HEIGHT.toPx() },
            bottomMarginPx = with(density) { REMOVE_BOTTOM_MARGIN.toPx() },
        )
    }

    val dropTarget: DropTarget? by derivedStateOf {
        val h = hand
        // Nothing hovers what no finger is over: the Move cell's carry lights every target evenly
        // and waits for a tap to say which one.
        if (h == null || !h.byFinger) {
            null
        } else {
            resolveDropTarget(
                x = pointer.x,
                y = pointer.y,
                rows = targets,
                sourceIndex = h.source.homeIndex,
                removeZone = removeZone,
                homeHasRoom = homeHasRoom(),
            )
        }
    }

    /**
     * Lift the item at [index] under a finger. Seeded at [at] — the row's own centre — so it starts
     * where it was and then catches up to the finger.
     */
    fun lift(index: Int, at: Offset) {
        pointer = at
        hand = InHand(DropSource.Home(index), byFinger = true)
    }

    /** Take the item at [index] with no finger on it: the strip's Move cell. Waits for a tap. */
    fun take(index: Int) {
        hand = InHand(DropSource.Home(index), byFinger = false)
    }

    /**
     * Lift the app [key] out of the library under a finger — the drawer's hold-and-drag-up (SID-30).
     * Seeded at [at], the drawer row's own centre, so the app detaches from where it was rather than
     * appearing under the finger: the drawer is about to melt out from under it, and the chip left
     * behind is the only thing that carries that row's identity across the change of context.
     */
    fun liftFromLibrary(key: String, at: Offset) {
        pointer = at
        hand = InHand(DropSource.Library(key), byFinger = true)
    }

    /**
     * Take the app [key] out of the library with no finger on it: the menu's Add-to-home cell. The
     * drawer closes behind it and the home lights its targets, so this is the placement half of the
     * §1 context jump — the tap that follows is what says where it goes.
     */
    fun takeFromLibrary(key: String) {
        hand = InHand(DropSource.Library(key), byFinger = false)
    }

    fun drag(at: Offset) {
        pointer = at
    }

    /**
     * Put down whatever is carried without editing anything — Back, HOME, or a reveal. Nothing to
     * undo either way: a home item never left the list, so it stays where it was, and an arrival
     * never entered it, so it simply doesn't arrive.
     */
    fun cancel() {
        hand = null
    }

    /**
     * Land whatever is in hand on [target] — the one commit point every drop goes through, finger or
     * tap, home row or library arrival alike. A null target (a cancel) just puts it back down.
     */
    fun place(target: DropTarget?) {
        val h = hand ?: return
        hand = null
        if (target == null) return
        val before = homeItems()
        val next = before.applyDrop(h.source, target, categoryOf)
        // Dropped back where it already was — or an arrival that ended on the remove pill, which is
        // its way of saying "not this". Either way it's a cancel, not an edit: don't write the home
        // (and the repo) for it.
        if (next != before) onCommit(next)
    }

    /**
     * What a tap at [pos] would mean for whatever is in hand — the finger-less carry's answer to
     * [dropTarget], which only a hovering finger can have.
     *
     * It resolves but does not commit, because between the two sits the only question §1 asks
     * (SID-31's folder-remove confirm) and the caller owns that. Landing it is [place]'s.
     */
    fun resolveAt(pos: Offset): DropTarget? {
        val h = hand ?: return null
        return resolveDropTarget(
            x = pos.x,
            y = pos.y,
            rows = targets,
            sourceIndex = h.source.homeIndex,
            removeZone = removeZone,
            homeHasRoom = homeHasRoom(),
        )
    }
}

@Composable
internal fun rememberPlacement(
    home: List<HomeItem>,
    containerW: Int,
    containerH: Int,
    categoryOf: (String) -> Category?,
    onCommit: (List<HomeItem>) -> Unit,
    homeBounds: HomeItemBounds,
): PlacementState {
    val density = LocalDensity.current
    // Latched rather than captured: these change constantly, and rebuilding the holder for each one
    // would drop the hand mid-carry. Keyed on density alone, since only its px conversions are baked.
    val homeState = rememberUpdatedState(home)
    val wState = rememberUpdatedState(containerW)
    val hState = rememberUpdatedState(containerH)
    val categoryState = rememberUpdatedState(categoryOf)
    val commitState = rememberUpdatedState(onCommit)
    // The registry is created once by the host and held for the composable's life, so it is captured
    // directly rather than latched — it never changes identity under a carry.
    return remember(density) {
        PlacementState(
            density = density,
            homeItems = { homeState.value },
            containerW = { wState.value.toFloat() },
            containerH = { hState.value.toFloat() },
            categoryOf = { categoryState.value(it) },
            onCommit = { commitState.value(it) },
            homeBounds = homeBounds,
        )
    }
}
