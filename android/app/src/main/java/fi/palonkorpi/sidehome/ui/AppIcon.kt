package fi.palonkorpi.sidehome.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
fun AppIcon(packageName: String, size: Dp, placeholder: Color, modifier: Modifier = Modifier) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val icon = AppIconCache.rememberIcon(packageName, sizePx)
    val shape = RoundedCornerShape(size * APP_ICON_CORNER_FRACTION)
    if (icon == null) {
        Box(modifier.size(size).clip(shape).background(placeholder))
    } else {
        Image(bitmap = icon, contentDescription = null, modifier = modifier.size(size).clip(shape))
    }
}
