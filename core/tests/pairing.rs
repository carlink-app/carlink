//! QR pairing over one waiting connection: a real client against the real
//! server's pairing port (port + 1), over loopback.

use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::AsyncReadExt;
use tslink_core::{cert, pair, server};
use wtransport::tls::Sha256Digest;
use wtransport::{ClientConfig, Endpoint};

const CODE: &str = "TEST-CODE-0002";
const SECRET: &str = "ABCDEFGHJKLMNPQRSTUV";

#[derive(Default)]
struct Events(Mutex<Vec<String>>);

impl server::Listener for Events {
    fn on_session(&self, connected: bool, _remote: &str) {
        self.0.lock().unwrap().push(format!("session {connected}"));
    }
    fn on_control(&self, _json: &str) {}
    fn on_keyframe_needed(&self, _pane: u16) {}
    fn on_paired(&self, _remote: &str) {
        self.0.lock().unwrap().push("paired".into());
    }
}

impl Events {
    fn count(&self, s: &str) -> usize {
        self.0.lock().unwrap().iter().filter(|e| *e == s).count()
    }
}

fn start(port: u16) -> (server::Server, Arc<Events>) {
    let events = Arc::new(Events::default());
    let srv = server::Server::start(server::Options { port, code: CODE.into(), allow: String::new() }, events.clone()).unwrap();
    (srv, events)
}

/// Connect to the pairing port (public certificate), send the tag, and return
/// the first line the phone sends within [wait] (None: nothing came).
async fn wait_for_code(port: u16, path: &str, secret: &str, wait: Duration, before_read: impl std::future::Future<Output = ()>) -> anyhow::Result<Option<String>> {
    let hash = cert::make(pair::CERT_NAME, cert::now_unix()).hash;
    let cfg = ClientConfig::builder().with_bind_default().with_server_certificate_hashes([Sha256Digest::new(hash)]).build();
    let conn = Endpoint::client(cfg)?.connect(format!("https://127.0.0.1:{}{path}", port + 1)).await?;
    let (mut send, mut recv) = conn.open_bi().await?.await?;
    send.write_all(format!("{{\"t\":\"pair\",\"tag\":\"{}\"}}\n", pair::tag(secret)).as_bytes()).await?;
    before_read.await;
    let read = async {
        let mut out = Vec::new();
        loop {
            let b = recv.read_u8().await?;
            if b == b'\n' {
                return anyhow::Ok(String::from_utf8(out)?);
            }
            out.push(b);
        }
    };
    Ok(tokio::time::timeout(wait, read).await.ok().transpose()?)
}

fn opened(line: &str, secret: &str) -> Option<String> {
    let v: serde_json::Value = serde_json::from_str(line).ok()?;
    assert_eq!(v["t"], "paired");
    pair::open(secret, v["nonce"].as_str()?, v["box"].as_str()?)
}

fn port(offset: u16) -> u16 {
    // Each server takes two ports (normal, pairing).
    43000 + (std::process::id() % 250) as u16 * 8 + offset * 2
}

#[test]
fn a_waiting_page_gets_the_code_once_the_phone_scans() {
    let p = port(0);
    let (srv, events) = start(p);
    let srv = Arc::new(srv);
    let rt = tokio::runtime::Runtime::new().unwrap();
    let scan = {
        let srv = srv.clone();
        async move {
            tokio::time::sleep(Duration::from_millis(300)).await; // the page waits first
            srv.pair(SECRET, CODE);
        }
    };
    let line = rt.block_on(wait_for_code(p, "/pair", SECRET, Duration::from_secs(3), scan)).unwrap().expect("code sent");
    assert_eq!(opened(&line, SECRET).as_deref(), Some(CODE));
    assert_eq!(events.count("paired"), 1);
    assert_eq!(events.count("session true"), 0, "pairing is not a car session");
}

#[test]
fn a_page_arriving_after_the_scan_gets_it_too() {
    let p = port(1);
    let (srv, events) = start(p);
    srv.pair(SECRET, CODE);
    let rt = tokio::runtime::Runtime::new().unwrap();
    let line = rt.block_on(wait_for_code(p, "/pair", SECRET, Duration::from_secs(3), async {})).unwrap().expect("code sent");
    assert_eq!(opened(&line, SECRET).as_deref(), Some(CODE));
    assert_eq!(events.count("paired"), 1);
}

#[test]
fn other_pages_and_second_takers_get_nothing() {
    let p = port(2);
    let (srv, events) = start(p);
    srv.pair(SECRET, CODE);
    let rt = tokio::runtime::Runtime::new().unwrap();
    rt.block_on(async {
        // Another secret's tag: never served.
        let other = wait_for_code(p, "/pair", "ABCDEFGHJKLMNPQRSTUW", Duration::from_millis(800), async {}).await.unwrap();
        assert_eq!(other, None);
        // The first with the right tag takes it; a second one gets nothing.
        assert!(wait_for_code(p, "/pair", SECRET, Duration::from_secs(3), async {}).await.unwrap().is_some());
        let again = wait_for_code(p, "/pair", SECRET, Duration::from_millis(800), async {}).await.unwrap();
        assert_eq!(again, None);
    });
    assert_eq!(events.count("paired"), 1);
}

#[test]
fn the_pairing_port_serves_nothing_else() {
    let p = port(3);
    let (_srv, _events) = start(p);
    let rt = tokio::runtime::Runtime::new().unwrap();
    let r = rt.block_on(wait_for_code(p, "/carlink", SECRET, Duration::from_millis(800), async {}));
    assert!(r.is_err() || r.unwrap().is_none());
}
