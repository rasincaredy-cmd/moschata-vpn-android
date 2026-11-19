# AGENTS.md

Operating guide and technical handbook for AI agents working in this repository. Read before making any modifications.

---

## 1. Project Overview

**Moschata VPN for Android** is the official all-in-one mobile client for the **Moschata VPN** service ([@MoschataVPN_bot](https://t.me/MoschataVPN_bot)).
It combines high-speed direct tunneling with a specialized bypass mode and instant network censorship diagnostics:

1. **Direct High-Speed Tunnel (WireGuard / AmneziaWG):**
   - High-performance kernel/userspace WireGuard tunnel using `com.wireguard.android.backend.GoBackend`.
   - Used for normal, unrestricted internet access with minimum latency and maximum bandwidth.
2. **Obhod Belogo Spiska / Bypass White List (qWDTT over VK TURN):**
   - Obfuscated tunneling over VKontakte WebRTC TURN media infrastructure (RTP/DTLS/RAW UDP).
   - Traffic is masked as WebRTC audio/video call media streams, allowing egress when mobile carriers throttle or restrict connections exclusively to state-approved "White Lists" (социально значимые ресурсы РФ).
3. **Built-in White-List Test (`WhiteListDetector`):**
   - Instant network diagnostic engine (< 200 ms with early exit).
   - Probes independent external targets (`Google 204`, `1.1.1.1`, `GitLab`) against Russian whitelist resources (`VK`, `Yandex`, `Gosuslugi`).
   - Uses an isolated trust manager to correctly handle Russian National CA (Минцифры) SSL certificates without false positives.
4. **Subscription Management & In-App Auto-Updates:**
   - 1-click subscription sync (`https://...` JSON endpoints).
   - Built-in GitHub Releases update check and silent APK package installer invocation.

---

## 2. Repository Layout

- `app/src/main/java/com/wdtt/client/`
  - `MainActivity.kt` — main entry point, navigation bar, lifecycle management, deep links, and update dialogs.
  - `detector/` — `WhiteListDetector.kt` network probing engine and UI components.
  - `tunnel/` — VPN service implementations (`TunnelService.kt`, `RawTunVpnService.kt`, `WireGuardHelper.kt`, `TunnelManager.kt`).
  - `profiles/` — connection profiles and subscription models (`ProfilesStore.kt`, `SubscriptionImport.kt`).
  - `settings/` — encrypted DataStore settings (`SettingsStore.kt`, `BypassShare.kt`).
  - `ui/`
    - `settings/SettingsTab.kt` — primary dashboard screen (connect button, active profile, White-List test card, configuration).
    - `profiles/ProfilesTab.kt` — profile list, subscription management, QR code scanner, manual profile editor.
    - `servers/ServersTab.kt` — self-hosted VPS server manager (admin interface).
    - `exceptions/ExceptionsTab.kt` — per-app split tunneling (VPN bypass/include rules).
    - `logs/LogsTab.kt` — live connection log viewer and system diagnostic exports.
    - `theme/` — Material 3 theming, dynamic colors, and color palettes.
- `go_client/` — Go backend code compiled via Gomobile (`netstack`, RTP obfuscation, WebRTC TURN client).
- `server/` — reference server implementation (`wdtt-server`, admin API, database bot).
- `.github/workflows/android-release.yml` — automated CI/CD for building signed release APKs on tag push (`v*`).

---

## 3. Critical Architectural Constraints & Gotchas

> [!CAUTION]
> **1. DO NOT CHANGE `applicationId = "net.qwdtt.client"`**  
> The production server (`wdtt-server` on klopas) derives each user's device credential bound to `entry.DeviceID`, which includes the Android package name. If `applicationId` is modified, the backend rejects connections with `DENIED:device_mismatch`. The public display name (`android:label`) must always be **Moschata VPN**, but the internal package name must remain `net.qwdtt.client`.

> [!IMPORTANT]
> **2. Auto-Connect on App Launch is STRICTLY DISABLED by Default**  
> Auto-connecting when opening the app is prohibited by user UX requirements. Connection must only be initiated when the user explicitly presses the connect button. Any auto-connect setting must default to `false`.

> [!IMPORTANT]
> **3. Server Infrastructure & Ports**  
> - **Active Central Server (Netherlands):** IP `48.102.173.96` (and `13.143.175.31`), WireGuard port `585`, qWDTT DTLS port `56000`, RAW port `56003`.  
> - **Closed Server (Germany `31.77.148.187`):** Permanently decommissioned. Never reference or connect to this IP.

> [!NOTE]
> **4. Branding & Links**  
> - Official bot: `https://t.me/MoschataVPN_bot`
> - Official GitHub repo: `https://github.com/rasincaredy-cmd/moschata-vpn-android`
> - Mascot: Russian Desman (выхухоль 🦫).
> - Do not include external donation links (CloudTips) or third-party promo bots (`@darkbit_vpnbot`, `@sidylinkbot`, `@darkbitVPN`).

---

## 4. Build and Development Commands

Run from the repository root:

```bash
# Check Kotlin code compilation and assemble debug APK
./gradlew assembleDebug

# Build optimized and signed release APKs (Universal and per-ABI)
./gradlew assembleRelease

# Output APK paths:
# app/build/outputs/apk/release/moschata-vpn-*-arm64-v8a.apk
# app/build/outputs/apk/release/moschata-vpn-*-universal.apk
```

### GitHub Actions Release Trigger:
To trigger an automated build and publish a new GitHub Release:
```bash
git tag v1.X.Y
git push origin v1.X.Y
```
CI will automatically generate a release keystore (if secrets are unset), compile all native Go libraries and APKs, and publish the release with release notes to `rasincaredy-cmd/moschata-vpn-android/releases`.

---

## 5. Coding Guidelines

- **Language:** Kotlin 1.9+ with Jetpack Compose Material 3.
- **Coroutines:** Use structured concurrency (`viewModelScope` or rememberCoroutineScope with `Dispatchers.IO` for network/I/O).
- **Probing & Network:** All probe requests must use `followRedirects(false)`, `followSslRedirects(false)`, zero body reading, and prompt cancellation upon resolution.
- **Tone:** Concise, factual, user-facing explanations without internal jargon or unnecessary code dumps.
