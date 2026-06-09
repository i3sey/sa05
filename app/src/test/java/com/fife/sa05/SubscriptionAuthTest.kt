package com.fife.sa05

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionAuthTest {
    @Test
    fun emptySubscriptionIsNotAuthorized() {
        assertFalse(SubscriptionAuth.isAuthorized(SubscriptionState()))
    }

    @Test
    fun urlWithoutProfilesIsNotAuthorized() {
        assertFalse(
            SubscriptionAuth.isAuthorized(
                SubscriptionState(url = "https://example.com/sub.json")
            )
        )
    }

    @Test
    fun urlWithProfilesIsAuthorized() {
        assertTrue(
            SubscriptionAuth.isAuthorized(
                SubscriptionState(
                    url = "https://example.com/sub.json",
                    profiles = listOf(
                        SubscriptionProfile(
                            id = "profile",
                            remarks = "Profile",
                            json = "{}"
                        )
                    )
                )
            )
        )
    }
}
