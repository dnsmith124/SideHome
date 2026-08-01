package fi.palonkorpi.sidehome.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one pure decision in the notification-dots row (SID-47): grant state → what the row says.
 * The rest of that row is framework glue (an intent to a system page, a lifecycle re-read), so
 * this is a small suite on purpose rather than a thin one.
 *
 * Both strings are load-bearing promises, not decoration. `README.md` and
 * `docs/notification-dots-announcement.md` tell the reader the row "reads **On**" once access is
 * granted, and the "tap to enable" half is the row's only instruction — nothing else in the app
 * says how to turn dots on, because the decided posture is no nag and no first-run interstitial.
 * If the wording drifts from the docs, one of the two is now lying to the user.
 */
class NotificationAccessTest {

    @Test fun `granted access reads On`() {
        assertEquals("On", notificationAccessStatus(granted = true))
    }

    @Test fun `ungranted access says it is off and how to fix that`() {
        // The row is the only in-app discovery path for the feature, so "Off" alone won't do:
        // it has to tell the user that tapping is what turns it on.
        assertEquals("Off — tap to enable", notificationAccessStatus(granted = false))
    }

    @Test fun `status is decided by the system grant alone`() {
        // Guards the decision, not the code: there is no app-side on/off preference, so the grant
        // is the only input. A future "granted but disabled" state would need a second argument
        // here — and that is exactly the two-state model SID-47 rejected.
        val states = listOf(true, false).map { notificationAccessStatus(it) }
        assertEquals(states.size, states.distinct().size)
    }
}
