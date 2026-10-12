package fi.palonkorpi.sidehome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.palonkorpi.sidehome.apps.AppEntry

/**
 * One category bubble: a tile peeking a 2×2 cluster of its members' **real** app icons (iOS
 * App-Library style), with the category label beneath. This is the sole grouped-mode tile now — the
 * §2b line-glyph variant lost the on-device comparison and was dropped, so the deliberate
 * inconsistency of real icons against the launcher's line-art is the design, not an experiment.
 *
 * The bubble never dims or tints the sky (DECISIONS): its fill is a translucent mix of the
 * foreground, so it darkens on a light sky and lightens on a dark one and stays on the one colour
 * scheme. No [androidx.compose.ui.graphics.CompositingStrategy.Offscreen] — this is a small solid
 * shape that would pay for a layer it has no gradient to amortise.
 */

/** Bubble geometry. All tune-on-device numbers — change them here, nowhere else. */
private val BUBBLE_CORNER = 26.dp
private val BUBBLE_FILL_ALPHA = 0.07f

/** The peek cluster's icon and gap, as fractions of the tile — so the cluster holds its proportions
 *  whatever width the row hands the bubble (two-up on a wide screen, three-up when packed) instead of
 *  a fixed size that only frames at one bubble count. A real icon stays recognisable down to ~34dp
 *  here; whether it still does at three-up is exactly the on-device call. */
private const val PEEK_ICON_FRACTION = 0.34f
private const val PEEK_GAP_FRACTION = 0.07f

@Composable
fun CategoryBubble(
    bucket: CategoryBucket,
    fg: Color,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = fg,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                // Square, off whatever width the row's weight hands us, so the bubble scales with the
                // screen instead of carrying a hardcoded size that only holds at 376dp.
                .aspectRatio(1f)
                .clip(RoundedCornerShape(BUBBLE_CORNER))
                .background(fg.copy(alpha = BUBBLE_FILL_ALPHA))
                // No ripple: LocalIndication is NoIndication at the launcher root.
                .clickable { onOpen() },
            contentAlignment = Alignment.Center,
        ) {
            // Size the cluster off the tile, not the screen — two icons and a gap span ~0.75 of it,
            // leaving an even margin inside the rounded corners at any bubble width.
            PeekCluster(
                apps = peekApps(bucket),
                fg = fg,
                iconSize = maxWidth * PEEK_ICON_FRACTION,
                gap = maxWidth * PEEK_GAP_FRACTION,
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = bucket.category.label,
            color = fg,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The 2×2 peek. A bucket can hold fewer than [PEEK_COUNT] (the "Other" bucket especially, since it is
 * never folded), so short rows are padded with an empty slot rather than allowed to re-centre — the
 * cluster keeps its grid and a three-app bucket reads as a three-app bucket.
 */
@Composable
private fun PeekCluster(apps: List<AppEntry>, fg: Color, iconSize: Dp, gap: Dp) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        for (row in apps.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (app in row) PeekIcon(app = app, fg = fg, size = iconSize)
                repeat(2 - row.size) { Spacer(Modifier.size(iconSize)) }
            }
        }
    }
}

/** One real app icon — the shared [AppIcon], tinting its still-loading / unloadable placeholder to
 *  sit quietly inside the bubble fill. */
@Composable
private fun PeekIcon(app: AppEntry, fg: Color, size: Dp) {
    AppIcon(packageName = app.packageName, size = size, placeholder = fg.copy(alpha = 0.10f))
}
