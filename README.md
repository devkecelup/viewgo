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
  temporary 9-character pairing code (120 s, single use, rate limited per device address).
- AES-256-GCM stream, key derived from the code with PBKDF2; the code itself is never sent.
- Android `MediaProjection` capture with the required consent dialog and foreground notification.
- H.264 up to 1080p30 at 10 Mbps or 720p30 at 5 Mbps; uses the screen aspect ratio within the
  selected resolution (portrait 1080x1920 or landscape 1920x1080) and even dimensions, and falls
  back to 720p if the 1080p encoder or capture surface cannot start.
- Capturable internal media/game audio streams as PCM over the encrypted session; Android or an
  individual app may block playback capture.
- Laptop shows the stream in a small window, with resolution and measured frame rate in the title.
- Stop from the phone app, the notification, the laptop (Esc / close window), or by revoking capture.
- Phone log: shown in the app, sent to the laptop over the same encrypted connection (printed as
  `phone: ...`), and shareable with **Share log** when no connection was made.

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
3. Tap **Find laptop**, enter the pairing code, pick 1080p or 720p, and press **Connect**.
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
cargo run --example send_sim -- 127.0.0.1 47810 <pairing code> sample.h264
```

## Known limits

- No recording, multi-viewer, touch control, iOS, or cloud relay.
- Screen orientation tracks the phone; rotating within portrait/landscape sizes adjusts the capture.
  Switching between portrait and landscape stops the stream with a reconnect prompt (H.264 encoder
  dimensions are fixed for a session).
- Playback audio capture is best-effort. Apps that mark audio as non-capturable (often DRM/voice apps)
  will stay silent by Android policy.
- Software H.264 decode on the laptop through ffmpeg; hardware decode is a later step.
- Quality adaptation is only the startup fallback to 720p. Bitrate does not change mid-session yet.
- 1080p is best effort. A 4 GB phone that gets hot or drops frames should use 720p.
- The pairing code is the only shared secret. Six-digit-style codes were rejected for 9 chars, but
  anyone who can read the code can pair; do not mirror on an untrusted network.

## Layout

```
docs/PROTOCOL.md   wire format and handshake
receiver/          Rust Windows receiver (ffmpeg decode + minifb window)
android/           Kotlin capture/encode app (no AndroidX)
```
