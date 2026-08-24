#!/usr/bin/env bash
#
# Build the yctun tunnel client (relayc) as the Android arm64 `librelayc.so`.
#
# The client implements a GET-only tunnel through the Yandex Cloud CDN:
#   app (SOCKS) -> relayc -> HTTPS GET dom.sa05.eu.cc -> relayd (VPS origin).
# See third_party/yctun/README.md and AGENTS.md (yctun section).
#
# The produced binary is a PIE executable renamed to librelayc.so and shipped
# in jniLibs; XrayVpnService runs it as
# `librelayc.so -config <filesDir>/yctun.json` before starting Xray.
#
# Usage: scripts/build-relayc-arm64.sh
# Env overrides: YCTUN_SOURCE (default third_party/yctun), ANDROID_NDK
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
YCTUN_SOURCE="${YCTUN_SOURCE:-$REPO_ROOT/third_party/yctun}"
OUT="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a/librelayc.so"
CHECK="$REPO_ROOT/scripts/check-elf-16kb.py"

[ -d "$YCTUN_SOURCE/cmd/relayc" ] || {
    echo "yctun source not found: $YCTUN_SOURCE (expected third_party/yctun)" >&2
    exit 1
}
command -v go >/dev/null || { echo "Go toolchain not found" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"

echo ">> Cross-compiling android/arm64 relayc"
(
  cd "$YCTUN_SOURCE"
  CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
    go build \
      -trimpath \
      -ldflags "-s -w" \
      -o "$OUT" \
      ./cmd/relayc
)

echo ">> Verifying 16 KB page compatibility (Android 15+)"
if python3 "$CHECK" "$OUT"; then
  echo "PASS $OUT"
else
  cat <<'EOF' >&2
FAIL: librelayc.so is not 16 KB-page compatible (Android 15+ may refuse to
load it). Rebuild with an NDK external linker, e.g.:

  ANDROID_NDK=$HOME/Android/Sdk/ndk/29.0.14206865
  CC=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang
  (cd third_party/yctun && \
    CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
    go build -trimpath \
      -ldflags "-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384" \
      -o "$OUT" ./cmd/relayc)
EOF
  exit 1
fi