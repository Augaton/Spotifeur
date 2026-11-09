package fr.spotifeur.update

import org.junit.Assert.*
import org.junit.Test

class UpdateReminderTest {
    private val dismissedAt = 1_800_000_000_000L

    @Test fun firstOfferAndNewerReleaseAreImmediate() {
        assertTrue(UpdateReminder.isDue("v1.2.4", null, 0, dismissedAt))
        assertTrue(UpdateReminder.isDue("v1.2.5", "v1.2.4", dismissedAt, dismissedAt + 1))
    }

    @Test fun sameReleaseStaysDeferredUntilExactlyTwentyFourHours() {
        assertFalse(UpdateReminder.isDue("v1.2.4", "v1.2.4", dismissedAt, dismissedAt))
        assertFalse(UpdateReminder.isDue("1.2.4", "v1.2.4", dismissedAt, dismissedAt + UpdateReminder.DELAY_MS - 1))
        assertTrue(UpdateReminder.isDue("v1.2.4", "1.2.4", dismissedAt, dismissedAt + UpdateReminder.DELAY_MS))
        assertTrue(UpdateReminder.isDue("v1.2.4", "v1.2.4", dismissedAt, dismissedAt + 2 * UpdateReminder.DELAY_MS))
    }

    @Test fun clockCorrectionAndInvalidStoredTimestampDoNotBlockReminders() {
        assertTrue(UpdateReminder.isDue("v1.2.4", "v1.2.4", dismissedAt, dismissedAt - 1))
        assertTrue(UpdateReminder.isDue("v1.2.4", "v1.2.4", 0, dismissedAt))
    }
}
