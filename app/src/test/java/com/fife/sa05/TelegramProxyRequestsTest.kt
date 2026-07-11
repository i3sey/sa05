package com.fife.sa05

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramProxyRequestsTest {
    @Test
    fun `standalone telegram proxy does not depend on subscription authorization`() {
        val requests = TelegramProxyRequests().withStandalone(true)

        assertTrue(requests.required)
        assertFalse(SubscriptionAuth.isAuthorized(SubscriptionState()))
    }

    @Test
    fun `shared proxy remains required until every requester releases it`() {
        val shared = TelegramProxyRequests()
            .withStandalone(true)
            .withVpn(true)

        assertTrue(shared.required)
        assertTrue(shared.withVpn(false).required)
        assertTrue(shared.withStandalone(false).withVpn(true).required)
        assertFalse(shared.withStandalone(false).withVpn(false).required)
    }
}
