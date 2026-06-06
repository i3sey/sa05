package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionDeepLinkTest {
    @Test
    fun parsesEncodedHttpsSubscription() {
        assertEquals(
            "https://sub.sa05.tech/token/json",
            SubscriptionDeepLink.parse(
                "sa05://add/https%3A%2F%2Fsub.sa05.tech%2Ftoken%2Fjson"
            )
        )
    }

    @Test
    fun preservesEncodedQueryAndPlus() {
        assertEquals(
            "https://example.com/sub?token=a+b&mode=json",
            SubscriptionDeepLink.parse(
                "sa05://add/https%3A%2F%2Fexample.com%2Fsub%3Ftoken%3Da%2Bb%26mode%3Djson"
            )
        )
    }

    @Test
    fun rejectsWrongOuterSchemeOrHost() {
        assertNull(
            SubscriptionDeepLink.parse(
                "https://add/https%3A%2F%2Fexample.com%2Fsub"
            )
        )
        assertNull(
            SubscriptionDeepLink.parse(
                "sa05://import/https%3A%2F%2Fexample.com%2Fsub"
            )
        )
    }

    @Test
    fun rejectsNonHttpsSubscription() {
        assertNull(
            SubscriptionDeepLink.parse(
                "sa05://add/http%3A%2F%2Fexample.com%2Fsub"
            )
        )
    }

    @Test
    fun rejectsMalformedPercentEncoding() {
        assertNull(SubscriptionDeepLink.parse("sa05://add/https%3A%2F%ZZ"))
    }
}
