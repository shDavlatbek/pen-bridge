//! Turns the phone's raw pen samples into clean hover / down / move / up / leave
//! events that each platform backend can inject directly.

use crate::protocol::PenSample;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PenPhase {
    /// Pen is in range above the surface (or just entered range).
    Hover,
    /// Tip touched down.
    Down,
    /// Tip is moving while touching.
    Move,
    /// Tip lifted (pen still in range).
    Up,
    /// Pen left the hover range.
    Leave,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PenEvent {
    pub phase: PenPhase,
    pub x: i32,
    pub y: i32,
    /// 0.0..=1.0 (always 0 unless touching).
    pub pressure: f32,
    pub tilt_x: i32,
    pub tilt_y: i32,
    pub barrel: bool,
    pub eraser: bool,
}

#[derive(Default)]
pub struct PenTracker {
    in_range: bool,
    tip: bool,
    eraser: bool,
    barrel: bool,
    last: (i32, i32),
}

impl PenTracker {
    pub fn new() -> Self {
        Self::default()
    }

    fn event(&self, phase: PenPhase, x: i32, y: i32, s: Option<&PenSample>) -> PenEvent {
        let touching = matches!(phase, PenPhase::Down | PenPhase::Move);
        let pressure = match s {
            Some(s) if touching => s.pressure as f32 / 65535.0,
            _ => 0.0,
        };
        PenEvent {
            phase,
            x,
            y,
            pressure,
            tilt_x: s.map_or(0, |s| s.tilt_x as i32),
            tilt_y: s.map_or(0, |s| s.tilt_y as i32),
            barrel: self.barrel,
            eraser: self.eraser,
        }
    }

    /// Feeds one sample (already mapped to desktop pixels) and appends the events to send.
    pub fn update(&mut self, s: &PenSample, x: i32, y: i32, out: &mut Vec<PenEvent>) {
        let tip = s.tip();
        let in_range = s.in_range() || tip;
        self.barrel = s.barrel();

        if tip {
            if !self.tip {
                if !self.in_range {
                    // Enter range first so apps see a normal pen approach.
                    self.eraser = s.eraser();
                    out.push(self.event(PenPhase::Hover, x, y, Some(s)));
                }
                // The eraser end is fixed for the whole stroke.
                self.eraser = s.eraser();
                out.push(self.event(PenPhase::Down, x, y, Some(s)));
            } else {
                out.push(self.event(PenPhase::Move, x, y, Some(s)));
            }
        } else {
            if self.tip {
                out.push(self.event(PenPhase::Up, x, y, Some(s)));
            }
            if in_range {
                self.eraser = s.eraser();
                if !self.tip || (x, y) != self.last {
                    out.push(self.event(PenPhase::Hover, x, y, Some(s)));
                }
            } else if self.in_range || self.tip {
                out.push(self.event(PenPhase::Leave, x, y, None));
            }
        }

        self.tip = tip;
        self.in_range = in_range;
        self.last = (x, y);
    }

    /// Lifts and removes the pen, e.g. when the phone disconnects mid-stroke.
    pub fn reset(&mut self, out: &mut Vec<PenEvent>) {
        let (x, y) = self.last;
        if self.tip {
            out.push(self.event(PenPhase::Up, x, y, None));
        }
        if self.in_range || self.tip {
            out.push(self.event(PenPhase::Leave, x, y, None));
        }
        self.tip = false;
        self.in_range = false;
        self.barrel = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::pen_flags::*;

    fn s(flags: u8, pressure: u16) -> PenSample {
        PenSample { flags, x: 0, y: 0, pressure, tilt_x: 0, tilt_y: 0 }
    }

    fn phases(t: &mut PenTracker, samples: &[(u8, i32)]) -> Vec<PenPhase> {
        let mut out = Vec::new();
        for (flags, x) in samples {
            t.update(&s(*flags, 30000), *x, 0, &mut out);
        }
        out.iter().map(|e| e.phase).collect()
    }

    #[test]
    fn full_stroke() {
        use PenPhase::*;
        let mut t = PenTracker::new();
        let got = phases(
            &mut t,
            &[
                (IN_RANGE, 1),
                (IN_RANGE, 2),
                (IN_RANGE | TIP, 2),
                (IN_RANGE | TIP, 3),
                (IN_RANGE, 3),
                (IN_RANGE, 4),
                (0, 4),
                (0, 4),
            ],
        );
        assert_eq!(got, vec![Hover, Hover, Down, Move, Up, Hover, Leave]);
    }

    #[test]
    fn touch_without_hover_enters_range_first() {
        use PenPhase::*;
        let mut t = PenTracker::new();
        assert_eq!(phases(&mut t, &[(TIP, 5), (0, 5)]), vec![Hover, Down, Up, Leave]);
    }

    #[test]
    fn pressure_only_while_touching() {
        let mut t = PenTracker::new();
        let mut out = Vec::new();
        t.update(&s(IN_RANGE, 60000), 0, 0, &mut out);
        t.update(&s(IN_RANGE | TIP, 65535), 0, 0, &mut out);
        assert_eq!(out[0].pressure, 0.0);
        assert!((out[1].pressure - 1.0).abs() < 1e-6);
    }

    #[test]
    fn eraser_latched_for_stroke() {
        let mut t = PenTracker::new();
        let mut out = Vec::new();
        t.update(&s(IN_RANGE | TIP | ERASER, 100), 0, 0, &mut out);
        t.update(&s(IN_RANGE | TIP, 100), 1, 0, &mut out);
        assert!(out.iter().all(|e| e.eraser));
    }

    #[test]
    fn reset_mid_stroke() {
        use PenPhase::*;
        let mut t = PenTracker::new();
        let mut out = Vec::new();
        t.update(&s(IN_RANGE | TIP, 100), 7, 8, &mut out);
        out.clear();
        t.reset(&mut out);
        assert_eq!(out.iter().map(|e| e.phase).collect::<Vec<_>>(), vec![Up, Leave]);
        assert_eq!((out[1].x, out[1].y), (7, 8));
        out.clear();
        t.reset(&mut out);
        assert!(out.is_empty());
    }
}
