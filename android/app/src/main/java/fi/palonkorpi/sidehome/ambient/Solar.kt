package fi.palonkorpi.sidehome.ambient

import fi.palonkorpi.sidehome.color.clampD
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Compact solar model (NOAA-ish, no dependency). Returns only the sun's altitude
 * in degrees — enough to drive *when* the palette turns for a latitude/season.
 * There is no literal sun object; time of day is expressed purely as colour.
 */
data class Sun(val altitude: Double, val rising: Boolean)

fun solar(hour: Double, latDeg: Double, doy: Int): Sun {
    val rad = PI / 180.0
    val decl = 23.44 * rad * sin(2 * PI * (doy - 81) / 365.24)
    val ha = (hour - 12) * 15 * rad
    val lat = latDeg * rad
    val alt = asin(
        clampD(
            sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(ha),
            -1.0, 1.0,
        )
    )
    return Sun(altitude = alt / rad, rising = hour < 12)
}

// acos kept available in case azimuth is needed later; altitude is all v1 uses.
@Suppress("unused")
private fun azimuthUnused() = acos(0.0)
