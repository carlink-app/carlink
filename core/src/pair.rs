//! QR pairing over one waiting connection (docs/superpowers/specs/
//! 2026-11-16-pairing-session-design.md). The car page and the phone share a
//! one-time secret through the QR code only; from it both derive a tag (which
//! waiting connection is the page that showed the QR) and a key that seals the
//! real pairing code. The pairing port's certificate is public, so secrecy
//! rests on the secret, not on TLS.

use base64::Engine as _;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64;
use ring::{aead, hkdf, hmac};

const SALT: &[u8] = b"tslink-pair-v1";
const TAG_MESSAGE: &[u8] = b"tslink pair";

/// The pairing port's certificate is made from this public "code": any page
/// can complete the handshake; the secret protects what is sent.
pub const CERT_NAME: &str = "TSLINK-PAIRING";

struct Len32;

impl hkdf::KeyType for Len32 {
    fn len(&self) -> usize {
        32
    }
}

/// 32 bytes of HKDF-SHA256(ikm = secret, salt = SALT, info).
fn derive(secret: &str, info: &[u8]) -> [u8; 32] {
    let prk = hkdf::Salt::new(hkdf::HKDF_SHA256, SALT).extract(secret.as_bytes());
    let info = [info];
    let mut out = [0u8; 32];
    prk.expand(&info, Len32).and_then(|okm| okm.fill(&mut out)).expect("hkdf");
    out
}

/// Which waiting connection belongs to the page that showed this secret (one-way).
pub fn tag(secret: &str) -> String {
    let key = hmac::Key::new(hmac::HMAC_SHA256, &derive(secret, b"id"));
    B64.encode(hmac::sign(&key, TAG_MESSAGE))
}

fn box_key(secret: &str) -> aead::LessSafeKey {
    aead::LessSafeKey::new(aead::UnboundKey::new(&aead::AES_256_GCM, &derive(secret, b"box")).expect("aes key"))
}

/// The pairing code sealed for the page: AES-256-GCM, base64url.
pub struct Sealed {
    pub nonce: String,
    pub sealed: String,
}

pub fn seal(secret: &str, code: &str, nonce: [u8; 12]) -> Sealed {
    let mut data = code.as_bytes().to_vec();
    box_key(secret)
        .seal_in_place_append_tag(aead::Nonce::assume_unique_for_key(nonce), aead::Aad::empty(), &mut data)
        .expect("seal");
    Sealed { nonce: B64.encode(nonce), sealed: B64.encode(data) }
}

/// The page's side, for tests: None unless [secret] sealed it unchanged.
pub fn open(secret: &str, nonce: &str, sealed: &str) -> Option<String> {
    let nonce: [u8; 12] = B64.decode(nonce).ok()?.try_into().ok()?;
    let mut data = B64.decode(sealed).ok()?;
    let plain = box_key(secret)
        .open_in_place(aead::Nonce::assume_unique_for_key(nonce), aead::Aad::empty(), &mut data)
        .ok()?;
    String::from_utf8(plain.to_vec()).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECRET: &str = "ABCDEFGHJKLMNPQRSTUV";
    const CODE: &str = "ABCD-EFGH-JKLM";

    #[test]
    fn tag_is_stable_and_secret_specific() {
        assert_eq!(tag(SECRET), tag(SECRET));
        assert_ne!(tag(SECRET), tag("ABCDEFGHJKLMNPQRSTUW"));
        assert!(!tag(SECRET).contains(SECRET));
    }

    #[test]
    fn sealed_code_opens_with_the_secret_only() {
        let sealed = seal(SECRET, CODE, [7u8; 12]);
        assert_eq!(open(SECRET, &sealed.nonce, &sealed.sealed).as_deref(), Some(CODE));
        assert_eq!(open("ABCDEFGHJKLMNPQRSTUW", &sealed.nonce, &sealed.sealed), None);
        // Flipping a bit breaks the box (AES-GCM).
        let mut bad = sealed.sealed.clone();
        bad.replace_range(0..1, if bad.starts_with('A') { "B" } else { "A" });
        assert_eq!(open(SECRET, &sealed.nonce, &bad), None);
    }

    /// The pairing port's public certificate, as the page pins it (web/src/pairsession.test.ts).
    #[test]
    fn certificate_vector() {
        assert_eq!(crate::cert::make(CERT_NAME, 1_794_744_000).hash_hex(), "0c2ac1440d283f05b7f79588b4f05ecb70768c4d0222e16d65511c831ec8f2c0");
    }

    /// Shared with web/src/pairsession.test.ts: both sides must agree byte for byte.
    #[test]
    fn vectors() {
        assert_eq!(tag(SECRET), "1GThOC0M3ImFQlqwf34TZ95_rS9_9vP9uzu310KBHW4");
        let s = seal(SECRET, CODE, [0u8; 12]);
        assert_eq!(s.nonce, "AAAAAAAAAAAAAAAA");
        assert_eq!(s.sealed, "SifG4lTAz4Gazfb0_xA6oTP915FRvmWD3eLpO31d");
    }
}
