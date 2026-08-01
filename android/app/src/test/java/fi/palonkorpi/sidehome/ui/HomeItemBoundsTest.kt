package fi.palonkorpi.sidehome.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SID-112: the one home-item bounds registry. These pin the two facts every reader now shares — where
 * a row sits ([HomeItemBounds.boundsOf], the strip anchor / drop targets) and what a point lands on
 * ([HomeItemBounds.hitTest], the arbiter defer + the theme-chooser long-press gate) — plus the prune
 * that stops a removed row's stale rect from swallowing a page swipe.
 */
class HomeItemBoundsTest {

    private fun bounds() = HomeItemBounds().apply {
        report(0, Rect(32f, 100f, 200f, 160f))
        report(1, Rect(32f, 160f, 200f, 220f))
    }

    @Test fun `boundsOf returns a reported rect and null for a row that never reported`() {
        val b = bounds()
        assertEquals(Rect(32f, 100f, 200f, 160f), b.boundsOf(0))
        assertNull(b.boundsOf(9))
    }

    @Test fun `hitTest returns the item under the point`() {
        val b = bounds()
        assertEquals(0, b.hitTest(Offset(100f, 130f)))
        assertEquals(1, b.hitTest(Offset(100f, 190f)))
    }

    @Test fun `hitTest is null on bare space, off to the side or below the rows`() {
        val b = bounds()
        assertNull(b.hitTest(Offset(300f, 130f))) // right of the rows
        assertNull(b.hitTest(Offset(100f, 400f))) // below the rows
    }

    @Test fun `retain drops a removed row so its stale rect no longer hit-tests`() {
        // The SID-26 trap: a favourite is removed but its last rect lingers; without the prune the
        // arbiter would still defer over that now-empty space and swallow a page swipe there.
        val b = bounds()
        b.retain(setOf(0))
        assertNull(b.boundsOf(1))
        assertNull(b.hitTest(Offset(100f, 190f)))
        assertEquals(0, b.hitTest(Offset(100f, 130f))) // the surviving row still answers
    }
}
