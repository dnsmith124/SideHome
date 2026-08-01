package fi.palonkorpi.sidehome.ime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This is the contract with SideType, executable (SID-18 / SID-20). The receiver ships already and
 * parses these exact strings; a token that is off by a character makes SideType do nothing at all,
 * which on-device is indistinguishable from a bug on their side. So these tests assert whole
 * literal wire strings rather than "contains a barBg somewhere" — if a refactor moves a key, joins
 * on "; ", or lowercases a hex digit, that is a protocol break and it must fail here first.
 */
class ImeSignalsTest {

    private val bg = Color(0xFF1B2A4A)
    private val fg = Color(0xFFFBF8F2)
    private val accent = Color(0xFF4C7FD0)
    private val sep = Color(0x38FBF8F2)

    // ---- the full payload, verbatim ------------------------------------------------------------

    @Test fun `the drawer field sends five colours plus appDict, in order`() {
        assertEquals(
            "fi.palonkorpi.sidetype.barBg=FF1B2A4A," +
                "fi.palonkorpi.sidetype.barFg=FFFBF8F2," +
                "fi.palonkorpi.sidetype.selectedBg=FF4C7FD0," +
                "fi.palonkorpi.sidetype.selectedText=FF1B2A4A," +
                "fi.palonkorpi.sidetype.separator=38FBF8F2," +
                "fi.palonkorpi.sidetype.appDict=1",
            drawerImeOptions(fg = fg, accent = accent, backdrop = bg, separator = sep),
        )
    }

    @Test fun `the backdrop is both the tray fill and the ink on the accent pill`() {
        // the inverted-chip mapping: selectedText is the backdrop, not the foreground
        val s = drawerImeOptions(fg = fg, accent = accent, backdrop = bg, separator = sep)
        assertTrue(s.contains("selectedText=FF1B2A4A"))
        assertTrue(s.contains("selectedBg=FF4C7FD0"))
    }

    // ---- namespace + format --------------------------------------------------------------------

    @Test fun `every key is namespaced to SideType's real applicationId`() {
        // NOT com.sidephone.sidetype — that namespace was the pre-grilling spec and is ignored.
        val s = drawerImeOptions(fg = fg, accent = accent, backdrop = bg, separator = sep)
        for (token in s.split(",")) {
            assertTrue(token, token.startsWith("fi.palonkorpi.sidetype."))
        }
    }

    @Test fun `colours are 8-hex ARGB, uppercase, unprefixed`() {
        val s = sideTypeImeOptions(barBg = Color(0xFF0A0B0C))
        assertEquals("fi.palonkorpi.sidetype.barBg=FF0A0B0C", s)
    }

    @Test fun `a fully transparent colour still sends all eight digits`() {
        // padStart, not a trimmed hex: 0x00000000 must not collapse to "0".
        assertEquals(
            "fi.palonkorpi.sidetype.barBg=00000000",
            sideTypeImeOptions(barBg = Color(0x00000000)),
        )
    }

    @Test fun `alpha survives the round trip`() {
        // the separator is the drawer's own divider tone, which is fg at 22% — the alpha is real
        // information on this wire, since the format is ARGB rather than RGB.
        assertEquals(
            "fi.palonkorpi.sidetype.separator=80FFFFFF",
            sideTypeImeOptions(separator = Color(0x80FFFFFF)),
        )
    }

    @Test fun `a non-sRGB colour is converted to sRGB rather than sent raw`() {
        // The ambient is authored in OKLCH and its colours can arrive in a wide-gamut space; the
        // wire is sRGB ARGB. Pure white in Display P3 must land on sRGB white, not on P3's raw
        // components. (DECISIONS: never claim a colour space you aren't in.)
        val p3White = Color(1f, 1f, 1f, 1f, ColorSpaces.DisplayP3)
        assertEquals(
            "fi.palonkorpi.sidetype.barBg=FFFFFFFF",
            sideTypeImeOptions(barBg = p3White),
        )
    }

    // ---- the appDict flag ----------------------------------------------------------------------

    @Test fun `appDict is sent as literally 1`() {
        // SideType's isFlagOn() accepts only "1" or "true" — "yes"/"on"/empty are all off.
        assertEquals("fi.palonkorpi.sidetype.appDict=1", sideTypeImeOptions(appDict = true))
    }

    @Test fun `appDict off sends no token at all rather than appDict=0`() {
        assertEquals("", sideTypeImeOptions(appDict = false))
    }

    // ---- "send only what you have" -------------------------------------------------------------

    @Test fun `a missing backdrop drops both channels it feeds, and nothing else`() {
        // Frameless drawer with no backdrop handed in: SideType defaults barBg + selectedText.
        assertEquals(
            "fi.palonkorpi.sidetype.barFg=FFFBF8F2," +
                "fi.palonkorpi.sidetype.selectedBg=FF4C7FD0," +
                "fi.palonkorpi.sidetype.separator=38FBF8F2," +
                "fi.palonkorpi.sidetype.appDict=1",
            drawerImeOptions(fg = fg, accent = accent, backdrop = null, separator = sep),
        )
    }

    @Test fun `each key is independently optional`() {
        assertEquals("fi.palonkorpi.sidetype.barFg=FFFBF8F2", sideTypeImeOptions(barFg = fg))
        assertEquals("fi.palonkorpi.sidetype.selectedBg=FF4C7FD0", sideTypeImeOptions(selectedBg = accent))
        assertEquals("", sideTypeImeOptions())
    }

    // ---- what the receiver's parser will make of it ---------------------------------------------

    @Test fun `the string survives SideType's own parser`() {
        // Mirrors HostImeOptions: split on ",", first "=" splits key/value, both trimmed.
        val parsed = drawerImeOptions(fg = fg, accent = accent, backdrop = bg, separator = sep)
            .split(",")
            .filter { it.contains("=") && !it.startsWith("=") }
            .associate { token ->
                val i = token.indexOf('=')
                token.substring(0, i).trim() to token.substring(i + 1).trim()
            }
        assertEquals(6, parsed.size)
        assertEquals("1", parsed["fi.palonkorpi.sidetype.appDict"])
        assertEquals("FF1B2A4A", parsed["fi.palonkorpi.sidetype.barBg"])
        // every colour value parses as an unsigned 32-bit int
        for ((k, v) in parsed) {
            if (k.endsWith("appDict")) continue
            assertEquals(8, v.length)
            v.toUInt(16)
        }
    }

    @Test fun `no token contains a separator that would break the split`() {
        val s = drawerImeOptions(fg = fg, accent = accent, backdrop = bg, separator = sep)
        assertEquals(6, s.split(",").size)
        for (token in s.split(",")) {
            assertEquals(1, token.count { it == '=' })
            assertEquals(token, token.trim())
        }
    }
}
