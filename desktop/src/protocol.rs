//! PenBridge wire protocol (version 1). See docs/PROTOCOL.md.
//!
//! Every message is a frame: `u16 length` (big-endian, counts the bytes that
//! follow), then `u8 type`, then the payload. Strings are `u16 length` + UTF-8.

use std::io::{self, Read};

use crate::display::Display;

pub const PROTOCOL_VERSION: u16 = 1;
const MAX_FRAME: usize = 8 * 1024;

pub const DISCOVERY_QUERY: &str = "PENBRIDGE?1";
pub const DISCOVERY_REPLY: &str = "PENBRIDGE!1";

pub mod msg {
    // phone -> PC
    pub const HELLO: u8 = 0x01;
    pub const SELECT_DISPLAY: u8 = 0x02;
    pub const PEN: u8 = 0x10;
    pub const KEY: u8 = 0x20;
    pub const PING: u8 = 0x30;
    pub const BYE: u8 = 0x7F;
    // PC -> phone
    pub const WELCOME: u8 = 0x81;
    pub const NOTICE: u8 = 0x83;
    pub const PONG: u8 = 0xB0;
}

pub mod pen_flags {
    pub const IN_RANGE: u8 = 0x01;
    pub const TIP: u8 = 0x02;
    pub const BARREL: u8 = 0x04;
    pub const ERASER: u8 = 0x08;
}

/// Index meaning "map the pad to all displays at once".
pub const ALL_DISPLAYS: u8 = 0xFF;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PenSample {
    pub flags: u8,
    /// 0..=65535 across the pad's active area.
    pub x: u16,
    pub y: u16,
    /// 0..=65535
    pub pressure: u16,
    /// Degrees, -90..=90.
    pub tilt_x: i8,
    pub tilt_y: i8,
}

impl PenSample {
    pub fn in_range(&self) -> bool {
        self.flags & pen_flags::IN_RANGE != 0
    }
    pub fn tip(&self) -> bool {
        self.flags & pen_flags::TIP != 0
    }
    pub fn barrel(&self) -> bool {
        self.flags & pen_flags::BARREL != 0
    }
    pub fn eraser(&self) -> bool {
        self.flags & pen_flags::ERASER != 0
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KeyAction {
    Down,
    Up,
    Tap,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ClientMsg {
    Hello { version: u16, device: String, app_version: String },
    SelectDisplay { index: u8 },
    Pen(PenSample),
    Key { action: KeyAction, combo: String },
    Ping { id: u32 },
    Bye,
    Unknown(u8),
}

#[derive(Debug, PartialEq, Eq)]
pub struct ProtoError(pub String);

impl std::fmt::Display for ProtoError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

struct Cursor<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }
    fn take(&mut self, n: usize) -> Result<&'a [u8], ProtoError> {
        if self.pos + n > self.buf.len() {
            return Err(ProtoError(format!(
                "message too short (need {} more bytes at offset {})",
                n, self.pos
            )));
        }
        let s = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        Ok(s)
    }
    fn u8(&mut self) -> Result<u8, ProtoError> {
        Ok(self.take(1)?[0])
    }
    fn i8(&mut self) -> Result<i8, ProtoError> {
        Ok(self.u8()? as i8)
    }
    fn u16(&mut self) -> Result<u16, ProtoError> {
        let b = self.take(2)?;
        Ok(u16::from_be_bytes([b[0], b[1]]))
    }
    fn u32(&mut self) -> Result<u32, ProtoError> {
        let b = self.take(4)?;
        Ok(u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
    }
    fn string(&mut self) -> Result<String, ProtoError> {
        let n = self.u16()? as usize;
        let b = self.take(n)?;
        String::from_utf8(b.to_vec()).map_err(|_| ProtoError("invalid UTF-8 string".into()))
    }
}

/// Reads one frame body (type byte + payload) into `buf`.
pub fn read_frame<R: Read>(r: &mut R, buf: &mut Vec<u8>) -> io::Result<()> {
    let mut len = [0u8; 2];
    r.read_exact(&mut len)?;
    let len = u16::from_be_bytes(len) as usize;
    if len == 0 || len > MAX_FRAME {
        return Err(io::Error::new(io::ErrorKind::InvalidData, format!("bad frame length {len}")));
    }
    buf.resize(len, 0);
    r.read_exact(buf)
}

pub fn parse_client(frame: &[u8]) -> Result<ClientMsg, ProtoError> {
    let mut c = Cursor::new(frame);
    let ty = c.u8()?;
    Ok(match ty {
        msg::HELLO => ClientMsg::Hello {
            version: c.u16()?,
            device: c.string()?,
            app_version: c.string()?,
        },
        msg::SELECT_DISPLAY => ClientMsg::SelectDisplay { index: c.u8()? },
        msg::PEN => ClientMsg::Pen(PenSample {
            flags: c.u8()?,
            x: c.u16()?,
            y: c.u16()?,
            pressure: c.u16()?,
            tilt_x: c.i8()?,
            tilt_y: c.i8()?,
        }),
        msg::KEY => {
            let action = match c.u8()? {
                1 => KeyAction::Down,
                2 => KeyAction::Up,
                3 => KeyAction::Tap,
                other => return Err(ProtoError(format!("unknown key action {other}"))),
            };
            ClientMsg::Key { action, combo: c.string()? }
        }
        msg::PING => ClientMsg::Ping { id: c.u32()? },
        msg::BYE => ClientMsg::Bye,
        other => ClientMsg::Unknown(other),
    })
}

/// Builds one outgoing frame, adding the length prefix in `finish`.
pub struct FrameWriter {
    buf: Vec<u8>,
}

impl FrameWriter {
    pub fn new(ty: u8) -> Self {
        let mut buf = Vec::with_capacity(64);
        buf.extend_from_slice(&[0, 0, ty]);
        Self { buf }
    }
    pub fn u8(mut self, v: u8) -> Self {
        self.buf.push(v);
        self
    }
    pub fn u16(mut self, v: u16) -> Self {
        self.buf.extend_from_slice(&v.to_be_bytes());
        self
    }
    pub fn u32(mut self, v: u32) -> Self {
        self.buf.extend_from_slice(&v.to_be_bytes());
        self
    }
    pub fn i32(mut self, v: i32) -> Self {
        self.buf.extend_from_slice(&v.to_be_bytes());
        self
    }
    pub fn string(mut self, s: &str) -> Self {
        // Cut on a char boundary so the length always fits in a u16.
        let mut end = s.len().min(1024);
        while !s.is_char_boundary(end) {
            end -= 1;
        }
        self.buf.extend_from_slice(&(end as u16).to_be_bytes());
        self.buf.extend_from_slice(&s.as_bytes()[..end]);
        self
    }
    pub fn finish(mut self) -> Vec<u8> {
        let len = (self.buf.len() - 2) as u16;
        self.buf[..2].copy_from_slice(&len.to_be_bytes());
        self.buf
    }
}

pub fn encode_welcome(host: &str, os: &str, displays: &[Display]) -> Vec<u8> {
    let mut w = FrameWriter::new(msg::WELCOME)
        .u16(PROTOCOL_VERSION)
        .string(host)
        .string(os)
        .u8(displays.len().min(32) as u8);
    for d in displays.iter().take(32) {
        w = w
            .i32(d.x)
            .i32(d.y)
            .i32(d.width)
            .i32(d.height)
            .u8(d.primary as u8)
            .string(&d.name);
    }
    w.finish()
}

pub fn encode_notice(text: &str) -> Vec<u8> {
    FrameWriter::new(msg::NOTICE).string(text).finish()
}

pub fn encode_pong(id: u32) -> Vec<u8> {
    FrameWriter::new(msg::PONG).u32(id).finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_pen() {
        let frame = [msg::PEN, 0x03, 0x12, 0x34, 0xFF, 0xFF, 0x80, 0x00, 0xF6, 0x05];
        let m = parse_client(&frame).unwrap();
        assert_eq!(
            m,
            ClientMsg::Pen(PenSample {
                flags: 3,
                x: 0x1234,
                y: 0xFFFF,
                pressure: 0x8000,
                tilt_x: -10,
                tilt_y: 5
            })
        );
        if let ClientMsg::Pen(p) = m {
            assert!(p.in_range() && p.tip() && !p.barrel() && !p.eraser());
        }
    }

    #[test]
    fn parses_hello_and_key() {
        let mut f = vec![msg::HELLO, 0, 1, 0, 3];
        f.extend_from_slice(b"S24");
        f.extend_from_slice(&[0, 5]);
        f.extend_from_slice(b"0.1.0");
        assert_eq!(
            parse_client(&f).unwrap(),
            ClientMsg::Hello { version: 1, device: "S24".into(), app_version: "0.1.0".into() }
        );
        let mut k = vec![msg::KEY, 3, 0, 6];
        k.extend_from_slice(b"ctrl+z");
        assert_eq!(
            parse_client(&k).unwrap(),
            ClientMsg::Key { action: KeyAction::Tap, combo: "ctrl+z".into() }
        );
    }

    #[test]
    fn rejects_truncated() {
        assert!(parse_client(&[msg::PEN, 1, 2]).is_err());
        assert!(parse_client(&[msg::KEY, 9, 0, 0]).is_err());
    }

    #[test]
    fn frames_round_trip() {
        let bytes = encode_pong(0xDEADBEEF);
        let mut r = &bytes[..];
        let mut buf = Vec::new();
        read_frame(&mut r, &mut buf).unwrap();
        assert_eq!(buf, vec![msg::PONG, 0xDE, 0xAD, 0xBE, 0xEF]);
    }

    #[test]
    fn welcome_layout() {
        let d = Display { name: "A".into(), x: -1920, y: 0, width: 1920, height: 1080, primary: true };
        let b = encode_welcome("pc", "windows", &[d]);
        assert_eq!(u16::from_be_bytes([b[0], b[1]]) as usize, b.len() - 2);
        assert_eq!(b[2], msg::WELCOME);
        // version
        assert_eq!(&b[3..5], &[0, 1]);
    }
}
