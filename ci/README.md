# Beeline Xray core build inputs

The GitHub Actions workflow `.github/workflows/build-xray-core.yml` builds the
Beeline-patched Xray core (`libxray.so`, android/arm64) from these inputs, on top
of upstream **XTLS/Xray-core v26.6.1**.

- `dialer.patch` — makes the XHTTP session ID a 12-char Base64URL string (9 random
  bytes) instead of the 36-char UUID. Beeline CDN answers `403` to the UUID form.
  Also adds `newBeelineSessionID` so the behaviour is unit-testable.
- `dialer_beeline_test.go` — asserts the session ID matches `^[A-Za-z0-9_-]{12}$`.
- `anet-patched/` — a drop-in replacement module for `github.com/wlynxg/anet`
  (pulled in transitively via `finalmask/realm → pion/stun`). Upstream anet's
  Android build `//go:linkname`s `net.zoneCache` and writes a struct whose layout
  no longer matches recent Go, corrupting the `net` package so **UDP/DNS break in
  the tunnel while TCP still works**. This stub delegates to the standard library,
  matching the stock core that ships in the app.

## Why CI

The core must be built with a **stock Go 1.26.4**. A custom local toolchain
(`go1.26.4-X:nodwarf5`) miscompiled android/arm64: TCP worked but UDP/DNS were
dead. The workflow pins stock Go to avoid that.

## Using the artifact

Download `libxray-beeline-arm64-v8a` and place `libxray.so` at
`app/src/main/jniLibs/arm64-v8a/libxray.so`. Never swap in an official Xray
binary — the Beeline profile regresses to `403`.
