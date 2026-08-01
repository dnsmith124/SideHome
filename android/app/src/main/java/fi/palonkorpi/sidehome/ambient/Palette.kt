package fi.palonkorpi.sidehome.ambient

import androidx.compose.ui.graphics.Color
import fi.palonkorpi.sidehome.color.Lch
import fi.palonkorpi.sidehome.color.adjust
import fi.palonkorpi.sidehome.color.clampD
import fi.palonkorpi.sidehome.color.hexToLch
import fi.palonkorpi.sidehome.color.mixLch
import fi.palonkorpi.sidehome.color.smoothStep
import fi.palonkorpi.sidehome.color.toColor
import kotlin.math.exp

/**
 * "Lively" — the saturated-Nordic palette, the only scheme shipped in v1.
 * (Light / dark alternatives come later.) Four keyframes; the solar model picks
 * which ones the sky crossfades through, expressed as colour only.
 */
object Lively {
    val night = hexToLch("#131F4A")
    val dawn = hexToLch("#E293B3")
    val day = hexToLch("#A4C8EA")
    val dusk = hexToLch("#EF8746")
    const val CHROMA = 1.12

    // Adaptive foreground inks — pushed near-black / near-white for real contrast.
    val fgDarkInk = Color(0xFF080604)
    val fgLightInk = Color(0xFFFBF8F2)
}

/** Reference latitude v1 ships with; the day cycle is real, a location-derived value comes later.
 *  Shared by the home and the settings screen so their skies match. */
const val DEFAULT_LATITUDE = 60.0

/**
 * The single "mood" colour for a moment in time. Everything else — base fill,
 * drifting fields, vignette, foreground ink, accent — derives from this.
 */
fun computeMood(hour: Double, latDeg: Double, doy: Int): Lch {
    val alt = solar(hour, latDeg, doy).altitude
    val dayness = smoothStep(-6.0, 7.0, alt)
    var mood = mixLch(Lively.night, Lively.day, dayness)
    // two twilight bands: warm orange sits low; rose/pink lingers just below the horizon
    val warm = exp(-((alt - 4) / 6.0) * ((alt - 4) / 6.0))
    val pink = exp(-((alt + 3) / 5.0) * ((alt + 3) / 5.0))
    mood = mixLch(mood, Lively.dusk, warm * 0.6)
    mood = mixLch(mood, Lively.dawn, pink * 0.55)
    // saturation lever (overcast = 0 in v1)
    return Lch(mood.l, mood.c * Lively.CHROMA, mood.h)
}

/** Should the foreground ink be dark (near-black) for this mood lightness? */
fun fgDarkFor(lightness: Double, previous: Boolean): Boolean = when {
    lightness > 0.55 -> true     // narrow hysteresis band: flips decisively, no muddy zone
    lightness < 0.50 -> false
    else -> previous
}

fun foregroundInk(dark: Boolean): Color = if (dark) Lively.fgDarkInk else Lively.fgLightInk

/**
 * Accent derived from the sky each frame: the mood's own hue, cranked saturated
 * and set to a legible lightness against the ground (darker by day, lighter by
 * night). Purely sky-derived — there is deliberately no user hue shift.
 */
fun accentColor(mood: Lch, fgDark: Boolean): Color =
    Lch(if (fgDark) 0.52 else 0.80, 0.16, mood.h).toColor()

// ---- colours used by the ambient renderer (mirrors the prototype's paint()) ----

fun baseFill(mood: Lch): Color = mood.adjust(-0.04, 1.0).toColor()

/** A representative flat sky colour — the solid backdrop shown behind overlays (no blur).
 *  Tuned to match the *perceived* home background: the ambient starts from baseFill (mood −0.04)
 *  and is then darkened at the edges by the vignette, so a flat fill at the mood lightness reads
 *  noticeably brighter than the home. Sitting a touch below baseFill makes the backdrop blend in
 *  rather than glow. */
fun skyTone(mood: Lch): Color = Lch(clampD(mood.l - 0.05, 0.0, 1.0), mood.c, mood.h).toColor()

/** A slightly lifted tone for small floating sheets (app action menu), so they read as a surface
 *  above the backdrop without a hard card edge. */
fun sheetTone(mood: Lch): Color = Lch(clampD(mood.l + 0.06, 0.0, 1.0), mood.c, mood.h).toColor()

/** A drifting field's colour, from its lightness/chroma/hue offsets. */
fun fieldColor(mood: Lch, dl: Double, dc: Double, dh: Double, alpha: Float): Color =
    mood.adjust(dl, dc, dh).toColor(alpha)

/** Outer vignette alpha grows as the sky darkens, for depth + legibility. */
fun vignetteAlpha(mood: Lch): Float =
    (0.18 + (1 - clampD(mood.l, 0.0, 1.0)) * 0.3).toFloat()
