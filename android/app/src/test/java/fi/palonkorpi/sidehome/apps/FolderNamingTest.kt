package fi.palonkorpi.sidehome.apps

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for SPEC §1's smart guess: what a folder born from a drag-combine is called.
 *
 * The rule the whole file is about is that a *wrong* name is worse than a blank one. The guess is
 * free to make and free to fix (long-press → Rename), so it may only fire where the category engine
 * is actually confident; everywhere else "Folder" is the honest answer and the user names it.
 */
class FolderNamingTest {

    @Test fun `two apps that agree on a category name the folder after it, and take its glyph`() {
        assertEquals(
            FolderIdentity("Money", "card"),
            folderIdentityFor(BuiltInCategory.MONEY.category, BuiltInCategory.MONEY.category),
        )
    }

    @Test fun `two apps in the same user bucket name the folder after it`() {
        // A user bucket is "confident" like any non-Other category — its whole reason to exist is that
        // the user said these belong together, which is exactly the signal a folder name wants.
        val work = Category("user:1", "Work", "case", builtIn = false)
        assertEquals(FolderIdentity("Work", "case"), folderIdentityFor(work, work))
    }

    @Test fun `two apps that disagree have no shared story, so the folder is just a folder`() {
        // There is no honest name for "a bank and a music player" — naming it after either one would
        // describe half the folder and mislead about the other.
        assertEquals(
            FolderIdentity(DEFAULT_FOLDER_NAME, DEFAULT_FOLDER_GLYPH),
            folderIdentityFor(BuiltInCategory.MONEY.category, BuiltInCategory.MEDIA.category),
        )
    }

    @Test fun `two apps the engine shrugged at do not agree — they are two shrugs`() {
        // OTHER is CategoryEngine's honest fallback: an app lands there rather than in a wrong
        // bucket. So a pair of them is not a confident match, it is two apps nothing was known
        // about — and "Other" is a worse folder name than "Folder".
        assertEquals(
            FolderIdentity(DEFAULT_FOLDER_NAME, DEFAULT_FOLDER_GLYPH),
            folderIdentityFor(BuiltInCategory.OTHER.category, BuiltInCategory.OTHER.category),
        )
    }

    @Test fun `an app with no category at all is never a match`() {
        // A null is an app the home knows only by key — uninstalled between the drop and the read,
        // or a home loaded before the app list. Not a signal, so it can't carry a name.
        assertEquals(
            FolderIdentity(DEFAULT_FOLDER_NAME, DEFAULT_FOLDER_GLYPH),
            folderIdentityFor(null, null),
        )
        assertEquals(
            FolderIdentity(DEFAULT_FOLDER_NAME, DEFAULT_FOLDER_GLYPH),
            folderIdentityFor(BuiltInCategory.MONEY.category, null),
        )
    }

    @Test fun `every confident category can name a folder, and none of them names it Other`() {
        // A guard on the buckets themselves: they are meant to be curated on-device against the real
        // installed set, and a bucket that arrives blank-labelled would ship a blank-named folder.
        for (builtIn in BuiltInCategory.entries) {
            val identity = folderIdentityFor(builtIn.category, builtIn.category)
            if (builtIn == BuiltInCategory.OTHER) {
                assertEquals(FolderIdentity(DEFAULT_FOLDER_NAME, DEFAULT_FOLDER_GLYPH), identity)
            } else {
                assertEquals(FolderIdentity(builtIn.label, builtIn.glyph), identity)
            }
        }
    }
}
