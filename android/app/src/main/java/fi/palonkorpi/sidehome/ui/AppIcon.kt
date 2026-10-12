package fi.palonkorpi.sidehome.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Alignment
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/** Corner rounding of a real app icon, as a fraction of its size — roughly Android's own squircle
 *  mask. Adaptive icons hand us a full-bleed square (the raw drawable, not the launcher-masked
 *  shape), so without this an icon is a hard-edged colour block. */
private const val APP_ICON_CORNER_FRACTION = 0.24f

/**
 * One real launcher app icon at [size], masked to a squircle. The raster lands from [AppIconCache]
 * off the main thread, so early frames have nothing to draw — [placeholder] holds the slot at exactly
 * the icon's size so nothing reflows as icons trickle in, and is also where an app with no loadable
 * icon rests rather than a gap.
 *
 * Shared by the App-Library peek clusters ([CategoryBubble]) and the opened-bucket member rows
 * ([FolderPopover] in grouped mode) so the two renderings of "the app's real icon" cannot drift.
 */
@Composable
fun AppIcon(packageName: String, size: Dp, placeholder: Color, modifier: Modifier = Modifier, mono: Boolean = false) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val icon = AppIconCache.rememberIcon(packageName, sizePx)
    val shape = RoundedCornerShape(size * APP_ICON_CORNER_FRACTION)
    if (icon == null) {
        Box(modifier.size(size).clip(shape).background(placeholder))
    } else {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = modifier.size(size).clip(shape),
            // Greyscale is a saturation of zero — the icon's own shapes, in the sky's quiet.
            colorFilter = if (mono) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
        )
    }
}

/**
 * How every app is drawn, launcher-wide (Pixel Edition, a setting): SideHome's own line glyph, the
 * app's real icon, that icon in greyscale, or no icon at all — labels alone. One answer for the home, the library, folders, buckets,
 * shelves and the bubbles' peek clusters, so an app looks the same wherever it appears.
 */
enum class IconStyle { GLYPH, REAL, MONO, NONE }

/** The live style, provided once at the launcher root so no call site has to carry it. */
val LocalIconStyle = staticCompositionLocalOf { IconStyle.GLYPH }

/** A real icon's badge diameter as a fraction of the icon: sized to the icon, not the stroke (a
 *  raster has none), and pushed past the corner onto the sky where it reads against a busy icon. */
private const val REAL_ICON_BADGE_FRACTION = 0.21f

/**
 * An app's mark in the current [IconStyle]: the line [glyph] via [LineIcon], or the real icon for
 * [packageName]. A null package (a folder) always draws the glyph. The notification [badge] rides
 * either — on a glyph as [LineIcon] draws it, on a raster as an overlaid dot.
 */
@Composable
fun AppMark(
    packageName: String?,
    glyph: String,
    color: Color,
    size: Dp,
    strokeWidthUnits: Float = 1.55f,
    badge: Color? = null,
    modifier: Modifier = Modifier,
) {
    val style = LocalIconStyle.current
    if (style == IconStyle.NONE) return
    if (packageName == null || style == IconStyle.GLYPH) {
        LineIcon(glyph = glyph, color = color, size = size, strokeWidthUnits = strokeWidthUnits, modifier = modifier, badge = badge)
        return
    }
    Box(modifier.size(size)) {
        AppIcon(packageName = packageName, size = size, placeholder = color.copy(alpha = 0.12f), mono = style == IconStyle.MONO)
        if (badge != null) {
            val diameter = size * REAL_ICON_BADGE_FRACTION
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = diameter * BadgeMetrics.Overhang, y = -diameter * BadgeMetrics.Overhang)
                    .size(diameter)
                    .background(badge, CircleShape),
            )
        }
    }
}

/**
 * A row's mark and the gap after it, together — so a row can drop both when the style is
 * [IconStyle.NONE]. With no icon a notification [badge] still has to land somewhere: it becomes a
 * small dot where the icon was, with a tighter gap, so a lit app still reads as lit.
 */
@Composable
fun RowMark(
    packageName: String?,
    glyph: String,
    color: Color,
    size: Dp,
    gap: Dp,
    strokeWidthUnits: Float = 1.55f,
    badge: Color? = null,
) {
    if (LocalIconStyle.current == IconStyle.NONE) {
        if (badge != null) {
            Box(Modifier.size(NO_ICON_BADGE).background(badge, CircleShape))
            Spacer(Modifier.width(10.dp))
        }
        return
    }
    AppMark(packageName = packageName, glyph = glyph, color = color, size = size, strokeWidthUnits = strokeWidthUnits, badge = badge)
    Spacer(Modifier.width(gap))
}

/** The dot that stands in for a badge when there is no icon to wear it. */
private val NO_ICON_BADGE = 7.dp
