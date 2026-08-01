package fi.palonkorpi.sidehome.apps

import android.content.Intent

/**
 * The pure half of §3's package lifecycle: which broadcasts mean the library moved under us, and
 * what a home page looks like once the apps it points at are gone.
 *
 * Compose-free and Context-free on purpose. The Android halves — the receiver in `MainActivity` and
 * the enumeration in [AppRepository] — are the parts that can't be tested on this project, so they
 * are kept down to plumbing and every decision they make is asked of something here.
 */

// ---- what a package broadcast means ------------------------------------------------------------

/** What a `PACKAGE_ADDED` / `PACKAGE_REMOVED` broadcast should make the launcher do. */
enum class PackageChange {
    /** The library really changed: re-enumerate, and prune whatever the home lost. */
    RELOAD,

    /** Nothing the launcher can see has changed — leave the home alone. */
    IGNORE,
}

/**
 * Resolve one package broadcast. [replacing] is the intent's `EXTRA_REPLACING`.
 *
 * The whole reason this is a function and not an `if` in the receiver: **an app update fires
 * `PACKAGE_REMOVED` too**, with `EXTRA_REPLACING` set, a beat before the `PACKAGE_ADDED` that puts
 * it back. Taking that at face value would re-enumerate a library the app is momentarily absent from
 * and prune its home tile — so updating an app would silently cost you your shortcut to it, and
 * empty a folder it was the last member of, deleting the folder outright. A remove-for-replace is
 * therefore ignored: the package is not going anywhere, and the `PACKAGE_ADDED` on the far side
 * re-enumerates anyway.
 *
 * The asymmetry is deliberate. An *added*-for-replace still reloads, even though the key set can't
 * have changed by it: the update may carry a new label, and re-enumerating on a set that turns out
 * identical prunes nothing — this is the direction where being wrong is free. Being wrong in the
 * other direction deletes a folder.
 */
fun packageChangeSignal(action: String?, replacing: Boolean): PackageChange = when (action) {
    Intent.ACTION_PACKAGE_REMOVED -> if (replacing) PackageChange.IGNORE else PackageChange.RELOAD
    Intent.ACTION_PACKAGE_ADDED -> PackageChange.RELOAD
    else -> PackageChange.IGNORE
}

// ---- what the home becomes -------------------------------------------------------------------

/**
 * Drop every home item pointing at an app that is no longer installed — loose tiles and folder
 * members alike — and then any folder that emptied on the way ([dropEmptyFolders]).
 *
 * It prunes against the *installed set* rather than against the one package a broadcast named, and
 * that is the point: re-enumerating and asking "what is still there?" subsumes the broadcast case
 * and fixes the general one for free (an app uninstalled from system Settings, or while the launcher
 * was stopped, or by an adb pull the launcher never heard about). A prune keyed on the named package
 * would be a second, narrower answer to the same question and could only ever drift from this one.
 *
 * Auto-delete on empty is [dropEmptyFolders]'s, not re-derived here, so an uninstall and the strip's
 * Remove-from-folder cannot disagree about whether an empty folder is a thing worth a home slot
 * (SID-31). A folder merely *down* to one member is left standing, exactly as on the remove path.
 */
fun List<HomeItem>.pruneMissing(installedKeys: Set<String>): List<HomeItem> =
    mapNotNull { item ->
        when (item) {
            is AppItem -> item.takeIf { it.key in installedKeys }
            is FolderItem -> item.copy(appKeys = item.appKeys.filter { it in installedKeys })
        }
    }.dropEmptyFolders()
