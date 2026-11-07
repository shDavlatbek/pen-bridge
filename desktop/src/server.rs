//! Accepts the phone's TCP connection (over USB via adb, or over Wi-Fi) and
//! turns its messages into pen and keyboard input.

use std::collections::HashMap;
use std::io::{self, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use crate::display::{self, Display};
use crate::keys::{parse_combo, KeyCombo};
use crate::log;
use crate::pen::{PenEvent, PenTracker};
use crate::platform::Injector;
use crate::protocol::{self, ClientMsg, KeyAction, PROTOCOL_VERSION};

pub type SharedInjector = Arc<Mutex<Box<dyn Injector>>>;

/// Runs forever, serving one phone at a time. A new connection replaces the old one.
pub fn run(listener: TcpListener, injector: SharedInjector, host: String) -> io::Result<()> {
    let current: Arc<Mutex<Option<TcpStream>>> = Arc::new(Mutex::new(None));
    for stream in listener.incoming() {
        let stream = match stream {
            Ok(s) => s,
            Err(e) => {
                log::warn(&format!("connection failed: {e}"));
                continue;
            }
        };
        if let Ok(clone) = stream.try_clone() {
            if let Some(old) = current.lock().unwrap().replace(clone) {
                let _ = old.shutdown(Shutdown::Both);
            }
        }
        let injector = injector.clone();
        let host = host.clone();
        thread::spawn(move || {
            let peer = stream.peer_addr().map(|a| a.to_string()).unwrap_or_else(|_| "?".into());
            let mut session = Session::new(stream, injector);
            if let Err(e) = session.serve(&host) {
                if !matches!(e.kind(), io::ErrorKind::UnexpectedEof | io::ErrorKind::ConnectionReset) {
                    log::info(&format!("Connection to {peer} ended: {e}"));
                }
            }
            session.cleanup();
            log::info(&format!("Phone disconnected ({}).", session.link));
        });
    }
    Ok(())
}

struct Session {
    stream: TcpStream,
    injector: SharedInjector,
    link: &'static str,
    displays: Vec<Display>,
    target: Option<Display>,
    pen: PenTracker,
    events: Vec<PenEvent>,
    held: HashMap<String, KeyCombo>,
    pen_errors: u32,
    notice_sent: bool,
}

impl Session {
    fn new(stream: TcpStream, injector: SharedInjector) -> Self {
        let link = match stream.peer_addr() {
            Ok(a) if a.ip().is_loopback() => "USB",
            _ => "Wi-Fi",
        };
        Self {
            stream,
            injector,
            link,
            displays: Vec::new(),
            target: None,
            pen: PenTracker::new(),
            events: Vec::with_capacity(4),
            held: HashMap::new(),
            pen_errors: 0,
            notice_sent: false,
        }
    }

    fn send(&mut self, bytes: &[u8]) -> io::Result<()> {
        self.stream.write_all(bytes)
    }

    fn notice(&mut self, text: &str) {
        log::warn(text);
        let _ = self.send(&protocol::encode_notice(text));
    }

    fn select_display(&mut self, index: u8) {
        self.displays = self.injector.lock().unwrap().displays();
        self.target = display::pick(&self.displays, index);
        if let Some(d) = &self.target {
            log::info(&format!("Pad mapped to {} ({}x{} at {},{}).", d.name, d.width, d.height, d.x, d.y));
        }
    }

    fn serve(&mut self, host: &str) -> io::Result<()> {
        self.stream.set_nodelay(true)?;
        // The phone pings every second; silence for this long means it's gone.
        self.stream.set_read_timeout(Some(Duration::from_secs(6)))?;
        let mut reader = self.stream.try_clone()?;
        let mut frame = Vec::with_capacity(64);

        protocol::read_frame(&mut reader, &mut frame)?;
        match protocol::parse_client(&frame) {
            Ok(ClientMsg::Hello { version, device, app_version }) => {
                log::info(&format!("Phone connected over {}: {device} (app {app_version}).", self.link));
                if version != PROTOCOL_VERSION {
                    self.notice(&format!(
                        "Version mismatch: phone uses protocol {version}, PC uses {PROTOCOL_VERSION}. \
                         Please update both apps."
                    ));
                    return Ok(());
                }
            }
            _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "expected HELLO")),
        }

        let (os, problem, displays) = {
            let inj = self.injector.lock().unwrap();
            (inj.os_name(), inj.startup_problem(), inj.displays())
        };
        self.displays = displays;
        let welcome = protocol::encode_welcome(host, os, &self.displays);
        self.send(&welcome)?;
        self.target = display::pick(&self.displays, display::primary_index(&self.displays));
        if let Some(p) = problem {
            self.notice(&p);
        }

        loop {
            protocol::read_frame(&mut reader, &mut frame)?;
            let msg = match protocol::parse_client(&frame) {
                Ok(m) => m,
                Err(e) => {
                    log::warn(&format!("ignoring bad message: {e}"));
                    continue;
                }
            };
            match msg {
                ClientMsg::Pen(sample) => {
                    let Some(target) = &self.target else { continue };
                    let (x, y) = target.map(sample.x, sample.y);
                    self.events.clear();
                    self.pen.update(&sample, x, y, &mut self.events);
                    self.inject_events();
                }
                ClientMsg::Key { action, combo } => self.key(action, &combo),
                ClientMsg::SelectDisplay { index } => self.select_display(index),
                ClientMsg::Ping { id } => self.send(&protocol::encode_pong(id))?,
                ClientMsg::Bye => return Ok(()),
                ClientMsg::Hello { .. } | ClientMsg::Unknown(_) => {}
            }
        }
    }

    fn inject_events(&mut self) {
        if self.events.is_empty() {
            return;
        }
        let mut failure = None;
        {
            let mut inj = self.injector.lock().unwrap();
            for ev in &self.events {
                if let Err(e) = inj.pen(ev) {
                    failure = Some(e);
                }
            }
        }
        if let Some(e) = failure {
            self.pen_errors += 1;
            if !self.notice_sent {
                self.notice_sent = true;
                self.notice(&format!("Pen problem on the PC: {e}"));
            } else if self.pen_errors % 500 == 0 {
                log::warn(&format!("{e} ({} times)", self.pen_errors));
            }
        }
    }

    fn key(&mut self, action: KeyAction, combo_text: &str) {
        let combo = match parse_combo(combo_text) {
            Ok(c) => c,
            Err(e) => {
                self.notice(&format!("Shortcut \"{combo_text}\" not understood: {e}"));
                return;
            }
        };
        let result = {
            let mut inj = self.injector.lock().unwrap();
            match action {
                KeyAction::Tap => inj.tap(&combo),
                KeyAction::Down => {
                    if self.held.contains_key(combo_text) {
                        Ok(())
                    } else {
                        self.held.insert(combo_text.to_string(), combo.clone());
                        inj.key_down(&combo)
                    }
                }
                KeyAction::Up => match self.held.remove(combo_text) {
                    Some(c) => inj.key_up(&c),
                    None => Ok(()),
                },
            }
        };
        if let Err(e) = result {
            self.notice(&e);
        }
    }

    /// Lifts the pen and releases held keys so nothing stays "stuck" on the PC.
    fn cleanup(&mut self) {
        self.events.clear();
        self.pen.reset(&mut self.events);
        self.inject_events();
        let held: Vec<KeyCombo> = self.held.drain().map(|(_, c)| c).collect();
        let mut inj = self.injector.lock().unwrap();
        for combo in held {
            let _ = inj.key_up(&combo);
        }
    }
}
