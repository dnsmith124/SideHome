package fi.palonkorpi.sidehome.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure gesture-routing logic. These pin the *feel* decisions SID-24 is about —
 * axis-lock on diagonals, the inset/edge-band gating, page/surface settling, and the temporal
 * item-ownership race — so they can be tuned with confidence rather than only by eye on-device.
 */
class GestureArbiterTest {

    private val t = GestureTuning()

    // ---- axis lock -------------------------------------------------------------------------

    @Test fun `clean vertical drag locks vertical`() {
        assertEquals(Axis.VERTICAL, lockAxis(dx = 2f, dy = 40f, axisBias = t.axisBias))
    }

    @Test fun `clean horizontal drag locks horizontal`() {
        assertEquals(Axis.HORIZONTAL, lockAxis(dx = 40f, dy = 2f, axisBias = t.axisBias))
    }

    @Test fun `pure diagonal resolves to vertical via the bias`() {
        // equal dx/dy: the >1 axisBias tilts the tie toward the primary (vertical) act.
        assertEquals(Axis.VERTICAL, lockAxis(dx = 30f, dy = 30f, axisBias = t.axisBias))
    }

    @Test fun `a diagonal clearly favouring horizontal still locks horizontal`() {
        // bias is a gentle tilt, not a veto — a decisive horizontal wins.
        assertEquals(Axis.HORIZONTAL, lockAxis(dx = 60f, dy = 30f, axisBias = t.axisBias))
    }

    @Test fun `no movement locks no axis`() {
        assertEquals(Axis.NONE, lockAxis(dx = 0f, dy = 0f, axisBias = t.axisBias))
    }

    @Test fun `sign is irrelevant to axis lock`() {
        assertEquals(Axis.VERTICAL, lockAxis(dx = -1f, dy = -40f, axisBias = t.axisBias))
        assertEquals(Axis.HORIZONTAL, lockAxis(dx = -40f, dy = 1f, axisBias = t.axisBias))
    }

    // ---- start-region gating ---------------------------------------------------------------

    // A 480x640 screen at density 1 with typical gesture insets (top shade ~24, nav ~48, edges ~20).
    private fun classify(x: Float, y: Float) = classifyStart(
        x = x, y = y, width = 480f, height = 640f,
        insets = GestureInsets(top = 24f, bottom = 48f, left = 20f, right = 20f),
        tuning = t, density = 1f,
    )

    @Test fun `centre-body touch arms vertical with no edge constraint`() {
        val r = classify(240f, 320f)
        assertTrue(r.armsVertical)
        assertEquals(EdgeDir.NONE, r.edgeDir)
        assertEquals("BODY", r.label)
    }

    @Test fun `touch inside the top shade inset does not arm vertical`() {
        val r = classify(240f, 10f)
        assertFalse(r.armsVertical)
    }

    @Test fun `touch inside the bottom nav inset does not arm vertical`() {
        val r = classify(240f, 620f) // 640 - 48 = 592 threshold
        assertFalse(r.armsVertical)
    }

    @Test fun `left edge within the low band pages toward Past`() {
        // band centre = 640 * 0.62 = 396.8, band height 200 -> [296.8, 496.8]; y=400 is inside.
        val r = classify(5f, 400f)
        assertEquals(EdgeDir.LEFT, r.edgeDir)
        assertEquals("LEFT-EDGE", r.label)
    }

    @Test fun `right edge within the low band pages toward Future`() {
        val r = classify(475f, 400f)
        assertEquals(EdgeDir.RIGHT, r.edgeDir)
    }

    @Test fun `edge touch above the low band has no directional constraint`() {
        // y=100 is above the [~297, ~497] band -> residual Back zone, not directional paging.
        val r = classify(5f, 100f)
        assertEquals(EdgeDir.NONE, r.edgeDir)
    }

    @Test fun `edge band falls back to tuning width when the OS reports no inset`() {
        val r = classifyStart(
            x = 15f, y = 400f, width = 480f, height = 640f,
            insets = GestureInsets(top = 24f, bottom = 48f, left = 0f, right = 0f),
            tuning = t, density = 1f,
        )
        // 15 <= edgeBandDp (20) fallback -> still a left edge.
        assertEquals(EdgeDir.LEFT, r.edgeDir)
    }

    // ---- exclusion span --------------------------------------------------------------------

    @Test fun `exclusion span is biased low and centred on the bias fraction`() {
        val (top, bottom) = edgeExclusionSpan(height = 640f, bandPx = 200f, biasFraction = 0.62f)
        assertEquals(296.8f, top, 0.1f)
        assertEquals(496.8f, bottom, 0.1f)
    }

    @Test fun `exclusion span clamps to the screen when it would overflow the bottom`() {
        val (top, bottom) = edgeExclusionSpan(height = 640f, bandPx = 200f, biasFraction = 0.95f)
        assertEquals(640f, bottom, 0.01f)
        assertEquals(440f, top, 0.01f) // full 200 band preserved, slid up off the bottom
    }

    @Test fun `exclusion band never exceeds the screen height`() {
        val (top, bottom) = edgeExclusionSpan(height = 100f, bandPx = 200f, biasFraction = 0.5f)
        assertEquals(0f, top, 0.01f)
        assertEquals(100f, bottom, 0.01f)
    }

    // ---- surface settling ------------------------------------------------------------------

    @Test fun `a short drag past the settle fraction opens`() {
        assertTrue(settleOpen(openFrac = 0.4f, openVelocityDpPerS = 0f, tuning = t))
    }

    @Test fun `a short drag below the settle fraction springs back`() {
        assertFalse(settleOpen(openFrac = 0.2f, openVelocityDpPerS = 0f, tuning = t))
    }

    @Test fun `a fast fling opens even from a tiny drag`() {
        assertTrue(settleOpen(openFrac = 0.05f, openVelocityDpPerS = 900f, tuning = t))
    }

    @Test fun `a fast fling the other way closes even past the fraction`() {
        assertFalse(settleOpen(openFrac = 0.5f, openVelocityDpPerS = -900f, tuning = t))
    }

    // ---- pager settling --------------------------------------------------------------------

    @Test fun `pager stays put for a small pan`() {
        assertEquals(1, settlePage(current = 1, offsetFrac = 0.1f, velocityDpPerS = 0f, pageCount = 3, tuning = t))
    }

    @Test fun `pager advances past the settle fraction`() {
        assertEquals(2, settlePage(current = 1, offsetFrac = 0.4f, velocityDpPerS = 0f, pageCount = 3, tuning = t))
    }

    @Test fun `pager retreats past the settle fraction`() {
        assertEquals(0, settlePage(current = 1, offsetFrac = -0.4f, velocityDpPerS = 0f, pageCount = 3, tuning = t))
    }

    @Test fun `pager flings a page even from a small pan`() {
        assertEquals(2, settlePage(current = 1, offsetFrac = 0.05f, velocityDpPerS = 900f, pageCount = 3, tuning = t))
    }

    @Test fun `pager cannot page past the last page`() {
        assertEquals(2, settlePage(current = 2, offsetFrac = 0.9f, velocityDpPerS = 900f, pageCount = 3, tuning = t))
    }

    @Test fun `pager cannot page before the first page`() {
        assertEquals(0, settlePage(current = 0, offsetFrac = -0.9f, velocityDpPerS = -900f, pageCount = 3, tuning = t))
    }

    @Test fun `a meaningful drag owns the direction against a recoiled fling`() {
        // SID-78: a quick swipe toward Future (offsetFrac past the reverse tolerance, short of the
        // settle fraction) whose end-velocity noisily recoiled backward commits *forward* — the drag
        // names the direction and the fling magnitude says "commit"; the recoil sign never reverses
        // it. So it lands Future, and above all NOT Past (the old bug paged you backwards).
        assertEquals(2, settlePage(current = 1, offsetFrac = 0.15f, velocityDpPerS = -900f, pageCount = 3, tuning = t))
        assertEquals(0, settlePage(current = 1, offsetFrac = -0.15f, velocityDpPerS = 900f, pageCount = 3, tuning = t))
    }

    @Test fun `a fast swipe back from Future lands on Home, never rubber-bands`() {
        // SID-78 head symptom: at Future, a brisk swipe back toward Home whose release velocity
        // recoiled the wrong way must settle on Home (1) — not snap back to Future (2).
        assertEquals(1, settlePage(current = 2, offsetFrac = -0.15f, velocityDpPerS = 900f, pageCount = 3, tuning = t))
        // Even a mostly-clean fast back-swipe lands Home rather than falling through to "stay".
        assertEquals(1, settlePage(current = 2, offsetFrac = -0.12f, velocityDpPerS = -900f, pageCount = 3, tuning = t))
    }

    @Test fun `a meaningful drag with no fling still needs the settle fraction`() {
        // Below the settle fraction and with no fling, a real drag stays put — the fling assist is
        // what lets a shorter drag commit, not the drag alone.
        assertEquals(1, settlePage(current = 1, offsetFrac = 0.15f, velocityDpPerS = 0f, pageCount = 3, tuning = t))
        assertEquals(1, settlePage(current = 1, offsetFrac = -0.15f, velocityDpPerS = 0f, pageCount = 3, tuning = t))
    }

    @Test fun `a fling still back-flicks from a near-standstill`() {
        // The deliberate flick from ~0 drag is preserved: under the reverse tolerance, velocity wins.
        assertEquals(0, settlePage(current = 1, offsetFrac = 0.02f, velocityDpPerS = -900f, pageCount = 3, tuning = t))
        assertEquals(2, settlePage(current = 1, offsetFrac = -0.02f, velocityDpPerS = 900f, pageCount = 3, tuning = t))
    }

    // ---- temporal item ownership -----------------------------------------------------------

    @Test fun `moving before the hold fires is navigation, not a menu`() {
        assertEquals(
            ItemAction.NAVIGATION,
            resolveItemAction(crossedSlopBeforeHold = true, holdFired = false, movedAfterHold = false),
        )
    }

    @Test fun `holding still opens the menu`() {
        assertEquals(
            ItemAction.MENU,
            resolveItemAction(crossedSlopBeforeHold = false, holdFired = true, movedAfterHold = false),
        )
    }

    @Test fun `holding then moving is a pickup`() {
        assertEquals(
            ItemAction.PICKUP,
            resolveItemAction(crossedSlopBeforeHold = false, holdFired = true, movedAfterHold = true),
        )
    }

    @Test fun `a quick touch with no hold and no slop is a tap`() {
        assertEquals(
            ItemAction.TAP,
            resolveItemAction(crossedSlopBeforeHold = false, holdFired = false, movedAfterHold = false),
        )
    }

    @Test fun `navigation wins the race even if the hold later fires`() {
        // crossing slop first releases the item; a late hold must not steal it back into a menu.
        assertEquals(
            ItemAction.NAVIGATION,
            resolveItemAction(crossedSlopBeforeHold = true, holdFired = true, movedAfterHold = true),
        )
    }
}
