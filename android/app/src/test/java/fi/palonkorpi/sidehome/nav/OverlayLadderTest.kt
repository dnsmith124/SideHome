package fi.palonkorpi.sidehome.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SID-110: the one overlay-stack model. These pin the exact behaviour the hand-copied sites had
 * before it was extracted — the Back ladder order and the arbiter-detach test — so the launcher
 * scaffold can consult one source and the "conflicting gesture" drift can't come back.
 */
class OverlayLadderTest {

    // ---- Back ladder: order, innermost first -----------------------------------------------

    @Test fun `each overlay in isolation pops itself`() {
        assertEquals(BackStep.Coach, backStep(OverlayStack(coach = true)))
        assertEquals(BackStep.WallpaperConsent, backStep(OverlayStack(wallpaperConsent = true)))
        assertEquals(BackStep.ConfirmRemoveFolder, backStep(OverlayStack(confirmRemoveFolder = true)))
        assertEquals(BackStep.Hand, backStep(OverlayStack(hand = true)))
        assertEquals(BackStep.IconPicker, backStep(OverlayStack(iconPicker = true)))
        assertEquals(BackStep.CategoryPicker, backStep(OverlayStack(categoryPicker = true)))
        assertEquals(BackStep.PhotoPositioner, backStep(OverlayStack(photoPositioner = true)))
        assertEquals(BackStep.FolderIcon, backStep(OverlayStack(folderIcon = true)))
        assertEquals(BackStep.Rename, backStep(OverlayStack(rename = true)))
        assertEquals(BackStep.Strip, backStep(OverlayStack(strip = true)))
        assertEquals(BackStep.Debug, backStep(OverlayStack(debug = true)))
        assertEquals(BackStep.Drawer, backStep(OverlayStack(drawerActive = true)))
        assertEquals(BackStep.Spotlight, backStep(OverlayStack(spotlightEngaged = true)))
        assertEquals(BackStep.RecentrePanes, backStep(OverlayStack(panesOffCentre = true)))
        assertEquals(BackStep.Folder, backStep(OverlayStack(folder = true)))
    }

    @Test fun `bare home pops nothing`() {
        assertEquals(BackStep.None, backStep(OverlayStack()))
    }

    @Test fun `the theme chooser and the future composer carry no Back rung`() {
        // Each owns its own touch-Back (a child BackHandler), so the host ladder steps past it — a gap
        // made visible as a null Overlay.back rather than a silent omission across the old five lists.
        // Flipping either to a real BackStep is a conscious
        // on-device decision; this pins today's behaviour so that change is a deliberate test edit.
        assertEquals(BackStep.None, backStep(OverlayStack(themeChooser = true)))
        assertEquals(BackStep.None, backStep(OverlayStack(futureComposer = true)))
    }

    @Test fun `the coach sits over everything`() {
        assertEquals(
            BackStep.Coach,
            backStep(OverlayStack(coach = true, folder = true, drawerActive = true, strip = true)),
        )
    }

    @Test fun `the wallpaper consent is a question like the confirm modal`() {
        // Back answers the safe answer over anything beneath it — Back must never be the press that
        // overwrites a wallpaper. (The host only raises it once the coach is down, so the coach rung
        // above it never actually contends.)
        assertEquals(
            BackStep.WallpaperConsent,
            backStep(OverlayStack(wallpaperConsent = true, drawerActive = true, folder = true)),
        )
    }

    @Test fun `the library's multi-select pops before a bucket, and only while the drawer is up`() {
        assertEquals(BackStep.LibrarySelecting, backStep(OverlayStack(drawerActive = true, librarySelecting = true)))
        assertEquals(BackStep.LibrarySelecting, backStep(OverlayStack(drawerActive = true, librarySelecting = true, galleryBucket = true)))
        assertEquals(BackStep.None, backStep(OverlayStack(librarySelecting = true)))
    }

    @Test fun `a gallery bucket only pops while the drawer is up, and before the drawer closes`() {
        // Drilled-in bucket + drawer: Back steps out of the bucket first (SID-96).
        assertEquals(BackStep.GalleryBucket, backStep(OverlayStack(drawerActive = true, galleryBucket = true)))
        // A stray bucket flag with no drawer is not a step of its own — it never happens, but if it
        // did, it must not shadow bare home.
        assertEquals(BackStep.None, backStep(OverlayStack(galleryBucket = true)))
    }

    @Test fun `drawer then spotlight then panes then folder, in that order`() {
        assertEquals(
            BackStep.Drawer,
            backStep(OverlayStack(drawerActive = true, spotlightEngaged = true, panesOffCentre = true, folder = true)),
        )
        assertEquals(
            BackStep.Spotlight,
            backStep(OverlayStack(spotlightEngaged = true, panesOffCentre = true, folder = true)),
        )
        assertEquals(BackStep.RecentrePanes, backStep(OverlayStack(panesOffCentre = true, folder = true)))
    }

    @Test fun `a float over an open folder pops the float first`() {
        assertEquals(BackStep.Strip, backStep(OverlayStack(strip = true, folder = true)))
        assertEquals(BackStep.Hand, backStep(OverlayStack(hand = true, folder = true)))
    }


    @Test fun `these overlays detach the touch arbiter`() {
        listOf(
            OverlayStack(folder = true),
            OverlayStack(strip = true),
            OverlayStack(iconPicker = true),
            OverlayStack(categoryPicker = true),
            OverlayStack(folderIcon = true),
            OverlayStack(rename = true),
            OverlayStack(debug = true),
            OverlayStack(hand = true),
            OverlayStack(futureComposer = true),
            OverlayStack(photoPositioner = true),
        ).forEach { assertTrue("$it should detach", detachesArbiter(it)) }
    }

    @Test fun `these do not detach the arbiter`() {
        // The drawer keeps the arbiter (a down-swipe drives it closed); confirm / theme / coach /
        // spotlight / gallery / off-centre panes all leave it attached too.
        listOf(
            OverlayStack(drawerActive = true),
            OverlayStack(confirmRemoveFolder = true),
            OverlayStack(wallpaperConsent = true),
            OverlayStack(themeChooser = true),
            OverlayStack(coach = true),
            OverlayStack(spotlightEngaged = true),
            OverlayStack(galleryBucket = true, drawerActive = true),
            OverlayStack(panesOffCentre = true),
            OverlayStack(),
        ).forEach { assertFalse("$it should not detach", detachesArbiter(it)) }
    }
}
