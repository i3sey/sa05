#!/usr/bin/env bash
# Fails when R8 stripped or renamed something that is only reached by reflection.
#
# JNA binds native symbols by the method name of the mapped interface, so a missing name here
# means TG WS Proxy dies at runtime with an UnsatisfiedLinkError. Unit tests cannot catch it:
# they run against unminified classes.
set -euo pipefail

apk="${1:?usage: check-r8-keeps.sh <release.apk>}"

if [[ ! -f "$apk" ]]; then
    echo "missing APK: $apk" >&2
    exit 1
fi

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

unzip -o -q "$apk" 'classes*.dex' -d "$workdir"

symbols=(
    StartProxy
    StopProxy
    SetPoolSize
    SetCfProxyCacheDir
    SetCfProxyConfig
    GetStats
    FreeString
)

status=0
pool="$workdir/strings.txt"
cat "$workdir"/classes*.dex | strings >"$pool"

for symbol in "${symbols[@]}"; do
    # Word-boundary match: dex pools these next to neighbouring entries, so an anchored
    # line match gives false negatives.
    if grep -qw "$symbol" "$pool"; then
        echo "ok      $symbol"
    else
        echo "MISSING $symbol" >&2
        status=1
    fi
done

if ! grep -q "SubscriptionRefreshWorker" "$pool"; then
    echo "MISSING SubscriptionRefreshWorker" >&2
    status=1
else
    echo "ok      SubscriptionRefreshWorker"
fi

exit "$status"
