package fi.palonkorpi.sidehome.notifications

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The launcher's badge source of truth (SID-45, SPEC.md §8): which packages have a notification
 * worth a dot right now.
 *
 * Process-wide, and a singleton rather than an injectable class like `AppRepository`, because the
 * *system* owns the lifecycle of the thing that fills it: [BadgeListenerService] is bound and
 * unbound by Android and never constructed by us, so there is no seam to hand a repository through.
 * Readers observe [badgedPackages]; the service is the only writer.
 *
 * Live and ephemeral — nothing here is persisted. The set is rebuilt from `getActiveNotifications()`
 * every time the service connects, so a stale dot can't outlive a reboot, a re-grant, or the
 * notification being dealt with while we weren't listening.
 */
object NotificationBadges {

    private val _badgedPackages = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Packages with at least one badge-worthy notification right now, keyed by **package name** —
     * notifications are per-package, not per-activity, so one entry lights every tile and folder
     * showing that app.
     *
     * Empty whenever notification access is ungranted: the OS then never binds the service, so
     * nothing ever publishes.
     */
    val badgedPackages: StateFlow<Set<String>> = _badgedPackages.asStateFlow()

    /**
     * Publish a freshly-rebuilt set. Internal — [BadgeListenerService] is the only writer, and it
     * always passes the whole set rather than a delta (see its `refresh`).
     */
    internal fun publish(packages: Set<String>) {
        _badgedPackages.value = packages
    }

    /**
     * Whether the user has granted this app notification access. Ungranted means the OS never binds
     * [BadgeListenerService] and [badgedPackages] stays empty — so a caller that wants to explain
     * *why* there are no dots, or send the user to the grant screen (the settings row, SID-47),
     * asks this rather than inferring it from an empty set.
     */
    fun isAccessGranted(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
}
