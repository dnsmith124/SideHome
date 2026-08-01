package fi.palonkorpi.sidehome.keynav

/**
 * The launcher-navigation vocabulary (SID-82). Every hardware key a profile binds resolves to one of
 * these, and every navigable surface reacts to the intent, never to the key — which is the whole
 * point. The Sidephone tiles are passive and can't be auto-detected, so they emit different keycode
 * *sets* (the Sundial a D-pad, QWERTY/T9 a keypad); routing them all through one intent set is what
 * lets a single binding table serve every tile with zero tile detection (SID-85).
 *
 * Framework-free so the dispatch fork ([decideKeyDispatch]) and the profiles ([KeyProfile]) can be
 * unit-tested without an Activity — the same discipline [fi.palonkorpi.sidehome.nav.HomeKeyLogic]
 * keeps.
 */
sealed interface KeyIntent {
    /**
     * The rocking axes — the mandatory core every profile binds. Up/down and left/right move a
     * surface's own selection cursor (SID-84); what a direction *means* is the surface's call — a
     * folder grid steps within its rows, the action strip steps across its cells, the home list
     * steps its favourites (and ignores left/right: paging to the Future pane is Sundial-only for
     * v1, an explicit scope call).
     */
    data object NavUp : KeyIntent
    data object NavDown : KeyIntent
    data object NavLeft : KeyIntent
    data object NavRight : KeyIntent

    /** Act on the cursor's target: launch an app, open a folder, fire a strip cell, accept a field. */
    data object Select : KeyIntent

    /** Step one layer back — the same ordered ladder the touch Back walks (SID-87). */
    data object Back : KeyIntent

    /** Open the app drawer from anywhere on the home. */
    data object OpenDrawer : KeyIntent

    /** Collapse the whole overlay stack to bare home in one press (the HOME cascade). */
    data object Home : KeyIntent

    /**
     * Raise the action strip on an item — the key equivalent of a long-press. Produced by *holding*
     * the launch key (the dispatch layer), not a dedicated keycode, so it costs no extra key on the
     * D-pad-only Sundial: hold [Select] ([index] = null → the cursor's item) or hold a direct-index
     * number ([index] = that 1-based item). "Hold the launch key" (SID-87).
     */
    data class ItemMenu(val index: Int? = null) : KeyIntent

    /**
     * Jump straight to the nth item on a bounded surface and act on it (SID-82 direct-index). [n] is
     * 1-based and badged 1–9; only surfaces that can't overflow past their badges offer it.
     */
    data class QuickIndex(val n: Int) : KeyIntent
}
