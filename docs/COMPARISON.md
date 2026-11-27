# ViewGo vs the other ways to mirror an Android screen to a Windows laptop

Data checked 2026-11-27. The scrcpy row comes from the official README (v5.0.1); the commercial
rows come from their public product pages. Scores are an assessment against the rubric below, not
measurements — except where a row names a measured number.

## What ViewGo does

### Finding and pairing the laptop

- Two paths in Step 1: tap **Search laptop** (DNS-SD/mDNS discovery on the local Wi-Fi), or type the
  laptop's IP by hand. Discovery is one scan at a time with an 8-second deadline, so a blocked
  router falls back to manual entry instead of spinning.
- The receiver advertises IPv4 only, matching the IPv4 socket it listens on.
- Temporary 4-character pairing code: 120 s lifetime, single use, rate limited per device address,
  explicit approval on the laptop. The code itself is never sent; AES-256-GCM keys are derived from
  it with PBKDF2.
- Last used address is remembered on the phone; a custom `:port` can be appended.

### Video

- H.264 from the phone's hardware encoder, 30 or 60 fps chosen on the phone:
  1080p16 / 1080p24 Mbps or 720p6 / 720p10 Mbps.
- Resolution follows the phone's native proportions (1080x2400 stays 1080x2400), with 720p as the
  automatic fallback when the encoder cannot hold the chosen rate.
- Hardware decode on the laptop by default (`VIEWGO_HWACCEL=0` forces software), with automatic
  fallback when the driver refuses.
- Rotation swaps only the encoder surface, so portrait/landscape keeps the session alive.
- Android 14+ can share a single chosen app instead of the whole screen.

### Consent and lifecycle

- Nothing is streamed before the system `MediaProjection` dialog is approved, and the capture stays
  visible through the required foreground-service notification.
- Stop from the phone app, the notification, the laptop window, or by revoking capture.

### Audio

- Optional internal media/game audio over the same encrypted session (needs `ffplay` on the
  laptop; Android or an app may block playback capture).
- A laptop-side volume slider applies live. Audio never stalls video: late audio chunks are dropped
  instead of holding the picture.

### Laptop windows and instrumentation

- **ViewGo** window: pairing code, live status, measured `in / dec / draw fps`, log panel,
  how-to-connect, OBS capture card with a borderless mirror toggle.
- **ViewGo Mirror** window: the picture only, auto-sized to the stream's aspect ratio.
- Phone log streams over the encrypted connection and never touches the phone's screen.
- Delay is bounded, not queued: at most two frames in flight, oldest dropped (then a fresh
  keyframe requested). Counters at each stage are named in `docs/PERFORMANCE.md`.

### Deliberately out of scope (MVP)

No remote control, no recording, no cloud, no accounts, no multi-viewer, no screen recording, no
internet requirement. That is PRD scope, not a missing implementation.

## Feature comparison

| | **ViewGo** | **scrcpy** | **Vysor** | **ApowerMirror** | **AirDroid Cast** | **Phone Link** |
|---|---|---|---|---|---|---|
| Connection | Same Wi-Fi, no cable | USB or TCP/IP via adb | USB or adb over Wi-Fi | Wi-Fi or USB | LAN or via relay | Same account ecosystem |
| Setup on the phone | Install APK once | None (no app installed) | Install app + USB debugging | Install app | Install app + account | Preinstalled on some brands |
| Account required | No | No | No for USB; ads without Pro | Yes (paid) | Yes | Microsoft account |
| Pairing ceremony | 4-char code, 120 s, single use, approve on laptop | adb trust / `adb tcpip` | adb trust | Device list / PIN | Cast code | Microsoft pairing |
| Transport encryption | AES-256-GCM from the code | adb tunnel | adb tunnel | Vendor protocol | Vendor protocol + relay | Vendor protocol |
| Remote control (input) | No (view only) | Yes — keyboard, mouse, HID, gamepad | Yes | Yes | Yes | Partial |
| Audio forwarding | Optional, in-session | Yes (Android 11+) | Yes | Yes | Yes | Yes |
| Recording | No (use OBS on the laptop) | Yes, to mp4/mkv | Yes (paid) | Yes (paid) | Yes (paid) | No |
| Frame rate | 30 / 60 fps on the phone | 30–120 fps, device-dependent | Device-dependent | Device-dependent | Device-dependent | Limited |
| Single-app share | Android 14+ | Virtual display / app start | No | No | No | No |
| Open source | Yes (this repo) | Yes (Apache-2.0) | No | No | No | No |
| Price | Free | Free | Freemium, Pro paid | Subscription | Subscription | Free with Windows |
| Latency claim | **Not yet measured end to end** | 35–70 ms (their README) | Not published | Not published | Not published | Not published |
| Measured laptop cost | 0.28 receiver + 0.30 ffmpeg cores, hw decode, 1080x2400@30 (`docs/PERFORMANCE.md`) | Not published per-machine | Not published | Not published | Not published | Not published |

## One chart: aggregate score

Five criteria, 0–5 each, 25 max, summed. Same rubric for every row.

| Criterion | 5 means |
|---|---|
| Privacy / local-only | No account, no cloud relay, traffic stays on the LAN |
| Setup effort | Fewest steps to the first picture |
| Cost | Free and open source |
| Feature breadth | Control, recording, audio, camera, virtual display |
| Light on devices | Lowest CPU/RAM on both ends |

```
Aggregate (max 25)
                                                            
scrcpy          23/25  ███████████████████████
ViewGo          20/25  ████████████████████
Phone Link      15/25  ███████████████
Vysor           14/25  ██████████████
ApowerMirror    14/25  ██████████████
AirDroid Cast   12/25  ████████████
```

Score breakdown:

| | Privacy | Setup | Cost | Features | Light | Total |
|---|---|---|---|---|---|---|
| scrcpy | 5 | 3 | 5 | 5 | 5 | **23** |
| ViewGo | 5 | 4 | 5 | 2 | 4 | **20** |
| Phone Link | 2 | 3 | 4 | 3 | 3 | **15** |
| Vysor | 3 | 3 | 2 | 3 | 3 | **14** |
| ApowerMirror | 2 | 4 | 2 | 4 | 2 | **14** |
| AirDroid Cast | 1 | 4 | 2 | 3 | 2 | **12** |

Why the scores land where they do:

- **scrcpy wins overall** because it is mature, full-featured and needs nothing installed on the
  phone — at the price of asking you to enable USB debugging (and adb over Wi-Fi for wireless).
- **ViewGo scores 20 by giving up feature breadth on purpose**: no control, no recording, no
  virtual display. It buys back setup effort (one code, auto-discovery, no adb) and keeps the
  privacy score of scrcpy while running entirely on the local network.
- **The commercial rows lose on privacy and cost**: an account, a relay server, or a subscription.
- **Setup effort** is judged from documented steps, not a stopwatch.

## Where ViewGo is honestly behind

- End-to-end latency has not been measured on real hardware; only receiver-side stage timings
  exist (`docs/PERFORMANCE.md`). Do not compare it with scrcpy's published 35–70 ms until a phone
  and laptop pair has been timed.
- No remote control and no in-app recording — view-only by scope.
- 1080p30 is best effort on a 4 GB phone; the encoder is the cap and the app logs the real rate.

## Sources

- scrcpy README, v5.0.1, https://github.com/Genymobile/scrcpy (fetched 2026-11-27): 30–120 fps,
  1920x1080+, 35–70 ms, ~1 s to first frame, audio on Android 11+, no account, no internet.
- Vysor, ApowerMirror, AirDroid Cast, Microsoft Phone Link product pages, assessed 2026-11-27.
- ViewGo numbers from this repo's README and `docs/PERFORMANCE.md`.