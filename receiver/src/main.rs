// ViewGo receiver: pairs with an Android sender, decrypts H.264 frames, decodes them with
// ffmpeg and shows the newest frame in a small window.

use std::collections::HashMap;
use std::env;
use std::io::{BufReader, BufWriter, Read, Write};
use std::net::{IpAddr, TcpListener, TcpStream};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};
use viewgo_receiver::protocol::*;

use mdns_sd::{ServiceDaemon, ServiceInfo};
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

struct AudioPlayer {
    child: Child,
    stdin: BufWriter<std::process::ChildStdin>,
}

fn audio_output_available() -> bool {
    #[cfg(windows)]
    {
        return Command::new("ffplay")
            .args([
                "-nodisp",
                "-autoexit",
                "-loglevel",
                "quiet",
                "-f",
                "lavfi",
                "-i",
                "anullsrc=r=8000:cl=mono",
                "-t",
                "0.1",
                "-f",
                "null",
                "-",
            ])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .map(|status| status.success())
            .unwrap_or(false);
    }
    #[cfg(not(windows))]
    {
        true
    }
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
    let _mdns = publish_receiver(port);
    println!("ViewGo receiver listening on port {port}");
    println!("On Android, tap Find laptop; pairing code: {code}  (valid 120 seconds)");

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
        if *shared.active.lock().unwrap() {
            drop(stream);
            continue;
        }
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
            None => {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    "bad record",
                ))
            }
        };
        if plain.is_empty() {
            continue;
        }
        match plain[0] {
            T_VIDEO_CONFIG => {
                let (width, height, fps) = decode_status(&plain)?;
                let dimensions_changed = video_config != Some((width, height));
                video_config = Some((width, height));
                shared.set_status(format!("Connected {peer} — {width}x{height}"));
                builder.start(
                    width,
                    height,
                    Arc::clone(&latest),
                    Arc::clone(&spares),
                    Arc::clone(&frame_seq),
                )?;
                if dimensions_changed {
                    builder.stop_audio();
                    received = 0;
                    window_start = Instant::now();
                    frames_in_window = 0;
                    let mut guard = latest.lock().unwrap();
                    guard.frame = None;
                    guard.width = 0;
                    guard.height = 0;
                    frame_seq.fetch_add(1, Ordering::Relaxed);
                }
                shared.set_status(format!("Connected {peer} — {width}x{height} — {fps} fps"));
            }
            T_VIDEO_FRAME => {
                if plain.len() < 10 {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::InvalidData,
                        "short frame",
                    ));
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
            T_AUDIO_CONFIG => {
                if plain.len() != 6 {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::InvalidData,
                        "short audio config",
                    ));
                }
                let sample_rate = u32::from_be_bytes(plain[1..5].try_into().unwrap());
                let channels = plain[5];
                if !(8_000..=192_000).contains(&sample_rate) || !(1..=2).contains(&channels) {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::InvalidData,
                        "unsupported audio format",
                    ));
                }
                let output_enabled = audio_output_available();
                if !output_enabled {
                    eprintln!(
                        "Audio forwarding off: Windows audio output device is unavailable or busy"
                    );
                }
                if let Err(error) = builder.ensure_audio(sample_rate, channels, output_enabled) {
                    eprintln!("Audio output unavailable (is ffplay installed?): {error}");
                }
            }
            T_AUDIO_FRAME => {
                if plain.len() > 1 && plain.len() <= 16 * 1024 {
                    builder.write_audio(&plain[1..]);
                }
            }
            T_LOG => {
                if plain.len() > 8193 {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::InvalidData,
                        "log too long",
                    ));
                }
                println!("phone: {}", String::from_utf8_lossy(&plain[1..]));
            }
            T_STOP => break,
            T_CONTROL => {}
            _ => {}
        }
    }
    println!("Session closed after {received} frames");
    Ok(())
}

fn decode_status(config: &[u8]) -> std::io::Result<(u16, u16, u8)> {
    if config.len() != 6 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "invalid video config length",
        ));
    }
    let width = u16::from_be_bytes([config[1], config[2]]);
    let height = u16::from_be_bytes([config[3], config[4]]);
    let fps = config[5];
    if !(2..=4096).contains(&width) || !(2..=4096).contains(&height) || !(1..=120).contains(&fps) {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "video config out of range",
        ));
    }
    Ok((width, height, fps))
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

fn handshake(
    stream: &mut TcpStream,
    peer: IpAddr,
    shared: &Arc<Shared>,
) -> std::io::Result<Handshake> {
    let hello = read_frame(stream, MAX_PLAIN)?;
    if hello.len() < 21 || &hello[0..4] != MAGIC || hello[4] != VERSION {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "bad hello",
        ));
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
        return Err(std::io::Error::new(
            std::io::ErrorKind::PermissionDenied,
            "pairing rejected",
        ));
    }

    let code = shared.current_code();
    let key = derive_key(&code, &salt, &client_random, &server_random);
    let expected = proof(&key, &salt, &client_random, &server_random);

    let auth = read_frame(stream, MAX_PLAIN)?;
    if auth.len() != 37 || &auth[0..4] != MAGIC || auth[4] != VERSION {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "bad auth",
        ));
    }
    if !proof_matches(&expected, &auth[5..37]) {
        return Err(std::io::Error::new(
            std::io::ErrorKind::PermissionDenied,
            "wrong code",
        ));
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
    audio: Option<AudioPlayer>,
    dimensions: Option<(usize, usize)>,
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
            if self.dimensions == Some((width as usize, height as usize)) {
                return Ok(());
            }
            let old = self.process.take();
            drop(old);
            self.dimensions = None;
        }
        let latest_for_reader = Arc::clone(&latest);
        let spares_for_reader = Arc::clone(&spares);
        let frame_seq_for_reader = Arc::clone(&frame_seq);
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
                let mut buffer = spares_for_reader
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
                let mut guard = latest_for_reader.lock().unwrap();
                let old = guard.frame.replace(buffer);
                guard.width = w;
                guard.height = h;
                guard.seq += 1;
                drop(guard);
                frame_seq_for_reader.fetch_add(1, Ordering::Relaxed);
                if let Some(old) = old {
                    let mut pool = spares_for_reader.lock().unwrap();
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
        self.dimensions = Some((w, h));
        Ok(())
    }

    fn write(&mut self, annex_b: &[u8]) -> std::io::Result<()> {
        let process = self.process.as_mut().expect("decoder started");
        if let Err(error) = process
            .stdin
            .write_all(annex_b)
            .and_then(|_| process.stdin.flush())
        {
            self.process.take();
            return Err(error);
        }
        Ok(())
    }

    fn start_audio(&mut self, sample_rate: u32, channels: u8) -> std::io::Result<()> {
        if self.audio.is_some()
            || !(8_000..=192_000).contains(&sample_rate)
            || !(1..=2).contains(&channels)
        {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "unsupported audio format",
            ));
        }
        let ffplay = env::var("FFPLAY").unwrap_or_else(|_| "ffplay".to_string());
        let rate = sample_rate.to_string();
        let channel_count = channels.to_string();
        let mut child = Command::new(ffplay)
            .args(["-nodisp", "-loglevel", "error"])
            .args([
                "-f",
                "s16le",
                "-ar",
                &rate,
                "-ac",
                &channel_count,
                "-blocksize",
                "4096",
                "-fflags",
                "nobuffer",
                "-flags",
                "low_delay",
                "-i",
                "pipe:0",
            ])
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .spawn()?;
        self.audio = Some(AudioPlayer {
            stdin: BufWriter::with_capacity(
                16 * 1024,
                child.stdin.take().ok_or_else(|| {
                    std::io::Error::new(std::io::ErrorKind::BrokenPipe, "ffplay stdin unavailable")
                })?,
            ),
            child,
        });
        Ok(())
    }

    fn ensure_audio(
        &mut self,
        sample_rate: u32,
        channels: u8,
        enabled: bool,
    ) -> std::io::Result<()> {
        self.stop_audio();
        if enabled {
            self.start_audio(sample_rate, channels)?;
        }
        Ok(())
    }

    fn write_audio(&mut self, pcm: &[u8]) {
        if let Some(audio) = self.audio.as_mut() {
            if let Err(error) = audio.stdin.write_all(pcm).and_then(|_| audio.stdin.flush()) {
                self.audio.take();
                eprintln!("Audio output stopped: {error}");
            }
        }
    }

    fn kill_audio(&mut self) {
        if let Some(mut audio) = self.audio.take() {
            let _ = audio.child.kill();
            let _ = audio.child.wait();
        }
    }

    fn stop_audio(&mut self) {
        self.kill_audio();
    }
}

impl Drop for FfmpegBuilder {
    fn drop(&mut self) {
        self.kill_audio();
    }
}

impl Drop for Ffmpeg {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

impl Drop for AudioPlayer {
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
    let mut frame_w = 0usize;
    let mut frame_h = 0usize;
    let mut seen = 0u64;
    let mut displayed = Vec::<u32>::new();

    while window.is_open() && !window.is_key_down(Key::Escape) {
        let next_seq = frame_seq.load(Ordering::Relaxed);
        if next_seq != seen {
            let mut guard = latest.lock().unwrap();
            if let Some(frame) = guard.frame.take() {
                seen = guard.seq;
                frame_w = guard.width;
                frame_h = guard.height;
                drop(guard);

                let (window_w, window_h) = window.get_size();
                let (window_w, window_h) = (window_w.max(1), window_h.max(1));
                if pixels.len() != window_w * window_h {
                    pixels.resize(window_w * window_h, 0);
                }
                pixels.fill(0x000000);
                scale_fit_bgra(&frame, frame_w, frame_h, &mut pixels, window_w, window_h);
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

        if frame_w > 0 && frame_h > 0 && !pixels.is_empty() {
            let (window_w, window_h) = window.get_size();
            let (window_w, window_h) = (window_w.max(1), window_h.max(1));
            if displayed.len() != window_w * window_h {
                displayed.resize(window_w * window_h, 0);
            }
            displayed.fill(0);
            let (draw_w, draw_h) = fit_size(frame_w, frame_h, window_w, window_h);
            let left = (window_w - draw_w) / 2;
            let top = (window_h - draw_h) / 2;
            for y in 0..draw_h {
                for x in 0..draw_w {
                    let sx = x * window_w / draw_w;
                    let sy = y * window_h / draw_h;
                    displayed[(top + y) * window_w + left + x] = pixels[sy * window_w + sx];
                }
            }
            let _ = window.update_with_buffer(&displayed, window_w, window_h);
        } else {
            let _ = window.update();
        }
    }
}

fn publish_receiver(port: u16) -> Option<ServiceDaemon> {
    let daemon = match ServiceDaemon::new() {
        Ok(daemon) => daemon,
        Err(error) => {
            eprintln!("Local discovery unavailable: {error}");
            return None;
        }
    };
    let info = match ServiceInfo::new(
        "_viewgo._tcp.local.",
        "ViewGo",
        "viewgo.local.",
        "0.0.0.0",
        port,
        &[("version", "1")][..],
    ) {
        Ok(info) => info.enable_addr_auto(),
        Err(error) => {
            eprintln!("Local discovery unavailable: {error}");
            let _ = daemon.shutdown();
            return None;
        }
    };
    if let Err(error) = daemon.register(info) {
        eprintln!("Local discovery unavailable: {error}");
        let _ = daemon.shutdown();
        return None;
    }
    Some(daemon)
}

fn fit_size(src_w: usize, src_h: usize, dst_w: usize, dst_h: usize) -> (usize, usize) {
    if src_w == 0 || src_h == 0 || dst_w == 0 || dst_h == 0 {
        return (0, 0);
    }
    let scale = (dst_w as f64 / src_w as f64).min(dst_h as f64 / src_h as f64);
    (
        ((src_w as f64 * scale).round() as usize).clamp(1, dst_w),
        ((src_h as f64 * scale).round() as usize).clamp(1, dst_h),
    )
}

fn bgra_pixel(frame: &[u8], src_w: usize, x: usize, y: usize) -> (u32, u32, u32) {
    let at = (y * src_w + x) * 4;
    (frame[at] as u32, frame[at + 1] as u32, frame[at + 2] as u32)
}

fn scale_fit_bgra(
    frame: &[u8],
    src_w: usize,
    src_h: usize,
    out: &mut [u32],
    dst_w: usize,
    dst_h: usize,
) {
    if src_w == 0 || src_h == 0 || dst_w == 0 || dst_h == 0 {
        return;
    }
    if frame.len() < src_w * src_h * 4 {
        return;
    }
    let (draw_w, draw_h) = fit_size(src_w, src_h, dst_w, dst_h);
    let left = (dst_w - draw_w) / 2;
    let top = (dst_h - draw_h) / 2;
    // Downscaling by nearest-neighbour is what made text look soft and jagged; average the
    // covered source pixels instead. Upscaling uses bilinear so enlarged windows stay smooth.
    let upscale = draw_w > src_w || draw_h > src_h;

    for y in 0..draw_h {
        for x in 0..draw_w {
            let (b, g, r) = if upscale {
                let fx = (x as f64 + 0.5) * src_w as f64 / draw_w as f64 - 0.5;
                let fy = (y as f64 + 0.5) * src_h as f64 / draw_h as f64 - 0.5;
                let x0f = fx.max(0.0);
                let y0f = fy.max(0.0);
                let x0 = (x0f as usize).min(src_w - 1);
                let y0 = (y0f as usize).min(src_h - 1);
                let x1 = (x0 + 1).min(src_w - 1);
                let y1 = (y0 + 1).min(src_h - 1);
                let tx = (fx - x0f as f64).clamp(0.0, 1.0);
                let ty = (fy - y0f as f64).clamp(0.0, 1.0);
                let p00 = bgra_pixel(frame, src_w, x0, y0);
                let p10 = bgra_pixel(frame, src_w, x1, y0);
                let p01 = bgra_pixel(frame, src_w, x0, y1);
                let p11 = bgra_pixel(frame, src_w, x1, y1);
                let mix = |a: u32, b: u32, c: u32, d: u32| -> u32 {
                    let top = a as f64 * (1.0 - tx) + b as f64 * tx;
                    let bottom = c as f64 * (1.0 - tx) + d as f64 * tx;
                    (top * (1.0 - ty) + bottom * ty).round() as u32
                };
                (
                    mix(p00.0, p10.0, p01.0, p11.0),
                    mix(p00.1, p10.1, p01.1, p11.1),
                    mix(p00.2, p10.2, p01.2, p11.2),
                )
            } else {
                let x0 = x * src_w / draw_w;
                let x1 = (((x + 1) * src_w + draw_w - 1) / draw_w)
                    .min(src_w)
                    .max(x0 + 1);
                let y0 = y * src_h / draw_h;
                let y1 = (((y + 1) * src_h + draw_h - 1) / draw_h)
                    .min(src_h)
                    .max(y0 + 1);
                let mut sum = [0u64; 3];
                let mut count = 0u64;
                for sy in y0..y1 {
                    for sx in x0..x1 {
                        let p = bgra_pixel(frame, src_w, sx, sy);
                        sum[0] += p.0 as u64;
                        sum[1] += p.1 as u64;
                        sum[2] += p.2 as u64;
                        count += 1;
                    }
                }
                let count = count.max(1);
                (
                    (sum[0] / count) as u32,
                    (sum[1] / count) as u32,
                    (sum[2] / count) as u32,
                )
            };
            out[(top + y) * dst_w + left + x] = (r << 16) | (g << 8) | b;
        }
    }
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
    fn fit_scale_keeps_landscape_and_portrait_content_centered() {
        let mut out = vec![0u32; 4 * 4];
        let src = [
            0, 0, 255, 255, // red
            0, 255, 0, 255, // green
        ];
        scale_fit_bgra(&src, 2, 1, &mut out, 4, 4);
        assert_eq!(out[4], 0x00ff0000);
        assert_eq!(out[7], 0x0000ff00);
        assert_eq!(out[0], 0);
    }

    #[test]
    fn downscale_averages_source_pixels_instead_of_dropping_them() {
        // 2x2 source: one red, one green, one blue, one white -> 1x1 output is mid grey.
        let src = [
            0, 0, 255, 255, // red
            0, 255, 0, 255, // green
            255, 0, 0, 255, // blue
            255, 255, 255, 255, // white
        ];
        let mut out = vec![0u32; 1];
        scale_fit_bgra(&src, 2, 2, &mut out, 1, 1);
        assert_eq!(out[0], 0x007f7f7f);
    }

    #[test]
    fn config_dimensions_change_after_rotation() {
        let mut builder = FfmpegBuilder::default();
        assert_eq!(builder.dimensions, None);
        builder.dimensions = Some((1920, 1080));
        assert_eq!(builder.dimensions, Some((1920, 1080)));
        builder.dimensions = Some((1080, 1920));
        assert_eq!(builder.dimensions, Some((1080, 1920)));
    }

    #[test]
    fn fit_viewport_preserves_portrait_and_landscape_aspect() {
        assert_eq!(fit_size(1080, 1920, 540, 360), (203, 360));
        assert_eq!(fit_size(1920, 1080, 540, 360), (540, 304));
        assert_eq!(fit_size(0, 1920, 540, 360), (0, 0));
    }

    #[test]
    fn video_config_rejects_truncated_zero_and_unbounded_dimensions() {
        assert!(decode_status(&[T_VIDEO_CONFIG, 0, 0, 0, 0]).is_err());
        assert!(decode_status(&[T_VIDEO_CONFIG, 0, 0, 0, 2, 30]).is_err());
        assert!(decode_status(&[T_VIDEO_CONFIG, 0x20, 1, 0, 2, 30]).is_err());
        assert_eq!(
            decode_status(&[T_VIDEO_CONFIG, 4, 0x38, 7, 0x80, 30]).unwrap(),
            (1080, 1920, 30)
        );
    }

    #[test]
    fn fresh_code_accepts() {
        assert_eq!(pairing_status(&shared(false), peer(2), &[7; 16]), STATUS_OK);
    }

    #[test]
    fn expired_code_rejects() {
        assert_eq!(
            pairing_status(&shared(true), peer(2), &[7; 16]),
            STATUS_EXPIRED
        );
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
