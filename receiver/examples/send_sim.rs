// Test-only sender: performs the real handshake and pushes H.264 Annex-B files as keyframes so the
// receiver's decrypt + ffmpeg + display path can be exercised without a phone. Several
// <file> <width> <height> triples replay a resolution change, which is what phone rotation sends.
//
// Usage: cargo run --example send_sim -- <host> <port> <code> <file.h264> [<width> <height>]...

use std::env;
use std::fs;
use std::io::Write;
use std::net::TcpStream;

use viewgo_receiver::protocol::*;

struct Segment {
    file: String,
    width: u16,
    height: u16,
}

fn parse_segments(args: &[String]) -> Vec<Segment> {
    if args.len() == 1 {
        return vec![Segment {
            file: args[0].clone(),
            width: 1080,
            height: 1920,
        }];
    }
    assert!(
        args.len() % 3 == 0,
        "usage: send_sim <host> <port> <code> <file> [<width> <height>]..."
    );
    args.chunks(3)
        .map(|chunk| Segment {
            file: chunk[0].clone(),
            width: chunk[1].parse().expect("width"),
            height: chunk[2].parse().expect("height"),
        })
        .collect()
}

fn main() -> std::io::Result<()> {
    let args: Vec<String> = env::args().collect();
    if args.len() != 5 && args.len() < 8 {
        eprintln!("usage: send_sim <host> <port> <code> <file> [<width> <height>]...");
        std::process::exit(2);
    }
    let (host, port, code) = (&args[1], &args[2], &args[3]);
    let segments = parse_segments(&args[4..]);

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
    let mut writer = stream;
    let reply = read_frame(&mut writer, MAX_PLAIN)?;
    // The receiver rejects a wrong code with a plaintext status instead of an encrypted AUTH_OK.
    if reply.len() >= 6 && &reply[0..4] == MAGIC && reply[4] == VERSION {
        eprintln!("receiver rejected the pairing (status {})", reply[5]);
        std::process::exit(1);
    }
    let ok = cipher.open(&reply).expect("encrypted auth reply");
    assert_eq!(ok[0], T_AUTH_OK, "receiver did not acknowledge the session");

    let log = b"sim sender online";
    let mut log_payload = vec![T_LOG];
    log_payload.extend_from_slice(log);
    write_frame(&mut writer, &cipher.seal(&log_payload))?;

    for segment in &segments {
        let media = fs::read(&segment.file)?;
        let mut config = vec![T_VIDEO_CONFIG];
        config.extend_from_slice(&segment.width.to_be_bytes());
        config.extend_from_slice(&segment.height.to_be_bytes());
        config.push(30);
        write_frame(&mut writer, &cipher.seal(&config))?;

        let mut payload = vec![T_VIDEO_FRAME, 1u8, 0, 0, 0, 0, 0, 0, 0, 0];
        payload.extend_from_slice(&media);
        write_frame(&mut writer, &cipher.seal(&payload))?;
        println!(
            "sent {} bytes as {}x{}",
            media.len(),
            segment.width,
            segment.height
        );
    }
    // Optional hold (VIEWGO_SIM_HOLD seconds) so the session stays alive while frames flow:
    // a session that closes immediately kills ffmpeg before it can emit a full frame, which
    // makes it impossible to observe the real decode/display path.
    if let Ok(secs) = std::env::var("VIEWGO_SIM_HOLD") {
        if let Ok(n) = secs.parse::<u64>() {
            println!("holding session open for {n}s");
            std::thread::sleep(std::time::Duration::from_secs(n));
        }
    }
    write_frame(&mut writer, &cipher.seal(&[T_STOP]))?;
    writer.flush()?;
    Ok(())
}
