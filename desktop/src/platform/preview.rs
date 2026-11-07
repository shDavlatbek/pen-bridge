//! Test mode for systems without a real backend yet (Linux, macOS).
//! It accepts everything and logs it, which is handy for testing the phone app.

use super::Injector;
use crate::display::Display;
use crate::keys::KeyCombo;
use crate::log;
use crate::pen::{PenEvent, PenPhase};

pub struct PreviewInjector {
    verbose: bool,
}

impl PreviewInjector {
    pub fn new(verbose: bool) -> Self {
        Self { verbose }
    }
}

impl Injector for PreviewInjector {
    fn os_name(&self) -> &'static str {
        if cfg!(target_os = "macos") {
            "macos-test"
        } else {
            "linux-test"
        }
    }

    fn startup_problem(&self) -> Option<String> {
        Some(
            "Pen output is not supported on this OS yet. Running in TEST MODE: \
             pen and key events are only printed."
                .into(),
        )
    }

    fn displays(&self) -> Vec<Display> {
        vec![Display { name: "Test display".into(), x: 0, y: 0, width: 1920, height: 1080, primary: true }]
    }

    fn pen(&mut self, ev: &PenEvent) -> Result<(), String> {
        let interesting = !matches!(ev.phase, PenPhase::Hover | PenPhase::Move);
        if self.verbose || interesting {
            log::info(&format!(
                "pen {:?} at ({}, {}) pressure {:.2} tilt ({}, {}){}{}",
                ev.phase,
                ev.x,
                ev.y,
                ev.pressure,
                ev.tilt_x,
                ev.tilt_y,
                if ev.barrel { " [side button]" } else { "" },
                if ev.eraser { " [eraser]" } else { "" },
            ));
        }
        Ok(())
    }

    fn key_down(&mut self, combo: &KeyCombo) -> Result<(), String> {
        log::info(&format!("key down {:?}", combo));
        Ok(())
    }

    fn key_up(&mut self, combo: &KeyCombo) -> Result<(), String> {
        log::info(&format!("key up   {:?}", combo));
        Ok(())
    }
}
