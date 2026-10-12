package fi.palonkorpi.sidehome.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * The page-dots — **Past ● Home ● Future** (SPEC §7, SID-27), drawn for the navigable pages. The one
 * affordance in the gesture map that earns its pixels: every other swipe is invisible by design (up
 * rides on convention, down is an unmarked power gesture), but *where you are* across the places is a
 * fact the home cannot otherwise tell you — the panes are places, not drawers, so there is no peeking
 * edge to infer it from. Deliberately no bottom grabber alongside it: the issue's lean, taken.
 *
 * **They sit at the top, under the status bar, and not at the bottom** where a launcher's dots
 * conventionally live. Two reasons, and the second is the real one:
 *  - The bottom centre is taken. §1's "apps" summon is already there, and stacking the horizontal
 *    position indicator directly above the vertical affordance builds a little column of chrome —
 *    the exact clutter this issue exists to avoid. It would also land in the drawer's own swipe-up
 *    band and near Android's nav gesture.
 *  - The top strip is the band the eye already parses as *status, not content*, and skips: it holds
 *    the OS clock and battery and nothing of ours. That is what resolves the acceptance criteria's
 *    apparent contradiction — "legible on both ambients" vs "without drawing attention on the centre
 *    home". Legibility is bought with placement rather than with contrast, so the dots can stay
 *    quiet enough to disappear on the home while still being *found* the moment you look for them.
 *
 * Legibility on both light and dark ambient is not, in the end, this file's problem: [fg] is the
 * adaptive foreground (near-black on light skies, near-white on dark, DECISIONS), so the dots are
 * dark-on-light or light-on-dark by construction, whatever the sky is doing. All that is chosen here
 * is *how much* of it — and the same alphas therefore read the same on both, which a hand-picked
 * grey never would.
 */

/** A dot's radius. Fixed — see [dotAlpha] for why the active dot does not also grow. */
private val DotRadius = 2.dp

/** Centre-to-centre. Wide enough to read as three marks rather than one dash at 480×640. */
private val DotSpacing = 9.dp

/** The gap below the status bar. Clear of the OS icons, still inside the status band. */
private val DotsTopGap = 6.dp

/** A place you are not. Low enough to be scenery on the centre home, present enough to count to 3. */
internal const val DOT_IDLE_ALPHA = 0.22f

/** The place you are. */
internal const val DOT_ACTIVE_ALPHA = 0.65f

/**
 * How lit dot [index] is at a continuous [pagePosition] (`PaneState.pagePosition`: PAST 0 .. FUTURE
 * 2, tracking the finger rather than snapping on commit).
 *
 * **Opacity alone carries the state, and the dots do not animate.** Both of those are the same
 * decision seen twice:
 *  - *No size change.* A dot here is 4dp — five physical pixels on the SP-01. A radius that grew by
 *    a dp would move the edge by less than a pixel: not a bigger dot, just a blurrier one. Opacity
 *    is the only channel with any resolution left at this size.
 *  - *No animation.* This is a triangular ramp on the live pan — 1 at the dot, 0 a page away, the
 *    same shape as `paneAlpha`, which is not a coincidence: a dot lights at exactly the rate its
 *    pane arrives. So the crossfade *is* the swipe, read straight off the finger. An animation would
 *    have to chase a number that is already correct, arrive late by its own duration, and pay a
 *    frame clock for the privilege — while a fling would outrun it and land on Future with the dot
 *    still travelling. Tracking is both cheaper and the more honest of the two.
 *
 * Pure, and unit-tested: what the dots say about where you are is exactly the kind of thing that can
 * be quietly wrong (off by one, or lit backwards) while looking perfectly plausible in a screenshot.
 */
internal fun dotAlpha(pagePosition: Float, index: Int): Float {
    val nearness = (1f - abs(pagePosition - index)).coerceIn(0f, 1f)
    return DOT_IDLE_ALPHA + (DOT_ACTIVE_ALPHA - DOT_IDLE_ALPHA) * nearness
}

/**
 * The dots themselves. Host this as a **sibling over the pager, never inside a pane** — a pane's
 * content travels with it, so dots living on the centre home would pan away with the home they are
 * meant to be reporting on.
 *
 * [pagePosition] is a lambda, and that is not a style choice — it is the same rule [PanePager] and
 * [fi.palonkorpi.sidehome.gesture.GestureScene] are built on. Read as a plain parameter, a per-frame
 * float recomposes the caller (here: the whole launcher tree) on every pointer event of a drag. It
 * is read only inside the [Canvas]'s draw lambda, so a swipe invalidates the draw phase and stops
 * there — three circles repainted per frame, no recomposition, no layout.
 *
 * No compositing layer, on purpose. `CompositingStrategy.Offscreen` is right for gradient-heavy
 * content that survives a gesture unchanged (the sky), and wrong for three small solid circles that
 * change every frame: the FBO round-trip would cost more than the draws it saves.
 */
@Composable
internal fun PageDots(
    pagePosition: () -> Float,
    fg: Color,
    modifier: Modifier = Modifier,
    lastPage: Int = FUTURE_PAGE,
    firstPage: Int = HOME_PAGE,
) {
    Canvas(
        modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = DotsTopGap)
            .size(
                // Derived from the navigable pages rather than a literal count, so disabling the
                // left page — or Future, via [lastPage] (SID-181) — never leaves a dead dot drawn
                // under an unreachable pane.
                width = DotRadius * 2 + DotSpacing * (lastPage - firstPage),
                height = DotRadius * 2,
            ),
    ) {
        val r = DotRadius.toPx()
        val step = DotSpacing.toPx()
        val cy = size.height / 2f
        val pos = pagePosition()
        // [pos] and [i] are absolute page indices (Home = 1), but the dots draw from the navigable
        // first page, so the x-offset is measured from [firstPage].
        for (i in firstPage..lastPage) {
            drawCircle(
                color = fg,
                radius = r,
                center = Offset(r + (i - firstPage) * step, cy),
                alpha = dotAlpha(pos, i),
            )
        }
    }
}
