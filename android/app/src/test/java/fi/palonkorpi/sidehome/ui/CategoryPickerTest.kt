package fi.palonkorpi.sidehome.ui

import fi.palonkorpi.sidehome.apps.BuiltInCategory
import fi.palonkorpi.sidehome.apps.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the category picker's pure logic (SID-62): how the choice list is assembled and
 * ordered, and which row the picker marks as the app's current category.
 *
 * The list has to be built from a *source* — built-ins from the enum plus the repo's user buckets —
 * never a hardcoded set, so a bucket made from the §1 menu appears the instant it exists. These
 * tests pin that contract without needing Compose or a device.
 */
class CategoryPickerTest {

    private fun user(id: String, label: String, glyph: String = "app") =
        Category(id, label, glyph, builtIn = false)

    @Test fun `choices are the built-ins in enum order when there are no user buckets`() {
        assertEquals(
            BuiltInCategory.entries.map { it.category },
            categoryChoices(emptyList()),
        )
    }

    @Test fun `user buckets follow the built-ins, in the given order`() {
        val work = user("user:1", "Work")
        val play = user("user:2", "Play")

        val choices = categoryChoices(listOf(work, play))

        assertEquals(BuiltInCategory.entries.size + 2, choices.size)
        // built-ins first, unchanged
        assertEquals(BuiltInCategory.entries.map { it.category }, choices.take(BuiltInCategory.entries.size))
        // then the user buckets, creation order preserved
        assertEquals(listOf(work, play), choices.takeLast(2))
    }

    @Test fun `every built-in is present exactly once`() {
        val choices = categoryChoices(listOf(user("user:1", "Work")))
        for (b in BuiltInCategory.entries) {
            assertEquals(1, choices.count { it.id == b.name })
        }
    }

    @Test fun `the current category is matched on id, not label or glyph`() {
        val media = BuiltInCategory.MEDIA.category
        // Same id, but display fields differ — still the current one.
        val relabeled = media.copy(label = "Sounds", glyph = "headset")

        assertTrue(isCurrentCategory(media, currentCategoryId = "MEDIA"))
        assertTrue(isCurrentCategory(relabeled, currentCategoryId = "MEDIA"))
    }

    @Test fun `a different category is not marked current`() {
        assertFalse(isCurrentCategory(BuiltInCategory.WEB.category, currentCategoryId = "MEDIA"))
        assertFalse(isCurrentCategory(user("user:1", "Work"), currentCategoryId = "MEDIA"))
    }

    @Test fun `a user bucket is current when its id is the resolved one`() {
        val work = user("user:1", "Work")
        assertTrue(isCurrentCategory(work, currentCategoryId = "user:1"))
        assertFalse(isCurrentCategory(work, currentCategoryId = "user:2"))
    }
}
