package com.fife.sa05

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes the redacted report to a file the user can hand to whoever is helping them.
 *
 * The file lives in the cache directory and is overwritten each time: a stale report from a
 * previous session is worse than none, and nothing here is worth keeping around.
 */
internal object DiagnosticReportSharing {
    private const val DIRECTORY = "reports"
    private const val FILE_NAME = "sa05-diagnostics.txt"

    fun collect(
        context: Context,
        settings: XraySettings,
        results: List<DiagnosticResult>
    ): String {
        val runtime = VpnRuntimeState.read(context)
        val subscription = settings.subscription
        return buildDiagnosticReport(
            DiagnosticReportInput(
                appVersionName = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                androidSdk = Build.VERSION.SDK_INT,
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                xrayVersion = XrayCore.displayVersion,
                backend = runtime.backend,
                status = runtime.status,
                failureKind = runtime.failureKind,
                statusMessage = runtime.message,
                networkType = runtime.networkType,
                components = runtime.components,
                zapretPreset = settings.zapretPreset,
                allowIpv6Bypass = settings.allowIpv6Bypass,
                advancedModeEnabled = settings.advancedModeEnabled,
                excludedAppCount = settings.excludedApps.size,
                profileCount = subscription.profiles.size,
                results = results,
                logLines = DiagnosticLog.snapshot(),
                secrets = buildList {
                    add(subscription.url)
                    add(settings.telegramSecret)
                    add(settings.telegramCfDomain)
                    subscription.profiles.forEach {
                        add(it.id)
                        add(it.remarks)
                    }
                }.filter(String::isNotBlank)
            )
        )
    }

    fun write(context: Context, report: String): File {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        return File(directory, FILE_NAME).apply { writeText(report) }
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SA05 diagnostics")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Отправить отчёт"
        )
    }
}
