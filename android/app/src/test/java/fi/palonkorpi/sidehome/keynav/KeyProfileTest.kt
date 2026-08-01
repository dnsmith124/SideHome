package fi.palonkorpi.sidehome.keynav

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped profiles (SID-86) and the "one unified table, no tile detection" thesis (SID-89): these
 * pin that the three tiles' keycodes are disjoint (so their union is lossless), that the reserved-set
 * never claims the rocker, and that every profile can at least select and go back.
 */
class KeyProfileTest {

    @Test fun `the unified default is every tile's union, losslessly`() {
        val union = KeyProfiles.UNIFIED.bindings
        for (profile in listOf(KeyProfiles.SUNDIAL, KeyProfiles.QWERTY, KeyProfiles.T9)) {
            for ((code, intent) in profile.bindings) {
                assertEquals("keycode $code in ${profile.id} survives the merge", intent, union[code])
            }
        }
    }

    @Test fun `the tiles' keycodes are disjoint except for the agreed Enter`() {
        // The union is only collision-free — and so needs no tile detection — if no keycode means two
        // different things across tiles. The one overlap (Enter) must agree on Select.
        val tiles = listOf(KeyProfiles.SUNDIAL, KeyProfiles.QWERTY, KeyProfiles.T9)
        for (i in tiles.indices) {
            for (j in i + 1 until tiles.size) {
                val shared = tiles[i].bindings.keys intersect tiles[j].bindings.keys
                for (code in shared) {
                    assertEquals(
                        "shared keycode $code disagrees between ${tiles[i].id} and ${tiles[j].id}",
                        tiles[i].bindings[code],
                        tiles[j].bindings[code],
                    )
                }
            }
        }
    }

    @Test fun `every profile can select and go back`() {
        for (profile in KeyProfiles.ALL) {
            assertTrue("${profile.id} has no Select", profile.bindings.containsValue(KeyIntent.Select))
            assertTrue("${profile.id} has no Back", profile.bindings.containsValue(KeyIntent.Back))
        }
    }

    @Test fun `no profile nor the reserved set ever binds the rocker`() {
        for (profile in KeyProfiles.ALL) {
            assertFalse("${profile.id} bound the rocker", profile.bindings.containsKey(KeyEvent.KEYCODE_CTRL_LEFT))
        }
        assertFalse(RESERVED_WHILE_TYPING.containsKey(KeyEvent.KEYCODE_CTRL_LEFT))
    }

    @Test fun `the reserved set is the D-pad ring plus select and back, nothing typeable`() {
        assertEquals(KeyIntent.NavUp, RESERVED_WHILE_TYPING[KeyEvent.KEYCODE_DPAD_UP])
        assertEquals(KeyIntent.Select, RESERVED_WHILE_TYPING[KeyEvent.KEYCODE_ENTER])
        assertEquals(KeyIntent.Back, RESERVED_WHILE_TYPING[KeyEvent.KEYCODE_BACK])
        // No letter or digit is ever reserved — those must always type.
        assertNull(RESERVED_WHILE_TYPING[KeyEvent.KEYCODE_T])
        assertNull(RESERVED_WHILE_TYPING[KeyEvent.KEYCODE_2])
    }

    @Test fun `the number grid is direct-index — both T9 digits and the QWERTY letters they print on`() {
        val u = KeyProfiles.UNIFIED.bindings
        assertEquals(KeyIntent.QuickIndex(1), u[KeyEvent.KEYCODE_1])
        assertEquals(KeyIntent.QuickIndex(9), u[KeyEvent.KEYCODE_9])
        // The marking 1=E, 2=T, 9=M — the letter keys the same numbers are printed on.
        assertEquals(KeyIntent.QuickIndex(1), u[KeyEvent.KEYCODE_E])
        assertEquals(KeyIntent.QuickIndex(2), u[KeyEvent.KEYCODE_T])
        assertEquals(KeyIntent.QuickIndex(9), u[KeyEvent.KEYCODE_M])
        // 0 / SPACE are spare.
        assertNull(u[KeyEvent.KEYCODE_0])
        assertNull(u[KeyEvent.KEYCODE_SPACE])
    }

    @Test fun `byId round-trips the known profiles and misses the unknown`() {
        assertEquals(KeyProfiles.SUNDIAL, KeyProfiles.byId("sundial"))
        assertEquals(KeyProfiles.UNIFIED, KeyProfiles.byId("unified"))
        assertNull(KeyProfiles.byId("gamepad"))
    }
}
