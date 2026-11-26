// Prints the fixed crypto vector asserted by the Android unit test.
// Run: cargo run --example vector

use viewgo_receiver::protocol::*;

fn main() {
    let salt = [0u8; 16];
    let cr = [1u8; 16];
    let sr = [2u8; 16];
    let key = derive_key("AB3DE4GHJ", &salt, &cr, &sr);
    let auth = proof(&key, &salt, &cr, &sr);
    println!("key  = {}", hex(&key));
    println!("proof= {}", hex(&auth));
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
