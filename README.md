# ViewGo

Mirror an Android phone's screen to a Windows laptop over the same Wi-Fi, with a pairing code and
encrypted video. Rust receiver, Kotlin Android sender, H.264 through the phone's hardware encoder,
decoded by ffmpeg on the laptop.

Read `PRD.md` for scope and `docs/PROTOCOL.md` for the wire format.

## Download

- APK: [viewgo-debug.apk](https://github.com/devkecelup/viewgo/releases/latest/download/viewgo-debug.apk)
- Windows receiver: [viewgo-receiver.exe](https://github.com/devkecelup/viewgo/releases/latest/download/viewgo-receiver.exe)

## What works now

- Automatic laptop discovery on the same Wi-Fi using DNS-SD/mDNS; the phone only needs the
  temporary 4-character pairing code (120 s, single use, rate limited per device address).
- AES-256-GCM stream, key derived from the code with PBKDF2; the code itself is never sent.
- Android `MediaProjection` capture with the required consent dialog and foreground notification.
- H.264 up to 1080p30 at 16 Mbps or 720p30 at 6 Mbps. The short side follows the quality choice and
  the long side keeps the phone's native proportions (1080x2400 stays 1080x2400), falling back to
  720p if the encoder or capture surface cannot start.
- Rotating the phone swaps only the encoder surface on the same virtual display (Android allows one
  `createVirtualDisplay` per projection), so portrait/landscape rotation keeps the session running.
- Android 14+ can share only one chosen app instead of the whole screen; earlier versions share the
  whole screen.
- Optional internal media/game audio streams over the encrypted session; enable it on the phone,
  and install `ffplay` on Windows. Android or an individual app may block playback capture.
- The laptop opens a native ViewGo window (white/green theme) with the pairing code, live status,
  the measured resolution and frame rate, a log panel for phone logs and receiver events, and the
  stream. Maximize it: the picture gets bigger and reads sharper.
- Stop from the phone app, the notification, or the laptop (Quit / close window), or by revoking
  capture.
- Phone log is shown on the laptop (log panel and `phone: ...`); the Android app keeps its log
  screen-free and only streams it over the encrypted connection.

## Requirements

- Windows laptop with Rust (MSVC toolchain), `ffmpeg` and `ffplay` on `PATH` (or set `FFMPEG` /
  `FFPLAY` to their exe).
- Android 10+ phone, and the APK installed (sideload is fine).
- Both devices on the same Wi-Fi network; the laptop must allow the receiver port through Windows
  Firewall (the first run normally prompts).

## Build

Receiver:

```powershell
cd receiver
cargo build --release
# binary: receiver/target/release/viewgo-receiver.exe
```

Android APK (JDK 17 + Android SDK 35):

```powershell
cd android
.\gradlew.bat assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

## Run

1. Start `viewgo-receiver.exe` (optionally `viewgo-receiver.exe 47810`). It prints the laptop
  pairing code. Allow it on your Windows private network when Firewall asks.
2. Install the APK on the phone and open **ViewGo**.
3. Tap **Find laptop**, enter the pairing code, pick 1080p or 720p, and choose **Share the whole
   screen** or (Android 14+) **Share one app**. Press **Connect**.
4. Allow screen capture when Android asks.
5. Stop with **Stop mirroring** in the app or the notification action.

Keep the phone unlocked while mirroring; capture of protected content (banking apps, DRM video)
shows black by Android design.

## Tests

```powershell
cd receiver
cargo test                 # crypto, framing, pairing policy
cargo run --example vector # crypto vector asserted by the Android unit test

cd android
.\gradlew.bat testDebugUnitTest
```

`receiver/examples/send_sim.rs` replays a real H.264 file through the live handshake so the full
receiver path can be checked without a phone:

```powershell
cargo run --example send_sim -- 127.0.0.1 47810 <pairing code> sample.h264 1080 1920
# add more <file> <width> <height> triples to replay a rotation mid-session
```

## Known limits

- No recording, multi-viewer, touch control, iOS, or cloud relay.
- Rotation swaps the encoder and sends a new keyframe, which can cause a short pause (a fraction of
  a second); the receiver restarts its decoder and keeps the window.
- Sharpness is limited by the laptop window, not the stream: the capture is the phone's native
  1080x2400, but a 9:20 phone drawn into a 1920x1080 laptop screen can only reach about 468x1040 px
  (height-limited). Maximize the receiver window for the biggest, sharpest picture.
- Games and apps that mark their window as secure (many banking apps and some DRM/game surfaces)
  are captured as black by Android; that is the platform's protection, not a ViewGo bug.
- Single-app capture requires Android 14+ and the **Share one app** option before opening the
  system consent screen. If Android/OEM disables that option, ViewGo cannot override system policy.
- Playback audio capture is best-effort. Apps that mark audio as non-capturable (often DRM/voice apps)
  will stay silent by Android policy.
- Software H.264 decode on the laptop through ffmpeg; hardware decode is a later step.
- Quality adaptation is only the startup fallback to 720p. Bitrate does not change mid-session yet.
- 1080p is best effort. A 4 GB phone that gets hot or drops frames should use 720p.
- The pairing code is the only shared secret. At 4 characters (~1 million combinations) online
  guessing stays impractical (120 s expiry, single use, 5 attempts per minute per address), but an
  attacker already on your Wi-Fi who records the pairing handshake can try every code offline and
  decrypt that session. Pair only on a network you trust.

## Layout

```
docs/PROTOCOL.md   wire format and handshake
receiver/          Rust Windows receiver (ffmpeg decode + native egui window)
android/           Kotlin capture/encode app (no AndroidX)
```
