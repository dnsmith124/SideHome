package fi.palonkorpi.sidehome.apps

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for §3's package lifecycle: what a package broadcast means, and what the home becomes
 * once an app it points at is gone.
 *
 * The Android constants used here (`Intent.ACTION_PACKAGE_*`) are Java compile-time constants, so
 * the compiler inlines their values at the call site and nothing on this path touches the stub
 * android.jar at runtime — which is what lets these run as plain JVM tests with no Robolectric and
 * no `returnDefaultValues`.
 */
class PackageLifecycleTest {

    // ---- the broadcast --------------------------------------------------------------------------

    @Test fun `a real uninstall reloads`() {
        assertEquals(
            PackageChange.RELOAD,
            packageChangeSignal(Intent.ACTION_PACKAGE_REMOVED, replacing = false),
        )
    }

    @Test fun `an app update must not prune the home it is momentarily missing from`() {
        // The landmine. An update fires PACKAGE_REMOVED with EXTRA_REPLACING a beat before the
        // PACKAGE_ADDED that puts the app back. Reloading on it would enumerate a library the app is
        // absent from, drop its home tile, and delete any folder it was the last member of — so
        // updating an app would silently cost you a hand-built grouping.
        assertEquals(
            PackageChange.IGNORE,
            packageChangeSignal(Intent.ACTION_PACKAGE_REMOVED, replacing = true),
        )
    }

    @Test fun `an install reloads`() {
        assertEquals(
            PackageChange.RELOAD,
            packageChangeSignal(Intent.ACTION_PACKAGE_ADDED, replacing = false),
        )
    }

    @Test fun `the far side of an update reloads too, and that asymmetry is the point`() {
        // Being wrong here is free — the key set is unchanged, so the prune drops nothing — and it
        // picks up a new label. Being wrong on the REMOVED side deletes a folder. So the two sides
        // of the same update are treated differently on purpose.
        assertEquals(
            PackageChange.RELOAD,
            packageChangeSignal(Intent.ACTION_PACKAGE_ADDED, replacing = true),
        )
    }

    @Test fun `anything else is ignored`() {
        assertEquals(PackageChange.IGNORE, packageChangeSignal(null, replacing = false))
        assertEquals(PackageChange.IGNORE, packageChangeSignal(Intent.ACTION_USER_PRESENT, replacing = false))
        assertEquals(PackageChange.IGNORE, packageChangeSignal(Intent.ACTION_PACKAGE_CHANGED, replacing = false))
    }

    // ---- the prune ------------------------------------------------------------------------------

    private fun app(n: String) = AppItem("$n/$n.Main")
    private fun key(n: String) = "$n/$n.Main"
    private fun installed(vararg names: String) = names.map { key(it) }.toSet()

    @Test fun `a loose tile whose app is gone is dropped`() {
        val home = listOf(app("a"), app("b"))
        assertEquals(listOf(app("a")), home.pruneMissing(installed("a")))
    }

    @Test fun `a home of installed apps is untouched`() {
        val home = listOf(app("a"), app("b"))
        assertEquals(home, home.pruneMissing(installed("a", "b")))
    }

    @Test fun `an uninstalled folder member leaves the folder standing`() {
        val home = listOf<HomeItem>(FolderItem("Media", listOf(key("a"), key("b"))))
        assertEquals(
            listOf<HomeItem>(FolderItem("Media", listOf(key("a")))),
            home.pruneMissing(installed("a")),
        )
    }

    @Test fun `a folder emptied by uninstalls deletes itself, freeing its slot`() {
        // SID-31's rule, reused rather than re-derived: an empty folder is one of four home slots
        // spent on a tile that opens onto nothing.
        val home = listOf(app("a"), FolderItem("Media", listOf(key("b"), key("c"))))
        assertEquals(listOf(app("a")), home.pruneMissing(installed("a")))
    }

    @Test fun `the prune and the strip's remove-from-folder agree about the last member`() {
        // The two ways a folder can empty. They must reach the same home or the launcher would have
        // two opinions about whether an empty folder is a thing to keep.
        val home = listOf<HomeItem>(app("a"), FolderItem("Media", listOf(key("b"))))
        assertEquals(
            home.removeFromFolder(1, key("b")),
            home.pruneMissing(installed("a")),
        )
    }

    @Test fun `a folder merely down to one member is left alone`() {
        // Dissolving it back into a loose app would second-guess an edit half-made and burn the name
        // and glyph on the way — the same call removeFromFolder makes.
        val home = listOf<HomeItem>(FolderItem("Media", listOf(key("a"), key("b")), glyph = "music"))
        assertEquals(
            listOf<HomeItem>(FolderItem("Media", listOf(key("a")), glyph = "music")),
            home.pruneMissing(installed("a")),
        )
    }

    @Test fun `order and position survive the prune`() {
        // The home is an ordered list the user arranged; pruning must not re-shuffle what is left.
        val home = listOf(app("a"), FolderItem("F", listOf(key("b"), key("c"))), app("d"))
        assertEquals(
            listOf(app("a"), FolderItem("F", listOf(key("c"))), app("d")),
            home.pruneMissing(installed("a", "c", "d")),
        )
    }

    @Test fun `an empty installed set clears the home rather than throwing`() {
        val home = listOf(app("a"), FolderItem("F", listOf(key("b"))))
        assertEquals(emptyList<HomeItem>(), home.pruneMissing(emptySet()))
    }

    @Test fun `pruning is idempotent`() {
        // It runs on every reload, and a reload can land on an already-pruned home (two broadcasts
        // in a row, or a broadcast racing the onStart catch-up).
        val home = listOf(app("a"), FolderItem("F", listOf(key("b"), key("c"))), app("d"))
        val once = home.pruneMissing(installed("a", "c"))
        assertEquals(once, once.pruneMissing(installed("a", "c")))
    }

    @Test fun `two entries of one package fall together`() {
        // Keys are package/class, so a package with two launcher activities holds two home tiles.
        // Uninstalling it takes both — the prune asks about keys, and both keys leave the set.
        val home = listOf(AppItem("p/p.One"), AppItem("p/p.Two"), app("a"))
        assertEquals(listOf(app("a")), home.pruneMissing(installed("a")))
    }
}
