# Project guidance: Android-to-Windows screen mirroring

This repository is for a small, privacy-conscious Android-to-Windows screen-mirroring MVP. Read `PRD.md` before implementation and keep changes within its scope.

## Product constraints

- MVP is one Android phone to one Windows laptop over the same local Wi-Fi; no cloud, accounts, recording, audio, multi-viewer, or remote control unless explicitly requested.
- 1080p30 is best-effort on capable hardware and networks, not a guarantee. Always report negotiated/actual quality and provide a graceful lower-quality fallback.
- Optimize for a 4 GB Android phone and an 8 GB Windows laptop. Measure memory, CPU, latency, battery/heat on real target hardware; keep queues bounded and drop stale frames rather than accumulating latency.
- Never stream screen content before Android's system `MediaProjection` permission is granted. Keep capture visible through the required foreground-service notification and stop on revocation.
- Pairing codes are temporary and single-use. Protect pairing with expiry, rate limiting, explicit phone approval, authenticated session setup, and encrypted transport. Default to local-network-only access; do not log codes, keys, or screen content.

## Architecture direction

- Prefer a thin native Android/Kotlin layer for `MediaProjection`, foreground-service lifecycle, and `MediaCodec` hardware encoding. Use Rust for the Windows receiver and shared protocol/network code where it adds value; use JNI only when profiling justifies it.
- Start with H.264 and hardware encode/decode where available. Probe device capabilities at runtime and handle unsupported profiles explicitly.
- Keep transport selection evidence-based: prototype WebRTC against simpler alternatives for measured latency, resource use, security, and maintenance cost before committing.
- Keep UI and abstractions minimal until an end-to-end video-path spike works on the actual target devices.

## Engineering rules

- Prefer the smallest complete change; use existing project patterns and dependencies. Do not add layers, features, dependencies, or configuration without a concrete need.
- Keep security, consent, validation, bounded resource use, and error handling. Do not trade these for fewer lines.
- For non-trivial logic, add a focused test or assertion. Test pairing expiry/reuse/rate limits, protocol bounds, disconnect/permission revocation, queue limits, and quality fallback as applicable.
- Avoid logging sensitive pairing/session material or image/video data.
- Document platform requirements and manual device-test results. Distinguish automated tests from measurements on real hardware.
- Do not claim 1080p, low latency, or resource targets without verification on named devices; record actual resolution, frame rate, latency, memory, CPU, and thermal behavior.

## Frontend and design skill

For any user-facing web interface, load and follow the installed `design-taste-frontend` skill. It is intended for company/product websites and should not be forced onto a native desktop receiver or Android app UI; use platform-native accessibility and interaction conventions there.

## Installed skills

- Use `ponytail` for implementation: YAGNI, minimal complete changes, and explicit unverified risks.
- Use `ponytail-review` for reviewing a change and `ponytail-audit` only when asked to audit the whole repository.
- Use `design-taste-frontend` only when designing/building/reviewing a web page or website; it is not a requirement for native app screens.
