//! Answers the phone's Wi-Fi broadcast so it can find this PC without typing an IP.

use std::net::UdpSocket;
use std::thread;

use crate::log;
use crate::protocol::{DISCOVERY_QUERY, DISCOVERY_REPLY};

pub fn spawn(discovery_port: u16, tcp_port: u16, host: String, os: &'static str) {
    let socket = match UdpSocket::bind(("0.0.0.0", discovery_port)) {
        Ok(s) => s,
        Err(e) => {
            log::warn(&format!(
                "Wi-Fi auto-discovery is off (UDP port {discovery_port} unavailable: {e}). \
                 You can still type this PC's IP address in the phone app."
            ));
            return;
        }
    };
    thread::spawn(move || {
        let reply = format!("{DISCOVERY_REPLY}|{tcp_port}|{host}|{os}");
        let mut buf = [0u8; 256];
        loop {
            match socket.recv_from(&mut buf) {
                Ok((n, from)) => {
                    if buf[..n].starts_with(DISCOVERY_QUERY.as_bytes()) {
                        let _ = socket.send_to(reply.as_bytes(), from);
                    }
                }
                Err(_) => thread::sleep(std::time::Duration::from_millis(200)),
            }
        }
    });
}
