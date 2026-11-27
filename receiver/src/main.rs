#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
// ViewGo receiver: pairs with an Android sender, decrypts H.264 frames, decodes them with
// ffmpeg and shows the newest frame in a native egui window (white/green, per design-taste).

use std::collections::{HashMap, VecDeque};
use std::env;
use std::io::{BufReader, BufWriter, Read, Write};
use std::net::{IpAddr, Ipv4Addr, TcpListener, TcpStream, UdpSocket};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU64, AtomicU8, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};
use viewgo_receiver::protocol::*;

use eframe::egui;
use mdns_sd::{ServiceDaemon, ServiceInfo};

const DEFAULT_PORT: u16 = 47810;
const DEFAULT_VOLUME: u8 = 70;
const CODE_TTL: Duration = Duration::from_secs(120);
const ATTEMPT_WINDOW: Duration = Duration::from_secs(60);
const MAX_ATTEMPTS_PER_IP: u32 = 5;
const LOG_CAP: usize = 400;

// A single log the GUI panel reads. Writes to stdout are ignored so a release build (windowed
// subsystem, no console) never panics on a closed handle.
static LOG: OnceLock<Mutex<VecDeque<String>>> = OnceLock::new();

fn log(text: impl Into<String>) {
    let text = text.into();
    let _ = writeln!(std::io::stdout(), "{text}");
    let mut guard = LOG
        .get_or_init(|| Mutex::new(VecDeque::new()))
        .lock()
        .unwrap();
    guard.push_back(text);
    while guard.len() > LOG_CAP {
        guard.pop_front();
    }
}

fn log_lines() -> Vec<String> {
    LOG.get_or_init(|| Mutex::new(VecDeque::new()))
        .lock()
        .unwrap()
        .iter()
        .cloned()
        .collect()
}

/// Stops the current session from this window: shutting the socket down makes serve() end
/// cleanly, which resets the status and issues a fresh pairing code. The phone sees the closed
/// socket and stops capturing.
fn stop_session(shared: &Shared) {
    // try_clone duplicates the handle (TcpStream is not Clone) and the guard is dropped before
    // blocking on the shutdown call.
    let handle = {
        let guard = shared.client.lock().unwrap();
        guard.as_ref().and_then(|stream| stream.try_clone().ok())
    };
    if let Some(stream) = handle {
        log("Stopping the session from this window".to_string());
        let _ = stream.shutdown(std::net::Shutdown::Both);
    }
}

struct Shared {
    code: Mutex<(String, Instant)>,
    active: Mutex<bool>,
    attempts: Mutex<HashMap<IpAddr, (u32, Instant)>>,
    seen_randoms: Mutex<Vec<[u8; 16]>>,
    status: Mutex<String>,
    /// When the current session started, for the idle/quality readout.
    started: Mutex<Option<Instant>>,
    /// The live client socket, so the window can stop a session from the laptop.
    client: Mutex<Option<TcpStream>>,
    /// Phone audio gain in percent; read for every PCM chunk so the slider applies live.
    volume: AtomicU8,
    /// Frames the window actually painted. The status compares it with the decode rate so a slow
    /// display path is visible instead of looking like a slow network.
    drawn: AtomicU64,
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
    /// Bounded PCM queue. The writer thread keeps ffplay fed without ever blocking the network
    /// loop; when the queue is full the newest chunk is dropped instead of stalling video.
    sender: Option<std::sync::mpsc::SyncSender<Vec<u8>>>,
    writer: Option<thread::JoinHandle<()>>,
}

/// 44-byte PCM WAV header written once before the first PCM frame: it carries the sample rate and
/// channel count that raw s16le over a pipe cannot express. Chunk sizes are 0xFFFFFFFF because the
/// stream has no length; ffplay reads until the pipe closes.
fn wav_header(sample_rate: u32, channels: u8) -> [u8; 44] {
    const BITS: u16 = 16;
    let block_align = channels as u16 * (BITS / 8);
    let byte_rate = sample_rate * block_align as u32;
    let mut header = [0u8; 44];
    header[0..4].copy_from_slice(b"RIFF");
    header[4..8].copy_from_slice(&u32::MAX.to_le_bytes());
    header[8..12].copy_from_slice(b"WAVE");
    header[12..16].copy_from_slice(b"fmt ");
    header[16..20].copy_from_slice(&16u32.to_le_bytes());
    header[20..22].copy_from_slice(&1u16.to_le_bytes());
    header[22] = channels;
    header[24..28].copy_from_slice(&sample_rate.to_le_bytes());
    header[28..32].copy_from_slice(&byte_rate.to_le_bytes());
    header[32..34].copy_from_slice(&block_align.to_le_bytes());
    header[34..36].copy_from_slice(&BITS.to_le_bytes());
    header[36..40].copy_from_slice(b"data");
    header[40..44].copy_from_slice(&u32::MAX.to_le_bytes());
    header
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
        started: Mutex::new(None),
        client: Mutex::new(None),
        volume: AtomicU8::new(DEFAULT_VOLUME),
        drawn: AtomicU64::new(0),
    });

    let latest = Arc::new(Mutex::new(Latest::new()));
    let spares: Arc<Mutex<Vec<Vec<u8>>>> = Arc::new(Mutex::new(Vec::new()));
    let frame_seq = Arc::new(AtomicU64::new(0));

    let listener = match TcpListener::bind(("0.0.0.0", port)) {
        Ok(listener) => Some(listener),
        Err(error) => {
            // Still open the window so the failure is readable instead of exiting silently.
            shared.set_status(format!("Cannot listen on port {port}: {error}"));
            log(format!("Cannot listen on port {port}: {error}"));
            None
        }
    };

    let code = shared.issue_code();
    let addresses = local_ipv4_addresses();
    let _mdns = publish_receiver(port, &addresses);
    log(format!("ViewGo receiver listening on port {port}"));
    if addresses.is_empty() {
        log("No IPv4 address found; falling back to automatic mDNS addresses");
    } else {
        log(format!(
            "Local IPv4: {}",
            addresses
                .iter()
                .map(|ip| ip.to_string())
                .collect::<Vec<_>>()
                .join(", ")
        ));
    }
    log(format!("Pairing code: {code}  (valid 120 seconds)"));

    if let Some(listener) = listener {
        let shared = Arc::clone(&shared);
        let latest = Arc::clone(&latest);
        let spares = Arc::clone(&spares);
        let frame_seq = Arc::clone(&frame_seq);
        thread::spawn(move || accept_loop(listener, shared, latest, spares, frame_seq));
    }

    run_gui(
        shared,
        latest,
        spares,
        frame_seq,
        port,
        primary_ipv4(&addresses),
    );
}

fn accept_loop(
    listener: TcpListener,
    shared: Arc<Shared>,
    latest: Arc<Mutex<Latest>>,
    spares: Arc<Mutex<Vec<Vec<u8>>>>,
    frame_seq: Arc<AtomicU64>,
) {
    for stream in listener.incoming() {
        let mut stream = match stream {
            Ok(stream) => stream,
            Err(_) => continue,
        };
        if *shared.active.lock().unwrap() {
            // Reply with a status instead of closing silently: without it the phone can only
            // report an EOFException with no reason.
            let mut reject = Vec::with_capacity(38);
            reject.extend_from_slice(MAGIC);
            reject.push(VERSION);
            reject.push(STATUS_BUSY);
            reject.extend_from_slice(&random_bytes::<16>());
            reject.extend_from_slice(&random_bytes::<16>());
            let _ = write_frame(&mut stream, &reject);
            let _ = stream.flush();
            log("Rejected pairing (laptop already has a session)".to_string());
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
                log(format!("Session ended: {error}"));
            }
            *shared.active.lock().unwrap() = false;
            *shared.started.lock().unwrap() = None;
            *shared.client.lock().unwrap() = None;
            shared.set_status("Waiting for a phone");
            let code = shared.issue_code();
            log(format!("Pairing code: {code}  (valid 120 seconds)"));
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
    // Expose a handle to the live socket so the window can stop this session.
    *shared.client.lock().unwrap() = Some(stream.try_clone()?);

    let mut builder = FfmpegBuilder::new(
        Arc::clone(&latest),
        Arc::clone(&spares),
        Arc::clone(&frame_seq),
    );
    let mut video_config: Option<(u16, u16)> = None;
    let mut received = 0u64;
    let mut window_start = Instant::now();
    let mut frames_in_window = 0u32;
    let mut bytes_in_window = 0u64;
    let mut last_seq = frame_seq.load(Ordering::Relaxed);
    let mut last_drawn = shared.drawn.load(Ordering::Relaxed);
    let mut last_status_log = Instant::now();

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
                builder.start(width, height)?;
                if dimensions_changed {
                    received = 0;
                    window_start = Instant::now();
                    frames_in_window = 0;
                    bytes_in_window = 0;
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
                // Video bytes only: the status shows whether the link actually delivers the
                // bitrate the phone asked for, which separates a slow network from a slow encoder.
                bytes_in_window += plain.len() as u64;
                if builder.write(&plain[10..]).is_err() {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::BrokenPipe,
                        "decoder stopped",
                    ));
                }
                if window_start.elapsed() >= Duration::from_secs(1) {
                    let elapsed = window_start.elapsed();
                    let in_fps = frames_in_window as f64 / elapsed.as_secs_f64();
                    // frame_seq is bumped by the decoder thread, so this is the decode rate: when
                    // it lags the input rate the queue is growing and delay accumulates.
                    let now_seq = frame_seq.load(Ordering::Relaxed);
                    let dec_fps = now_seq.saturating_sub(last_seq) as f64 / elapsed.as_secs_f64();
                    last_seq = now_seq;
                    // The window bumps `drawn` for every frame it actually painted. A decode rate
                    // at the input rate with a lower draw rate means the display path is the
                    // bottleneck, not the network or the decoder.
                    let now_drawn = shared.drawn.load(Ordering::Relaxed);
                    let draw_fps =
                        now_drawn.saturating_sub(last_drawn) as f64 / elapsed.as_secs_f64();
                    last_drawn = now_drawn;
                    let mbps = bytes_in_window as f64 * 8.0 / elapsed.as_secs_f64() / 1_000_000.0;
                    let (w, h) = video_config.unwrap();
                    let line = format!(
                        "{peer} — {w}x{h} — in {in_fps:.0} / dec {dec_fps:.0} / draw {draw_fps:.0} fps — {mbps:.1} Mbps"
                    );
                    shared.set_status(line.clone());
                    // A periodic copy in the log lets a session be diagnosed afterwards, when the
                    // live status line is already replaced.
                    if last_status_log.elapsed() >= Duration::from_secs(10) {
                        last_status_log = Instant::now();
                        log(line);
                    }
                    frames_in_window = 0;
                    bytes_in_window = 0;
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
                // No availability probe: ffplay tells us itself by exiting, which is reported
                // once from write_audio while the video keeps running.
                if let Err(error) = builder.reset_audio(sample_rate, channels) {
                    log(format!(
                        "Audio output unavailable (is ffplay installed?): {error}"
                    ));
                }
            }
            T_AUDIO_FRAME => {
                if plain.len() > 1 && plain.len() <= 16 * 1024 {
                    let volume = shared.volume.load(Ordering::Relaxed);
                    builder.write_audio(&plain[1..], volume);
                }
            }
            T_LOG => {
                if plain.len() > 8193 {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::InvalidData,
                        "log too long",
                    ));
                }
                log(format!("phone: {}", String::from_utf8_lossy(&plain[1..])));
            }
            T_STOP => break,
            T_CONTROL => {}
            _ => {}
        }
    }
    log(format!("Session closed after {received} frames"));
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
        log(format!("Rejected pairing from {peer} (status {status})"));
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
        // Tell the phone why instead of closing (it would only ever see EOF).
        let mut reject = Vec::with_capacity(38);
        reject.extend_from_slice(MAGIC);
        reject.push(VERSION);
        reject.push(STATUS_BAD_CODE);
        reject.extend_from_slice(&salt);
        reject.extend_from_slice(&server_random);
        let _ = write_frame(stream, &reject);
        let _ = stream.flush();
        log(format!("Rejected pairing from {peer} (wrong code)"));
        return Err(std::io::Error::new(
            std::io::ErrorKind::PermissionDenied,
            "wrong code",
        ));
    }

    *shared.active.lock().unwrap() = true;
    *shared.started.lock().unwrap() = Some(Instant::now());
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
    log(format!("Paired with {peer}"));
    Ok(Handshake { key })
}

struct FfmpegBuilder {
    process: Option<Ffmpeg>,
    audio: Option<AudioPlayer>,
    /// Stream size the current decoder was started for, so a repeated config does not restart it.
    dimensions: Option<(usize, usize)>,
    /// Stream size to rebuild the decoder with after a hardware-decode failure.
    stream: Option<(u16, u16)>,
    /// Whether the decoder may use the platform hardware decoder. Cleared for good once a
    /// hardware attempt fails; `VIEWGO_HWACCEL=0` starts without it.
    hwaccel: bool,
    latest: Arc<Mutex<Latest>>,
    spares: Arc<Mutex<Vec<Vec<u8>>>>,
    frame_seq: Arc<AtomicU64>,
    audio_dropped: u64,
}

/// ffmpeg command line for decoding the phone's Annex-B H.264 into `out_w`x`out_h` BGRA frames.
///
/// `hwaccel` adds `-hwaccel auto`, which lets ffmpeg pick the platform decoder (d3d11va on
/// Windows) and fall back to software when the driver offers nothing usable. Measured on a
/// 1080x2400 High profile stream: 250 fps decoded instead of 154 fps in software, with
/// byte-identical output.
fn decoder_args(hwaccel: bool, out_w: usize, out_h: usize) -> Vec<String> {
    let mut args: Vec<String> = ["-hide_banner", "-loglevel", "error"]
        .map(String::from)
        .to_vec();
    if hwaccel {
        args.extend(["-hwaccel", "auto"].map(String::from));
    }
    args.extend(
        [
            // NOTE: -fflags nobuffer was tried here and silently produced zero rawvideo output on a
            // piped Annex-B stream (verified: 0 bytes vs 1 frame without it), so it must not come
            // back. Latency is bounded elsewhere: small socket buffer + drop-stale.
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
            "-vf",
            &format!("scale={out_w}:{out_h}"),
            "-f",
            "rawvideo",
            "-pix_fmt",
            "bgra",
            "pipe:1",
        ]
        .map(String::from),
    );
    args
}

impl FfmpegBuilder {
    fn new(
        latest: Arc<Mutex<Latest>>,
        spares: Arc<Mutex<Vec<Vec<u8>>>>,
        frame_seq: Arc<AtomicU64>,
    ) -> Self {
        Self {
            process: None,
            audio: None,
            dimensions: None,
            stream: None,
            hwaccel: cfg!(windows)
                && env::var("VIEWGO_HWACCEL")
                    .map(|value| value != "0")
                    .unwrap_or(true),
            latest,
            spares,
            frame_seq,
            audio_dropped: 0,
        }
    }

    fn start(&mut self, width: u16, height: u16) -> std::io::Result<()> {
        if self.process.is_some() {
            if self.dimensions == Some((width as usize, height as usize)) {
                return Ok(());
            }
            drop(self.process.take());
            self.dimensions = None;
        }
        let latest_for_reader = Arc::clone(&self.latest);
        let spares_for_reader = Arc::clone(&self.spares);
        let frame_seq_for_reader = Arc::clone(&self.frame_seq);
        let ffmpeg = env::var("FFMPEG").unwrap_or_else(|_| "ffmpeg".to_string());
        // The pipe carries raw BGRA, so a 1080x2400 phone frame is ~10 MB: at 30 fps that is
        // ~310 MB/s through ffmpeg stdout, the reader thread and the buffer pool, which is what
        // makes the picture stutter on a modest laptop. Decode down to at most DECODE_MAX on the
        // long side and let the mirror window scale the (much smaller) frame the rest of the way.
        // scale=WxH also pins the rawvideo output to exactly the dimensions the reader consumes:
        // an encoder that rounds a non-macroblock-aligned size (e.g. 1080 -> 1088) would otherwise
        // shift every frame and garble the picture.
        let (out_w, out_h) = decode_size(width as usize, height as usize);
        let mut child = Command::new(ffmpeg)
            .args(decoder_args(self.hwaccel, out_w, out_h))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()?;

        let stdin = child.stdin.take().unwrap();
        let stdout = child.stdout.take().unwrap();
        let (w, h) = (out_w, out_h);

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
        // Remember the stream size (not the decoded size) so a repeated config for the same
        // stream does not restart the decoder, and so a failed hardware start can be retried in
        // software at the same size.
        self.dimensions = Some((width as usize, height as usize));
        self.stream = Some((width, height));
        Ok(())
    }

    fn write(&mut self, annex_b: &[u8]) -> std::io::Result<()> {
        let outcome = match self.process.as_mut() {
            Some(process) => process
                .stdin
                .write_all(annex_b)
                .and_then(|_| process.stdin.flush()),
            None => return Ok(()),
        };
        let Err(error) = outcome else {
            return Ok(());
        };
        self.process = None;
        // A hardware decoder that the driver advertises but cannot run exits at the first frame.
        // Software decoding keeps the session alive instead of ending it; the picture recovers at
        // the next keyframe (the phone sends one every two seconds).
        if let Some((width, height)) = self.software_retry() {
            log("Hardware video decode failed; continuing in software");
            self.start(width, height)?;
            return Ok(());
        }
        Err(error)
    }

    /// Software fallback after a hardware decode failure: returns the stream size to restart the
    /// decoder with, at most once.
    fn software_retry(&mut self) -> Option<(u16, u16)> {
        if !self.hwaccel {
            return None;
        }
        self.hwaccel = false;
        self.stream
    }

    fn start_audio(&mut self, sample_rate: u32, channels: u8) -> std::io::Result<()> {
        if self.audio.is_some() {
            return Ok(());
        }
        if !(8_000..=192_000).contains(&sample_rate) || !(1..=2).contains(&channels) {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "unsupported audio format",
            ));
        }
        let ffplay = env::var("FFPLAY").unwrap_or_else(|_| "ffplay".to_string());
        let mut child = Command::new(ffplay)
            .args([
                // ffplay takes the input as a positional argument: it has no `-i`, and `-ac` /
                // `-channel_layout` are not valid input options in the ffplay builds on PATH.
                "-nodisp",
                "-autoexit",
                "-loglevel",
                "error",
                "-fflags",
                "nobuffer",
                "-f",
                "wav",
                "pipe:0",
            ])
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .spawn()?;
        let mut stdin = BufWriter::with_capacity(
            16 * 1024,
            child.stdin.take().ok_or_else(|| {
                std::io::Error::new(std::io::ErrorKind::BrokenPipe, "ffplay stdin unavailable")
            })?,
        );
        // The header is what tells ffplay the sample rate and channel count; raw s16le over a
        // pipe would be read as mono. Sizes stay 0xFFFFFFFF because the stream has no length.
        if let Err(error) = stdin
            .write_all(&wav_header(sample_rate, channels))
            .and_then(|_| stdin.flush())
        {
            let _ = child.kill();
            return Err(error);
        }
        // 4 chunks ≈ 85 ms of audio: enough to ride out a hiccup, small enough that audio stays in
        // step with the picture. A full queue means playback is behind: drop new chunks rather
        // than block the network thread, which also carries video.
        let (sender, receiver) = std::sync::mpsc::sync_channel::<Vec<u8>>(4);
        let writer = thread::spawn(move || {
            while let Ok(pcm) = receiver.recv() {
                if stdin.write_all(&pcm).and_then(|_| stdin.flush()).is_err() {
                    break;
                }
            }
        });
        self.audio_dropped = 0;
        self.audio = Some(AudioPlayer {
            child,
            sender: Some(sender),
            writer: Some(writer),
        });
        Ok(())
    }

    fn reset_audio(&mut self, sample_rate: u32, channels: u8) -> std::io::Result<()> {
        self.kill_audio();
        self.start_audio(sample_rate, channels)
    }

    fn write_audio(&mut self, pcm: &[u8], volume: u8) {
        let Some(audio) = self.audio.as_mut() else {
            return;
        };
        let Some(sender) = audio.sender.as_ref() else {
            return;
        };
        let mut chunk = pcm.to_vec();
        apply_gain(&mut chunk, volume);
        match sender.try_send(chunk) {
            Ok(()) => {}
            Err(std::sync::mpsc::TrySendError::Full(_)) => {
                self.audio_dropped += 1;
                if self.audio_dropped % 50 == 1 {
                    log("Audio output is behind; dropping audio chunks to keep video smooth");
                }
            }
            Err(std::sync::mpsc::TrySendError::Disconnected(_)) => {
                self.audio.take();
                log(
                    "Audio output stopped: ffplay exited or the Windows playback device is unavailable",
                );
            }
        }
    }

    fn kill_audio(&mut self) {
        // Dropping AudioPlayer closes the queue, kills ffplay and joins the writer thread.
        self.audio.take();
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
        // Closing the queue lets the writer finish; killing ffplay unblocks a writer stuck on a
        // full pipe, and the join keeps teardown deterministic.
        self.sender.take();
        let _ = self.child.kill();
        let _ = self.child.wait();
        if let Some(writer) = self.writer.take() {
            let _ = writer.join();
        }
    }
}

/// Applies a percentage gain to signed 16-bit little-endian PCM in place. 100% is untouched,
/// 0% is silence; values stay inside the i16 range because `volume <= 100`.
fn apply_gain(pcm: &mut [u8], volume: u8) {
    let volume = volume.min(100);
    if volume >= 100 {
        return;
    }
    let volume = i32::from(volume);
    for sample in pcm.chunks_exact_mut(2) {
        let value = i32::from(i16::from_le_bytes([sample[0], sample[1]]));
        let scaled = (value * volume / 100) as i16;
        let bytes = scaled.to_le_bytes();
        sample[0] = bytes[0];
        sample[1] = bytes[1];
    }
}

fn run_gui(
    shared: Arc<Shared>,
    latest: Arc<Mutex<Latest>>,
    spares: Arc<Mutex<Vec<Vec<u8>>>>,
    frame_seq: Arc<AtomicU64>,
    port: u16,
    local_ipv4: Option<Ipv4Addr>,
) {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1120.0, 780.0])
            .with_min_inner_size([760.0, 540.0])
            .with_icon(app_icon()),
        ..Default::default()
    };
    if let Err(error) = eframe::run_native(
        "ViewGo",
        options,
        Box::new(move |cc| {
            ViewGoApp::init(cc, shared, latest, spares, frame_seq, port, local_ipv4)
        }),
    ) {
        log(format!("Cannot open window: {error}"));
    }
}

// Palette dealt by design-taste-frontend (`start.mjs brand --page software --bg #fff
// --accent #0f7b4f --radius soft`): white ground, green action.
const BG: egui::Color32 = egui::Color32::from_rgb(0xff, 0xff, 0xff);
const SURFACE: egui::Color32 = egui::Color32::from_rgb(0xf1, 0xf2, 0xf3);
const BORDER: egui::Color32 = egui::Color32::from_rgb(0xe4, 0xe7, 0xeb);
const INK: egui::Color32 = egui::Color32::from_rgb(0x10, 0x18, 0x28);
const MUTED: egui::Color32 = egui::Color32::from_rgb(0x5f, 0x67, 0x72);
const ACCENT: egui::Color32 = egui::Color32::from_rgb(0x0f, 0x7b, 0x4f);
const ACCENT_INK: egui::Color32 = egui::Color32::from_rgb(0xff, 0xff, 0xff);
const DANGER: egui::Color32 = egui::Color32::from_rgb(0xb4, 0x23, 0x18); // same red as the Android Stop

/// Stable id so OBS Window Capture keeps matching the title across repaints.
fn mirror_viewport() -> egui::ViewportId {
    egui::ViewportId::from_hash_of("viewgo-mirror")
}

/// Coverage ratios of the brand mark, shared with the Android launcher icon and
/// tools/make_icon.ps1 so every surface draws the same shape.
fn icon_inside_tile(x: f32, y: f32) -> bool {
    const PAD: f32 = 0.0625;
    const R: f32 = 0.175;
    rounded_rect(x, y, PAD, PAD, 1.0 - PAD, 1.0 - PAD, R)
}

fn icon_inside_screen(x: f32, y: f32) -> bool {
    // Matches tools/make_icon.ps1: screen is 0.58 x 0.40 of the tile, centred, top at 0.29.
    let tile = 0.875;
    let sw = tile * 0.58;
    let sh = tile * 0.40;
    let x0 = 0.0625 + (tile - sw) / 2.0;
    rounded_rect(x, y, x0, 0.29, x0 + sw, 0.29 + sh, sh * 0.16)
}

fn icon_inside_stand(x: f32, y: f32) -> bool {
    let tile = 0.875;
    let w = tile * 0.34;
    let x0 = 0.0625 + (tile - w) / 2.0;
    rounded_rect(x, y, x0, 0.67, x0 + w, 0.70, 0.01)
}

/// Point-in-rounded-rectangle in unit space.
fn rounded_rect(x: f32, y: f32, x0: f32, y0: f32, x1: f32, y1: f32, r: f32) -> bool {
    if x < x0 || x > x1 || y < y0 || y > y1 {
        return false;
    }
    let (ix0, ix1) = (x0 + r, x1 - r);
    let (iy0, iy1) = (y0 + r, y1 - r);
    if x >= ix0 && x <= ix1 || y >= iy0 && y <= iy1 {
        return true;
    }
    let cx = if x < ix0 { ix0 } else { ix1 };
    let cy = if y < iy0 { iy0 } else { iy1 };
    (x - cx).powi(2) + (y - cy).powi(2) <= r * r
}

/// 32x32 RGBA brand mark for the window and taskbar icon, drawn here so no asset file is needed.
fn app_icon() -> egui::IconData {
    const S: usize = 32;
    const SS: f32 = 4.0; // 4x4 supersamples per pixel
    let mut rgba = vec![0u8; S * S * 4];
    for y in 0..S {
        for x in 0..S {
            let mut cov_green = 0u32;
            let mut cov_white = 0u32;
            for sy in 0..4 {
                for sx in 0..4 {
                    let px = (x as f32 + sx as f32 / SS) / S as f32;
                    let py = (y as f32 + sy as f32 / SS) / S as f32;
                    if !icon_inside_tile(px, py) {
                        continue;
                    }
                    if icon_inside_screen(px, py) || icon_inside_stand(px, py) {
                        cov_white += 1;
                    } else {
                        cov_green += 1;
                    }
                }
            }
            let total = (cov_green + cov_white) as f32 / 16.0;
            if total <= 0.0 {
                continue; // stays transparent
            }
            let i = (y * S + x) * 4;
            // Blend white over green by how much of the pixel the glyph covers.
            let w = cov_white as f32 / (cov_green + cov_white).max(1) as f32;
            rgba[i] = (15.0 + (255.0 - 15.0) * w).round() as u8;
            rgba[i + 1] = (123.0 + (255.0 - 123.0) * w).round() as u8;
            rgba[i + 2] = (79.0 + (255.0 - 79.0) * w).round() as u8;
            rgba[i + 3] = (total * 255.0).round() as u8;
        }
    }
    egui::IconData {
        rgba,
        width: S as u32,
        height: S as u32,
    }
}

/// Target height for the mirror window; width follows the stream aspect so the picture fills it.
const MIRROR_H: f32 = 720.0;

struct ViewGoApp {
    shared: Arc<Shared>,
    latest: Arc<Mutex<Latest>>,
    spares: Arc<Mutex<Vec<Vec<u8>>>>,
    frame_seq: Arc<AtomicU64>,
    port: u16,
    /// IPv4 address shown to the user for manual entry on the phone.
    local_ipv4: Option<Ipv4Addr>,
    seen: u64,
    last: Option<(Vec<u8>, usize, usize)>,
    scaled: Vec<u32>,
    area: (usize, usize),
    tex_size: (usize, usize),
    texture: Option<egui::TextureHandle>,
    /// Last stream dimensions the mirror window was sized for.
    stream_size: Option<(u32, u32)>,
    /// Borderless mirror window (clean OBS capture). Toggling recreates the native window.
    borderless: bool,
    /// Counts frames until the mirror window is recreated after a toggle.
    recreate_in: u32,
    /// Throttles the "render is slow" log so a struggling laptop reports once per couple seconds.
    last_slow_render: Instant,
}

impl ViewGoApp {
    fn init(
        cc: &eframe::CreationContext<'_>,
        shared: Arc<Shared>,
        latest: Arc<Mutex<Latest>>,
        spares: Arc<Mutex<Vec<Vec<u8>>>>,
        frame_seq: Arc<AtomicU64>,
        port: u16,
        local_ipv4: Option<Ipv4Addr>,
    ) -> Result<Box<dyn eframe::App>, Box<dyn std::error::Error + Send + Sync>> {
        let mut visuals = egui::Visuals::light();
        visuals.panel_fill = BG;
        visuals.window_fill = BG;
        visuals.override_text_color = Some(INK);
        visuals.extreme_bg_color = SURFACE;
        visuals.faint_bg_color = SURFACE;
        visuals.selection.bg_fill = ACCENT;
        visuals.hyperlink_color = ACCENT;
        visuals.widgets.noninteractive.bg_fill = SURFACE;
        visuals.widgets.noninteractive.corner_radius = egui::CornerRadius::same(8);
        visuals.widgets.inactive.bg_fill = SURFACE;
        visuals.widgets.inactive.fg_stroke = egui::Stroke::new(1.0, INK);
        visuals.widgets.inactive.corner_radius = egui::CornerRadius::same(8);
        visuals.widgets.hovered.bg_fill = BORDER;
        visuals.widgets.hovered.corner_radius = egui::CornerRadius::same(8);
        visuals.widgets.active.bg_fill = ACCENT;
        visuals.widgets.active.fg_stroke = egui::Stroke::new(1.4, ACCENT_INK);
        visuals.widgets.active.corner_radius = egui::CornerRadius::same(8);
        cc.egui_ctx.set_visuals(visuals);

        // A laptop whose driver fell back to a software adapter paints the mirror slowly no matter
        // how fast the decode is; naming the adapter makes that visible instead of mysterious.
        if let Some(state) = &cc.wgpu_render_state {
            let info = state.adapter.get_info();
            log(format!(
                "Graphics: {} ({:?} via {:?})",
                info.name, info.device_type, info.backend
            ));
        }

        Ok(Box::new(ViewGoApp {
            shared,
            latest,
            spares,
            frame_seq,
            port,
            local_ipv4,
            seen: 0,
            last: None,
            scaled: Vec::new(),
            area: (0, 0),
            tex_size: (0, 0),
            texture: None,
            stream_size: None,
            borderless: false,
            recreate_in: 0,
            last_slow_render: Instant::now(),
        }))
    }

    /// Takes the newest decoded frame; returns true when one arrived.
    fn take_frame(&mut self) -> bool {
        if self.frame_seq.load(Ordering::Relaxed) == self.seen {
            return false;
        }
        let mut guard = self.latest.lock().unwrap();
        let Some(frame) = guard.frame.take() else {
            return false;
        };
        self.seen = guard.seq;
        let size = (guard.width, guard.height);
        drop(guard);
        if let Some((old, _, _)) = self.last.replace((frame, size.0, size.1)) {
            if let Ok(mut pool) = self.spares.lock() {
                if pool.len() < 3 {
                    pool.push(old);
                }
            }
        }
        true
    }

    /// Scales the newest frame into the mirror viewport's area and refreshes the texture.
    /// Kept in one method so the picture can be drawn from the dedicated mirror window.
    fn update_picture(&mut self, mirror_ui: &mut egui::Ui, ctx: &egui::Context) {
        let avail = mirror_ui.available_size();
        let area = ((avail.x.max(1.0)) as usize, (avail.y.max(1.0)) as usize);
        let fresh = self.take_frame();
        // Follow the share screen: keep the mirror window at 720px tall and let the width track the
        // stream aspect, so a landscape game fills the window instead of a strip in a portrait box.
        if let Some((fw, fh)) = self.last.as_ref().map(|(_, w, h)| (*w as u32, *h as u32)) {
            if fw > 0 && fh > 0 && self.stream_size != Some((fw, fh)) {
                self.stream_size = Some((fw, fh));
                let width = (f64::from(MIRROR_H) * f64::from(fw) / f64::from(fh))
                    .round()
                    .clamp(320.0, 1600.0) as f32;
                ctx.send_viewport_cmd_to(
                    mirror_viewport(),
                    egui::ViewportCommand::InnerSize(egui::vec2(width, MIRROR_H)),
                );
            }
        }
        if (fresh || self.area != area) && self.last.is_some() {
            let started = Instant::now();
            let (frame, fw, fh) = self.last.as_ref().unwrap();
            let (dw, dh) = fit_size(*fw, *fh, area.0, area.1);
            if dw > 0 && dh > 0 {
                if self.scaled.len() != dw * dh {
                    self.scaled.resize(dw * dh, 0);
                }
                self.scaled.fill(0);
                scale_fit_bgra(frame, *fw, *fh, &mut self.scaled, dw, dh);
                let after_scale = started.elapsed();
                let pixels: Vec<egui::Color32> = self
                    .scaled
                    .iter()
                    .map(|&p| {
                        egui::Color32::from_rgb(
                            ((p >> 16) & 0xff) as u8,
                            ((p >> 8) & 0xff) as u8,
                            (p & 0xff) as u8,
                        )
                    })
                    .collect();
                let after_convert = started.elapsed();
                let image = egui::ColorImage {
                    size: [dw, dh],
                    source_size: egui::vec2(dw as f32, dh as f32),
                    pixels,
                };
                match &mut self.texture {
                    Some(handle) => handle.set(image, egui::TextureOptions::LINEAR),
                    None => {
                        self.texture =
                            Some(ctx.load_texture("mirror", image, egui::TextureOptions::LINEAR))
                    }
                }
                self.tex_size = (dw, dh);
                self.area = area;
                self.shared.drawn.fetch_add(1, Ordering::Relaxed);
                // A render that takes longer than a frame budget shows up as stutter on screen.
                // Report it (throttled) instead of silently dropping frames.
                let elapsed = started.elapsed();
                if elapsed > Duration::from_millis(33)
                    && self.last_slow_render.elapsed() > Duration::from_secs(2)
                {
                    self.last_slow_render = Instant::now();
                    log(format!(
                        "Render is slow ({} ms for {dw}x{dh} from {fw}x{fh}: scale {} ms, convert {} ms, upload {} ms); lower the mirror window size if video stutters",
                        elapsed.as_millis(),
                        after_scale.as_millis(),
                        (after_convert - after_scale).as_millis(),
                        (elapsed - after_convert).as_millis(),
                    ));
                }
            }
        }
    }
}

impl eframe::App for ViewGoApp {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        // Owned clone so the panel closures can use it while `ui` is borrowed.
        let ctx = ui.ctx().clone();
        let status = self.shared.status.lock().unwrap().clone();
        let code = self.shared.current_code();
        let active = *self.shared.active.lock().unwrap();
        // Keep status, code and log live even when no video frames arrive. While mirroring, ask a
        // little more often than the display refresh: a 16 ms timer drifts against a 16.7 ms vsync
        // and occasionally misses one, which shows up as 60 fps jittering to 30.
        ctx.request_repaint_after(Duration::from_millis(if active { 8 } else { 16 }));
        let port = self.port;
        let local_ipv4 = self.local_ipv4;

        egui::Panel::top("header").exact_size(60.0).show(ui, |ui| {
            ui.horizontal_centered(|ui| {
                let (rect, _) =
                    ui.allocate_exact_size(egui::vec2(32.0, 32.0), egui::Sense::hover());
                paint_mark(ui.painter(), rect);
                ui.add_space(10.0);
                ui.label(egui::RichText::new("ViewGo").size(20.0).strong().color(INK));
                ui.add_space(14.0);
                badge(ui, active);
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.button("Quit").clicked() {
                        ctx.send_viewport_cmd(egui::ViewportCommand::Close);
                    }
                    ui.label(
                        egui::RichText::new(format!("port {port}"))
                            .size(13.0)
                            .color(MUTED),
                    );
                });
            });
        });

        egui::Panel::bottom("log")
            .resizable(true)
            .default_size(150.0)
            .min_size(90.0)
            .show(ui, |ui| {
                ui.add_space(6.0);
                ui.horizontal(|ui| {
                    ui.label(egui::RichText::new("Log").size(12.0).color(MUTED));
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        ui.label(
                            egui::RichText::new("phone logs and receiver events")
                                .size(11.0)
                                .color(MUTED),
                        );
                    });
                });
                ui.add_space(2.0);
                egui::ScrollArea::vertical()
                    .stick_to_bottom(true)
                    .auto_shrink([false, false])
                    .show(ui, |ui| {
                        for line in log_lines() {
                            ui.label(
                                egui::RichText::new(line)
                                    .monospace()
                                    .size(12.0)
                                    .color(MUTED),
                            );
                        }
                    });
            });

        egui::Panel::right("side").exact_size(334.0).show(ui, |ui| {
            card(ui, |ui| {
                ui.label(egui::RichText::new("Pairing code").size(13.0).color(MUTED));
                ui.add_space(6.0);
                ui.label(
                    egui::RichText::new(code)
                        .monospace()
                        .size(54.0)
                        .strong()
                        .color(INK),
                );
                ui.add_space(2.0);
                ui.label(
                    egui::RichText::new("Valid 120 seconds, single use")
                        .size(12.0)
                        .color(MUTED),
                );
                ui.add_space(10.0);
                match local_ipv4 {
                    Some(ip) => {
                        ui.label(
                            egui::RichText::new("Laptop address")
                                .size(12.0)
                                .color(MUTED),
                        );
                        ui.label(
                            egui::RichText::new(format!("{ip}:{port}"))
                                .monospace()
                                .size(14.0)
                                .color(INK),
                        );
                        ui.label(
                            egui::RichText::new(
                                "Type this in the phone's Laptop IP field if search fails.",
                            )
                            .size(11.0)
                            .color(MUTED),
                        );
                    }
                    None => {
                        ui.label(
                            egui::RichText::new("No IPv4 address detected")
                                .size(12.0)
                                .color(DANGER),
                        );
                    }
                }
            });
            ui.add_space(14.0);
            card(ui, |ui| {
                ui.label(egui::RichText::new("Session").size(13.0).color(MUTED));
                ui.add_space(6.0);
                ui.label(egui::RichText::new(status).size(15.0).color(INK));
                if active {
                    ui.add_space(10.0);
                    if ui
                        .add_sized(
                            [ui.available_width(), 36.0],
                            egui::Button::new(
                                egui::RichText::new("Stop sharing")
                                    .strong()
                                    .color(egui::Color32::WHITE),
                            )
                            .fill(DANGER),
                        )
                        .clicked()
                    {
                        stop_session(&self.shared);
                    }
                }
            });
            ui.add_space(14.0);
            card(ui, |ui| {
                ui.label(egui::RichText::new("Audio").size(13.0).color(MUTED));
                ui.add_space(6.0);
                let mut volume = self.shared.volume.load(Ordering::Relaxed);
                if ui
                    .add(egui::Slider::new(&mut volume, 0..=100).suffix("%"))
                    .changed()
                {
                    self.shared.volume.store(volume, Ordering::Relaxed);
                }
                ui.add_space(4.0);
                ui.label(
                    egui::RichText::new("Phone sound; changes apply while mirroring.")
                        .size(11.0)
                        .color(MUTED),
                );
            });
            ui.add_space(14.0);
            card(ui, |ui| {
                ui.label(
                    egui::RichText::new("How to connect")
                        .size(13.0)
                        .color(MUTED),
                );
                ui.add_space(8.0);
                ui.label(
                    egui::RichText::new("1.  Open ViewGo on your phone.")
                        .size(14.0)
                        .color(INK),
                );
                ui.label(
                    egui::RichText::new(
                        "2.  Tap the search icon, or type the laptop address above.",
                    )
                    .size(14.0)
                    .color(INK),
                );
                ui.label(
                    egui::RichText::new("3.  Type the code above, then Connect.")
                        .size(14.0)
                        .color(INK),
                );
            });
            ui.add_space(14.0);
            card(ui, |ui| {
                ui.label(egui::RichText::new("OBS capture").size(13.0).color(MUTED));
                ui.add_space(8.0);
                let mut borderless = self.borderless;
                if ui
                    .checkbox(&mut borderless, "Borderless mirror window")
                    .changed()
                {
                    self.borderless = borderless;
                    let _ =
                        ctx.send_viewport_cmd_to(mirror_viewport(), egui::ViewportCommand::Close);
                    // Give the close a few frames before reopening with the new style.
                    self.recreate_in = 3;
                }
                ui.add_space(4.0);
                ui.label(
                    egui::RichText::new("On: clean picture for OBS. Off: movable and maximizable.")
                        .size(12.0)
                        .color(MUTED),
                );
            });
        });

        // The picture lives in its own window: movable and maximizable by default, or borderless
        // for a clean OBS capture (toggle in the side panel; that recreates the native window).
        let mirror = mirror_viewport();
        if self.recreate_in > 0 {
            self.recreate_in -= 1;
            if self.recreate_in == 0 {
                // Re-fit to the stream once it shows again.
                self.stream_size = None;
            }
        } else {
            let builder = egui::ViewportBuilder::default()
                .with_title("ViewGo Mirror")
                .with_decorations(!self.borderless)
                .with_icon(app_icon())
                .with_inner_size([540.0, MIRROR_H])
                .with_min_inner_size([240.0, 320.0]);
            ctx.show_viewport_immediate(mirror, builder, |mirror_ui, _| {
                self.update_picture(mirror_ui, &ctx);
                mirror_ui.with_layout(
                    egui::Layout::centered_and_justified(egui::Direction::TopDown),
                    |ui| match (&self.texture, self.tex_size) {
                        (Some(tex), (dw, dh)) if dw > 0 && dh > 0 => {
                            let size = egui::vec2(dw as f32, dh as f32);
                            ui.add(egui::Image::new((tex.id(), size)).fit_to_exact_size(size));
                        }
                        _ => {
                            ui.vertical_centered(|ui| {
                                ui.add_space(40.0);
                                ui.label(
                                    egui::RichText::new("Waiting for a phone")
                                        .size(24.0)
                                        .color(MUTED),
                                );
                                ui.add_space(10.0);
                                ui.label(
                                    egui::RichText::new(
                                        "Type the pairing code on your phone, on the same Wi-Fi.",
                                    )
                                    .size(14.0)
                                    .color(MUTED),
                                );
                            });
                        }
                    },
                );
            });
        }

        let since = self
            .shared
            .started
            .lock()
            .unwrap()
            .map(|t| t.elapsed().as_secs());

        egui::CentralPanel::default().show(ui, |ui| {
            ui.with_layout(
                egui::Layout::centered_and_justified(egui::Direction::TopDown),
                |ui| {
                    ui.vertical_centered(|ui| {
                        let (rect, _) =
                            ui.allocate_exact_size(egui::vec2(460.0, 170.0), egui::Sense::hover());
                        paint_flow(ui.painter(), rect);
                        ui.add_space(26.0);
                        ui.label(
                            egui::RichText::new("The picture is in the ViewGo Mirror window")
                                .size(24.0)
                                .strong()
                                .color(INK),
                        );
                        ui.add_space(14.0);
                        ui.label(
                            egui::RichText::new(
                                "Point OBS at that window and show it fullscreen.\nThis window keeps the pairing code, session status and log.",
                            )
                            .size(15.0)
                            .color(MUTED),
                        );
                        if let Some(secs) = since {
                            ui.add_space(20.0);
                            chip(
                                ui,
                                &format!("Session {:02}:{:02}", secs / 60, secs % 60),
                                true,
                            );
                        }
                    });
                },
            );
        });
    }
}

fn card(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui)) {
    let style = ui.style().clone();
    egui::Frame::group(&style)
        .fill(BG)
        .stroke(egui::Stroke::new(1.0, BORDER))
        .corner_radius(egui::CornerRadius::same(12))
        .inner_margin(egui::Margin::same(16))
        .show(ui, add);
    ui.add_space(2.0);
}

fn chip(ui: &mut egui::Ui, label: &str, accent: bool) {
    let colour = if accent { ACCENT_INK } else { MUTED };
    let galley =
        ui.painter()
            .layout_no_wrap(label.to_string(), egui::FontId::proportional(13.0), colour);
    let pad = egui::vec2(13.0, 7.0);
    let size = galley.size() + pad * 2.0;
    let (rect, _) = ui.allocate_exact_size(size, egui::Sense::hover());
    ui.painter().rect_filled(
        rect,
        egui::CornerRadius::same((size.y / 2.0) as u8),
        if accent { ACCENT } else { SURFACE },
    );
    ui.painter().galley(rect.min + pad, galley, colour);
}

fn badge(ui: &mut egui::Ui, active: bool) {
    chip(
        ui,
        if active {
            "Mirroring"
        } else {
            "Waiting for a phone"
        },
        active,
    );
}

fn paint_mark(painter: &egui::Painter, rect: egui::Rect) {
    painter.rect_filled(rect, egui::CornerRadius::same(9), ACCENT);
    let screen = egui::Rect::from_center_size(
        egui::pos2(rect.center().x, rect.center().y - rect.height() * 0.06),
        egui::vec2(rect.width() * 0.56, rect.height() * 0.40),
    );
    painter.rect_filled(screen, egui::CornerRadius::same(2), BG);
    let stand = egui::Rect::from_center_size(
        egui::pos2(rect.center().x, rect.max.y - rect.height() * 0.19),
        egui::vec2(rect.width() * 0.34, 2.0),
    );
    painter.rect_filled(stand, 0.0, BG);
}

/// Idle-state flow picture: phone -> Wi-Fi dots -> laptop showing the mirror window.
/// Drawn with the painter so it stays vector-sharp at any window size.
fn paint_flow(painter: &egui::Painter, rect: egui::Rect) {
    let cy = rect.center().y;

    // Phone on the left.
    let phone =
        egui::Rect::from_center_size(egui::pos2(rect.left() + 64.0, cy), egui::vec2(58.0, 106.0));
    painter.rect_filled(phone, egui::CornerRadius::same(12), BG);
    painter.rect_stroke(
        phone,
        egui::CornerRadius::same(12),
        egui::Stroke::new(1.6, BORDER),
        egui::StrokeKind::Inside,
    );
    painter.rect_filled(
        egui::Rect::from_center_size(
            egui::pos2(phone.center().x, phone.center().y - 5.0),
            egui::vec2(40.0, 66.0),
        ),
        egui::CornerRadius::same(4),
        ACCENT,
    );
    painter.rect_filled(
        egui::Rect::from_center_size(
            egui::pos2(phone.center().x, phone.max.y - 13.0),
            egui::vec2(18.0, 3.0),
        ),
        egui::CornerRadius::same(2),
        SURFACE,
    );

    // Laptop on the right, its screen is the green mirror window with the ViewGo glyph.
    let laptop = egui::Rect::from_center_size(
        egui::pos2(rect.right() - 98.0, cy - 6.0),
        egui::vec2(170.0, 104.0),
    );
    painter.rect_filled(laptop, egui::CornerRadius::same(8), BG);
    painter.rect_stroke(
        laptop,
        egui::CornerRadius::same(8),
        egui::Stroke::new(1.6, BORDER),
        egui::StrokeKind::Inside,
    );
    let mirror = egui::Rect::from_center_size(
        egui::pos2(laptop.center().x, laptop.center().y - 3.0),
        egui::vec2(148.0, 80.0),
    );
    painter.rect_filled(mirror, egui::CornerRadius::same(4), ACCENT);
    painter.rect_filled(
        egui::Rect::from_center_size(
            egui::pos2(mirror.center().x, mirror.center().y - 6.0),
            egui::vec2(54.0, 34.0),
        ),
        egui::CornerRadius::same(3),
        BG,
    );
    painter.rect_filled(
        egui::Rect::from_center_size(
            egui::pos2(mirror.center().x, mirror.center().y + 24.0),
            egui::vec2(36.0, 3.0),
        ),
        egui::CornerRadius::same(2),
        BG,
    );
    painter.line_segment(
        [
            egui::pos2(laptop.left() - 16.0, laptop.bottom() + 6.0),
            egui::pos2(laptop.right() + 16.0, laptop.bottom() + 6.0),
        ],
        egui::Stroke::new(2.4, BORDER),
    );

    // Wi-Fi dots travelling from the phone to the laptop.
    let start = phone.right() + 30.0;
    let end = laptop.left() - 30.0;
    for (i, radius) in [3.0f32, 4.5, 6.0].into_iter().enumerate() {
        let t = i as f32 / 2.0;
        let x = start + (end - start) * t;
        painter.circle_filled(
            egui::pos2(x, cy),
            radius,
            if i == 2 { ACCENT } else { SURFACE },
        );
    }
}

/// IPv4 addresses of the local interfaces, excluding loopback.
///
/// The receiver listens on `0.0.0.0` (IPv4 only). `enable_addr_auto()` would also advertise AAAA
/// records, and Android can then resolve the laptop over IPv6 and fail to connect. Enumerating the
/// same interfaces ourselves keeps the existing multi-NIC behaviour while publishing A records only.
fn local_ipv4_addresses() -> Vec<Ipv4Addr> {
    let mut addresses: Vec<Ipv4Addr> = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter(|interface| !interface.is_loopback())
        .filter_map(|interface| match interface.addr {
            if_addrs::IfAddr::V4(v4) if !v4.ip.is_unspecified() && !v4.ip.is_link_local() => {
                Some(v4.ip)
            }
            _ => None,
        })
        .collect();
    addresses.sort();
    addresses.dedup();
    addresses
}

/// The address Windows would use for outgoing traffic, shown in the window so it can be typed on
/// the phone. A UDP `connect` sends nothing; it only asks the routing table for a source address.
fn primary_ipv4(addresses: &[Ipv4Addr]) -> Option<Ipv4Addr> {
    let routed = UdpSocket::bind(("0.0.0.0", 0))
        .and_then(|socket| {
            // TEST-NET-1 (RFC 5737) keeps the dummy peer off the real internet; nothing is sent.
            socket.connect(("192.0.2.1", 9))?;
            socket.local_addr()
        })
        .ok()
        .and_then(|addr| match addr.ip() {
            IpAddr::V4(ip) if !ip.is_loopback() && !ip.is_unspecified() => Some(ip),
            _ => None,
        });
    routed.or_else(|| addresses.first().copied())
}

fn publish_receiver(port: u16, addresses: &[Ipv4Addr]) -> Option<ServiceDaemon> {
    let daemon = match ServiceDaemon::new() {
        Ok(daemon) => daemon,
        Err(error) => {
            log(format!("Local discovery unavailable: {error}"));
            return None;
        }
    };
    let info = if addresses.is_empty() {
        // Unusual (no IPv4 interface): keep auto-detection rather than publishing nothing.
        ServiceInfo::new(
            "_viewgo._tcp.local.",
            "ViewGo",
            "viewgo.local.",
            "0.0.0.0",
            port,
            &[("version", "1")][..],
        )
        .map(ServiceInfo::enable_addr_auto)
    } else {
        let ips: Vec<IpAddr> = addresses.iter().copied().map(IpAddr::V4).collect();
        ServiceInfo::new(
            "_viewgo._tcp.local.",
            "ViewGo",
            "viewgo.local.",
            &ips[..],
            port,
            &[("version", "1")][..],
        )
    };
    let info = match info {
        Ok(info) => info,
        Err(error) => {
            log(format!("Local discovery unavailable: {error}"));
            let _ = daemon.shutdown();
            return None;
        }
    };
    if let Err(error) = daemon.register(info) {
        log(format!("Local discovery unavailable: {error}"));
        let _ = daemon.shutdown();
        return None;
    }
    Some(daemon)
}

/// Longest side of the raw BGRA frame the decoder emits. The mirror window is 720 px tall by
/// default, so anything above 1080 is wasted pipe traffic; a maximized window upscales slightly
/// instead of stalling the stream.
const DECODE_MAX: usize = 1080;

/// Size ffmpeg should scale decoded frames to. Streams already within the cap keep their exact
/// size (no needless resample); larger ones are fitted, preserving aspect.
fn decode_size(src_w: usize, src_h: usize) -> (usize, usize) {
    if src_w == 0 || src_h == 0 {
        return (0, 0);
    }
    if src_w.max(src_h) <= DECODE_MAX {
        return (src_w, src_h);
    }
    fit_size(src_w, src_h, DECODE_MAX, DECODE_MAX)
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
    if frame.len() < src_w * src_h * 4 || out.len() < dst_w * dst_h {
        return;
    }
    let (draw_w, draw_h) = fit_size(src_w, src_h, dst_w, dst_h);
    let left = (dst_w - draw_w) / 2;
    let top = (dst_h - draw_h) / 2;
    // Downscaling by nearest-neighbour is what made text look soft and jagged; average the
    // covered source pixels instead. Upscaling uses bilinear so enlarged windows stay smooth.
    let upscale = draw_w > src_w || draw_h > src_h;

    // Scaling a 1080x2400 phone frame down to a ~324x720 window is the receiver's heaviest
    // per-frame CPU job. Split the destination rows across cores so it does not stall the UI.
    let workers = thread::available_parallelism()
        .map(|count| count.get())
        .unwrap_or(1)
        .clamp(1, 8)
        .min(draw_h);
    let rows_per_band = draw_h.div_ceil(workers);
    let region_start = top * dst_w;
    let region = &mut out[region_start..region_start + draw_h * dst_w];

    if workers == 1 {
        scale_band(
            frame, src_w, src_h, region, dst_w, 0, draw_h, draw_w, draw_h, left, upscale,
        );
        return;
    }
    std::thread::scope(|scope| {
        for (band_index, band) in region.chunks_mut(rows_per_band * dst_w).enumerate() {
            let y_start = band_index * rows_per_band;
            let rows = band.len() / dst_w;
            scope.spawn(move || {
                scale_band(
                    frame, src_w, src_h, band, dst_w, y_start, rows, draw_w, draw_h, left, upscale,
                );
            });
        }
    });
}

/// Scales destination rows `y_start..y_start + rows` of the fitted picture into `out`.
#[allow(clippy::too_many_arguments)]
fn scale_band(
    frame: &[u8],
    src_w: usize,
    src_h: usize,
    out: &mut [u32],
    dst_w: usize,
    y_start: usize,
    rows: usize,
    draw_w: usize,
    draw_h: usize,
    left: usize,
    upscale: bool,
) {
    for y in 0..rows {
        let sy = y_start + y;
        for x in 0..draw_w {
            let (b, g, r) = if upscale {
                let fx = (x as f64 + 0.5) * src_w as f64 / draw_w as f64 - 0.5;
                let fy = (sy as f64 + 0.5) * src_h as f64 / draw_h as f64 - 0.5;
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
                let y0 = sy * src_h / draw_h;
                let y1 = (((sy + 1) * src_h + draw_h - 1) / draw_h)
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
            out[y * dst_w + left + x] = (r << 16) | (g << 8) | b;
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
            code: Mutex::new(("AB3D".to_string(), expiry)),
            active: Mutex::new(false),
            attempts: Mutex::new(HashMap::new()),
            seen_randoms: Mutex::new(Vec::new()),
            status: Mutex::new(String::new()),
            started: Mutex::new(None),
            client: Mutex::new(None),
            volume: AtomicU8::new(DEFAULT_VOLUME),
            drawn: AtomicU64::new(0),
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
    fn decoder_args_pin_the_output_size_and_offer_hardware_decode() {
        let (w, h) = decode_size(1080, 2400);
        let hardware = decoder_args(true, w, h);
        let software = decoder_args(false, w, h);
        assert!(hardware.windows(2).any(|pair| pair == ["-hwaccel", "auto"]));
        assert!(!software.iter().any(|arg| arg == "-hwaccel"));
        // The reader thread assumes exactly this frame size, so the filter must not drift.
        assert!(hardware.iter().any(|arg| arg == "scale=486:1080"));
        assert!(hardware.iter().any(|arg| arg == "bgra"));
        assert!(hardware.iter().any(|arg| arg == "passthrough"));
    }

    #[test]
    fn a_failed_hardware_decoder_falls_back_to_software_once() {
        let mut builder = FfmpegBuilder::new(
            Arc::new(Mutex::new(Latest::new())),
            Arc::new(Mutex::new(Vec::new())),
            Arc::new(AtomicU64::new(0)),
        );
        builder.hwaccel = true;
        builder.stream = Some((1080, 2400));
        assert_eq!(builder.software_retry(), Some((1080, 2400)));
        assert!(!builder.hwaccel);
        assert_eq!(builder.software_retry(), None, "software is never retried");
    }

    #[test]
    fn app_icon_marks_the_brand() {
        let icon = app_icon();
        assert_eq!((icon.width, icon.height), (32, 32));
        assert_eq!(icon.rgba.len(), 32 * 32 * 4);
        let at = |x: usize, y: usize| &icon.rgba[(y * 32 + x) * 4..(y * 32 + x) * 4 + 4];
        // centre of the white screen glyph
        assert_eq!(at(16, 15), &[255, 255, 255, 255]);
        // green tile above the screen
        assert_eq!(at(16, 3), &[15, 123, 79, 255]);
        // rounded corner is masked out by the launcher
        assert_eq!(at(0, 0)[3], 0);
    }

    #[test]
    fn stop_session_shuts_the_client_socket() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let server = thread::spawn(move || listener.accept().unwrap().0);
        let client = std::net::TcpStream::connect(addr).unwrap();
        let accepted = server.join().unwrap();

        let shared = shared(false);
        *shared.client.lock().unwrap() = Some(client);
        stop_session(&shared);

        // The peer must see EOF: that is what ends serve() and resets the session.
        let mut buf = [0u8; 4];
        let mut reader = std::io::BufReader::new(accepted);
        assert_eq!(reader.read(&mut buf).unwrap(), 0);
        assert!(shared.client.lock().unwrap().is_some());
    }

    #[test]
    fn fit_viewport_preserves_portrait_and_landscape_aspect() {
        assert_eq!(fit_size(1080, 1920, 540, 360), (203, 360));
        assert_eq!(fit_size(1920, 1080, 540, 360), (540, 304));
        assert_eq!(fit_size(0, 1920, 540, 360), (0, 0));
    }

    #[test]
    fn decode_size_caps_the_long_side_without_upscaling_small_streams() {
        // 1080x2400 phone frame: 10 MB of raw BGRA per frame is what made video stutter.
        assert_eq!(decode_size(1080, 2400), (486, 1080));
        // Landscape phone or a small screen keeps its size exactly.
        assert_eq!(decode_size(1920, 1080), (1080, 608));
        assert_eq!(decode_size(640, 360), (640, 360));
        assert_eq!(decode_size(0, 0), (0, 0));
        // Output dimensions are always even enough for the reader's frame size math and never
        // exceed the cap.
        for (w, h) in [(1080, 2400), (2400, 1080), (1440, 3200), (720, 1280)] {
            let (dw, dh) = decode_size(w, h);
            assert!(dw.max(dh) <= DECODE_MAX, "{dw}x{dh} exceeds cap");
            assert!(dw > 0 && dh > 0);
        }
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

    #[test]
    fn gain_scales_pcm_and_mutes_at_zero() {
        let mut pcm = Vec::new();
        for value in [1000i16, -1000, i16::MAX, i16::MIN] {
            pcm.extend_from_slice(&value.to_le_bytes());
        }
        apply_gain(&mut pcm, 50);
        let scaled: Vec<i16> = pcm
            .chunks_exact(2)
            .map(|sample| i16::from_le_bytes([sample[0], sample[1]]))
            .collect();
        assert_eq!(scaled, vec![500, -500, 16383, -16384]);

        apply_gain(&mut pcm, 0);
        assert!(pcm.iter().all(|&byte| byte == 0));

        let mut untouched = 1234i16.to_le_bytes().to_vec();
        apply_gain(&mut untouched, 100);
        assert_eq!(i16::from_le_bytes([untouched[0], untouched[1]]), 1234);
    }
}
