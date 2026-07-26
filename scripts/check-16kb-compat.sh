#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "Usage: $0 <apk> [abis]" >&2
    echo "  abis: comma-separated, default arm64-v8a. The dev APK also carries x86_64." >&2
    exit 2
fi

apk=$1
abis=${2:-arm64-v8a}
if [[ ! -f "$apk" ]]; then
    echo "APK not found: $apk" >&2
    exit 2
fi

find_zipalign() {
    if [[ -n "${ZIPALIGN:-}" && -x "$ZIPALIGN" ]]; then
        printf '%s\n' "$ZIPALIGN"
        return
    fi

    local sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
    if [[ -z "$sdk_root" && -f local.properties ]]; then
        sdk_root=$(sed -n 's/^sdk\.dir=//p' local.properties | tail -n 1)
        sdk_root=${sdk_root//\\:/:}
        sdk_root=${sdk_root//\\\\/\\}
    fi
    if [[ -n "$sdk_root" && -d "$sdk_root/build-tools" ]]; then
        find "$sdk_root/build-tools" -name zipalign -type f -perm -u+x -print \
            | sort -V \
            | tail -n 1
    fi
}

zipalign_bin=$(find_zipalign)
if [[ -z "$zipalign_bin" ]]; then
    echo "zipalign not found; set ANDROID_SDK_ROOT, ANDROID_HOME, or ZIPALIGN" >&2
    exit 2
fi

zipalign_log=$(mktemp)
trap 'rm -f "$zipalign_log"' EXIT
if ! "$zipalign_bin" -c -P 16 -v 4 "$apk" >"$zipalign_log" 2>&1; then
    cat "$zipalign_log" >&2
    echo "FAIL: APK entries are not 16 KB ZIP-aligned" >&2
    exit 1
fi

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
python3 "$script_dir/check-elf-16kb.py" --apk "$apk" --abis "$abis"
echo "PASS $apk: ZIP alignment and all $abis ELF/RELRO checks passed"
