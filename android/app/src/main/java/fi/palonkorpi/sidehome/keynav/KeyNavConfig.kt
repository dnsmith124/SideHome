package fi.palonkorpi.sidehome.keynav

/**
 * The strong defaults for key-nav's taste calls (SID-97), gathered in one flip-ready place so a
 * future settings screen — or the debug toggles that precede it — can change behaviour without
 * touching a surface. These are per-view *policies*, deliberately NOT the keycode→intent map: which
 * key does what is the profile's job (SID-89), and never a user setting.
 *
 * The FINAL model (SID-81) leaves exactly these to be felt out on the SP-01: the A–Z List is
 * search-first (its field auto-focuses so the tile types straight into it), while the App Gallery is
 * browse-y (it arrives in D-pad mode, so its field must NOT steal focus). The highlight starts on the
 * first item on every surface (reveal-first). Whichever the thumb prefers becomes the shipped default;
 * the debug toggle then graduates to a real setting or is deleted.
 */
data class KeyNavConfig(
    /** A–Z List: focus the search field on open, so a hardware tile types into it with no tap. */
    val searchAutoFocusList: Boolean = true,
    /** App Gallery: the field does NOT auto-focus — the number keys drive a D-pad over the bubbles. */
    val searchAutoFocusGallery: Boolean = false,
    /** The rocking highlight lands on the first item, not the second (reveal-first). */
    val highlightFromFirst: Boolean = true,
) {
    companion object {
        val DEFAULT = KeyNavConfig()
    }
}

/**
 * The App Gallery's number-key D-pad (SID-96): in browse mode the number grid stops being direct-index
 * and becomes a cross — `2`↑ `8`↓ `4`← `6`→, `5` = select — over the bubble grid. Maps a 1-based
 * [KeyIntent.QuickIndex] number to the rocking intent it stands for, or null for the grid corners
 * (1/3/7/9) and any out-of-range number, which are inert here. The Sundial has no numbers, so it just
 * emits the ring's `Nav*`/`Select` directly and never passes through this.
 */
fun galleryDpadIntent(n: Int): KeyIntent? = when (n) {
    2 -> KeyIntent.NavUp
    8 -> KeyIntent.NavDown
    4 -> KeyIntent.NavLeft
    6 -> KeyIntent.NavRight
    5 -> KeyIntent.Select
    else -> null
}
