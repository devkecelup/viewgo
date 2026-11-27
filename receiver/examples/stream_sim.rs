// Test-only sender: replays a real H.264 Annex-B file (encoded with AUD NAL units, i.e.
// `-x264-params aud=1`) as individual video frames at a fixed rate, so the receiver's decrypt,
// decode and display path can be measured end to end without a phone.
//
// Usage: cargo run --example stream_sim -- <host> <port> <code> <file.h264> [<width> <height>] [<fps>] [<seconds>]
//
// Everything after the file is optional: default 1080x2400 at 30 fps, looping until <seconds>
// elapse (default 8, 0 = play the file once as fast as the socket accepts it).

use std::env;
use std::fs;
use std::io::Write;
use std::net::TcpStream;
use std::time::{Duration, Instant};

use viewgo_receiver::protocol::*;

/// Splits Annex-B into access units at AUD NAL units (type 9). x264's `aud=1` emits one per frame,
/// which is exactly the grouping the phone's MediaCodec output has per encoded frame.
fn access_units(stream: &[u8]) -> Vec<&[u8]> {
    let mut starts = Vec::new();
    let mut i = 0;
    while i + 3 <= stream.len() {
        let sc4 = i + 4 <= stream.len() && stream[i..i + 4] == [0, 0, 0, 1];
        let sc3 = stream[i..i + 3] == [0, 0, 1];
        if sc4 {
            if stream[i + 4] == 9 {
                starts.push(i);
            }
            i += 4;
        } else if sc3 {
            if i + 3 < stream.len() && stream[i + 3] == 9 {
                starts.push(i);
            }
            i += 3;
        } else {
            i += 1;
        }
    }
    if starts.is_empty() {
        return vec![stream];
    }
    starts
        .iter()
        .enumerate()
        .map(|(n, &start)| {
            let end = starts.get(n + 1).copied().unwrap_or(stream.len());
            &stream[start..end]
        })
        .collect()
}

fn main() -> std::io::Result<()> {
    let args: Vec<String> = env::args().collect();
    if args.len() < 5 {
        eprintln!(
            "usage: stream_sim <host> <port> <code> <file.h264> [<width> <height>] [<fps>] [<seconds>]"
        );
        std::process::exit(2);
    }
    let (host, port, code) = (&args[1], &args[2], &args[3]);
    let width: u16 = args.get(5).and_then(|v| v.parse().ok()).unwrap_or(1080);
    let height: u16 = args.get(6).and_then(|v| v.parse().ok()).unwrap_or(2400);
    let fps: f64 = args.get(7).and_then(|v| v.parse().ok()).unwrap_or(30.0);
    let seconds: f64 = args.get(8).and_then(|v| v.parse().ok()).unwrap_or(8.0);

    let media = fs::read(&args[4])?;
    let frames = access_units(&media);
    println!("{} access units from {} bytes", frames.len(), media.len());

    let mut stream = TcpStream::connect(format!("{host}:{port}"))?;
    stream.set_nodelay(true)?;

    let client_random = random_bytes::<16>();
    let mut hello = Vec::new();
    hello.extend_from_slice(MAGIC);
    hello.push(VERSION);
    hello.extend_from_slice(&client_random);
    write_frame(&mut stream, &hello)?;

    let challenge = read_frame(&mut stream, MAX_PLAIN)?;
    assert_eq!(&challenge[0..4], MAGIC);
    let status = challenge[5];
    if status != STATUS_OK {
        eprintln!("receiver rejected the pairing (status {status})");
        std::process::exit(1);
    }
    let salt = &challenge[6..22];
    let server_random = &challenge[22..38];

    let key = derive_key(code, salt, &client_random, server_random);
    let mut auth = Vec::new();
    auth.extend_from_slice(MAGIC);
    auth.push(VERSION);
    auth.extend_from_slice(&proof(&key, salt, &client_random, server_random));
    write_frame(&mut stream, &auth)?;

    let mut cipher = Cipher::new(key, 0);
    let mut ok = cipher
        .open(&read_frame(&mut stream, MAX_PLAIN)?)
        .expect("encrypted auth reply");
    assert_eq!(ok.swap_remove(0), T_AUTH_OK);

    let mut log_payload = vec![T_LOG];
    log_payload.extend_from_slice(b"stream_sim online");
    write_frame(&mut stream, &cipher.seal(&log_payload))?;

    let mut config = vec![T_VIDEO_CONFIG];
    config.extend_from_slice(&width.to_be_bytes());
    config.extend_from_slice(&height.to_be_bytes());
    config.push(fps.round().clamp(1.0, 120.0) as u8);
    write_frame(&mut stream, &cipher.seal(&config))?;

    let interval = if fps > 0.0 {
        Some(Duration::from_secs_f64(1.0 / fps))
    } else {
        None
    };
    let deadline = (seconds > 0.0).then(|| Instant::now() + Duration::from_secs_f64(seconds));
    let mut sent = 0u64;
    let mut next = Instant::now();
    'outer: loop {
        for frame in &frames {
            let mut payload = vec![T_VIDEO_FRAME, 1, 0, 0, 0, 0, 0, 0, 0, 0];
            payload.extend_from_slice(frame);
            if let Err(error) = write_frame(&mut stream, &cipher.seal(&payload)) {
                eprintln!("socket write failed after {sent} frames: {error}");
                break 'outer;
            }
            sent += 1;
            if let Some(deadline) = deadline {
                if Instant::now() >= deadline {
                    break 'outer;
                }
            }
            if let Some(interval) = interval {
                next += interval;
                let now = Instant::now();
                if next > now {
                    std::thread::sleep(next - now);
                } else if now - next > interval * 4 {
                    // The receiver is far behind: stop pretending to be real time.
                    next = now;
                }
            }
        }
        if seconds <= 0.0 {
            break;
        }
    }
    println!("sent {sent} frames");
    let _ = write_frame(&mut stream, &cipher.seal(&[T_STOP]));
    stream.flush()
}
