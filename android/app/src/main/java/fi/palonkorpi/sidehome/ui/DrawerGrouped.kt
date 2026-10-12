package fi.palonkorpi.sidehome.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fi.palonkorpi.sidehome.apps.BuiltInCategory
import fi.palonkorpi.sidehome.apps.Category
import fi.palonkorpi.sidehome.apps.AppEntry

// ---- pure logic (Compose-free, so it can be unit-tested) ------------------------------------

/**
 * How the drawer's **idle/browse** state is laid out (SPEC §2b). Search is not a mode: it always
 * collapses to a flat ranked list whatever this says — the mode only governs what you see when you
 * are not typing.
 *
 * Two modes, after the on-device §2b comparison: the plain list, and the grouped view whose bubbles
 * peek their members' **real** app icons. The line-glyph-only bubbles (the old Mode B) were dropped —
 * with the icon peek sitting right beside it, a bucket wearing a single Iconoir glyph read as the
 * poorer of the two and earned nothing the list didn't already give.
 *
 * @property glyph the segmented control's mark: [LIST] is a list, [BUBBLES_PEEK] the grouped grid.
 */
enum class BrowseMode(val glyph: String) {
    /** Mode A: the alphabetical list with quiet section letters. Today's drawer, unchanged. */
    LIST("list"),

    /** The grouped view: one bubble per bucket, each peeking a 2×2 cluster of its members' **real**
     *  app icons. */
    BUBBLES_PEEK("app"),
}

/** One category's bubble: the bucket and the apps behind it, already ordered. */
data class CategoryBucket(val category: Category, val apps: List<AppEntry>)

/**
 * Below this many members a category is folded into "Other" instead of getting its own bubble
 * (SPEC §2b's min-bucket-size N, explicitly left "tune on-device").
 *
 * **Why 3.** A bubble is expensive: on a 376dp-wide screen two of them fill a row and roughly a
 * third of the viewport's height, so a bucket of one spends a full tile — and a whole tap — to reach
 * a single app that the plain list would have shown you outright. That makes N=1 strictly worse than
 * Mode A for that app. N=2 is still a tile per app-and-a-half, and in Mode C a two-app bucket draws a
 * 2×2 cluster half full of holes, which is exactly the case that will look broken at this size. 3 is
 * the first size where a bubble is carrying more than the list does and the peek cluster reads as a
 * cluster.
 *
 * Pulling the other way: everything folded lands in "Other", and an "Other" that swallows a third of
 * the phone is its own failure — a junk drawer is not a category. With ~40 apps over 7 real buckets
 * the average bucket is ~5, so N=3 should fold only the genuine strays. **This is a guess until it
 * is seen against the real 40** — it is a single constant precisely so it can be moved.
 */
const val MIN_BUCKET_SIZE = 3

/** How many members a Mode C bubble peeks. Four, because the cluster is a 2×2. */
const val PEEK_COUNT = 4

/**
 * Group [apps] into the bubbles grouped mode shows, folding thin buckets into "Other"
 * ([MIN_BUCKET_SIZE]).
 *
 * **Order is: built-ins in the enum's declaration order, then the user's own buckets, with "Other"
 * always last** — not by size, and not alphabetical. Size-ordering is the tempting one and it is
 * wrong: buckets would reshuffle under the user's thumb every time an app was installed or
 * re-categorised, so the muscle memory that makes a grouped view faster than a list could never form.
 * The enum order is curated (it reads roughly most-reached-for first) and, crucially, *stable*; user
 * buckets follow in creation order, which is likewise stable. "Other" is last because it is the
 * honest leftovers rather than a peer of the real buckets.
 *
 * **User buckets are exempt from the [minBucketSize] fold** (SID-61). A built-in of one or two apps
 * is a stray the heuristic wasn't sure about, better shown in the plain list; a two-app category the
 * user *made* is the whole point, and folding it into "Other" would also make a user bucket behave
 * differently from a built-in — the one thing SID-61 forbids. Empty user buckets don't appear here:
 * this groups the installed apps, so a bucket with no members has nothing to group (the picker,
 * SID-62, is where a freshly-made empty bucket is surfaced to drop into).
 *
 * Members keep the repository's alphabetical order. The folded strays are re-sorted as they join
 * "Other", because they arrive bucket-by-bucket rather than in one alphabetical run.
 *
 * "Other" is never itself folded — there is nowhere for it to go, and a two-app "Other" is a true
 * statement about the library rather than a bucket that failed a threshold.
 */
fun bucketApps(apps: List<AppEntry>, minBucketSize: Int = MIN_BUCKET_SIZE): List<CategoryBucket> {
    val byCategory = apps.groupBy { it.category }
    val kept = ArrayList<CategoryBucket>()
    val other = ArrayList<AppEntry>()
    for (builtIn in BuiltInCategory.entries) {
        if (builtIn == BuiltInCategory.OTHER) continue
        val members = byCategory[builtIn.category].orEmpty()
        if (members.isEmpty()) continue
        if (members.size < minBucketSize) other.addAll(members) else kept.add(CategoryBucket(builtIn.category, members))
    }
    // User buckets, in creation order (the numeric part of their "user:N" id), never folded.
    val userBuckets = byCategory.entries
        .filter { !it.key.builtIn }
        .sortedBy { it.key.id.substringAfter(':').toIntOrNull() ?: Int.MAX_VALUE }
        .map { CategoryBucket(it.key, it.value) }
    other.addAll(byCategory[BuiltInCategory.OTHER.category].orEmpty())
    val tail = if (other.isEmpty()) emptyList()
    else listOf(CategoryBucket(BuiltInCategory.OTHER.category, other.sortedBy { it.label.lowercase() }))
    return kept + userBuckets + tail
}

/**
 * Which members a Mode C bubble shows through its 2×2 — the bucket's first [count], i.e. the
 * alphabetically-first.
 *
 * **Most-used would be the better answer and we cannot have it**: usage ranking needs
 * `UsageStatsManager` and the `PACKAGE_USAGE_STATS` permission, which is a settings-page grant flow
 * and an issue of its own — far past what an exploratory prototype should drag in. Of what is left,
 * alphabetical-first beats the alternatives on the two things a peek has to do: it is **stable**, so
 * a bubble looks the same every time you open the drawer and can be recognised by its cluster rather
 * than re-read (a peek that reshuffles is just noise); and it is **honest**, in that it shows the
 * four you would meet first on opening the bucket, so the peek is a preview of the thing rather than
 * a separate claim about it.
 *
 * The cost, which is worth watching on-device: it biases toward the front of the alphabet forever,
 * so a bucket's last member is never once glimpsed.
 */
fun peekApps(bucket: CategoryBucket, count: Int = PEEK_COUNT): List<AppEntry> = bucket.apps.take(count)

// ---- the grouped browse surface ---------------------------------------------------------------

/** Three bubbles to a row (Oliver's on-device call, now that the icon-peek is the only grouped
 *  mode): the buckets pack tighter and read as a set rather than a short stack. The peek cluster's
 *  icons shrink with the narrower bubble — the thing to watch when judging three-up on the device. */
internal const val BUBBLES_PER_ROW = 3

private val BUBBLE_GAP = 14.dp

/**
 * Grouped mode's idle state (SPEC §2b): the category bubbles, three to a row.
 *
 * A plain scrolling [Column] rather than a `LazyVerticalGrid` — there are at most eight bubbles, so
 * there is nothing to virtualise, and the lazy grid would buy a scrap of API surface and an extra
 * measure pass for a list that never exceeds three rows.
 *
 * Bubbles carry no notification dot, deliberately: a mark on a bucket would double-count what the
 * bucket's own members already show once it is opened, and a corner badge on a peeking cluster reads
 * as noise at this size. The dots stay on the list rows and on the members inside an opened bucket.
 */
@Composable
fun GroupedBrowse(
    buckets: List<CategoryBucket>,
    fg: Color,
    scrollState: ScrollState,
    onOpen: (CategoryBucket) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = fg,
    // Drawn under the last row of bubbles, inside the same scroll: the shelf rows (Pixel Edition).
    footer: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(BUBBLE_GAP),
    ) {
        for (row in buckets.chunked(BUBBLES_PER_ROW)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BUBBLE_GAP),
            ) {
                for (bucket in row) {
                    CategoryBubble(
                        bucket = bucket,
                        fg = fg,
                        onOpen = { onOpen(bucket) },
                        modifier = Modifier.weight(1f),
                        accent = accent,
                    )
                }
                // A lone bubble on the last row keeps its column's width instead of stretching to
                // fill the row — an odd bucket count must not make one bubble twice the size.
                repeat(BUBBLES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        footer()
    }
}

/**
 * The list/grouped segmented control that lives on the search line (SPEC §2b).
 *
 * **Two segments** — List and the grouped view — now that the §2b comparison is settled (the
 * line-glyph bubbles lost and were dropped). The spec's control is exactly this flip: "List ↔ the
 * Grouped mode".
 */
@Composable
fun BrowseModeToggle(
    mode: BrowseMode,
    onModeChange: (BrowseMode) -> Unit,
    fg: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            // A translucent currentColor cushion, per DECISIONS — the control tints itself out of the
            // foreground rather than introducing a surface colour of its own.
            .background(fg.copy(alpha = 0.07f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (m in BrowseMode.entries) {
            val selected = m == mode
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) fg.copy(alpha = 0.10f) else Color.Transparent)
                    .clickable { onModeChange(m) }
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            ) {
                // Accent marks the live one, like every other selected mark in the launcher.
                LineIcon(
                    glyph = m.glyph,
                    color = if (selected) accent else fg,
                    size = 17.dp,
                    strokeWidthUnits = 1.6f,
                )
            }
        }
    }
}
