package fi.palonkorpi.sidehome.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the home edits behind the in-hand placement model (SID-29, SPEC §1).
 *
 * The load-bearing property is the one the spec is built on: **grouping frees a slot**. A home
 * capped at [AppRepository.MAX_HOME] can only stay addable if dropping one thing onto another turns
 * two items into one — so every combine/nest/replace here is checked for shrinking the list, not
 * just for producing the right folder. That is what makes "home full" resolvable instead of a wall.
 *
 * The other property is index safety: the source index is held for the whole carry, and the home can
 * change underneath (an app uninstalls, a reload lands), so a stale index must put the item down,
 * never throw.
 *
 * SID-31 adds folder lifecycle to the same file, because it is the same property read backwards:
 * grouping frees a slot, so *un*grouping costs one — which is why popping a member out is the single
 * edit here a full home can refuse, and why an emptied folder has to hand its slot back.
 */
class HomePlacementTest {

    private val a = AppItem("a/A")
    private val b = AppItem("b/B")
    private val c = AppItem("c/C")
    private val pay = FolderItem("Pay", listOf("p/P", "q/Q"))

    private val home: List<HomeItem> = listOf(a, pay, b, c)

    // ---- moveIndex (reorder) -----------------------------------------------------------------

    @Test fun `moving down lands in the gap you pointed at, not one short of it`() {
        // The gap is counted before the item leaves its slot, which is what the finger is pointing
        // at; getting this off by one is the classic drag-reorder bug.
        assertEquals(listOf(pay, b, a, c), home.moveIndex(from = 0, toGap = 3))
    }

    @Test fun `moving up lands above the item you pointed above`() {
        assertEquals(listOf(a, c, pay, b), home.moveIndex(from = 3, toGap = 1))
    }

    @Test fun `moving to the ends works`() {
        assertEquals(listOf(c, a, pay, b), home.moveIndex(from = 3, toGap = 0))
        assertEquals(listOf(pay, b, c, a), home.moveIndex(from = 0, toGap = 4))
    }

    @Test fun `the two gaps either side of an item are both a no-op — dropping it back where it was`() {
        assertEquals(home, home.moveIndex(from = 1, toGap = 1))
        assertEquals(home, home.moveIndex(from = 1, toGap = 2))
    }

    @Test fun `a gap past the ends clamps rather than throwing`() {
        assertEquals(listOf(pay, b, c, a), home.moveIndex(from = 0, toGap = 99))
        assertEquals(listOf(c, a, pay, b), home.moveIndex(from = 3, toGap = -5))
    }

    @Test fun `moving a stale index leaves the home alone`() {
        assertSame(home, home.moveIndex(from = 9, toGap = 0))
    }

    @Test fun `a reorder never changes what is on the home`() {
        assertEquals(home.placedKeys(), home.moveIndex(0, 3).placedKeys())
        assertEquals(home.size, home.moveIndex(0, 3).size)
    }

    // ---- combineIntoFolder (drop onto an app body) --------------------------------------------

    @Test fun `dropping an app on an app makes one folder — two items become one, freeing a slot`() {
        val out = listOf(a, b, c).combineIntoFolder(from = 2, onto = 0)
        assertEquals(2, out.size)
        assertEquals(FolderItem("Folder", listOf("a/A", "c/C"), "folder"), out[0])
        assertEquals(b, out[1])
    }

    @Test fun `a caller that knows no categories still gets a folder, named the plain fallback`() {
        // The home model knows apps only by key, so naming needs a lookup passed in. Without one —
        // the drop layer's own tests, a home loaded before the app list — the fallback is what a
        // combine has always produced, and the drop still lands rather than waiting for a name.
        val folder = listOf(a, b).combineIntoFolder(from = 1, onto = 0).first() as FolderItem
        assertEquals(DEFAULT_FOLDER_NAME, folder.name)
        assertEquals(DEFAULT_FOLDER_GLYPH, folder.glyph)
    }

    @Test fun `a folder born of two apps that share a confident category names itself after it`() {
        // SPEC §1's day-one smart guess. The category engine is the only thing that knows; combine
        // asks it through the lookup and never re-derives a category of its own.
        val folder = listOf(a, b)
            .combineIntoFolder(from = 1, onto = 0, categoryOf = { BuiltInCategory.MONEY.category })
            .first() as FolderItem
        assertEquals("Money", folder.name)
        assertEquals("card", folder.glyph)
    }

    @Test fun `a folder born of two apps that share nothing keeps the fallback`() {
        val categories = mapOf("a/A" to BuiltInCategory.MONEY.category, "b/B" to BuiltInCategory.MEDIA.category)
        val folder = listOf(a, b)
            .combineIntoFolder(from = 1, onto = 0, categoryOf = { categories[it] })
            .first() as FolderItem
        assertEquals(DEFAULT_FOLDER_NAME, folder.name)
    }

    @Test fun `nesting into a folder never re-names it — the guess is only for a folder's birth`() {
        // Once it exists the folder is curated: its name is the user's, whether they typed it or
        // accepted the guess, and a third app arriving is not grounds to overwrite it.
        val out = listOf(FolderItem("Money", listOf("a/A")), b).nestIntoFolder(from = 1, folderIndex = 0)
        assertEquals(FolderItem("Money", listOf("a/A", "b/B")), out[0])
    }

    @Test fun `the folder lands where the target stood, so the drop reads as joining it`() {
        val out = listOf(a, b, c).combineIntoFolder(from = 0, onto = 2)
        assertEquals(listOf("c/C", "a/A"), (out[1] as FolderItem).appKeys)
        assertEquals(b, out[0])
    }

    @Test fun `a folder in hand never combines — it is already the group`() {
        // It used to absorb the app, on the reasoning that it frees a slot like any other grouping.
        // It does, but it reads as the folder eating whatever it lands on, which is not what moving
        // a folder means: you group by dropping an app *onto* a folder, not by driving the folder
        // over an app. Carrying a folder only ever moves it. resolveDropTarget declines this before
        // it can be asked, so no accept ring is shown; this is the model's backstop.
        assertSame(home, home.combineIntoFolder(from = 1, onto = 2))
    }

    @Test fun `dropping on a folder is not this move`() {
        // Body-drop onto a folder is nesting; the resolver routes it there and this stays inert.
        assertSame(home, home.combineIntoFolder(from = 0, onto = 1))
    }

    @Test fun `combining with itself or with a stale index is a no-op`() {
        assertSame(home, home.combineIntoFolder(from = 2, onto = 2))
        assertSame(home, home.combineIntoFolder(from = 9, onto = 0))
        assertSame(home, home.combineIntoFolder(from = 0, onto = 9))
    }

    // ---- nestIntoFolder (drop onto a folder) --------------------------------------------------

    @Test fun `dropping an app on a folder nests it and frees a slot`() {
        val out = home.nestIntoFolder(from = 3, folderIndex = 1)
        assertEquals(3, out.size)
        assertEquals(FolderItem("Pay", listOf("p/P", "q/Q", "c/C")), out[1])
        assertTrue(out.placedKeys().contains("c/C"))
    }

    @Test fun `nesting an app the folder already holds changes nothing but still takes it off the home`() {
        val duplicated = listOf(AppItem("p/P"), pay)
        val out = duplicated.nestIntoFolder(from = 0, folderIndex = 1)
        assertEquals(listOf(pay), out)
    }

    @Test fun `a folder dropped on a folder stays put — merging is folder lifecycle, not placement`() {
        val two = listOf(pay, FolderItem("Play", listOf("r/R")))
        assertSame(two, two.nestIntoFolder(from = 0, folderIndex = 1))
    }

    @Test fun `nesting into an app, into itself, or on a stale index is a no-op`() {
        assertSame(home, home.nestIntoFolder(from = 0, folderIndex = 2))
        assertSame(home, home.nestIntoFolder(from = 1, folderIndex = 1))
        assertSame(home, home.nestIntoFolder(from = 9, folderIndex = 1))
    }

    // ---- replaceIndex (the target beside each app) ---------------------------------------------

    @Test fun `replacing takes the app's slot and drops the app from the home`() {
        val out = home.replaceIndex(from = 3, target = 2)
        assertEquals(listOf(a, pay, c), out)
        // the replaced app is only unplaced — nothing here can uninstall it, it is still in the
        // library and one tap from coming back.
        assertFalse(out.placedKeys().contains("b/B"))
    }

    @Test fun `replacing from above the target keeps the target's slot`() {
        assertEquals(listOf(pay, a, c), home.replaceIndex(from = 0, target = 2))
    }

    @Test fun `a folder can never be replaced — it is curated, not swappable`() {
        // SPEC §1's deliberate asymmetry: a mis-drop must not be able to obliterate a grouping.
        assertSame(home, home.replaceIndex(from = 0, target = 1))
    }

    @Test fun `replacing with itself or on a stale index is a no-op`() {
        assertSame(home, home.replaceIndex(from = 2, target = 2))
        assertSame(home, home.replaceIndex(from = 9, target = 2))
        assertSame(home, home.replaceIndex(from = 0, target = 9))
    }

    // ---- removeFromFolder (the member strip's Remove cell) -------------------------------------

    @Test fun `removing a member unplaces only that app — the folder and the rest of the home stand`() {
        // Nothing here uninstalls: the app is off the home and back to being one tap away in the
        // library, exactly like an app removed from the home itself (SPEC §1).
        val out = home.removeFromFolder(folderIndex = 1, appKey = "p/P")
        assertFalse(out.placedKeys().contains("p/P"))
        assertEquals(listOf(a, out[1], b, c), out)
    }

    @Test fun `a folder down to one app stays a folder — it is a state you pass through`() {
        // Dissolving it back into a loose app would be the launcher second-guessing an edit
        // half-made: you have just taken one of two out, and the next thing you do is usually drop
        // another one in. It also keeps the folder's name and glyph, which a dissolve would burn.
        val out = home.removeFromFolder(folderIndex = 1, appKey = "p/P")
        assertEquals(FolderItem("Pay", listOf("q/Q")), out[1])
    }

    @Test fun `emptying a folder deletes it, freeing its home slot`() {
        // The lifecycle rule that pays for itself: a folder with nothing in it is a home slot — one
        // of four — spent on nothing, and there is no content left to justify the tile.
        val one = listOf(a, FolderItem("Pay", listOf("p/P")), b)
        assertEquals(listOf(a, b), one.removeFromFolder(folderIndex = 1, appKey = "p/P"))
    }

    @Test fun `removing an app the folder does not hold, or on a stale or app index, is a no-op`() {
        assertSame(home, home.removeFromFolder(1, "z/Z"))
        assertSame(home, home.removeFromFolder(0, "a/A"))
        assertSame(home, home.removeFromFolder(9, "p/P"))
    }

    // ---- popFromFolder (the member strip's Move-to-home cell) ---------------------------------

    @Test fun `a member moved to home lands beside the folder it came out of, not at the end`() {
        // It reads as the app stepping out of the folder. Appending it to the bottom of the home
        // would read as a new arrival from the library, which is not what just happened.
        val out = listOf(a, pay, b).popFromFolder(folderIndex = 1, appKey = "p/P")
        assertEquals(listOf(a, FolderItem("Pay", listOf("q/Q")), AppItem("p/P"), b), out)
    }

    @Test fun `the last member out takes the folder's own slot as the folder auto-deletes`() {
        // Ungrouping costs a slot and auto-delete refunds one, so this nets out even on a full home
        // — which is why canPopFromFolder says yes to a one-app folder at the cap.
        val full = listOf(a, FolderItem("Pay", listOf("p/P")), b, c)
        assertEquals(AppRepository.MAX_HOME, full.size)
        val out = full.popFromFolder(folderIndex = 1, appKey = "p/P")
        assertEquals(listOf(a, AppItem("p/P"), b, c), out)
        assertEquals(AppRepository.MAX_HOME, out.size)
    }

    @Test fun `popping a member is not allowed to push the home past its cap`() {
        // Ungrouping is the one move that *costs* a slot, so it is the one move a full home can
        // refuse. The strip never dims Move-to-home over it: the caller routes a full home through
        // the in-hand placement flow instead, where replace/nest/combine all make room.
        val full = listOf(a, pay, b, c)
        assertEquals(AppRepository.MAX_HOME, full.size)
        assertSame(full, full.popFromFolder(folderIndex = 1, appKey = "p/P"))
        assertFalse(full.canPopFromFolder(1))
        assertTrue(listOf(a, pay, b).canPopFromFolder(1))
        assertTrue(listOf(a, FolderItem("Pay", listOf("p/P")), b, c).canPopFromFolder(1))
    }

    @Test fun `canPopFromFolder says no to anything that is not a folder`() {
        assertFalse(home.canPopFromFolder(0))
        assertFalse(home.canPopFromFolder(9))
    }

    @Test fun `a member moved to home is still on the home — it changed slot, not existence`() {
        val out = listOf(a, pay, b).popFromFolder(1, "p/P")
        assertTrue(out.placedKeys().contains("p/P"))
        assertEquals(listOf(a, pay, b).placedKeys(), out.placedKeys())
    }

    @Test fun `popping an app the folder does not hold, or on a stale or app index, is a no-op`() {
        assertSame(home, home.popFromFolder(1, "z/Z"))
        assertSame(home, home.popFromFolder(0, "a/A"))
        assertSame(home, home.popFromFolder(9, "p/P"))
    }

    // ---- dropEmptyFolders (the other way a folder empties) ------------------------------------

    @Test fun `a folder whose every member uninstalled is dropped like one emptied by hand`() {
        // The load filters members that are no longer installed, which can empty a folder without
        // anyone touching it. Same rule, same outcome: an empty folder is not a thing to keep.
        val stale = listOf(a, FolderItem("Pay", emptyList()), b)
        assertEquals(listOf(a, b), stale.dropEmptyFolders())
    }

    @Test fun `a folder with anything left in it survives the prune`() {
        assertEquals(home, home.dropEmptyFolders())
    }

    // ---- folderAt (the one question both remove paths ask) ------------------------------------

    @Test fun `folderAt names the folder a removal would destroy, and nothing else`() {
        // The strip's Remove cell and the drag's remove pill both gate their confirm on this, so the
        // two can't drift into disagreeing about what is curated enough to deserve a question.
        assertEquals(pay, home.folderAt(1))
        assertNull(home.folderAt(0))
        assertNull(home.folderAt(9))
    }

    // ---- the property the whole model rests on -------------------------------------------------

    @Test fun `every grouping drop on a full home frees a slot`() {
        val full = listOf(a, b, c, pay)
        assertEquals(AppRepository.MAX_HOME, full.size)
        for (out in listOf(
            full.combineIntoFolder(from = 0, onto = 1),   // app onto app
            full.nestIntoFolder(from = 0, folderIndex = 3), // app onto folder
            full.replaceIndex(from = 0, target = 1),      // app onto a replace target
        )) {
            assertTrue("a full home must come free, not hit a wall", out.size < AppRepository.MAX_HOME)
        }
    }

    @Test fun `no placement ever mutates the list it was given`() {
        val before = home.toList()
        home.moveIndex(0, 3)
        home.combineIntoFolder(2, 3)
        home.nestIntoFolder(2, 1)
        home.replaceIndex(2, 3)
        assertEquals(before, home)
    }
}
