package com.fife.sa05

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRefreshPolicyTest {
    private val profile = SubscriptionProfile(
        id = "profile",
        remarks = "Auto",
        json = "{}"
    )

    @Test
    fun `schedules only a valid cached subscription with a positive provider interval`() {
        assertEquals(
            6L,
            SubscriptionRefreshPolicy.intervalHours(
                SubscriptionState(
                    url = "https://example.com/subscription",
                    profiles = listOf(profile),
                    updateIntervalHours = 6
                )
            )
        )
        assertNull(
            SubscriptionRefreshPolicy.intervalHours(
                SubscriptionState(
                    url = "https://example.com/subscription",
                    profiles = listOf(profile),
                    updateIntervalHours = null
                )
            )
        )
        assertNull(
            SubscriptionRefreshPolicy.intervalHours(
                SubscriptionState(
                    url = "https://example.com/subscription",
                    profiles = listOf(profile),
                    updateIntervalHours = 0
                )
            )
        )
        assertNull(
            SubscriptionRefreshPolicy.intervalHours(
                SubscriptionState(
                    url = "",
                    profiles = listOf(profile),
                    updateIntervalHours = 6
                )
            )
        )
        assertNull(
            SubscriptionRefreshPolicy.intervalHours(
                SubscriptionState(
                    url = "https://example.com/subscription",
                    profiles = emptyList(),
                    updateIntervalHours = 6
                )
            )
        )
    }

    @Test
    fun `retries transport errors but leaves invalid responses until the next interval`() {
        assertTrue(SubscriptionRefreshPolicy.shouldRetry(IOException("offline")))
        assertFalse(
            SubscriptionRefreshPolicy.shouldRetry(
                IllegalArgumentException("Некорректный ответ")
            )
        )
    }
}
