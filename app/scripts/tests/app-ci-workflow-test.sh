#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
workflow="$repo_root/.github/workflows/app-ci.yml"

if [[ ! -f "$workflow" ]]; then
    echo "missing app CI workflow: $workflow" >&2
    exit 1
fi

grep -Eq '^  push:' "$workflow"
grep -Eq '^  pull_request:' "$workflow"
grep -F 'uses: actions/setup-java@v5' "$workflow" >/dev/null
grep -F 'java-version: "21"' "$workflow" >/dev/null
grep -F 'uses: gradle/actions/setup-gradle@v6' "$workflow" >/dev/null
grep -F 'cache-provider: basic' "$workflow" >/dev/null
grep -F 'run: ./gradlew lint testDebugUnitTest assembleDev' "$workflow" >/dev/null
grep -F 'run: python3 scripts/check-elf-16kb.py app/src/main/jniLibs/arm64-v8a/*.so' \
    "$workflow" >/dev/null
# A developer who built x86_64 locally gets a two-ABI dev APK, so the dev check must
# tolerate it. CI itself only ever sees arm64 — x86_64 is not tracked.
grep -F 'run: scripts/check-16kb-compat.sh app/build/outputs/apk/dev/app-dev.apk arm64-v8a,x86_64' \
    "$workflow" >/dev/null
grep -F 'run: app/scripts/check-repository-secrets.sh .' "$workflow" >/dev/null
grep -F 'run: app/scripts/tests/app-ci-workflow-test.sh' "$workflow" >/dev/null
# R8 runs on release only, so the minified build and its keep-rule check are the gate.
grep -F 'run: ./gradlew assembleRelease' "$workflow" >/dev/null
grep -F 'run: app/scripts/check-r8-keeps.sh app/build/outputs/apk/release/app-release.apk' \
    "$workflow" >/dev/null
grep -F 'run: scripts/check-16kb-compat.sh app/build/outputs/apk/release/app-release.apk' \
    "$workflow" >/dev/null
