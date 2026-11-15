//! Deterministic WebTransport certificate derived from the pairing code.
//!
//! Chromium accepts a self-signed WebTransport certificate when the page
//! passes its SHA-256 hash (`serverCertificateHashes`) and the certificate is
//! ECDSA P-256, valid for at most 14 days. The car page has no secure way to
//! ask the phone for that hash, so both sides compute the certificate from the
//! pairing code the user typed once:
//!
//!   key   = P-256 scalar from SHA-256("carlink-wt-key-v1:" + code)
//!   valid = Monday 00:00 UTC of the current week, for 13 days
//!   DER   = fixed template below, signed with RFC 6979 (deterministic) ECDSA,
//!           low-S normalized
//!
//! `web/src/cert.ts` must produce byte-identical output; the test vector in
//! this file and in the web tests keeps them in sync.

use p256::ecdsa::{Signature, SigningKey, signature::hazmat::PrehashSigner};
use p256::pkcs8::EncodePrivateKey;
use sha2::{Digest, Sha256};

const KEY_LABEL: &str = "carlink-wt-key-v1:";
pub const WEEK_SECS: i64 = 7 * 24 * 3600;
const VALID_SECS: i64 = 13 * 24 * 3600;
// 1970-01-01 was a Thursday; Monday 1970-01-05 00:00 UTC.
const FIRST_MONDAY: i64 = 4 * 24 * 3600;

pub struct WtCert {
    pub der: Vec<u8>,
    pub key_pkcs8: Vec<u8>,
    pub hash: [u8; 32],
    pub not_before: i64,
}

pub fn now_unix() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

impl WtCert {
    pub fn hash_hex(&self) -> String {
        self.hash.iter().map(|b| format!("{b:02x}")).collect()
    }
}

/// Monday 00:00 UTC of the week containing `unix_secs`.
pub fn week_start(unix_secs: i64) -> i64 {
    FIRST_MONDAY + (unix_secs - FIRST_MONDAY).div_euclid(WEEK_SECS) * WEEK_SECS
}

fn signing_key(code: &str) -> SigningKey {
    let mut h: [u8; 32] = Sha256::digest(format!("{KEY_LABEL}{}", normalize_code(code))).into();
    loop {
        if let Ok(k) = SigningKey::from_bytes(&h.into()) {
            return k;
        }
        // Zero or >= curve order: rehash (practically never happens).
        h = Sha256::digest(h).into();
    }
}

/// Codes are shown grouped ("ABCD-EFGH-JKLM"); case and separators don't matter.
pub fn normalize_code(code: &str) -> String {
    code.chars().filter(|c| c.is_ascii_alphanumeric()).map(|c| c.to_ascii_uppercase()).collect()
}

pub fn make(code: &str, unix_secs: i64) -> WtCert {
    let key = signing_key(code);
    let not_before = week_start(unix_secs);
    let point = key.verifying_key().to_sec1_point(false);
    let tbs = tbs_certificate(not_before, point.as_bytes());

    let digest: [u8; 32] = Sha256::digest(&tbs).into();
    let sig: Signature = key.sign_prehash(&digest).expect("sign");
    let sig = sig.normalize_s();
    let sig_der = sig.to_der();

    let mut bitstring = vec![0u8];
    bitstring.extend_from_slice(sig_der.as_bytes());
    let der = seq(&[tbs, alg_ecdsa_sha256(), tlv(0x03, &bitstring)].concat());
    let hash: [u8; 32] = Sha256::digest(&der).into();
    let key_pkcs8 = key.to_pkcs8_der().expect("pkcs8").as_bytes().to_vec();
    WtCert { der, key_pkcs8, hash, not_before }
}

fn tbs_certificate(not_before: i64, pubkey_uncompressed: &[u8]) -> Vec<u8> {
    let version = tlv(0xA0, &tlv(0x02, &[2]));
    let serial = tlv(0x02, &der_uint(&(not_before as u64).to_be_bytes()));
    let name = seq(&tlv(0x31, &seq(&[tlv(0x06, &[0x55, 0x04, 0x03]), tlv(0x0C, b"carlink")].concat())));
    let validity = seq(&[utc_time(not_before), utc_time(not_before + VALID_SECS)].concat());
    let alg = seq(&[
        tlv(0x06, &[0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01]),       // id-ecPublicKey
        tlv(0x06, &[0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07]), // prime256v1
    ].concat());
    let mut bits = vec![0u8];
    bits.extend_from_slice(pubkey_uncompressed);
    let spki = seq(&[alg, tlv(0x03, &bits)].concat());
    seq(&[version, serial, alg_ecdsa_sha256(), name.clone(), validity, name, spki, extensions()].concat())
}

/// Chromium's WebTransport certificate parser rejects certificates without
/// extensions. Same set Go's x509 package writes for a server cert.
fn extensions() -> Vec<u8> {
    let key_usage = seq(&[
        tlv(0x06, &[0x55, 0x1D, 0x0F]),
        tlv(0x01, &[0xFF]),
        tlv(0x04, &tlv(0x03, &[0x07, 0x80])), // digitalSignature
    ].concat());
    let ext_key_usage = seq(&[
        tlv(0x06, &[0x55, 0x1D, 0x25]),
        tlv(0x04, &seq(&tlv(0x06, &[0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01]))), // serverAuth
    ].concat());
    let basic_constraints = seq(&[
        tlv(0x06, &[0x55, 0x1D, 0x13]),
        tlv(0x01, &[0xFF]),
        tlv(0x04, &seq(&[])),
    ].concat());
    tlv(0xA3, &seq(&[key_usage, ext_key_usage, basic_constraints].concat()))
}

fn alg_ecdsa_sha256() -> Vec<u8> {
    seq(&tlv(0x06, &[0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02]))
}

fn seq(content: &[u8]) -> Vec<u8> {
    tlv(0x30, content)
}

fn tlv(tag: u8, content: &[u8]) -> Vec<u8> {
    let mut out = vec![tag];
    let n = content.len();
    if n < 0x80 {
        out.push(n as u8);
    } else if n < 0x100 {
        out.extend_from_slice(&[0x81, n as u8]);
    } else {
        out.extend_from_slice(&[0x82, (n >> 8) as u8, n as u8]);
    }
    out.extend_from_slice(content);
    out
}

/// Minimal two's-complement encoding of an unsigned big-endian integer.
fn der_uint(be: &[u8]) -> Vec<u8> {
    let mut i = 0;
    while i + 1 < be.len() && be[i] == 0 {
        i += 1;
    }
    let mut out = Vec::new();
    if be[i] & 0x80 != 0 {
        out.push(0);
    }
    out.extend_from_slice(&be[i..]);
    out
}

fn utc_time(unix_secs: i64) -> Vec<u8> {
    let (y, mo, d, h, mi, s) = civil(unix_secs);
    let txt = format!("{:02}{:02}{:02}{:02}{:02}{:02}Z", y % 100, mo, d, h, mi, s);
    tlv(0x17, txt.as_bytes())
}

/// Unix seconds to (year, month, day, hour, min, sec), UTC. (Howard Hinnant's algorithm.)
fn civil(t: i64) -> (i64, u32, u32, u32, u32, u32) {
    let days = t.div_euclid(86400);
    let secs = t.rem_euclid(86400);
    let z = days + 719468;
    let era = z.div_euclid(146097);
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
    (y, m, d, (secs / 3600) as u32, (secs % 3600 / 60) as u32, (secs % 60) as u32)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn week_start_is_monday() {
        // 2026-11-15 (Sunday) 12:00 UTC -> 2026-11-09 (Monday)
        assert_eq!(week_start(1_794_744_000), 1_794_182_400);
        assert_eq!(civil(1_794_182_400), (2026, 11, 9, 0, 0, 0));
    }

    #[test]
    fn week_boundaries() {
        let monday = 1_794_182_400; // 2026-11-09 00:00:00 UTC
        assert_eq!(week_start(monday), monday);
        assert_eq!(week_start(monday - 1), monday - WEEK_SECS);
        assert_eq!(week_start(monday + WEEK_SECS - 1), monday);
        assert_eq!(week_start(0), FIRST_MONDAY - WEEK_SECS); // before the first Monday
        assert_eq!(civil(1_830_211_200), (2027, 12, 31, 0, 0, 0));
        assert_eq!(civil(1_835_395_200), (2028, 2, 29, 0, 0, 0));
    }

    /// Split one DER TLV off the front: (content, rest).
    fn tlv_split(b: &[u8]) -> (&[u8], &[u8]) {
        let (len, hdr) = match b[1] {
            n if n < 0x80 => (n as usize, 2),
            0x81 => (b[2] as usize, 3),
            _ => (((b[2] as usize) << 8) | b[3] as usize, 4),
        };
        (&b[hdr..hdr + len], &b[hdr + len..])
    }

    #[test]
    fn signature_is_low_s() {
        use p256::ecdsa::Signature;
        let c = make("abcd-efgh-jklm", 1_794_744_000);
        let (cert, _) = tlv_split(&c.der);
        let (_tbs, rest) = tlv_split(cert);
        let (_alg, rest) = tlv_split(rest);
        let (bits, _) = tlv_split(rest);
        let sig = Signature::from_der(&bits[1..]).expect("signature DER");
        assert_eq!(sig.normalize_s(), sig, "high-S signature");
    }

    #[test]
    fn deterministic_and_parses() {
        let a = make("abcd-efgh-jklm", 1_794_744_000);
        let b = make("ABCDEFGHJKLM", 1_794_744_000);
        assert_eq!(a.der, b.der);
        // Shared test vector with web/src/cert.test.ts and docs/PROTOCOL.md.
        assert_eq!(a.hash_hex(), "2fdf659c522b8e77a8d5d86953e1a0b67a313cd615cd5ba265136e5a11486009");
        assert_eq!(a.not_before, 1_794_182_400);
        assert!(wtransport::tls::Certificate::from_der(a.der.clone()).is_ok());
    }
}
