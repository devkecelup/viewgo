# PRD: Android-to-Windows Wi-Fi Screen Mirroring

**Status:** Draft — decisions and device testing still needed  
**Product type:** Local-network screen-mirroring application  
**First release:** One Android phone streaming to one Windows laptop

## 1. Summary

Build a simple way to mirror an Android phone's screen to a Windows laptop over the same Wi-Fi network. The user pairs the devices with a short-lived code, approves Android's system screen-capture prompt, and sees the phone screen in a low-latency laptop window.

The design prioritizes low CPU and memory use on a 4 GB Android phone and an 8 GB Windows laptop. 1080p is a desired quality when both devices and the network can sustain it; the application must fall back to a lower resolution or bitrate rather than becoming unusable.

## 2. Problem and goals

### Problem

The user wants to show an Android screen on a modest Windows laptop without a cable, account, or cloud relay, while keeping resource use reasonable and image quality clear.

### Goals

- Mirror one Android device to one Windows PC over local Wi-Fi.
- Make pairing easy with a temporary code and explicit approval on the phone.
- Prefer Android hardware video encoding and Windows hardware decoding when available.
- Aim for 1080p at up to 30 fps on capable devices and networks.
- Adapt quality when performance or network conditions degrade.
- Keep latency, buffering, CPU use, and memory use bounded.
- Clearly communicate capture, connection, and quality state to the user.

### Non-goals for the MVP

- iOS support, internet/cloud streaming, recording, audio mirroring, multi-viewer, remote control/touchback, accounts, and app-store distribution.
- Guaranteeing 1080p on every Android phone or Wi-Fi network.
- Building a custom video codec or requiring a discrete GPU.

## 3. Users and use cases

### Primary user

A person with an Android phone (4 GB RAM) and a Windows laptop (8 GB RAM), both on the same Wi-Fi network, who wants to show their phone screen on the laptop.

### Main flow

1. User opens the receiver on Windows and selects **Start mirroring**.
2. Laptop displays a temporary pairing code and brief connection instructions.
3. User opens the Android app, enters the code, and chooses **Connect**.
4. Android displays the official screen-capture permission prompt. User approves it.
5. Laptop displays the stream and its current resolution/frame rate/connection state.
6. User stops from either device; Android capture foreground service and network session stop cleanly.

## 4. Product requirements

### Pairing and connection

- Devices must be on a reachable local network; no cloud account or relay is required for MVP.
- Pairing code is unpredictable, expires quickly, and is single-use. The exact length and expiry are implementation decisions; code entropy must resist guessing on a local network.
- Phone shows the target PC identity and asks the user to approve starting capture.
- Reject malformed, expired, reused, and rate-limited pairing attempts without revealing unnecessary details.
- Disconnect cleanly on user request, permission revocation, app/process loss, or network loss.
- Do not expose a listening receiver to the public internet by default. Document Windows Firewall behavior and request only the access needed for local-network discovery/connection.

### Screen capture and video

- Android uses the system screen-capture consent flow (`MediaProjection`) and a foreground service with a visible notification while capture is active.
- Video is encoded on-device using Android `MediaCodec` hardware acceleration when available; provide a safe fallback or clear unsupported-device error if no suitable encoder exists.
- H.264 is the initial codec target for broad decode support. Avoid custom codec work.
- Initial target: up to 1920×1080 at 30 fps. Resolution and bitrate are negotiated and may be lowered.
- Quality adaptation should prefer reducing bitrate and then resolution/frame rate according to measured sustained congestion or decode/render pressure. Use hysteresis to avoid rapid quality oscillation.
- Bound frame queues and drop stale frames rather than allowing latency and memory to grow without limit.
- Receiver renders the newest usable frame and reports actual, not merely requested, quality.
- Preserve aspect ratio; handle phone rotation and app/window resizing without corrupting the stream.

### Resource and usability requirements

- No unbounded frame queues, retained full-resolution frame history, or unnecessary copies between buffers.
- Use hardware decode on Windows when supported; software decode is an allowed fallback if it remains usable.
- Show connection state, actual resolution/frame rate, and understandable failure/recovery messages.
- Keep the experience usable at 720p or lower when 1080p is not sustainable.
- Stop capture promptly when the user ends the session or permission is revoked.

## 5. Quality attributes and acceptance criteria

These are MVP targets to validate on representative real hardware, not universal guarantees.

| Area | Acceptance target |
|---|---|
| Pairing | Correct, unexpired code connects devices on the same LAN; expired/invalid/reused code is rejected. |
| Consent | No screen pixels are transmitted before the user grants Android screen-capture permission. |
| Stop/privacy | Stop on phone and laptop ends streaming; revoked permission stops capture and closes the session. |
| Resolution | On a capable test phone and stable 5 GHz Wi-Fi, receiver reaches 1080p30 when negotiated; otherwise reports and uses the actual fallback mode. |
| Adaptation | Under constrained Wi-Fi or receiver load, quality can fall without unbounded queue growth or a stuck session, then recover gradually. |
| Responsiveness | Target glass-to-glass latency under 250 ms on the local test network; measure with a repeatable method and record results. |
| Memory | Set and measure explicit app/receiver memory budgets on reference devices; no monotonic growth during a 30-minute session. Initial engineering budget: Android incremental app memory ≤250 MB; Windows receiver incremental working set ≤350 MB. |
| Stability | 30-minute mirror session completes without crash, thermal shutdown, or unrecoverable disconnect on the agreed reference devices. |
| Security | Pairing expires, attempts are rate-limited, stream is local-network only by default, and code/session secrets are not logged. |

Memory and latency targets are provisional and must be revisited after a thin end-to-end prototype on the actual phone and laptop. Baselines vary by OS, vendor, decoder, and display stack.

## 6. Proposed technical direction

- **Windows receiver and shared networking/protocol:** Rust, with a small native UI/windowing and video-rendering layer selected after a prototype.
- **Android capture/encoder integration:** Kotlin Android app using `MediaProjection`, foreground service, and `MediaCodec`; Rust may be added through JNI only if profiling shows a real need. Do not force all Android code into Rust.
- **Transport:** direct local-network transport. Prototype WebRTC first if it meets the resource and integration constraints; otherwise evaluate a small authenticated UDP-based transport. Choose only after measuring latency, interoperability, and implementation/security cost.
- **Video:** H.264 first; negotiate dimensions, frame rate, bitrate, orientation, and codec capability.
- **Discovery/pairing:** receiver-generated short-lived code maps to a local endpoint; use authenticated session establishment and encrypted media/control transport. Do not assume that a code alone encrypts or authenticates the stream.
- **Adaptation:** start conservatively, collect coarse receiver/network feedback, and adjust bounded encoding parameters. Avoid elaborate congestion-control machinery until measurements require it.

The transport and Windows UI/decoder are open engineering decisions. A spike must compare options before committing the implementation.

## 7. MVP scope and milestones

1. **Feasibility spike:** verify Android capture + hardware H.264 encode and Windows decode/render at 720p/1080p on the intended devices; measure CPU, memory, latency, heat, and battery.
2. **Local session:** establish a direct connection with a temporary code and stream a fixed conservative profile.
3. **Usable receiver:** add status, start/stop, orientation handling, and clear errors.
4. **Adaptive quality:** bounded queues, telemetry, and quality fallback/recovery.
5. **Hardening:** pairing abuse protection, permission/network-loss handling, long-session and resource tests, Windows firewall documentation.

Do not build a polished UI or complex abstraction before the feasibility spike proves the video path on the target devices.

## 8. Risks and mitigations

- **Device encoder differences:** probe codec capabilities at runtime; test on the actual phone; fallback to lower modes or explain unsupported hardware.
- **Wi-Fi congestion/interference:** adapt bitrate/resolution and show a clear degraded-connection state.
- **Windows decode/UI complexity:** prototype a minimal Rust receiver early; hardware decode APIs may require platform-specific integration.
- **Pairing-code guessing or LAN interception:** short expiry, single-use codes, rate limits, authenticated key exchange, encrypted session, and explicit phone approval.
- **Android background restrictions:** use documented `MediaProjection` consent and foreground-service behavior; stop safely on revoked permission or OS lifecycle changes.
- **Overstated HD promise:** report measured stream quality and treat 1080p as best effort, never a guarantee.

## 9. Open questions

- Exact Android phone model and Android version?
- Does the Windows laptop have an integrated GPU with hardware H.264 decode?
- Is 5 GHz Wi-Fi available, and can both devices connect to the same access point?
- Is audio mirroring needed later? It is excluded from MVP.
- Should the receiver be a native desktop window or a browser-based local viewer? Prefer native unless the prototype shows a materially simpler/lighter option.

## 10. Definition of done

The MVP is done when the main flow works on the agreed Android phone and Windows laptop, the user can start/stop securely with consent, 1080p is reached when supported and otherwise gracefully falls back, and a documented 30-minute test records latency, actual resolution/frame rate, memory, CPU, and thermal behavior without unbounded resource growth.
