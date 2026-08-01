package fi.palonkorpi.sidehome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import fi.palonkorpi.sidehome.apps.AppRepository
import fi.palonkorpi.sidehome.apps.PackageChange
import fi.palonkorpi.sidehome.apps.packageChangeSignal
import fi.palonkorpi.sidehome.settings.SettingsStore
import fi.palonkorpi.sidehome.ui.SideHomeApp

/**
 * Which signal a launcher-level trigger resolves to, given the covered flag it consumes. Pure so the
 * reveal-trigger table (SID-33 / SID-34) can be unit-tested without an Activity.
 */
enum class RevealSignal {
    /** The home was covered and is now back: replay the entrance cascade. */
    APP_REVEAL,

    /** A HOME press on the already-visible launcher: step the overlay stack back, no cascade. */
    HOME_RECALL,

    /** No edge — bump nothing. */
    NONE,
}

/**
 * A fresh MAIN/HOME intent is either a return-from-app reveal (we were covered) or a HOME press on
 * the live launcher. Every HOME press means one or the other, so this never resolves to NONE.
 */
fun homeIntentSignal(wasBackgrounded: Boolean): RevealSignal =
    if (wasBackgrounded) RevealSignal.APP_REVEAL else RevealSignal.HOME_RECALL

/**
 * ACTION_USER_PRESENT — the keyguard was just dismissed onto our window (SID-34). Always a reveal:
 * the same cascade as every other one, no wake-fade variant. It is not a HOME press, so it can never
 * step the overlay stack — that is what makes it different from [homeIntentSignal].
 *
 * Deliberately **not** gated on `wasBackgrounded`, and that is the whole subtlety. The keyguard does
 * not stop us: verified on SP01GE260600728, with the screen asleep MainActivity is still RESUMED, so
 * `onStop` never runs across a lock/unlock cycle and the flag is false the entire time. Gating on it
 * meant this resolved to NONE on every real unlock and the cascade never played — which is the same
 * fact that motivates the issue (we stay alive under the keyguard, so `onResume` can't drive this
 * either), read the wrong way round.
 *
 * What gates it instead is *registration*: the receiver lives only between `onStart` and `onStop`, so
 * a broadcast can only reach us while we are the started window — i.e. the one being unlocked onto.
 * Unlocking into some other app leaves us stopped and unregistered, and the later HOME back to us
 * takes the [homeIntentSignal] path on the flag that path does set.
 */
fun userPresentSignal(): RevealSignal = RevealSignal.APP_REVEAL

/**
 * The launcher. A single HOME Activity — no Launcher3 fork. Compose paints the whole
 * window (ambient background + home + drawer); the OS window is a transparent shell.
 */
class MainActivity : ComponentActivity() {

    // Bumped when the user presses HOME while the launcher is already foreground, so the UI can
    // step the overlay stack back toward the bare home (SID-33). One HOME press = full reset.
    private val homeRecall = mutableIntStateOf(0)

    // Bumped when the launcher is revealed after something had covered it — another app, or the
    // keyguard (SID-34). Drives the entrance cascade — the one honest "the home was hidden and is
    // now back" signal.
    private val appReveal = mutableIntStateOf(0)

    // Hardware key-navigation (SID-81). The composition installs a handler here — dispatchKeyEvent is
    // the first hook in Android's key path (the Activity is the Window.Callback, called before the view
    // hierarchy and the IME), which is exactly why consuming a key here can beat the predictive IME to
    // it (the double-Enter fix, SID-91). Null while nothing is installed, so key-nav simply passes
    // through until the Compose layer is up.
    @Volatile
    var keyEventHandler: ((KeyEvent) -> Boolean)? = null

    // Set in onStop(): the launcher went fully behind something (another app / recents / lock).
    // Consumed by the next reveal trigger so the cascade replays. We key off onStop, not onResume,
    // because onResume is flaky to key on for this on API 31: on unlock we were already resumed
    // *underneath* the keyguard, so it may never re-fire (SID-34).
    private var wasBackgrounded = false

    // Bumped when the installed set may have moved under us (SID-12): an app was installed or
    // uninstalled, here or anywhere else on the device. The UI re-enumerates and prunes whatever the
    // home lost. A counter rather than a flag for the same reason as the two above — it is an event,
    // and two uninstalls in a row must be two reloads, not one.
    private val packageChange = mutableIntStateOf(0)

    // Set in onStop(): the package receiver below is unregistered while we are stopped, so anything
    // installed or uninstalled in that window happened unheard. Consumed by the next onStart, which
    // re-enumerates once to catch up. See the argument in onStart().
    private var packagesStale = false

    // SID-12's stale-entry pruning. PACKAGE_ADDED / PACKAGE_REMOVED, runtime-registered — the same
    // route USER_PRESENT takes below, and not really a choice here: these are implicit broadcasts
    // about other packages, which a manifest-declared receiver has not been served since API 26.
    // Runtime registration is the supported route, and it is the only one available to us anyway
    // (the manifest is not ours to edit).
    //
    // The data scheme is not optional either: PACKAGE_* carry a `package:` uri, and an IntentFilter
    // with no scheme matches no intent that has one — the filter would sit there looking correct and
    // receive nothing at all, which is the same silent failure as the export flag below and would
    // read identically from the outside.
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val replacing = intent?.getBooleanExtra(Intent.EXTRA_REPLACING, false) ?: false
            when (packageChangeSignal(intent?.action, replacing)) {
                PackageChange.RELOAD -> packageChange.intValue += 1
                PackageChange.IGNORE -> Unit
            }
        }
    }

    // The unlock reveal trigger (SID-34). ACTION_USER_PRESENT fires the instant the keyguard is
    // dismissed — when the user actually lands on home. Deliberately NOT ACTION_SCREEN_ON, which
    // fires while still locked: the cascade would play out behind the lockscreen and be gone by the
    // time the user got here. Runtime-registered only — USER_PRESENT is not deliverable to a
    // manifest receiver.
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_USER_PRESENT) return
            bump(userPresentSignal())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val repo = AppRepository(this)
        val settings = SettingsStore(this)
        setContent {
            SideHomeApp(
                repo = repo,
                settings = settings,
                homeRecall = homeRecall.intValue,
                appReveal = appReveal.intValue,
                packageChange = packageChange.intValue,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // Scoped to onStart/onStop, not onCreate/onDestroy, and that scope is load-bearing rather
        // than just tidy: while stopped we are not the window the user unlocks onto (they unlocked
        // into some other app), so that unlock is not our reveal — the later HOME back to us is.
        // Listening only while started gives us exactly that for free, and leaks nothing.
        //
        // EXPORTED, and it must be. The flag does not describe who is *allowed* to send: it decides
        // whether we accept broadcasts originating outside our own process at all, and the system is
        // outside. NOT_EXPORTED reads as the safe choice for a protected broadcast, but it makes the
        // OS synthesise a private DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION that the system process
        // does not hold, so every USER_PRESENT is silently dropped — verified in `dumpsys activity
        // broadcasts` on SP01GE260600728, which listed our filter under "Skipped ... requiredPermission".
        // Exporting costs nothing here precisely because USER_PRESENT is protected: only the system
        // may send it, so no other app can forge a reveal.
        ContextCompat.registerReceiver(
            this,
            userPresentReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_EXPORTED,
        )

        // Same onStart/onStop scope as the receiver above, but for a different reason, and the
        // reason matters because the requirement is the opposite one. USER_PRESENT *wants* to be
        // deaf while stopped — an unlock onto someone else's window is not our reveal. A package
        // uninstalled while we are stopped is still very much our problem: the home would come back
        // holding a tile that opens onto nothing.
        //
        // So the scope is not what makes this correct — the catch-up below is. Listening while
        // stopped would buy nothing anyway: the launcher can be killed outright while backgrounded,
        // so there is no registration window that could make live delivery sufficient, and a
        // re-enumeration we must do on the way back in regardless subsumes every broadcast we
        // missed. Given that, staying registered would only add wakeups to prune a home nobody is
        // looking at.
        //
        // EXPORTED, and it must be — the same trap USER_PRESENT already paid for above. NOT_EXPORTED
        // makes the OS synthesise a DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION the system process does
        // not hold, and every broadcast is dropped silently: no crash, no log, just a launcher that
        // never notices an uninstall. PACKAGE_ADDED / PACKAGE_REMOVED are protected broadcasts, so
        // only the system can send them and exporting forges nothing.
        ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_EXPORTED,
        )

        // Belt-and-suspenders (SPEC §3): catch up on whatever happened while we were deaf. Gated on
        // the flag rather than fired every onStart, because an unconditional bump would re-enumerate
        // every installed app on every single return to home — the most common thing that happens on
        // this device — to discover nothing changed. onStop is the honest edge: it is exactly when we
        // stopped listening.
        //
        // Note this cannot double up with the initial load: the first onStart follows onCreate, and
        // onStop has never run, so the flag is false and the UI's own first enumeration stands alone.
        // The keyguard can't trigger it spuriously either — locking does not stop us (SID-34), so a
        // lock/unlock cycle never sets the flag.
        if (packagesStale) {
            packagesStale = false
            packageChange.intValue += 1
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(userPresentReceiver)
        unregisterReceiver(packageReceiver)
        wasBackgrounded = true
        packagesStale = true
    }

    // Every hardware key passes here first. Offer it to the key-nav handler; if it claims the key
    // (returns true) it never reaches the view hierarchy or the IME, otherwise the default path runs
    // and the key types / acts as usual. A true no-op when nothing is installed or key-nav is off.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyEventHandler?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing HOME while the launcher is foreground delivers a fresh MAIN/HOME intent. If we
        // were backgrounded (an app was covering us), this is a return-from-app reveal → replay the
        // cascade. Otherwise it's a HOME press on the live launcher → step back toward bare home.
        bump(homeIntentSignal(wasBackgrounded))
    }

    /** Apply a resolved signal: a reveal consumes the covered flag and replays the cascade. */
    private fun bump(signal: RevealSignal) {
        when (signal) {
            RevealSignal.APP_REVEAL -> {
                wasBackgrounded = false
                appReveal.intValue += 1
            }
            RevealSignal.HOME_RECALL -> homeRecall.intValue += 1
            RevealSignal.NONE -> Unit
        }
    }
}
