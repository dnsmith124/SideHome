package fi.palonkorpi.sidehome.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification

/**
 * Bridges Android's notification stream into [NotificationBadges].
 *
 * A `NotificationListenerService` is the only AOSP mechanism for this — `LauncherApps` cannot see
 * notifications — and is the same path Launcher3 takes. The OS binds it only once the user has
 * granted notification access; until then this never runs and [NotificationBadges.badgedPackages]
 * stays empty. The `BIND_NOTIFICATION_LISTENER_SERVICE` permission in the manifest is what makes
 * that grant meaningful: only the system may bind us.
 */
class BadgeListenerService : NotificationListenerService() {

    /**
     * Reused scratch for ranking lookups: [RankingMap.getRanking] fills a caller-supplied [Ranking].
     * One allocation for the life of the service instead of one per notification per event — the
     * A22 doesn't need the garbage. Safe to share: callbacks are delivered on the main thread.
     */
    private val scratchRanking = Ranking()

    override fun onListenerConnected() {
        // Seed. The stream only reports *changes*, so notifications already up when we bound
        // would otherwise never badge. This is also what makes the set ephemeral-but-correct:
        // every connect rebuilds it from scratch, so nothing needs persisting.
        refresh(runCatching { currentRanking }.getOrNull())
    }

    override fun onListenerDisconnected() {
        // Access revoked, or the OS is tearing us down. Drop the dots rather than freeze them —
        // we can no longer tell whether they're still true.
        NotificationBadges.publish(emptySet())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        refresh(rankingMap)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        refresh(rankingMap)
    }

    /**
     * Rebuild the whole set from `activeNotifications` rather than adding or removing the one
     * package the event names.
     *
     * Badge-worthiness can flip *in place* — a media app updates a plain notification into an
     * ongoing one, a group summary arrives after its children, a channel's badge setting changes —
     * and per-event delta bookkeeping gets all three wrong. A package also needs its dot to survive
     * until its *last* badge-worthy notification is gone, which a delta can't know without keeping
     * a per-package count anyway. Notification events are rare (a handful a minute), so one binder
     * call each is well inside budget on the A22, and this is correct by construction.
     */
    private fun refresh(rankingMap: RankingMap?) {
        // Throws if the OS has already unbound us — a posted/removed callback can race a
        // disconnect. Nothing to publish then; the disconnect clears the set.
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val badged = HashSet<String>()
        for (sbn in active) {
            if (sbn == null) continue
            if (isBadgeWorthy(sbn.notification.flags, canShowBadge(sbn, rankingMap))) {
                badged.add(sbn.packageName)
            }
        }
        NotificationBadges.publish(badged)
    }

    /**
     * The badge setting for [sbn], read off the ranking the OS handed us. [Ranking.canShowBadge] is
     * the *effective* value — the channel's own `NotificationChannel.canShowBadge()` folded together
     * with the app-level badge toggle — so honouring it inherits the OS's per-channel tuning for
     * free, and avoids a second binder call to fetch the channel itself.
     *
     * Defaults to `true` when the ranking has no entry for this key: a missing ranking is a gap in
     * our information, not the user asking for silence, and suppressing a real notification is the
     * worse error.
     */
    private fun canShowBadge(sbn: StatusBarNotification, rankingMap: RankingMap?): Boolean {
        val found = rankingMap?.getRanking(sbn.key, scratchRanking) ?: false
        return if (found) scratchRanking.canShowBadge() else true
    }
}
