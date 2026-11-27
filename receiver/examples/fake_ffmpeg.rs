// Test-only ffmpeg stand-in: behaves like a driver that advertises a hardware decoder it cannot
// run. It fails when asked for `-hwaccel` and proxies everything else to the real ffmpeg, so the
// receiver's software fallback can be exercised on demand.
//
// Usage: FFMPEG=<path to this exe> target\release\viewgo-receiver.exe

use std::process::{Command, Stdio};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.iter().any(|arg| arg == "-hwaccel") {
        eprintln!("fake_ffmpeg: refusing hardware decode");
        std::process::exit(1);
    }
    let status = Command::new("ffmpeg")
        .args(&args)
        .stdin(Stdio::inherit())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit())
        .status();
    std::process::exit(match status {
        Ok(status) => status.code().unwrap_or(1),
        Err(error) => {
            eprintln!("fake_ffmpeg: cannot run ffmpeg: {error}");
            1
        }
    });
}
