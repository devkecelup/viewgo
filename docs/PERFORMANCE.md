# Performance: where mirroring frames get lost

The picture on the laptop can only run as fast as the slowest stage of this chain. Each stage has
its own counter, so a slow picture can be attributed instead of guessed at.

| # | Stage | Owner | Counter / symptom |
|---|-------|-------|-------------------|
| 1 | Screen capture + H.264 encode | phone | phone log `encoder N fps (target 30)`, `encode delay N ms` |
| 2 | Sending over Wi-Fi | phone | phone log `send blocked N ms`, `dropped N frames` |
| 3 | Receive + decrypt + pipe into ffmpeg | laptop | status `in N fps` |
| 4 | ffmpeg decode + scale to BGRA | laptop | status `dec N fps` |
| 5 | Scale into the window + texture upload + paint | laptop | status `draw N fps` |

The ViewGo window shows `in / dec / draw fps` once a second, and writes the same line to the log
every 10 seconds.

## Reading it

- `in` low (e.g. 11 while the phone claims 30): frames never arrive. Cause is stage 1 or 2 — the
  phone cannot encode that capture size in real time, or the Wi-Fi link is dropping. The phone's own
  log says which (`encoder`, `send blocked`, `dropped`). Nothing about this is fixable on the laptop.
- `in` normal but `dec` low: ffmpeg cannot decode as fast as frames arrive. The decode queue grows,
  so delay also grows. Fix on the laptop: see hardware decode below, or lower the phone's quality.
- `in` and `dec` normal but `draw` low: decode is fine, the window is not repainting fast enough.
  The log then also says `Render is slow (...)` with the split between scaling, colour conversion and
  texture upload, so the slow part is named.

## Measured facts (Ryzen 5 5600G, Radeon iGPU, Windows, 2026-11-27)

Test stream: 1080x2400 H.264 High, 12 Mbps, replayed by `examples/stream_sim` at a fixed rate over
loopback, so only the receiver is under test.

| Measurement | Result |
|-------------|--------|
| ffmpeg decode 1080x2400 -> 486x1080 BGRA, software | 154 fps |
| same, `-hwaccel auto` (d3d11va on this GPU) | 250 fps (output byte-identical) |
| CPU scaling 486x1080 -> 324x720 (mirror default) | 0.6 ms/frame |
| CPU scaling + colour conversion, window 2560x1440 | 5.7 ms/frame |
| Receiver process, 1080x2400 @ 30 fps | 0.28 CPU cores |
| ffmpeg process, 30 fps, software decode | 0.43 CPU cores |
| ffmpeg process, 30 fps, hardware decode | 0.30 CPU cores |
| Whole session, 1080x2400 @ 30 fps, mirror maximized to 1920x1080 | `in 30 / dec 30 / draw 30` |
| Whole session, 1080x2400 @ 60 fps, 20 Mbps | `in 60 / dec 60 / draw 60` |

So on this class of machine the laptop keeps up at 60 fps with a maximized mirror window. A laptop
that reports `draw` well below `dec` is the exception worth investigating, and the `Render is slow`
line says where its time goes.

The log also names the graphics adapter at startup (`Graphics: <name> (<type> via <backend>)`). A
laptop that fell back to a software adapter (`Cpu`) paints slowly no matter how fast decode is, and
that line is the first thing to check when `draw` is low while `dec` is fine.

## Getting 30 fps, and 60 fps

- **60 fps needs the phone to encode 60 fps.** The phone sends its chosen rate (`30` or `60 fps` on
  the app's Frame rate row) in the video config, and the encoder is told the same rate. Nothing on
  the laptop can raise the rate above what the phone sends.
- **1080x2400 at 60 fps is a lot to ask of a 4 GB phone.** If the log says
  `encoder N fps (target 60)`, the encoder is the limit: use 720p (and 60 fps) instead, which the
  app offers as a normal combination.
- **Bitrate** rises with the frame rate for the same detail (1080p24 Mbps / 720p10 Mbps at 60 fps).
  The phone lowers it automatically if the socket starts blocking.
- **Laptop side:** hardware decode on (default), and no need to shrink the mirror window — scaling
  cost is a millisecond at the default size and a few at full screen.

## Levers

- `VIEWGO_HWACCEL=0` in the laptop's environment forces software decode (default: hardware decode
  with automatic fallback). Hardware decode also frees CPU for OBS on an 8 GB laptop.
- `FFMPEG` / `FFPLAY` point at specific binaries if they are not on `PATH`.
- Mirror window size is the only thing that changes laptop scaling cost: the default 720 px tall
  window costs under a millisecond per frame; a full-screen 1440p window costs a few.
- On the phone, 720p is the fallback when the encoder cannot hold the chosen rate at 1080x2400 —
  that is the stage most likely to be the real limit on a 4 GB phone, and the only stage that
  decides whether 60 fps is possible at all.

## Reproducing without a phone

`examples/stream_sim` performs the real handshake and replays an Annex-B file as timed frames:

```powershell
# one-off: encode a phone-shaped clip with per-frame AUD NALs
ffmpeg -f lavfi -i "testsrc2=size=1080x2400:rate=30" -t 6 -c:v libx264 -profile:v high `
  -preset veryfast -b:v 12M -g 60 -x264-params aud=1 clip.h264

# receiver in one window/terminal, then point the simulator at its code
target\release\examples\stream_sim.exe 127.0.0.1 47810 <CODE> clip.h264 1080 2400 30 20
```

`examples/fake_ffmpeg` stands in for a driver that refuses hardware decode (`FFMPEG=<path>`), which
exercises the software fallback.
