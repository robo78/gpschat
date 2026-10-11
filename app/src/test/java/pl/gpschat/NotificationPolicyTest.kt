package pl.gpschat

import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    @Test fun enteringNewPublicMessageNotifies() {
        assertTrue(NotificationPolicy.shouldNotify(false, 200, false, 100))
    }
    @Test fun previouslyDeliveredMessageDoesNotNotifyAgain() {
        assertFalse(NotificationPolicy.shouldNotify(false, 200, true, 100))
    }
    @Test fun authorDoesNotReceiveTheirOwnAlert() {
        assertFalse(NotificationPolicy.shouldNotify(true, 200, false, 100))
    }
    @Test fun expiredMessagesCannotNotifyEvenAtExactExpiry() {
        assertFalse(NotificationPolicy.shouldNotify(false, 100, false, 100))
        assertFalse(NotificationPolicy.shouldNotify(false, 99, false, 100))
    }
}
