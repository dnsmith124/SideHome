package fi.palonkorpi.sidehome.keynav

import fi.palonkorpi.sidehome.gesture.DEFAULT_HOLD_TIMEOUT_MS

/**
 * What the dispatcher should do with one hardware key. `MainActivity.dispatchKeyEvent` is the first
 * hook in Android's key path (the Activity is the `Window.Callback`, called before the view hierarchy
 * and the IME), so [Handle] — consuming the key — means it never reaches the focused field or the
 * predictive IME, while [PassThrough] hands it on to type or to be a system key.
 */
sealed interface KeyDispatch {
    /** Consume the key and run this intent. */
    data class Handle(val intent: KeyIntent) : KeyDispatch

    /** Let the key flow on (`super.dispatchKeyEvent`) — types into a field, or is a system key. */
    data object PassThrough : KeyDispatch
}

/**
 * The three-way dispatch fork (SID-88), pure so every branch is exhaustively unit-testable:
 *
 *  1. **key-nav OFF** → everything passes through. The global toggle (SID-89) must never fight an
 *     external Key Mapper app, so OFF is a true no-op, not a swallow.
 *  2. **a text field is focused** → only the uniform reserved set ([RESERVED_WHILE_TYPING]) becomes
 *     intents; every other key passes through to type (SID-85).
 *  3. **nothing focused** → the active [profile] maps the key to an intent; an unmapped key passes
 *     through, so volume/power/media and anything the launcher doesn't claim keep working.
 *
 * [keyCode] is the raw Android keycode; the caller owns the two booleans and the active profile.
 */
fun decideKeyDispatch(
    keyCode: Int,
    navEnabled: Boolean,
    textFieldFocused: Boolean,
    profile: KeyProfile,
): KeyDispatch {
    if (!navEnabled) return KeyDispatch.PassThrough
    val table = if (textFieldFocused) RESERVED_WHILE_TYPING else profile.bindings
    val intent = table[keyCode] ?: return KeyDispatch.PassThrough
    return KeyDispatch.Handle(intent)
}

/**
 * The default long-press threshold for turning a held [KeyIntent.Select] into [KeyIntent.ItemMenu].
 * One number with the touch hold ([DEFAULT_HOLD_TIMEOUT_MS], SID-111) so "how long is a hold" can't
 * be one value for a finger and another for a key. This is only the pre-wire default: at runtime the
 * controller is fed the live [fi.palonkorpi.sidehome.gesture.GestureTuning.holdTimeoutMs] so the
 * on-device tune moves both paths together.
 */
const val KEY_HOLD_THRESHOLD_MS: Long = DEFAULT_HOLD_TIMEOUT_MS

/**
 * Was a key press a hold rather than a tap? Time-based (down/up event times) so the tap-vs-hold split
 * that makes Select double as item-menu (SID-87 "hold the launch key") is testable off the event
 * stream. The wiring layer holds the down time per keycode and classifies on release.
 */
fun isKeyHold(downTimeMs: Long, upTimeMs: Long, thresholdMs: Long = KEY_HOLD_THRESHOLD_MS): Boolean =
    upTimeMs - downTimeMs >= thresholdMs
