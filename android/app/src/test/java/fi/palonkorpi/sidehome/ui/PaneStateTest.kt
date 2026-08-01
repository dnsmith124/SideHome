package fi.palonkorpi.sidehome.ui

import androidx.compose.runtime.MonotonicFrameClock
import fi.palonkorpi.sidehome.gesture.EdgeDir
import fi.palonkorpi.sidehome.gesture.GestureTuning
import fi.palonkorpi.sidehome.gesture.paneAlpha
import fi.palonkorpi.sidehome.gesture.paneTranslation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for where the viewport sits across Past | Home | Future (SID-25, SPEC §7) — the pan
 * arithmetic and the rules that clamp a finger, which is everything about the pager that can be
 * wrong while looking perfectly smooth. The signs are the point: [PaneState.dragPx] runs *with* the
 * finger, `settlePage` and [PaneState.panPx] run *toward Future*, and a flipped sign there pages
 * the wrong way on a device with no other pager to compare against.
 *
 * Raw px, no density, matching the arbiter's decision layer: a 480px-wide page.
 */
class PaneStateTest {

    private val w = 480f
    private val tuning = GestureTuning()

    /**
     * A frame clock whose every tick is a whole second, so a settle is long over in a couple of
     * frames and [PaneState.goTo] lands synchronously. With [Dispatchers.Unconfined] nothing here
     * ever really suspends, so the settle animation runs *for real* — this walks the same path the
     * device does rather than a test-only shortcut into a state a finger might not be able to reach.
     */
    private class SecondPerFrameClock : MonotonicFrameClock {
        private var nanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            nanos += 1_000_000_000L
            return onFrame(nanos)
        }
    }

    /**
     * A 60Hz clock that reports what the panes did *after each frame*, for watching a settle in
     * flight rather than only where it lands — the release is the part a fling can be wrong in, and
     * it is over long before the animation is.
     *
     * It always advances, and that is not incidental. A frame clock that stops ticking but keeps
     * calling `onFrame` hands the animation the same timestamp for ever: zero elapsed time per
     * frame, so a spring never converges, `animate` never returns, and on [Dispatchers.Unconfined]
     * the test spins the CPU until something kills it. Observe by recording, never by stalling time.
     */
    private class RecordingClock : MonotonicFrameClock {
        private var nanos = 0L
        var afterFrame: (() -> Unit)? = null
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            nanos += 16_666_667L
            val r = onFrame(nanos)
            afterFrame?.invoke()
            return r
        }
    }

    /** Every frame of [dragPx] during a settle, in order. */
    private fun framesOf(build: PaneState.() -> Unit): Pair<PaneState, List<Float>> {
        val clock = RecordingClock()
        val p = panes(clock = clock)
        val seen = mutableListOf<Float>()
        clock.afterFrame = { seen += p.dragPx }
        p.build()
        return p to seen
    }

    private val density = 1.275f   // the SP-01's

    private fun panes(width: Float = w, clock: MonotonicFrameClock = SecondPerFrameClock()) =
        PaneState(CoroutineScope(Dispatchers.Unconfined + clock), { width }, { tuning }, { density })

    private fun PaneState.dragBody(dx: Float) = drag(dx, EdgeDir.NONE)

    // ---- the live drag ---------------------------------------------------------------------

    @Test fun `a body drag moves the panes with the finger, px for px`() {
        val p = panes()
        p.dragBody(-120f)
        assertEquals(-120f, p.dragPx, 0.01f)
        p.dragBody(-30f)
        assertEquals(-150f, p.dragPx, 0.01f)
    }

    @Test fun `a drag can never travel more than one page, however far the finger goes`() {
        // The clamp is what makes the settle a two-way decision: past a page the pager would be
        // showing a pane it has no index for, and the release would have to resolve two pages at
        // once. One page of travel per gesture, always.
        val p = panes()
        p.dragBody(-5000f)
        assertEquals(-w, p.dragPx, 0.01f)
        // The rightward clamp, tested where a rightward drag is actually allowed: at Future, toward
        // Home. (From Home itself, right is toward the disabled Past and buys no travel at all.)
        val q = panes()
        q.goTo(FUTURE_PAGE)
        q.dragBody(5000f)
        assertEquals(w, q.dragPx, 0.01f)
    }

    @Test fun `Home is the left end — with Past disabled it cannot go further left`() {
        // Past ships disabled for v1 ([FIRST_PAGE]), so Home is the hard left end: a rightward drag
        // (which would reveal Past) buys no travel, while toward Future is still allowed.
        val p = panes()   // starts at Home
        p.dragBody(400f)   // dragging right would reveal the disabled Past
        assertEquals(0f, p.dragPx, 0.01f)
        p.dragBody(-400f)  // toward Future is still allowed
        assertEquals(-400f, p.dragPx, 0.01f)
    }

    @Test fun `the ends do not wrap at Future either`() {
        val p = panes()
        p.goTo(FUTURE_PAGE)
        p.dragBody(-400f)
        assertEquals(0f, p.dragPx, 0.01f)
        p.dragBody(400f)
        assertEquals(400f, p.dragPx, 0.01f)
    }

    // ---- the edge Back-bands ---------------------------------------------------------------

    @Test fun `with Past disabled the left edge-band pages nowhere`() {
        // The left Back-band only ever paged toward Past; with Past disabled for v1 there is nothing
        // behind Home, so it buys no travel either way (Future is the right band's job).
        val p = panes()
        p.drag(200f, EdgeDir.LEFT)
        assertEquals(0f, p.dragPx, 0.01f)     // toward the disabled Past — refused
        val q = panes()
        q.drag(-200f, EdgeDir.LEFT)
        assertEquals(0f, q.dragPx, 0.01f)     // toward Future from the left band — still refused
    }

    @Test fun `a drag from the right edge-band may only go toward Future`() {
        val p = panes()
        p.drag(-200f, EdgeDir.RIGHT)
        assertEquals(-200f, p.dragPx, 0.01f)
        val q = panes()
        q.drag(200f, EdgeDir.RIGHT)
        assertEquals(0f, q.dragPx, 0.01f)
    }

    @Test fun `an edge drag is still clamped by the end it is heading for`() {
        // Both rules apply at once, and the tighter one has to win: at Home the left band's
        // direction (toward the disabled Past) is the one that is already exhausted.
        val p = panes()   // Home is the left end
        p.drag(300f, EdgeDir.LEFT)
        assertEquals(0f, p.dragPx, 0.01f)
    }

    // ---- what a release lands on -----------------------------------------------------------

    @Test fun `dragging left past the settle fraction pages to Future, and short of it comes back`() {
        // The sign convention, pinned: the finger moving left is negative dragPx and means Future.
        val p = panes()
        p.dragBody(-(w * tuning.settleFraction + 1f))
        assertEquals(FUTURE_PAGE, p.settleTarget(0f))

        val q = panes()
        q.dragBody(-(w * tuning.settleFraction - 1f))
        assertEquals(HOME_PAGE, q.settleTarget(0f))
    }

    @Test fun `with Past disabled a rightward drag from Home is refused and settles on Home`() {
        // Dragging right would page toward the disabled Past: it buys no travel, and the release
        // lands back on Home rather than falling through to page 0.
        val p = panes()
        p.dragBody(w * tuning.settleFraction + 1f)
        assertEquals(0f, p.dragPx, 0.01f)
        assertEquals(HOME_PAGE, p.settleTarget(0f))
    }

    @Test fun `a fling pages even from a barely-moved finger, in the direction it was thrown`() {
        val p = panes()
        p.dragBody(-1f)
        assertEquals(FUTURE_PAGE, p.settleTarget(tuning.flingVelocityDpPerS))
        // A backward fling toward the disabled Past cannot leave Home.
        val q = panes()
        assertEquals(HOME_PAGE, q.settleTarget(-tuning.flingVelocityDpPerS))
    }

    @Test fun `a fling leaves the finger at the speed it was thrown, instead of from a standstill`() {
        // The bug this pins was invisible to every "where does it land" test, because it landed in
        // exactly the right place. A fixed-duration easing starts from zero velocity, so the panes
        // stopped dead the instant the finger left and eased back up — a hitch precisely where the
        // motion should be smoothest. Nothing to see on a slow drag (no discontinuity to make); the
        // whole feel of a fast one, reported from the device as "heavy stutter on fast swipes"
        // while the frame timings were healthy.
        //
        // So the assertion is about the first frame that *moves*, and it is measured against the
        // finger rather than a magic number: a release that continues the throw covers roughly what
        // the finger would have in that frame. Half of it is generous and still an order of
        // magnitude clear of a standstill, which covers ~0.
        //
        // Which frame that is, is the subtlety, and the first version of this test got it wrong and
        // failed against a working spring. `animate` spends its first withFrameNanos learning the
        // animation's *start time* and emits the initial value at it — elapsed time is zero there,
        // so a stalled release and a perfect one are identical on that sample, and asserting on it
        // measures the clock rather than the animation. The first frame carrying any time is the
        // second sample; the travel that means anything is the one across it.
        val throwDpPerS = tuning.flingVelocityDpPerS * 3f
        val (_, seen) = framesOf {
            dragBody(-2f)
            settle(throwDpPerS)          // a hard throw toward Future
        }
        assertTrue("the settle produced no frames to judge", seen.size >= 2)
        val movedInFrameOne = -(seen[1] - seen[0])             // toward Future is negative dragPx
        val fingerWouldHaveMoved = throwDpPerS * density / 60f // px in one 60Hz frame
        assertTrue(
            "the release stalled: ${movedInFrameOne}px in the first moving frame, " +
                "against ${fingerWouldHaveMoved}px of finger",
            movedInFrameOne > fingerWouldHaveMoved * 0.5f,
        )
    }

    @Test fun `a settle never overshoots the page it is landing on`() {
        // A spring makes the release continuous; a *bouncy* one would say the panes are on elastic
        // — Future would slide in past its edge and expose a strip of the pane beyond before coming
        // back. A page is a place, not an object. Watch every frame, not just the last: overshoot
        // is by definition something that has been given back by the time it settles.
        val (p, seen) = framesOf {
            dragBody(-(w * 0.6f))
            settle(tuning.flingVelocityDpPerS * 4f)
        }
        assertEquals(FUTURE_PAGE, p.pageIndex)
        // goTo re-expresses the target as dragPx = -w against the *old* index, so travel past the
        // page reads as dragPx < -w on some frame.
        assertTrue(
            "overshot the page by ${-(seen.min() + w)}px",
            seen.all { it >= -w - 0.5f },
        )
    }

    @Test fun `a fling off the end settles on the end rather than on a page that isn't there`() {
        val p = panes()
        p.goTo(FUTURE_PAGE)
        assertEquals(FUTURE_PAGE, p.settleTarget(tuning.flingVelocityDpPerS))
    }

    @Test fun `a settle before the panes have been measured stays put instead of dividing by zero`() {
        // containerW is 0 for the first composition, and a lift can land in it.
        val unmeasured = panes(width = 0f)
        assertEquals(HOME_PAGE, unmeasured.settleTarget(0f))
    }

    // ---- the pan the sky and the panes are both placed from --------------------------------

    @Test fun `panning is measured from the centre home, mid-drag and settled alike`() {
        // One number for both halves of the swipe. Anything that recombined pageIndex with dragPx
        // itself would be a second formula to keep in sync with this one — and the sky and the
        // panes drifting apart by a frame is exactly the seam the continuous field forbids.
        val p = panes()
        assertEquals(0f, p.panPx, 0.01f)
        p.dragBody(-100f)
        assertEquals(100f, p.panPx, 0.01f)    // finger left = viewport right = toward Future
        p.goTo(FUTURE_PAGE)
        assertEquals(w, p.panPx, 0.01f)
        // goTo(PAST) is clamped to Home now (Past disabled), so the pan returns to centre.
        p.goTo(PAST_PAGE)
        assertEquals(0f, p.panPx, 0.01f)
    }

    @Test fun `the pan is continuous across the frame where a settle swaps index for drag`() {
        // goTo re-expresses the same frame against the new index. If the two disagreed by a pixel
        // the whole scene would jump on the last frame of every swipe.
        val p = panes()
        p.dragBody(-w)
        val beforeSwap = p.panPx
        p.goTo(FUTURE_PAGE)
        assertEquals(beforeSwap, p.panPx, 0.01f)
    }

    @Test fun `page position is the continuous position the dots read, between the pages it settles on`() {
        // SID-27's dots want where the viewport *is*, not where it last landed.
        val p = panes()
        assertEquals(HOME_PAGE.toFloat(), p.pagePosition, 0.001f)
        p.dragBody(-w / 2f)
        assertEquals(1.5f, p.pagePosition, 0.001f)
        p.goTo(FUTURE_PAGE)
        assertEquals(FUTURE_PAGE.toFloat(), p.pagePosition, 0.001f)
    }

    @Test fun `page position falls back to the settled page before the panes are measured`() {
        val unmeasured = panes(width = 0f)
        assertEquals(HOME_PAGE.toFloat(), unmeasured.pagePosition, 0.001f)
    }

    @Test fun `a reveal blasts back to the centre with no travel left over`() {
        val p = panes()
        p.goTo(FUTURE_PAGE)
        p.dragBody(200f)
        p.reset()
        assertEquals(HOME_PAGE, p.pageIndex)
        assertEquals(0f, p.dragPx, 0.01f)
        assertEquals(0f, p.panPx, 0.01f)
        assertTrue(p.isCentered)
    }

    // ---- how the pager places the panes from that pan ---------------------------------------

    @Test fun `the panes tile edge to edge, so the pan never shows two of them meeting`() {
        // Fully paged, not peeked: at rest home fills the viewport and its neighbours are exactly
        // one page away on either side — no gap for the sky to be mistaken for a seam, no overlap.
        assertEquals(-w, paneTranslation(-1, w, 0f), 0.01f)
        assertEquals(0f, paneTranslation(0, w, 0f), 0.01f)
        assertEquals(w, paneTranslation(1, w, 0f), 0.01f)
    }

    @Test fun `a committed swipe takes the centre home away — it is paged, not peeked`() {
        assertEquals(0f, paneTranslation(1, w, w), 0.01f)    // Future has landed
        assertEquals(-w, paneTranslation(0, w, w), 0.01f)    // home is a full page off-screen
    }

    @Test fun `only the centre pane is fully present, and a pane a page away has gone`() {
        assertEquals(1f, paneAlpha(0f, w), 0.001f)
        assertEquals(0f, paneAlpha(w, w), 0.001f)
        assertEquals(0f, paneAlpha(-w, w), 0.001f)
        assertEquals(0.5f, paneAlpha(w / 2f, w), 0.001f)
    }

    @Test fun `the pane beyond the one being swiped to is gone rather than negative`() {
        // At Past, Future is two pages out. An un-clamped alpha would go negative and the
        // draw-skip test that reads it would let a full screen of content through.
        assertEquals(0f, paneAlpha(paneTranslation(1, w, -w), w), 0.001f)
    }

    @Test fun `an unmeasured pager shows its content rather than a blank screen`() {
        assertEquals(1f, paneAlpha(0f, 0f), 0.001f)
    }
}
