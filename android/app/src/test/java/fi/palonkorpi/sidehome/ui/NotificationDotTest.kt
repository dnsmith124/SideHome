package fi.palonkorpi.sidehome.ui

import androidx.compose.ui.graphics.Color
import fi.palonkorpi.sidehome.apps.BuiltInCategory
import fi.palonkorpi.sidehome.apps.AppEntry
import fi.palonkorpi.sidehome.apps.FolderItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the notification dot's pure logic (SID-46): who gets a dot.
 *
 * The decision worth pinning is the **closed folder's single aggregated dot** — a folder speaks for
 * apps it doesn't own, so it is the one place where "is this dotted?" isn't a plain set lookup, and
 * the one place where a wrong answer is invisible (a folder that never lights, or one that lights
 * forever) rather than obviously broken.
 */
class NotificationDotTest {

    private fun app(pkg: String) = AppEntry(
        label = pkg,
        packageName = pkg,
        className = "$pkg.Main",
        glyph = "app",
        category = BuiltInCategory.OTHER.category,
    )

    // ---- per-app -------------------------------------------------------------------------------

    @Test fun `an app is badged by its package, not by its home key`() {
        // Two launcher entries of the same package (key = package/class) must both light: a dot is a
        // property of the package, so the same app placed twice can't disagree with itself.
        val badged = setOf("com.example.chat")
        assertTrue(isBadged(app("com.example.chat"), badged))
        assertTrue(isBadged(app("com.example.chat").copy(className = "com.example.chat.Second"), badged))
        assertFalse(isBadged(app("com.example.maps"), badged))
    }

    @Test fun `nothing is badged when the set is empty`() {
        // The ungranted-access state: the OS never binds the listener, so the set stays empty and no
        // tile may claim a notification it can't know about.
        assertFalse(isBadged(app("com.example.chat"), emptySet()))
    }

    // ---- folder aggregation --------------------------------------------------------------------

    @Test fun `a folder lights when any one member is badged`() {
        assertTrue(
            folderIsBadged(
                listOf("com.example.maps", "com.example.chat", "com.example.notes"),
                setOf("com.example.chat"),
            ),
        )
    }

    @Test fun `a folder whose members are all clear stays dark`() {
        // The dot must not leak from the badged set at large — only the folder's own members vote.
        assertFalse(
            folderIsBadged(
                listOf("com.example.maps", "com.example.notes"),
                setOf("com.example.chat"),
            ),
        )
    }

    @Test fun `an empty folder is never badged`() {
        // `any {}` on an empty list is false, and that is the answer we want: a folder with nothing
        // in it has nothing waiting. Pinned because an aggregation that defaulted the other way
        // would light every freshly-created folder.
        assertFalse(folderIsBadged(emptyList(), setOf("com.example.chat")))
        assertFalse(folderIsBadged(emptyList(), emptySet()))
    }

    @Test fun `many badged members still make exactly one decision`() {
        // The dot is not a count: three lit members and one lit member are the same boolean. This is
        // what stops per-member pips creeping back in.
        assertTrue(
            folderIsBadged(
                listOf("com.example.chat", "com.example.mail", "com.example.sms"),
                setOf("com.example.chat", "com.example.mail", "com.example.sms"),
            ),
        )
    }

    // ---- folder aggregation over home keys ------------------------------------------------------

    @Test fun `a home folder resolves its package-slash-class keys to packages before deciding`() {
        val chat = app("com.example.chat")
        val maps = app("com.example.maps")
        val byKey = mapOf(chat.key to chat, maps.key to maps)
        val folder = FolderItem(name = "Comms", appKeys = listOf(maps.key, chat.key))

        assertTrue(folderIsBadged(folder, byKey, setOf("com.example.chat")))
        assertFalse(folderIsBadged(folder, byKey, setOf("com.example.other")))
    }

    @Test fun `a member key that no longer resolves does not vote`() {
        // An uninstalled app leaves a stale key behind; if it counted, the folder would be stuck lit
        // with no way for the user to clear it — the notification it refers to is unreachable.
        val folder = FolderItem(name = "Comms", appKeys = listOf("com.gone.app/com.gone.app.Main"))
        assertFalse(folderIsBadged(folder, emptyMap(), setOf("com.gone.app")))
    }

    // ---- tint ------------------------------------------------------------------------------------

    @Test fun `the tint is the accent when badged and absent when not`() {
        // Null-means-no-dot: LineIcon's badge param defaults to null, so un-badged is the default
        // everywhere and never depends on a colour being "off".
        val accent = Color(0xFF3366CC)
        assertTrue(badgeTint(true, accent) == accent)
        assertTrue(badgeTint(false, accent) == null)
    }
}
