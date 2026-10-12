package fi.palonkorpi.sidehome.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import fi.palonkorpi.sidehome.gesture.EdgeDir
import fi.palonkorpi.sidehome.gesture.GestureTuning
import fi.palonkorpi.sidehome.gesture.settlePage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * SPEC §7: where the viewport sits across the three panes — **Past | Home | Future** — and how a
 * finger moves it. Driven by SID-24's horizontal-lock output; the panes themselves are laid out by
 * [fi.palonkorpi.sidehome.gesture.PanePager] and the sky under them by
 * [fi.palonkorpi.sidehome.ui.ContinuousSky], both positioned from [panPx] alone.
 *
 * Split out of `HomeScreen.kt` so the pane scaffold and the §1 placement model stop contending for
 * one file. [HOME_PAGE] is the centre, and the centre is special: the drawer and spotlight are
 * summonable only from it (SID-24 enforces that against [isCentered]).
 */
internal const val PAST_PAGE = 0
internal const val HOME_PAGE = 1
internal const val FUTURE_PAGE = 2
private const val PAGE_COUNT = 3

/**
 * The leftmost page a swipe could ever reach. Upstream shipped Past disabled; the Pixel Edition
 * enables it per the "Left page" setting, so the live floor is [PaneState]'s `firstPage` lambda
 * (its runtime twin, like `lastPage` for the weather) and this is only the hard limit.
 */
internal const val FIRST_PAGE = PAST_PAGE

/**
 * Stiffness of the page-settle spring (SID-78 "settle feel"). Between Compose's StiffnessMediumLow
 * (400 — the old value, which visibly crept for ~150ms before moving when a settle starts from rest,
 * e.g. a didn't-quite-commit snap-back) and StiffnessMedium (1500 — the drawer's snap, a touch abrupt
 * for a full-page slide). Paired with a no-bounce damping this makes a release commit promptly and
 * decisively without a rubber-band tail. The one on-device feel knob for the pager's settle.
 */
private const val PAGER_SETTLE_STIFFNESS = 700f

@Stable
internal class PaneState(
    private val scope: CoroutineScope,
    private val containerW: () -> Float,
    private val tuning: () -> GestureTuning,
    // Only the release needs it: the arbiter reports fling velocity in dp/s (it is a feel number,
    // tuned against thresholds), and [dragPx] is px like the rest of this layer.
    private val density: () -> Float,
    // The rightmost page a swipe can reach — [FIRST_PAGE]'s runtime twin (SID-181). A lambda like
    // [containerW] because, unlike the compile-time floor, this one changes live: turning weather
    // off in settings pulls the ceiling in to Home without recreating the state.
    private val lastPage: () -> Int = { FUTURE_PAGE },
    // The leftmost page a swipe can reach — Past when the left page is on, else Home. A lambda for
    // the same reason as [lastPage]: the setting flips live.
    private val firstPage: () -> Int = { HOME_PAGE },
) {
    /** Which page is settled. Mid-gesture this is still the page we left; [dragPx] is the travel. */
    var pageIndex by mutableIntStateOf(HOME_PAGE)
        private set

    /** Live pan, in px, expressed against [pageIndex]. Negative reveals the next page. */
    var dragPx by mutableFloatStateOf(0f)
        private set

    private var job by mutableStateOf<Job?>(null)

    val isCentered: Boolean get() = pageIndex == HOME_PAGE

    /**
     * How far right of the centre home the viewport has travelled, in px — settled travel and live
     * finger together, so nothing downstream has to recombine [pageIndex] with [dragPx] and get the
     * sign wrong. The panes and the sky are both placed from this one number, which is what keeps
     * them in phase by construction rather than by two agreeing formulas.
     *
     * **Draw-phase only.** Reading it subscribes the reader to every frame of a drag, so it belongs
     * inside a `graphicsLayer`/draw lambda and never in composition scope — on this hardware a
     * per-frame recomposition of the home is the whole cost of the gesture. [pageIndex] is the
     * composition-safe read: it changes twice a swipe.
     */
    val panPx: Float get() = (pageIndex - HOME_PAGE) * containerW() - dragPx

    /**
     * The same travel as a continuous page position (PAST 0 .. FUTURE 2), for SID-27's page dots:
     * [pageIndex] is where a dot *settles*, this is where the viewport is right now. Draw-phase
     * only, for the same reason as [panPx].
     */
    val pagePosition: Float
        get() {
            val w = containerW()
            return if (w > 0f) HOME_PAGE + panPx / w else pageIndex.toFloat()
        }

    /**
     * Live horizontal pan: the finger moves the panes directly; ends are clamped and, inside an
     * edge Back-band, only the band's direction is allowed (left-edge → Past, right-edge → Future).
     */
    fun drag(dxPx: Float, edge: EdgeDir) {
        job?.cancel()
        val w = containerW()
        val lower = if (pageIndex < lastPage()) -w else 0f   // drag left (negative) reveals the next
        val upper = if (pageIndex > firstPage()) w else 0f   // drag right (positive) reveals the prev
        val (lo, hi) = when (edge) {
            EdgeDir.LEFT -> 0f to upper
            EdgeDir.RIGHT -> lower to 0f
            EdgeDir.NONE -> lower to upper
        }
        dragPx = (dragPx + dxPx).coerceIn(lo, hi)
    }

    /**
     * Animate to a page, keeping the panes visually continuous across the index/drag swap — and
     * across the release, which is what [initialVelocityPxPerS] is for.
     *
     * A spring rather than a tween, and the velocity is not optional. Every fixed-duration easing
     * starts from a standstill, so a fling handed one drops the panes to zero the instant the finger
     * leaves and eases back up: a hitch exactly at the moment the motion should be at its smoothest.
     * It is invisible on a slow drag — nothing to discontinue — and it is the whole of what a fast
     * swipe feels like. Handing the spring the speed the finger was already going is what makes the
     * release a continuation rather than a new animation.
     *
     * No bounce: a page is a place, not a physical object, and overshooting past Home to peek at
     * Past would say the panes are on elastic.
     */
    fun goTo(target: Int, initialVelocityPxPerS: Float = 0f) {
        val w = containerW()
        val np = target.coerceIn(firstPage(), lastPage())
        val settledDrag = (pageIndex - np) * w // the current frame, expressed against the old index
        job?.cancel()
        job = scope.launch {
            animate(
                initialValue = dragPx,
                targetValue = settledDrag,
                initialVelocity = initialVelocityPxPerS,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = PAGER_SETTLE_STIFFNESS,
                    visibilityThreshold = 0.5f,
                ),
            ) { v, _ -> dragPx = v }
            pageIndex = np
            dragPx = 0f
        }
    }

    /**
     * Which page a release here would land on. Its own function because the sign flip is the one
     * thing in the pager that is easy to get backwards and impossible to see in a screenshot:
     * [dragPx] is measured *with* the finger (drag right = positive), while [settlePage] asks for
     * travel toward the next, higher-index page. Off by a sign, every swipe pages the wrong way.
     */
    fun settleTarget(velocityTowardNextDpPerS: Float): Int {
        val w = containerW()
        val frac = if (w > 0f) -dragPx / w else 0f
        // Coerce to the navigable range: with the left page off, a leftward fling from Home must
        // land back on Home, never fall through to page 0 — and with weather off (SID-181) the same
        // is true of a rightward fling toward the disabled Future.
        return settlePage(pageIndex, frac, velocityTowardNextDpPerS, PAGE_COUNT, tuning())
            .coerceIn(firstPage(), lastPage())
    }

    /**
     * Let go. The velocity picks the page *and* carries into the animation that gets there — the
     * finger's own speed, converted into [dragPx]'s frame: dragPx tracks the finger (drag right is
     * positive) while the velocity is reported toward the next, higher-index page, so the two run
     * opposite. This is the same sign trap [settleTarget] exists for, one step further on.
     */
    fun settle(velocityTowardNextDpPerS: Float) = goTo(
        target = settleTarget(velocityTowardNextDpPerS),
        initialVelocityPxPerS = -velocityTowardNextDpPerS * density(),
    )

    /** Snap back to the centre with no travel — part of a reveal's blast back to bare home. */
    fun reset() {
        job?.cancel()
        pageIndex = HOME_PAGE
        dragPx = 0f
    }
}

@Composable
internal fun rememberPanes(
    containerW: Int,
    tuning: GestureTuning,
    lastPage: Int = FUTURE_PAGE,
    firstPage: Int = HOME_PAGE,
): PaneState {
    val scope = rememberCoroutineScope()
    val wState = rememberUpdatedState(containerW)
    val tuningState = rememberUpdatedState(tuning)
    val densityState = rememberUpdatedState(LocalDensity.current.density)
    val lastPageState = rememberUpdatedState(lastPage)
    val firstPageState = rememberUpdatedState(firstPage)
    return remember(scope) {
        PaneState(
            scope = scope,
            containerW = { wState.value.toFloat() },
            tuning = { tuningState.value },
            density = { densityState.value },
            lastPage = { lastPageState.value },
            firstPage = { firstPageState.value },
        )
    }
}
