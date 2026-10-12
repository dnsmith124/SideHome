package fi.palonkorpi.sidehome.nav

/**
 * SID-110: the one ordered model of the launcher's overlay stack.
 *
 * "Which surface owns input right now" used to be re-derived by hand in several places in the
 * launcher scaffold — the Back ladder and the arbiter-detach test among them. Each enumerated its own
 * subset of the ~dozen overlay holders in its own order, and the "conflicting gesture" drift lived in
 * the gap between those copies. This is the single source: the host reads its holders once into an
 * [OverlayStack], and every one of those decisions is a pure function of it, testable without a device.
 *
 * Deliberately *not* folded in here (they are different concerns, not this precedence):
 *  - the entrance-cascade / HOME-key rule — already its own pure module ([homeKeyAction]);
 *  - `overlayOpen` / `fullyCovered` — draw + cascade booleans whose drawer-vs-spotlight asymmetry
 *    (SID-60) is about *covering*, not input precedence;
 *  - `resetToBareHome` — a clear-all, not a ladder.
 */

/**
 * Which overlays are currently up, read straight off the host's state holders. All default false, so a
 * test (or the bare home) names only what it opens. The arbiter-gated pair are pre-combined by the
 * host: [spotlightEngaged] / [panesOffCentre] already fold in the arbiter-enabled guard.
 */
data class OverlayStack(
    val coach: Boolean = false,
    val wallpaperConsent: Boolean = false,
    val confirmRemoveFolder: Boolean = false,
    val hand: Boolean = false,
    val iconPicker: Boolean = false,
    val categoryPicker: Boolean = false,
    /** The Photo theme's framing step is up (SID-43 crop flow) — a full-screen surface like the pickers. */
    val photoPositioner: Boolean = false,
    val folderIcon: Boolean = false,
    val rename: Boolean = false,
    val strip: Boolean = false,
    val debug: Boolean = false,
    val themeChooser: Boolean = false,
    val futureComposer: Boolean = false,
    val folder: Boolean = false,
    val drawerActive: Boolean = false,
    /** A drilled-in gallery bucket is open inside the drawer. */
    val galleryBucket: Boolean = false,
    /** Spotlight is off its rest *and* the arbiter is enabled (the host folds that guard in). */
    val spotlightEngaged: Boolean = false,
    /** The pager is off the centre home *and* the arbiter is enabled. */
    val panesOffCentre: Boolean = false,
)

/**
 * One step of the Back ladder — what a single Back press pops next. The host maps each to its own
 * dismiss side effect; the *order* is the stacking order of [LADDER], innermost first. [None] is a
 * no-op (Back on bare home, which the launcher swallows).
 */
enum class BackStep {
    Coach,
    WallpaperConsent,
    ConfirmRemoveFolder,
    Hand,
    IconPicker,
    CategoryPicker,
    PhotoPositioner,
    FolderIcon,
    Rename,
    Strip,
    Debug,
    GalleryBucket,
    Drawer,
    Spotlight,
    RecentrePanes,
    Folder,
    None,
}

/**
 * One overlay in the stack, and everything the ladder needs to know about it — declared once, in
 * [LADDER]. This inverts SID-110's hand-ordered `when`s: "which overlay takes part in which decision"
 * was a subset re-picked inside each of [backStep] / [detachesArbiter], and those subsets had already
 * drifted (the theme chooser and the Future composer had fallen off the Back ladder). With one row per
 * overlay a missing behaviour is a visible blank in that row, not a silent omission spread across
 * lists — and adding an overlay is one row, not several edits.
 */
private class Overlay(
    /** Is this overlay up, read off the [OverlayStack] snapshot. */
    val present: (OverlayStack) -> Boolean,
    /** What Back pops when this is the innermost present rung. `null` = Back steps *past* it: a child
     *  owns its own touch-Back (the theme chooser and the composer each have their own BackHandler),
     *  so it never reaches the host ladder. */
    val back: BackStep? = null,
    /** True when this takes the touch pointer stream, so the gesture arbiter goes inert. */
    val detaches: Boolean = false,
)

/**
 * The overlay stack, innermost (top) first. [backStep] walks it in this order; [detachesArbiter]
 * folds over it order-free. Every rung defaults absent, so a bare home matches nothing.
 */
private val LADDER: List<Overlay> = listOf(
    // The coach sits over everything and eats every touch, so Back (a touch) can only be aimed at it.
    Overlay(present = { it.coach }, back = BackStep.Coach),
    // The wallpaper consent (SID-180) is a question like the confirm modal, and Back answers its safe
    // answer ("not now"). The host only raises it once the coach is down, so it never contends with it.
    Overlay(
        present = { it.wallpaperConsent }, back = BackStep.WallpaperConsent,
    ),
    // A question is the innermost thing and answers Cancel — above the hand, because raising it already
    // put the carry down.
    Overlay(
        present = { it.confirmRemoveFolder }, back = BackStep.ConfirmRemoveFolder,
    ),
    // Carrying: Back puts the item back where it came from (SPEC §1).
    Overlay(
        present = { it.hand }, back = BackStep.Hand, detaches = true,
    ),
    Overlay(
        present = { it.iconPicker }, back = BackStep.IconPicker, detaches = true,
    ),
    Overlay(
        present = { it.categoryPicker }, back = BackStep.CategoryPicker, detaches = true,
    ),
    // The photo positioner (SID-43): a full-screen framing surface on the pickers' exact contract —
    // it owns the pinch/drag, so the arbiter must not read them as pane swipes. Back cancels.
    Overlay(
        present = { it.photoPositioner }, back = BackStep.PhotoPositioner, detaches = true,
    ),
    Overlay(
        present = { it.folderIcon }, back = BackStep.FolderIcon, detaches = true,
    ),
    Overlay(
        present = { it.rename }, back = BackStep.Rename, detaches = true,
    ),
    Overlay(
        present = { it.strip }, back = BackStep.Strip, detaches = true,
    ),
    Overlay(
        present = { it.debug }, back = BackStep.Debug, detaches = true,
    ),
    // The theme chooser owns its own touch-Back (ThemeChooser's BackHandler), so it carries no [back]
    // rung. Folded in here rather than left off the list so that gap is a single visible blank, not a
    // silent omission.
    Overlay(present = { it.themeChooser }),
    // A drilled-in Gallery bucket steps out to the bubble grid before the drawer closes (SID-96). It is
    // only ever up *inside* the drawer, so the drawer rung below owns its detach.
    Overlay(present = { it.drawerActive && it.galleryBucket }, back = BackStep.GalleryBucket),
    // The drawer keeps the arbiter attached (a down-swipe drives it closed).
    Overlay(
        present = { it.drawerActive }, back = BackStep.Drawer,
    ),
    // The Future city composer: a text field you are inside, so it detaches the touch arbiter; its
    // own BackHandler owns touch-Back. Same documented gap as the chooser.
    Overlay(present = { it.futureComposer }, detaches = true),
    // The arbiter's return-to-centre cases, below every real overlay and above bare home.
    Overlay(present = { it.spotlightEngaged }, back = BackStep.Spotlight),
    Overlay(present = { it.panesOffCentre }, back = BackStep.RecentrePanes),
    // An open folder is the outermost thing Back steps out of.
    Overlay(
        present = { it.folder }, back = BackStep.Folder,
        detaches = true,
    ),
)

/**
 * What one Back press pops, given the stack: the innermost present rung that owns a Back step. Rungs
 * with a null [Overlay.back] (a child owns their touch-Back) are stepped past. [BackStep.None] on a
 * bare home, which the launcher swallows.
 */
fun backStep(s: OverlayStack): BackStep =
    LADDER.firstOrNull { it.present(s) && it.back != null }?.back ?: BackStep.None

/**
 * Should the touch gesture arbiter be detached? True when any overlay up takes the pointer stream — a
 * modal float, an open folder, a carry, or the Future composer. The drawer does **not** (a down-swipe
 * keeps driving it closed), and neither do the confirm modal, the theme chooser, or the coach.
 */
fun detachesArbiter(s: OverlayStack): Boolean =
    LADDER.any { it.present(s) && it.detaches }
