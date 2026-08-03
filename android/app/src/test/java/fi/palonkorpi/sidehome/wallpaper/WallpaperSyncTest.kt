package fi.palonkorpi.sidehome.wallpaper

import fi.palonkorpi.sidehome.color.Lch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SID-180: the perceptual gate that keeps `setBitmap` rare. These pin the delta's geometry — it is
 * Euclidean in OKLab, so hue only matters in proportion to chroma — and the gate's two edges.
 */
class WallpaperSyncTest {

    @Test
    fun `a never-set wallpaper is always due`() {
        assertTrue(wallpaperDue(Lch(0.5, 0.1, 1.0), lastSet = null))
    }

    @Test
    fun `the same mood is never due`() {
        val mood = Lch(0.5, 0.1, 1.0)
        assertEquals(0.0, moodDelta(mood, mood), 1e-12)
        assertFalse(wallpaperDue(mood, mood))
    }

    @Test
    fun `a sub-threshold drift stays quiet`() {
        val set = Lch(0.500, 0.10, 1.0)
        val drifted = Lch(0.510, 0.10, 1.0)   // ΔL = 0.01 < 0.02
        assertFalse(wallpaperDue(drifted, set))
    }

    @Test
    fun `a visible lightness shift re-renders`() {
        val set = Lch(0.50, 0.10, 1.0)
        val dusk = Lch(0.45, 0.10, 1.0)       // ΔL = 0.05 > 0.02
        assertTrue(wallpaperDue(dusk, set))
    }

    @Test
    fun `a hue swing at zero chroma is invisible, and does not fire`() {
        // The polar form would call this a huge move; in OKLab it is exactly nothing — which is what
        // it looks like. This is why the delta is cartesian.
        val grey = Lch(0.5, 0.0, 0.0)
        val greySpun = Lch(0.5, 0.0, 3.0)
        assertEquals(0.0, moodDelta(grey, greySpun), 1e-12)
        assertFalse(wallpaperDue(greySpun, grey))
    }

    @Test
    fun `the same hue swing at full chroma fires`() {
        val vivid = Lch(0.5, 0.15, 0.0)
        val vividSpun = Lch(0.5, 0.15, 3.0)
        assertTrue(wallpaperDue(vividSpun, vivid))
    }

    @Test
    fun `the delta is symmetric`() {
        val a = Lch(0.62, 0.08, 0.7)
        val b = Lch(0.31, 0.12, 4.1)
        assertEquals(moodDelta(a, b), moodDelta(b, a), 1e-12)
    }
}
