//! Monitors and mapping the phone's pad area onto one of them.

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Display {
    pub name: String,
    /// Top-left corner in desktop (virtual screen) pixels.
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
    pub primary: bool,
}

impl Display {
    /// Maps normalized pad coordinates (0..=65535) to desktop pixels.
    pub fn map(&self, nx: u16, ny: u16) -> (i32, i32) {
        let fx = nx as f64 / 65535.0;
        let fy = ny as f64 / 65535.0;
        let px = self.x + (fx * (self.width - 1).max(0) as f64).round() as i32;
        let py = self.y + (fy * (self.height - 1).max(0) as f64).round() as i32;
        (px, py)
    }
}

/// One rectangle covering every display, used for "all displays" mode.
pub fn bounding(displays: &[Display]) -> Option<Display> {
    let first = displays.first()?;
    let (mut l, mut t) = (first.x, first.y);
    let (mut r, mut b) = (first.x + first.width, first.y + first.height);
    for d in displays {
        l = l.min(d.x);
        t = t.min(d.y);
        r = r.max(d.x + d.width);
        b = b.max(d.y + d.height);
    }
    Some(Display { name: "All displays".into(), x: l, y: t, width: r - l, height: b - t, primary: false })
}

/// Picks the display for an index sent by the phone, falling back to the primary one.
pub fn pick(displays: &[Display], index: u8) -> Option<Display> {
    if index == crate::protocol::ALL_DISPLAYS {
        return bounding(displays);
    }
    displays
        .get(index as usize)
        .or_else(|| displays.iter().find(|d| d.primary))
        .or_else(|| displays.first())
        .cloned()
}

pub fn primary_index(displays: &[Display]) -> u8 {
    displays.iter().position(|d| d.primary).unwrap_or(0) as u8
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(x: i32, y: i32, w: i32, h: i32, primary: bool) -> Display {
        Display { name: String::new(), x, y, width: w, height: h, primary }
    }

    #[test]
    fn maps_corners() {
        let m = d(1920, 0, 2560, 1440, false);
        assert_eq!(m.map(0, 0), (1920, 0));
        assert_eq!(m.map(65535, 65535), (1920 + 2559, 1439));
        assert_eq!(m.map(32768, 32768), (1920 + 1280, 720));
    }

    #[test]
    fn bounding_box_and_pick() {
        let ds = vec![d(-1920, 200, 1920, 1080, false), d(0, 0, 2560, 1440, true)];
        let b = bounding(&ds).unwrap();
        assert_eq!((b.x, b.y, b.width, b.height), (-1920, 0, 4480, 1440));
        assert_eq!(pick(&ds, 9).unwrap().x, 0); // falls back to primary
        assert_eq!(pick(&ds, 0).unwrap().x, -1920);
        assert_eq!(primary_index(&ds), 1);
    }
}
