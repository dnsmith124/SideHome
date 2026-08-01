package fi.palonkorpi.sidehome.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import fi.palonkorpi.sidehome.gesture.GestureTuning
import fi.palonkorpi.sidehome.gesture.ItemAction
import fi.palonkorpi.sidehome.gesture.resolveItemAction
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The §1 press grammar on one home / drawer item, as a single modifier: tap → launch or open,
 * long-press held still → the action strip, long-press then move → pickup-drag.
 *
 * The split between the last two is the SID-24 arbiter's hold-vs-slop race, so the thresholds are
 * read from [GestureTuning] and the outcome is classified by [resolveItemAction] rather than by a
 * second set of numbers here that could drift from it. That is also the surface SID-26 wires the
 * real arbiter into.
 *
 * [onHold] fires at the hold instant, not on release, so the hold is answered while the finger is
 * still down; a move after that is a pickup instead and [onPickup] takes over.
 *
 * After the pickup the finger is the item's carrier, so we keep the pointer for the rest of the
 * gesture and stream it: [onDrag] on every move (positions are **local** to this item, so a call
 * site that hit-tests against root bounds adds its own origin), then [onDrop] once when it lifts
 * (SID-29). A call site that has no drag to run — the drawer's rows, whose library drag-up is
 * SID-30 — can leave both off; the defaults make the pickup a one-shot signal as before.
 *
 * Nothing is consumed until the hold fires, so a drag that beats the timer still reaches the
 * drawer's list or the home's drawer-swipe ([ItemAction.NAVIGATION]) — an item never swallows a
 * scroll. Once the hold has fired we own the pointer and do consume, so the list underneath can't
 * scroll out from under an open strip, nor the drawer rise while an item is being carried.
 */
@Composable
fun Modifier.itemPress(
    tuning: GestureTuning,
    onTap: () -> Unit,
    onHold: () -> Unit,
    onPickup: () -> Unit,
    onDrag: (Offset) -> Unit = {},
    onDrop: () -> Unit = {},
): Modifier {
    // pointerInput only restarts when its key changes, so the gesture loop would otherwise hold
    // the lambdas captured on first composition forever.
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val pickup by rememberUpdatedState(onPickup)
    val drag by rememberUpdatedState(onDrag)
    val drop by rememberUpdatedState(onDrop)

    return this.pointerInput(tuning) {
        // Two different jobs, so two different thresholds (they used to share tuning.slopDp, which
        // sat *above* the list's own scroll slop and let scrolls leak through as taps/holds):
        //  - scrollSlop cancels a pending tap/hold. It is the platform touchSlop — the very distance
        //    a LazyColumn uses to claim a scroll — so any movement that would begin a scroll also
        //    kills the launch/menu, and a sub-slop nudge that scrolls nothing can no longer launch.
        //  - dragSlop is the deliberate pull that turns an *already-held* item into a pickup, and
        //    stays the coarser arbiter slop so a carry needs real intent, not a twitch.
        val scrollSlop = viewConfiguration.touchSlop
        val dragSlop = tuning.slopDp * density
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var crossedSlopBeforeHold = false
            var lifted = false

            // Race the hold timer against movement / lift. Timing out means neither happened — the
            // finger is still down and still, so the hold won.
            withTimeoutOrNull(tuning.holdTimeoutMs) {
                var settled = false
                while (!settled) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        lifted = true
                        settled = true
                    } else if (change.isConsumed) {
                        // The scrolling list claimed this gesture — it's a scroll, not a tap. Bail as
                        // "moved" so it resolves to NAVIGATION, never a launch. This is the load-bearing
                        // check: a row travels *with* the scroll, so the pointer's position local to the
                        // row barely changes and the distance test below never trips — which is exactly
                        // how a scroll used to end up launching the app it lifted over.
                        crossedSlopBeforeHold = true
                        settled = true
                    } else if ((change.position - down.position).getDistance() > scrollSlop) {
                        crossedSlopBeforeHold = true
                        settled = true
                    }
                }
            }
            val holdFired = !crossedSlopBeforeHold && !lifted

            var movedAfterHold = false
            if (holdFired) {
                hold()
                var settled = false
                while (!settled) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        // The hold already claimed this gesture (it resolves to MENU here), so own the
                        // release too. Otherwise the lift reaches an ancestor tap-catcher — the folder
                        // / bucket popover's tap-to-dismiss — which reads it as a tap and closes the
                        // surface out from under the strip the hold just raised.
                        change?.consume()
                        settled = true
                    } else {
                        change.consume()
                        if ((change.position - down.position).getDistance() > dragSlop) {
                            movedAfterHold = true
                            settled = true
                        }
                    }
                }
            }

            when (resolveItemAction(crossedSlopBeforeHold, holdFired, movedAfterHold)) {
                ItemAction.TAP -> tap()
                // The item is in hand now, and the same finger is still down — so this gesture isn't
                // over, it has become the carry. Stay on the pointer until it lifts.
                ItemAction.PICKUP -> {
                    pickup()
                    carry(down.id, drag, drop)
                }
                // MENU is already on screen (raised at the hold instant) and NAVIGATION belongs to
                // whatever surface the drag reached — neither has anything left to do here.
                ItemAction.MENU, ItemAction.NAVIGATION -> {}
            }
        }
    }
}

/**
 * Follow one carried item until its pointer lifts. A vanished pointer (a cancel — another window
 * taking the stream) is treated as a lift at the last place we saw it: the in-hand model has no
 * error state, so an interrupted carry puts the thing down where it is rather than swallowing it.
 */
private suspend fun AwaitPointerEventScope.carry(
    id: PointerId,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
) {
    var carrying = true
    while (carrying) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == id }
        if (change == null || !change.pressed) {
            carrying = false
        } else {
            change.consume()
            onDrag(change.position)
        }
    }
    onDrop()
}
