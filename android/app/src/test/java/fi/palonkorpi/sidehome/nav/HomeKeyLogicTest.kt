package fi.palonkorpi.sidehome.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure HOME-key + entrance-cascade logic (SID-33). These pin the routing table
 * (HOME steps back toward bare home, else opens the drawer) and the reveal-edge classification
 * (only a full-screen surface's fully-covered → revealed edge replays the cascade — never a
 * lightweight float, never a partial peek), so the on-device behaviour can be reasoned about
 * rather than only eyeballed.
 */
class HomeKeyLogicTest {

    // ---- HOME-key routing table ------------------------------------------------------------

    @Test fun `inside another app returns to bare home with cascade`() {
        assertEquals(
            HomeKeyAction.ReturnToBareWithCascade,
            homeKeyAction(HomeUiState(inAnotherApp = true)),
        )
    }

    @Test fun `drawer-full collapses to bare with cascade`() {
        assertEquals(
            HomeKeyAction.CollapseWithCascade,
            homeKeyAction(HomeUiState(drawerFull = true)),
        )
    }

    @Test fun `open folder collapses to bare with cascade`() {
        assertEquals(
            HomeKeyAction.CollapseWithCascade,
            homeKeyAction(HomeUiState(folderOpen = true)),
        )
    }

    @Test fun `open spotlight collapses to bare with cascade`() {
        assertEquals(
            HomeKeyAction.CollapseWithCascade,
            homeKeyAction(HomeUiState(spotlightOpen = true)),
        )
    }

    @Test fun `open edit sheet collapses to bare with cascade`() {
        assertEquals(
            HomeKeyAction.CollapseWithCascade,
            homeKeyAction(HomeUiState(editOpen = true)),
        )
    }

    @Test fun `action-strip menu dismisses to bare with no cascade`() {
        assertEquals(
            HomeKeyAction.DismissNoCascade,
            homeKeyAction(HomeUiState(actionMenuOpen = true)),
        )
    }

    @Test fun `icon picker dismisses to bare with no cascade`() {
        assertEquals(
            HomeKeyAction.DismissNoCascade,
            homeKeyAction(HomeUiState(iconPickerOpen = true)),
        )
    }

    @Test fun `debug scrubber dismisses to bare with no cascade`() {
        assertEquals(
            HomeKeyAction.DismissNoCascade,
            homeKeyAction(HomeUiState(debugOpen = true)),
        )
    }

    @Test fun `bare home with nothing open opens the drawer`() {
        assertEquals(HomeKeyAction.OpenDrawer, homeKeyAction(HomeUiState()))
    }

    // ---- routing priority ------------------------------------------------------------------

    @Test fun `a full-screen overlay outranks a lightweight float`() {
        // e.g. the action menu long-pressed over a fully-open drawer: the drawer's presence means
        // HOME is a real reveal, so cascade wins over the float's no-cascade dismissal.
        assertEquals(
            HomeKeyAction.CollapseWithCascade,
            homeKeyAction(HomeUiState(drawerFull = true, actionMenuOpen = true)),
        )
    }

    @Test fun `app coverage outranks every on-screen overlay`() {
        assertEquals(
            HomeKeyAction.ReturnToBareWithCascade,
            homeKeyAction(
                HomeUiState(
                    inAnotherApp = true,
                    drawerFull = true,
                    folderOpen = true,
                    debugOpen = true,
                ),
            ),
        )
    }

    // ---- reveal-edge classification --------------------------------------------------------

    @Test fun `closing a full-screen surface fires the cascade`() {
        assertTrue(shouldFireCascade(prevCovered = true, nowCovered = false, kind = CoveringSurface.DRAWER_FULL))
    }

    @Test fun `closing a lightweight float does not fire the cascade`() {
        assertFalse(shouldFireCascade(prevCovered = true, nowCovered = false, kind = CoveringSurface.ACTION_MENU))
    }

    @Test fun `opening a surface does not fire the cascade`() {
        assertFalse(shouldFireCascade(prevCovered = false, nowCovered = true, kind = CoveringSurface.FOLDER))
    }

    @Test fun `staying covered does not fire the cascade`() {
        assertFalse(shouldFireCascade(prevCovered = true, nowCovered = true, kind = CoveringSurface.SPOTLIGHT))
    }

    @Test fun `a partial peek that never reached full does not fire the cascade`() {
        // prev already false: the drawer was only peeked, never fully covered → no reveal edge.
        assertFalse(shouldFireCascade(prevCovered = false, nowCovered = false, kind = CoveringSurface.DRAWER_FULL))
    }

    @Test fun `every full-screen surface fires on its close edge`() {
        CoveringSurface.values().filter { it.isFullScreen }.forEach { kind ->
            assertTrue("$kind should fire on close", shouldFireCascade(true, false, kind))
        }
    }

    @Test fun `no lightweight float fires on its close edge`() {
        CoveringSurface.values().filterNot { it.isFullScreen }.forEach { kind ->
            assertFalse("$kind must not fire on close", shouldFireCascade(true, false, kind))
        }
    }
}
