# Building libtgwsproxy.so

The bundled binary is built from the adjacent
`tg-ws-proxy-android-1.2.0.tar.gz` source archive at tag `v1.2.0`.

Prerequisites:

- Go 1.26
- Android NDK r29
- Linux x86_64 host

```bash
tar -xzf tg-ws-proxy-android-1.2.0.tar.gz
cd tg-ws-proxy-android-1.2.0

export NDK="$ANDROID_SDK_ROOT/ndk/29.0.14206865"
export GOOS=android
export GOARCH=arm64
export CGO_ENABLED=1
export CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"

go build \
  -buildmode=c-shared \
  -buildvcs=false \
  -trimpath \
  -ldflags="-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384" \
  -o libtgwsproxy.so \
  tg-ws-proxy.go
```

The complete pinned build, contract checks, and installation are automated by
`scripts/build-native-arm64.sh`. The current release APK binary has SHA-256:

```text
c4e5464e2ad51d679fe17fa361783f0427bd3815d70ef5927b57d9147501974c
```

Go build IDs may prevent a byte-identical output across toolchain patch
versions. Functional verification should also confirm the exported C symbols
listed by `NativeProxy.kt` in the upstream source.
