package fi.palonkorpi.sidehome.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SID-110: the one overlay-stack model. These pin the exact behaviour the five hand-copied sites had
 * before it was extracted — the Back ladder order, the key-nav routing, the arbiter-detach test, the
 * text-field test, and the open-drawer guard — so the launcher scaffold can consult one source and the
 * "conflicting gesture" drift can't come back.
 */
class OverlayLadderTest {

    // ---- Back ladder: order, innermost first -----------------------------------------------

    @Test fun `each overlay in isolation pops itself`() {
        assertEquals(BackStep.Coach, backStep(OverlayStack(coach = true)))
        assertEquals(BackStep.ConfirmRemoveFolder, backStep(OverlayStack(confirmRemoveFolder = true)))
        assertEquals(BackStep.Hand, backStep(OverlayStack(hand = true)))
        assertEquals(BackStep.IconPicker, backStep(OverlayStack(iconPicker = true)))
        assertEquals(BackStep.CategoryPicker, backStep(OverlayStack(categoryPicker = true)))
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
        // Flipping either to a real BackStep (so key-nav Back also dismisses it) is a conscious
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

    // ---- key-nav routing -------------------------------------------------------------------

    @Test fun `routing precedence is strip then folder then modal then drawer then home`() {
        assertEquals(KeyRoute.Strip, keyRoute(OverlayStack(strip = true, folder = true, drawerActive = true)))
        assertEquals(KeyRoute.Folder, keyRoute(OverlayStack(folder = true, debug = true, drawerActive = true)))
        assertEquals(KeyRoute.ModalGlobalsOnly, keyRoute(OverlayStack(debug = true, drawerActive = true)))
        assertEquals(KeyRoute.Drawer, keyRoute(OverlayStack(drawerActive = true)))
        assertEquals(KeyRoute.Home, keyRoute(OverlayStack()))
    }

    @Test fun `every modal float routes to globals-only`() {
        listOf(
            OverlayStack(hand = true),
            OverlayStack(confirmRemoveFolder = true),
            OverlayStack(iconPicker = true),
            OverlayStack(categoryPicker = true),
            OverlayStack(folderIcon = true),
            OverlayStack(rename = true),
            OverlayStack(debug = true),
            OverlayStack(themeChooser = true),
        ).forEach { assertEquals(KeyRoute.ModalGlobalsOnly, keyRoute(it)) }
    }

    @Test fun `the future composer is not a modal for key routing`() {
        // Letters type into the composer (textField), but the reserved nav keys still fall to the home
        // — matching the original, which never listed futureComposer in the routing modal set.
        assertEquals(KeyRoute.Home, keyRoute(OverlayStack(futureComposer = true)))
    }

    // ---- arbiter detach --------------------------------------------------------------------

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
        ).forEach { assertTrue("$it should detach", detachesArbiter(it)) }
    }

    @Test fun `these do not detach the arbiter`() {
        // The drawer keeps the arbiter (a down-swipe drives it closed); confirm / theme / coach /
        // spotlight / gallery / off-centre panes all leave it attached too.
        listOf(
            OverlayStack(drawerActive = true),
            OverlayStack(confirmRemoveFolder = true),
            OverlayStack(themeChooser = true),
            OverlayStack(coach = true),
            OverlayStack(spotlightEngaged = true),
            OverlayStack(galleryBucket = true, drawerActive = true),
            OverlayStack(panesOffCentre = true),
            OverlayStack(),
        ).forEach { assertFalse("$it should not detach", detachesArbiter(it)) }
    }

    // ---- text-field focus ------------------------------------------------------------------

    @Test fun `the A–Z list search owns letters, the App Gallery does not`() {
        assertTrue(overlayTextFieldFocused(OverlayStack(drawerActive = true, drawerGallery = false)))
        assertFalse(overlayTextFieldFocused(OverlayStack(drawerActive = true, drawerGallery = true)))
    }

    @Test fun `rename and the future composer own letters`() {
        assertTrue(overlayTextFieldFocused(OverlayStack(rename = true)))
        assertTrue(overlayTextFieldFocused(OverlayStack(futureComposer = true)))
    }

    @Test fun `bare home has no focused field`() {
        assertFalse(overlayTextFieldFocused(OverlayStack()))
    }

    // ---- open-drawer guard -----------------------------------------------------------------

    @Test fun `bare home may open the drawer`() {
        assertTrue(canOpenDrawerFromBare(OverlayStack()))
    }

    @Test fun `each of these blocks opening the drawer`() {
        listOf(
            OverlayStack(folder = true),
            OverlayStack(strip = true),
            OverlayStack(iconPicker = true),
            OverlayStack(categoryPicker = true),
            OverlayStack(folderIcon = true),
            OverlayStack(rename = true),
            OverlayStack(debug = true),
            OverlayStack(hand = true),
            OverlayStack(themeChooser = true),
            OverlayStack(confirmRemoveFolder = true),
        ).forEach { assertFalse("$it should block open", canOpenDrawerFromBare(it)) }
    }

    @Test fun `these do not block opening the drawer`() {
        // Matching the original guard, which never listed them — they never coexist with the bare home
        // the drawer key is pressed on.
        listOf(
            OverlayStack(futureComposer = true),
            OverlayStack(galleryBucket = true),
            OverlayStack(spotlightEngaged = true),
            OverlayStack(panesOffCentre = true),
            OverlayStack(coach = true),
        ).forEach { assertTrue("$it should not block open", canOpenDrawerFromBare(it)) }
    }
}
