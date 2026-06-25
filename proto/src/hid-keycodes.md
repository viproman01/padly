# HID Usage Page 0x07 keycodes

Padly transmits keyboard events using HID Usage Page 0x07 (Keyboard/Keypad page) codes. These are the same codes a physical USB keyboard would emit. macOS knows how to translate them via `CGEvent`. On Android we map Android keycodes to HID codes inside `transport.Encoder`.

This file is the canonical mapping table.

## Letters and digits

| HID code | Key |
|---|---|
| 0x04 | A |
| 0x05 | B |
| 0x06 | C |
| 0x07 | D |
| 0x08 | E |
| 0x09 | F |
| 0x0A | G |
| 0x0B | H |
| 0x0C | I |
| 0x0D | J |
| 0x0E | K |
| 0x0F | L |
| 0x10 | M |
| 0x11 | N |
| 0x12 | O |
| 0x13 | P |
| 0x14 | Q |
| 0x15 | R |
| 0x16 | S |
| 0x17 | T |
| 0x18 | U |
| 0x19 | V |
| 0x1A | W |
| 0x1B | X |
| 0x1C | Y |
| 0x1D | Z |
| 0x1E | 1 |
| 0x1F | 2 |
| 0x20 | 3 |
| 0x21 | 4 |
| 0x22 | 5 |
| 0x23 | 6 |
| 0x24 | 7 |
| 0x25 | 8 |
| 0x26 | 9 |
| 0x27 | 0 |

## Control keys

| HID code | Key |
|---|---|
| 0x28 | Return |
| 0x29 | Escape |
| 0x2A | Backspace |
| 0x2B | Tab |
| 0x2C | Space |
| 0x2D | - / _ |
| 0x2E | = / + |
| 0x2F | [ / { |
| 0x30 | ] / } |
| 0x31 | \ / \| |
| 0x33 | ; / : |
| 0x34 | ' / " |
| 0x35 | ` / ~ |
| 0x36 | , / < |
| 0x37 | . / > |
| 0x38 | / / ? |

## Function keys

| HID code | Key |
|---|---|
| 0x3A | F1 |
| 0x3B | F2 |
| 0x3C | F3 |
| 0x3D | F4 |
| 0x3E | F5 |
| 0x3F | F6 |
| 0x40 | F7 |
| 0x41 | F8 |
| 0x42 | F9 |
| 0x43 | F10 |
| 0x44 | F11 |
| 0x45 | F12 |

## Arrows and navigation

| HID code | Key |
|---|---|
| 0x4F | Right Arrow |
| 0x50 | Left Arrow |
| 0x51 | Down Arrow |
| 0x52 | Up Arrow |
| 0x4A | Home |
| 0x4D | End |
| 0x4B | Page Up |
| 0x4E | Page Down |
| 0x49 | Insert |
| 0x4C | Delete (forward) |

## Modifier keys (sent in `mods` bitmask, not as `code`)

| HID code | Padly bit | Modifier |
|---|---|---|
| 0xE0 | 4 | Left Ctrl |
| 0xE1 | 8 | Left Shift |
| 0xE2 | 2 | Left Alt / Option |
| 0xE3 | 1 | Left GUI / Command |
| 0xE4 | 4 | Right Ctrl |
| 0xE5 | 8 | Right Shift |
| 0xE6 | 2 | Right Alt / Option |
| 0xE7 | 1 | Right GUI / Command |

On macOS, GUI = Command. On Android we treat the on-screen `cmd` chip as the GUI modifier and the OS routes it correctly because we send pure HID codes.
