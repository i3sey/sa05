#!/usr/bin/env bash
#
# Build the Beeline-patched Xray core as the Android arm64 `libxray.so`.
#
# The patch (transport/internet/splithttp/dialer.go -> newBeelineSessionID)
# shortens the XHTTP session ID to a 12-char Base64URL string so Beeline CDN
# stops answering the request with HTTP 403. See XRAY_CLIENT_BEELINE_INTEGRATION.md.
#
# The produced binary is a PIE executable renamed to libxray.so and shipped in
# jniLibs; XrayVpnService runs it as `libxray.so run -config config.json`.
#
# Usage: scripts/build-xray-core.sh
# Env overrides: XRAY_FORK, ANDROID_NDK, ANDROID_API
set -euo pipefail

XRAY_FORK="${XRAY_FORK:-/home/sa05/xraySandbox/xray-core-beeline}"
ANDROID_NDK="${ANDROID_NDK:-/home/sa05/Android/Sdk/ndk/29.0.14206865}"
ANDROID_API="${ANDROID_API:-24}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a/libxray.so"

CC="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${ANDROID_API}-clang"
[ -x "$CC" ] || { echo "NDK clang not found: $CC" >&2; exit 1; }
[ -d "$XRAY_FORK" ] || { echo "Xray fork not found: $XRAY_FORK" >&2; exit 1; }

echo ">> Verifying short session-ID patch (§10.4/§10.9)"
( cd "$XRAY_FORK" && go test ./transport/internet/splithttp/ -run TestNewBeelineSessionID -count=1 )

echo ">> Cross-compiling android/arm64 core"
(
  cd "$XRAY_FORK"
  # -checklinkname=0: the wlynxg/anet dep (Android network interfaces) reaches
  # net.zoneCache via //go:linkname, which Go 1.23+ rejects by default.
  # 16 KB page alignment (Android 15+): the external linker must set
  # max/common-page-size to 16384, else PT_LOAD stays at 4 KB and the APK fails
  # scripts/check-16kb-compat.sh.
  PAGE='-Wl,-z,max-page-size=16384,-z,common-page-size=16384'
  GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC="$CC" \
    go build -trimpath -buildmode=pie \
      -ldflags="-s -w -checklinkname=0 -linkmode=external -extldflags=$PAGE" \
      -o "$OUT" ./main
)

echo ">> Result"
file "$OUT"
SHA="$(sha256sum "$OUT" | awk '{print $1}')"
echo "SHA-256: $SHA"
echo
echo "Update app/src/main/java/com/fife/sa05/XrayCore.kt SHA256 to:"
echo "    $SHA"
