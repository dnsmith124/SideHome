package fi.palonkorpi.sidehome.gesture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs

/**
 * The three paged positions — **Past | Home | Future** — over the one continuous sky (SPEC §7/§4,
 * SID-25). Fully paged, not peeked: a committed swipe takes the centre home away, because the panes
 * are places rather than drawers.
 *
 * This is the whole scaffold, and it is deliberately nothing but three transforms:
 *  - **Nothing here recomposes during a swipe, ever.** [panPx] is read only inside `graphicsLayer`
 *    and `drawWithContent` lambdas, so a drag invalidates the draw phase and stops there. That also
 *    means no pane is ever mounted or unmounted by the pan: the home keeps its state and its
 *    entrance cascade across a trip to Future, and coming back is a matrix change, not a re-layout.
 *  - The off-viewport panes are skipped at the draw, not dimmed to nothing — an alpha-0 home still
 *    hands the GE8300 a full screen of text to blend for no pixels.
 *
 * The sky is not here, and must not be: it is one field the whole viewport pans across, so it is
 * the pager's *sibling* ([fi.palonkorpi.sidehome.ui.ContinuousSky]), not a thing each pane owns.
 * That is also the only arrangement in which it cannot cut at a seam. The two stay in phase because
 * both are placed from the same [panPx].
 *
 * [containerW] is a page's width in px; [panPx] is how far right of [home] the viewport has moved.
 */
@Composable
fun PanePager(
    containerW: Int,
    panPx: () -> Float,
    past: @Composable () -> Unit,
    home: @Composable () -> Unit,
    future: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Pane(slot = -1, containerW = containerW, panPx = panPx, content = past)
        Pane(slot = 0, containerW = containerW, panPx = panPx, content = home)
        Pane(slot = 1, containerW = containerW, panPx = panPx, content = future)
    }
}

/**
 * How present a pane's content is, from where the pane has landed: full at the centre, gone by the
 * time it is a page away. The panes tile edge-to-edge and never overlap, so this is not a fade
 * *between* two things — it is each pane dissolving into the ambient as it leaves, which is what
 * SPEC §4 means by "content on the untouched ambient": mid-swipe the sky is what you are crossing,
 * and the content is what is arriving. Pure, and in px like the rest of the decision layer.
 */
internal fun paneAlpha(txPx: Float, containerW: Float): Float =
    if (containerW <= 0f) 1f else (1f - abs(txPx) / containerW).coerceIn(0f, 1f)

/** Where a pane sits, in px from the viewport's left edge. [slot] is its page offset from home. */
internal fun paneTranslation(slot: Int, containerW: Float, panPx: Float): Float =
    slot * containerW - panPx

@Composable
private fun Pane(
    slot: Int,
    containerW: Int,
    panPx: () -> Float,
    content: @Composable () -> Unit,
) {
    val w = containerW.toFloat()
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val tx = paneTranslation(slot, w, panPx())
                translationX = tx
                alpha = paneAlpha(tx, w)
                // Auto would push a full-screen saveLayer for the fade the moment anything in the
                // pane overlaps anything else — the exact per-frame offscreen the ambient was built
                // to avoid. A pane is a sparse column of text, so modulating each op's alpha is
                // both cheaper and visually indistinguishable here.
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .drawWithContent {
                if (paneAlpha(paneTranslation(slot, w, panPx()), w) > 0.004f) drawContent()
            },
    ) {
        content()
    }
}
