package com.fife.sa05

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnRuntimeContractInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun vpnServiceIsDeclaredForAndroidVpnBinding() {
        val serviceInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getServiceInfo(
                ComponentName(context, XrayVpnService::class.java),
                PackageManager.ComponentInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getServiceInfo(
                ComponentName(context, XrayVpnService::class.java),
                0
            )
        }

        assertEquals(Manifest.permission.BIND_VPN_SERVICE, serviceInfo.permission)
        assertFalse(serviceInfo.exported)
    }

    @Test
    fun requiredVpnRuntimeFilesArePackaged() {
        val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
        listOf(
            "libxray.so",
            "libtun2socks.so",
            "libciadpi.so",
            "libtgwsproxy.so"
        ).forEach { library ->
            assertTrue("Missing $library", File(nativeLibraryDir, library).isFile)
        }

        listOf("geoip.dat", "geosite.dat").forEach { asset ->
            context.assets.open(asset).use { input ->
                assertTrue("Empty $asset", input.read() >= 0)
            }
        }
    }
}
