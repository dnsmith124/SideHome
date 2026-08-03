package fi.palonkorpi.sidehome.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SID-180: the consent's flag lifecycle, on [FirstRunCoachTest]'s pattern. The invariant under
 * test is the destructive-act gate: nothing is committed until an answer, either answer commits
 * exactly once, and "not now" commits the do-nothing pair.
 */
class WallpaperConsentTest {

    private class FakeStore {
        var home = false
        var lock = false
        var commits = 0
        var seen = false

        fun consent() = WallpaperConsentState(seen) { h, l ->
            home = h
            lock = l
            commits += 1
            seen = true
        }
    }

    @Test
    fun `a fresh install is asked`() {
        assertTrue(FakeStore().consent().visible)
    }

    @Test
    fun `an install that has answered is never asked again`() {
        val store = FakeStore()
        store.consent().approve(home = true, lock = true)
        assertFalse(store.consent().visible)
    }

    @Test
    fun `showing the question commits nothing`() {
        val store = FakeStore()
        store.consent()
        assertEquals(0, store.commits)
        assertFalse(store.home)
        assertFalse(store.lock)
    }

    @Test
    fun `approve commits the toggles as left`() {
        val store = FakeStore()
        store.consent().approve(home = true, lock = false)
        assertTrue(store.home)
        assertFalse(store.lock)
        assertEquals(1, store.commits)
        assertTrue(store.seen)
    }

    @Test
    fun `not now commits both slots off, and still counts as answered`() {
        val store = FakeStore()
        store.consent().dismiss()
        assertFalse(store.home)
        assertFalse(store.lock)
        assertEquals(1, store.commits)
        assertTrue(store.seen)
    }

    @Test
    fun `a second answer is not a second commit`() {
        val store = FakeStore()
        val consent = store.consent()
        consent.approve(home = true, lock = true)
        consent.approve(home = false, lock = false)
        consent.dismiss()
        assertEquals(1, store.commits)
        assertTrue(store.home)
        assertTrue(store.lock)
    }
}
