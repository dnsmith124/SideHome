package fi.palonkorpi.sidehome.ui

import fi.palonkorpi.sidehome.apps.AppRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the action strip's pure logic (SID-28): which cells each variant offers, and the
 * Y-anchor's flip + clamp. These are the decisions the strip is *about* — the anchoring rule exists
 * so the bar never slides under the status bar or off the bottom, and the ≤5-action cap is what
 * keeps it one clean row.
 *
 * Four variants, not three: [ActionTarget.FOLDER_MEMBER] arrived with SID-31 and has a suite of its
 * own next door in `FolderMemberStripTest`, which is why this file's variant section only walks the
 * other three.
 */
class ActionStripTest {

    private fun actions(ctx: ActionContext) = actionsFor(ctx).map { it.action }

    private fun enabled(ctx: ActionContext, action: StripAction) =
        actionsFor(ctx).first { it.action == action }.enabled

    // ---- the variants ------------------------------------------------------------------------

    @Test fun `a library app offers change-icon, add-to-home, category, hide, lock, app-info`() {
        // No Uninstall cell: it never resolved on the de-Googled ROM, so uninstall is handled through
        // Info (the app's system-details page) rather than a dead launcher cell.
        assertEquals(
            listOf(
                StripAction.CHANGE_ICON,
                StripAction.ADD_HOME,
                StripAction.CATEGORY,
                StripAction.HIDE,
                StripAction.LOCK,
                StripAction.APP_INFO,
            ),
            actions(ActionContext(ActionTarget.LIBRARY_APP)),
        )
    }

    @Test fun `a shelved app offers only the way back, and info`() {
        assertEquals(listOf(StripAction.UNHIDE, StripAction.APP_INFO), actions(ActionContext(ActionTarget.SHELVED_HIDDEN)))
        assertEquals(listOf(StripAction.UNLOCK, StripAction.APP_INFO), actions(ActionContext(ActionTarget.SHELVED_LOCKED)))
        for (t in listOf(ActionTarget.SHELVED_HIDDEN, ActionTarget.SHELVED_LOCKED)) {
            assertTrue("$t must not offer add-to-home", !actions(ActionContext(t)).contains(StripAction.ADD_HOME))
        }
    }

    @Test fun `home app offers change-icon, add-to-folder, move, remove, app-info`() {
        assertEquals(
            listOf(
                StripAction.CHANGE_ICON,
                StripAction.ADD_FOLDER,
                StripAction.MOVE,
                StripAction.REMOVE,
                StripAction.APP_INFO,
            ),
            actions(ActionContext(ActionTarget.HOME_APP, homeCount = 4)),
        )
    }

    @Test fun `home folder offers rename, change-icon, move, remove`() {
        assertEquals(
            listOf(
                StripAction.RENAME,
                StripAction.CHANGE_ICON,
                StripAction.MOVE,
                StripAction.REMOVE,
            ),
            actions(ActionContext(ActionTarget.HOME_FOLDER, homeCount = 4)),
        )
    }

    @Test fun `no variant exceeds the one-row cap`() {
        for (target in ActionTarget.values()) {
            val n = actionsFor(ActionContext(target)).size
            assertTrue("$target has $n actions", n <= MAX_STRIP_ACTIONS)
        }
    }

    // ---- enablement --------------------------------------------------------------------------

    @Test fun `add-to-home is live for a library app that is not placed`() {
        assertTrue(enabled(ActionContext(ActionTarget.LIBRARY_APP, onHome = false), StripAction.ADD_HOME))
    }

    @Test fun `add-to-home is dead once the app is already on home`() {
        assertFalse(enabled(ActionContext(ActionTarget.LIBRARY_APP, onHome = true), StripAction.ADD_HOME))
    }

    @Test fun `add-to-home never dims at the home cap — a full home is a question, not a wall`() {
        // It used to dim here, and that dimming *was* the dead-end §1 forbids (SID-30). A full home
        // resolves by grouping: drop the app on a row and it combines, nests, or takes the slot.
        // Past the cap as well as up to it — nothing may start caring at the boundary.
        for (count in 0..AppRepository.MAX_HOME + 1) {
            assertTrue(
                "add-to-home went dim at homeCount=$count",
                enabled(ActionContext(ActionTarget.LIBRARY_APP, homeCount = count), StripAction.ADD_HOME),
            )
        }
    }

    @Test fun `the cell stays present even when it cannot run, so the row does not reflow`() {
        // dimmed, not dropped: the strip's width is semi-fixed per variant (SID-28).
        assertTrue(actions(ActionContext(ActionTarget.LIBRARY_APP, onHome = true)).contains(StripAction.ADD_HOME))
    }

    @Test fun `move is dead on a home of one — there is nowhere to move to`() {
        assertFalse(enabled(ActionContext(ActionTarget.HOME_APP, homeCount = 1), StripAction.MOVE))
        assertFalse(enabled(ActionContext(ActionTarget.HOME_FOLDER, homeCount = 1), StripAction.MOVE))
    }

    @Test fun `move is live once the home has two items`() {
        assertTrue(enabled(ActionContext(ActionTarget.HOME_APP, homeCount = 2), StripAction.MOVE))
    }

    // ---- the Y anchor ------------------------------------------------------------------------
    //
    // A 480x640 screen: strip 62dp tall, 10dp gap, 36dp top limit, ~8dp bottom margin. Working in
    // raw px here (the pure layer is unit-agnostic; the Compose layer converts).

    private val stripH = 160f
    private val gap = 26f
    private val topLimit = 92f
    private val bottomLimit = 1620f

    @Test fun `an item mid-screen puts the strip above it`() {
        val y = stripAnchorY(
            itemTop = 800f, itemBottom = 880f,
            stripHeight = stripH, gap = gap, topLimit = topLimit, bottomLimit = bottomLimit,
        )
        assertFalse(stripDropsBelow(itemTop = 800f, stripHeight = stripH, gap = gap, topLimit = topLimit))
        assertEquals(800f - gap - stripH, y, 0.01f)
    }

    @Test fun `an item near the top drops the strip below it, clear of the status bar`() {
        assertTrue(stripDropsBelow(itemTop = 120f, stripHeight = stripH, gap = gap, topLimit = topLimit))
        val y = stripAnchorY(
            itemTop = 120f, itemBottom = 200f,
            stripHeight = stripH, gap = gap, topLimit = topLimit, bottomLimit = bottomLimit,
        )
        assertEquals(200f + gap, y, 0.01f)
        assertTrue(y >= topLimit)
    }

    @Test fun `an item near the bottom keeps the strip above it`() {
        val y = stripAnchorY(
            itemTop = 1500f, itemBottom = 1580f,
            stripHeight = stripH, gap = gap, topLimit = topLimit, bottomLimit = bottomLimit,
        )
        assertEquals(1500f - gap - stripH, y, 0.01f)
        assertTrue(y + stripH <= bottomLimit)
    }

    @Test fun `the strip never leaves the safe band at either edge`() {
        // an item pinned to the very top: dropping below still can't push it past the bottom limit
        val top = stripAnchorY(
            itemTop = 0f, itemBottom = 0f,
            stripHeight = stripH, gap = gap, topLimit = topLimit, bottomLimit = bottomLimit,
        )
        assertTrue(top >= topLimit)
        // an item hanging off the bottom: the strip clamps rather than following it off-screen
        val bottom = stripAnchorY(
            itemTop = 3000f, itemBottom = 3080f,
            stripHeight = stripH, gap = gap, topLimit = topLimit, bottomLimit = bottomLimit,
        )
        assertTrue(bottom + stripH <= bottomLimit + 0.01f)
    }

    @Test fun `a band shorter than the strip clamps to the top instead of throwing`() {
        // coerceIn throws on an inverted range, so the upper bound is floored at topLimit.
        val y = stripAnchorY(
            itemTop = 100f, itemBottom = 150f,
            stripHeight = stripH, gap = gap, topLimit = topLimit, bottomLimit = topLimit + 10f,
        )
        assertEquals(topLimit, y, 0.01f)
    }

    @Test fun `the flip decision only depends on the room above`() {
        // exactly enough room above → stays above
        assertFalse(stripDropsBelow(itemTop = topLimit + gap + stripH, stripHeight = stripH, gap = gap, topLimit = topLimit))
        // one px less → flips
        assertTrue(stripDropsBelow(itemTop = topLimit + gap + stripH - 1f, stripHeight = stripH, gap = gap, topLimit = topLimit))
    }
}
