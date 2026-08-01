package fi.palonkorpi.sidehome.ambient

import fi.palonkorpi.sidehome.color.Lch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unit tests for the pure theme→engine-parameter seam (SPEC.md §6). These pin the four v1
 * themes as *parameter variants of the one ambient renderer*: which themes still drift with the
 * sun, which paints edge-only, and how each resolves the effective mood the renderer paints.
 */
class ThemeEngineTest {

    // Two genuinely-different solar moods (deep night vs midday) to prove pass-through / freeze.
    private val doy = 172 // near the summer solstice
    private val night = computeMood(0.0, DEFAULT_LATITUDE, doy)
    private val day = computeMood(12.0, DEFAULT_LATITUDE, doy)

    // ---- drift: only the ambient (1) and edge (4) themes follow the sun ----

    @Test fun `ambient and edge themes drift with the sun`() {
        assertTrue(driftEnabled(Theme.AMBIENT))
        assertTrue(driftEnabled(Theme.EDGE_DARK))
    }

    @Test fun `full light and full dark freeze the solar progression`() {
        assertFalse(driftEnabled(Theme.FULL_LIGHT))
        assertFalse(driftEnabled(Theme.FULL_DARK))
    }

    // ---- edge-only: only theme 4 paints the ambient as an edge over a dark centre ----

    @Test fun `only the edge theme is edge-only`() {
        assertTrue(edgeOnly(Theme.EDGE_DARK))
        assertFalse(edgeOnly(Theme.AMBIENT))
        assertFalse(edgeOnly(Theme.FULL_LIGHT))
        assertFalse(edgeOnly(Theme.FULL_DARK))
    }

    // ---- themeMood: the pinned themes ignore the solar input entirely ----

    @Test fun `full light pins its light keyframe regardless of the hour`() {
        assertEquals(ThemeKeyframes.Default.light, themeMood(Theme.FULL_LIGHT, day))
        assertEquals(themeMood(Theme.FULL_LIGHT, day), themeMood(Theme.FULL_LIGHT, night))
    }

    @Test fun `full dark pins its dark keyframe regardless of the hour`() {
        assertEquals(ThemeKeyframes.Default.dark, themeMood(Theme.FULL_DARK, day))
        assertEquals(themeMood(Theme.FULL_DARK, day), themeMood(Theme.FULL_DARK, night))
    }

    // ---- themeMood: the drifting themes pass the live solar mood straight through ----

    @Test fun `ambient passes the live solar mood through and varies with the hour`() {
        assertEquals(day, themeMood(Theme.AMBIENT, day))
        assertNotEquals(themeMood(Theme.AMBIENT, day), themeMood(Theme.AMBIENT, night))
    }

    @Test fun `edge theme caps the sky dark and boosts its chroma`() {
        val edge = themeMood(Theme.EDGE_DARK, day)
        assertEquals(day.l.coerceAtMost(EDGE_MAX_L), edge.l, 1e-9)  // lightness capped dark
        assertEquals(day.h, edge.h, 0.0)                           // hue unchanged
        assertEquals(day.c * EDGE_CHROMA_BOOST, edge.c, 1e-9)      // chroma boosted so the rim reads bold
        assertNotEquals(themeMood(Theme.EDGE_DARK, day), themeMood(Theme.EDGE_DARK, night))
    }

    // ---- custom: the chosen colour, fixed by default; opt-in day dimming (SID-177) ----

    @Test fun `custom is the chosen colour, unchanged by the hour`() {
        val hue = customHueRadians(200f)   // a seed the summer daytime sky never sits at
        val atDay = themeMood(Theme.CUSTOM, day, hue)     // customDrift defaults off
        val atNight = themeMood(Theme.CUSTOM, night, hue)
        // The colour you chose, resolved through the shared helper (not the sky's lightness).
        assertEquals(customBaseColor(hue, DEFAULT_CUSTOM_SAT), atDay)
        assertEquals(hue, atDay.h, 1e-9)
        // It does not track the sun by default — identical at every hour.
        assertEquals(atDay, atNight)
    }

    @Test fun `custom does not drift unless opted in`() {
        assertFalse(driftEnabled(Theme.CUSTOM))                    // default: fixed colour
        assertFalse(driftEnabled(Theme.CUSTOM, customDrift = false))
        assertTrue(driftEnabled(Theme.CUSTOM, customDrift = true))
        assertFalse(edgeOnly(Theme.CUSTOM))
    }

    @Test fun `custom with day-adjust dims cleanly at night — lower lightness and chroma, same hue`() {
        val hue = customHueRadians(200f)
        val atDay = themeMood(Theme.CUSTOM, day, hue, customDrift = true)
        val atNight = themeMood(Theme.CUSTOM, night, hue, customDrift = true)
        assertNotEquals(atDay, atNight)                            // now it varies with the hour
        assertTrue("night must be darker", atNight.l < atDay.l)
        // The neat-darkening guarantee: chroma falls *with* lightness, so night can't go muddy
        // (a muddy dark is low lightness with high chroma; here both drop together).
        assertTrue("night must be less saturated", atNight.c < atDay.c)
        assertEquals("hue is preserved", hue, atNight.h, 1e-9)
        // Full daytime sits at the top of the dim ramp — the undimmed chosen colour.
        assertEquals(customBaseColor(hue, DEFAULT_CUSTOM_SAT).l, atDay.l, 1e-6)
    }

    @Test fun `only custom locks the sky fields to its single hue`() {
        // Custom must read as the one chosen colour — its fields vary in tone, not hue (spread 0).
        assertTrue(hueLocked(Theme.CUSTOM))
        assertEquals(0f, fieldHueSpread(Theme.CUSTOM), 0f)
        // The ambient themes keep their full analogous-hue spread for a living, multi-hue sky.
        assertFalse(hueLocked(Theme.AMBIENT))
        assertEquals(1f, fieldHueSpread(Theme.AMBIENT), 0f)
        assertEquals(1f, fieldHueSpread(Theme.EDGE_DARK), 0f)
        assertEquals(1f, fieldHueSpread(Theme.FULL_LIGHT), 0f)
    }

    @Test fun `custom hue degrees convert to radians`() {
        assertEquals(Math.PI, customHueRadians(180f), 1e-9)
        assertEquals(0.0, customHueRadians(0f), 0.0)
    }

    @Test fun `custom saturation is a pastel-to-saturated axis, never grey`() {
        val hue = customHueRadians(200f)
        val pastel = customBaseColor(hue, 0f)              // slider's low end
        val vivid = customBaseColor(hue, CUSTOM_SAT_MAX)   // slider's high end
        // Pastel is light and lightly tinted; vivid is a touch darker and strongly tinted.
        assertTrue("pastel must be lighter than vivid", pastel.l > vivid.l)
        assertTrue("pastel must be less chromatic than vivid", pastel.c < vivid.c)
        // The low end is a real tint of the hue, not neutral grey — the whole point of SID-177.
        assertTrue("pastel keeps a chroma floor (not grey)", pastel.c > 0.0)
        assertEquals(CUSTOM_PASTEL_C, pastel.c, 1e-9)
        assertEquals(CUSTOM_VIVID_C, vivid.c, 1e-9)
        assertEquals("hue is preserved at the pastel end", hue, pastel.h, 1e-9)
        assertEquals("hue is preserved at the vivid end", hue, vivid.h, 1e-9)
    }

    // ---- the light keyframe reads light, the dark keyframe reads dark (never pure white/black) ----

    @Test fun `keyframes are tinted, not pure white or black`() {
        assertTrue("full light must read light", ThemeKeyframes.Default.light.l > 0.55)
        assertTrue("full dark must read dark", ThemeKeyframes.Default.dark.l < 0.5)
        assertTrue("full light keeps a tint", ThemeKeyframes.Default.light.c > 0.0)
        assertTrue("full dark keeps a tint", ThemeKeyframes.Default.dark.c > 0.0)
    }

    // ---- ...and each is its own theme, not the ambient sky with a lightness nudge ----

    /**
     * The point of a pinned theme is to contrast with the ambient. The first cut derived the
     * keyframes from [Lively.day] / [Lively.night] with only a lightness nudge, so full light
     * rendered #ADD6FD against a midday #A0C8EF — same hue, same chroma, ΔL 0.04 — and was
     * indistinguishable on the device. Those values score ΔE 0.042 / 0.057 here; the ones that
     * read as separate themes score 0.15 / 0.12. Lightness alone is not enough: a theme has to
     * move in hue and chroma too. The threshold sits between the two, so this fails if anyone
     * ever slides a keyframe back toward the sky it exists to contrast with.
     */
    @Test fun `pinned keyframes are perceptibly distinct from the ambient sky`() {
        assertTrue(
            "full light is too close to the midday sky — dE=${dE(ThemeKeyframes.Default.light, day)}",
            dE(ThemeKeyframes.Default.light, day) > MIN_THEME_DISTANCE,
        )
        assertTrue(
            "full dark is too close to the night sky — dE=${dE(ThemeKeyframes.Default.dark, night)}",
            dE(ThemeKeyframes.Default.dark, night) > MIN_THEME_DISTANCE,
        )
    }

    // ---- display names for the chooser tiles ----

    @Test fun `every theme has a distinct display name`() {
        val names = Theme.entries.map { it.displayName }
        assertEquals(Theme.entries.size, names.toSet().size)
        assertEquals("Ambient", Theme.AMBIENT.displayName)
    }

    private companion object {
        /** Comfortably above a just-noticeable difference; see the distinctness test's rationale. */
        const val MIN_THEME_DISTANCE = 0.09

        /** Euclidean distance in OKLab — the space the whole palette is designed in, so a plain
         *  ΔE here is a fair stand-in for "do these read as different colours".
         *  [Lch.h] is already in radians (it comes off `atan2`), so it feeds cos/sin directly —
         *  converting it again would rotate the hue and quietly invalidate the comparison. */
        fun dE(a: Lch, b: Lch): Double {
            fun lab(c: Lch) = Triple(c.l, c.c * cos(c.h), c.c * sin(c.h))
            val (l1, a1, b1) = lab(a)
            val (l2, a2, b2) = lab(b)
            return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
        }
    }
}
