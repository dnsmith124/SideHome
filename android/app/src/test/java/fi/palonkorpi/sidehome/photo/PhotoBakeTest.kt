package fi.palonkorpi.sidehome.photo

import fi.palonkorpi.sidehome.color.argbToLch
import fi.palonkorpi.sidehome.color.hexToLch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SID-43: the bake pipeline's pure maths — extraction, blur, crop — kept as `IntArray` functions
 * precisely so this file can exist without a device.
 */
class PhotoBakeTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    // ---- extraction ------------------------------------------------------------------------

    @Test
    fun `the int pixel pipeline agrees with the hex pipeline`() {
        val viaHex = hexToLch("#3A6EA5")
        val viaInt = argbToLch(0xFF3A6EA5.toInt())
        assertEquals(viaHex.l, viaInt.l, 1e-12)
        assertEquals(viaHex.c, viaInt.c, 1e-12)
        assertEquals(viaHex.h, viaInt.h, 1e-12)
    }

    @Test
    fun `a uniform image extracts its own colour`() {
        val px = IntArray(64) { 0xFF3A6EA5.toInt() }
        val mood = averageMood(px)
        val expected = hexToLch("#3A6EA5")
        assertEquals(expected.l, mood.l, 1e-9)
        assertEquals(expected.c, mood.c, 1e-9)
        assertEquals(expected.h, mood.h, 1e-9)
    }

    @Test
    fun `a dark image reads dark, a light one light — the ink decision's input`() {
        assertTrue(averageMood(IntArray(16) { black }).l < 0.5)
        assertTrue(averageMood(IntArray(16) { white }).l > 0.55)
    }

    @Test
    fun `an even black-and-white mix averages to a mid grey, not either pole`() {
        val px = IntArray(32) { if (it % 2 == 0) black else white }
        val mood = averageMood(px)
        assertTrue(mood.l in 0.2..0.8)
        // 1e-6, not tighter: OKLab's matrix constants leave white a ~4e-8 chroma residue.
        assertEquals("a neutral mix carries no chroma", 0.0, mood.c, 1e-6)
    }

    // ---- blur ------------------------------------------------------------------------------

    @Test
    fun `blurring a flat image changes nothing`() {
        val px = IntArray(8 * 8) { 0xFF7F5040.toInt() }
        assertArrayEquals(px, boxBlurPass(px, 8, 8, radius = 2))
    }

    @Test
    fun `a blur pass smooths a hard edge`() {
        // Left half black, right half white; after one pass the pixels flanking the seam must have
        // moved off their poles toward each other.
        val w = 8; val h = 4
        val px = IntArray(w * h) { if (it % w < w / 2) black else white }
        val out = boxBlurPass(px, w, h, radius = 1)
        fun red(argb: Int) = (argb shr 16) and 0xFF
        assertTrue("dark side of the seam must lighten", red(out[w / 2 - 1]) > 0)
        assertTrue("light side of the seam must darken", red(out[w / 2]) < 255)
    }

    @Test
    fun `blur preserves overall brightness`() {
        // A box blur is an average — it must redistribute light, never create or destroy it.
        val w = 8; val h = 8
        val px = IntArray(w * h) { if (it % 3 == 0) black else white }
        val out = boxBlurPass(px, w, h, radius = 2)
        fun total(a: IntArray) = a.sumOf { ((it shr 16) and 0xFF).toLong() }
        val drift = total(out) - total(px)
        // Clamped edges allow a little drift; a sign error or window bug is orders bigger.
        assertTrue("brightness drifted by $drift", kotlin.math.abs(drift) < w * h * 8)
    }

    @Test
    fun `radius zero is the identity`() {
        val px = IntArray(16) { (0xFF shl 24) or (it * 16) }
        assertArrayEquals(px, boxBlurPass(px, 4, 4, radius = 0))
    }

    // ---- frame geometry (the framing step, as an affine map) ---------------------------------

    /** Apply the nine-value matrix to a source point — what Canvas will do with it. */
    private fun map(m: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])

    @Test
    fun `no zoom pan or twist is exactly the cover fit`() {
        // A wide source against the portrait screen: cover scale 1 (heights match), centred
        // horizontally — the old centre-crop, spoken as a matrix.
        val m = frameMatrix(1600, 640, 480, 640, zoom = 1f, panX = 0f, panY = 0f, rotDeg = 0f)
        assertEquals(1f, m[0], 1e-4f)
        assertEquals(0f, m[1], 1e-4f)
        assertEquals(1f, m[4], 1e-4f)
        val (cx, cy) = map(m, 800f, 320f)
        assertEquals(240f, cx, 1e-3f)   // source centre lands on the screen centre
        assertEquals(320f, cy, 1e-3f)
    }

    @Test
    fun `zooming holds the screen centre still`() {
        val m = frameMatrix(480, 640, 480, 640, zoom = 2f, panX = 0f, panY = 0f, rotDeg = 0f)
        assertEquals(2f, m[0], 1e-4f)
        val (cx, cy) = map(m, 240f, 320f)
        assertEquals(240f, cx, 1e-3f)
        assertEquals(320f, cy, 1e-3f)
        // and a corner flies outward, not inward
        val (x0, y0) = map(m, 0f, 0f)
        assertEquals(-240f, x0, 1e-3f)
        assertEquals(-320f, y0, 1e-3f)
    }

    @Test
    fun `panning slides the frame in screen px`() {
        val still = frameMatrix(480, 640, 480, 640, 2f, 0f, 0f, 0f)
        val moved = frameMatrix(480, 640, 480, 640, 2f, 100f, -50f, 0f)
        assertEquals(still[2] + 100f, moved[2], 1e-3f)
        assertEquals(still[5] - 50f, moved[5], 1e-3f)
        assertEquals(still[0], moved[0], 1e-6f)
    }

    @Test
    fun `a twist rotates about the screen centre`() {
        // At 90° the axes swap; whatever the angle, the source centre must stay pinned to the
        // screen centre — rotation happens around the frame, never around a corner.
        val m = frameMatrix(480, 640, 480, 640, zoom = 4f / 3f, panX = 0f, panY = 0f, rotDeg = 90f)
        assertEquals(0f, m[0], 1e-4f)
        assertEquals(-4f / 3f, m[1], 1e-3f)
        assertEquals(4f / 3f, m[3], 1e-3f)
        val (cx, cy) = map(m, 240f, 320f)
        assertEquals(240f, cx, 1e-3f)
        assertEquals(320f, cy, 1e-3f)
    }

    @Test
    fun `zoom below one is treated as the cover fit, never a letterbox`() {
        assertArrayEquals(
            frameMatrix(960, 1280, 480, 640, 1f, 0f, 0f, 0f),
            frameMatrix(960, 1280, 480, 640, 0.5f, 0f, 0f, 0f),
            1e-5f,
        )
    }

    // ---- the rotation's coverage guarantees --------------------------------------------------

    @Test
    fun `an upright photo needs no extra zoom`() {
        assertEquals(1f, rotatedMinZoom(480, 640, 480, 640, 0f), 1e-5f)
        assertEquals(1f, rotatedMinZoom(1600, 640, 480, 640, 0f), 1e-5f)
        // and neither does an upside-down one — coverage cares about the axes, not the direction
        assertEquals(1f, rotatedMinZoom(480, 640, 480, 640, 180f), 1e-4f)
    }

    @Test
    fun `a quarter turn on the portrait screen needs the aspect's square`() {
        // Screen-shaped source turned 90°: its short side must now span the screen's long side,
        // so the zoom floor is (640/480) / (480/640)... = 4/3.
        assertEquals(4f / 3f, rotatedMinZoom(480, 640, 480, 640, 90f), 1e-4f)
        assertEquals(4f / 3f, rotatedMinZoom(480, 640, 480, 640, 270f), 1e-4f)
    }

    @Test
    fun `at the cover fit there is no pan to spend`() {
        assertArrayEquals(
            floatArrayOf(0f, 0f),
            clampFramePan(480, 640, 480, 640, zoom = 1f, rotDeg = 0f, panX = 50f, panY = -80f),
            1e-4f,
        )
    }

    @Test
    fun `zoomed in, a wild pan clamps to the overhang`() {
        // At 2× a screen-shaped source overhangs 240 px sideways and 320 px down.
        assertArrayEquals(
            floatArrayOf(240f, -320f),
            clampFramePan(480, 640, 480, 640, 2f, 0f, 99_999f, -99_999f),
            1e-3f,
        )
    }

    @Test
    fun `the clamp box turns with the photo`() {
        // Turned 90° at 2×, the photo's long axis lies horizontal: the sideways budget is the
        // *height's* overhang (400) and the vertical budget is the width's (160).
        assertArrayEquals(
            floatArrayOf(400f, 160f),
            clampFramePan(480, 640, 480, 640, 2f, 90f, 99_999f, 99_999f),
            1e-3f,
        )
    }

    // ---- the zone-derived ink ground ---------------------------------------------------------

    @Test
    fun `the ink answers to the zones, not the whole image`() {
        // A bright image with a dark left column (where the text stands): the whole-image average
        // reads light, but the zone average must read dark — the round-6 navy-shirt bug.
        val w = 100; val h = 100
        val px = IntArray(w * h) { i -> if (i % w < 50) 0xFF101010.toInt() else 0xFFF0F0F0.toInt() }
        val leftZone = listOf(floatArrayOf(0f, 0f, 50f, 100f))
        assertTrue("whole image reads light", averageMood(px).l > 0.45)
        assertTrue("the text's ground reads dark", zoneLightness(px, w, h, leftZone, step = 4) < 0.3)
    }

    @Test
    fun `an empty zone sample falls back to the middle rather than dividing by zero`() {
        assertEquals(0.5, zoneLightness(IntArray(16), 4, 4, emptyList()), 1e-9)
    }
}
