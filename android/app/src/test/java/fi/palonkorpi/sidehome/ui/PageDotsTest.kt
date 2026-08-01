package fi.palonkorpi.sidehome.ui

import androidx.compose.runtime.MonotonicFrameClock
import fi.palonkorpi.sidehome.gesture.EdgeDir
import fi.palonkorpi.sidehome.gesture.GestureTuning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for what the page-dots say about where you are (SID-27, SPEC §7).
 *
 * Worth testing precisely because it is worthless to look at: three 5px circles at three alphas is
 * a thing that can be lit backwards, lit off by one, or lit two pages wide, and every one of those
 * is a perfectly plausible screenshot. The ramp is where all of that lives, so the ramp is pure.
 */
class PageDotsTest {

    private val w = 480f   // the SP-01's, in px like the rest of the gesture decision layer

    /**
     * Always advances. A frame clock that keeps calling `onFrame` with a frozen timestamp hands the
     * settle spring zero elapsed time for ever, so it never converges and `animate` never returns —
     * on [Dispatchers.Unconfined] that is a live-lock, not a slow test. See [PaneStateTest].
     */
    private class SecondPerFrameClock : MonotonicFrameClock {
        private var nanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            nanos += 1_000_000_000L
            return onFrame(nanos)
        }
    }

    private fun panes() = PaneState(
        CoroutineScope(Dispatchers.Unconfined + SecondPerFrameClock()),
        { w },
        { GestureTuning() },
        { 1.275f },
    )

    // ---- the ramp ---------------------------------------------------------------------------

    @Test fun `at rest on the centre home, the home dot is lit and its neighbours are not`() {
        assertEquals(DOT_IDLE_ALPHA, dotAlpha(HOME_PAGE.toFloat(), PAST_PAGE), 0.001f)
        assertEquals(DOT_ACTIVE_ALPHA, dotAlpha(HOME_PAGE.toFloat(), HOME_PAGE), 0.001f)
        assertEquals(DOT_IDLE_ALPHA, dotAlpha(HOME_PAGE.toFloat(), FUTURE_PAGE), 0.001f)
    }

    @Test fun `each end lights its own dot, and only it`() {
        // Off by one here would be invisible at the centre — where the dots are symmetric — and only
        // ever wrong at the ends, which is the half of the travel nobody screenshots.
        assertEquals(DOT_ACTIVE_ALPHA, dotAlpha(PAST_PAGE.toFloat(), PAST_PAGE), 0.001f)
        assertEquals(DOT_IDLE_ALPHA, dotAlpha(PAST_PAGE.toFloat(), HOME_PAGE), 0.001f)
        assertEquals(DOT_ACTIVE_ALPHA, dotAlpha(FUTURE_PAGE.toFloat(), FUTURE_PAGE), 0.001f)
        assertEquals(DOT_IDLE_ALPHA, dotAlpha(FUTURE_PAGE.toFloat(), HOME_PAGE), 0.001f)
    }

    @Test fun `halfway between two pages their dots are equally lit`() {
        val mid = (DOT_IDLE_ALPHA + DOT_ACTIVE_ALPHA) / 2f
        assertEquals(mid, dotAlpha(1.5f, HOME_PAGE), 0.001f)
        assertEquals(mid, dotAlpha(1.5f, FUTURE_PAGE), 0.001f)
        // The pane you are travelling away from is already a page and a half off: idle, not dimmer.
        assertEquals(DOT_IDLE_ALPHA, dotAlpha(1.5f, PAST_PAGE), 0.001f)
    }

    @Test fun `the dot being left dims exactly as fast as the one arriving lights`() {
        // The crossfade is the swipe read straight off the finger — the sum of the pair is constant
        // across the whole travel, so there is no frame where the dots are collectively brighter or
        // dimmer than at rest. That is what keeps a swipe from flashing the top of the screen.
        val atRest = dotAlpha(1f, HOME_PAGE) + dotAlpha(1f, FUTURE_PAGE)
        for (step in 0..10) {
            val pos = 1f + step / 10f
            assertEquals(atRest, dotAlpha(pos, HOME_PAGE) + dotAlpha(pos, FUTURE_PAGE), 0.001f)
        }
    }

    @Test fun `a dot two pages away is idle rather than negative`() {
        // Un-clamped, the ramp goes below zero at two pages out — and at Past, Future is exactly two
        // pages out. A negative alpha is not a dimmer dot; it is undefined at the draw.
        assertEquals(DOT_IDLE_ALPHA, dotAlpha(PAST_PAGE.toFloat(), FUTURE_PAGE), 0.001f)
        assertEquals(DOT_IDLE_ALPHA, dotAlpha(FUTURE_PAGE.toFloat(), PAST_PAGE), 0.001f)
    }

    @Test fun `no dot is ever brighter than active or dimmer than idle, anywhere in the travel`() {
        for (step in 0..40) {
            val pos = step / 20f   // PAST 0 .. FUTURE 2
            for (i in PAST_PAGE..FUTURE_PAGE) {
                val a = dotAlpha(pos, i)
                assertTrue("dot $i at $pos was $a", a >= DOT_IDLE_ALPHA - 0.001f)
                assertTrue("dot $i at $pos was $a", a <= DOT_ACTIVE_ALPHA + 0.001f)
            }
        }
    }

    // ---- against the real pager ---------------------------------------------------------------

    @Test fun `the dots track the finger, and do not wait for the page to commit`() {
        // The behavioural claim of the whole issue, against the real PaneState rather than a number
        // typed in by hand: mid-drag the dots are already mid-crossfade, because they read
        // pagePosition (continuous) and not pageIndex (which is still the page being left).
        val p = panes()
        assertEquals(DOT_ACTIVE_ALPHA, dotAlpha(p.pagePosition, HOME_PAGE), 0.001f)

        p.drag(-w / 2f, EdgeDir.NONE)   // finger halfway toward Future, nothing committed yet
        assertEquals(HOME_PAGE, p.pageIndex)
        val mid = (DOT_IDLE_ALPHA + DOT_ACTIVE_ALPHA) / 2f
        assertEquals(mid, dotAlpha(p.pagePosition, HOME_PAGE), 0.001f)
        assertEquals(mid, dotAlpha(p.pagePosition, FUTURE_PAGE), 0.001f)

        p.goTo(FUTURE_PAGE)
        assertEquals(DOT_ACTIVE_ALPHA, dotAlpha(p.pagePosition, FUTURE_PAGE), 0.001f)
        assertEquals(DOT_IDLE_ALPHA, dotAlpha(p.pagePosition, HOME_PAGE), 0.001f)
    }

    @Test fun `with Past disabled a rightward drag lights nothing new — Home stays put`() {
        // Dragging toward the disabled Past buys no travel, so the dots do not move: Home stays lit
        // and the Past dot (unreachable, and no longer drawn) never lights.
        val p = panes()
        p.drag(w / 2f, EdgeDir.NONE)   // finger right = toward the disabled Past
        assertEquals(HOME_PAGE, p.pageIndex)
        assertEquals(DOT_ACTIVE_ALPHA, dotAlpha(p.pagePosition, HOME_PAGE), 0.001f)
        assertEquals(DOT_IDLE_ALPHA, dotAlpha(p.pagePosition, PAST_PAGE), 0.001f)
    }

    @Test fun `before the panes are measured the dots still say Home rather than dividing by zero`() {
        // containerW is 0 for the first composition, and the dots are drawn in it.
        val unmeasured = PaneState(
            CoroutineScope(Dispatchers.Unconfined + SecondPerFrameClock()),
            { 0f },
            { GestureTuning() },
            { 1.275f },
        )
        assertEquals(DOT_ACTIVE_ALPHA, dotAlpha(unmeasured.pagePosition, HOME_PAGE), 0.001f)
    }
}
