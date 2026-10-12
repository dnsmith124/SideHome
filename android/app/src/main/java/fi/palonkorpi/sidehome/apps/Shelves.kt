package fi.palonkorpi.sidehome.apps

/**
 * The two shelves an app can be put on instead of the main library (Pixel Edition):
 *  - [HIDDEN] — out of the list, the search and the bubbles, but one tap away at the foot of the
 *    library for anyone who knows to look.
 *  - [LOCKED] — the same, behind the phone's own screen lock: the shelf opens only after Android
 *    has confirmed the device credential.
 * An app is on at most one shelf; putting it on the other moves it. A shelved app is never on the
 * home — shelving prunes it there, exactly as an uninstall would.
 */
enum class Shelf(val label: String, val glyph: String) {
    HIDDEN("Hidden", "archive"),
    LOCKED("Locked", "lock"),
}

/** Which app keys sit on which shelf. Pure and persistable as two string sets. */
data class Shelves(val hidden: Set<String> = emptySet(), val locked: Set<String> = emptySet()) {

    fun shelfOf(key: String): Shelf? = when {
        locked.contains(key) -> Shelf.LOCKED
        hidden.contains(key) -> Shelf.HIDDEN
        else -> null
    }

    fun put(key: String, shelf: Shelf): Shelves = when (shelf) {
        Shelf.HIDDEN -> copy(hidden = hidden + key, locked = locked - key)
        Shelf.LOCKED -> copy(hidden = hidden - key, locked = locked + key)
    }

    fun release(key: String): Shelves = copy(hidden = hidden - key, locked = locked - key)

    val isEmpty: Boolean get() = hidden.isEmpty() && locked.isEmpty()
}

/** The library split three ways. Each list keeps the repository's alphabetical order. */
data class ShelvedApps(val visible: List<AppEntry>, val hidden: List<AppEntry>, val locked: List<AppEntry>)

/** Split the installed apps by shelf. A shelved key whose app is gone simply matches nothing. */
fun List<AppEntry>.shelve(shelves: Shelves): ShelvedApps {
    val visible = ArrayList<AppEntry>(size)
    val hidden = ArrayList<AppEntry>()
    val locked = ArrayList<AppEntry>()
    for (app in this) {
        when (shelves.shelfOf(app.key)) {
            null -> visible.add(app)
            Shelf.HIDDEN -> hidden.add(app)
            Shelf.LOCKED -> locked.add(app)
        }
    }
    return ShelvedApps(visible, hidden, locked)
}

/** The keys the home may hold: installed and on no shelf. */
fun List<AppEntry>.unshelvedKeys(shelves: Shelves): Set<String> =
    mapNotNullTo(HashSet()) { app -> app.key.takeIf { shelves.shelfOf(it) == null } }
