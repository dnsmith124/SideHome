package fi.palonkorpi.sidehome.ui

import fi.palonkorpi.sidehome.apps.AppItem
import fi.palonkorpi.sidehome.apps.AppRepository
import fi.palonkorpi.sidehome.apps.FolderItem
import fi.palonkorpi.sidehome.apps.HomeItem
import fi.palonkorpi.sidehome.apps.placedKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the in-hand drop resolution (SID-29, SPEC §1) — which semantic a release at a point
 * means. This is where the spec's one hard rule lives: make-folder (the item's body) and replace
 * (the target beside it) must never be confused, so they are resolved from spatially separate
 * rectangles and the separation is checked here rather than eyeballed on a 480×640 screen.
 *
 * Raw px, no density: like the strip's anchor and the arbiter's thresholds, the decision layer is
 * unit-agnostic and the Compose layer converts. A 480px-wide screen; rows are 60px tall and start at
 * y=100, tiling with no space between them — so the only "gap between items" that exists is the band
 * at each row's edge, which is exactly what makes that band load-bearing.
 */
class HomeDragTest {

    private val containerW = 480f
    private val hitPx = 56f
    private val marginPx = 16f

    /** app, folder, app — the mixed home every rule has to hold on. */
    private val rows = listOf(
        HomeRowInput(0, isFolder = false, bounds = DropRect(32f, 100f, 200f, 160f)),
        HomeRowInput(1, isFolder = true, bounds = DropRect(32f, 160f, 200f, 220f)),
        HomeRowInput(2, isFolder = false, bounds = DropRect(32f, 220f, 200f, 280f)),
    )

    private val removeZone = DropRect(166f, 560f, 314f, 604f)

    private fun targets(source: Int? = null) =
        homeRowTargets(rows, source, containerW, hitPx, marginPx)

    private fun resolve(x: Float, y: Float, source: Int? = 2, hasRoom: Boolean = true) =
        resolveDropTarget(x, y, targets(source), source, removeZone, hasRoom)

    // ---- the targets each row offers -----------------------------------------------------------

    @Test fun `an app arriving from the library gets a replace target on every app, none on folders`() {
        // The asymmetry is deliberate (SPEC §1): a loose app is cheap to re-add, a folder is
        // curated — it can be opened or added to, never obliterated by a mis-drop.
        val t = targets(source = null)
        assertTrue(t[0].replace != null)
        assertNull(t[1].replace)
        assertTrue(t[2].replace != null)
    }

    @Test fun `an item already on the home is offered no replace target at all`() {
        // Replace answers exactly one question — "the home is full and I want this new app on it,
        // so what goes?" — and that question only arises for something arriving from the library.
        // Everything already on the home is placed; swapping two of them is just a reorder. Offering
        // it during an ordinary drag hung a destructive target beside every row for the whole
        // gesture, which is a lot of chances to lose an app by a few px.
        for (source in 0..2) {
            assertTrue(
                "row $source in hand must see no replace targets anywhere",
                targets(source = source).all { it.replace == null },
            )
        }
    }

    @Test fun `the replace targets line up in one column, whatever the labels do`() {
        // Rows are content-width and ragged; a target that tracked each row's own right edge would
        // wander left and right down the list. It hangs off the screen instead.
        val ragged = listOf(
            HomeRowInput(0, false, DropRect(32f, 100f, 90f, 160f)),
            HomeRowInput(1, false, DropRect(32f, 160f, 300f, 220f)),
        )
        val t = homeRowTargets(ragged, null, containerW, hitPx, marginPx)
        assertEquals(t[0].replace!!.left, t[1].replace!!.left, 0.01f)
        assertEquals(containerW - marginPx, t[0].replace!!.right, 0.01f)
    }

    @Test fun `each replace target sits level with its own row`() {
        val t = targets()
        assertEquals(rows[0].bounds.centerY, t[0].replace!!.centerY, 0.01f)
        assertEquals(rows[2].bounds.centerY, t[2].replace!!.centerY, 0.01f)
    }

    @Test fun `a replace target never reaches into the row above or below it`() {
        // The rows tile with nothing between them and are barely taller than a touch target, so a
        // square target would overlap its neighbours' and "which app did I just replace?" would come
        // down to scan order.
        val tight = listOf(
            HomeRowInput(0, false, DropRect(32f, 100f, 200f, 140f)),
            HomeRowInput(1, false, DropRect(32f, 140f, 200f, 180f)),
        )
        val t = homeRowTargets(tight, null, containerW, hitPx, marginPx)
        assertTrue(t[0].replace!!.bottom <= t[1].replace!!.top)
    }

    @Test fun `a long row is trimmed where the replace target begins`() {
        // Otherwise a long label would reach under the target and turn a replace into a make-folder
        // — the one confusion the spatial separation exists to prevent.
        val long = listOf(HomeRowInput(0, false, DropRect(32f, 100f, 470f, 160f)))
        val t = homeRowTargets(long, null, containerW, hitPx, marginPx)
        assertEquals(t[0].replace!!.left, t[0].body.right, 0.01f)
    }

    // ---- what a release means ------------------------------------------------------------------

    @Test fun `dropping on an app body combines — two items become one, freeing a slot`() {
        assertEquals(DropTarget.Combine(0), resolve(x = 100f, y = 130f))
    }

    @Test fun `dropping on a folder body nests`() {
        assertEquals(DropTarget.Nest(1), resolve(x = 100f, y = 190f))
    }

    @Test fun `a folder dropped on a folder falls into a gap rather than promising a merge`() {
        // Merging two folders dissolves a grouping the user built by hand — that's folder lifecycle
        // (SID-31), not placement, so placement declines it. The subtlety is *where* it declines:
        // nestIntoFolder only accepts apps, so a Nest here would light the accept ring, promise the
        // merge, and then quietly do nothing on release — the "silent no-op" §1 rules out. Declining
        // in the resolver is what makes the refusal visible, because the ring never appears.
        val twoFolders = listOf(
            HomeRowInput(0, isFolder = false, bounds = DropRect(32f, 100f, 200f, 160f)),
            HomeRowInput(1, isFolder = true, bounds = DropRect(32f, 160f, 200f, 220f)),
            HomeRowInput(2, isFolder = true, bounds = DropRect(32f, 220f, 200f, 280f)),
        )
        val t = homeRowTargets(twoFolders, 2, containerW, hitPx, marginPx)
        // Carrying the folder at 2 onto the dead centre of the folder at 1.
        assertEquals(DropTarget.Gap(1), resolveDropTarget(100f, 190f, t, 2, removeZone, true))
    }

    @Test fun `a folder in hand only ever travels — an app body is a gap, not a combine`() {
        // A carried folder groups nothing: it *is* the group. Swallowing the app it lands on reads
        // as the folder eating the home rather than being moved around it — you group by dropping an
        // app onto a folder, never by driving the folder over an app. So every body under a carried
        // folder is just somewhere to put it down.
        assertEquals(DropTarget.Gap(0), resolve(x = 100f, y = 130f, source = 1))
    }

    @Test fun `dropping on the replace target beside an app replaces it, never combines`() {
        // Source null = arriving from the library, the only carry that is offered replace at all.
        val t = targets(source = null)[0].replace!!
        assertEquals(DropTarget.Replace(0), resolve(x = t.centerX, y = t.centerY, source = null))
    }

    @Test fun `the replace column beside a folder is not a target — the drop falls into a gap`() {
        // A folder has no replace target, so the same x that would replace an app means nothing
        // there; it must resolve to a placement anyway rather than to nothing.
        val t = resolve(x = 420f, y = 190f)
        assertTrue("expected a gap, got $t", t is DropTarget.Gap)
    }

    @Test fun `the remove zone wins over everything under it`() {
        assertEquals(DropTarget.Remove, resolve(x = removeZone.centerX, y = removeZone.centerY))
    }

    @Test fun `a row's top edge is the gap above it and its bottom edge the gap below`() {
        // The rows tile with no space between them, so these bands *are* the reorder target.
        assertEquals(DropTarget.Gap(0), resolve(x = 100f, y = 103f))
        assertEquals(DropTarget.Gap(1), resolve(x = 100f, y = 157f))
        assertEquals(DropTarget.Gap(1), resolve(x = 100f, y = 163f))
    }

    @Test fun `the band splits a row into edge-gaps and a body that means grouping`() {
        // 30% of a 60px row = an 18px band at each edge, 24px of body in the middle. Too small and
        // reordering is impossible; too large and a folder can never be made — the on-device knob.
        val band = 60f * DROP_GAP_BAND_FRACTION
        assertEquals(DropTarget.Gap(0), resolve(x = 100f, y = 100f + band - 1f))
        assertEquals(DropTarget.Combine(0), resolve(x = 100f, y = 100f + band + 1f))
    }

    @Test fun `dropping an item back on its own body puts it down where it was`() {
        // Not a fold-into-itself and not an error: the carry just ends.
        assertEquals(DropTarget.Gap(2), resolve(x = 100f, y = 250f, source = 2))
    }

    @Test fun `a drop in the dead space around the rows falls into the nearest gap`() {
        // Never a dead-end: a stray release reads as "put it down around here".
        assertEquals(DropTarget.Gap(0), resolve(x = 240f, y = 20f))
        assertEquals(DropTarget.Gap(3), resolve(x = 240f, y = 400f))
        assertEquals(DropTarget.Gap(2), resolve(x = 300f, y = 215f))
    }

    @Test fun `an item carried in from off the home resolves the same way`() {
        // The library's add paths (SID-30) land through this same resolution — with no source row,
        // every row is a live target.
        assertEquals(DropTarget.Combine(2), resolve(x = 100f, y = 250f, source = null))
    }

    // ---- a full home, and the arrival that has to land on it (SID-30) --------------------------

    @Test fun `a full home offers an arriving app no gaps — a gap would be the 5th row`() {
        // The bands are the only place a new row could be inserted, and inserting is the one landing
        // that grows the home. At the cap there is no 5th slot to grow into, so the bands stop being
        // gaps and the row underneath answers instead: group with me, or take my place.
        //
        // It has to be withheld *here* rather than clamped in the model: a gap line drawn across a
        // full home promises a row that cannot exist, and the release would have to quietly refuse —
        // the silent no-op §1 rules out.
        assertEquals(DropTarget.Combine(0), resolve(x = 100f, y = 103f, source = null, hasRoom = false))
        assertEquals(DropTarget.Combine(0), resolve(x = 100f, y = 157f, source = null, hasRoom = false))
        assertEquals(DropTarget.Nest(1), resolve(x = 100f, y = 163f, source = null, hasRoom = false))
    }

    @Test fun `a stray drop on a full home falls onto the nearest row, since it cannot fall between`() {
        // The dead space around the rows still has to mean something (there is no error state), and
        // with no gap left to fall into, the nearest row is the only honest reading of "put it down
        // around here".
        assertEquals(DropTarget.Combine(0), resolve(x = 240f, y = 20f, source = null, hasRoom = false))
        assertEquals(DropTarget.Combine(2), resolve(x = 240f, y = 400f, source = null, hasRoom = false))
    }

    @Test fun `a full home still offers the replace targets — that is what they are for`() {
        // Replace is the answer to exactly this state, so the cap must not take it away.
        val t = targets(source = null)[2].replace!!
        assertEquals(
            DropTarget.Replace(2),
            resolve(x = t.centerX, y = t.centerY, source = null, hasRoom = false),
        )
    }

    @Test fun `the cap never closes the gaps for something already on the home`() {
        // Reordering a full home is the commonest thing there is, and it cannot grow anything — the
        // item is already one of the four. Only an arrival is asking for a slot that doesn't exist.
        assertEquals(DropTarget.Gap(0), resolve(x = 100f, y = 103f, source = 2, hasRoom = false))
    }

    // ---- the drop, applied ---------------------------------------------------------------------

    private val home: List<HomeItem> = listOf(AppItem("a/A"), FolderItem("Pay", listOf("p/P")), AppItem("b/B"))

    /** The item at home index 2 ("b/B"), in hand — the carry SID-29 shipped. */
    private val fromHome = DropSource.Home(2)

    /** An app that is on no home row, arriving out of the library — SID-30's carry. */
    private val arriving = DropSource.Library("c/C")

    @Test fun `each target routes to its own edit of the home`() {
        assertEquals(
            listOf(AppItem("b/B"), AppItem("a/A"), FolderItem("Pay", listOf("p/P"))),
            home.applyDrop(fromHome, DropTarget.Gap(0)),
        )
        assertEquals(
            listOf(AppItem("a/A"), FolderItem("Pay", listOf("p/P", "b/B"))),
            home.applyDrop(fromHome, DropTarget.Nest(1)),
        )
        assertEquals(
            listOf(FolderItem("Folder", listOf("a/A", "b/B")), FolderItem("Pay", listOf("p/P"))),
            home.applyDrop(fromHome, DropTarget.Combine(0)),
        )
        assertEquals(
            listOf(AppItem("b/B"), FolderItem("Pay", listOf("p/P"))),
            home.applyDrop(fromHome, DropTarget.Replace(0)),
        )
        assertEquals(
            listOf(AppItem("a/A"), FolderItem("Pay", listOf("p/P"))),
            home.applyDrop(fromHome, DropTarget.Remove),
        )
    }

    @Test fun `no drop of something already on the home can grow it`() {
        // Shuffling the four items around can only ever end with four or fewer, which is what lets
        // the whole reorder gesture ignore the cap.
        for (t in listOf(
            DropTarget.Gap(0), DropTarget.Nest(1), DropTarget.Combine(0),
            DropTarget.Replace(0), DropTarget.Remove,
        )) {
            assertTrue("$t grew the home", home.applyDrop(fromHome, t).size <= home.size)
        }
    }

    // ---- the arrival, applied (SID-30) ---------------------------------------------------------

    @Test fun `an arriving app lands by the same rules as one already on the home`() {
        // It has no slot to edit against, which is the whole difference — but not a different set of
        // outcomes. Each of these is the same edit the same target means for a home item.
        assertEquals(
            listOf(AppItem("a/A"), AppItem("c/C"), FolderItem("Pay", listOf("p/P")), AppItem("b/B")),
            home.applyDrop(arriving, DropTarget.Gap(1)),
        )
        assertEquals(
            listOf(FolderItem("Folder", listOf("a/A", "c/C")), FolderItem("Pay", listOf("p/P")), AppItem("b/B")),
            home.applyDrop(arriving, DropTarget.Combine(0)),
        )
        assertEquals(
            listOf(AppItem("a/A"), FolderItem("Pay", listOf("p/P", "c/C")), AppItem("b/B")),
            home.applyDrop(arriving, DropTarget.Nest(1)),
        )
        assertEquals(
            listOf(AppItem("c/C"), FolderItem("Pay", listOf("p/P")), AppItem("b/B")),
            home.applyDrop(arriving, DropTarget.Replace(0)),
        )
    }

    @Test fun `a gap below everything appends the arrival, rather than dropping it`() {
        // The gap indices count the home as it is drawn, and the one past the last row is a real
        // place to point at — the staging the arrival goes through must not swallow it.
        assertEquals(home + AppItem("c/C"), home.applyDrop(arriving, DropTarget.Gap(home.size)))
    }

    @Test fun `an app replaced by an arrival leaves the home, and the arrival takes its exact slot`() {
        val out = home.applyDrop(arriving, DropTarget.Replace(0))
        assertTrue(out.placedKeys().contains("c/C"))
        assertTrue("the replaced app is unplaced, not uninstalled", !out.placedKeys().contains("a/A"))
        assertEquals(0, out.indexOf(AppItem("c/C")))
    }

    @Test fun `Remove cancels an arrival instead of removing it — it was never on the home`() {
        // The remove pill is the finger's "not this": Back is unreachable with a hand in mid-air, so
        // the pill has to be the way out. There is nothing to take off the home, so the whole carry
        // just ends and the home is untouched — which is also what keeps it from being written.
        assertSame(home, home.applyDrop(arriving, DropTarget.Remove))
    }

    @Test fun `an arrival never lands twice — an app already on the home is not arriving`() {
        // The drawer declines the carry for an app it already shows as placed, so nothing should ever
        // ask for this; the backstop is here because a duplicate is not a cosmetic bug — it is two
        // rows launching one app, and a folder member with a ghost outside it.
        assertSame(home, home.applyDrop(DropSource.Library("b/B"), DropTarget.Gap(0)))
        assertSame(home, home.applyDrop(DropSource.Library("p/P"), DropTarget.Gap(0)))
    }

    @Test fun `only a gap grows the home, and only by one`() {
        // The property the cap rests on: every other landing folds the arrival into something already
        // there, so the resolver can hold the line by withholding the gaps alone.
        for (t in listOf(DropTarget.Nest(1), DropTarget.Combine(0), DropTarget.Replace(0), DropTarget.Remove)) {
            assertTrue("$t grew the home", home.applyDrop(arriving, t).size <= home.size)
        }
        assertEquals(home.size + 1, home.applyDrop(arriving, DropTarget.Gap(0)).size)
    }

    @Test fun `a full home takes an arrival by grouping — the wall the old add path hit is gone`() {
        // The reason both add paths resolve into this layer at all (SPEC §1): the 5th app is not
        // refused, it is asked where it goes. Every answer a full home can give is checked here,
        // because "Add to home" being dimmed at the cap is exactly what SID-30 removed.
        val full = listOf(AppItem("a/A"), AppItem("b/B"), AppItem("c/C"), FolderItem("Pay", listOf("p/P"))) +
            (4 until AppRepository.MAX_HOME).map { AppItem("f$it/F") }
        assertEquals(AppRepository.MAX_HOME, full.size)
        for (t in listOf(DropTarget.Combine(0), DropTarget.Nest(3), DropTarget.Replace(1))) {
            val out = full.applyDrop(DropSource.Library("d/D"), t)
            assertTrue("$t must land the 5th app", out.placedKeys().contains("d/D"))
            assertTrue("$t must not break the cap", out.size <= AppRepository.MAX_HOME)
        }
    }

    @Test fun `a full home of folders still takes an arrival — every body nests it`() {
        // Nothing to combine with and no replace target anywhere (folders are never replaced), so
        // nesting is the only answer left. It has to be enough on its own, or this home is a wall.
        val folders = listOf(
            HomeRowInput(0, isFolder = true, bounds = DropRect(32f, 100f, 200f, 160f)),
            HomeRowInput(1, isFolder = true, bounds = DropRect(32f, 160f, 200f, 220f)),
        )
        val t = homeRowTargets(folders, null, containerW, hitPx, marginPx)
        assertTrue("a folder is never replaceable", t.all { it.replace == null })
        assertEquals(DropTarget.Nest(1), resolveDropTarget(100f, 190f, t, null, removeZone, false))
        assertEquals(DropTarget.Nest(0), resolveDropTarget(240f, 20f, t, null, removeZone, false))
    }

    @Test fun `an arrival never mutates the home it was given`() {
        // It is staged into a copy to borrow the on-home edits; the caller's list must not see it.
        val before = home.toList()
        home.applyDrop(arriving, DropTarget.Gap(1))
        home.applyDrop(arriving, DropTarget.Combine(0))
        assertEquals(before, home)
    }
}
