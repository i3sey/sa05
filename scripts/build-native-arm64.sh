#!/usr/bin/env bash
# Backwards-compatible entry point: builds the shipped arm64-v8a libraries.
# The ABI-parameterised implementation lives in build-native.sh.
set -euo pipefail
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/build-native.sh" arm64-v8a "$@"
