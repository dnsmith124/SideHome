package fi.palonkorpi.sidehome.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the layered category resolver and the keyword table. These
 * exercise the seam that decides every app's bucket; the `PackageManager` wiring
 * in [AppRepository] is a thin adapter over this logic.
 */
class CategoryEngineTest {

    // ---- layered priority: highest confidence wins ----

    @Test fun override_beats_every_lower_layer() {
        val c = CategoryEngine.resolve(
            override = BuiltInCategory.MONEY.category,
            intent = BuiltInCategory.WEB,
            fdroid = BuiltInCategory.MEDIA,
            hay = "firefox browser org.mozilla.firefox",
        )
        assertEquals(BuiltInCategory.MONEY.category, c)
    }

    @Test fun user_bucket_override_flows_through_as_the_final_category() {
        // The auto layers are all built-in by type, so only an override can carry a user bucket —
        // and when one does, it wins like any override and comes out unchanged (SID-61).
        val work = Category("user:1", "Work", "case", builtIn = false)
        val c = CategoryEngine.resolve(
            override = work,
            intent = BuiltInCategory.WEB,
            fdroid = null,
            hay = "slack com.slack",
        )
        assertEquals(work, c)
        assertTrue(!c.builtIn)
    }

    @Test fun intent_beats_fdroid_and_keyword() {
        // Name says "music" (keyword MEDIA) but the OS declares a browser role.
        val c = CategoryEngine.resolve(
            override = null,
            intent = BuiltInCategory.WEB,
            fdroid = BuiltInCategory.MEDIA,
            hay = "music player com.example.music",
        )
        assertEquals(BuiltInCategory.WEB.category, c)
    }

    @Test fun fdroid_beats_keyword() {
        val c = CategoryEngine.resolve(
            override = null,
            intent = null,
            fdroid = BuiltInCategory.MEDIA,
            hay = "newpipe org.schabi.newpipe", // no keyword match
        )
        assertEquals(BuiltInCategory.MEDIA.category, c)
    }

    @Test fun keyword_used_when_no_stronger_layer() {
        val c = CategoryEngine.resolve(
            override = null,
            intent = null,
            fdroid = null,
            hay = "my dialer com.example.phone",
        )
        assertEquals(BuiltInCategory.COMMUNICATION.category, c)
    }

    @Test fun unmatched_falls_through_to_other() {
        val c = CategoryEngine.resolve(
            override = null,
            intent = null,
            fdroid = null,
            hay = "zxqv com.acme.zxqv",
        )
        assertEquals(BuiltInCategory.OTHER.category, c)
    }

    @Test fun never_silently_wrong_only_ever_other_when_unknown() {
        // The whole point of Layer 5: unknowns are Other, not a guessed bucket.
        assertNull(CategoryEngine.keywordCategory("wholly unrecognised thing"))
        val c = CategoryEngine.resolve(null, null, null, "wholly unrecognised thing")
        assertEquals(BuiltInCategory.OTHER.category, c)
    }

    // ---- keyword table: glyph and category stay in lockstep ----

    @Test fun keyword_maps_expected_buckets() {
        assertEquals(BuiltInCategory.COMMUNICATION, CategoryEngine.keywordCategory("signal messenger"))
        assertEquals(BuiltInCategory.MEDIA, CategoryEngine.keywordCategory("open camera"))
        assertEquals(BuiltInCategory.WEB, CategoryEngine.keywordCategory("firefox"))
        assertEquals(BuiltInCategory.WEB, CategoryEngine.keywordCategory("osm map"))
        assertEquals(BuiltInCategory.MONEY, CategoryEngine.keywordCategory("my bank"))
        assertEquals(BuiltInCategory.HEALTH, CategoryEngine.keywordCategory("fit tracker"))
        assertEquals(BuiltInCategory.PRODUCTIVITY, CategoryEngine.keywordCategory("notes"))
        assertEquals(BuiltInCategory.UTILITIES, CategoryEngine.keywordCategory("weather"))
    }

    @Test fun keyword_glyph_and_category_share_the_first_match() {
        // camera → MEDIA bucket but its own specific glyph, not the bucket glyph.
        assertEquals("camera", CategoryEngine.keywordGlyph("open camera"))
        assertEquals(BuiltInCategory.MEDIA, CategoryEngine.keywordCategory("open camera"))
        assertNotEquals(BuiltInCategory.MEDIA.glyph, CategoryEngine.keywordGlyph("open camera"))
    }

    @Test fun keyword_order_first_match_wins() {
        // "phone" (COMMUNICATION) precedes any later rule; "music phone" hits phone first.
        assertEquals(BuiltInCategory.COMMUNICATION, CategoryEngine.keywordCategory("music phone"))
    }

    // ---- glyphFor: specific keyword glyph beats the bucket glyph, Other → app ----

    @Test fun glyphFor_prefers_specific_keyword_glyph() {
        assertEquals("camera", CategoryEngine.glyphFor("open camera", BuiltInCategory.MEDIA.category))
    }

    @Test fun glyphFor_falls_back_to_category_glyph() {
        // No keyword hit, but the resolved bucket lends its representative glyph.
        assertEquals(BuiltInCategory.WEB.glyph, CategoryEngine.glyphFor("org.schabi.newpipe", BuiltInCategory.WEB.category))
    }

    @Test fun glyphFor_uses_a_user_buckets_own_glyph_when_no_keyword_hits() {
        val work = Category("user:1", "Work", "case", builtIn = false)
        assertEquals("case", CategoryEngine.glyphFor("zxqv com.acme.zxqv", work))
    }

    @Test fun glyphFor_other_is_neutral_app_tile() {
        assertEquals("app", CategoryEngine.glyphFor("zxqv", BuiltInCategory.OTHER.category))
    }

    // ---- built-in id round-trip (the seam a stale/user id resolves through) ----

    @Test fun byId_round_trips_and_rejects_garbage() {
        for (cat in BuiltInCategory.entries) {
            assertEquals(cat, BuiltInCategory.byId(cat.name))
        }
        assertNull(BuiltInCategory.byId("NOT_A_CATEGORY"))
        assertNull(BuiltInCategory.byId("user:1"))
    }
}

/** Sanity checks on the bundled F-Droid snapshot (Layer 3). */
class FdroidCategoryMapTest {

    @Test fun known_packages_resolve() {
        assertEquals(BuiltInCategory.MEDIA, FdroidCategoryMap.categoryFor("org.schabi.newpipe"))
        assertEquals(BuiltInCategory.COMMUNICATION, FdroidCategoryMap.categoryFor("org.thoughtcrime.securesms"))
        assertEquals(BuiltInCategory.WEB, FdroidCategoryMap.categoryFor("net.osmand.plus"))
        assertEquals(BuiltInCategory.UTILITIES, FdroidCategoryMap.categoryFor("org.fdroid.fdroid"))
    }

    @Test fun unlisted_package_is_null_so_it_falls_through() {
        assertNull(FdroidCategoryMap.categoryFor("com.acme.unknown"))
    }

    @Test fun snapshot_is_non_trivial() {
        assertTrue("bundled map should carry a real seed", FdroidCategoryMap.size > 30)
    }
}
