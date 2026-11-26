# ViewGo protocol v1

Local-network screen mirroring: Android sender, Windows receiver. TCP, default port **47810**.
The laptop publishes `_viewgo._tcp.local.` using mDNS/DNS-SD so the Android app can find it without
typing an IP address. The pairing code is still required and is not itself a network locator.
This document is the single source of truth for both implementations; change it before changing either side.

## Framing

Every message on the wire is `u32 length` (big-endian) followed by exactly `length` bytes.
Maximum frame body: 16 MiB. Larger or zero lengths close the connection.

## Handshake (plaintext)

Both sides know the pairing code shown by the receiver (4 characters, Crockford base32
alphabet `0123456789ABCDEFGHJKMNPQRSTVWXYZ`). The code is **never sent**.

`salt` is 16 random bytes from the receiver. `cr` is 16 random bytes from the sender.
`sr` is 16 random bytes from the receiver.

```
sender -> receiver  HELLO
                    00..03 "VGMR"
                    04     version (1)
                    05     cr[16]
                    21..   zero padding ignored

receiver -> sender  CHALLENGE
                    00..03 "VGMR"
                    04     version (1)
                    05     status: 0 ok, 1 bad code, 2 expired, 3 rate limited, 4 busy
                    06     salt[16]
                    22     sr[16]

sender -> receiver  AUTH   (only when status == 0)
                    00..03 "VGMR"
                    04     version (1)
                    05     proof[32]

receiver -> sender  AUTH_OK (encrypted, counter 0, direction S->C)
                    payload: 10 "VGMR"
```

Key derivation:

```
key = PBKDF2-HMAC-SHA256(password = code as ASCII, salt = salt || cr || sr,
                         iterations = 120000, dkLen = 32)
proof = HMAC-SHA256(key, "viewgo-auth" || salt || cr || sr)
```

The receiver compares `proof` in constant time. A wrong proof, a replayed `cr`, an expired or
already-used code, or too many attempts (5 per IP per 60 s) closes the connection. A receiver is
**busy** while a session is active and rejects new handshakes.

## Encrypted records

After `AUTH_OK`, every message body is:

```
iv[12] || ciphertext || tag[16]      AES-256-GCM, 128-bit tag, no AAD
iv  = direction(1 byte: 0 = sender->receiver, 1 = receiver->sender)
      || 3 zero bytes
      || counter (u64 big-endian, starts at 0, +1 per record, never reused)
```

Counter overflow or an authentication failure closes the connection. The receiver must not accept
a counter it has already seen for a direction.

## Payloads (inside encrypted records)

```
0x10  AUTH_OK      "VGMR" (only used once, as the first S->C record)
0x01  VIDEO_CONFIG u16 width, u16 height, u8 fps. May be resent mid-session after a rotation or
                   capture-size change; the receiver restarts its decoder for the new size.
0x02  VIDEO_FRAME  u8 flags (bit0 = keyframe), u64 pts_us, then H.264 Annex-B bytes
0x03  CONTROL      u8 command, optional bytes:
                   1 = request keyframe (no args)
                   2 = set bitrate: u32 bits per second
0x04  LOG          UTF-8 text from the phone, printed by the receiver (max 8 KiB)
0x05  AUDIO_CONFIG u32 sample rate Hz, u8 channels (PCM signed 16-bit little endian)
0x06  AUDIO_FRAME  PCM signed 16-bit little endian samples
0x07  STOP         no args
```

The phone sends raw playback PCM only when the user enabled audio. The receiver may report audio
output unavailable and discard PCM if Windows has no usable playback device; video continues.

Annex-B is required: NAL units separated by `00 00 00 01` or `00 00 01`. Keyframe frames include
SPS and PPS before the IDR slice. Audio capture is Android playback-capture PCM; apps that deny
capture (including protected/DRM playback) produce no audio by platform policy.

## Session rules

- One sender at a time; the receiver generates a fresh code for every session.
- Code lifetime: 120 seconds, single use, discarded after a successful AUTH.
- The receiver binds only to the local network and never accepts a session before the sender
  grants Android's `MediaProjection` consent (the sender refuses to connect without a live
  projection).
- Either side may close the socket at any time; the other treats it as a clean stop.
