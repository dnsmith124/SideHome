package fi.palonkorpi.sidehome.color

import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Colour is interpolated in OKLCH, never sRGB — a direct port of the prototype's
 * sRGB ⇄ OKLab ⇄ OKLCH pipeline. Working in perceptual space keeps dawn↔day
 * midpoints from turning to grey mud.
 */

/** Perceptual lightness [0..1], chroma, hue (radians). */
data class Lch(val l: Double, val c: Double, val h: Double)

fun clampD(x: Double, a: Double, b: Double): Double = min(b, max(a, x))
fun lerpD(a: Double, b: Double, t: Double): Double = a + (b - a) * t

fun smoothStep(e0: Double, e1: Double, x: Double): Double {
    val t = clampD((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)
}

private fun srgb2lin(c: Double): Double =
    if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

private fun lin2srgb(c: Double): Double =
    if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055

private fun rgb2oklab(r0: Double, g0: Double, b0: Double): DoubleArray {
    val r = srgb2lin(r0); val g = srgb2lin(g0); val b = srgb2lin(b0)
    val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return doubleArrayOf(
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )
}

private fun oklab2rgb(L: Double, a: Double, bb: Double): FloatArray {
    val l = (L + 0.3963377774 * a + 0.2158037573 * bb).pow(3)
    val m = (L - 0.1055613458 * a - 0.0638541728 * bb).pow(3)
    val s = (L - 0.0894841775 * a - 1.2914855480 * bb).pow(3)
    val r = lin2srgb(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s)
    val g = lin2srgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s)
    val b = lin2srgb(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
    return floatArrayOf(
        clampD(r, 0.0, 1.0).toFloat(),
        clampD(g, 0.0, 1.0).toFloat(),
        clampD(b, 0.0, 1.0).toFloat(),
    )
}

fun hexToLch(hex: String): Lch {
    val h = hex.removePrefix("#")
    val r = h.substring(0, 2).toInt(16) / 255.0
    val g = h.substring(2, 4).toInt(16) / 255.0
    val b = h.substring(4, 6).toInt(16) / 255.0
    val lab = rgb2oklab(r, g, b)
    return Lch(lab[0], hypot(lab[1], lab[2]), atan2(lab[2], lab[1]))
}

fun Lch.toColor(alpha: Float = 1f): Color {
    val a = c * cos(h)
    val b = c * sin(h)
    val rgb = oklab2rgb(l, a, b)
    return Color(rgb[0], rgb[1], rgb[2], alpha)
}

/** Shortest-hue-path interpolation between two OKLCH colours. */
fun mixLch(a: Lch, b: Lch, t: Double): Lch {
    var dh = b.h - a.h
    if (dh > Math.PI) dh -= 2 * Math.PI
    if (dh < -Math.PI) dh += 2 * Math.PI
    return Lch(lerpD(a.l, b.l, t), lerpD(a.c, b.c, t), a.h + dh * t)
}

/** Nudge a colour: add lightness, scale chroma, rotate hue. */
fun Lch.adjust(dL: Double, dC: Double = 1.0, dh: Double = 0.0): Lch =
    Lch(clampD(l + dL, 0.0, 1.0), max(0.0, c * dC), h + dh)
