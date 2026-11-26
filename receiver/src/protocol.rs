// Shared protocol: pairing code, key derivation, encrypted records, framing.
// Layout is defined in docs/PROTOCOL.md. Change that document first.

use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};
use hmac::{Hmac, Mac};
use pbkdf2::pbkdf2_hmac;
use rand::RngCore;
use sha2::Sha256;
use std::io::{Read, Write};

pub const MAGIC: &[u8; 4] = b"VGMR";
pub const VERSION: u8 = 1;
pub const PBKDF2_ITER: u32 = 120_000;
pub const MAX_BODY: usize = 16 * 1024 * 1024;
pub const MAX_PLAIN: usize = 4096;
pub const CODE_ALPHABET: &[u8] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";
pub const CODE_LEN: usize = 4;

pub const STATUS_OK: u8 = 0;
pub const STATUS_EXPIRED: u8 = 2;
pub const STATUS_RATE_LIMITED: u8 = 3;
pub const STATUS_BUSY: u8 = 4;

pub const T_AUTH_OK: u8 = 0x10;
pub const T_VIDEO_CONFIG: u8 = 0x01;
pub const T_VIDEO_FRAME: u8 = 0x02;
pub const T_CONTROL: u8 = 0x03;
pub const T_LOG: u8 = 0x04;
pub const T_AUDIO_CONFIG: u8 = 0x05;
pub const T_AUDIO_FRAME: u8 = 0x06;
pub const T_STOP: u8 = 0x07;

pub fn new_code() -> String {
    let mut rng = rand::thread_rng();
    let mut bytes = [0u8; CODE_LEN];
    rng.fill_bytes(&mut bytes);
    bytes
        .iter()
        .map(|b| CODE_ALPHABET[(*b as usize) % CODE_ALPHABET.len()] as char)
        .collect()
}

/// Strips separators and maps Crockford look-alikes; returns the canonical code.
pub fn normalize_code(input: &str) -> Option<String> {
    let mut out = String::new();
    for ch in input.chars() {
        let c = ch.to_ascii_uppercase();
        if c == '-' || c == ' ' {
            continue;
        }
        let c = match c {
            'I' | 'L' => '1',
            'O' => '0',
            _ => c,
        };
        if !CODE_ALPHABET.contains(&(c as u8)) {
            return None;
        }
        out.push(c);
    }
    (out.len() == CODE_LEN).then_some(out)
}

pub fn derive_key(code: &str, salt: &[u8], cr: &[u8], sr: &[u8]) -> [u8; 32] {
    let mut full_salt = Vec::with_capacity(salt.len() + cr.len() + sr.len());
    full_salt.extend_from_slice(salt);
    full_salt.extend_from_slice(cr);
    full_salt.extend_from_slice(sr);
    let mut key = [0u8; 32];
    pbkdf2_hmac::<Sha256>(code.as_bytes(), &full_salt, PBKDF2_ITER, &mut key);
    key
}

pub fn proof(key: &[u8; 32], salt: &[u8], cr: &[u8], sr: &[u8]) -> [u8; 32] {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("hmac accepts any key length");
    mac.update(b"viewgo-auth");
    mac.update(salt);
    mac.update(cr);
    mac.update(sr);
    mac.finalize().into_bytes().into()
}

pub fn proof_matches(expected: &[u8], given: &[u8]) -> bool {
    if expected.len() != given.len() {
        return false;
    }
    expected
        .iter()
        .zip(given)
        .fold(0u8, |acc, (a, b)| acc | (a ^ b))
        == 0
}

pub fn random_bytes<const N: usize>() -> [u8; N] {
    let mut out = [0u8; N];
    rand::thread_rng().fill_bytes(&mut out);
    out
}

pub fn read_frame<R: Read>(r: &mut R, max: usize) -> std::io::Result<Vec<u8>> {
    let mut len = [0u8; 4];
    r.read_exact(&mut len)?;
    let n = u32::from_be_bytes(len) as usize;
    if n == 0 || n > max {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "frame length out of range",
        ));
    }
    let mut body = vec![0u8; n];
    r.read_exact(&mut body)?;
    Ok(body)
}

pub fn write_frame<W: Write>(w: &mut W, body: &[u8]) -> std::io::Result<()> {
    w.write_all(&(body.len() as u32).to_be_bytes())?;
    w.write_all(body)
}

/// Authenticated record layer. `local_dir` is the direction byte this side writes.
pub struct Cipher {
    key: [u8; 32],
    local_dir: u8,
    peer_dir: u8,
    send_ctr: u64,
    recv_ctr: u64,
}

impl Cipher {
    pub fn new(key: [u8; 32], local_dir: u8) -> Self {
        Self {
            key,
            local_dir,
            peer_dir: local_dir ^ 1,
            send_ctr: 0,
            recv_ctr: 0,
        }
    }

    fn nonce(dir: u8, ctr: u64) -> [u8; 12] {
        let mut iv = [0u8; 12];
        iv[0] = dir;
        iv[4..].copy_from_slice(&ctr.to_be_bytes());
        iv
    }

    pub fn seal(&mut self, plaintext: &[u8]) -> Vec<u8> {
        let aead = Aes256Gcm::new_from_slice(&self.key).expect("32-byte key");
        let iv = Self::nonce(self.local_dir, self.send_ctr);
        self.send_ctr = self.send_ctr.wrapping_add(1);
        let ct = aead
            .encrypt(Nonce::from_slice(&iv), plaintext)
            .expect("encryption of a short record cannot fail");
        let mut out = Vec::with_capacity(12 + ct.len());
        out.extend_from_slice(&iv);
        out.extend_from_slice(&ct);
        out
    }

    pub fn open(&mut self, record: &[u8]) -> Option<Vec<u8>> {
        if record.len() < 12 + 16 {
            return None;
        }
        if record[0] != self.peer_dir {
            return None;
        }
        let ctr = u64::from_be_bytes(record[4..12].try_into().ok()?);
        if ctr != self.recv_ctr {
            return None;
        }
        let aead = Aes256Gcm::new_from_slice(&self.key).ok()?;
        let plaintext = aead
            .decrypt(Nonce::from_slice(&record[..12]), &record[12..])
            .ok()?;
        self.recv_ctr = self.recv_ctr.wrapping_add(1);
        Some(plaintext)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn code_normalisation() {
        let code = "ab3-d";
        assert_eq!(normalize_code("AB3D").as_deref(), Some("AB3D"));
        assert_eq!(normalize_code(code).as_deref(), Some("AB3D"));
        assert_eq!(normalize_code("AB3O").as_deref(), Some("AB30"));
        assert_eq!(normalize_code("AB3I").as_deref(), Some("AB31"));
        assert_eq!(normalize_code("AB3"), None);
        assert_eq!(normalize_code("AB3D!"), None);
    }

    #[test]
    fn generated_codes_are_canonical() {
        for _ in 0..50 {
            let code = new_code();
            assert_eq!(normalize_code(&code).as_deref(), Some(code.as_str()));
        }
    }

    #[test]
    fn both_sides_derive_the_same_key_and_proof() {
        let salt = random_bytes::<16>();
        let cr = random_bytes::<16>();
        let sr = random_bytes::<16>();
        let a = derive_key("AB3DE4GHJ", &salt, &cr, &sr);
        let b = derive_key("AB3DE4GHJ", &salt, &cr, &sr);
        assert_eq!(a, b);
        assert!(proof_matches(
            &proof(&a, &salt, &cr, &sr),
            &proof(&b, &salt, &cr, &sr)
        ));
        assert!(!proof_matches(
            &proof(&a, &salt, &cr, &sr),
            &proof(&b, &salt, &cr, &[0u8; 16])
        ));
    }

    #[test]
    fn records_round_trip_and_reject_replay_and_tamper() {
        let key = random_bytes::<32>();
        let mut sender = Cipher::new(key, 0);
        let mut receiver = Cipher::new(key, 1);
        let r1 = sender.seal(b"hello");
        let r2 = sender.seal(b"world");
        assert_eq!(receiver.open(&r1).as_deref(), Some(&b"hello"[..]));
        assert_eq!(receiver.open(&r2).as_deref(), Some(&b"world"[..]));
        assert!(receiver.open(&r1).is_none(), "replayed record is rejected");

        let mut sender = Cipher::new(key, 0);
        let mut receiver = Cipher::new(key, 1);
        let mut tampered = sender.seal(b"secret");
        let last = tampered.len() - 1;
        tampered[last] ^= 1;
        assert!(receiver.open(&tampered).is_none());

        let mut wrong_dir = Cipher::new(key, 1).seal(b"nope");
        let mut receiver = Cipher::new(key, 1);
        assert!(receiver.open(&wrong_dir).is_none());
        wrong_dir[0] = 0;
    }

    #[test]
    fn frames_bounded() {
        let mut buf = Vec::new();
        write_frame(&mut buf, b"abc").unwrap();
        let mut cur = std::io::Cursor::new(buf);
        assert_eq!(read_frame(&mut cur, 8).unwrap(), b"abc");
        assert!(read_frame(&mut cur, 8).is_err());

        let bad = 0u32.to_be_bytes();
        assert!(read_frame(&mut std::io::Cursor::new(bad.to_vec()), 8).is_err());
        let huge = (9u32).to_be_bytes();
        assert!(read_frame(&mut std::io::Cursor::new(huge.to_vec()), 8).is_err());
    }
}
