//! Parses shortcut strings like "ctrl+shift+z", "space", "alt", "]" or
//! "ctrl+wheelup" into a platform-neutral key combo.

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Modifier {
    Ctrl,
    Shift,
    Alt,
    /// Windows key / Cmd on Mac.
    Meta,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum NamedKey {
    Space,
    Tab,
    Enter,
    Escape,
    Backspace,
    Delete,
    Insert,
    Home,
    End,
    PageUp,
    PageDown,
    Up,
    Down,
    Left,
    Right,
    CapsLock,
    /// F1..F24
    F(u8),
    /// The key that has "+" on it (the "=" key on US layouts, ";" on Japanese layouts).
    Plus,
    /// The key that has "-" on it.
    Minus,
    NumPlus,
    NumMinus,
    NumMultiply,
    NumDivide,
    /// Numpad 0..9
    Num(u8),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Key {
    /// A printable character, resolved through the active keyboard layout.
    Char(char),
    Named(NamedKey),
    WheelUp,
    WheelDown,
    WheelLeft,
    WheelRight,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct KeyCombo {
    pub modifiers: Vec<Modifier>,
    /// `None` for modifier-only combos such as "alt" or "ctrl+shift".
    pub key: Option<Key>,
}

fn modifier(name: &str) -> Option<Modifier> {
    Some(match name {
        "ctrl" | "control" | "ctl" => Modifier::Ctrl,
        "shift" => Modifier::Shift,
        "alt" | "option" | "opt" => Modifier::Alt,
        "win" | "meta" | "cmd" | "command" | "super" => Modifier::Meta,
        _ => return None,
    })
}

fn named(name: &str) -> Option<Key> {
    use NamedKey::*;
    let k = match name {
        "space" | "spacebar" => Space,
        "tab" => Tab,
        "enter" | "return" => Enter,
        "esc" | "escape" => Escape,
        "backspace" | "bksp" | "bs" => Backspace,
        "delete" | "del" => Delete,
        "insert" | "ins" => Insert,
        "home" => Home,
        "end" => End,
        "pageup" | "pgup" => PageUp,
        "pagedown" | "pgdn" => PageDown,
        "up" => Up,
        "down" => Down,
        "left" => Left,
        "right" => Right,
        "capslock" => CapsLock,
        "plus" => Plus,
        "minus" => Minus,
        "numplus" | "numadd" => NumPlus,
        "numminus" | "numsub" => NumMinus,
        "nummul" | "nummultiply" => NumMultiply,
        "numdiv" | "numdivide" => NumDivide,
        "wheelup" => return Some(Key::WheelUp),
        "wheeldown" => return Some(Key::WheelDown),
        "wheelleft" => return Some(Key::WheelLeft),
        "wheelright" => return Some(Key::WheelRight),
        _ => {
            if let Some(n) = name.strip_prefix("num").and_then(|r| r.parse::<u8>().ok()) {
                if n <= 9 {
                    return Some(Key::Named(Num(n)));
                }
            }
            if let Some(n) = name.strip_prefix('f').and_then(|r| r.parse::<u8>().ok()) {
                if (1..=24).contains(&n) {
                    return Some(Key::Named(F(n)));
                }
            }
            return None;
        }
    };
    Some(Key::Named(k))
}

pub fn parse_combo(input: &str) -> Result<KeyCombo, String> {
    let input = input.trim();
    if input.is_empty() {
        return Err("empty shortcut".into());
    }
    // Split on '+', treating an empty piece as a literal '+' key ("ctrl++").
    let mut tokens: Vec<String> = Vec::new();
    let mut last_was_plus_key = false;
    for piece in input.split('+') {
        let piece = piece.trim();
        if piece.is_empty() {
            if !last_was_plus_key {
                tokens.push("+".into());
                last_was_plus_key = true;
            }
        } else {
            tokens.push(piece.to_string());
            last_was_plus_key = false;
        }
    }

    let mut combo = KeyCombo { modifiers: Vec::new(), key: None };
    for token in tokens {
        let lower = token.to_lowercase();
        if let Some(m) = modifier(&lower) {
            if !combo.modifiers.contains(&m) {
                combo.modifiers.push(m);
            }
            continue;
        }
        let key = if let Some(k) = named(&lower) {
            k
        } else {
            let mut chars = token.chars();
            match (chars.next(), chars.next()) {
                (Some(c), None) => Key::Char(c.to_lowercase().next().unwrap_or(c)),
                _ => return Err(format!("unknown key \"{token}\"")),
            }
        };
        if combo.key.is_some() {
            return Err(format!("\"{input}\" has more than one main key"));
        }
        combo.key = Some(key);
    }
    Ok(combo)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn basic() {
        let c = parse_combo("Ctrl+Shift+Z").unwrap();
        assert_eq!(c.modifiers, vec![Modifier::Ctrl, Modifier::Shift]);
        assert_eq!(c.key, Some(Key::Char('z')));
    }

    #[test]
    fn modifier_only_and_named() {
        assert_eq!(parse_combo("alt").unwrap(), KeyCombo { modifiers: vec![Modifier::Alt], key: None });
        assert_eq!(parse_combo("space").unwrap().key, Some(Key::Named(NamedKey::Space)));
        assert_eq!(parse_combo("shift + space").unwrap().modifiers, vec![Modifier::Shift]);
        assert_eq!(parse_combo("f12").unwrap().key, Some(Key::Named(NamedKey::F(12))));
        assert_eq!(parse_combo("num7").unwrap().key, Some(Key::Named(NamedKey::Num(7))));
        assert_eq!(parse_combo("ctrl+wheelup").unwrap().key, Some(Key::WheelUp));
        assert_eq!(parse_combo("ctrl+plus").unwrap().key, Some(Key::Named(NamedKey::Plus)));
    }

    #[test]
    fn punctuation() {
        assert_eq!(parse_combo("]").unwrap().key, Some(Key::Char(']')));
        assert_eq!(parse_combo("alt+[").unwrap().key, Some(Key::Char('[')));
        assert_eq!(parse_combo("ctrl++").unwrap().key, Some(Key::Char('+')));
        assert_eq!(parse_combo("+").unwrap().key, Some(Key::Char('+')));
        assert_eq!(parse_combo("ctrl+-").unwrap().key, Some(Key::Char('-')));
    }

    #[test]
    fn errors() {
        assert!(parse_combo("").is_err());
        assert!(parse_combo("ctrl+banana").is_err());
        assert!(parse_combo("a+b").is_err());
        assert!(parse_combo("f25").is_err());
    }
}
