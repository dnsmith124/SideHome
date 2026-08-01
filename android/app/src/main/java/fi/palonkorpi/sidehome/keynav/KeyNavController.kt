package fi.palonkorpi.sidehome.keynav

import android.view.KeyEvent

/**
 * The bridge between `MainActivity.dispatchKeyEvent` (which sees every hardware key first, before the
 * IME or any focused field) and the Compose surfaces that react to intents. The Activity installs
 * [onKeyEvent] as its handler; the composition sets the live inputs ([navEnabled], [textFieldFocused],
 * [profile]) and the [onIntent] sink every recomposition, so the installed handler always reads the
 * current state without being reinstalled.
 *
 * The pure fork ([decideKeyDispatch]) decides *whether* a key is ours; this class only adds the two
 * things that need the event stream: repeat handling (a held direction auto-moves; a discrete action
 * fires once) and the tap-vs-hold split that makes [KeyIntent.Select] double as [KeyIntent.ItemMenu]
 * — "hold the launch key" (SID-87), so the action strip costs no extra key on the D-pad-only Sundial.
 */
/**
 * A mutable, non-observable slot a surface registers its own key handler into (SID-84). Used by the
 * drawer, which owns its ranked results, to answer nav/select without hoisting that state to the host.
 * Deliberately NOT Compose state: a surface republishes its handler every recomposition, and an
 * observable slot would recompose the reader in a loop. The host reads it at key-time, off the
 * composition path.
 */
class KeyHandlerSlot {
    var handler: ((KeyIntent) -> Unit)? = null
}

class KeyNavController {
    var navEnabled: Boolean = true
    var textFieldFocused: Boolean = false
    var profile: KeyProfile = KeyProfiles.DEFAULT
    var onIntent: (KeyIntent) -> Unit = {}

    // The hold threshold for the tap-vs-hold split, fed live from GestureTuning.holdTimeoutMs so the
    // key hold and the touch hold are the one number (SID-111). Defaults to the shared constant until
    // the composition wires the tuning in.
    var holdTimeoutMs: Long = KEY_HOLD_THRESHOLD_MS

    // Set once the current launch-key press has already fired ItemMenu via auto-repeat, so its
    // release doesn't then also fire a tap activation.
    private var holdFired = false

    /** @return true if the key was consumed (handled as an intent); false to pass it through. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val decision = decideKeyDispatch(event.keyCode, navEnabled, textFieldFocused, profile)
        if (decision !is KeyDispatch.Handle) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> onDown(event, decision.intent)
            KeyEvent.ACTION_UP -> onUp(event, decision.intent)
        }
        // Consume both edges of a key we claim, so a handled press never leaks a stray UP downstream.
        return true
    }

    // The "launch keys" carry a tap-vs-hold split: tap activates the item, hold raises its strip
    // (SID-87). Select targets the cursor's item (ItemMenu(null)); a direct-index number targets that
    // item (ItemMenu(n)).
    private fun isLaunchKey(intent: KeyIntent) = intent is KeyIntent.Select || intent is KeyIntent.QuickIndex

    private fun menuFor(intent: KeyIntent): KeyIntent =
        KeyIntent.ItemMenu((intent as? KeyIntent.QuickIndex)?.n)

    private fun onDown(event: KeyEvent, intent: KeyIntent) {
        if (isLaunchKey(intent)) {
            // First DOWN arms the press; a later auto-repeat DOWN past the threshold is the hold —
            // fire the menu once, mid-hold, the way the touch long-press raises it at the hold instant.
            if (event.repeatCount == 0) {
                holdFired = false
            } else if (!holdFired && isKeyHold(event.downTime, event.eventTime, holdTimeoutMs)) {
                holdFired = true
                onIntent(menuFor(intent))
            }
            return
        }
        // Directions may auto-repeat so a held key keeps moving the cursor; every other intent is a
        // one-shot and ignores repeats (holding Back must not pop layer after layer).
        val repeats = intent == KeyIntent.NavUp || intent == KeyIntent.NavDown ||
            intent == KeyIntent.NavLeft || intent == KeyIntent.NavRight
        if (repeats || event.repeatCount == 0) onIntent(intent)
    }

    private fun onUp(event: KeyEvent, intent: KeyIntent) {
        if (isLaunchKey(intent)) {
            if (!holdFired) {
                // No auto-repeat arrived (or the device doesn't repeat these keys): classify by the
                // press duration on release — a long hold is the menu, a quick tap the activation.
                if (isKeyHold(event.downTime, event.eventTime, holdTimeoutMs)) onIntent(menuFor(intent))
                else onIntent(intent)
            }
            holdFired = false
        }
    }
}
