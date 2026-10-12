package fi.palonkorpi.sidehome.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelvesTest {

    private fun app(key: String) = AppEntry(label = key.uppercase(), packageName = key, className = "$key.Main", glyph = "app", category = BuiltInCategory.OTHER.category)
    private val apps = listOf(app("a"), app("b"), app("c"), app("d"))
    private fun AppEntry.k() = key

    @Test fun `an app is on at most one shelf — the other shelf lets go`() {
        val a = apps[0].k()
        val s = Shelves().put(a, Shelf.HIDDEN)
        assertEquals(Shelf.HIDDEN, s.shelfOf(a))
        val moved = s.put(a, Shelf.LOCKED)
        assertEquals(Shelf.LOCKED, moved.shelfOf(a))
        assertTrue(moved.hidden.isEmpty())
        assertNull(moved.release(a).shelfOf(a))
        assertTrue(moved.release(a).isEmpty)
    }

    @Test fun `shelving splits the library and keeps its order`() {
        val s = Shelves(hidden = setOf(apps[1].k()), locked = setOf(apps[3].k(), "gone/Gone"))
        val out = apps.shelve(s)
        assertEquals(listOf(apps[0], apps[2]), out.visible)
        assertEquals(listOf(apps[1]), out.hidden)
        assertEquals(listOf(apps[3]), out.locked)
    }

    @Test fun `the home may only hold what is installed and on no shelf`() {
        val s = Shelves(hidden = setOf(apps[1].k()), locked = setOf(apps[2].k()))
        assertEquals(setOf(apps[0].k(), apps[3].k()), apps.unshelvedKeys(s))
    }

    @Test fun `a home that holds a shelved app loses it, folder and all, through the usual prune`() {
        val s = Shelves(locked = setOf(apps[2].k()))
        val home: List<HomeItem> = listOf(AppItem(apps[0].k()), FolderItem("Pay", listOf(apps[2].k())), AppItem(apps[1].k()))
        assertEquals(listOf(AppItem(apps[0].k()), AppItem(apps[1].k())), home.pruneMissing(apps.unshelvedKeys(s)))
    }
}
