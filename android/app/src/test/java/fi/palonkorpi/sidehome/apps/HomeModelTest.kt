package fi.palonkorpi.sidehome.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for the home model's pure edits. SID-28 moved these out of the Edit sheet and onto the
 * action strip, which holds the index it was raised on while the home can change underneath it —
 * so every edit has to survive a stale or wrong-typed index rather than throw.
 */
class HomeModelTest {

    private val home: List<HomeItem> = listOf(
        AppItem("a/A"),
        FolderItem("Pay", listOf("b/B", "c/C")),
        AppItem("d/D"),
    )

    // ---- placedKeys --------------------------------------------------------------------------

    @Test fun `placedKeys covers loose apps and folder members alike`() {
        assertEquals(setOf("a/A", "b/B", "c/C", "d/D"), home.placedKeys())
    }

    // ---- removeIndex -------------------------------------------------------------------------

    @Test fun `removeIndex drops just that item`() {
        assertEquals(listOf(AppItem("a/A"), AppItem("d/D")), home.removeIndex(1))
    }

    @Test fun `removeIndex on a stale index leaves the home alone`() {
        assertSame(home, home.removeIndex(9))
        assertSame(home, home.removeIndex(-1))
    }

    // ---- renameFolder ------------------------------------------------------------------------

    @Test fun `renameFolder renames only the folder, keeping its members and glyph`() {
        val out = home.renameFolder(1, "Money")
        assertEquals(FolderItem("Money", listOf("b/B", "c/C")), out[1])
        assertEquals(home[0], out[0])
        assertEquals(home[2], out[2])
    }

    @Test fun `renameFolder on an app index is a no-op`() {
        assertSame(home, home.renameFolder(0, "Money"))
    }

    @Test fun `renameFolder on a stale index is a no-op`() {
        assertSame(home, home.renameFolder(9, "Money"))
    }

    // ---- setFolderGlyph ----------------------------------------------------------------------

    @Test fun `setFolderGlyph re-glyphs only the folder`() {
        val out = home.setFolderGlyph(1, "wallet")
        assertEquals(FolderItem("Pay", listOf("b/B", "c/C"), glyph = "wallet"), out[1])
    }

    @Test fun `setFolderGlyph on an app index is a no-op`() {
        assertSame(home, home.setFolderGlyph(2, "wallet"))
    }

    @Test fun `an edit never mutates the list it was given`() {
        val before = home.toList()
        home.renameFolder(1, "Money")
        home.setFolderGlyph(1, "wallet")
        home.removeIndex(0)
        assertEquals(before, home)
    }

    // ---- moveWithinFolder (SID-94) -----------------------------------------------------------

    private val trio: List<HomeItem> = listOf(
        AppItem("x/X"),
        FolderItem("Trip", listOf("p/P", "q/Q", "r/R")),
    )

    @Test fun `moveWithinFolder moves a member up into the pointed gap`() {
        // drag r (idx 2) into the gap before q (gap 1) → p, r, q
        val out = trio.moveWithinFolder(1, from = 2, toGap = 1)
        assertEquals(FolderItem("Trip", listOf("p/P", "r/R", "q/Q")), out[1])
        assertEquals(trio[0], out[0])
    }

    @Test fun `moveWithinFolder shifts by one when dragging downward`() {
        // drag p (idx 0) below everything (gap 3) → q, r, p
        val out = trio.moveWithinFolder(1, from = 0, toGap = 3)
        assertEquals(listOf("q/Q", "r/R", "p/P"), (out[1] as FolderItem).appKeys)
    }

    @Test fun `moveWithinFolder into its own slot keeps the order`() {
        assertEquals(listOf("p/P", "q/Q", "r/R"), (trio.moveWithinFolder(1, 1, 1)[1] as FolderItem).appKeys)
        assertEquals(listOf("p/P", "q/Q", "r/R"), (trio.moveWithinFolder(1, 1, 2)[1] as FolderItem).appKeys)
    }

    @Test fun `moveWithinFolder clamps an out-of-range gap to the bottom`() {
        assertEquals(listOf("q/Q", "r/R", "p/P"), (trio.moveWithinFolder(1, 0, 99)[1] as FolderItem).appKeys)
    }

    @Test fun `moveWithinFolder on a non-folder or stale slot is a no-op`() {
        assertSame(trio, trio.moveWithinFolder(0, 0, 1))
        assertSame(trio, trio.moveWithinFolder(9, 0, 1))
    }

    @Test fun `moveWithinFolder on a stale member index is a no-op`() {
        assertSame(trio, trio.moveWithinFolder(1, from = 9, toGap = 0))
    }

    @Test fun `moveWithinFolder does not mutate the input`() {
        val before = trio.toList()
        trio.moveWithinFolder(1, 2, 0)
        assertEquals(before, trio)
    }
}
