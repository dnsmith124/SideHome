package fi.palonkorpi.sidehome.ui

import fi.palonkorpi.sidehome.apps.AppRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the strip variant raised on an app inside an open folder (SID-31, SPEC §1 D).
 *
 * The rule this variant exists to prove is that a folder's contents are managed in the *same*
 * grammar as the home's — hold the thing itself, get a strip beside it — rather than in an edit
 * surface of their own. So the checks here are mostly about what it deliberately does *not* do.
 */
class FolderMemberStripTest {

    private val member = ActionContext(ActionTarget.FOLDER_MEMBER)

    private fun actions(ctx: ActionContext) = actionsFor(ctx).map { it.action }

    @Test fun `a folder member offers change-icon, move-to-home, remove-from-folder, app-info`() {
        assertEquals(
            listOf(
                StripAction.CHANGE_ICON,
                StripAction.ADD_HOME,
                StripAction.REMOVE,
                StripAction.APP_INFO,
            ),
            actions(member),
        )
    }

    @Test fun `move-to-home is never dimmed, whatever the home is doing`() {
        // The one cell that could plausibly be gated on the cap, and deliberately isn't: a full home
        // is resolvable (replace / nest / combine all free a slot), so the cell hands the app to the
        // in-hand placement flow rather than refusing. A wall is the outcome SPEC §1 rules out.
        // Past the cap as well as up to it: nothing here may start caring at the boundary.
        for (count in 0..AppRepository.MAX_HOME + 1) {
            val cells = actionsFor(member.copy(homeCount = count, onHome = true))
            assertTrue(
                "move-to-home went dim at homeCount=$count",
                cells.first { it.action == StripAction.ADD_HOME }.enabled,
            )
        }
    }

    @Test fun `every cell is live — nothing about a member can fail to run`() {
        assertTrue(actionsFor(member).all { it.enabled })
    }

    @Test fun `there is no add-member cell — you add by dropping an app onto the folder`() {
        // Adding is already covered by the drag (SID-29/SID-30), so a cell for it would be a second
        // way to do one thing, and the fifth cell in a row that only fits five.
        assertTrue(StripAction.ADD_FOLDER !in actions(member))
        assertTrue(actionsFor(member).size < MAX_STRIP_ACTIONS)
    }

    @Test fun `there is no rename cell — a member is an app, and its name is the app's`() {
        assertTrue(StripAction.RENAME !in actions(member))
    }
}
