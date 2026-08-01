package fi.palonkorpi.sidehome.keynav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key-nav config defaults (SID-97) and the App Gallery's number→D-pad map (SID-96): the two taste
 * calls the FINAL model (SID-81) pins as strong defaults — List search-first, Gallery browse-first —
 * plus the `2↑ 8↓ 4← 6→ 5●` cross that turns the number grid into a D-pad in the gallery.
 */
class KeyNavConfigTest {

    @Test fun `defaults are List-search-first, Gallery-browse-first, highlight-from-first`() {
        val c = KeyNavConfig.DEFAULT
        assertTrue("A–Z List auto-focuses its search field", c.searchAutoFocusList)
        assertFalse("the Gallery does not — it arrives in D-pad mode", c.searchAutoFocusGallery)
        assertTrue("the highlight starts on the first item", c.highlightFromFirst)
    }

    @Test fun `gallery D-pad maps the cross positions to rocking intents`() {
        assertEquals(KeyIntent.NavUp, galleryDpadIntent(2))
        assertEquals(KeyIntent.NavDown, galleryDpadIntent(8))
        assertEquals(KeyIntent.NavLeft, galleryDpadIntent(4))
        assertEquals(KeyIntent.NavRight, galleryDpadIntent(6))
        assertEquals(KeyIntent.Select, galleryDpadIntent(5))
    }

    @Test fun `gallery D-pad leaves the grid corners and out-of-range inert`() {
        for (corner in listOf(1, 3, 7, 9)) assertNull("corner $corner is inert", galleryDpadIntent(corner))
        assertNull(galleryDpadIntent(0))
        assertNull(galleryDpadIntent(10))
    }
}
