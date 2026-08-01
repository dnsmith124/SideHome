package fi.palonkorpi.sidehome.gesture

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Compose glue for the gesture-routing arbiter (SID-24, SPEC §7). The *decisions* live in
 * [GestureArbiter] (pure, unit-tested); this file only reads runtime insets/velocities, drives the
 * pointer stream, and assembles the surfaces it routes to: the real pane scaffold ([PanePager],
 * SID-25) plus the still-stubbed spotlight sheet (SID-14) and the debug HUD. Home items are not
 * routed here — they own their own press grammar via itemPress (SID-111). Everything here is behind
 * the debug flag in [fi.palonkorpi.sidehome.ui.SideHomeApp].
 */

/** What the HUD shows about the last routing decision (page is passed separately, it's live state). */
data class GestureHudState(
    val axis: Axis = Axis.NONE,
    val region: String = "—",
    val action: String = "—",
)

/**
 * Callbacks the arbiter fires as it routes. Held in one object so the [pointerInput] captures a
 * single stable reference; the lambdas read live launcher state (drawer/spotlight/page fractions)
 * through Compose state delegates, so a stale capture still sees current values.
 */
class ArbiterHooks(
    /** True only when the center home is settled — drawer/spotlight arm here alone. */
    val onCenterPage: () -> Boolean,
    /** True while the drawer is off its rest — a vertical drag then keeps driving it (to close). */
    val drawerEngaged: () -> Boolean,
    /** True while the spotlight sheet is off its rest — vertical keeps driving it. */
    val spotlightEngaged: () -> Boolean,
    val onDrawerDrag: (dyPx: Float) -> Unit,
    val onDrawerSettle: (velocityDpPerS: Float) -> Unit,
    val onSpotlightDrag: (dyPx: Float) -> Unit,
    val onSpotlightSettle: (velocityDpPerS: Float) -> Unit,
    val onPagerDrag: (dxPx: Float, edge: EdgeDir) -> Unit,
    val onPagerSettle: (velocityTowardNextDpPerS: Float) -> Unit,
    /** A vertical drag on a side pane (Past/Future) — the host scrolls that pane's content by it. */
    val onPaneScroll: (dyPx: Float) -> Unit,
    val onHud: (axis: Axis?, region: String?, action: String?) -> Unit,
)

private enum class DragMode(val hud: String) {
    DRAWER("drawer"),
    SPOTLIGHT("spotlight"),
    PAGER("pager"),
    PANE_SCROLL("pane-scroll"),
    HELD("held"),
}

/**
 * Spotlight (the swipe-down search) ships **disabled** for v1: it is an unfinished stub (SID-14), so a
 * down-swipe from home is inert rather than pulling a dead sheet down. One line to bring it back when
 * the stub becomes real. (The drawer's down-swipe-to-close is unaffected — that routes through DRAWER,
 * not spotlight.)
 */
private const val SPOTLIGHT_ENABLED = false

/**
 * A recoil- and undersampling-robust release velocity, in the same units as [raw].
 *
 * On the SP-01's digitizer a fast flick lands few, irregular samples with a hair of recoil at
 * lift-off, so [raw] (VelocityTracker's end-velocity) is doubly unreliable: its *sign* flips (a
 * fast swipe reads as a fling the wrong way — SID-78), and its *magnitude* often falls under the
 * fling bar so a genuine quick flick is mistaken for a tiny slow drag and never commits. Both are
 * fixed from the drag's own geometry: [avgVel] is the finger's net travel over the whole drag
 * divided by its duration — its sign is the reliable direction, and its magnitude is a floor no
 * real flick can dip under. So keep [raw]'s magnitude when the tracker saw the speed, but never
 * read below the average, and always take the average's sign. A drag too short to time (no [avgVel])
 * has nothing better, so [raw] passes through.
 */
private fun recoilRobust(raw: Float, avgVel: Float): Float =
    if (avgVel == 0f) raw else sign(avgVel) * maxOf(abs(raw), abs(avgVel))

/**
 * The dead-zone → axis-lock → route arbiter as a single root [pointerInput]. One touch-down is
 * classified against the insets/edge-bands, then a race resolves: cross slop → navigation
 * (axis-locked to drawer/spotlight or the pager); lift first → a bare-sky tap (a no-op). Navigation
 * consumes the stream so child taps cancel — a swipe still navigates, a clean tap does nothing.
 *
 * The item grammar (tap / hold→strip / hold-drag→pickup) is **not** here: a press that lands on a
 * home favourite is yielded whole to that item's [fi.palonkorpi.sidehome.ui.itemPress] via
 * [isOverHomeItem], which is the single hold-vs-slop authority for touch (SID-111). This arbiter
 * only owns the sky between the items.
 */
fun Modifier.homeGestureArbiter(
    tuning: GestureTuning,
    density: Float,
    insets: GestureInsets,
    hooks: ArbiterHooks,
    isActive: () -> Boolean = { true },
    isOverHomeItem: (Offset) -> Boolean = { false },
): Modifier = pointerInput(tuning, insets, density) {
    val slopPx = tuning.slopDp * density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        // Detached (a modal / strip / carry is up): stay mounted but inert. This node is *always*
        // attached and gated here rather than added/removed on the flip, because tearing a pointer
        // node down mid-gesture cancels the home item's own in-flight itemPress — so a hold that
        // raised the strip could never become a pickup-drag (SID-114). Consuming nothing, the fresh
        // touch falls through to whatever surface is up, exactly as an absent arbiter would.
        if (!isActive()) return@awaitEachGesture
        // SID-26: a press that lands on a home favourite belongs to that item — its own itemPress
        // owns tap, hold→strip, and hold-then-drag→pickup (which is how you reorder). The arbiter must
        // not also read that gesture as paging or a drawer swipe, or the reorder is stolen the instant
        // the finger drifts before the hold lands. So on the centre home, an item-origin press yields
        // the whole gesture to the row and the arbiter does nothing with it. A swipe from bare sky
        // still pages. The page check matters: a pane is laid out at the origin and only translated,
        // so a home row's root bounds read as if it sat centre-screen even while it's paged away —
        // without the guard a touch on Future could match a home row and freeze paging.
        if (hooks.onCenterPage() && isOverHomeItem(start)) return@awaitEachGesture
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val region = classifyStart(
            x = start.x, y = start.y, width = w, height = h,
            insets = insets, tuning = tuning, density = density,
        )
        hooks.onHud(Axis.NONE, region.label, "down")

        val vt = VelocityTracker()
        vt.addPosition(down.uptimeMillis, start)
        // Shared accumulators — awaitPointerEvent() is only ever called directly in this lambda
        // (AwaitPointerEventScope is @RestrictsSuspension, so it can't be reached through a stored
        // receiver or a plain local function).
        var accX = 0f
        var accY = 0f

        // ---- Phase 1: race movement-slop vs release ----
        // The arbiter no longer runs a hold-timer of its own (SID-111): a press that lands on a home
        // favourite was already yielded to that item's itemPress above, and itemPress owns the whole
        // tap / hold→strip / hold-drag→pickup grammar. So on the bare sky the only question left here
        // is whether the finger crossed slop (a navigation drag) or lifted first (a sky tap).
        var crossedSlop = false
        while (true) {
            val ch = awaitPointerEvent().changes.firstOrNull() ?: continue
            vt.addPosition(ch.uptimeMillis, ch.position)
            val d = ch.positionChange(); accX += d.x; accY += d.y
            if (!ch.pressed) break
            if (hypot(accX, accY) >= slopPx) { crossedSlop = true; break }
        }

        if (!crossedSlop) {
            // Lifted without crossing slop: a tap on bare sky. Nothing to launch here — home items
            // own their own taps (itemPress) — so it is a no-op beyond the HUD.
            hooks.onHud(Axis.NONE, region.label, "tap")
        } else {
            // SLOP → navigation. Resolve where the locked drag goes, then feed it through one drain
            // loop. Up opens the drawer, down the spotlight (center home only); while a surface is
            // already open the drag keeps driving it, so a down-swipe closes it. Vertical on a side
            // pane, or horizontal behind an open surface, is a drained no-op.
            val vertical = lockAxis(accX, accY, tuning.axisBias) == Axis.VERTICAL
            val surfaceOpen = hooks.drawerEngaged() || hooks.spotlightEngaged()
            val mode = when {
                // A vertical drag only arms the drawer/spotlight when it started clear of the
                // system insets (shade / nav) — inside those bands the drag stays a drained
                // no-op so it doesn't fight the OS shade or back/nav gestures.
                vertical && region.armsVertical && (hooks.onCenterPage() || surfaceOpen) -> {
                    val wantsSpotlight = hooks.spotlightEngaged() || (!hooks.drawerEngaged() && accY >= 0f)
                    when {
                        // Spotlight ships disabled (SPOTLIGHT_ENABLED) — a down-swipe from home is
                        // inert rather than opening the stub. Up-swipes still open the drawer, and a
                        // down-swipe while the drawer is open still drives it closed (that's not a
                        // spotlight open — drawerEngaged sends it to DRAWER below).
                        wantsSpotlight && !SPOTLIGHT_ENABLED -> DragMode.HELD
                        wantsSpotlight -> DragMode.SPOTLIGHT
                        else -> DragMode.DRAWER
                    }
                }
                vertical -> DragMode.PANE_SCROLL
                surfaceOpen -> DragMode.HELD
                else -> DragMode.PAGER
            }
            hooks.onHud(
                if (vertical) Axis.VERTICAL else Axis.HORIZONTAL,
                region.label,
                if (mode == DragMode.PAGER) "pager:${region.edgeDir}" else mode.hud,
            )
            var lastPos = start
            var lastT = down.uptimeMillis
            while (true) {
                val ch = awaitPointerEvent().changes.firstOrNull() ?: continue
                vt.addPosition(ch.uptimeMillis, ch.position)
                lastPos = ch.position
                lastT = ch.uptimeMillis
                val delta = ch.positionChange()
                when (mode) {
                    DragMode.DRAWER -> hooks.onDrawerDrag(delta.y)
                    DragMode.SPOTLIGHT -> hooks.onSpotlightDrag(delta.y)
                    DragMode.PAGER -> hooks.onPagerDrag(delta.x, region.edgeDir)
                    DragMode.PANE_SCROLL -> hooks.onPaneScroll(delta.y)
                    else -> {}   // HELD → drained no-op
                }
                val lifted = ch.changedToUpIgnoreConsumed()
                ch.consume()
                if (lifted) break
            }
            // Recoil-robust release velocity (SID-78). This digitizer flicks back a hair right at
            // lift-off, so VelocityTracker often reports the *opposite* sign: a fast swipe then reads
            // as a fling the wrong way — landing you backwards, and jerking the settle spring off in
            // the wrong direction before it corrects (the "settle jitter"). The finger's net travel
            // over the whole drag is a reliable direction where the end-velocity is not, so keep the
            // tracker's speed but take the travel's sign.
            val v = vt.calculateVelocity()
            val net = lastPos - start
            // Average finger velocity over the drag (px/s), the recoil-immune floor for [recoilRobust].
            val durSec = ((lastT - down.uptimeMillis).coerceAtLeast(1L)) / 1000f
            val avgX = net.x / durSec
            val avgY = net.y / durSec
            when (mode) {
                DragMode.DRAWER -> hooks.onDrawerSettle(-recoilRobust(v.y, avgY) / density)
                DragMode.SPOTLIGHT -> hooks.onSpotlightSettle(recoilRobust(v.y, avgY) / density)
                DragMode.PAGER -> hooks.onPagerSettle(-recoilRobust(v.x, avgX) / density)
                else -> {}
            }
        }
    }
}

/**
 * The whole gesture layer over the continuous sky: the three paged panes ([PanePager]), the
 * descending spotlight stub, and the debug HUD. The ambient is drawn by the caller
 * as this scene's sibling and pans on its own parallax — one field for the whole viewport, not a
 * thing a pane owns — so it can never cut at a seam. [gesturesActive] detaches the arbiter while a
 * modal overlay is up.
 *
 * Every live float arrives as a lambda ([panPx], [spotlightFrac], [pageIndex]) rather than a value.
 * That is not a style choice: read as a parameter, a per-frame float recomposes the caller — the
 * entire launcher tree — on every pointer event of a drag, which on this hardware is the difference
 * between a swipe that tracks the finger and one that doesn't.
 */
@Composable
fun GestureScene(
    tuning: GestureTuning,
    fg: Color,
    accent: Color,
    containerW: Int,
    containerH: Int,
    pageIndex: () -> Int,
    panPx: () -> Float,
    spotlightFrac: () -> Float,
    hud: GestureHudState,
    hudVisible: Boolean,
    gesturesActive: () -> Boolean,
    hooks: ArbiterHooks,
    centerHome: @Composable () -> Unit,
    pastPane: @Composable () -> Unit = { PanePlaceholder("Past", fg) },
    futurePane: @Composable () -> Unit = { PanePlaceholder("Future", fg) },
    isOverHomeItem: (Offset) -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val insets = currentGestureInsets()

    // Claim the low edge Back-bands so raw touch reaches us for directional paging.
    EdgeExclusionEffect(tuning, density, containerW, containerH, insets)

    // The one composition-scope read of a live float in the scene, and it is a boolean crossing:
    // derivedStateOf keeps a spotlight drag from recomposing here on every frame just to answer
    // "is it off its rest yet".
    val frac = rememberUpdatedState(spotlightFrac)
    val spotlightMounted by remember { derivedStateOf { frac.value() > 0.001f } }

    Box(
        modifier
            .fillMaxSize()
            // Always attached, gated internally by [gesturesActive]: mounting/unmounting this node on
            // the flip would cancel a home item's in-flight itemPress mid-gesture, so a hold that
            // raised the strip could never become a pickup-drag (SID-114).
            .homeGestureArbiter(tuning, density, insets, hooks, gesturesActive, isOverHomeItem),
    ) {
        PanePager(
            containerW = containerW,
            panPx = panPx,
            past = pastPane,
            home = { centerHome() },
            future = futurePane,
        )

        // Spotlight stub descends from the top as its fraction rises.
        if (spotlightMounted) {
            SpotlightStub(spotlightFrac, containerH, accent, fg)
        }

        if (hudVisible) {
            GestureHud(hud, pageIndex)
        }
    }
}

/** Read the live `systemGestures` insets as px floats. */
@Composable
fun currentGestureInsets(): GestureInsets {
    val insets = WindowInsets.systemGestures
    val d = LocalDensity.current
    val ld = LocalLayoutDirection.current
    return GestureInsets(
        top = insets.getTop(d).toFloat(),
        bottom = insets.getBottom(d).toFloat(),
        left = insets.getLeft(d, ld).toFloat(),
        right = insets.getRight(d, ld).toFloat(),
    )
}

/**
 * Set the per-edge `systemGestureExclusionRects`: a low-biased [GestureTuning.exclusionBandDp]-tall
 * band on each side (as wide as the reported gesture inset, or the tuning fallback), so within it
 * the edge gives raw touch to us for directional paging. Cleared on dispose.
 */
@Composable
fun EdgeExclusionEffect(
    tuning: GestureTuning,
    density: Float,
    containerW: Int,
    containerH: Int,
    insets: GestureInsets,
) {
    val view = LocalView.current
    DisposableEffect(view, containerW, containerH, insets, tuning) {
        val (top, bottom) = edgeExclusionSpan(
            height = containerH.toFloat(),
            bandPx = tuning.exclusionBandDp * density,
            biasFraction = tuning.exclusionBiasFraction,
        )
        val fallbackEdge = tuning.edgeBandDp * density
        val leftW = edgeWidth(insets.left, fallbackEdge)
        val rightW = edgeWidth(insets.right, fallbackEdge)
        view.systemGestureExclusionRects = listOf(
            Rect(0, top.toInt(), leftW.toInt(), bottom.toInt()),
            Rect((containerW - rightW).toInt(), top.toInt(), containerW, bottom.toInt()),
        )
        onDispose { view.systemGestureExclusionRects = emptyList() }
    }
}

/**
 * What a side pane holds until §4 fills it (SID-13): its name, and otherwise the bare sky. No tint
 * and no card — SID-24's stub panes wore a translucent wash each, and a wash with an edge *is* the
 * seam the continuous sky exists to abolish; you could watch it cross the screen. The label is only
 * here because an empty pane is otherwise indistinguishable from the home's own sky, and paging has
 * to be visible to be judged before the dots (SID-27) and the content (SID-13) land.
 */
@Composable
private fun PanePlaceholder(label: String, fg: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(label, color = fg.copy(alpha = 0.35f), fontSize = 22.sp)
    }
}

@Composable
private fun SpotlightStub(frac: () -> Float, containerH: Int, tint: Color, fg: Color) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = (frac() - 1f) * containerH }
            .background(Color(0xE60E141B)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 56.dp)) {
            Box(Modifier.padding(bottom = 10.dp).size(width = 40.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(tint))
            Text("SPOTLIGHT (stub)", color = fg, fontSize = 16.sp)
        }
    }
}

@Composable
private fun GestureHud(hud: GestureHudState, pageIndex: () -> Int) {
    val pageName = when (pageIndex()) {
        0 -> "Past"
        2 -> "Future"
        else -> "Home"
    }
    Box(Modifier.fillMaxSize().padding(top = 44.dp, start = 10.dp)) {
        Column(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xB3000000))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text("axis:   ${hud.axis}", color = Color.White, fontSize = 11.sp)
            Text("start:  ${hud.region}", color = Color.White, fontSize = 11.sp)
            Text("action: ${hud.action}", color = Color.White, fontSize = 11.sp)
            Text("page:   $pageName", color = Color.White, fontSize = 11.sp)
        }
    }
}
