package fi.palonkorpi.sidehome.nav

/**
 * SID-110: the one ordered model of the launcher's overlay stack.
 *
 * "Which surface owns input right now" used to be re-derived by hand in five places in the launcher
 * scaffold — the Back ladder, the arbiter-detach test, the key-nav routing + its reveal-first cursor
 * gate, the hardware-letter text-field test, and the open-drawer guard. Each enumerated its own subset
 * of the ~dozen overlay holders in its own order, and the "conflicting gesture" drift lived in the gap
 * between those copies. This is the single source: the host reads its holders once into an
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
 * test (or the bare home) names only what it opens. The two drawer-mode flags and the arbiter-gated
 * pair are pre-combined by the host: [drawerGallery] is the drawer in browse (App Gallery) mode, and
 * [spotlightEngaged] / [panesOffCentre] already fold in the arbiter-enabled guard.
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
    /** The drawer is in App Gallery (browse) mode — its numbers rock the bubble grid, not a search field. */
    val drawerGallery: Boolean = false,
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
 * Where a hardware key-nav intent is routed — the surface whose cursor it drives. A modal float
 * ([ModalGlobalsOnly]) answers only the globals (Back / Home) the host handled before this; the drawer
 * routes through its own registered handler; everything else falls to the home.
 *
 * **Declaration order is routing priority, highest first**: when several surfaces are up at once,
 * [keyRoute] picks the earliest-declared one. Reorder these and you reorder key-nav precedence.
 */
enum class KeyRoute { Strip, Folder, ModalGlobalsOnly, Drawer, Home }

/**
 * One overlay in the stack, and everything the ladder needs to know about it — declared once, in
 * [LADDER]. This inverts SID-110's five hand-ordered `when`s: "which overlay takes part in which
 * decision" was a subset re-picked inside each of [backStep] / [keyRoute] / [detachesArbiter] /
 * [overlayTextFieldFocused] / [canOpenDrawerFromBare], and those subsets had already drifted (the
 * theme chooser and the Future composer had fallen off the Back ladder; the composer off key routing).
 * With one row per overlay a missing behaviour is a visible blank in that row, not a silent omission
 * spread across five lists — and adding an overlay is one row, not five edits.
 */
private class Overlay(
    /** Is this overlay up, read off the [OverlayStack] snapshot. */
    val present: (OverlayStack) -> Boolean,
    /** What Back pops when this is the innermost present rung. `null` = Back steps *past* it: a child
     *  owns its own touch-Back (the theme chooser and the composer each have their own BackHandler),
     *  so it never reaches the host ladder — and key-nav Back therefore steps past it today too. */
    val back: BackStep? = null,
    /** The cursor key-nav drives while this is up. `null` = key-nav looks past it to whatever is under
     *  (ultimately the home). Resolved by [KeyRoute] priority, not by stack order. */
    val key: KeyRoute? = null,
    /** True when this takes the touch pointer stream, so the gesture arbiter goes inert. */
    val detaches: Boolean = false,
    /** True when this owns hardware letters (a focused text field). A predicate, not a flag, because
     *  the drawer owns letters only in A–Z list mode, not in the browse-first App Gallery (SID-96). */
    val textField: (OverlayStack) -> Boolean = { false },
    /** True when this must be cleared (by Back/Home) before the OpenDrawer key may open the drawer
     *  from a settled bare home. */
    val blocksDrawer: Boolean = false,
)

/**
 * The overlay stack, innermost (top) first. [backStep] walks it in this order; the other queries fold
 * over it order-free — key-nav by [KeyRoute] priority, the rest by any / none. Every rung defaults
 * absent, so a bare home matches nothing.
 */
private val LADDER: List<Overlay> = listOf(
    // The coach sits over everything and eats every touch, so Back (a touch) can only be aimed at it.
    Overlay(present = { it.coach }, back = BackStep.Coach),
    // The wallpaper consent (SID-180) is a question like the confirm modal, and Back answers its safe
    // answer ("not now"). The host only raises it once the coach is down, so it never contends with it.
    Overlay(
        present = { it.wallpaperConsent }, back = BackStep.WallpaperConsent,
        key = KeyRoute.ModalGlobalsOnly, blocksDrawer = true,
    ),
    // A question is the innermost thing and answers Cancel — above the hand, because raising it already
    // put the carry down.
    Overlay(
        present = { it.confirmRemoveFolder }, back = BackStep.ConfirmRemoveFolder,
        key = KeyRoute.ModalGlobalsOnly, blocksDrawer = true,
    ),
    // Carrying: Back puts the item back where it came from (SPEC §1).
    Overlay(
        present = { it.hand }, back = BackStep.Hand,
        key = KeyRoute.ModalGlobalsOnly, detaches = true, blocksDrawer = true,
    ),
    Overlay(
        present = { it.iconPicker }, back = BackStep.IconPicker,
        key = KeyRoute.ModalGlobalsOnly, detaches = true, blocksDrawer = true,
    ),
    Overlay(
        present = { it.categoryPicker }, back = BackStep.CategoryPicker,
        key = KeyRoute.ModalGlobalsOnly, detaches = true, blocksDrawer = true,
    ),
    // The photo positioner (SID-43): a full-screen framing surface on the pickers' exact contract —
    // it owns the pinch/drag, so the arbiter must not read them as pane swipes. Back cancels.
    Overlay(
        present = { it.photoPositioner }, back = BackStep.PhotoPositioner,
        key = KeyRoute.ModalGlobalsOnly, detaches = true, blocksDrawer = true,
    ),
    Overlay(
        present = { it.folderIcon }, back = BackStep.FolderIcon,
        key = KeyRoute.ModalGlobalsOnly, detaches = true, blocksDrawer = true,
    ),
    Overlay(
        present = { it.rename }, back = BackStep.Rename,
        key = KeyRoute.ModalGlobalsOnly, detaches = true, textField = { true }, blocksDrawer = true,
    ),
    Overlay(
        present = { it.strip }, back = BackStep.Strip,
        key = KeyRoute.Strip, detaches = true, blocksDrawer = true,
    ),
    Overlay(
        present = { it.debug }, back = BackStep.Debug,
        key = KeyRoute.ModalGlobalsOnly, detaches = true, blocksDrawer = true,
    ),
    // The theme chooser owns its own touch-Back (ThemeChooser's BackHandler), so it carries no [back]
    // rung — key-nav Back steps past it today. Folded in here rather than left off four separate lists
    // so that gap is a single visible null, not a silent omission (a conscious TODO to close on-device).
    Overlay(present = { it.themeChooser }, key = KeyRoute.ModalGlobalsOnly, blocksDrawer = true),
    // A drilled-in Gallery bucket steps out to the bubble grid before the drawer closes (SID-96). It is
    // only ever up *inside* the drawer, so the drawer rung below owns its key / text-field / detach.
    Overlay(present = { it.drawerActive && it.galleryBucket }, back = BackStep.GalleryBucket),
    // The drawer keeps the arbiter attached (a down-swipe drives it closed). Owns letters in A–Z list
    // mode only — the App Gallery is browse-first (SID-96).
    Overlay(
        present = { it.drawerActive }, back = BackStep.Drawer,
        key = KeyRoute.Drawer, textField = { !it.drawerGallery },
    ),
    // The Future city composer: a text field you are inside, so it detaches the touch arbiter and owns
    // letters — but key-nav's reserved nav set still falls to the home (matching the original, which
    // never routed it), and its own BackHandler owns touch-Back. Same documented gap as the chooser.
    Overlay(present = { it.futureComposer }, detaches = true, textField = { true }),
    // The arbiter's return-to-centre cases, below every real overlay and above bare home.
    Overlay(present = { it.spotlightEngaged }, back = BackStep.Spotlight),
    Overlay(present = { it.panesOffCentre }, back = BackStep.RecentrePanes),
    // An open folder is the outermost thing Back steps out of, but a first-class cursor for key-nav.
    Overlay(
        present = { it.folder }, back = BackStep.Folder, key = KeyRoute.Folder,
        detaches = true, blocksDrawer = true,
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
 * Which surface a key-nav intent acts on: the highest-priority [KeyRoute] among the overlays up
 * (priority is [KeyRoute] declaration order — strip, then folder, then any modal float, then the
 * drawer), or [KeyRoute.Home] when nothing routes.
 */
fun keyRoute(s: OverlayStack): KeyRoute =
    LADDER.filter { it.present(s) }.mapNotNull { it.key }.minByOrNull { it.ordinal } ?: KeyRoute.Home

/**
 * Should the touch gesture arbiter be detached? True when any overlay up takes the pointer stream — a
 * modal float, an open folder, a carry, or the Future composer. The drawer does **not** (a down-swipe
 * keeps driving it closed), and neither do the confirm modal, the theme chooser, or the coach.
 */
fun detachesArbiter(s: OverlayStack): Boolean =
    LADDER.any { it.present(s) && it.detaches }

/**
 * Does a surface with a focused text field own hardware letters (SID-85)? The A–Z list's search
 * (drawer, list mode), a folder rename, or the Future city composer — so letters type there while the
 * reserved D-pad + Enter + Back set still navigates. The App Gallery is browse-first, so it does not.
 */
fun overlayTextFieldFocused(s: OverlayStack): Boolean =
    LADDER.any { it.present(s) && it.textField(s) }

/**
 * From a settled bare home (the drawer closed), may the OpenDrawer key open it? Only when nothing that
 * a Back/Home should clear first is up. (The drawer, spotlight, off-centre panes, a gallery bucket, the
 * coach and the composer never coexist with a bare home the drawer key is pressed on, so none blocks.)
 */
fun canOpenDrawerFromBare(s: OverlayStack): Boolean =
    LADDER.none { it.present(s) && it.blocksDrawer }
