# SA05 Xray client

## Goal

Minimal Android Xray client that accepts a full JSON config without rewriting
its routing, balancers, VLESS, Reality, gRPC, or XHTTP settings.

The primary source is one HTTPS subscription URL returning a JSON array of
complete Xray configs. The selected profile is identified by `remarks`.

## Architecture

1. `XrayVpnService` owns one Android VPN and three composite modes:
   Full Auto, Local Bypass, and Proxy Only.
2. Xray must expose a loopback SOCKS inbound with UDP enabled.
3. Android `VpnService` creates an IPv4 TUN (`10.10.10.1/30`).
4. `libtun2socks.so` forwards TUN traffic to that SOCKS inbound.
5. Selected packages use `VpnService.Builder.addDisallowedApplication`, so
   Android routes them directly outside the VPN.
6. The client package is always excluded to keep Xray outbound sockets out of
   its own TUN loop.
7. The Hosts tab starts a separate temporary Xray process for one outbound,
   then performs an HTTP/HTTPS request through its local SOCKS inbound.
8. Subscription refresh is performed at app startup and manually. A failed
   refresh never replaces the last valid cached profile list.
9. The foreground notification stores the actually running profile separately
   from the currently selected profile. Quick Settings toggles the VPN and
   reflects the persisted runtime state.
10. `sa05://add/<percent-encoded-https-url>` imports a subscription immediately.
    Invalid or failed imports never replace the last valid cached subscription.
11. Main-screen diagnostics probes Google, Ya.ru, RuTracker, Rule34, Kinozal,
    NNMClub, YouTube, and Telegram sequentially and publishes each result immediately.
    When connected, probes validate the active backend's SOCKS inbound and also
    require the TUN and tun2socks processes to remain alive. The Open action is
    the authoritative end-to-end browser check because the client UID must stay
    excluded from its own VPN.
12. Local Bypass starts Telegram WS Proxy plus ByeDPI on loopback and a minimal
    Xray compatibility
    bridge. BadVPN tun2socks sends TUN traffic to Xray; Xray forwards TCP
    through ByeDPI, blocks QUIC on UDP/443 to force browser TCP fallback, and
    sends remaining UDP (including DNS) directly. This avoids direct
    BadVPN/ByeDPI SOCKS incompatibilities and QUIC bypassing ByeDPI.
13. Zapret auto-selection requires Google or Ya.ru plus at least two of
    Rule34, Kinozal, and NNMClub. RuTracker is informational only because its
    intermittent HTTP 521 is not a reliable bypass signal. Telegram is reported
    separately because its blocking may be IP-based. Auto mode refuses to
    connect if no strategy passes.
14. Diagnostics validates TLS, follows only same-site/explicit redirects,
    requires the expected 2xx response and a non-trivial body. HTTP 403/451,
    other non-2xx responses, empty bodies, and transport failures are failures.
    RuTracker 5xx responses are shown as inconclusive.
15. Custom ByeDPI arguments are supported. The app always owns the loopback
    address and port and rejects daemon, pidfile, and transparent-mode overrides.
16. Full Auto sends Telegram through local TG WS Proxy, YouTube TCP through a
    selected ByeDPI process behind the local Xray compatibility bridge, and all
    remaining traffic through the selected
    provider Xray profile. Runtime-only Xray rules block QUIC globally to force
    reliable TCP fallback and route
    `geosite:youtube` plus major YouTube CDN domains to ByeDPI.
    Sniffing uses `routeOnly:true`, so routing sees the hostname while the
    compatibility bridge receives the original destination IP and forwards it
    to ByeDPI, matching the working Local Bypass path.
17. Full Auto performs a strict HTTPS YouTube probe for each ByeDPI preset and
    validates a real mobile Innertube player POST plus web, mobile API, image,
    and video-edge endpoints through the final
    Xray-to-bridge-to-ByeDPI route. If every local preset fails, Full Auto keeps
    Telegram WS active and sends YouTube through the selected provider Xray
    profile instead of failing the VPN. Its cache is separate from Local Bypass
    auto-selection and is tied to the current Android network.
18. Proxy Only starts the selected Xray profile without ByeDPI or TG WS Proxy.
19. TG WS Proxy listens on `127.0.0.1:1443` and must be applied in Telegram
    through `tg://proxy`. It is not a general-purpose SOCKS proxy.
20. TG WS Proxy uses Cloudflare WebSocket routing by default, supports an
    optional custom Cloudflare domain, and generates its MTProto secret locally.

## Config contract

- Strict JSON, not JSONC.
- At least one inbound with:
  - `"protocol": "socks"`
  - `"listen": "127.0.0.1"`
  - a valid numeric `"port"`
  - `"settings": { "udp": true }`
- The saved config is never overwritten.
- Full Auto augments only the runtime copy with collision-safe internal
  outbounds and highest-priority YouTube routing rules.
- Provider Hysteria JSON is passed through unchanged. The bundled Xray fork
  accepts `"protocol":"hysteria"` and rejects conversion to `"hysteria2"`.
- `geoip.dat` and `geosite.dat` are copied from APK assets to `filesDir`.

## Native runtime

- APK is intentionally restricted to `arm64-v8a`.
- `libxray.so`, `libtun2socks.so`, `geoip.dat`, and `geosite.dat` were sourced
  from the published NetGuard v1.3.11 APK, whose project documents Xray plus
  BadVPN tun2socks integration.
- The current Xray binary is chosen because the requested config uses modern
  transports including Reality, XHTTP, and Hysteria.
- `libciadpi.so` is the unmodified static aarch64 executable from ByeDPI
  v0.17.3, packaged with a `.so` name so Android extracts it as executable.
- Current ByeDPI presets are based on the upstream v0.17.x options and examples
  (`fake-tls-mod`, `tlsminor`, `auto-mode`, adaptive fake/OOB chains):
  https://github.com/hufrea/byedpi and
  https://github.com/hufrea/byedpi/discussions/533.
- The expanded YouTube mobile/API/CDN domain coverage follows the maintained
  zapret-discord-youtube lists:
  https://github.com/Flowseal/zapret-discord-youtube.
- `libtgwsproxy.so` is from Telegram WS Proxy Android v1.2.0 and is called
  through JNA. Its corresponding GPLv3 source archive is bundled under
  `third_party/tg-ws-proxy-android/`.
- ByeDPI and zapret2 are MIT licensed. `nfqws2` is not used because its
  NFQUEUE/firewall integration requires root on normal Android devices.

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
- Subscription deep links require the nested HTTPS URL to be percent-encoded.
- The main screen contains profile selection, refresh, VPN toggle, and settings.
  Subscription editing, host ping, and app exclusions are under Settings.
- App exclusions are applied when connecting. Reconnect after changing them.
- Host ping uses `burstObservatory.pingConfig.destination`, then
  `observatory.probeUrl`, then `https://www.gstatic.com/generate_204`.
- Restriction diagnostics use validated HTTPS responses rather than ICMP.
  Rule34, Kinozal, and NNMClub measure DPI bypass; RuTracker is informational
  and Telegram indicates separate IP-level availability. YouTube is
  informational for general scoring and is the strict Full Auto selector.
  Automated requests
  validate the backend but cannot originate from the app's excluded UID through
  its own TUN. Use Open for the final browser-route check.
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
- Subscription URL/config cache is excluded from Android cloud and device
  transfer backup because URLs may contain access tokens.
- Material You is user-configurable and enabled by default. Android 12+ uses
  wallpaper colors; older systems and disabled dynamic color use the fallback
  ocean/sand/coral palette.
- Notification actions stop the current VPN or reconnect with the currently
  selected profile. The Quick Settings tile opens the app's VPN permission
  flow when Android authorization has not been granted yet.
- All modes share one app-exclusion list. Changing mode while connected
  reconnects the service immediately.
- Local Bypass and Full Auto fail atomically if TG WS Proxy cannot start.
- The app UID remains excluded from TUN, so TG WS WebSocket connections and
  Xray outbound sockets leave directly without looping into the VPN.
