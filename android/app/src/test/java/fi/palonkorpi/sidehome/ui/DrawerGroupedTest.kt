package fi.palonkorpi.sidehome.ui

import fi.palonkorpi.sidehome.apps.BuiltInCategory
import fi.palonkorpi.sidehome.apps.Category
import fi.palonkorpi.sidehome.apps.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for grouped mode's pure logic (SID-23, SPEC §2b): how apps become bubbles, the
 * min-bucket-size fold into "Other", the bubble order, and which members a Mode C bubble peeks.
 *
 * These are the decisions the prototype is *about* — everything else in the feature is a composable
 * and therefore untestable here, which is exactly why this logic lives outside them. The one thing
 * these tests deliberately do **not** assert is the value of [MIN_BUCKET_SIZE]: it is a tune-on-device
 * number, and a test pinning it to 3 would turn "the user changed a constant after feeling it" into
 * "the build is red". They pass the threshold in explicitly instead.
 */
class DrawerGroupedTest {

    private fun app(label: String, category: Category) =
        AppEntry(
            label = label,
            packageName = "com.example.${label.lowercase()}",
            className = "com.example.${label.lowercase()}.Main",
            glyph = "app",
            category = category,
        )

    /** [n] apps in [category], labelled a1, a2, … so order is checkable. */
    private fun apps(n: Int, category: Category, prefix: String = "a") =
        (1..n).map { app("$prefix$it", category) }

    private fun categoriesOf(buckets: List<CategoryBucket>) = buckets.map { it.category }

    // ---- bucketing ---------------------------------------------------------------------------

    @Test fun `apps group into their categories`() {
        val all = apps(3, BuiltInCategory.MEDIA.category, "m") + apps(3, BuiltInCategory.MONEY.category, "p")
        val buckets = bucketApps(all, minBucketSize = 3)

        assertEquals(listOf(BuiltInCategory.MEDIA.category, BuiltInCategory.MONEY.category), categoriesOf(buckets))
        assertEquals(listOf("m1", "m2", "m3"), buckets[0].apps.map { it.label })
    }

    @Test fun `an empty library makes no bubbles`() {
        assertEquals(emptyList<CategoryBucket>(), bucketApps(emptyList(), minBucketSize = 3))
    }

    @Test fun `a category with no apps gets no bubble`() {
        val buckets = bucketApps(apps(3, BuiltInCategory.MEDIA.category), minBucketSize = 3)
        assertEquals(listOf(BuiltInCategory.MEDIA.category), categoriesOf(buckets))
    }

    // ---- the min-bucket-size fold ------------------------------------------------------------

    @Test fun `a bucket at the threshold keeps its own bubble`() {
        val buckets = bucketApps(apps(3, BuiltInCategory.MEDIA.category), minBucketSize = 3)
        assertEquals(listOf(BuiltInCategory.MEDIA.category), categoriesOf(buckets))
    }

    @Test fun `a bucket under the threshold folds into Other`() {
        val all = apps(3, BuiltInCategory.MEDIA.category, "m") + apps(2, BuiltInCategory.MONEY.category, "p")
        val buckets = bucketApps(all, minBucketSize = 3)

        assertEquals(listOf(BuiltInCategory.MEDIA.category, BuiltInCategory.OTHER.category), categoriesOf(buckets))
        assertEquals(listOf("p1", "p2"), buckets[1].apps.map { it.label })
    }

    @Test fun `folded strays merge with the apps already in Other, alphabetically`() {
        val all = listOf(app("zeta", BuiltInCategory.OTHER.category), app("alpha", BuiltInCategory.MONEY.category)) +
            apps(3, BuiltInCategory.MEDIA.category, "m")
        val buckets = bucketApps(all, minBucketSize = 3)

        val other = buckets.single { it.category == BuiltInCategory.OTHER.category }
        // The strays arrive bucket-by-bucket rather than in one alphabetical run, so Other re-sorts.
        assertEquals(listOf("alpha", "zeta"), other.apps.map { it.label })
    }

    @Test fun `Other is never folded, however small`() {
        val buckets = bucketApps(listOf(app("solo", BuiltInCategory.OTHER.category)), minBucketSize = 3)
        assertEquals(listOf(BuiltInCategory.OTHER.category), categoriesOf(buckets))
        assertEquals(listOf("solo"), buckets[0].apps.map { it.label })
    }

    @Test fun `no Other bubble when nothing is unbucketed`() {
        val buckets = bucketApps(apps(3, BuiltInCategory.MEDIA.category), minBucketSize = 3)
        assertTrue(buckets.none { it.category == BuiltInCategory.OTHER.category })
    }

    @Test fun `a threshold of one folds nothing`() {
        val all = apps(1, BuiltInCategory.MEDIA.category, "m") + apps(1, BuiltInCategory.MONEY.category, "p")
        val buckets = bucketApps(all, minBucketSize = 1)
        assertEquals(listOf(BuiltInCategory.MEDIA.category, BuiltInCategory.MONEY.category), categoriesOf(buckets))
    }

    @Test fun `a threshold nothing can meet folds the whole library into Other`() {
        val all = apps(3, BuiltInCategory.MEDIA.category, "m") + apps(3, BuiltInCategory.MONEY.category, "p")
        val buckets = bucketApps(all, minBucketSize = 99)

        assertEquals(listOf(BuiltInCategory.OTHER.category), categoriesOf(buckets))
        assertEquals(6, buckets[0].apps.size)
    }

    @Test fun `folding never loses or duplicates an app`() {
        val all = apps(4, BuiltInCategory.MEDIA.category, "m") +
            apps(2, BuiltInCategory.MONEY.category, "p") +
            apps(1, BuiltInCategory.HEALTH.category, "h") +
            apps(2, BuiltInCategory.OTHER.category, "o")
        val buckets = bucketApps(all, minBucketSize = 3)

        val emitted = buckets.flatMap { it.apps }
        assertEquals(all.size, emitted.size)
        assertEquals(all.map { it.key }.toSet(), emitted.map { it.key }.toSet())
    }

    // ---- order -------------------------------------------------------------------------------

    @Test fun `bubbles follow the category enum's order, not the app order`() {
        // Fed in reverse: the output must not depend on how the repository happened to list them.
        val all = apps(3, BuiltInCategory.UTILITIES.category, "u") +
            apps(3, BuiltInCategory.MEDIA.category, "m") +
            apps(3, BuiltInCategory.COMMUNICATION.category, "c")
        val buckets = bucketApps(all, minBucketSize = 3)

        assertEquals(
            listOf(BuiltInCategory.COMMUNICATION.category, BuiltInCategory.MEDIA.category, BuiltInCategory.UTILITIES.category),
            categoriesOf(buckets),
        )
    }

    @Test fun `bubble order does not shift with bucket size`() {
        // Media is much the larger bucket; a size-ordered grid would put it first. It must not —
        // bubbles that reshuffle as apps come and go can never be found by muscle memory.
        val all = apps(9, BuiltInCategory.MEDIA.category, "m") + apps(3, BuiltInCategory.COMMUNICATION.category, "c")
        val buckets = bucketApps(all, minBucketSize = 3)

        assertEquals(listOf(BuiltInCategory.COMMUNICATION.category, BuiltInCategory.MEDIA.category), categoriesOf(buckets))
    }

    @Test fun `Other sorts last, behind every real bucket`() {
        val all = apps(2, BuiltInCategory.COMMUNICATION.category, "c") + apps(3, BuiltInCategory.UTILITIES.category, "u")
        val buckets = bucketApps(all, minBucketSize = 3)

        assertEquals(listOf(BuiltInCategory.UTILITIES.category, BuiltInCategory.OTHER.category), categoriesOf(buckets))
    }

    // ---- user-defined buckets (SID-61) -------------------------------------------------------

    private fun userCat(n: Int, label: String) = Category("user:$n", label, "app", builtIn = false)

    @Test fun `a user bucket is exempt from the min-size fold`() {
        // A two-app built-in folds into Other; a two-app user bucket, made on purpose, keeps its
        // bubble — otherwise a user bucket would behave differently from a built-in.
        val work = userCat(1, "Work")
        val all = apps(3, BuiltInCategory.MEDIA.category, "m") +
            apps(2, BuiltInCategory.MONEY.category, "p") +   // built-in, folds
            apps(2, work, "w")                               // user, survives
        val buckets = bucketApps(all, minBucketSize = 3)

        assertEquals(listOf(BuiltInCategory.MEDIA.category, work, BuiltInCategory.OTHER.category), categoriesOf(buckets))
    }

    @Test fun `user buckets sit after built-ins and before Other`() {
        val work = userCat(1, "Work")
        val all = apps(1, BuiltInCategory.OTHER.category, "o") +
            apps(2, work, "w") +
            apps(3, BuiltInCategory.MEDIA.category, "m")
        val buckets = bucketApps(all, minBucketSize = 3)

        assertEquals(listOf(BuiltInCategory.MEDIA.category, work, BuiltInCategory.OTHER.category), categoriesOf(buckets))
    }

    @Test fun `user buckets keep creation order, numerically not lexically`() {
        // "user:10" must follow "user:2", which a string sort would get wrong. Fed reversed.
        val c10 = userCat(10, "Later")
        val c2 = userCat(2, "Earlier")
        val all = apps(1, c10, "x") + apps(1, c2, "y")
        val buckets = bucketApps(all, minBucketSize = 3)

        assertEquals(listOf(c2, c10), categoriesOf(buckets))
    }

    // ---- the Mode C peek ---------------------------------------------------------------------

    @Test fun `a peek shows the bucket's first four, in order`() {
        val bucket = CategoryBucket(BuiltInCategory.MEDIA.category, apps(6, BuiltInCategory.MEDIA.category, "m"))
        assertEquals(listOf("m1", "m2", "m3", "m4"), peekApps(bucket).map { it.label })
    }

    @Test fun `a peek is stable — the same bucket always shows the same four`() {
        val bucket = CategoryBucket(BuiltInCategory.MEDIA.category, apps(6, BuiltInCategory.MEDIA.category, "m"))
        assertEquals(peekApps(bucket), peekApps(bucket))
    }

    @Test fun `a short bucket peeks everything it has and no more`() {
        val bucket = CategoryBucket(BuiltInCategory.OTHER.category, apps(2, BuiltInCategory.OTHER.category, "o"))
        assertEquals(listOf("o1", "o2"), peekApps(bucket).map { it.label })
    }

    @Test fun `an empty bucket peeks nothing rather than throwing`() {
        assertEquals(emptyList<AppEntry>(), peekApps(CategoryBucket(BuiltInCategory.OTHER.category, emptyList())))
    }

    @Test fun `the peek never exceeds the 2x2 the cluster can draw`() {
        val bucket = CategoryBucket(BuiltInCategory.MEDIA.category, apps(40, BuiltInCategory.MEDIA.category, "m"))
        assertEquals(PEEK_COUNT, peekApps(bucket).size)
        assertTrue(PEEK_COUNT <= 4)
    }

    // ---- the mode toggle ---------------------------------------------------------------------

    @Test fun `every browse mode carries a glyph the segmented control can draw`() {
        for (mode in BrowseMode.entries) {
            assertTrue("${mode.name} has no glyph", mode.glyph.isNotBlank())
        }
    }

    @Test fun `List is the only mode that is not grouped`() {
        assertEquals(
            listOf(BrowseMode.BUBBLES_PEEK),
            BrowseMode.entries.filter { it != BrowseMode.LIST },
        )
    }
}
