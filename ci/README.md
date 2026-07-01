# Beeline Xray core build inputs

`.github/workflows/build-xray-core.yml` builds the Beeline-patched Xray core
(`libxray.so`, android/arm64) from these inputs.

- **Base: XTLS/Xray-core `v26.5.9`.** Newest tag before v26.6.1 (which added
  `pion/stun` (added in v26.6.1 for realm STUN), which transitively pulls
  `github.com/wlynxg/anet`. anet's Android build corrupts the `net` package on
  recent Go and kills UDP/DNS in the tunnel (TCP keeps working — that was the
  "ping/Telegram work but no sites" symptom). The stock core shipped in the app
  has no anet and works, so we match it.
- `patch-xray.sh` — locates the XHTTP dialer by content and rewrites the session
  ID to a 12-char Base64URL string (9 random bytes). Beeline CDN answers `403`
  to the stock 36-char UUID. Path/line-tolerant so it survives version bumps.
- `dialer_beeline_test.go` — asserts the session ID matches `^[A-Za-z0-9_-]{12}$`.

Run "Build Beeline Xray core" (auto on push to `ci/**`, or via *Run workflow*,
optionally overriding the tag). Download `libxray-beeline-arm64-v8a`, place
`libxray.so` at `app/src/main/jniLibs/arm64-v8a/libxray.so`. Never swap in an
official Xray binary — the Beeline profile regresses to `403`.

The build fails if the result contains `wlynxg/anet` / `net.zoneCache`, i.e. if a
future base tag reintroduces the UDP-breaking dependency.
