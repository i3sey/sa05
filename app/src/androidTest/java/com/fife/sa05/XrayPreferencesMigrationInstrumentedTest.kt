package com.fife.sa05

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class XrayPreferencesMigrationInstrumentedTest {
    @Test
    fun legacySharedPreferencesAreMigrated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacyName = "xray-migration-${System.nanoTime()}"
        val legacyPreferences = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        check(
            legacyPreferences.edit()
                .putString("vpn_backend", VpnBackend.PROXY_ONLY.name)
                .putString("subscription", """{"url":"https://example.com/sub"}""")
                .putStringSet("excluded", setOf("org.example.browser"))
                .commit()
        )
        val dataStoreFile = File(context.cacheDir, "$legacyName.preferences_pb")
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            migrations = XrayPreferences.migrations(context, legacyName),
            scope = dataStoreScope,
            produceFile = { dataStoreFile }
        )

        try {
            val settings = XrayPreferences.decodeSettings(dataStore.data.first())
            assertEquals(VpnBackend.PROXY_ONLY, settings.vpnBackend)
            assertEquals("https://example.com/sub", settings.subscription.url)
            assertEquals(setOf("org.example.browser"), settings.excludedApps)
        } finally {
            dataStoreScope.cancel()
            legacyPreferences.edit().clear().commit()
            dataStoreFile.delete()
        }
    }
}
