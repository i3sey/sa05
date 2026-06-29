# Rebuilding arm64 native executables

Run the repository script from the project root:

```bash
ANDROID_SDK_ROOT="$HOME/Android/Sdk" scripts/build-native-arm64.sh
```

The script requires Go 1.26.4 and Android NDK 29.0.14206865. It builds for
Android API 24 and passes both `max-page-size=16384` and
`common-page-size=16384` to the NDK linker. Sources are fetched at immutable
revisions:

- Xray-core `d2758a023cd7f4174a5a5fa4ff66e487d4342ba0`
- shadowsocks-android `ae28fd91931fe4d2d5aab044de9ceaf9ed07ad56`
- badvpn `2814301fbd7e1838634580a18a34f8b68587f931`
- libancillary `232d69a5ebb4461b572bd3f3b97088091e01c243`
- ByeDPI `7efde1b1296eaaa187b70e951894dde17527489c` (`v0.17.3`)
- TG WS Proxy source archive SHA-256
  `328409ea4dfcbc50eb3b9dbc24dec9535442a69d926b91e8fb2578fb7f71abba`

The official `android-ndk-r29-linux.zip` used for this build has SHA-256
`4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf`.

Nothing is copied into `app/src/main/jniLibs` until every output passes the ELF
16 KB/RELRO check and its runtime contract checks. To inspect an intermediate
failure, set `NATIVE_WORK_DIR` to an empty directory; otherwise the temporary
source and build trees are removed automatically.

With the pinned toolchain, two clean builds produce identical files:

```text
fb423ab8732d637f76597760b9c7befee99a04330ff840932f1552958251925f  libxray.so
eae44c7891bc4ae0d9d18a886ca1eb0d3ff5704fb1863a2ebd6e178d0fcc2636  libtun2socks.so
d0426ca0da2b190eb33e74db34b23b9409217b572b3d1b96b8d649ee2befe3e7  libciadpi.so
c4e5464e2ad51d679fe17fa361783f0427bd3815d70ef5927b57d9147501974c  libtgwsproxy.so
```

Validate an assembled APK with:

```bash
ANDROID_SDK_ROOT="$HOME/Android/Sdk" \
  scripts/check-16kb-compat.sh app/build/outputs/apk/debug/app-debug.apk
```
