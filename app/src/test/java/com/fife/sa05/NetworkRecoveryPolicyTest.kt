package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkRecoveryPolicyTest {
    @Test
    fun `network changes are classified before recovery starts`() {
        assertEquals(
            NetworkRecoveryDecision.NONE,
            NetworkRecoveryPolicy.networkChanged("wifi", "wifi")
        )
        assertEquals(
            NetworkRecoveryDecision.WAIT_FOR_NETWORK,
            NetworkRecoveryPolicy.networkChanged("wifi", null)
        )
        assertEquals(
            NetworkRecoveryDecision.VERIFY_ROUTE,
            NetworkRecoveryPolicy.networkChanged("wifi", "mobile")
        )
    }

    @Test
    fun `an unhealthy route reconnects only within the automatic retry budget`() {
        assertEquals(
            NetworkRecoveryDecision.NONE,
            NetworkRecoveryPolicy.routeChecked(healthy = true, automaticAttempts = 0)
        )
        assertEquals(
            NetworkRecoveryDecision.RECONNECT,
            NetworkRecoveryPolicy.routeChecked(healthy = false, automaticAttempts = 0)
        )
        assertEquals(
            NetworkRecoveryDecision.FAIL,
            NetworkRecoveryPolicy.routeChecked(
                healthy = false,
                automaticAttempts = NetworkRecoveryPolicy.MAX_AUTOMATIC_ATTEMPTS
            )
        )
    }
}
