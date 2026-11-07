//! Platform backends. Each one creates the virtual pen and presses keys.
//!
//! Windows is implemented. macOS and Linux can be added later as new files
//! here that implement `Injector`; until then they run in test mode, which
//! only logs what it would do.

use crate::display::Display;
use crate::keys::KeyCombo;
use crate::pen::PenEvent;

pub trait Injector: Send {
    /// Short OS name sent to the phone, e.g. "windows".
    fn os_name(&self) -> &'static str;
    /// Problem to show the user at startup, if pen output can't work.
    fn startup_problem(&self) -> Option<String> {
        None
    }
    fn displays(&self) -> Vec<Display>;
    fn pen(&mut self, ev: &PenEvent) -> Result<(), String>;
    fn key_down(&mut self, combo: &KeyCombo) -> Result<(), String>;
    fn key_up(&mut self, combo: &KeyCombo) -> Result<(), String>;
    fn tap(&mut self, combo: &KeyCombo) -> Result<(), String> {
        self.key_down(combo)?;
        self.key_up(combo)
    }
}

#[cfg(windows)]
mod windows;

#[cfg(not(windows))]
mod preview;

/// Process-wide setup that must run before anything else (e.g. DPI awareness).
pub fn init_process() {
    #[cfg(windows)]
    windows::init_process();
}

pub fn create(verbose: bool) -> Box<dyn Injector> {
    #[cfg(windows)]
    {
        let _ = verbose;
        Box::new(windows::WinInjector::new())
    }
    #[cfg(not(windows))]
    {
        Box::new(preview::PreviewInjector::new(verbose))
    }
}
