# PenBridge protocol (version 1)

The phone is the client and the PC is the server.

- **TCP 47800:** the session. Over USB, `adb reverse tcp:47800 tcp:47800` lets the phone reach
  the PC at `127.0.0.1:47800`.
- **UDP 47801:** Wi‑Fi discovery.

## Discovery (UDP 47801)

1. The phone broadcasts the ASCII text `PENBRIDGE?1`.
2. Each PC replies to the sender with `PENBRIDGE!1|<tcp port>|<host name>|<os>`,
   for example `PENBRIDGE!1|47800|STUDIO-PC|windows`.

## Framing (TCP)

Every message:

```
u16 length   big-endian, number of bytes that follow (1..8192)
u8  type
... payload
```

All integers are big-endian. A `str` is a `u16` byte length followed by that many bytes of UTF-8.

## Phone → PC

| Type | Name | Payload |
|---|---|---|
| `0x01` | HELLO | `u16 protocol version (1)`, `str device name`, `str app version`. This must be the first message. |
| `0x02` | SELECT_DISPLAY | `u8 index` into the WELCOME list, or `0xFF` for all displays |
| `0x10` | PEN | `u8 flags`, `u16 x`, `u16 y`, `u16 pressure`, `i8 tiltX°`, `i8 tiltY°` |
| `0x20` | KEY | `u8 action` (1 = down, 2 = up, 3 = tap), `str combo` such as `"ctrl+shift+z"` |
| `0x30` | PING | `u32 id`. The PC answers with PONG carrying the same id. Sent every second. |
| `0x7F` | BYE | none |

PEN fields:
- `x`, `y`: 0–65535 across the pad. The pad has the same aspect ratio as the selected display.
- `pressure`: 0–65535. The phone has already applied the pressure curve.
- `flags`: `0x01` in range (hovering or touching), `0x02` tip touching, `0x04` barrel button,
  `0x08` eraser.
- A PEN with flags `0` means the pen has left the hover range.

## PC → phone

| Type | Name | Payload |
|---|---|---|
| `0x81` | WELCOME | `u16 version`, `str host name`, `str os`, `u8 count`, then for each display: `i32 x`, `i32 y`, `i32 width`, `i32 height`, `u8 primary`, `str name`. Displays are sorted left to right. |
| `0x83` | NOTICE | `str message` to show the user, for example "unknown key 'banana'" |
| `0xB0` | PONG | `u32 id` copied from the PING |

## Rules

- **One phone at a time.** A new connection replaces the old one.
- **Time-out.** If the PC hears nothing for 6 seconds, it drops the connection.
- **Cleanup.** When a connection ends, the PC lifts the pen and releases every key held with KEY down.
- **Key combos.** Combos are plain text, so each platform backend maps key names its own way.
  The names are listed in the README.
