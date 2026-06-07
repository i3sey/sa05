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
  -trimpath \
  -ldflags="-s -w" \
  -o libtgwsproxy.so \
  tg-ws-proxy.go
```

The release APK binary bundled by SA05 has SHA-256:

```text
cf003924a6792e9bc1548b424b5caa446bcabfd564741dbc43849ec9e19bd45c
```

Go build IDs may prevent a byte-identical output across toolchain patch
versions. Functional verification should also confirm the exported C symbols
listed by `NativeProxy.kt` in the upstream source.
