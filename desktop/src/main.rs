//! PenBridge desktop companion.
//!
//! Receives pen and shortcut input from the PenBridge phone app (over USB or
//! Wi-Fi) and turns it into a real pen tablet on this computer.

mod adb;
mod discovery;
mod display;
mod keys;
mod log;
mod pen;
mod platform;
mod protocol;
mod server;

use std::net::{TcpListener, UdpSocket};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

pub const TCP_PORT: u16 = 47800;
pub const DISCOVERY_PORT: u16 = 47801;

struct Args {
    port: u16,
    no_adb: bool,
    adb: Option<PathBuf>,
    verbose: bool,
}

fn usage() -> ! {
    println!(
        "PenBridge {}\n\n\
         Usage: PenBridge [options]\n\n\
         \x20 --port <n>     TCP port to listen on (default {TCP_PORT}; Wi-Fi discovery uses port+1)\n\
         \x20 --no-adb       Don't set up USB connections\n\
         \x20 --adb <path>   Path to adb, if it isn't found automatically\n\
         \x20 --verbose      Log every pen sample (test mode)\n",
        env!("CARGO_PKG_VERSION")
    );
    std::process::exit(0);
}

fn parse_args() -> Args {
    let mut args = Args { port: TCP_PORT, no_adb: false, adb: None, verbose: false };
    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        match a.as_str() {
            "--port" => args.port = it.next().and_then(|v| v.parse().ok()).unwrap_or_else(|| usage()),
            "--no-adb" => args.no_adb = true,
            "--adb" => args.adb = Some(PathBuf::from(it.next().unwrap_or_else(|| usage()))),
            "--verbose" | "-v" => args.verbose = true,
            _ => usage(),
        }
    }
    args
}

fn host_name() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .ok()
        .or_else(|| std::fs::read_to_string("/etc/hostname").ok().map(|s| s.trim().to_string()))
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "My PC".into())
}

/// The LAN address other devices use to reach us (no packets are sent).
fn lan_ip() -> Option<String> {
    let s = UdpSocket::bind("0.0.0.0:0").ok()?;
    s.connect("192.0.2.1:9").ok()?;
    let ip = s.local_addr().ok()?.ip();
    (!ip.is_loopback() && !ip.is_unspecified()).then(|| ip.to_string())
}

/// When started by double-click, keep the window open so errors can be read.
fn wait_before_exit() {
    if cfg!(windows) {
        println!("\nPress Enter to close.");
        let _ = std::io::stdin().read_line(&mut String::new());
    }
}

fn main() {
    let args = parse_args();
    log::start();
    platform::init_process();

    let injector = platform::create(args.verbose);
    let host = host_name();

    println!("==============================================");
    println!(" PenBridge {}  -  phone pen tablet for your PC", env!("CARGO_PKG_VERSION"));
    println!("==============================================");
    if let Some(problem) = injector.startup_problem() {
        log::warn(&problem);
    }
    for (i, d) in injector.displays().iter().enumerate() {
        log::info(&format!(
            "Display {}: {} {}x{}{}",
            i + 1,
            d.name,
            d.width,
            d.height,
            if d.primary { " (main)" } else { "" }
        ));
    }

    let listener = match TcpListener::bind(("0.0.0.0", args.port)) {
        Ok(l) => l,
        Err(e) => {
            log::warn(&format!(
                "Can't listen on port {} ({e}). Is PenBridge already running in another window?",
                args.port
            ));
            wait_before_exit();
            std::process::exit(1);
        }
    };

    discovery::spawn(args.port.wrapping_add(1), args.port, host.clone(), injector.os_name());
    if !args.no_adb {
        adb::spawn(args.port, args.adb.clone());
    }

    match lan_ip() {
        Some(ip) => log::info(&format!("Wi-Fi: phone can connect to {ip} (port {}) - found automatically.", args.port)),
        None => log::info("Wi-Fi: no network address found."),
    }
    if cfg!(windows) {
        log::info("If Windows Firewall asks, allow PenBridge on Private networks.");
    }
    log::info("Waiting for the phone... (close this window to stop)");

    let shared: server::SharedInjector = Arc::new(Mutex::new(injector));
    if let Err(e) = server::run(listener, shared, host) {
        log::warn(&format!("server stopped: {e}"));
        wait_before_exit();
    }
}
