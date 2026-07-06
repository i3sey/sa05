#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
checker="$script_dir/../check-repository-secrets.sh"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT

git -C "$fixture" init --quiet
printf '%s\n' '# fixture' > "$fixture/README.md"
git -C "$fixture" add README.md

"$checker" "$fixture"

printf '%s\n' 'sdk.dir=/secret/android-sdk' > "$fixture/local.properties"
git -C "$fixture" add -f local.properties
if "$checker" "$fixture" 2>"$fixture/error.log"; then
    echo "expected tracked local.properties to be rejected" >&2
    exit 1
fi
grep -F 'local.properties' "$fixture/error.log" >/dev/null

git -C "$fixture" rm --cached --quiet local.properties
mkdir -p "$fixture/signing"
printf '%s\n' 'private key material' > "$fixture/signing/release.jks"
git -C "$fixture" add signing/release.jks
if "$checker" "$fixture" 2>"$fixture/error.log"; then
    echo "expected tracked release keystore to be rejected" >&2
    exit 1
fi
grep -F 'signing/release.jks' "$fixture/error.log" >/dev/null
