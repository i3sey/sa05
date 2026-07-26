package com.fife.sa05

/**
 * Everything a shared diagnostic report may reveal is user-identifying or provider-identifying,
 * so redaction is the point of this file, not a decoration on it.
 *
 * The report is meant to be pasted into a chat with whoever is helping. That means it must not
 * carry the subscription URL (it embeds an access token), profile UUIDs, the Telegram Proxy
 * secret, or the provider's server addresses — leaking those hands over the account and helps
 * map the provider's infrastructure.
 */
internal object ReportRedaction {
    private val uuid = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )
    private val hexSecret = Regex("\\b[0-9a-fA-F]{32,}\\b")
    private val url = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\s"'<>]+""")
    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val ipv6 = Regex("""\b(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\b""")
    private val email = Regex("""\b[\w.+-]+@[\w-]+\.[\w.-]+\b""")

    /**
     * Loopback and the fixed TUN addresses are the app's own plumbing and carry no information
     * about the user, so keeping them readable is what makes a report useful at all.
     */
    private val keptAddresses = setOf(
        "127.0.0.1",
        "0.0.0.0",
        "255.255.255.252",
        TUN_IPV4_ADDRESS,
        "10.10.10.2",
        "1.1.1.1"
    )

    fun redact(text: String, extraSecrets: Collection<String> = emptyList()): String {
        var result = text
        // Caller-supplied literals first: they are the most specific and may contain
        // characters the generic patterns would only partly match.
        extraSecrets
            .map(String::trim)
            .filter { it.length >= MIN_SECRET_LENGTH }
            .sortedByDescending(String::length)
            .forEach { secret -> result = result.replace(secret, "<removed>") }
        result = url.replace(result) { "<url removed>" }
        result = email.replace(result) { "<email removed>" }
        result = uuid.replace(result) { "<uuid removed>" }
        result = hexSecret.replace(result) { "<secret removed>" }
        result = ipv4.replace(result) { match ->
            if (match.value in keptAddresses) match.value else "<ip removed>"
        }
        result = ipv6.replace(result) { match ->
            if (match.value in keptAddresses) match.value else "<ip removed>"
        }
        return result
    }

    private const val MIN_SECRET_LENGTH = 4
}

internal data class DiagnosticReportInput(
    val appVersionName: String,
    val appVersionCode: Int,
    val androidRelease: String,
    val androidSdk: Int,
    val device: String,
    val xrayVersion: String,
    val backend: VpnBackend,
    val status: VpnRunStatus,
    val failureKind: VpnFailureKind,
    val statusMessage: String,
    val networkType: VpnNetworkType,
    val components: List<VpnComponentSnapshot>,
    val zapretPreset: ZapretPreset,
    val allowIpv6Bypass: Boolean,
    val advancedModeEnabled: Boolean,
    val excludedAppCount: Int,
    val profileCount: Int,
    val results: List<DiagnosticResult>,
    val logLines: List<String>,
    /** Literal values that must never appear, e.g. the subscription URL and profile ids. */
    val secrets: Collection<String> = emptyList()
)

/**
 * Builds the shareable report. Pure so the redaction can be tested without Android:
  * a regression here leaks credentials, and that is not something to discover in the field.
 */
internal fun buildDiagnosticReport(input: DiagnosticReportInput): String {
    val body = buildString {
        appendLine("SA05 diagnostic report")
        appendLine("======================")
        appendLine()
        appendLine("App:        ${input.appVersionName} (${input.appVersionCode})")
        appendLine("Xray:       ${input.xrayVersion}")
        appendLine("Android:    ${input.androidRelease} (SDK ${input.androidSdk})")
        appendLine("Device:     ${input.device}")
        appendLine()
        appendLine("Mode:       ${input.backend.title}")
        appendLine("Status:     ${input.status}")
        if (input.failureKind != VpnFailureKind.NONE) {
            appendLine("Failure:    ${input.failureKind}")
        }
        if (input.statusMessage.isNotBlank()) {
            appendLine("Message:    ${input.statusMessage}")
        }
        appendLine("Network:    ${input.networkType.title}")
        appendLine("ByeDPI:     ${input.zapretPreset.title}")
        appendLine("IPv6:       ${if (input.allowIpv6Bypass) "passed outside VPN" else "blackholed"}")
        appendLine("Advanced:   ${input.advancedModeEnabled}")
        appendLine("Profiles:   ${input.profileCount}")
        appendLine("Excluded:   ${input.excludedAppCount} app(s)")
        appendLine()
        appendLine("Components")
        appendLine("----------")
        if (input.components.isEmpty()) {
            appendLine("(none reported)")
        } else {
            input.components.forEach { appendLine("${it.component.title}: ${it.state}") }
        }
        appendLine()
        appendLine("Probes")
        appendLine("------")
        if (input.results.isEmpty()) {
            appendLine("(not run)")
        } else {
            input.results.forEach { result ->
                val detail = listOfNotNull(
                    result.statusCode?.toString(),
                    result.delayMs?.let { "${it}ms" },
                    "${result.bodyBytes}B".takeIf { result.bodyBytes > 0 },
                    result.error.takeIf(String::isNotBlank)
                ).joinToString(" · ")
                appendLine("${result.target.label}: ${result.status}${if (detail.isBlank()) "" else " ($detail)"}")
            }
        }
        appendLine()
        appendLine("Process log (last ${input.logLines.size} lines)")
        appendLine("-----------")
        if (input.logLines.isEmpty()) {
            appendLine("(empty)")
        } else {
            input.logLines.forEach(::appendLine)
        }
    }
    return ReportRedaction.redact(body, input.secrets)
}
