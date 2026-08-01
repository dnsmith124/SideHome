package fi.palonkorpi.sidehome.notifications

import android.app.Notification

/**
 * Flags that disqualify a notification from badging, as one mask.
 *
 *  - [Notification.FLAG_ONGOING_EVENT] / [Notification.FLAG_NO_CLEAR] — media playback, VPN and
 *    foreground-service "running" notices. Furniture the user can't dismiss, not news.
 *  - [Notification.FLAG_GROUP_SUMMARY] — restates children a multi-thread app has already posted;
 *    counting both double-signals the package.
 */
private const val SUPPRESSING_FLAGS =
    Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR or Notification.FLAG_GROUP_SUMMARY

/**
 * The "meaningful-only" rule (SID-45, SPEC.md §8): does one notification earn its package a dot?
 *
 * Pure — it takes the two facts it needs as plain values rather than a `StatusBarNotification`, so
 * the decision is unit-testable without Android (the same seam as `formatClock`). The platform read
 * lives in [BadgeListenerService], which calls this.
 *
 * A notification badges iff it is **dismissable** (none of [SUPPRESSING_FLAGS]) *and*
 * **badge-eligible** ([canShowBadge], which carries the OS's own per-channel badge tuning — a
 * channel the user has already told Android not to badge stays quiet here for free).
 *
 * Net: a badged package has a real, dismissable notification the user hasn't dealt with. Flags
 * outside the mask (auto-cancel, lights, …) are irrelevant and must not affect the answer.
 *
 * @param flags a `Notification.flags` bitfield.
 * @param canShowBadge the effective badge setting for the notification's channel.
 */
fun isBadgeWorthy(flags: Int, canShowBadge: Boolean): Boolean =
    canShowBadge && (flags and SUPPRESSING_FLAGS) == 0
