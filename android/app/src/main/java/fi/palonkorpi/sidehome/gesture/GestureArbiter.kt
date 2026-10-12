package fi.palonkorpi.sidehome.gesture

import kotlin.math.abs

/**
 * Pure gesture-routing logic for the home — the "prototype the routing first" deliverable
 * (SID-24, SPEC §7). Everything here is unit-agnostic and Compose-free so the *feel* decisions
 * (axis-lock bias, settle thresholds, edge bands, the hold-vs-slop race) are all captured as
 * plain data + functions that can be reasoned about and tested without a device. The Compose
 * layer ([fi.palonkorpi.sidehome.ui] gesture glue) reads runtime insets/velocities and feeds
 * them here; the numbers below are the tune-on-device knobs.
 */

/** Default fraction of a surface's travel past which it settles open / pages, absent a fling.
 *  Shared so the arbiter tuning and the legacy drawer-settle path can't drift apart. */
const val DEFAULT_SETTLE_FRACTION = 0.27f

/**
 * The one hold-timeout (ms) the touch path reads (SID-111), via [GestureTuning.holdTimeoutMs] and
 * the itemPress race. Kept in one place so "how long is a hold" has exactly one answer.
 */
const val DEFAULT_HOLD_TIMEOUT_MS: Long = 450L

/** Runtime `systemGestures` insets (px) the Compose layer reads and feeds to the pure arbiter. */
data class GestureInsets(val top: Float, val bottom: Float, val left: Float, val right: Float)

/** Which axis a drag committed to once it crossed slop. Diagonals resolve to whichever won. */
enum class Axis { NONE, HORIZONTAL, VERTICAL }

/**
 * Directional constraint from starting inside a low-biased edge Back-band: a touch that lands in
 * the left band can only page toward [LEFT] (Past), the right band only toward [RIGHT] (Future).
 * Body touches are unconstrained ([NONE]).
 */
enum class EdgeDir { NONE, LEFT, RIGHT }

/**
 * What a touch that landed on a home item resolved to, from the temporal race between the
 * hold-timer and the movement-slop (iOS-springboard rule).
 */
enum class ItemAction { TAP, MENU, PICKUP, NAVIGATION }

/**
 * Every threshold that governs how the arbiter *feels*, in one place. All distances are in dp and
 * velocities in dp/s so they read the same across densities; the Compose layer scales by the
 * runtime density. These are the paper starting points — SID-24's acceptance is that they get
 * tuned on the device, not fixed here.
 */
data class GestureTuning(
    /** Jitter below this (dp from touch-down) is ignored — not even considered a drag yet. */
    val deadZoneDp: Float = 4f,
    /** Travel (dp) the finger must accumulate before the arbiter commits to an axis. */
    val slopDp: Float = 12f,
    /** Diagonal tie-breaker: >1 favours the vertical axis (drawer/spotlight are the primary act). */
    val axisBias: Float = 1.15f,
    /** Fraction of a surface's travel past which it settles open / pages, absent a fling. */
    val settleFraction: Float = DEFAULT_SETTLE_FRACTION,
    /**
     * Release speed (dp/s) that snaps a surface open/paged regardless of how far it moved. Lowered
     * from 400 (SID-78/SID-79): a quick back-flick from Future, or a brisk swipe-up to the library,
     * was under the old bar *and* short of [settleFraction], so it fell through to "stay put" and
     * rubber-banded. This one knob gates both the pager and the drawer/spotlight open. First-pass
     * value — tune on-device.
     */
    val flingVelocityDpPerS: Float = 300f,
    /**
     * The drag fraction below which a release is treated as a *near-standstill* — a flick with no
     * meaningful travel — so the release velocity is trusted to pick the direction (the deliberate
     * back-flick). At or above it the finger clearly went one way, so that drag *owns* the direction
     * and the velocity is only allowed to say "commit" (a fling), never to reverse it. This is what
     * stops the SP-01's recoil-prone end-velocity — a fast swipe often flicks back a hair at lift-off,
     * so `VelocityTracker` reports the *opposite* sign — from landing you backwards (SID-78).
     */
    val flingReverseToleranceFrac: Float = 0.08f,
    /** Horizontal width (dp) of each directional edge Back-band, when the OS reports no inset. */
    val edgeBandDp: Float = 20f,
    /** Vertical height (dp) of the raw-touch exclusion band per edge (Android caps this at 200dp). */
    val exclusionBandDp: Float = 200f,
    /** Vertical centre of the exclusion band as a fraction of height (0 = top, 1 = bottom). */
    val exclusionBiasFraction: Float = 0.62f,
    /**
     * Press-and-hold time (ms) before a touch on an item captures the pointer for menu/pickup.
     * Raised from 320 toward the platform long-press standard (500): a library scroll that begins
     * with any dwell — a beat before the finger drags, or the Helio A22 delivering the first move
     * late — used to let this short timer win, firing the strip and then reading the scroll as a
     * pickup that yanked the app out of the list. On-device knob (SID-24). Shared with the
     * hardware-key hold via [DEFAULT_HOLD_TIMEOUT_MS] (SID-111).
     */
    val holdTimeoutMs: Long = DEFAULT_HOLD_TIMEOUT_MS,
)

/**
 * Where a touch-down landed, and what it is therefore allowed to arm. Produced by [classifyStart].
 */
data class StartRegion(
    /** True when the down is below the shade inset and above the nav inset → drawer/spotlight may arm. */
    val armsVertical: Boolean,
    /** Directional edge constraint for horizontal paging (see [EdgeDir]). */
    val edgeDir: EdgeDir,
) {
    /** Short label for the debug HUD. */
    val label: String
        get() = when {
            edgeDir == EdgeDir.LEFT -> "LEFT-EDGE"
            edgeDir == EdgeDir.RIGHT -> "RIGHT-EDGE"
            armsVertical -> "BODY"
            else -> "INSET"
        }
}

/**
 * Vertical span [top, bottom] (px) of the low-biased edge exclusion band: a [bandPx]-tall window
 * centred at [biasFraction] of [height], clamped to stay on screen. Inside this span the edge
 * gives raw touch to us (directional paging); outside it the residual system-Back survives and is
 * treated as return-to-center.
 */
fun edgeExclusionSpan(height: Float, bandPx: Float, biasFraction: Float): Pair<Float, Float> {
    val band = bandPx.coerceIn(0f, height)
    val center = height * biasFraction
    var top = center - band / 2f
    var bottom = center + band / 2f
    if (top < 0f) { bottom -= top; top = 0f }
    if (bottom > height) { top -= (bottom - height); bottom = height }
    return top.coerceAtLeast(0f) to bottom.coerceAtMost(height)
}

/** Width (px) of a directional edge band: the reported gesture [inset] when the OS gives one,
 *  else the [fallbackPx] tuning. Shared by [classifyStart] and the exclusion-rect setter. */
fun edgeWidth(inset: Float, fallbackPx: Float): Float = if (inset > 0f) inset else fallbackPx

/**
 * Classify a touch-down against the runtime [systemGestures][topInset] insets and the edge bands.
 * Insets and coordinates are in px; [density] converts the dp tunings. A gesture arms vertically
 * only strictly inside the top/bottom insets; the left/right edge bands (as wide as the reported
 * gesture inset, or [GestureTuning.edgeBandDp] when the OS reports none) impose a paging direction
 * only within the low-biased exclusion span.
 */
fun classifyStart(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    insets: GestureInsets,
    tuning: GestureTuning,
    density: Float,
): StartRegion {
    val armsVertical = y > insets.top && y < height - insets.bottom
    val (bandTop, bandBottom) = edgeExclusionSpan(
        height = height,
        bandPx = tuning.exclusionBandDp * density,
        biasFraction = tuning.exclusionBiasFraction,
    )
    val inBand = y in bandTop..bandBottom
    val fallbackEdge = tuning.edgeBandDp * density
    val leftW = edgeWidth(insets.left, fallbackEdge)
    val rightW = edgeWidth(insets.right, fallbackEdge)
    val edgeDir = when {
        inBand && x <= leftW -> EdgeDir.LEFT
        inBand && x >= width - rightW -> EdgeDir.RIGHT
        else -> EdgeDir.NONE
    }
    return StartRegion(armsVertical = armsVertical, edgeDir = edgeDir)
}

/**
 * Lock a drag to its dominant axis from the movement accumulated at the moment slop was crossed.
 * [axisBias] > 1 tilts ties (and shallow diagonals) toward [Axis.VERTICAL]; a pure diagonal
 * therefore resolves to vertical, matching the drawer/spotlight being the primary gesture.
 */
fun lockAxis(dx: Float, dy: Float, axisBias: Float): Axis {
    val ax = abs(dx)
    val ay = abs(dy) * axisBias
    if (ax == 0f && ay == 0f) return Axis.NONE
    return if (ay >= ax) Axis.VERTICAL else Axis.HORIZONTAL
}

/**
 * Decide, on release, whether a surface (drawer or spotlight) settles open. [openFrac] is its
 * travel toward open in 0..1 and [openVelocityDpPerS] is positive when the finger was still moving
 * toward open. A fling either way wins outright; otherwise the settle fraction decides.
 */
fun settleOpen(openFrac: Float, openVelocityDpPerS: Float, tuning: GestureTuning): Boolean = when {
    openVelocityDpPerS >= tuning.flingVelocityDpPerS -> true
    openVelocityDpPerS <= -tuning.flingVelocityDpPerS -> false
    else -> openFrac >= tuning.settleFraction
}

/**
 * Snap the pager to a page on release. [offsetFrac] is the pan away from [current] as a fraction of
 * page width — positive toward the next (higher-index) page — and [velocityDpPerS] carries the same
 * sign convention. The result is clamped into 0 until [pageCount] − 1 (the ends don't wrap).
 *
 * The model is **drag-owns-direction** (SID-78). On the SP-01's digitizer a fast swipe routinely
 * recoils a hair at lift-off, so `VelocityTracker` hands back the *opposite* sign — trust it to
 * choose a direction and a brisk swipe lands you backwards, or, when the recoil merely cancels the
 * speed, drops through to "stay" and never settles. So once the finger has meaningfully travelled
 * (past [GestureTuning.flingReverseToleranceFrac]) that drag *names* the direction, and the velocity
 * is only allowed to decide **whether to commit** — its magnitude assists (a fling commits even short
 * of the settle fraction), but its sign can never reverse the drag. Only a genuine near-standstill —
 * no drag to trust — hands the choice to the velocity, which is the deliberate back-flick.
 */
fun settlePage(
    current: Int,
    offsetFrac: Float,
    velocityDpPerS: Float,
    pageCount: Int,
    tuning: GestureTuning,
): Int {
    val tol = tuning.flingReverseToleranceFrac
    val fling = abs(velocityDpPerS) >= tuning.flingVelocityDpPerS
    val past = abs(offsetFrac) >= tuning.settleFraction
    val dragDir = when {                       // the direction the finger actually moved the content
        offsetFrac >= tol -> 1
        offsetFrac <= -tol -> -1
        else -> 0                              // near-standstill: no meaningful drag
    }
    val step = when {
        // A meaningful drag owns the direction: commit if it went far enough OR the finger was still
        // flying (fling magnitude) — but always *its* way, never the recoil-reversed velocity's.
        dragDir != 0 -> if (past || fling) dragDir else 0
        // No drag to trust — the velocity is all there is, so a fling here is the intended back-flick.
        velocityDpPerS >= tuning.flingVelocityDpPerS -> 1
        velocityDpPerS <= -tuning.flingVelocityDpPerS -> -1
        else -> 0
    }
    return (current + step).coerceIn(0, pageCount - 1)
}

/**
 * Resolve what a touch that started on a home item becomes, from the race between the hold-timer
 * and the movement-slop (SPEC §7 "temporal ownership"):
 *  - moved past slop **before** the hold fired → [ItemAction.NAVIGATION] (item releases; the swipe
 *    works even across icons),
 *  - hold fired, then the finger moved → [ItemAction.PICKUP],
 *  - hold fired and the finger stayed still → [ItemAction.MENU],
 *  - lifted with no slop and no hold → [ItemAction.TAP] (launch).
 */
fun resolveItemAction(
    crossedSlopBeforeHold: Boolean,
    holdFired: Boolean,
    movedAfterHold: Boolean,
): ItemAction = when {
    crossedSlopBeforeHold -> ItemAction.NAVIGATION
    holdFired && movedAfterHold -> ItemAction.PICKUP
    holdFired -> ItemAction.MENU
    else -> ItemAction.TAP
}
