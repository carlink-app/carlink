//! Run the core on the desktop (no Android) for protocol debugging.
//!   cargo run --example devserver -- [CODE] [PORT]
use std::sync::Arc;

struct Log;
impl carlink_core::server::Listener for Log {
    fn on_session(&self, connected: bool, remote: &str) {
        println!("session {} {remote}", if connected { "up" } else { "down" });
    }
    fn on_control(&self, json: &str) {
        println!("control: {json}");
    }
    fn on_keyframe_needed(&self, pane: u16) {
        println!("keyframe needed: pane {pane}");
    }
}

struct Stdout;
impl log::Log for Stdout {
    fn enabled(&self, m: &log::Metadata) -> bool {
        m.level() <= log::Level::Debug && !m.target().starts_with("rustls")
    }
    fn log(&self, r: &log::Record) {
        if self.enabled(r.metadata()) {
            println!("[{}] {}: {}", r.level(), r.target(), r.args());
        }
    }
    fn flush(&self) {}
}

fn main() -> anyhow::Result<()> {
    log::set_logger(&Stdout).ok();
    log::set_max_level(log::LevelFilter::Debug);
    let mut args = std::env::args().skip(1);
    let code = args.next().unwrap_or_else(|| "TEST-CODE-0001".into());
    let port: u16 = args.next().map(|p| p.parse()).transpose()?.unwrap_or(4433);
    println!("cert {}", carlink_core::cert::make(&code, carlink_core::cert::now_unix()).hash_hex());
    let opts = carlink_core::server::Options { port, code, allow: String::new() };
    let _server = carlink_core::server::Server::start(opts, Arc::new(Log))?;
    std::thread::park();
    Ok(())
}
