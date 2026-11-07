//! Tiny console logger with a running clock.

use std::sync::OnceLock;
use std::time::Instant;

static START: OnceLock<Instant> = OnceLock::new();

pub fn start() {
    START.get_or_init(Instant::now);
}

fn stamp() -> String {
    let secs = START.get_or_init(Instant::now).elapsed().as_secs();
    format!("{:02}:{:02}:{:02}", secs / 3600, (secs / 60) % 60, secs % 60)
}

pub fn info(msg: &str) {
    println!("[{}] {}", stamp(), msg);
}

pub fn warn(msg: &str) {
    println!("[{}] WARNING: {}", stamp(), msg);
}
