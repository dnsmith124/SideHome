package fi.palonkorpi.sidehome.pane

/**
 * One line on the Notes page. [done] is a ticked reminder: it stays on the page, struck through,
 * until it is removed — a journal line is simply a note nobody ticks.
 */
data class Note(val id: Long, val text: String, val done: Boolean = false)

private const val FIELD = '\u001F'   // unit separator: never typed, so the text needs no escaping
private const val ROW = '\n'

/** One line per note, fields split on a control character. Newlines are stripped on [addNote]. */
fun encodeNotes(notes: List<Note>): String =
    notes.joinToString(ROW.toString()) { "${it.id}$FIELD${if (it.done) 1 else 0}$FIELD${it.text}" }

/** Tolerant of anything: a corrupt row is dropped, never the whole list. */
fun decodeNotes(raw: String?): List<Note> {
    if (raw.isNullOrEmpty()) return emptyList()
    return raw.split(ROW).mapNotNull { row ->
        val parts = row.split(FIELD, limit = 3)
        if (parts.size != 3) return@mapNotNull null
        val id = parts[0].toLongOrNull() ?: return@mapNotNull null
        Note(id = id, text = parts[2], done = parts[1] == "1")
    }
}

/** Append a trimmed, single-line note; blank text adds nothing. Newest last, like a journal. */
fun List<Note>.addNote(text: String, id: Long): List<Note> {
    val clean = text.replace(ROW, ' ').replace(FIELD, ' ').trim()
    if (clean.isEmpty()) return this
    return this + Note(id = id, text = clean)
}

fun List<Note>.toggleNote(id: Long): List<Note> = map { if (it.id == id) it.copy(done = !it.done) else it }

fun List<Note>.removeNote(id: Long): List<Note> = filterNot { it.id == id }
