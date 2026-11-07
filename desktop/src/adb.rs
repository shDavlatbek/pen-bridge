//! USB mode. When a phone with USB debugging is plugged in, we run
//! `adb reverse tcp:PORT tcp:PORT`, so the phone app can reach this PC through
//! the cable by connecting to its own 127.0.0.1:PORT.

use std::collections::HashSet;
use std::path::PathBuf;
use std::process::{Command, Output};
use std::thread;
use std::time::Duration;

use crate::log;

const ADB_EXE: &str = if cfg!(windows) { "adb.exe" } else { "adb" };

fn candidates(explicit: Option<PathBuf>) -> Vec<PathBuf> {
    let mut list = Vec::new();
    if let Some(p) = explicit {
        list.push(p);
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            list.push(dir.join(ADB_EXE));
            list.push(dir.join("platform-tools").join(ADB_EXE));
        }
    }
    // Plain "adb" lets the OS search PATH.
    list.push(PathBuf::from(ADB_EXE));
    if let Ok(local) = std::env::var("LOCALAPPDATA") {
        list.push(PathBuf::from(local).join("Android/Sdk/platform-tools").join(ADB_EXE));
    }
    if let Ok(home) = std::env::var("HOME") {
        list.push(PathBuf::from(&home).join("Android/Sdk/platform-tools").join(ADB_EXE));
        list.push(PathBuf::from(&home).join("Library/Android/sdk/platform-tools").join(ADB_EXE));
    }
    list
}

fn run(adb: &PathBuf, args: &[&str]) -> std::io::Result<Output> {
    let mut cmd = Command::new(adb);
    cmd.args(args);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd.output()
}

fn find_adb(explicit: Option<PathBuf>) -> Option<PathBuf> {
    candidates(explicit)
        .into_iter()
        .find(|p| run(p, &["version"]).map(|o| o.status.success()).unwrap_or(false))
}

pub fn spawn(port: u16, explicit: Option<PathBuf>) {
    thread::spawn(move || {
        let mut adb = find_adb(explicit.clone());
        match &adb {
            Some(p) => log::info(&format!("USB: using {}", p.display())),
            None => log::info(
                "USB: adb not found, so USB mode is off (Wi-Fi still works). To enable USB, put the \
                 'platform-tools' folder from Google's Android SDK Platform-Tools next to PenBridge.",
            ),
        }

        let mut ready: HashSet<String> = HashSet::new();
        let mut warned_unauthorized: HashSet<String> = HashSet::new();
        let mut ticks: u32 = 0;
        loop {
            thread::sleep(Duration::from_secs(2));
            ticks += 1;
            let Some(path) = adb.clone() else {
                // Look again every ~20 s in case the user adds adb while we run.
                if ticks.is_multiple_of(10) {
                    adb = find_adb(explicit.clone());
                    if let Some(p) = &adb {
                        log::info(&format!("USB: found {}", p.display()));
                    }
                }
                continue;
            };

            let Ok(out) = run(&path, &["devices"]) else { continue };
            let text = String::from_utf8_lossy(&out.stdout);
            let mut seen = HashSet::new();
            for line in text.lines().skip(1) {
                let mut parts = line.split_whitespace();
                let (Some(serial), Some(state)) = (parts.next(), parts.next()) else { continue };
                seen.insert(serial.to_string());
                match state {
                    "device" if !ready.contains(serial) => {
                        let spec = format!("tcp:{port}");
                        match run(&path, &["-s", serial, "reverse", &spec, &spec]) {
                            Ok(o) if o.status.success() => {
                                log::info(&format!("USB: phone {serial} connected by cable. Open PenBridge on the phone."));
                                ready.insert(serial.to_string());
                            }
                            Ok(o) => log::warn(&format!(
                                "USB: could not set up phone {serial}: {}",
                                String::from_utf8_lossy(&o.stderr).trim()
                            )),
                            Err(e) => log::warn(&format!("USB: adb failed: {e}")),
                        }
                    }
                    "unauthorized" if warned_unauthorized.insert(serial.to_string()) => {
                        log::info("USB: phone found. Tap \"Allow\" on the USB debugging prompt on your phone.");
                    }
                    _ => {}
                }
            }
            ready.retain(|s| seen.contains(s));
            warned_unauthorized.retain(|s| seen.contains(s));

            // Every ~10 s, check the rule still exists (another tool may have restarted adb).
            if ticks.is_multiple_of(5) {
                let spec = format!("tcp:{port}");
                ready.retain(|serial| match run(&path, &["-s", serial, "reverse", "--list"]) {
                    Ok(o) => String::from_utf8_lossy(&o.stdout).contains(&spec),
                    Err(_) => false,
                });
            }
        }
    });
}
