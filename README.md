<p align="center">
  <img src="docs/img/viewgo-logo.svg" width="84" height="84" alt="ViewGo logo">
</p>

<h1 align="center">ViewGo</h1>

<p align="center">
  <sub>Mirror an Android phone's screen to a Windows laptop over the same Wi-Fi,<br>
  with a pairing code and encrypted video.</sub>
</p>

<p align="center">
  <img alt="latest release" src="https://img.shields.io/github/v/release/devkecelup/viewgo?style=for-the-badge&label=release&color=0F7B4F">
  <img alt="Android to Windows" src="https://img.shields.io/badge/Android_%E2%86%92_Windows-101828?style=for-the-badge&logo=android&logoColor=3DDC84">
  <img alt="LAN only" src="https://img.shields.io/badge/LAN_only-0F7B4F?style=for-the-badge&logo=wifi&logoColor=white">
  <img alt="AES-256-GCM" src="https://img.shields.io/badge/AES--256--GCM-0F7B4F?style=for-the-badge&logo=letsencrypt&logoColor=white">
  <img alt="open source" src="https://img.shields.io/badge/open_source-101828?style=for-the-badge&logo=github&logoColor=white">
</p>

<p align="center">
  <sub>Rust receiver, Kotlin Android sender, H.264 through the phone's hardware encoder,<br>
  decoded by ffmpeg on the laptop. Read <code>docs/PROTOCOL.md</code> for the wire format.</sub>
</p>

<br>

---

<br>

## Download

| Platform | Package |
|---|---|
| **Android 10+** | [viewgo-debug.apk](https://github.com/devkecelup/viewgo/releases/latest/download/viewgo-debug.apk) — sideload is fine |
| **Windows (installer)** | [ViewGo-Setup-&lt;version&gt;.exe](https://github.com/devkecelup/viewgo/releases/latest) — recommended |
| **Windows (portable)** | [viewgo-receiver.exe](https://github.com/devkecelup/viewgo/releases/latest/download/viewgo-receiver.exe) — no install |

<br>

---

<br>

## Why ViewGo

<table>
<tr>
<td width="50%" valign="top">
  <img src="docs/img/icons/lock.svg" width="24" height="24" alt=""><br>
  <strong>No account, no cloud</strong><br>
  The picture never leaves your local network. Nothing to sign up for, no relay server, no ads.
</td>
<td width="50%" valign="top">
  <img src="docs/img/icons/plug.svg" width="24" height="24" alt=""><br>
  <strong>No adb, no USB debugging</strong><br>
  Install the APK, run the receiver, type four characters. scrcpy asks you to enable USB debugging
  and use adb; ViewGo does not.
</td>
</tr>
<tr>
<td valign="top">
  <img src="docs/img/icons/ticket.svg" width="24" height="24" alt=""><br>
  <strong>Pairing, not blanket trust</strong><br>
  A 4-character code that lives 120 seconds, works once, is rate limited per device address and
  must be approved on the laptop. The code never travels; keys are derived with PBKDF2 and the
  stream is AES-256-GCM.
</td>
<td valign="top">
  <img src="docs/img/icons/timer.svg" width="24" height="24" alt=""><br>
  <strong>Bounded delay, not a growing queue</strong><br>
  At most two frames in flight. The oldest is dropped and a fresh keyframe requested, so a slow
  phone shows fewer frames instead of seconds of delay.
</td>
</tr>
<tr>
<td valign="top">
  <img src="docs/img/icons/search.svg" width="24" height="24" alt=""><br>
  <strong>It tells you where time goes</strong><br>
  <code>in / dec / draw fps</code> on the laptop and <code>encoder</code> / <code>send blocked</code> /
  <code>dropped</code> counters on the phone attribute a slow picture instead of guessing.
</td>
<td valign="top">
  <img src="docs/img/icons/package.svg" width="24" height="24" alt=""><br>
  <strong>Small, open, one purpose</strong><br>
  Sender and receiver live in this repo. View-only by scope: no control, no recording, no cloud.
  Android 14+ can share a single chosen app.
</td>
</tr>
</table>

<br>

---

<br>

## One number per tool

<p align="center">
  <img src="docs/img/comparison-score.svg" width="860" alt="Aggregate score out of 25: scrcpy 23, ViewGo 20, Phone Link 15, Vysor 14, ApowerMirror 14, AirDroid Cast 12">
</p>

<p align="center">
  <sub>Five criteria, 0–5 each, summed to 25. Same rubric for every row — the breakdown is below.</sub>
</p>

<br>

| Criterion | 5 means |
|---|---|
| **Privacy / local-only** | No account, no cloud relay, traffic stays on the LAN |
| **Setup effort** | Fewest steps to the first picture |
| **Cost** | Free and open source |
| **Feature breadth** | Control, recording, audio, camera, virtual display |
| **Light on devices** | Lowest CPU/RAM on both ends |

<br>

| | Privacy | Setup | Cost | Features | Light | **Total** |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| **scrcpy** | 5 | 3 | 5 | 5 | 5 | **23** |
| **ViewGo** | 5 | 4 | 5 | 2 | 4 | **20** |
| **Phone Link** | 2 | 3 | 4 | 3 | 3 | **15** |
| **Vysor** | 3 | 3 | 2 | 3 | 3 | **14** |
| **ApowerMirror** | 2 | 4 | 2 | 4 | 2 | **14** |
| **AirDroid Cast** | 1 | 4 | 2 | 3 | 2 | **12** |

- **scrcpy wins overall** — mature, full-featured, nothing installed on the phone. The price is
  enabling USB debugging (and adb over Wi-Fi for wireless use).
- **ViewGo scores 20 by giving up feature breadth on purpose.** No control, no recording, no
  virtual display — in exchange: one code, auto-discovery, no adb, and traffic that never leaves
  the LAN.
- **The commercial rows lose on privacy and cost** — an account, a relay server, or a subscription.
- Setup effort is judged from documented steps, not a stopwatch.

<br>

---

<br>

## Feature by feature

<table>
<tr>
<th></th>
<th><img src="docs/img/viewgo-logo.svg" width="20" height="20" alt=""><br><strong>ViewGo</strong></th>
<th><img src="docs/img/scrcpy-logo.svg" width="20" height="20" alt=""><br><strong>scrcpy</strong></th>
<th><img src="docs/img/phone-link.png" width="20" height="20" alt=""><br><strong>Phone Link</strong></th>
<th><img src="docs/img/vysor.png" width="20" height="20" alt=""><br><strong>Vysor</strong></th>
<th><img src="docs/img/apowermirror.png" width="20" height="20" alt=""><br><strong>ApowerMirror</strong></th>
<th><img src="docs/img/airdroid.png" width="20" height="20" alt=""><br><strong>AirDroid Cast</strong></th>
</tr>
<tr>
<td><strong>Connection</strong></td>
<td>Same Wi-Fi, no cable</td>
<td>USB or TCP/IP via adb</td>
<td>Same account ecosystem</td>
<td>USB or adb over Wi-Fi</td>
<td>Wi-Fi or USB</td>
<td>LAN or via relay</td>
</tr>
<tr>
<td><strong>Phone setup</strong></td>
<td>Install APK once</td>
<td>Nothing installed</td>
<td>Preinstalled on some brands</td>
<td>App + USB debugging</td>
<td>Install app</td>
<td>App + account</td>
</tr>
<tr>
<td><strong>Account required</strong></td>
<td>No</td>
<td>No</td>
<td>Microsoft account</td>
<td>No for USB; ads without Pro</td>
<td>Yes (paid)</td>
<td>Yes</td>
</tr>
<tr>
<td><strong>Pairing</strong></td>
<td>4-char code, 120 s, single use, approved on laptop</td>
<td>adb trust / <code>adb tcpip</code></td>
<td>Microsoft pairing</td>
<td>adb trust</td>
<td>Device list / PIN</td>
<td>Cast code</td>
</tr>
<tr>
<td><strong>Transport encryption</strong></td>
<td>AES-256-GCM, key from the code</td>
<td>adb tunnel</td>
<td>Vendor protocol</td>
<td>adb tunnel</td>
<td>Vendor protocol</td>
<td>Vendor protocol + relay</td>
</tr>
<tr>
<td><strong>Remote control</strong></td>
<td><img src="docs/img/icons/minus.svg" width="14" height="14" alt="no"> view only</td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"> keyboard, mouse, HID, gamepad</td>
<td><img src="docs/img/icons/warn.svg" width="14" height="14" alt="partial"> partial</td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"></td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"></td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"></td>
</tr>
<tr>
<td><strong>Audio forwarding</strong></td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"> optional, in-session</td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"> Android 11+</td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"></td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"></td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"></td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"></td>
</tr>
<tr>
<td><strong>Recording</strong></td>
<td><img src="docs/img/icons/minus.svg" width="14" height="14" alt="no"> OBS on the laptop</td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"> mp4 / mkv</td>
<td><img src="docs/img/icons/minus.svg" width="14" height="14" alt="no"></td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"> paid</td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"> paid</td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"> paid</td>
</tr>
<tr>
<td><strong>Frame rate</strong></td>
<td>30 / 60 fps chosen on the phone</td>
<td>30–120 fps, device-dependent</td>
<td>Limited</td>
<td>Device-dependent</td>
<td>Device-dependent</td>
<td>Device-dependent</td>
</tr>
<tr>
<td><strong>Single-app share</strong></td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"> Android 14+</td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"> virtual display / app start</td>
<td><img src="docs/img/icons/minus.svg" width="14" height="14" alt="no"></td>
<td><img src="docs/img/icons/minus.svg" width="14" height="14" alt="no"></td>
<td><img src="docs/img/icons/minus.svg" width="14" height="14" alt="no"></td>
<td><img src="docs/img/icons/minus.svg" width="14" height="14" alt="no"></td>
</tr>
<tr>
<td><strong>Open source</strong></td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"></td>
<td><img src="docs/img/icons/check.svg" width="14" height="14" alt="yes"> Apache-2.0</td>
<td><img src="docs/img/icons/minus.svg" width="14" height="14" alt="no"></td>
<td><img src="docs/img/icons/minus.svg" width="14" height="14" alt="no"></td>
<td><img src="docs/img/icons/minus.svg" width="14" height="14" alt="no"></td>
<td><img src="docs/img/icons/minus.svg" width="14" height="14" alt="no"></td>
</tr>
<tr>
<td><strong>Price</strong></td>
<td>Free</td>
<td>Free</td>
<td>Free with Windows</td>
<td>Freemium, Pro paid</td>
<td>Subscription</td>
<td>Subscription</td>
</tr>
<tr>
<td><strong>Latency claim</strong></td>
<td><strong>Not yet measured end to end</strong></td>
<td>35–70 ms (their README)</td>
<td>Not published</td>
<td>Not published</td>
<td>Not published</td>
<td>Not published</td>
</tr>
<tr>
<td><strong>Measured laptop cost</strong></td>
<td>0.28 receiver + 0.30 ffmpeg cores, hw decode, 1080x2400 @ 30 fps</td>
<td>Not published per machine</td>
<td>Not published</td>
<td>Not published</td>
<td>Not published</td>
<td>Not published</td>
</tr>
</table>

<br>

---

<br>

## How it works

### Finding and pairing

- **Two paths:** **Search laptop** runs one mDNS scan with an **8-second deadline**; if nothing
  answers, type the IPv4 address (and optional `:port`) in **Laptop IP address (optional)**. The
  last address is remembered. IPv4 only, matching the receiver's socket.
- **Pairing:** a 4-character code that lives 120 s, works once and is rate limited per device
  address; approved on the laptop. **AES-256-GCM**, key derived from the code with PBKDF2 — the
  code itself is never sent.

### Video

- H.264 from the phone's hardware encoder at **30 or 60 fps**: 1080p16 / 1080p24 Mbps, or
  720p6 / 720p10 Mbps. Native proportions are kept (1080x2400 stays 1080x2400); 720p is the
  automatic fallback when the encoder cannot start, and the phone logs the rate it really holds
  (`encoder N fps`).
- Rotation swaps only the encoder surface, so the session survives portrait/landscape. Android 14+
  can share **one app** instead of the whole screen.

### Audio and the laptop windows

- Optional internal audio over the same encrypted session (needs `ffplay`); an **Audio** slider
  sets the laptop volume live (default 70 %). Late audio is dropped, never stalling video.
- **ViewGo** window: code, status, `in / dec / draw fps`, log, OBS *Borderless mirror window*
  toggle. **ViewGo Mirror** window: the picture only, auto-sized to the stream.

### Delay and stopping

- At most **two frames in flight**; the oldest is dropped and a fresh keyframe requested, so delay
  stays bounded. The phone logs `encode delay` / `send blocked` / `dropped`.
- Stop from the phone app, the notification, the laptop window, or by revoking capture. The phone
  log streams to the laptop over the encrypted session.

<br>

---

<br>

## Requirements

- Windows with Rust (MSVC), `ffmpeg` and `ffplay` on `PATH` (or set `FFMPEG` / `FFPLAY`).
- Android 10+ phone with the APK sideloaded; both devices on the same Wi-Fi.
- Allow the receiver port through Windows Firewall on first run.

<br>

## Build

```powershell
# Windows receiver
cd receiver; cargo build --release          # receiver/target/release/viewgo-receiver.exe

# Installer (Inno Setup 6), back at the repo root
cd ..; & "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe" installer\viewgo.iss   # dist/ViewGo-Setup-<version>.exe

# Android APK (JDK 17+, Android SDK 35)
cd android; .\gradlew.bat assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with the debug key, so it sideloads. `assembleRelease` is unsigned until
you add a keystore.

<br>

## Run

1. Start `ViewGo-Setup-<version>.exe` (or `viewgo-receiver.exe [port]`). It shows the pairing code
   and the laptop's IPv4 address; allow it on your private network.
2. Install the APK and open **ViewGo**.
3. **Search laptop** (or type the IP), enter the code, pick quality and share target, press
   **Start mirroring**.
4. Allow screen capture when Android asks.
5. Stop from the app or the notification.

Keep the phone unlocked; protected content (banking, DRM) shows black by Android design.

<br>

## Tests

```powershell
cd receiver; cargo test; cargo run --example vector
cd ..\android; .\gradlew.bat testDebugUnitTest
```

`receiver/examples/send_sim.rs` replays an H.264 file through the real handshake, so the receiver
can be exercised without a phone:

```powershell
cargo run --example send_sim -- 127.0.0.1 47810 <code> sample.h264 1080 1920
```

<br>

## Known limits

- **Latency is not measured end to end** — only stage counters exist. Don't compare it with
  scrcpy's 35–70 ms until a real phone + laptop pair is timed.
- **IPv4 only.** If mDNS is blocked, type the address shown in the ViewGo window; IPv6 literals
  and host names are rejected.
- **View-only by scope:** no recording, multi-viewer, touch control, iOS, or cloud relay.
- **Rotation** swaps the encoder and sends a keyframe, causing a short pause.
- **Sharpness is window-limited**, and frames are capped at 1080 px on the long side; a larger
  mirror window is upscaled and slightly soft.
- **Secure content is black** (banking, some DRM/games); single-app share needs Android 14+;
  playback audio capture is best-effort and may stay silent.
- **Decode** uses d3d11va when available and falls back to software (`VIEWGO_HWACCEL=0` forces it);
  scaling runs on the CPU. Numbers live in `docs/PERFORMANCE.md`.
- **1080p is best effort.** Quality falls back to 720p only at startup; bitrate does not adapt
  mid-session.
- **Pair only on a network you trust.** 4 characters (~1M) makes online guessing impractical, but
  an attacker on your Wi-Fi who records the handshake can brute-force it offline.

<br>

---

<br>

## Layout

```
docs/PROTOCOL.md     wire format and handshake
docs/PERFORMANCE.md  stage counters and measured numbers
receiver/            Rust receiver (ffmpeg decode + native egui window)
android/             Kotlin capture/encode app (no AndroidX)
```

<br>

## Comparison notes

Scores are an assessment against the rubric above, not measurements. scrcpy figures come from its
README v5.0.1 (fetched 2026-11-27); Vysor, ApowerMirror, AirDroid Cast and Phone Link were assessed
from their product pages the same day. Logos: ViewGo from `ic_logo.xml`, scrcpy from its repo
(Apache-2.0), the rest are site favicons used only to identify the products.
