package com.fife.sa05

/**
 * Metadata for the bundled, Beeline-patched Xray core (`jniLibs/<abi>/libxray.so`).
 *
 * The core carries a small patch in `transport/internet/splithttp/dialer.go`
 * that shortens the XHTTP session ID to a 12-char Base64URL string so Beeline
 * CDN stops answering XHTTP requests with HTTP 403. The patch touches only the
 * XHTTP dialer; every other protocol behaves exactly like upstream Xray.
 *
 * [SHA256] is filled in by `scripts/build-xray-core.sh` after each rebuild and
 * belongs in release metadata (§11): an app update must never silently replace
 * this binary with an official one, or the Beeline profile regresses to 403.
 */
object XrayCore {
    const val VERSION = "26.6.1"
    const val VARIANT = "beeline"
    const val SESSION_ID_FORMAT = "base64url/12"
    const val SHA256 = "af7187d9cceba9307d17e688baa87ae64ef87f53c9f69d6b39f7971a26eb6982"

    val displayVersion: String get() = "Xray $VERSION-$VARIANT"
}
