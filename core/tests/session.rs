//! End-to-end: real WebTransport client against the real server over loopback.

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD as B64;
use carlink_core::{cert, server};
use sha2::{Digest, Sha256};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::AsyncReadExt;
use wtransport::tls::Sha256Digest;
use wtransport::{ClientConfig, Connection, Endpoint};

const CODE: &str = "TEST-CODE-0001";

#[derive(Default)]
struct Events(Mutex<Vec<String>>);

impl server::Listener for Events {
    fn on_session(&self, connected: bool, _remote: &str) {
        self.0.lock().unwrap().push(format!("session {connected}"));
    }
    fn on_control(&self, json: &str) {
        self.0.lock().unwrap().push(format!("control {json}"));
    }
    fn on_keyframe_needed(&self, pane: u16) {
        self.0.lock().unwrap().push(format!("keyframe {pane}"));
    }
    fn on_rate(&self, level: u32) {
        self.0.lock().unwrap().push(format!("rate {level}"));
    }
}

impl Events {
    fn has(&self, s: &str) -> bool {
        self.0.lock().unwrap().iter().any(|e| e == s)
    }
    fn count(&self, s: &str) -> usize {
        self.0.lock().unwrap().iter().filter(|e| *e == s).count()
    }
    fn position(&self, s: &str) -> Option<usize> {
        self.0.lock().unwrap().iter().position(|e| e == s)
    }
}

fn mac(code: &str, nonce: &[u8]) -> String {
    let k: [u8; 32] = Sha256::digest(format!("carlink-auth-key-v1:{}", cert::normalize_code(code))).into();
    let key = ring::hmac::Key::new(ring::hmac::HMAC_SHA256, &k);
    B64.encode(ring::hmac::sign(&key, &[b"carlink-auth-v1|".as_slice(), nonce].concat()))
}

async fn connect(port: u16) -> anyhow::Result<Connection> {
    let hash = cert::make(CODE, cert::now_unix()).hash;
    let cfg = ClientConfig::builder().with_bind_default().with_server_certificate_hashes([Sha256Digest::new(hash)]).build();
    Ok(Endpoint::client(cfg)?.connect(format!("https://127.0.0.1:{port}/carlink")).await?)
}

async fn read_line(recv: &mut wtransport::RecvStream) -> anyhow::Result<String> {
    let mut out = Vec::new();
    loop {
        let b = recv.read_u8().await?;
        if b == b'\n' {
            return Ok(String::from_utf8(out)?);
        }
        out.push(b);
    }
}

/// Connect, answer the challenge with `code`, return (conn, send, recv, reply).
async fn auth(port: u16, code: &str) -> anyhow::Result<(Connection, wtransport::SendStream, wtransport::RecvStream, String)> {
    let conn = connect(port).await?;
    let (mut send, mut recv) = conn.open_bi().await?.await?;
    let challenge: serde_json::Value = serde_json::from_str(&read_line(&mut recv).await?)?;
    assert_eq!(challenge["t"], "challenge");
    let nonce = B64.decode(challenge["nonce"].as_str().unwrap())?;
    send.write_all(format!("{{\"t\":\"auth\",\"mac\":\"{}\"}}\n", mac(code, &nonce)).as_bytes()).await?;
    let reply = tokio::time::timeout(Duration::from_secs(3), read_line(&mut recv)).await.unwrap_or_else(|_| Ok("timeout".into()));
    Ok((conn, send, recv, reply.unwrap_or_else(|e| format!("error {e}"))))
}

#[test]
fn authenticated_session_end_to_end() {
    let events = Arc::new(Events::default());
    let port = 41000 + (std::process::id() % 1000) as u16;
    let srv = server::Server::start(server::Options { port, code: CODE.into(), allow: String::new() }, events.clone()).unwrap();
    let rt = tokio::runtime::Runtime::new().unwrap();

    rt.block_on(async {
        // Wrong code: rejected, nothing reaches the app (no rate either).
        let (_c, _s, _r, reply) = auth(port, "WRONG-CODE-0000").await.unwrap();
        assert_ne!(reply, r#"{"t":"authok"}"#);
        assert!(!events.has("session true"));
        assert!(!events.0.lock().unwrap().iter().any(|e| e.starts_with("rate")));
    });

    // The failed attempt put 127.0.0.1 into a 1 s backoff.
    std::thread::sleep(Duration::from_millis(1100));

    rt.block_on(async {
        let (conn, mut send, mut recv, reply) = auth(port, CODE).await.unwrap();
        assert_eq!(reply, r#"{"t":"authok"}"#);
        tokio::time::sleep(Duration::from_millis(100)).await;
        assert!(events.has("session true"));
        // Each session starts the encoder at START_LEVEL, after it's announced.
        let start = format!("rate {}", carlink_core::rate::START_LEVEL);
        assert_eq!(events.count(&start), 1);
        assert!(events.position("session true") < events.position(&start));

        // control: page -> phone (validated, unknown types close the session later)
        send.write_all(b"{\"t\":\"hello\",\"v\":2}\n").await.unwrap();
        tokio::time::sleep(Duration::from_millis(100)).await;
        assert!(events.has(r#"control {"t":"hello","v":2}"#));

        // control: phone -> page
        srv.send_control(r#"{"t":"pane","id":0}"#);
        assert_eq!(read_line(&mut recv).await.unwrap(), r#"{"t":"pane","id":0}"#);

        // datagram echo
        conn.send_datagram(b"ping1234").unwrap();
        let d = tokio::time::timeout(Duration::from_secs(2), conn.receive_datagram()).await.unwrap().unwrap();
        assert_eq!(d.payload().as_ref(), b"ping1234");

        // video frames: one uni stream each, header + payload, per-pane seq
        for i in 0..3u8 {
            assert!(srv.send_frame(0, if i == 0 { server::FLAG_KEY } else { 0 }, 1000 + i as u64, &[0, 0, 0, 1, 0x65, i]));
        }
        let mut seqs = Vec::new();
        for _ in 0..3 {
            let mut s = tokio::time::timeout(Duration::from_secs(2), conn.accept_uni()).await.unwrap().unwrap();
            let mut buf = Vec::new();
            s.read_to_end(&mut buf).await.unwrap();
            assert_eq!(buf.len(), server::HEADER_LEN + 6);
            assert_eq!(buf[0], 1);
            assert_eq!(u16::from_be_bytes([buf[2], buf[3]]), 0);
            let seq = u32::from_be_bytes(buf[4..8].try_into().unwrap());
            let pts = u64::from_be_bytes(buf[8..16].try_into().unwrap());
            assert_eq!(pts, 1000 + seq as u64);
            assert_eq!(buf[server::HEADER_LEN + 5], seq as u8);
            seqs.push(seq);
        }
        seqs.sort();
        assert_eq!(seqs, vec![0, 1, 2]);

        // an unknown message type ends the session
        send.write_all(b"{\"t\":\"shell\",\"cmd\":\"id\"}\n").await.unwrap();
        tokio::time::sleep(Duration::from_millis(300)).await;
        assert!(events.has("session false"));
        assert!(!events.0.lock().unwrap().iter().any(|e| e.contains("shell")));
        drop(conn);

        // A new session starts from START_LEVEL again, wherever the last one ended.
        let (_conn2, _send2, _recv2, reply) = auth(port, CODE).await.unwrap();
        assert_eq!(reply, r#"{"t":"authok"}"#);
        tokio::time::sleep(Duration::from_millis(100)).await;
        assert_eq!(events.count("session true"), 2);
        assert_eq!(events.count(&start), 2);
    });
    srv.shutdown();
}
