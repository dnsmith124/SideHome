package fi.palonkorpi.sidehome.notifications

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The meaningful-only rule (SID-45, SPEC.md §8). [isBadgeWorthy] takes plain values rather than a
 * `StatusBarNotification`, so the whole decision is testable here without Android — the platform
 * read it feeds on lives in `BadgeListenerService` and is deliberately trivial.
 *
 * `Notification.FLAG_*` are Java compile-time constants, so they inline; nothing below touches the
 * Android runtime.
 */
class BadgeFilterTest {

    // ---- the badge case ------------------------------------------------------------------------

    @Test fun plainDismissableNotificationBadges() {
        assertTrue(isBadgeWorthy(flags = 0, canShowBadge = true))
    }

    @Test fun unrelatedFlagsDoNotSuppress() {
        // the mask must be exact, not "has any flags": a typical dismissable message notification
        // carries auto-cancel and lights, and must still badge
        val flags = Notification.FLAG_AUTO_CANCEL or Notification.FLAG_SHOW_LIGHTS
        assertTrue(isBadgeWorthy(flags, canShowBadge = true))
    }

    // ---- not ongoing ---------------------------------------------------------------------------

    @Test fun ongoingEventDoesNotBadge() {
        // media playback, VPN, "app is running" — furniture, not news
        assertFalse(isBadgeWorthy(Notification.FLAG_ONGOING_EVENT, canShowBadge = true))
    }

    @Test fun noClearDoesNotBadge() {
        assertFalse(isBadgeWorthy(Notification.FLAG_NO_CLEAR, canShowBadge = true))
    }

    @Test fun ongoingAlongsideDismissableFlagsStillDoesNotBadge() {
        // suppression wins over any co-present benign flag
        val flags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_AUTO_CANCEL
        assertFalse(isBadgeWorthy(flags, canShowBadge = true))
    }

    // ---- not a group summary -------------------------------------------------------------------

    @Test fun groupSummaryDoesNotBadge() {
        // the children badge the package; counting the summary too would double-signal it
        assertFalse(isBadgeWorthy(Notification.FLAG_GROUP_SUMMARY, canShowBadge = true))
    }

    // ---- badge-eligible ------------------------------------------------------------------------

    @Test fun channelThatCannotBadgeDoesNotBadge() {
        // the user already told Android not to badge this channel; honour it rather than re-decide
        assertFalse(isBadgeWorthy(flags = 0, canShowBadge = false))
    }

    @Test fun canShowBadgeIsVetoNotOverride() {
        // it can only ever suppress — it never rescues an ongoing or summary notification
        assertFalse(isBadgeWorthy(Notification.FLAG_ONGOING_EVENT, canShowBadge = false))
        assertFalse(isBadgeWorthy(Notification.FLAG_GROUP_SUMMARY, canShowBadge = false))
    }

    @Test fun everySuppressorAtOnceDoesNotBadge() {
        val flags = Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_NO_CLEAR or
            Notification.FLAG_GROUP_SUMMARY
        assertFalse(isBadgeWorthy(flags, canShowBadge = false))
        assertFalse(isBadgeWorthy(flags, canShowBadge = true))
    }

    // ---- decided boundary ----------------------------------------------------------------------

    @Test fun foregroundServiceFlagAloneIsNotASuppressor() {
        // pins the decided rule: only ONGOING_EVENT / NO_CLEAR / GROUP_SUMMARY veto. A real
        // foreground-service notice is suppressed because it also carries FLAG_ONGOING_EVENT —
        // not because of this flag — so a dismissable notification from an app that happens to
        // run a service still badges.
        assertTrue(isBadgeWorthy(Notification.FLAG_FOREGROUND_SERVICE, canShowBadge = true))
        assertFalse(
            isBadgeWorthy(
                Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_ONGOING_EVENT,
                canShowBadge = true,
            )
        )
    }
}
