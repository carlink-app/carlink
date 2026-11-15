//! WebTransport server for the car page. Protocol: docs/PROTOCOL.md (v2).
//!
//! - Session URL https://<phone>:<port>/carlink, only from the allowed subnet.
//! - The page opens one bidirectional control stream (JSON lines). The phone
//!   sends a challenge; the page answers with an HMAC keyed by the pairing
//!   code. Nothing else happens before that succeeds.
//! - Video: one unidirectional stream per encoded frame (24-byte header +
//!   Annex B access unit), oldest first, keyframes prioritized.
//! - Datagrams are echoed (RTT, Wi-Fi keepalive).
//! - Backlog: when frames can't be queued, the pane drops frames until its
//!   next keyframe and asks the encoder for one. Delay never builds up.
//! - Rate: every 200 ms the queueing signals feed [`RateControl`]; the
//!   listener gets the new bitrate level (see crate::rate).

use crate::cert;
use crate::rate::{RateControl, START_LEVEL, Sample};
use base64::Engine as _;
use base64::engine::general_purpose::STANDARD as B64;
use std::collections::{HashMap, HashSet};
use std::future::IntoFuture;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::sync::{Semaphore, mpsc};
use wtransport::endpoint::IncomingSession;
use wtransport::quinn::congestion::BbrConfig;
use wtransport::quinn::{TransportConfig, VarInt};
use wtransport::{Connection, Endpoint, Identity, RecvStream, SendStream, ServerConfig};

pub const FLAG_KEY: u8 = 1;
pub const HEADER_LEN: usize = 24;
const QUEUE_FRAMES: usize = 6;
const INFLIGHT_STREAMS: usize = 6;
const CONTROL_QUEUE: usize = 64;
const MAX_LINE: usize = 16 * 1024;
/// Control messages per second before touch moves are dropped (a 1000 Hz
/// mouse or a fast touchscreen can exceed it; the page throttles moves too).
const MAX_CONTROL_PER_SEC: u32 = 500;
/// Beyond this the session is closed: not a gesture, a flood.
const FLOOD_PER_SEC: u32 = 4 * MAX_CONTROL_PER_SEC;
/// Messages that make the phone start a process or do real work (`am`
/// launches, task removal, the app list): far more than a driver can tap.
const MAX_HEAVY_PER_SEC: u32 = 20;
const HEAVY_TYPES: &[&str] = &["launch", "close", "close-all", "apps", "key", "notif-open", "app-icons"];

/// How a control message counts against the per-second budgets.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
    /// A touch move: the next one supersedes it, so it may be dropped.
    Move,
    /// Costly on the phone (see HEAVY_TYPES): limited to MAX_HEAVY_PER_SEC.
    Heavy,
    Other,
}
const AUTH_TIMEOUT: Duration = Duration::from_secs(5);
/// QUIC + TLS + WebTransport CONNECT must finish within this.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
/// Connections not yet authenticated (each holds handshake and TLS state).
const MAX_PENDING: usize = 4;
const KEYFRAME_REQUEST_INTERVAL: Duration = Duration::from_millis(250);
const AUTH_KEY_LABEL: &str = "carlink-auth-key-v1:";
const AUTH_MAC_LABEL: &[u8] = b"carlink-auth-v1|";
/// Control message types the page may send (anything else closes the session).
const CONTROL_TYPES: &[&str] = &["hello", "layout", "apps", "launch", "touch", "key", "keyframe", "notif-open", "close", "close-all", "app-icons"];

/// Callbacks into the app (implemented over JNI in lib.rs).
pub trait Listener: Send + Sync + 'static {
    fn on_session(&self, connected: bool, remote: &str);
    fn on_control(&self, json: &str);
    fn on_keyframe_needed(&self, pane: u16);
    /// New bitrate level, permille of the codec's maximum (crate::rate).
    fn on_rate(&self, _level: u32) {}
}

pub struct Options {
    pub port: u16,
    pub code: String,
    /// Allowed client subnet, e.g. "9.9.0.0/24"; empty = any (development).
    pub allow: String,
}

struct Frame {
    pane: u16,
    flags: u8,
    pts_us: u64,
    data: Vec<u8>,
}

struct SessionHandle {
    id: u64,
    frames: mpsc::Sender<Frame>,
    control: mpsc::Sender<String>,
    conn: Arc<Connection>,
}

struct Shared {
    session: Option<SessionHandle>,
    backpressure: Backpressure,
    failures: HashMap<IpAddr, (u32, Instant)>,
}

pub struct Server {
    rt: tokio::runtime::Runtime,
    shared: Arc<Mutex<Shared>>,
    listener: Arc<dyn Listener>,
}

// ---------------------------------------------------------------- backpressure

#[derive(Debug, PartialEq)]
pub(crate) enum Admit {
    Sent,
    Dropped,
    /// Dropped; the encoder should produce a keyframe.
    DroppedNeedKeyframe,
}

pub(crate) enum Queue {
    Full,
    Closed,
}

/// Per-pane drop-until-keyframe state.
#[derive(Default)]
pub(crate) struct Backpressure {
    dropping: HashSet<u16>,
    last_request: HashMap<u16, Instant>,
    /// Times the send queue was found full since the rate controller last
    /// looked. Frames skipped while waiting for a keyframe don't count: one
    /// Wi-Fi stall would otherwise look like seconds of congestion.
    pub(crate) overflows: u32,
    /// Frames accepted since the rate controller last looked.
    pub(crate) sent: u32,
}

impl Backpressure {
    pub(crate) fn admit(&mut self, pane: u16, key: bool, now: Instant, try_send: impl FnOnce() -> Result<(), Queue>) -> Admit {
        if !key && self.dropping.contains(&pane) {
            // Keep asking while waiting: the keyframe asked for may itself have
            // hit a full queue, or the encoder may have skipped the request.
            // Otherwise the pane waits for the encoder's own keyframe interval
            // (seconds of frozen picture, seen on a busy 2.4 GHz link).
            return self.request_keyframe(pane, now);
        }
        match try_send() {
            Ok(()) => {
                self.sent += 1;
                if key {
                    self.dropping.remove(&pane);
                }
                Admit::Sent
            }
            // The session is going away; asking for keyframes won't help.
            Err(Queue::Closed) => Admit::Dropped,
            Err(Queue::Full) => {
                self.overflows += 1;
                self.dropping.insert(pane);
                self.request_keyframe(pane, now)
            }
        }
    }

    /// Dropped; ask the encoder for a keyframe, at most every KEYFRAME_REQUEST_INTERVAL.
    fn request_keyframe(&mut self, pane: u16, now: Instant) -> Admit {
        let due = self.last_request.get(&pane).is_none_or(|t| now.duration_since(*t) >= KEYFRAME_REQUEST_INTERVAL);
        if due {
            self.last_request.insert(pane, now);
            Admit::DroppedNeedKeyframe
        } else {
            Admit::Dropped
        }
    }

    fn reset(&mut self) {
        *self = Self::default();
    }
}

// ---------------------------------------------------------------- wire helpers

pub(crate) fn encode_header(flags: u8, pane: u16, seq: u32, pts_us: u64, send_us: u64) -> [u8; HEADER_LEN] {
    let mut h = [0u8; HEADER_LEN];
    h[0] = 1; // kind: video
    h[1] = flags;
    h[2..4].copy_from_slice(&pane.to_be_bytes());
    h[4..8].copy_from_slice(&seq.to_be_bytes());
    h[8..16].copy_from_slice(&pts_us.to_be_bytes());
    h[16..24].copy_from_slice(&send_us.to_be_bytes());
    h
}

pub(crate) fn auth_key(code: &str) -> ring::hmac::Key {
    use sha2::{Digest, Sha256};
    let k: [u8; 32] = Sha256::digest(format!("{AUTH_KEY_LABEL}{}", cert::normalize_code(code))).into();
    ring::hmac::Key::new(ring::hmac::HMAC_SHA256, &k)
}

fn auth_message(nonce: &[u8]) -> Vec<u8> {
    [AUTH_MAC_LABEL, nonce].concat()
}

#[cfg(test)]
fn auth_mac(key: &ring::hmac::Key, nonce: &[u8]) -> Vec<u8> {
    ring::hmac::sign(key, &auth_message(nonce)).as_ref().to_vec()
}

fn auth_ok(key: &ring::hmac::Key, nonce: &[u8], mac: &[u8]) -> bool {
    ring::hmac::verify(key, &auth_message(nonce), mac).is_ok()
}

/// Allowed IPv4 networks as (network, mask); `None` = allow all.
pub(crate) type Allow = Option<Vec<(u32, u32)>>;

/// Parse "a.b.c.d/n,a.b.c.d,..." (a bare address = /32); empty = allow all.
pub(crate) fn parse_allow(s: &str) -> anyhow::Result<Allow> {
    if s.trim().is_empty() {
        return Ok(None);
    }
    s.split(',')
        .map(|entry| {
            let entry = entry.trim();
            let (ip, bits) = entry.split_once('/').unwrap_or((entry, "32"));
            let ip: Ipv4Addr = ip.parse()?;
            let bits: u32 = bits.parse()?;
            anyhow::ensure!(bits <= 32, "bad prefix length");
            let mask = if bits == 0 { 0 } else { u32::MAX << (32 - bits) };
            Ok((u32::from(ip) & mask, mask))
        })
        .collect::<anyhow::Result<Vec<_>>>()
        .map(Some)
}

pub(crate) fn allowed(allow: &Allow, addr: IpAddr) -> bool {
    let Some(nets) = allow else { return true };
    let v4 = match addr {
        IpAddr::V4(a) => a,
        IpAddr::V6(a) => match a.to_ipv4_mapped() {
            Some(a) => a,
            None => return false,
        },
    };
    nets.iter().any(|&(net, mask)| u32::from(v4) & mask == net)
}

/// Reads newline-terminated lines, refusing lines longer than MAX_LINE.
struct LineReader {
    recv: RecvStream,
    buf: Vec<u8>,
}

impl LineReader {
    async fn next(&mut self) -> anyhow::Result<Option<String>> {
        loop {
            if let Some(i) = self.buf.iter().position(|&b| b == b'\n') {
                let line: Vec<u8> = self.buf.drain(..=i).collect();
                return Ok(Some(String::from_utf8(line[..i].to_vec())?.trim_end_matches('\r').to_string()));
            }
            anyhow::ensure!(self.buf.len() <= MAX_LINE, "control line too long");
            let mut chunk = [0u8; 4096];
            let n = self.recv.read(&mut chunk).await?.unwrap_or(0);
            if n == 0 {
                return Ok(None);
            }
            self.buf.extend_from_slice(&chunk[..n]);
        }
    }
}

async fn write_line(send: &mut SendStream, line: &str) -> anyhow::Result<()> {
    send.write_all(format!("{line}\n").as_bytes()).await?;
    Ok(())
}

/// Validate a control line from the page: the message re-serialized if its
/// type is allowed, and how it counts against the budgets.
fn validate_control(line: &str) -> Option<(String, Kind)> {
    let v: serde_json::Value = serde_json::from_str(line).ok()?;
    let t = v.get("t")?.as_str()?;
    if !CONTROL_TYPES.contains(&t) {
        return None;
    }
    let kind = if t == "touch" && v.get("a").and_then(|a| a.as_str()) == Some("move") {
        Kind::Move
    } else if HEAVY_TYPES.contains(&t) {
        Kind::Heavy
    } else {
        Kind::Other
    };
    Some((v.to_string(), kind))
}

/// Whether a message is dropped: touch moves past MAX_CONTROL_PER_SEC
/// messages this second, heavy ones past MAX_HEAVY_PER_SEC heavy ones.
/// Touch downs/ups and the rest are never dropped (FLOOD_PER_SEC closes).
fn over_budget(count: u32, heavy_count: u32, kind: Kind) -> bool {
    match kind {
        Kind::Move => count > MAX_CONTROL_PER_SEC,
        Kind::Heavy => heavy_count > MAX_HEAVY_PER_SEC,
        Kind::Other => false,
    }
}

/// How long to refuse an address after `n` failed authentications.
fn backoff(n: u32) -> Duration {
    Duration::from_secs(1u64 << n.saturating_sub(1).min(6)).min(Duration::from_secs(60))
}

// ---------------------------------------------------------------- server

fn server_config(port: u16, code: &str) -> anyhow::Result<(ServerConfig, i64)> {
    let c = cert::make(code, cert::now_unix());
    let identity = Identity::new(
        wtransport::tls::CertificateChain::single(wtransport::tls::Certificate::from_der(c.der)?),
        wtransport::tls::PrivateKey::from_der_pkcs8(c.key_pkcs8),
    );
    let mut transport = TransportConfig::default();
    // BBR models bandwidth and RTT instead of backing off on every lost
    // packet; random Wi-Fi loss shouldn't halve the video rate.
    transport.congestion_controller_factory(Arc::new(BbrConfig::default()));
    transport.initial_rtt(Duration::from_millis(10));
    transport.max_idle_timeout(Some(VarInt::from_u32(15_000).into()));
    transport.keep_alive_interval(Some(Duration::from_secs(2)));
    transport.max_concurrent_bidi_streams(VarInt::from_u32(4));
    transport.max_concurrent_uni_streams(VarInt::from_u32(16));
    // Plain sendmsg per packet: GSO batches and DF-based MTU probes don't
    // survive every Android network path (seen on the emulator).
    transport.enable_segmentation_offload(false);
    transport.mtu_discovery_config(None);
    let config = ServerConfig::builder()
        // IPv4 only: the hotspot is IPv4.
        .with_bind_address(SocketAddr::from(([0, 0, 0, 0], port)))
        .with_custom_transport(identity, transport)
        .build();
    Ok((config, c.not_before))
}

impl Server {
    pub fn start(opts: Options, listener: Arc<dyn Listener>) -> anyhow::Result<Self> {
        let allow = Arc::new(parse_allow(&opts.allow)?);
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("carlink-net")
            .enable_all()
            .build()?;
        let shared = Arc::new(Mutex::new(Shared {
            session: None,
            backpressure: Backpressure::default(),
            failures: HashMap::new(),
        }));
        let (config, week) = server_config(opts.port, &opts.code)?;
        let endpoint = Arc::new(rt.block_on(async { Endpoint::server(config) })?);
        log::info!("WebTransport listening on :{}, allow {:?}, cert week {week}", opts.port, opts.allow);

        // Rotate the certificate when a new week starts (pages pass this and
        // last week's hash, so either keeps working).
        {
            let endpoint = endpoint.clone();
            let code = opts.code.clone();
            let port = opts.port;
            rt.spawn(async move {
                let mut current = week;
                loop {
                    tokio::time::sleep(Duration::from_secs(600)).await;
                    if cert::week_start(cert::now_unix()) == current {
                        continue;
                    }
                    let rotated = server_config(port, &code).and_then(|(cfg, w)| {
                        endpoint.reload_config(cfg, false)?;
                        Ok(w)
                    });
                    match rotated {
                        Ok(w) => {
                            current = w;
                            log::info!("cert rotated to week {w}");
                        }
                        Err(e) => log::warn!("cert rotation failed: {e}"),
                    }
                }
            });
        }

        {
            let shared = shared.clone();
            let listener = listener.clone();
            let key = Arc::new(auth_key(&opts.code));
            let pending = Arc::new(Semaphore::new(MAX_PENDING));
            rt.spawn(async move {
                for id in 1u64.. {
                    let incoming = endpoint.accept().await;
                    let (shared, listener, key, pending, allow) = (shared.clone(), listener.clone(), key.clone(), pending.clone(), allow.clone());
                    tokio::spawn(async move {
                        if let Err(e) = handle_session(id, incoming, allow, key, shared, listener, pending).await {
                            log::info!("session {id} ended: {e}");
                        }
                    });
                }
            });
        }

        Ok(Server { rt, shared, listener })
    }

    /// Queue an encoded access unit. Returns false if it was dropped.
    pub fn send_frame(&self, pane: u16, flags: u8, pts_us: u64, data: &[u8]) -> bool {
        // Copy before taking the lock: frames are up to a few hundred KB and
        // the lock is shared with the control path and the rate task.
        let frame = Frame { pane, flags, pts_us, data: data.to_vec() };
        let admit = {
            let mut guard = self.shared.lock().unwrap();
            let s = &mut *guard;
            let Some(session) = s.session.as_ref() else { return false };
            let frames = &session.frames;
            s.backpressure.admit(pane, flags & FLAG_KEY != 0, Instant::now(), || {
                frames.try_send(frame).map_err(|e| match e {
                    mpsc::error::TrySendError::Full(_) => Queue::Full,
                    mpsc::error::TrySendError::Closed(_) => Queue::Closed,
                })
            })
        };
        if admit == Admit::DroppedNeedKeyframe {
            self.listener.on_keyframe_needed(pane);
        }
        admit == Admit::Sent
    }

    pub fn send_control(&self, json: &str) {
        let s = self.shared.lock().unwrap();
        if let Some(session) = s.session.as_ref() {
            // A page that stops reading its control stream loses messages
            // rather than growing memory.
            let _ = session.control.try_send(json.to_string());
        }
    }

    pub fn shutdown(self) {
        if let Some(s) = self.shared.lock().unwrap().session.take() {
            s.conn.close(wtransport::VarInt::from_u32(0), b"shutdown");
        }
        self.rt.shutdown_timeout(Duration::from_secs(1));
    }
}

async fn handle_session(
    id: u64,
    incoming: IncomingSession,
    allow: Arc<Allow>,
    key: Arc<ring::hmac::Key>,
    shared: Arc<Mutex<Shared>>,
    listener: Arc<dyn Listener>,
    pending: Arc<Semaphore>,
) -> anyhow::Result<()> {
    // Decide from the first packet, before any handshake: nothing (not even
    // the certificate, which is derived from the pairing code) goes to
    // addresses outside the car subnet or in auth backoff.
    let ip = incoming.remote_address().ip();
    let in_backoff = shared.lock().unwrap().failures.get(&ip).is_some_and(|(n, at)| at.elapsed() < backoff(*n));
    if !allowed(&allow, ip) || in_backoff {
        incoming.ignore();
        anyhow::bail!("ignored {ip}{}", if in_backoff { " (auth backoff)" } else { "" });
    }
    let Ok(pending_slot) = pending.try_acquire_owned() else {
        incoming.refuse();
        anyhow::bail!("refused {ip}: too many unauthenticated connections");
    };
    let request = tokio::time::timeout(HANDSHAKE_TIMEOUT, incoming.into_future()).await.map_err(|_| anyhow::anyhow!("handshake timeout from {ip}"))??;
    if request.path() != "/carlink" {
        let path = request.path().to_string();
        request.not_found().await;
        anyhow::bail!("rejected {ip} path {path}");
    }
    let conn = Arc::new(tokio::time::timeout(HANDSHAKE_TIMEOUT, request.accept()).await.map_err(|_| anyhow::anyhow!("accept timeout from {ip}"))??);
    let remote = conn.remote_address().to_string();

    // ---- authentication
    let auth = tokio::time::timeout(AUTH_TIMEOUT, async {
        let (mut send, recv) = conn.accept_bi().await?;
        let mut nonce = [0u8; 32];
        ring::rand::SecureRandom::fill(&ring::rand::SystemRandom::new(), &mut nonce)
            .map_err(|_| anyhow::anyhow!("no randomness"))?;
        write_line(&mut send, &format!(r#"{{"t":"challenge","v":2,"nonce":"{}"}}"#, B64.encode(nonce))).await?;
        let mut lines = LineReader { recv, buf: Vec::new() };
        let line = lines.next().await?.ok_or_else(|| anyhow::anyhow!("closed during auth"))?;
        let v: serde_json::Value = serde_json::from_str(&line)?;
        let mac = match (v.get("t").and_then(|t| t.as_str()), v.get("mac").and_then(|m| m.as_str())) {
            (Some("auth"), Some(mac)) => mac,
            _ => "",
        };
        let ok = B64.decode(mac).is_ok_and(|mac| auth_ok(&key, &nonce, &mac));
        anyhow::Ok((ok, send, lines))
    })
    .await;
    let (mut send, mut lines) = match auth {
        Ok(Ok((true, send, lines))) => (send, lines),
        other => {
            {
                let mut s = shared.lock().unwrap();
                let entry = s.failures.entry(ip).or_insert((0, Instant::now()));
                *entry = (entry.0 + 1, Instant::now());
            }
            conn.close(wtransport::VarInt::from_u32(1), b"auth");
            let why = match other {
                Ok(Ok(_)) => "bad mac".to_string(),
                Ok(Err(e)) => e.to_string(),
                Err(_) => "timeout".to_string(),
            };
            anyhow::bail!("auth failed from {remote}: {why}");
        }
    };
    write_line(&mut send, r#"{"t":"authok"}"#).await?;
    drop(pending_slot); // authenticated: no longer counts against MAX_PENDING
    log::info!("session {id} authenticated from {remote}");

    // ---- register (replaces and closes any previous session)
    let (frame_tx, frame_rx) = mpsc::channel::<Frame>(QUEUE_FRAMES);
    let (ctl_tx, mut ctl_rx) = mpsc::channel::<String>(CONTROL_QUEUE);
    let queue_probe = frame_tx.clone();
    {
        let mut s = shared.lock().unwrap();
        s.failures.remove(&ip);
        if let Some(old) = s.session.replace(SessionHandle { id, frames: frame_tx, control: ctl_tx, conn: conn.clone() }) {
            old.conn.close(wtransport::VarInt::from_u32(2), b"replaced");
            listener.on_session(false, "replaced");
        }
        s.backpressure.reset();
        // Called under the lock so session up/down events stay in order.
        listener.on_session(true, &remote);
    }
    // Each session starts from the middle level, not wherever the last one ended.
    listener.on_rate(START_LEVEL);
    let rate = {
        let (conn, shared, listener) = (conn.clone(), shared.clone(), listener.clone());
        tokio::spawn(async move {
            let mut rc = RateControl::new(Instant::now());
            let mut tick = tokio::time::interval(Duration::from_millis(200));
            loop {
                tick.tick().await;
                let (dropped, sent) = {
                    let mut s = shared.lock().unwrap();
                    // Replaced: the counters and the encoder belong to the new
                    // session now (this task is aborted shortly).
                    if s.session.as_ref().is_none_or(|h| h.id != id) {
                        break;
                    }
                    let bp = &mut s.backpressure;
                    (std::mem::take(&mut bp.overflows), std::mem::take(&mut bp.sent))
                };
                let sample = Sample {
                    rtt: conn.rtt(),
                    dropped,
                    sent,
                    queued: queue_probe.max_capacity() - queue_probe.capacity(),
                    queue_capacity: queue_probe.max_capacity(),
                };
                if let Some(level) = rc.tick(Instant::now(), sample) {
                    log::info!("rate level {level} ({sample:?})");
                    listener.on_rate(level);
                }
            }
        })
    };

    let echo = {
        let conn = conn.clone();
        tokio::spawn(async move {
            while let Ok(d) = conn.receive_datagram().await {
                let _ = conn.send_datagram(d.payload());
            }
        })
    };
    let sender = tokio::spawn(send_frames(conn.clone(), frame_rx));
    let writer = tokio::spawn(async move {
        while let Some(line) = ctl_rx.recv().await {
            if write_line(&mut send, &line).await.is_err() {
                break;
            }
        }
    });

    let is_current = || shared.lock().unwrap().session.as_ref().is_some_and(|h| h.id == id);
    let result: anyhow::Result<()> = async {
        let mut window = Instant::now();
        let mut count = 0u32;
        let mut heavy_count = 0u32;
        while let Some(line) = lines.next().await? {
            if line.is_empty() {
                continue;
            }
            if window.elapsed() >= Duration::from_secs(1) {
                window = Instant::now();
                count = 0;
                heavy_count = 0;
            }
            count += 1;
            anyhow::ensure!(count <= FLOOD_PER_SEC, "control flood");
            let Some((msg, kind)) = validate_control(&line) else { anyhow::bail!("invalid control message") };
            if kind == Kind::Heavy {
                heavy_count += 1;
            }
            if over_budget(count, heavy_count, kind) {
                continue; // skip intermediate touch moves / excess launches; keep downs/ups
            }
            if !is_current() {
                break;
            }
            listener.on_control(&msg);
        }
        Ok(())
    }
    .await;

    echo.abort();
    rate.abort();
    sender.abort();
    writer.abort();
    {
        let mut s = shared.lock().unwrap();
        if s.session.as_ref().is_some_and(|h| h.id == id) {
            s.session = None;
            listener.on_session(false, &remote);
        }
    }
    conn.close(wtransport::VarInt::from_u32(0), b"bye");
    result
}

async fn send_frames(conn: Arc<Connection>, mut rx: mpsc::Receiver<Frame>) {
    let permits = Arc::new(Semaphore::new(INFLIGHT_STREAMS));
    let epoch = Instant::now();
    let mut seqs: HashMap<u16, u32> = HashMap::new();
    while let Some(f) = rx.recv().await {
        let Ok(permit) = permits.clone().acquire_owned().await else { break };
        let Ok(opening) = conn.open_uni().await else { break };
        let seq = seqs.entry(f.pane).or_insert(0);
        let header = encode_header(f.flags, f.pane, *seq, f.pts_us, epoch.elapsed().as_micros() as u64);
        *seq = seq.wrapping_add(1);
        let key = f.flags & FLAG_KEY != 0;
        tokio::spawn(async move {
            let _permit = permit;
            if let Ok(mut s) = opening.await {
                // Decoding is strictly in order, so send oldest first; a
                // keyframe unblocks a waiting decoder, so it goes ahead.
                s.set_priority(if key { 1 } else { 0 });
                if s.write_all(&header).await.is_ok() && s.write_all(&f.data).await.is_ok() {
                    let _ = s.finish().await;
                }
            }
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn header_golden_vector() {
        let h = encode_header(1, 0x0102, 0xFFFF_FFFE, (1u64 << 40) + 5, 123_456_789);
        assert_eq!(hex::encode(h), "01010102fffffffe000001000000000500000000075bcd15");
    }

    #[test]
    fn auth_vector() {
        let nonce: Vec<u8> = (0u8..32).collect();
        let key = auth_key("abcd-efgh-jklm");
        let mac = auth_mac(&key, &nonce);
        assert_eq!(hex::encode(&mac), "6b5183c6e69c925ba2965bdb3a9b95730378219ba4f8fb84147ca63b33aaf1ad");
        assert!(auth_ok(&key, &nonce, &mac));
        assert!(!auth_ok(&auth_key("abcd-efgh-jkln"), &nonce, &mac));
    }

    #[test]
    fn allow_subnet() {
        let a = parse_allow("9.9.0.0/24").unwrap();
        assert!(allowed(&a, "9.9.0.2".parse().unwrap()));
        assert!(allowed(&a, "::ffff:9.9.0.2".parse().unwrap()));
        assert!(!allowed(&a, "9.9.1.2".parse().unwrap()));
        assert!(!allowed(&a, "192.168.1.5".parse().unwrap()));
        assert!(!allowed(&a, "::1".parse().unwrap()));
        assert!(allowed(&parse_allow("").unwrap(), "1.2.3.4".parse().unwrap()));
        assert!(parse_allow("9.9.0.0/33").is_err());
    }

    #[test]
    fn allow_list() {
        let a = parse_allow("9.9.0.0/24, 192.0.2.10").unwrap();
        assert!(allowed(&a, "9.9.0.2".parse().unwrap()));
        assert!(allowed(&a, "192.0.2.10".parse().unwrap()));
        assert!(!allowed(&a, "192.0.2.11".parse().unwrap()));
        assert!(parse_allow("9.9.0.0/24,").is_err());
        assert!(parse_allow("9.9.0.0/24,any").is_err());
    }

    #[test]
    fn control_validation() {
        assert_eq!(validate_control(r#"{"t":"keyframe","pane":0}"#), Some((r#"{"pane":0,"t":"keyframe"}"#.to_string(), Kind::Other)));
        assert!(validate_control(r#"{"t":"rm -rf"}"#).is_none());
        assert!(validate_control("not json").is_none());
        assert!(validate_control(r#"["t"]"#).is_none());
        let deep = "[".repeat(10_000) + &"]".repeat(10_000);
        assert!(validate_control(&deep).is_none()); // recursion limit, no stack overflow
    }

    #[test]
    fn control_validation_types() {
        // Every page->phone type in PROTOCOL.md passes, including milestone 3's notif-open.
        for t in ["hello", "layout", "apps", "launch", "touch", "key", "keyframe", "notif-open", "close", "close-all"] {
            assert!(validate_control(&format!(r#"{{"t":"{t}"}}"#)).is_some(), "{t}");
        }
        let (open, _) = validate_control(r#"{"t":"notif-open","id":"n3f9a1c"}"#).unwrap();
        assert_eq!(open, r#"{"id":"n3f9a1c","t":"notif-open"}"#);
        // Phone->page types (and near misses) can't be injected by the page.
        for t in ["notif", "notif-gone", "open", "opened", "icons", "error", "Notif-open", "notif-open ", ""] {
            assert!(validate_control(&format!(r#"{{"t":"{t}"}}"#)).is_none(), "{t:?}");
        }
        assert!(validate_control(r#"{"t":1}"#).is_none());
        assert!(validate_control(r#"{"id":"n1"}"#).is_none());
    }

    #[test]
    fn only_touch_moves_are_droppable() {
        let kind = |l: &str| validate_control(l).unwrap().1;
        assert_eq!(kind(r#"{"t":"touch","pane":0,"a":"move","id":0,"x":1,"y":2}"#), Kind::Move);
        assert_eq!(kind(r#"{"t":"touch","pane":0,"a":"down","id":0,"x":1,"y":2}"#), Kind::Other);
        assert_eq!(kind(r#"{"t":"touch","pane":0,"a":"up","id":0,"x":1,"y":2}"#), Kind::Other);
        assert_eq!(kind(r#"{"t":"launch","pane":0,"pkg":"a","cls":"b"}"#), Kind::Heavy);
        assert_eq!(kind(r#"{"t":"close-all","pane":0}"#), Kind::Heavy);
        assert_eq!(kind(r#"{"t":"keyframe","pane":0}"#), Kind::Other);
    }

    #[test]
    fn moves_dropped_only_over_budget() {
        assert!(!over_budget(1, 0, Kind::Move));
        assert!(!over_budget(MAX_CONTROL_PER_SEC, 0, Kind::Move));
        assert!(over_budget(MAX_CONTROL_PER_SEC + 1, 0, Kind::Move));
        assert!(over_budget(FLOOD_PER_SEC, 0, Kind::Move));
        // Downs, ups etc. are never dropped, however busy.
        assert!(!over_budget(MAX_CONTROL_PER_SEC + 1, 0, Kind::Other));
        assert!(!over_budget(FLOOD_PER_SEC, 0, Kind::Other));
        // Launches/closes: their own small budget, independent of touch traffic.
        assert!(!over_budget(FLOOD_PER_SEC, MAX_HEAVY_PER_SEC, Kind::Heavy));
        assert!(over_budget(2, MAX_HEAVY_PER_SEC + 1, Kind::Heavy));
        // Dropping must kick in well before the session is closed as a flood.
        const { assert!(FLOOD_PER_SEC > MAX_CONTROL_PER_SEC) };
    }

    #[test]
    fn allow_list_edges() {
        // Whitespace-only = allow all; /0 = everyone; host bits are masked off.
        assert!(parse_allow("  ").unwrap().is_none());
        let all = parse_allow("0.0.0.0/0").unwrap();
        assert!(allowed(&all, "203.0.113.7".parse().unwrap()));
        assert!(!allowed(&all, "::1".parse().unwrap()), "IPv6 never matches, even /0");
        let a = parse_allow("10.0.2.15/24").unwrap();
        assert_eq!(a, Some(vec![(u32::from(Ipv4Addr::new(10, 0, 2, 0)), 0xFFFF_FF00)]));
        assert!(allowed(&a, "10.0.2.200".parse().unwrap()));
        // A bare address is /32: only itself.
        let one = parse_allow("192.0.2.10").unwrap();
        assert!(!allowed(&one, "192.0.2.11".parse().unwrap()));
        assert!(allowed(&parse_allow("9.9.0.0/24,192.0.2.10").unwrap(), "::ffff:192.0.2.10".parse().unwrap()));
        for bad in ["9.9.0.0/", "9.9.0.0/-1", "9.9.0", "a.b.c.d", ",", "9.9.0.0/24,,1.2.3.4"] {
            assert!(parse_allow(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn allow_rejects_leading_zero_octets() {
        // Rust refuses octets like "01"; the app must not pass them through.
        assert!(parse_allow("01.2.3.4").is_err());
    }

    #[test]
    fn backoff_grows_and_caps() {
        assert_eq!(backoff(1), Duration::from_secs(1));
        assert_eq!(backoff(2), Duration::from_secs(2));
        assert_eq!(backoff(4), Duration::from_secs(8));
        assert_eq!(backoff(40), Duration::from_secs(60));
    }

    mod backpressure {
        use super::*;
        fn ok() -> Result<(), Queue> {
            Ok(())
        }
        fn full() -> Result<(), Queue> {
            Err(Queue::Full)
        }
        fn closed() -> Result<(), Queue> {
            Err(Queue::Closed)
        }

        #[test]
        fn passes_when_queue_has_room() {
            let mut b = Backpressure::default();
            assert_eq!(b.admit(0, false, Instant::now(), ok), Admit::Sent);
        }

        #[test]
        fn full_drops_until_keyframe() {
            let mut b = Backpressure::default();
            let t0 = Instant::now();
            assert_eq!(b.admit(0, false, t0, full), Admit::DroppedNeedKeyframe);
            assert_eq!(b.admit(0, false, t0, || panic!("must not send")), Admit::Dropped);
            assert_eq!(b.admit(0, true, t0, full), Admit::Dropped); // keyframe that doesn't fit
            assert_eq!(b.admit(0, true, t0, ok), Admit::Sent); // accepted keyframe clears
            assert_eq!(b.admit(0, false, t0, ok), Admit::Sent);
            // Only the two real overflows count, not the skipped frame.
            assert_eq!(b.overflows, 2);
        }

        #[test]
        fn keyframe_requests_are_rate_limited() {
            let mut b = Backpressure::default();
            let t0 = Instant::now();
            assert_eq!(b.admit(0, false, t0, full), Admit::DroppedNeedKeyframe);
            assert_eq!(b.admit(0, true, t0 + Duration::from_millis(249), full), Admit::Dropped);
            assert_eq!(b.admit(0, true, t0 + Duration::from_millis(250), full), Admit::DroppedNeedKeyframe);
        }

        #[test]
        fn keeps_asking_for_a_keyframe_while_skipping_frames() {
            // The first request's keyframe got lost: skipped frames ask again.
            let mut b = Backpressure::default();
            let t0 = Instant::now();
            assert_eq!(b.admit(0, false, t0, full), Admit::DroppedNeedKeyframe);
            assert_eq!(b.admit(0, false, t0 + Duration::from_millis(100), || panic!("must not send")), Admit::Dropped);
            assert_eq!(b.admit(0, false, t0 + Duration::from_millis(260), || panic!("must not send")), Admit::DroppedNeedKeyframe);
            assert_eq!(b.admit(0, false, t0 + Duration::from_millis(300), || panic!("must not send")), Admit::Dropped);
            // Skipped frames aren't overflows (rate control must not see them).
            assert_eq!(b.overflows, 1);
        }

        #[test]
        fn closed_queue_does_not_request_keyframes() {
            let mut b = Backpressure::default();
            assert_eq!(b.admit(0, false, Instant::now(), closed), Admit::Dropped);
            assert_eq!(b.admit(0, false, Instant::now(), ok), Admit::Sent);
        }

        #[test]
        fn counters_count_accepted_frames_and_real_overflows_only() {
            let mut b = Backpressure::default();
            let t0 = Instant::now();
            b.admit(0, true, t0, ok); // keyframes count as sent
            b.admit(0, false, t0, ok);
            assert_eq!((b.sent, b.overflows), (2, 0));
            b.admit(0, false, t0, full); // overflow, starts dropping
            b.admit(0, false, t0, ok); // skipped while waiting for a keyframe: neither
            b.admit(1, false, t0, closed); // closing session: neither
            assert_eq!((b.sent, b.overflows), (2, 1));
            // Rate-limited keyframe requests still count every overflow.
            b.admit(0, true, t0, full);
            assert_eq!(b.overflows, 2);
            b.admit(0, true, t0, ok);
            assert_eq!(b.sent, 3);
        }

        #[test]
        fn reset_clears_counters_and_keyframe_request_timer() {
            let mut b = Backpressure::default();
            let t0 = Instant::now();
            b.admit(0, false, t0, ok);
            assert_eq!(b.admit(0, false, t0, full), Admit::DroppedNeedKeyframe);
            b.reset();
            assert_eq!((b.sent, b.overflows), (0, 0));
            // A new session asks for a keyframe at once, not 250 ms later.
            assert_eq!(b.admit(0, false, t0, full), Admit::DroppedNeedKeyframe);
        }

        #[test]
        fn panes_are_independent_and_reset_clears() {
            let mut b = Backpressure::default();
            let t0 = Instant::now();
            b.admit(1, false, t0, full);
            assert_eq!(b.admit(0, false, t0, ok), Admit::Sent);
            assert_eq!(b.admit(1, false, t0, ok), Admit::Dropped);
            b.reset();
            assert_eq!(b.admit(1, false, t0, ok), Admit::Sent);
        }
    }
}
