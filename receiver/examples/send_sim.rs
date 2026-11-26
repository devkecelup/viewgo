// Test-only sender: performs the real handshake and pushes one H.264 Annex-B file as a single
// keyframe so the receiver's decrypt + ffmpeg + display path can be exercised without a phone.
//
// Usage: cargo run --example send_sim -- <host> <port> <code> <file.h264>

use std::env;
use std::fs;
use std::io::Write;
use std::net::TcpStream;

use viewgo_receiver::protocol::*;

fn main() -> std::io::Result<()> {
    let args: Vec<String> = env::args().collect();
    if args.len() != 5 {
        eprintln!("usage: send_sim <host> <port> <code> <file.h264>");
        std::process::exit(2);
    }
    let (host, port, code, file) = (&args[1], &args[2], &args[3], &args[4]);
    let media = fs::read(file)?;

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
    let ok = cipher.open(&read_frame(&mut writer, MAX_PLAIN)?).expect("encrypted auth reply");
    assert_eq!(ok[0], T_AUTH_OK, "receiver did not acknowledge the session");

    write_frame(&mut writer, &cipher.seal(&[T_VIDEO_CONFIG, 0x04, 0x38, 0x07, 0x80, 30]))?;
    let log = b"sim sender online";
    let mut log_payload = vec![T_LOG];
    log_payload.extend_from_slice(log);
    write_frame(&mut writer, &cipher.seal(&log_payload))?;

    let mut payload = vec![T_VIDEO_FRAME, 1u8, 0, 0, 0, 0, 0, 0, 0, 0];
    payload.extend_from_slice(&media);
    write_frame(&mut writer, &cipher.seal(&payload))?;
    write_frame(&mut writer, &cipher.seal(&[T_STOP]))?;
    writer.flush()?;
    println!("sent {} bytes of H.264", media.len());
    Ok(())
}
