package fi.palonkorpi.sidehome.apps

/** What a folder is called and what it looks like — the two things a drag-combine has to invent. */
data class FolderIdentity(val name: String, val glyph: String)

/**
 * SPEC §1's smart guess for a folder born from a drag-combine: two apps that share a **confident**
 * category name the folder after that category and take its glyph; anything less falls back to
 * plain "Folder" and waits to be renamed.
 *
 * Confident means the pair agree on a bucket that is not Other. Other is [CategoryEngine]'s honest
 * shrug — an app lands there rather than in a wrong bucket — so two of them are not a match, they are
 * two apps nothing was known about, and "Other" is a worse folder name than "Folder". That is the
 * whole threshold: no scoring, no confidence number, because the
 * engine has already collapsed its four layers into one answer and the only bit it kept about how
 * sure it was is whether it reached OTHER.
 *
 * The asymmetry is deliberate. Guessing is free and a wrong guess is not: the name sits on the home
 * page until someone fixes it, and a folder called "Money" holding a music player is worse than one
 * called "Folder" — the first lies, the second only asks.
 */
fun folderIdentityFor(a: Category?, b: Category?): FolderIdentity =
    if (a != null && a == b && !a.isOther) {
        FolderIdentity(a.label, a.glyph)
    } else {
        FolderIdentity(DEFAULT_FOLDER_NAME, DEFAULT_FOLDER_GLYPH)
    }
