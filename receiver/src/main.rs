// ViewGo receiver: pairs with an Android sender, decrypts H.264 frames, decodes them with
// ffmpeg and shows the newest frame in a small window.

use viewgo_receiver::protocol::*;
use std::collections::HashMap;
use std::env;
use std::io::{BufReader, BufWriter, Read, Write};
use std::net::{IpAddr, TcpListener, TcpStream, UdpSocket};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use minifb::{Key, Window, WindowOptions};

const DEFAULT_PORT: u16 = 47810;
const CODE_TTL: Duration = Duration::from_secs(120);
const ATTEMPT_WINDOW: Duration = Duration::from_secs(60);
const MAX_ATTEMPTS_PER_IP: u32 = 5;

struct Shared {
    code: Mutex<(String, Instant)>,
    active: Mutex<bool>,
    attempts: Mutex<HashMap<IpAddr, (u32, Instant)>>,
    seen_randoms: Mutex<Vec<[u8; 16]>>,
    status: Mutex<String>,
}

impl Shared {
    fn issue_code(&self) -> String {
        let mut guard = self.code.lock().unwrap();
        let previous = guard.0.clone();
        let code = loop {
            let candidate = new_code();
            if candidate != previous {
                break candidate;
            }
        };
        *guard = (code.clone(), Instant::now() + CODE_TTL);
        code
    }

    fn current_code(&self) -> String {
        self.code.lock().unwrap().0.clone()
    }

    fn set_status(&self, text: impl Into<String>) {
        *self.status.lock().unwrap() = text.into();
    }
}

struct Latest {
    seq: u64,
    frame: Option<Vec<u8>>,
    width: usize,
    height: usize,
}

impl Latest {
    fn new() -> Self {
        Self {
            seq: 0,
            frame: None,
            width: 0,
            height: 0,
        }
    }
}

struct Ffmpeg {
    child: Child,
    stdin: BufWriter<std::process::ChildStdin>,
}

fn main() {
    let port = env::args()
        .nth(1)
        .and_then(|value| value.parse::<u16>().ok())
        .unwrap_or(DEFAULT_PORT);

    let shared = Arc::new(Shared {
        code: Mutex::new((String::new(), Instant::now())),
        active: Mutex::new(false),
        attempts: Mutex::new(HashMap::new()),
        seen_randoms: Mutex::new(Vec::new()),
        status: Mutex::new("Waiting for a phone".to_string()),
    });

    let latest = Arc::new(Mutex::new(Latest::new()));
    let spares: Arc<Mutex<Vec<Vec<u8>>>> = Arc::new(Mutex::new(Vec::new()));
    let frame_seq = Arc::new(AtomicU64::new(0));

    let listener = match TcpListener::bind(("0.0.0.0", port)) {
        Ok(listener) => listener,
        Err(error) => {
            eprintln!("Cannot listen on port {port}: {error}");
            return;
        }
    };

    let code = shared.issue_code();
    println!("ViewGo receiver listening on port {port}");
    if let Some(ip) = local_ip() {
        println!("Phone connects to: {ip}:{port}");
    }
    println!("Pairing code: {code}  (valid 120 seconds)");

    {
        let shared = Arc::clone(&shared);
        let latest = Arc::clone(&latest);
        let spares = Arc::clone(&spares);
        let frame_seq = Arc::clone(&frame_seq);
        thread::spawn(move || accept_loop(listener, shared, latest, spares, frame_seq));
    }

    run_window(shared, latest, spares, frame_seq, port);
}

fn accept_loop(
    listener: TcpListener,
    shared: Arc<Shared>,
    latest: Arc<Mutex<Latest>>,
    spares: Arc<Mutex<Vec<Vec<u8>>>>,
    frame_seq: Arc<AtomicU64>,
) {
    for stream in listener.incoming() {
        let stream = match stream {
            Ok(stream) => stream,
            Err(_) => continue,
        };
        let peer = match stream.peer_addr() {
            Ok(addr) => addr.ip(),
            Err(_) => continue,
        };
        let shared = Arc::clone(&shared);
        let latest = Arc::clone(&latest);
        let spares = Arc::clone(&spares);
        let frame_seq = Arc::clone(&frame_seq);
        thread::spawn(move || {
            let _ = stream.set_nodelay(true);
            if let Err(error) = serve(stream, peer, &shared, latest, spares, frame_seq) {
                println!("Session ended: {error}");
            }
            *shared.active.lock().unwrap() = false;
            shared.set_status("Waiting for a phone");
            let code = shared.issue_code();
            println!("Pairing code: {code}  (valid 120 seconds)");
        });
    }
}

fn serve(
    mut stream: TcpStream,
    peer: IpAddr,
    shared: &Arc<Shared>,
    latest: Arc<Mutex<Latest>>,
    spares: Arc<Mutex<Vec<Vec<u8>>>>,
    frame_seq: Arc<AtomicU64>,
) -> std::io::Result<()> {
    stream.set_read_timeout(Some(Duration::from_secs(15)))?;
    stream.set_write_timeout(Some(Duration::from_secs(15)))?;

    let handshake = match handshake(&mut stream, peer, shared) {
        Ok(value) => value,
        Err(error) => return Err(error),
    };
    stream.set_read_timeout(None)?;
    stream.set_write_timeout(None)?;

    let mut cipher = Cipher::new(handshake.key, 1);
    let mut out = BufWriter::with_capacity(64 * 1024, stream.try_clone()?);
    write_frame(&mut out, &cipher.seal(&[T_AUTH_OK, b'V', b'G', b'M', b'R']))?;
    out.flush()?;

    let mut builder = FfmpegBuilder::default();
    let mut video_config: Option<(u16, u16)> = None;
    let mut received = 0u64;
    let mut window_start = Instant::now();
    let mut frames_in_window = 0u32;

    loop {
        let body = read_frame(&mut stream, MAX_BODY)?;
        let plain = match cipher.open(&body) {
            Some(plain) => plain,
            None => return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "bad record")),
        };
        if plain.is_empty() {
            continue;
        }
        match plain[0] {
            T_VIDEO_CONFIG => {
                if plain.len() < 6 {
                    return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "short config"));
                }
                let width = u16::from_be_bytes([plain[1], plain[2]]);
                let height = u16::from_be_bytes([plain[3], plain[4]]);
                if width == 0 || height == 0 {
                    return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "bad size"));
                }
                video_config = Some((width, height));
                shared.set_status(format!("Connected {peer} — {width}x{height}"));
                builder.start(width, height, Arc::clone(&latest), Arc::clone(&spares), Arc::clone(&frame_seq))?;
            }
            T_VIDEO_FRAME => {
                if plain.len() < 10 {
                    return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "short frame"));
                }
                if video_config.is_none() || builder.process.is_none() {
                    continue;
                }
                received += 1;
                frames_in_window += 1;
                if builder.write(&plain[10..]).is_err() {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::BrokenPipe,
                        "decoder stopped",
                    ));
                }
                if window_start.elapsed() >= Duration::from_secs(1) {
                    let fps = frames_in_window as f64 / window_start.elapsed().as_secs_f64();
                    let (w, h) = video_config.unwrap();
                    shared.set_status(format!("{peer} — {w}x{h} — {fps:.0} fps"));
                    frames_in_window = 0;
                    window_start = Instant::now();
                }
            }
            T_STOP => break,
            T_LOG => {
                let text = String::from_utf8_lossy(&plain[1..]);
                println!("phone: {text}");
            }
            T_CONTROL => {}
            _ => {}
        }
    }
    println!("Session closed after {received} frames");
    Ok(())
}

struct Handshake {
    key: [u8; 32],
}

fn pairing_status(shared: &Shared, peer: IpAddr, client_random: &[u8; 16]) -> u8 {
    let mut attempts = shared.attempts.lock().unwrap();
    let entry = attempts.entry(peer).or_insert((0, Instant::now()));
    if entry.1.elapsed() > ATTEMPT_WINDOW {
        *entry = (0, Instant::now());
    }
    entry.0 += 1;
    let attempts_so_far = entry.0;
    drop(attempts);

    let active = shared.active.lock().unwrap();
    let code = shared.code.lock().unwrap();
    let seen = shared.seen_randoms.lock().unwrap();

    if *active {
        STATUS_BUSY
    } else if attempts_so_far > MAX_ATTEMPTS_PER_IP {
        STATUS_RATE_LIMITED
    } else if Instant::now() > code.1 || seen.contains(client_random) {
        STATUS_EXPIRED
    } else {
        STATUS_OK
    }
}

fn handshake(stream: &mut TcpStream, peer: IpAddr, shared: &Arc<Shared>) -> std::io::Result<Handshake> {
    let hello = read_frame(stream, MAX_PLAIN)?;
    if hello.len() < 21 || &hello[0..4] != MAGIC || hello[4] != VERSION {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "bad hello"));
    }
    let client_random: [u8; 16] = hello[5..21].try_into().unwrap();

    let status = pairing_status(shared, peer, &client_random);

    let salt = random_bytes::<16>();
    let server_random = random_bytes::<16>();
    let mut challenge = Vec::with_capacity(38);
    challenge.extend_from_slice(MAGIC);
    challenge.push(VERSION);
    challenge.push(status);
    challenge.extend_from_slice(&salt);
    challenge.extend_from_slice(&server_random);
    write_frame(stream, &challenge)?;
    stream.flush()?;

    if status != STATUS_OK {
        println!("Rejected pairing from {peer} (status {status})");
        return Err(std::io::Error::new(std::io::ErrorKind::PermissionDenied, "pairing rejected"));
    }

    let code = shared.current_code();
    let key = derive_key(&code, &salt, &client_random, &server_random);
    let expected = proof(&key, &salt, &client_random, &server_random);

    let auth = read_frame(stream, MAX_PLAIN)?;
    if auth.len() != 37 || &auth[0..4] != MAGIC || auth[4] != VERSION {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "bad auth"));
    }
    if !proof_matches(&expected, &auth[5..37]) {
        return Err(std::io::Error::new(std::io::ErrorKind::PermissionDenied, "wrong code"));
    }

    *shared.active.lock().unwrap() = true;
    {
        let mut seen = shared.seen_randoms.lock().unwrap();
        seen.push(client_random);
        if seen.len() > 64 {
            seen.remove(0);
        }
    }
    {
        let mut code = shared.code.lock().unwrap();
        code.1 = Instant::now();
    }
    println!("Paired with {peer}");
    Ok(Handshake { key })
}

#[derive(Default)]
struct FfmpegBuilder {
    process: Option<Ffmpeg>,
}

impl FfmpegBuilder {
    fn start(
        &mut self,
        width: u16,
        height: u16,
        latest: Arc<Mutex<Latest>>,
        spares: Arc<Mutex<Vec<Vec<u8>>>>,
        frame_seq: Arc<AtomicU64>,
    ) -> std::io::Result<()> {
        if self.process.is_some() {
            return Ok(());
        }
        let ffmpeg = env::var("FFMPEG").unwrap_or_else(|_| "ffmpeg".to_string());
        // shortcut: H.264 decoding runs in software through ffmpeg; move to Media Foundation
        // (D3D11VA) if the target laptop cannot decode 1080p in real time.
        let mut child = Command::new(ffmpeg)
            .args([
                "-hide_banner",
                "-loglevel",
                "error",
                "-flags",
                "low_delay",
                "-probesize",
                "32",
                "-analyzeduration",
                "0",
                "-f",
                "h264",
                "-i",
                "pipe:0",
                "-an",
                "-sn",
                "-dn",
                "-fps_mode",
                "passthrough",
                "-f",
                "rawvideo",
                "-pix_fmt",
                "bgra",
                "pipe:1",
            ])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()?;

        let stdin = child.stdin.take().unwrap();
        let stdout = child.stdout.take().unwrap();
        let (w, h) = (width as usize, height as usize);

        thread::spawn(move || {
            let mut reader = BufReader::with_capacity(1 << 20, stdout);
            let frame_size = w * h * 4;
            loop {
                let mut buffer = spares
                    .lock()
                    .unwrap()
                    .pop()
                    .unwrap_or_else(|| vec![0u8; frame_size]);
                if buffer.len() != frame_size {
                    buffer.resize(frame_size, 0);
                }
                if reader.read_exact(&mut buffer).is_err() {
                    break;
                }
                let mut guard = latest.lock().unwrap();
                let old = guard.frame.replace(buffer);
                guard.width = w;
                guard.height = h;
                guard.seq += 1;
                drop(guard);
                frame_seq.fetch_add(1, Ordering::Relaxed);
                if let Some(old) = old {
                    let mut pool = spares.lock().unwrap();
                    if pool.len() < 3 {
                        pool.push(old);
                    }
                }
            }
        });

        self.process = Some(Ffmpeg {
            child,
            stdin: BufWriter::with_capacity(1 << 20, stdin),
        });
        Ok(())
    }

    fn write(&mut self, annex_b: &[u8]) -> std::io::Result<()> {
        let process = self.process.as_mut().expect("decoder started");
        process.stdin.write_all(annex_b)?;
        process.stdin.flush()
    }
}

impl Drop for Ffmpeg {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn run_window(
    shared: Arc<Shared>,
    latest: Arc<Mutex<Latest>>,
    spares: Arc<Mutex<Vec<Vec<u8>>>>,
    frame_seq: Arc<AtomicU64>,
    port: u16,
) {
    let mut window = match Window::new(
        "ViewGo",
        540,
        960,
        WindowOptions {
            resize: true,
            ..WindowOptions::default()
        },
    ) {
        Ok(window) => window,
        Err(error) => {
            eprintln!("Cannot open window: {error}");
            return;
        }
    };
    window.set_target_fps(60);

    let mut pixels = Vec::<u32>::new();
    let mut width = 0usize;
    let mut height = 0usize;
    let mut seen = 0u64;

    while window.is_open() && !window.is_key_down(Key::Escape) {
        let next_seq = frame_seq.load(Ordering::Relaxed);
        if next_seq != seen {
            let mut guard = latest.lock().unwrap();
            if let Some(frame) = guard.frame.take() {
                seen = guard.seq;
                width = guard.width;
                height = guard.height;
                drop(guard);

                if pixels.len() != width * height {
                    pixels.resize(width * height, 0);
                }
                // ffmpeg gives BGRA bytes; minifb wants 0x00RRGGBB.
                for (pixel, bgra) in pixels.iter_mut().zip(frame.chunks_exact(4)) {
                    *pixel = (bgra[0] as u32) | ((bgra[1] as u32) << 8) | ((bgra[2] as u32) << 16);
                }
                if let Ok(mut pool) = spares.lock() {
                    if pool.len() < 3 {
                        pool.push(frame);
                    }
                }
            }
        }

        let code = shared.current_code();
        let status = shared.status.lock().unwrap().clone();
        window.set_title(&format!("ViewGo — {status} — port {port} — code {code}"));

        if width > 0 && height > 0 && !pixels.is_empty() {
            let _ = window.update_with_buffer(&pixels, width, height);
        } else {
            let _ = window.update();
        }
    }
}

fn local_ip() -> Option<IpAddr> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    Some(socket.local_addr().ok()?.ip())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;

    fn shared(expired: bool) -> Arc<Shared> {
        let expiry = if expired {
            Instant::now() - Duration::from_secs(1)
        } else {
            Instant::now() + CODE_TTL
        };
        Arc::new(Shared {
            code: Mutex::new(("AB3DE4GHJ".to_string(), expiry)),
            active: Mutex::new(false),
            attempts: Mutex::new(HashMap::new()),
            seen_randoms: Mutex::new(Vec::new()),
            status: Mutex::new(String::new()),
        })
    }

    fn peer(last: u8) -> IpAddr {
        IpAddr::V4(Ipv4Addr::new(192, 168, 1, last))
    }

    #[test]
    fn fresh_code_accepts() {
        assert_eq!(pairing_status(&shared(false), peer(2), &[7; 16]), STATUS_OK);
    }

    #[test]
    fn expired_code_rejects() {
        assert_eq!(pairing_status(&shared(true), peer(2), &[7; 16]), STATUS_EXPIRED);
    }

    #[test]
    fn busy_receiver_rejects() {
        let shared = shared(false);
        *shared.active.lock().unwrap() = true;
        assert_eq!(pairing_status(&shared, peer(2), &[7; 16]), STATUS_BUSY);
    }

    #[test]
    fn replayed_client_random_rejects() {
        let shared = shared(false);
        shared.seen_randoms.lock().unwrap().push([9; 16]);
        assert_eq!(pairing_status(&shared, peer(2), &[9; 16]), STATUS_EXPIRED);
    }

    #[test]
    fn attempts_are_rate_limited_per_ip() {
        let shared = shared(false);
        for _ in 0..MAX_ATTEMPTS_PER_IP {
            assert_eq!(pairing_status(&shared, peer(2), &[1; 16]), STATUS_OK);
        }
        assert_eq!(
            pairing_status(&shared, peer(2), &[1; 16]),
            STATUS_RATE_LIMITED
        );
        assert_eq!(pairing_status(&shared, peer(3), &[1; 16]), STATUS_OK);
    }
}
