// Test-only sender: performs the handshake then holds the session open (no T_STOP) so a second
// pairing attempt can be observed getting rejected as busy instead of EOF.
//
// Usage: cargo run --example hold -- <host> <port> <code>

use std::env;
use std::io::Write;
use std::net::TcpStream;
use std::thread;
use std::time::Duration;

use viewgo_receiver::protocol::*;

fn main() -> std::io::Result<()> {
    let args: Vec<String> = env::args().collect();
    if args.len() != 4 {
        eprintln!("usage: hold <host> <port> <code>");
        std::process::exit(2);
    }
    let (host, port, code) = (&args[1], &args[2], &args[3]);

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
        eprintln!("first pairing rejected (status {status})");
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
    let ok = cipher
        .open(&read_frame(&mut stream, MAX_PLAIN)?)
        .expect("encrypted auth reply");
    assert_eq!(ok[0], T_AUTH_OK, "receiver did not acknowledge the session");

    println!("holding session open for 15s");
    let _ = stream.flush();
    thread::sleep(Duration::from_secs(15));
    Ok(())
}
