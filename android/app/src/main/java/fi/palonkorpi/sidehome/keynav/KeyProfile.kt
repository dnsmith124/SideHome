package fi.palonkorpi.sidehome.keynav

import android.view.KeyEvent

/**
 * A semantic mapping over one fixed keycode set (SID-86). Because the tiles are passive — identical
 * firmware, same keycode per physical position — a "profile" is never different codes per hardware,
 * only a different reading of the codes a given overlay actually exposes.
 *
 * The [KeyEvent] constants below are `public static final int` compile-time constants, so they inline
 * to plain ints: this file references them for readability but the maps hold literals, and the tests
 * never touch a KeyEvent *method* — keeping the whole module unit-testable off-device.
 */
data class KeyProfile(
    val id: String,
    val label: String,
    /** keycode → intent, consulted only when no text field is focused (the all-nav modality). */
    val bindings: Map<Int, KeyIntent>,
)

/**
 * The keycodes a *focused text field* still surrenders to navigation (SID-85) — i.e. every tile's
 * nav / select / back / global keys, everything EXCEPT the typeable direct-index keys (letters and
 * digits), which must reach the field. The dispatcher reserves exactly this set while typing; the
 * hardware decides which actually arrive, so whichever tile is on can still walk a focused list,
 * launch, and — crucially — *leave* the field. Keyed to each tile's real captured codes (SID-86):
 * the Sundial's Back corner is `DPAD_LEFT`, its library corner `DPAD_RIGHT`, its Home corner `TAB`,
 * its centre `MEDIA_PLAY_PAUSE`; T9 selects on `#` and backs on `*` (its Call key is the OS dialer key,
 * never delivered here); QWERTY backs on `BACK`.
 * The ▲▼ rocker (`CTRL_LEFT`, 113) is deliberately absent: it's the IME's predictive⇄abc toggle.
 *
 * Reserving Enter/select here is also what fixes the drawer's double-Enter (SID-91): Select is acted
 * on at the Activity layer before the predictive IME can eat the first Enter as a composition commit.
 */
val RESERVED_WHILE_TYPING: Map<Int, KeyIntent> = mapOf(
    // List nav — the Sundial ring's vertical (a focused field is 1-D, so up/down is all it needs).
    KeyEvent.KEYCODE_DPAD_UP to KeyIntent.NavUp,
    KeyEvent.KEYCODE_DPAD_DOWN to KeyIntent.NavDown,
    // Select from any tile's select/centre key.
    KeyEvent.KEYCODE_DPAD_CENTER to KeyIntent.Select,
    KeyEvent.KEYCODE_ENTER to KeyIntent.Select,
    KeyEvent.KEYCODE_NUMPAD_ENTER to KeyIntent.Select,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to KeyIntent.Select, // Sundial centre
    KeyEvent.KEYCODE_POUND to KeyIntent.Select,            // T9 select
    // Escape/dismiss + the globals a focused field must still honour, so a d-pad or T9 tile can leave.
    KeyEvent.KEYCODE_BACK to KeyIntent.Back,               // QWERTY hardware Back
    KeyEvent.KEYCODE_DPAD_LEFT to KeyIntent.Back,          // Sundial top-left
    KeyEvent.KEYCODE_STAR to KeyIntent.Back,               // T9 * (its Call key is the OS dialer key)
    KeyEvent.KEYCODE_DPAD_RIGHT to KeyIntent.OpenDrawer,   // Sundial top-right (library toggle)
    KeyEvent.KEYCODE_TAB to KeyIntent.Home,                // Sundial bottom-left
)

/**
 * The 3×3 number grid, as **direct-index** (SID-82/SID-86): press a marking, focus + activate that
 * badged item in one press. The two number-bearing tiles print the SAME grid, so each grid *position*
 * binds to the code its tile emits — the T9 digit `KEYCODE_1..9`, and the QWERTY letter the marking
 * is printed on (1=E, 2=T, 3=U, 4=D, 5=G, 6=J, 7=C, 8=B, 9=M) — both resolving to the same
 * [KeyIntent.QuickIndex]. No SYM modifier is needed on QWERTY because we bind the letter keycode the
 * key actually emits.
 *
 * Direct-index is consulted only when nothing is focused; a focused search field types these keys
 * instead (the modality boolean, SID-85). On the unbounded A–Z drawer the same grid is meant to rock
 * as a D-pad, but there the search field holds focus, so on the number tiles the drawer is type +
 * Enter + Back, and the reserved D-pad ring (the Sundial's) is what rocks a focused drawer.
 */
private val QWERTY_GRID: Map<Int, KeyIntent> = listOf(
    KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_U,
    KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_J,
    KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_M,
).mapIndexed { i, code -> code to (KeyIntent.QuickIndex(i + 1) as KeyIntent) }.toMap()

private val T9_GRID: Map<Int, KeyIntent> = (1..9).associate { n ->
    (KeyEvent.KEYCODE_0 + n) to (KeyIntent.QuickIndex(n) as KeyIntent)
}

/** Both number tiles' grids together — the direct-index half of the unified default. */
val DIRECT_INDEX: Map<Int, KeyIntent> = QWERTY_GRID + T9_GRID

/**
 * Sundial: a D-pad, the sole tile with no number grid, so it *rocks* every surface — cursor + select,
 * never direct-index. Bound from the definitive 9-of-9 getevent capture (SID-86, 2026-07-18), which
 * corrected the earlier partial one:
 *  - Ring **vertical** is a true D-pad (`DPAD_UP`/`DOWN`); ring **horizontal** reports the media
 *    transport keys (`MEDIA_PREVIOUS`/`NEXT`) → NavLeft/NavRight.
 *  - **Centre reports `MEDIA_PLAY_PAUSE`, not `ENTER`** — which is exactly why centre was dead (the
 *    profile bound only `DPAD_CENTER`/`ENTER`). → Select.
 *  - Corners: bottom-right `ENTER` → Select (a second select), bottom-left `TAB` → Back, and the two
 *    top corners report `DPAD_LEFT`/`DPAD_RIGHT` → the globals (open-drawer / home).
 *
 * CAVEAT (SID-86): centre + ring-horizontal are MEDIA keycodes. getevent proves the kernel emits them,
 * not that they survive Android's media-session layer to reach `dispatchKeyEvent`. Verify on-device
 * (press centre) before trusting the Sundial; if intercepted, these three fall to a MediaSession grab.
 */
val SUNDIAL_PROFILE = KeyProfile(
    id = "sundial",
    label = "Sundial",
    bindings = mapOf(
        KeyEvent.KEYCODE_DPAD_UP to KeyIntent.NavUp,
        KeyEvent.KEYCODE_DPAD_DOWN to KeyIntent.NavDown,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to KeyIntent.NavLeft,
        KeyEvent.KEYCODE_MEDIA_NEXT to KeyIntent.NavRight,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to KeyIntent.Select,
        KeyEvent.KEYCODE_ENTER to KeyIntent.Select,
        // Corners, pinned by the count-tagged capture (2026-07-18) in Oliver's own frame: top-left
        // `DPAD_LEFT`=Back, top-right `DPAD_RIGHT`=open library, bottom-left `TAB`=Home, bottom-right
        // `ENTER`=Select.
        KeyEvent.KEYCODE_DPAD_LEFT to KeyIntent.Back,
        KeyEvent.KEYCODE_DPAD_RIGHT to KeyIntent.OpenDrawer,
        KeyEvent.KEYCODE_TAB to KeyIntent.Home,
    ),
)

/**
 * Compact QWERTY (SID-83/SID-86): the number grid on the letter keys, plus the physical Back and
 * Enter. The ▲▼ rocker (`CTRL_LEFT`) is left spare — it's the IME's predictive toggle (SID-85).
 * open-drawer is the hardware HOME key, which reaches the launcher as a MAIN/HOME intent
 * (`onNewIntent`), not a key event, so it needs no binding here.
 */
val QWERTY_PROFILE = KeyProfile(
    id = "qwerty",
    label = "Compact QWERTY",
    bindings = QWERTY_GRID + mapOf(
        KeyEvent.KEYCODE_BACK to KeyIntent.Back,
        KeyEvent.KEYCODE_ENTER to KeyIntent.Select,
    ),
)

/**
 * T9 (SID-83/SID-86): the number grid on the digit keys. The T9 tile's top nav row (Call·Home·End) is
 * a hardware telephony/home row the OS claims before the launcher — pressing Call opens the Phone app,
 * End is call-control, and Home arrives only as a MAIN/HOME intent (which is how it opens the drawer).
 * None reach `dispatchKeyEvent` (verified on-device 2026-07-30, T9 top-left → Phone app, no key event),
 * so Back and Select live on the keypad's function keys: `*` = Back, `#` = Select; `0` is spare.
 */
val T9_PROFILE = KeyProfile(
    id = "t9",
    label = "T9",
    bindings = T9_GRID + mapOf(
        KeyEvent.KEYCODE_POUND to KeyIntent.Select,   // #
        KeyEvent.KEYCODE_STAR to KeyIntent.Back,      // *
    ),
)

/**
 * The shipped default (SID-89's "no manual switcher needed" thesis, realized). The three tiles' codes
 * are disjoint — D-pad vs letters vs digits vs media/symbols never collide, and the one overlap
 * (ENTER = Select on both Sundial and QWERTY) agrees — so their union is a single collision-free table
 * that navigates *whichever* tile is physically on, with no detection. A key a tile doesn't have
 * simply never arrives. The per-tile profiles above stay as named data for the eventual custom-profile
 * switcher and for documentation.
 */
val UNIFIED_PROFILE = KeyProfile(
    id = "unified",
    label = "Auto (all tiles)",
    bindings = SUNDIAL_PROFILE.bindings + QWERTY_PROFILE.bindings + T9_PROFILE.bindings,
)

/** The known profiles, and the default the launcher ships with. */
object KeyProfiles {
    val SUNDIAL = SUNDIAL_PROFILE
    val QWERTY = QWERTY_PROFILE
    val T9 = T9_PROFILE
    val UNIFIED = UNIFIED_PROFILE

    /** Every selectable profile — the unified default first, the per-tile tables after it. */
    val ALL = listOf(UNIFIED, QWERTY, T9, SUNDIAL)

    val DEFAULT = UNIFIED

    fun byId(id: String): KeyProfile? = ALL.firstOrNull { it.id == id }
}
