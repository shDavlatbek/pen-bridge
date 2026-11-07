//! Windows backend.
//!
//! The pen is a synthetic Windows Ink pen (InjectSyntheticPointerInput, Windows 10
//! 1809 or newer), so apps get pressure, tilt, hover, the barrel button and the
//! eraser. In Clip Studio Paint set File > Preferences > Tablet > "TabletPC".
//! Keys and the scroll wheel go through SendInput.

use std::mem::{size_of, zeroed};

use windows_sys::Win32::Foundation::{GetLastError, BOOL, LPARAM, POINT, RECT, TRUE};
use windows_sys::Win32::Graphics::Gdi::{EnumDisplayMonitors, GetMonitorInfoW, HDC, HMONITOR, MONITORINFO, MONITORINFOEXW};
use windows_sys::Win32::UI::Controls::{
    CreateSyntheticPointerDevice, DestroySyntheticPointerDevice, HSYNTHETICPOINTERDEVICE, POINTER_FEEDBACK_DEFAULT,
    POINTER_TYPE_INFO,
};
use windows_sys::Win32::UI::HiDpi::{SetProcessDpiAwarenessContext, DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2};
use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
    MapVirtualKeyW, SendInput, VkKeyScanW, INPUT, INPUT_KEYBOARD, INPUT_MOUSE, KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP,
    MAPVK_VK_TO_VSC, MOUSEEVENTF_HWHEEL, MOUSEEVENTF_WHEEL,
};
use windows_sys::Win32::UI::Input::Pointer::{
    InjectSyntheticPointerInput, POINTER_CHANGE_FIRSTBUTTON_DOWN, POINTER_CHANGE_FIRSTBUTTON_UP, POINTER_CHANGE_NONE,
    POINTER_FLAG_DOWN, POINTER_FLAG_FIRSTBUTTON, POINTER_FLAG_INCONTACT, POINTER_FLAG_INRANGE, POINTER_FLAG_PRIMARY,
    POINTER_FLAG_UP, POINTER_FLAG_UPDATE, POINTER_INFO, POINTER_PEN_INFO,
};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    MONITORINFOF_PRIMARY, PEN_FLAG_BARREL, PEN_FLAG_ERASER, PEN_FLAG_INVERTED, PEN_FLAG_NONE, PEN_MASK_PRESSURE,
    PEN_MASK_TILT_X, PEN_MASK_TILT_Y, PT_PEN,
};

use super::Injector;
use crate::display::Display;
use crate::keys::{Key, KeyCombo, Modifier, NamedKey};
use crate::log;
use crate::pen::{PenEvent, PenPhase};

pub fn init_process() {
    // Work in physical pixels so the pad maps exactly onto scaled (125%, 150%...) monitors.
    unsafe {
        SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    }
}

struct PenDevice(HSYNTHETICPOINTERDEVICE);
// The handle is only used from one thread at a time (behind a Mutex).
unsafe impl Send for PenDevice {}

impl Drop for PenDevice {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe { DestroySyntheticPointerDevice(self.0) };
        }
    }
}

pub struct WinInjector {
    pen: PenDevice,
    create_error: Option<u32>,
    /// Which optional pointer flags to leave out (bit 0: PRIMARY, bit 1: FIRSTBUTTON).
    /// If Windows rejects an event we try the other combinations and keep the one that works.
    compat: u8,
}

impl WinInjector {
    pub fn new() -> Self {
        let dev = unsafe { CreateSyntheticPointerDevice(PT_PEN, 1, POINTER_FEEDBACK_DEFAULT) };
        let create_error = if dev.is_null() { Some(unsafe { GetLastError() }) } else { None };
        Self { pen: PenDevice(dev), create_error, compat: 0 }
    }

    fn inject(&self, ev: &PenEvent, compat: u8) -> Result<(), u32> {
        let (mut flags, change) = match ev.phase {
            PenPhase::Hover => (POINTER_FLAG_INRANGE | POINTER_FLAG_UPDATE, POINTER_CHANGE_NONE),
            PenPhase::Down => (
                POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT | POINTER_FLAG_FIRSTBUTTON | POINTER_FLAG_DOWN,
                POINTER_CHANGE_FIRSTBUTTON_DOWN,
            ),
            PenPhase::Move => (
                POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT | POINTER_FLAG_FIRSTBUTTON | POINTER_FLAG_UPDATE,
                POINTER_CHANGE_NONE,
            ),
            PenPhase::Up => (POINTER_FLAG_INRANGE | POINTER_FLAG_UP, POINTER_CHANGE_FIRSTBUTTON_UP),
            // An update without INRANGE takes the pen out of range.
            PenPhase::Leave => (POINTER_FLAG_UPDATE, POINTER_CHANGE_NONE),
        };
        if compat & 1 == 0 {
            flags |= POINTER_FLAG_PRIMARY;
        }
        if compat & 2 != 0 {
            flags &= !POINTER_FLAG_FIRSTBUTTON;
        }

        let touching = matches!(ev.phase, PenPhase::Down | PenPhase::Move);
        let mut pen_flags = PEN_FLAG_NONE;
        if ev.barrel {
            pen_flags |= PEN_FLAG_BARREL;
        }
        if ev.eraser {
            pen_flags |= PEN_FLAG_INVERTED;
            if touching {
                pen_flags |= PEN_FLAG_ERASER;
            }
        }

        unsafe {
            let mut pointer: POINTER_INFO = zeroed();
            pointer.pointerType = PT_PEN;
            pointer.pointerId = 0;
            pointer.pointerFlags = flags;
            pointer.ptPixelLocation = POINT { x: ev.x, y: ev.y };
            pointer.ptPixelLocationRaw = POINT { x: ev.x, y: ev.y };
            pointer.ButtonChangeType = change;

            let mut info: POINTER_TYPE_INFO = zeroed();
            info.r#type = PT_PEN;
            info.Anonymous.penInfo = POINTER_PEN_INFO {
                pointerInfo: pointer,
                penFlags: pen_flags,
                penMask: PEN_MASK_PRESSURE | PEN_MASK_TILT_X | PEN_MASK_TILT_Y,
                pressure: if touching { (ev.pressure.clamp(0.0, 1.0) * 1024.0).round() as u32 } else { 0 },
                rotation: 0,
                tiltX: ev.tilt_x.clamp(-90, 90),
                tiltY: ev.tilt_y.clamp(-90, 90),
            };

            if InjectSyntheticPointerInput(self.pen.0, &info, 1) == 0 {
                return Err(GetLastError());
            }
        }
        Ok(())
    }
}

impl Injector for WinInjector {
    fn os_name(&self) -> &'static str {
        "windows"
    }

    fn startup_problem(&self) -> Option<String> {
        self.create_error.map(|code| {
            format!(
                "Windows refused to create the virtual pen (error {code}). \
                 PenBridge needs Windows 10 version 1809 or newer."
            )
        })
    }

    fn displays(&self) -> Vec<Display> {
        let mut list: Vec<Display> = Vec::new();
        unsafe extern "system" fn each(mon: HMONITOR, _: HDC, _: *mut RECT, data: LPARAM) -> BOOL {
            let list = &mut *(data as *mut Vec<Display>);
            let mut info: MONITORINFOEXW = zeroed();
            info.monitorInfo.cbSize = size_of::<MONITORINFOEXW>() as u32;
            if GetMonitorInfoW(mon, &mut info as *mut MONITORINFOEXW as *mut MONITORINFO) != 0 {
                let r = info.monitorInfo.rcMonitor;
                let len = info.szDevice.iter().position(|&c| c == 0).unwrap_or(info.szDevice.len());
                let name = String::from_utf16_lossy(&info.szDevice[..len]);
                list.push(Display {
                    name: name.trim_start_matches(r"\\.\").to_string(),
                    x: r.left,
                    y: r.top,
                    width: r.right - r.left,
                    height: r.bottom - r.top,
                    primary: info.monitorInfo.dwFlags & MONITORINFOF_PRIMARY != 0,
                });
            }
            TRUE
        }
        unsafe {
            EnumDisplayMonitors(std::ptr::null_mut(), std::ptr::null(), Some(each), &mut list as *mut _ as LPARAM);
        }
        // Left-to-right, then top-to-bottom, so "Display 1" is the leftmost.
        list.sort_by_key(|d| (d.x, d.y));
        list
    }

    fn pen(&mut self, ev: &PenEvent) -> Result<(), String> {
        if self.pen.0.is_null() {
            return Err("virtual pen is not available".into());
        }
        let first = match self.inject(ev, self.compat) {
            Ok(()) => return Ok(()),
            Err(code) => code,
        };
        for compat in 0..4u8 {
            if compat != self.compat && self.inject(ev, compat).is_ok() {
                log::info(&format!("Pen: switched to compatibility mode {compat}."));
                self.compat = compat;
                return Ok(());
            }
        }
        Err(format!("pen injection failed (Windows error {first})"))
    }

    fn key_down(&mut self, combo: &KeyCombo) -> Result<(), String> {
        let plan = plan_combo(combo)?;
        let mut inputs = Vec::new();
        for &vk in &plan.modifiers {
            inputs.push(key_input(vk, false));
        }
        match plan.main {
            Some(Main::Key(vk)) => inputs.push(key_input(vk, false)),
            Some(Main::Wheel { horizontal, delta }) => inputs.push(wheel_input(horizontal, delta)),
            None => {}
        }
        send(&inputs)
    }

    fn key_up(&mut self, combo: &KeyCombo) -> Result<(), String> {
        let plan = plan_combo(combo)?;
        let mut inputs = Vec::new();
        if let Some(Main::Key(vk)) = plan.main {
            inputs.push(key_input(vk, true));
        }
        for &vk in plan.modifiers.iter().rev() {
            inputs.push(key_input(vk, true));
        }
        send(&inputs)
    }
}

enum Main {
    Key(u16),
    Wheel { horizontal: bool, delta: i32 },
}

struct Plan {
    /// Virtual-key codes to hold, in press order.
    modifiers: Vec<u16>,
    main: Option<Main>,
}

const VK_SHIFT: u16 = 0x10;
const VK_CONTROL: u16 = 0x11;
const VK_MENU: u16 = 0x12;
const VK_LWIN: u16 = 0x5B;

fn modifier_vk(m: Modifier) -> u16 {
    match m {
        Modifier::Ctrl => VK_CONTROL,
        Modifier::Shift => VK_SHIFT,
        Modifier::Alt => VK_MENU,
        Modifier::Meta => VK_LWIN,
    }
}

fn named_vk(k: NamedKey) -> u16 {
    use NamedKey::*;
    match k {
        Space => 0x20,
        Tab => 0x09,
        Enter => 0x0D,
        Escape => 0x1B,
        Backspace => 0x08,
        Delete => 0x2E,
        Insert => 0x2D,
        Home => 0x24,
        End => 0x23,
        PageUp => 0x21,
        PageDown => 0x22,
        Up => 0x26,
        Down => 0x28,
        Left => 0x25,
        Right => 0x27,
        CapsLock => 0x14,
        F(n) => 0x70 + (n as u16 - 1),
        Plus => 0xBB,  // VK_OEM_PLUS
        Minus => 0xBD, // VK_OEM_MINUS
        NumPlus => 0x6B,
        NumMinus => 0x6D,
        NumMultiply => 0x6A,
        NumDivide => 0x6F,
        Num(n) => 0x60 + n as u16,
    }
}

fn plan_combo(combo: &KeyCombo) -> Result<Plan, String> {
    let mut modifiers: Vec<u16> = combo.modifiers.iter().map(|&m| modifier_vk(m)).collect();
    let main = match combo.key {
        None => None,
        Some(Key::Named(k)) => Some(Main::Key(named_vk(k))),
        Some(Key::WheelUp) => Some(Main::Wheel { horizontal: false, delta: 120 }),
        Some(Key::WheelDown) => Some(Main::Wheel { horizontal: false, delta: -120 }),
        Some(Key::WheelRight) => Some(Main::Wheel { horizontal: true, delta: 120 }),
        Some(Key::WheelLeft) => Some(Main::Wheel { horizontal: true, delta: -120 }),
        Some(Key::Char(c)) => {
            let vk = char_vk(c).ok_or_else(|| format!("this keyboard layout has no \"{c}\" key"))?;
            // If the layout needs extra modifiers to type this character, add them.
            let shift_state = (vk >> 8) & 0xFF;
            for (bit, m) in [(1, VK_SHIFT), (2, VK_CONTROL), (4, VK_MENU)] {
                if shift_state & bit != 0 && !modifiers.contains(&m) {
                    modifiers.push(m);
                }
            }
            Some(Main::Key(vk & 0xFF))
        }
    };
    Ok(Plan { modifiers, main })
}

/// Returns VkKeyScan's result: low byte = virtual key, high byte = shift state.
fn char_vk(c: char) -> Option<u16> {
    if c.is_ascii_alphanumeric() {
        // Letters and digits have fixed virtual-key codes on every layout.
        return Some(c.to_ascii_uppercase() as u16);
    }
    let mut buf = [0u16; 2];
    let units = c.encode_utf16(&mut buf);
    if units.len() != 1 {
        return None;
    }
    let r = unsafe { VkKeyScanW(units[0]) };
    if r == -1 {
        None
    } else {
        Some(r as u16)
    }
}

fn is_extended(vk: u16) -> bool {
    matches!(vk, 0x21..=0x28 | 0x2D | 0x2E | 0x5B | 0x5C | 0x5D | 0x6F | 0x90 | 0xA3 | 0xA5)
}

fn key_input(vk: u16, up: bool) -> INPUT {
    unsafe {
        let mut input: INPUT = zeroed();
        input.r#type = INPUT_KEYBOARD;
        input.Anonymous.ki.wVk = vk;
        input.Anonymous.ki.wScan = MapVirtualKeyW(vk as u32, MAPVK_VK_TO_VSC) as u16;
        let mut flags = 0;
        if up {
            flags |= KEYEVENTF_KEYUP;
        }
        if is_extended(vk) {
            flags |= KEYEVENTF_EXTENDEDKEY;
        }
        input.Anonymous.ki.dwFlags = flags;
        input
    }
}

fn wheel_input(horizontal: bool, delta: i32) -> INPUT {
    unsafe {
        let mut input: INPUT = zeroed();
        input.r#type = INPUT_MOUSE;
        input.Anonymous.mi.mouseData = delta as u32;
        input.Anonymous.mi.dwFlags = if horizontal { MOUSEEVENTF_HWHEEL } else { MOUSEEVENTF_WHEEL };
        input
    }
}

fn send(inputs: &[INPUT]) -> Result<(), String> {
    if inputs.is_empty() {
        return Ok(());
    }
    let sent = unsafe { SendInput(inputs.len() as u32, inputs.as_ptr(), size_of::<INPUT>() as i32) };
    if sent as usize != inputs.len() {
        let code = unsafe { GetLastError() };
        return Err(format!(
            "Windows blocked the key press (error {code}). If your art app runs as administrator, \
             run PenBridge as administrator too."
        ));
    }
    Ok(())
}
