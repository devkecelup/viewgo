# ViewGo

Mirror an Android phone's screen to a Windows laptop over the same Wi-Fi, with a pairing code and
encrypted video. Rust receiver, Kotlin Android sender, H.264 through the phone's hardware encoder,
decoded by ffmpeg on the laptop.

Read `PRD.md` for scope and `docs/PROTOCOL.md` for the wire format.

## Download

- APK: [viewgo-debug.apk](https://github.com/devkecelup/viewgo/releases/latest/download/viewgo-debug.apk)
- Windows receiver: [viewgo-receiver.exe](https://github.com/devkecelup/viewgo/releases/latest/download/viewgo-receiver.exe)

## What works now

- Pairing with a 9-character code shown by the laptop (120 s, single use, rate limited per IP).
- AES-256-GCM stream, key derived from the code with PBKDF2; the code itself is never sent.
- Android `MediaProjection` capture with the required consent dialog and foreground notification.
- H.264 1080p30 at 6 Mbps (CBR) or 720p30 at 3 Mbps; automatic fallback to 720p if the 1080p encoder
  cannot start.
- Laptop shows the stream in a small window, with resolution and measured frame rate in the title.
- Stop from the phone app, the notification, the laptop (Esc / close window), or by revoking capture.
- Phone log: shown in the app, sent to the laptop over the same encrypted connection (printed as
  `phone: …`), and shareable with **Share log** when no connection was made.

## Requirements

- Windows laptop with Rust (MSVC toolchain) and `ffmpeg` on `PATH` (or set `FFMPEG` to its exe).
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
   address and a pairing code.
2. Install the APK on the phone and open **ViewGo**.
3. Type the laptop address and the pairing code, pick 1080p or 720p, and press **Connect**.
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

- No audio, recording, multi-viewer, touch control, iOS, or cloud relay.
- Fixed portrait capture: rotating the phone letterboxes landscape apps instead of switching the
  stream to landscape.
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
