package fi.palonkorpi.sidehome.pane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesModelTest {

    private val notes = listOf(Note(1, "Call the bank"), Note(2, "Rain due Friday", done = true))

    @Test fun `encode then decode is the identity`() {
        assertEquals(notes, decodeNotes(encodeNotes(notes)))
    }

    @Test fun `an empty list round-trips to nothing on disk`() {
        assertEquals("", encodeNotes(emptyList()))
        assertEquals(emptyList<Note>(), decodeNotes(""))
        assertEquals(emptyList<Note>(), decodeNotes(null))
    }

    @Test fun `a corrupt row is dropped, not the whole list`() {
        val raw = encodeNotes(notes) + "\nnot-a-note\n\u001F\u001F"
        assertEquals(notes, decodeNotes(raw))
    }

    @Test fun `adding trims, collapses newlines, and ignores blank text`() {
        val out = emptyList<Note>().addNote("  Milk\nand eggs  ", id = 7)
        assertEquals(listOf(Note(7, "Milk and eggs")), out)
        assertEquals(out, out.addNote("   ", id = 8))
    }

    @Test fun `a note with a field separator typed in still round-trips`() {
        val out = emptyList<Note>().addNote("a\u001Fb", id = 1)
        assertEquals(out, decodeNotes(encodeNotes(out)))
    }

    @Test fun `toggle flips one, remove drops one, newest stays last`() {
        val toggled = notes.toggleNote(1)
        assertTrue(toggled[0].done)
        assertTrue(toggled[1].done)
        assertEquals(listOf(Note(2, "Rain due Friday", done = true)), notes.removeNote(1))
        assertEquals(3L, notes.addNote("Next", id = 3).last().id)
    }
}
