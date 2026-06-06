package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRepositoryTest {
    private fun profile(remarks: String? = null, address: String = "one.example"): String {
        val remarksField = remarks?.let { ""","remarks":"$it"""" }.orEmpty()
        return """
            {
              "inbounds":[{
                "listen":"127.0.0.1",
                "port":10808,
                "protocol":"socks",
                "settings":{"udp":true}
              }],
              "outbounds":[{
                "tag":"proxy",
                "protocol":"vless",
                "settings":{"vnext":[{"address":"$address","port":443}]}
              }]
              $remarksField
            }
        """.trimIndent()
    }

    @Test
    fun parsesProfileArrayAndRemarks() {
        val profiles = SubscriptionRepository.parseProfiles(
            "[${profile("Германия")},${profile(null, "two.example")}]"
        )

        assertEquals(2, profiles.size)
        assertEquals("Германия", profiles[0].remarks)
        assertEquals("Профиль 2", profiles[1].remarks)
        assertNotEquals(profiles[0].id, profiles[1].id)
    }

    @Test
    fun profileIdIsStableForSameJson() {
        val body = "[${profile("Авто")}]"

        assertEquals(
            SubscriptionRepository.parseProfiles(body).single().id,
            SubscriptionRepository.parseProfiles(body).single().id
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyArray() {
        SubscriptionRepository.parseProfiles("[]")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonArrayResponse() {
        SubscriptionRepository.parseProfiles("""{"outbounds":[]}""")
    }

    @Test
    fun parsesOnlyBypassPackageNames() {
        val result = SubscriptionRepository.parseBypassHeader(
            "bypass",
            "com.example.app, ru.test.app, invalid package"
        )

        assertEquals(setOf("com.example.app", "ru.test.app"), result)
        assertTrue(
            SubscriptionRepository.parseBypassHeader("proxy", "com.example.app").isEmpty()
        )
    }
}
