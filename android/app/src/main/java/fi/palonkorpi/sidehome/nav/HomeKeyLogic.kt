package fi.palonkorpi.sidehome.nav

/**
 * Pure HOME-key + entrance-cascade decision logic (SID-33 / SPEC §0). Framework-free so it can be
 * unit-tested without Compose/Android. Wired into [fi.palonkorpi.sidehome.ui.SideHomeApp] (the HOME
 * state machine + the reveal-edge derivation) and MainActivity (onStop / onNewIntent).
 *
 * The cascade is a *reveal* animation: it must fire only on the fully-covered → revealed edge of a
 * full-screen surface, never on a bare-home HOME press (nothing was covering → no edge → no replay,
 * which was the glitch this issue fixes).
 */

/**
 * A surface that can sit over the bare home. Full-screen surfaces hide the home, so revealing them
 * again is an entrance — the cascade replays. Lightweight floats leave the home on screen (it just
 * re-undims), so dismissing them is NOT an entrance and must not replay the cascade.
 */
enum class CoveringSurface(val isFullScreen: Boolean) {
    /** Another app is foreground (this launcher was backgrounded). */
    APP(true),

    /** The app drawer, dragged/flung all the way open. A partial peek is NOT this. */
    DRAWER_FULL(true),

    /** An opened home folder popover. */
    FOLDER(true),

    /** The spotlight surface (SID-24 arbiter), fully open. */
    SPOTLIGHT(true),

    /** The home edit sheet. */
    EDIT(true),

    /** The §1 anchored action-strip menu (long-press an app). */
    ACTION_MENU(false),

    /** The per-app icon picker. */
    ICON_PICKER(false),

    /** The debug day-scrubber panel. */
    DEBUG(false),
}

/**
 * Should the entrance cascade fire? Only on the fully-covered → revealed edge of a *full-screen*
 * surface. Opening (false → true), staying covered (true → true), a lightweight float, and a
 * partial peek that never reached full (prev already false) all return false.
 */
fun shouldFireCascade(prevCovered: Boolean, nowCovered: Boolean, kind: CoveringSurface): Boolean =
    kind.isFullScreen && prevCovered && !nowCovered

/** What a HOME press resolves to, given the current overlay stack. */
enum class HomeKeyAction {
    /** Return from another app to bare home; the cascade replays (a reveal edge). */
    ReturnToBareWithCascade,

    /** Collapse a full-screen overlay (drawer-full / folder / spotlight / edit) to bare home; cascade replays. */
    CollapseWithCascade,

    /** Dismiss a lightweight float (menu / icon picker / debug) to bare home; NO cascade. */
    DismissNoCascade,

    /** Already at bare home with nothing open: open the drawer. */
    OpenDrawer,
}

/**
 * The overlay stack at the instant HOME is pressed. Every settled surface is either fully open or
 * fully closed (the drawer/spotlight settle to 0 or 1; a partial peek only exists mid-drag, when
 * HOME cannot be pressed), so full-vs-closed booleans are a complete partition here.
 */
data class HomeUiState(
    val inAnotherApp: Boolean = false,
    val drawerFull: Boolean = false,
    val folderOpen: Boolean = false,
    val spotlightOpen: Boolean = false,
    val editOpen: Boolean = false,
    val actionMenuOpen: Boolean = false,
    val iconPickerOpen: Boolean = false,
    val debugOpen: Boolean = false,
) {
    val anyFullScreenOverlay: Boolean
        get() = drawerFull || folderOpen || spotlightOpen || editOpen

    val anyLightweightFloat: Boolean
        get() = actionMenuOpen || iconPickerOpen || debugOpen
}

/**
 * The unified HOME rule: HOME steps back toward bare home; if already at bare home, it opens the
 * drawer. One press blasts the whole overlay stack at once (unlike Back, which pops a single layer).
 * Priority: app coverage → full-screen overlay → lightweight float → bare-home.
 */
fun homeKeyAction(state: HomeUiState): HomeKeyAction = when {
    state.inAnotherApp -> HomeKeyAction.ReturnToBareWithCascade
    state.anyFullScreenOverlay -> HomeKeyAction.CollapseWithCascade
    state.anyLightweightFloat -> HomeKeyAction.DismissNoCascade
    else -> HomeKeyAction.OpenDrawer
}
