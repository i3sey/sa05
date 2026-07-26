#!/usr/bin/env bash
# Reproducibly builds the bundled native executables for one ABI.
#
# Usage: build-native.sh [abi]      (abi: arm64-v8a | x86_64, default arm64-v8a)
#
# arm64-v8a is what ships. x86_64 exists so the dev build runs on an emulator; it is never
# packaged into a release.
set -euo pipefail

readonly NDK_VERSION=29.0.14206865
readonly GO_VERSION_PREFIX=go1.26.4
readonly ANDROID_API=24
readonly XRAY_COMMIT=d2758a023cd7f4174a5a5fa4ff66e487d4342ba0
readonly SHADOWSOCKS_COMMIT=ae28fd91931fe4d2d5aab044de9ceaf9ed07ad56
readonly BADVPN_COMMIT=2814301fbd7e1838634580a18a34f8b68587f931
readonly LIBANCILLARY_COMMIT=232d69a5ebb4461b572bd3f3b97088091e01c243
readonly BYEDPI_COMMIT=7efde1b1296eaaa187b70e951894dde17527489c
readonly TG_ARCHIVE_SHA256=328409ea4dfcbc50eb3b9dbc24dec9535442a69d926b91e8fb2578fb7f71abba

abi=${1:-arm64-v8a}
case "$abi" in
    arm64-v8a)
        go_arch=arm64
        clang_triple=aarch64-linux-android
        ;;
    x86_64)
        go_arch=amd64
        clang_triple=x86_64-linux-android
        ;;
    *)
        echo "Unsupported ABI: $abi (expected arm64-v8a or x86_64)" >&2
        exit 2
        ;;
esac

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
output_dir=${NATIVE_OUTPUT_DIR:-$repo_root/app/src/main/jniLibs/$abi}
tg_archive="$repo_root/third_party/tg-ws-proxy-android/tg-ws-proxy-android-1.2.0.tar.gz"

sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
ndk=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
if [[ -z "$ndk" && -n "$sdk_root" ]]; then
    ndk="$sdk_root/ndk/$NDK_VERSION"
fi
if [[ -z "$ndk" || ! -f "$ndk/source.properties" ]]; then
    echo "Android NDK $NDK_VERSION not found; set ANDROID_SDK_ROOT or ANDROID_NDK_HOME" >&2
    exit 2
fi
actual_ndk=$(sed -n 's/^Pkg\.Revision = //p' "$ndk/source.properties")
if [[ "$actual_ndk" != "$NDK_VERSION" ]]; then
    echo "Expected Android NDK $NDK_VERSION, found ${actual_ndk:-unknown}" >&2
    exit 2
fi
if [[ $(go env GOVERSION) != "$GO_VERSION_PREFIX"* ]]; then
    echo "Expected Go $GO_VERSION_PREFIX, found $(go env GOVERSION)" >&2
    exit 2
fi

readonly toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
readonly cc="$toolchain/bin/${clang_triple}${ANDROID_API}-clang"
readonly readelf="$toolchain/bin/llvm-readelf"
readonly nm="$toolchain/bin/llvm-nm"
readonly strip="$toolchain/bin/llvm-strip"
# 16 KB pages are required on arm64 and harmless elsewhere, so both ABIs use the same flags.
readonly page_ldflags="-Wl,-z,max-page-size=16384,-z,common-page-size=16384"
for tool in git go make tar python3 "$cc" "$readelf" "$nm" "$strip" "$ndk/ndk-build"; do
    command -v "$tool" >/dev/null || {
        echo "Required tool not found: $tool" >&2
        exit 2
    }
done

work_dir=${NATIVE_WORK_DIR:-}
cleanup_work_dir=false
if [[ -z "$work_dir" ]]; then
    work_dir=$(mktemp -d "${TMPDIR:-/tmp}/sa05-native.XXXXXX")
    cleanup_work_dir=true
else
    mkdir -p "$work_dir"
fi
if $cleanup_work_dir; then
    trap 'rm -rf "$work_dir"' EXIT
fi
staging="$work_dir/output"
mkdir -p "$staging"

clone_at() {
    local name=$1
    local url=$2
    local commit=$3
    local destination="$work_dir/$name"
    git init -q "$destination"
    git -C "$destination" remote add origin "$url"
    git -C "$destination" fetch -q --depth 1 origin "$commit"
    git -C "$destination" checkout -q --detach FETCH_HEAD
    [[ $(git -C "$destination" rev-parse HEAD) == "$commit" ]] || {
        echo "Source pin mismatch for $name" >&2
        exit 1
    }
}

echo "Fetching pinned native sources"
clone_at xray-core https://github.com/XTLS/Xray-core.git "$XRAY_COMMIT"
clone_at shadowsocks-android https://github.com/shadowsocks/shadowsocks-android.git "$SHADOWSOCKS_COMMIT"
clone_at byedpi https://github.com/hufrea/byedpi.git "$BYEDPI_COMMIT"

git -C "$work_dir/shadowsocks-android" submodule update -q --init --depth 1 \
    core/src/main/jni/badvpn \
    core/src/main/jni/libancillary
[[ $(git -C "$work_dir/shadowsocks-android/core/src/main/jni/badvpn" rev-parse HEAD) == "$BADVPN_COMMIT" ]]
[[ $(git -C "$work_dir/shadowsocks-android/core/src/main/jni/libancillary" rev-parse HEAD) == "$LIBANCILLARY_COMMIT" ]]

echo "$TG_ARCHIVE_SHA256  $tg_archive" | sha256sum --check --status || {
    echo "TG WS Proxy source archive checksum mismatch" >&2
    exit 1
}
tar -xzf "$tg_archive" -C "$work_dir"

export GOOS=android
export GOARCH=$go_arch
export CGO_ENABLED=1
export CC="$cc"
export GOTOOLCHAIN=local
export GOFLAGS=-mod=readonly

# The Beeline short-session-ID patch has to go on before the build. Without it this script
# produced a stock upstream core and silently replaced the patched one in jniLibs, taking the
# XHTTP 403 fix with it — the exact regression XrayCore.SHA256 exists to catch.
echo "Applying the Beeline session-ID patch"
"$repo_root/ci/patch-xray.sh" "$work_dir/xray-core" "$repo_root/ci" >/dev/null
(
    cd "$work_dir/xray-core"
    go test ./transport/internet/splithttp/ -run TestNewBeelineSessionID -count=1 >/dev/null
) || {
    echo "Beeline session-ID patch did not take; refusing to ship an unpatched core" >&2
    exit 1
}

echo "Building Xray for $abi"
(
    cd "$work_dir/xray-core"
    # -checklinkname=0: the wlynxg/anet dependency reaches net.zoneCache through
    # //go:linkname, which Go 1.23+ rejects by default.
    go build \
        -buildvcs=false \
        -trimpath \
        -ldflags="-s -w -checklinkname=0 -linkmode=external -extldflags=$page_ldflags" \
        -o "$staging/libxray.so" \
        ./main
)

echo "Building tun2socks for $abi"
"$ndk/ndk-build" \
    -C "$work_dir/shadowsocks-android/core/src/main/jni" \
    -j"$(nproc)" \
    APP_ABI="$abi" \
    APP_PLATFORM=android-$ANDROID_API \
    APP_MODULES=tun2socks \
    APP_CFLAGS="-ffile-prefix-map=$work_dir/shadowsocks-android=/src/shadowsocks-android -fmacro-prefix-map=$work_dir/shadowsocks-android=/src/shadowsocks-android" \
    APP_LDFLAGS="$page_ldflags -Wl,--build-id=none" \
    NDK_DEBUG=0
tun2socks_output="$work_dir/shadowsocks-android/core/src/main/libs/$abi/libtun2socks.so"
if [[ ! -f "$tun2socks_output" ]]; then
    echo "tun2socks build produced no $abi executable" >&2
    exit 1
fi
cp "$tun2socks_output" "$staging/libtun2socks.so"

# Dynamically linked against bionic, not -static-pie. Static PIE executables segfault before
# reaching main on current Android: a bare printf() built that way dies the same way ByeDPI
# did, while the identical source linked dynamically runs. bionic ships on every device, so
# there is nothing to gain from static linking here.
echo "Building ByeDPI for $abi"
make -C "$work_dir/byedpi" \
    -j"$(nproc)" \
    CC="$cc" \
    CFLAGS="-I. -std=c99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter -pedantic -fPIE" \
    LDFLAGS="-pie $page_ldflags"
cp "$work_dir/byedpi/ciadpi" "$staging/libciadpi.so"
"$strip" --strip-all "$staging/libciadpi.so"

echo "Building TG WS Proxy for $abi"
(
    cd "$work_dir/tg-ws-proxy-android-1.2.0"
    go build \
        -buildmode=c-shared \
        -buildvcs=false \
        -trimpath \
        -ldflags="-s -w -linkmode=external -extldflags=$page_ldflags" \
        -o "$staging/libtgwsproxy.so" \
        ./tg-ws-proxy.go
)

echo "Validating native outputs"
python3 "$repo_root/scripts/check-elf-16kb.py" --abis "$abi" "$staging"/*.so

for option in --tunfd --sock-path --socks-server-addr; do
    strings "$staging/libtun2socks.so" | grep -F -- "$option" >/dev/null || {
        echo "libtun2socks.so does not expose required option $option" >&2
        exit 1
    }
done
for symbol in StartProxy StopProxy SetPoolSize SetCfProxyCacheDir SetCfProxyConfig GetStats FreeString; do
    "$nm" -D --defined-only "$staging/libtgwsproxy.so" | grep -E "[[:space:]]$symbol$" >/dev/null || {
        echo "libtgwsproxy.so does not export $symbol" >&2
        exit 1
    }
done
go version -m "$staging/libxray.so" | grep -F $'path\tgithub.com/xtls/xray-core/main' >/dev/null || {
    echo "libxray.so was not built from the expected Go package" >&2
    exit 1
}
# The opposite of what this used to assert. A missing INTERP means the binary was linked
# static-pie again, which loads on the build host and segfaults on the device.
if ! "$readelf" -lW "$staging/libciadpi.so" | grep INTERP >/dev/null; then
    echo "libciadpi.so must be a dynamically linked PIE; static PIE crashes on Android" >&2
    exit 1
fi
if ! "$readelf" -hW "$staging/libciadpi.so" | grep -q "DYN"; then
    echo "libciadpi.so must stay position independent" >&2
    exit 1
fi

mkdir -p "$output_dir"
for library in libxray.so libtun2socks.so libciadpi.so; do
    install -m 0755 "$staging/$library" "$output_dir/$library"
done
install -m 0644 "$staging/libtgwsproxy.so" "$output_dir/libtgwsproxy.so"

echo "Installed validated $abi libraries in $output_dir"
sha256sum "$output_dir"/libxray.so \
    "$output_dir"/libtun2socks.so \
    "$output_dir"/libciadpi.so \
    "$output_dir"/libtgwsproxy.so
