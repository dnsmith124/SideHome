package fi.palonkorpi.sidehome.pane

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Notes page's list, on disk and in memory. Process-wide like the weather store, so the pane
 * and anything else that reads it see one list. Writes are tiny and synchronous: a note is a
 * sentence, and the list lives in the launcher's own prefs — never on the network.
 */
class NotesStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _notes = MutableStateFlow(decodeNotes(prefs.getString(KEY_NOTES, null)))
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    fun add(text: String) = write(_notes.value.addNote(text, id = System.currentTimeMillis()))
    fun toggle(id: Long) = write(_notes.value.toggleNote(id))
    fun remove(id: Long) = write(_notes.value.removeNote(id))

    private fun write(next: List<Note>) {
        _notes.value = next
        prefs.edit().putString(KEY_NOTES, encodeNotes(next)).apply()
    }

    companion object {
        private const val PREFS = "notes"
        private const val KEY_NOTES = "notes_v1"

        @Volatile
        private var instance: NotesStore? = null

        fun get(context: Context): NotesStore =
            instance ?: synchronized(this) { instance ?: NotesStore(context).also { instance = it } }
    }
}

@Composable
fun rememberNotesStore(): NotesStore {
    val context = LocalContext.current
    return remember(context.applicationContext) { NotesStore.get(context) }
}
