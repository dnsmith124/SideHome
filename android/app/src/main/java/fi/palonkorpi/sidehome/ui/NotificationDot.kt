package fi.palonkorpi.sidehome.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import fi.palonkorpi.sidehome.apps.AppEntry
import fi.palonkorpi.sidehome.apps.FolderItem
import fi.palonkorpi.sidehome.notifications.NotificationBadges

/**
 * The notification dot (SID-46, SPEC.md §8): the small accent mark a tile wears while its app has
 * something waiting. This file is the *decision* layer — who gets a dot, in what colour, at what
 * size; [LineIcon] in `Glyphs.kt` is the only thing that draws it.
 *
 * Three rules the whole feature hangs off:
 *
 *  - **A dot, not a count.** Forty messages and one message look identical. The dot says "there is
 *    something here", and the app itself says how much — so there is no number to render, no
 *    arithmetic to get wrong, and nothing that grows a tile as the day fills up.
 *  - **Accent-tinted, never system-red.** The dot takes `accentColor(mood, fgDark)` like every other
 *    active mark, so it drifts with the sky through the day instead of being the one hardcoded
 *    colour on an otherwise fully sky-derived screen (DECISIONS: "accent is derived from the sky").
 *  - **Keyed on the package, not the placement.** [NotificationBadges.badgedPackages] is a set of
 *    package names, so an app placed on home *and* inside a folder lights in both places at once and
 *    clears in both at once. There is deliberately no per-tile badge state to keep in sync.
 */

/** Dot geometry. Both are tune-on-device numbers — change them here, nowhere else. */
object BadgeMetrics {
    /**
     * Dot diameter, in multiples of the glyph's own stroke width. Relative to the stroke rather than
     * absolute dp so the dot stays in proportion across every [LineIcon] size the launcher uses
     * (16dp summon glyph → 25dp home tile) without a per-call-site number.
     */
    const val DiameterStrokes = 3.0f

    /**
     * How far the dot pokes past the glyph's bounding box, as a fraction of its own diameter, on
     * both axes. A little overhang keeps it reading as a mark *on* the tile rather than as a stray
     * blob of the line art — glyphs draw right up to the edges of their 24-unit box.
     */
    const val Overhang = 0.25f
}

/**
 * The badged set, live. Composables read the singleton directly rather than having it threaded down
 * from `MainActivity`: the badge source is process-wide and system-owned (Android binds the listener
 * service, we never construct it), so there is no seam to inject it through, and a dot is a property
 * of the package rather than of any screen's state.
 *
 * Empty when notification access is ungranted — the OS then never binds the service, so nothing ever
 * publishes and no tile is dotted.
 */
@Composable
fun rememberBadgedPackages(): Set<String> {
    val packages by NotificationBadges.badgedPackages.collectAsState()
    return packages
}

/**
 * The colour to hand [LineIcon]'s `badge` parameter: [accent] when the tile should be dotted, null
 * when it should not. Null-means-absent (rather than a boolean plus a colour) keeps un-badged the
 * default at every call site — a tile that doesn't ask for a dot can't accidentally get one.
 */
fun badgeTint(isBadged: Boolean, accent: Color): Color? = if (isBadged) accent else null

/** Does [app]'s package have something waiting? */
fun isBadged(app: AppEntry, badgedPackages: Set<String>): Boolean =
    app.packageName in badgedPackages

/**
 * The closed folder's **single aggregated dot**: lit if *any* member is lit. Not a count, not
 * per-member pips — a closed folder only ever claims "something in here"; opening it is what reveals
 * which app (the opened folder's member tiles carry their own dots).
 *
 * Takes package names, not [AppEntry] or home keys, because that is the only fact the answer depends
 * on — which keeps this the pure, testable seam of the feature.
 */
fun folderIsBadged(memberPackages: Iterable<String>, badgedPackages: Set<String>): Boolean =
    memberPackages.any { it in badgedPackages }

/**
 * [folderIsBadged] for a home folder, which stores members as `"package/class"` home keys rather
 * than package names. Members that no longer resolve (app uninstalled, key stale) simply don't vote
 * — a folder pointing at a dead key must not light up forever.
 */
fun folderIsBadged(
    folder: FolderItem,
    appsByKey: Map<String, AppEntry>,
    badgedPackages: Set<String>,
): Boolean = folderIsBadged(
    folder.appKeys.mapNotNull { appsByKey[it]?.packageName },
    badgedPackages,
)
