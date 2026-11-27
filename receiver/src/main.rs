#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
// ViewGo receiver: pairs with an Android sender, decrypts H.264 frames, decodes them with
// ffmpeg and shows the newest frame in a native egui window (white/green, per design-taste).

use std::collections::{HashMap, VecDeque};
use std::env;
use std::io::{BufReader, BufWriter, Read, Write};
use std::net::{IpAddr, TcpListener, TcpStream};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};
use viewgo_receiver::protocol::*;

use eframe::egui;
use mdns_sd::{ServiceDaemon, ServiceInfo};

const DEFAULT_PORT: u16 = 47810;
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
    let _mdns = publish_receiver(port);
    log(format!("ViewGo receiver listening on port {port}"));
    log(format!("Pairing code: {code}  (valid 120 seconds)"));

    if let Some(listener) = listener {
        let shared = Arc::clone(&shared);
        let latest = Arc::clone(&latest);
        let spares = Arc::clone(&spares);
        let frame_seq = Arc::clone(&frame_seq);
        thread::spawn(move || accept_loop(listener, shared, latest, spares, frame_seq));
    }

    run_gui(shared, latest, spares, frame_seq, port);
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

    let mut builder = FfmpegBuilder::default();
    let mut video_config: Option<(u16, u16)> = None;
    let mut received = 0u64;
    let mut window_start = Instant::now();
    let mut frames_in_window = 0u32;
    let mut last_seq = frame_seq.load(Ordering::Relaxed);

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
                    let elapsed = window_start.elapsed();
                    let in_fps = frames_in_window as f64 / elapsed.as_secs_f64();
                    // frame_seq is bumped by the decoder thread, so this is the decode rate: when
                    // it lags the input rate the queue is growing and delay accumulates.
                    let now_seq = frame_seq.load(Ordering::Relaxed);
                    let dec_fps = now_seq.saturating_sub(last_seq) as f64 / elapsed.as_secs_f64();
                    last_seq = now_seq;
                    let (w, h) = video_config.unwrap();
                    shared.set_status(format!(
                        "{peer} — {w}x{h} — in {in_fps:.0} / dec {dec_fps:.0} fps"
                    ));
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
        // scale=WxH pins the rawvideo output to exactly the dimensions the reader will consume:
        // an encoder that rounds a non-macroblock-aligned size (e.g. 1080 -> 1088) would otherwise
        // shift every frame and garble the picture.
        let out_size = format!("scale={}:{}", width, height);
        let mut child = Command::new(ffmpeg)
            .args([
                "-hide_banner",
                "-loglevel",
                "error",
                // NOTE: -fflags nobuffer was tried here and silently produced zero rawvideo output
                // on a piped Annex-B stream (verified: 0 bytes vs 1 frame without it), so it must
                // not come back. Latency is bounded elsewhere: small socket buffer + drop-stale.
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
                &out_size,
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
        self.audio = Some(AudioPlayer { stdin, child });
        Ok(())
    }

    fn reset_audio(&mut self, sample_rate: u32, channels: u8) -> std::io::Result<()> {
        self.kill_audio();
        self.start_audio(sample_rate, channels)
    }

    fn write_audio(&mut self, pcm: &[u8]) {
        let failed = match self.audio.as_mut() {
            None => return,
            Some(audio) => match audio.child.try_wait() {
                Ok(Some(_)) => true,
                Err(_) => true,
                Ok(None) => audio
                    .stdin
                    .write_all(pcm)
                    .and_then(|_| audio.stdin.flush())
                    .is_err(),
            },
        };
        if failed {
            self.audio.take();
            log(
                "Audio output stopped: ffplay exited or the Windows playback device is unavailable",
            );
        }
    }

    fn kill_audio(&mut self) {
        if let Some(mut audio) = self.audio.take() {
            let _ = audio.child.kill();
            let _ = audio.child.wait();
        }
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

fn run_gui(
    shared: Arc<Shared>,
    latest: Arc<Mutex<Latest>>,
    spares: Arc<Mutex<Vec<Vec<u8>>>>,
    frame_seq: Arc<AtomicU64>,
    port: u16,
) {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1120.0, 780.0])
            .with_min_inner_size([760.0, 540.0]),
        ..Default::default()
    };
    if let Err(error) = eframe::run_native(
        "ViewGo",
        options,
        Box::new(move |cc| ViewGoApp::init(cc, shared, latest, spares, frame_seq, port)),
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

struct ViewGoApp {
    shared: Arc<Shared>,
    latest: Arc<Mutex<Latest>>,
    spares: Arc<Mutex<Vec<Vec<u8>>>>,
    frame_seq: Arc<AtomicU64>,
    port: u16,
    seen: u64,
    last: Option<(Vec<u8>, usize, usize)>,
    scaled: Vec<u32>,
    area: (usize, usize),
    tex_size: (usize, usize),
    texture: Option<egui::TextureHandle>,
}

impl ViewGoApp {
    fn init(
        cc: &eframe::CreationContext<'_>,
        shared: Arc<Shared>,
        latest: Arc<Mutex<Latest>>,
        spares: Arc<Mutex<Vec<Vec<u8>>>>,
        frame_seq: Arc<AtomicU64>,
        port: u16,
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

        Ok(Box::new(ViewGoApp {
            shared,
            latest,
            spares,
            frame_seq,
            port,
            seen: 0,
            last: None,
            scaled: Vec::new(),
            area: (0, 0),
            tex_size: (0, 0),
            texture: None,
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
        if (fresh || self.area != area) && self.last.is_some() {
            let (frame, fw, fh) = self.last.as_ref().unwrap();
            let (dw, dh) = fit_size(*fw, *fh, area.0, area.1);
            if dw > 0 && dh > 0 {
                if self.scaled.len() != dw * dh {
                    self.scaled.resize(dw * dh, 0);
                }
                self.scaled.fill(0);
                scale_fit_bgra(frame, *fw, *fh, &mut self.scaled, dw, dh);
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
            }
        }
    }
}

impl eframe::App for ViewGoApp {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        // Owned clone so the panel closures can use it while `ui` is borrowed.
        let ctx = ui.ctx().clone();
        // Keep status, code and log live even when no video frames arrive.
        ctx.request_repaint_after(Duration::from_millis(16));

        let status = self.shared.status.lock().unwrap().clone();
        let code = self.shared.current_code();
        let active = *self.shared.active.lock().unwrap();
        let port = self.port;

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
            });
            ui.add_space(14.0);
            card(ui, |ui| {
                ui.label(egui::RichText::new("Session").size(13.0).color(MUTED));
                ui.add_space(6.0);
                ui.label(egui::RichText::new(status).size(15.0).color(INK));
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
                    egui::RichText::new("2.  Tap Find laptop on the same Wi-Fi.")
                        .size(14.0)
                        .color(INK),
                );
                ui.label(
                    egui::RichText::new("3.  Type the code above, then Connect.")
                        .size(14.0)
                        .color(INK),
                );
            });
        });

        // The picture lives in its own borderless window so OBS can window-capture it clean and
        // show it fullscreen; this window stays a control panel with no video.
        let mirror = egui::ViewportId::from_hash_of("viewgo-mirror");
        let builder = egui::ViewportBuilder::default()
            .with_title("ViewGo Mirror")
            .with_decorations(false)
            .with_inner_size([540.0, 960.0])
            .with_min_inner_size([240.0, 420.0]);
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

        egui::CentralPanel::default().show(ui, |ui| {
            ui.with_layout(
                egui::Layout::centered_and_justified(egui::Direction::TopDown),
                |ui| {
                    ui.vertical_centered(|ui| {
                        ui.add_space(40.0);
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

fn badge(ui: &mut egui::Ui, active: bool) {
    let label = if active {
        "Mirroring"
    } else {
        "Waiting for a phone"
    };
    let colour = if active { ACCENT_INK } else { MUTED };
    let galley =
        ui.painter()
            .layout_no_wrap(label.to_string(), egui::FontId::proportional(13.0), colour);
    let pad = egui::vec2(13.0, 7.0);
    let size = galley.size() + pad * 2.0;
    let (rect, _) = ui.allocate_exact_size(size, egui::Sense::hover());
    ui.painter().rect_filled(
        rect,
        egui::CornerRadius::same((size.y / 2.0) as u8),
        if active { ACCENT } else { SURFACE },
    );
    ui.painter().galley(rect.min + pad, galley, colour);
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

fn publish_receiver(port: u16) -> Option<ServiceDaemon> {
    let daemon = match ServiceDaemon::new() {
        Ok(daemon) => daemon,
        Err(error) => {
            log(format!("Local discovery unavailable: {error}"));
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
            code: Mutex::new(("AB3D".to_string(), expiry)),
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
