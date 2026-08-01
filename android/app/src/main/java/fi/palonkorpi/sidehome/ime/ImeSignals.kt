package fi.palonkorpi.sidehome.ime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The `privateImeOptions` wire protocol SideType reads off a host field's `EditorInfo`
 * (SID-18 colours, SID-20 app-name prediction bias). One string, comma-separated
 * `namespacedKey=value` tokens, shared by both signals.
 *
 * Two facts about the receiver that this file must not drift from:
 *  - SideType discards **every** token unless `EditorInfo.packageName` is exactly
 *    `fi.palonkorpi.sidehome`. A `.debug`-suffixed variant is silently ignored — colours and
 *    appDict alike — which is the usual reason "nothing happened".
 *  - Every key is independently optional; SideType defaults each channel it isn't sent.
 *
 * Any other IME ignores private options it doesn't know, so sending is always safe.
 */
private const val NS = "fi.palonkorpi.sidetype."

/**
 * 8-hex ARGB, uppercase, unprefixed — the only colour format on this wire. `toArgb()` converts
 * out of whatever colour space the [Color] carries into sRGB, so the hex is always sRGB and never
 * leaks the OKLCH values the ambient is authored in.
 */
private fun argb(c: Color): String =
    c.toArgb().toUInt().toString(16).uppercase().padStart(8, '0')

/**
 * Builds the token string. Null colours are omitted rather than sent as a guess — "send only what
 * you have" is the contract, and a missing channel is a SideType default, not a broken bar.
 */
internal fun sideTypeImeOptions(
    barBg: Color? = null,
    barFg: Color? = null,
    selectedBg: Color? = null,
    selectedText: Color? = null,
    separator: Color? = null,
    appDict: Boolean = false,
): String {
    val tokens = ArrayList<String>(6)
    barBg?.let { tokens += "${NS}barBg=${argb(it)}" }
    barFg?.let { tokens += "${NS}barFg=${argb(it)}" }
    selectedBg?.let { tokens += "${NS}selectedBg=${argb(it)}" }
    selectedText?.let { tokens += "${NS}selectedText=${argb(it)}" }
    separator?.let { tokens += "${NS}separator=${argb(it)}" }
    // "1" and "true" are the only values SideType reads as on; anything else is off.
    if (appDict) tokens += "${NS}appDict=1"
    return tokens.joinToString(",")
}

/**
 * The drawer search field's payload: the ambient colours SideType tints its tray with, plus the
 * app-name prediction bias that field always wants.
 *
 * [backdrop] is the sky-derived solid the drawer floats on — it is both the tray's background and
 * the ink on the accent pill, giving the selected candidate an inverted chip. The drawer is
 * frameless (it has no fill of its own), so this has to be handed in from the scene that paints
 * the backdrop; without it those two channels fall back to SideType's own.
 */
fun drawerImeOptions(fg: Color, accent: Color, backdrop: Color?, separator: Color): String =
    sideTypeImeOptions(
        barBg = backdrop,
        barFg = fg,
        selectedBg = accent,
        selectedText = backdrop,
        separator = separator,
        appDict = true,
    )
