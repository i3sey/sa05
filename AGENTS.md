# SA05 Xray client

## Goal

Minimal Android Xray client that accepts a full JSON config without rewriting
its routing, balancers, VLESS, Reality, gRPC, or XHTTP settings.

## Architecture

1. `XrayVpnService` starts `libxray.so` with the saved JSON.
2. Xray must expose a loopback SOCKS inbound with UDP enabled.
3. Android `VpnService` creates an IPv4 TUN (`10.10.10.1/30`).
4. `libtun2socks.so` forwards TUN traffic to that SOCKS inbound.
5. Selected packages use `VpnService.Builder.addDisallowedApplication`, so
   Android routes them directly outside the VPN.
6. The client package is always excluded to keep Xray outbound sockets out of
   its own TUN loop.
7. The Hosts tab starts a separate temporary Xray process for one outbound,
   then performs an HTTP/HTTPS request through its local SOCKS inbound.

## Config contract

- Strict JSON, not JSONC.
- At least one inbound with:
  - `"protocol": "socks"`
  - `"listen": "127.0.0.1"`
  - a valid numeric `"port"`
  - `"settings": { "udp": true }`
- The saved config is never overwritten.
- At runtime, legacy `"protocol":"hysteria"` with `settings.version=2` is
  translated to the current Xray `hysteria2.settings.servers` shape. This is
  required for the example config; current Xray does not accept the legacy
  transport shape.
- `geoip.dat` and `geosite.dat` are copied from APK assets to `filesDir`.

## Native runtime

- APK is intentionally restricted to `arm64-v8a`.
- `libxray.so`, `libtun2socks.so`, `geoip.dat`, and `geosite.dat` were sourced
  from the published NetGuard v1.3.11 APK, whose project documents Xray plus
  BadVPN tun2socks integration.
- The current Xray binary is chosen because the requested config uses modern
  transports including Reality, XHTTP, and Hysteria.

## Build

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Known limits

- IPv4 TUN only.
- No subscription/import URL support; paste JSON directly.
- App exclusions are applied when connecting. Reconnect after changing them.
- Host ping uses `burstObservatory.pingConfig.destination`, then
  `observatory.probeUrl`, then `https://www.gstatic.com/generate_204`.
- Ping measures time from HTTP request write to the first response byte. It
  validates the selected protocol, authentication, Reality/TLS, and transport.
- Only one host ping runs at a time; starting another cancels the previous
  temporary Xray process.
- Process log readers treat stream closure during stop/cancel as normal.
  Ping cancellation closes both the temporary Xray process and active socket.
- Notification permission is optional for functionality but should be granted
  on Android 13+ for a visible foreground-service notification.
- `QUERY_ALL_PACKAGES` is used only to populate the app-exclusion picker. A
  Play Store release would need to satisfy Google Play's restricted-permission
  policy or replace this with a narrower package discovery flow.
