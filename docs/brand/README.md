# My Cockpit brand mark

The mark is an instrument panel: three small solid dials and a large artificial horizon banked at 30°.
It is a symbol only; there is no wordmark. Chosen 2026-10-21 (design canvas "My Cockpit logo concepts",
variation B30).

| File | Use |
|---|---|
| `mark.svg` | Master mark on a transparent background. Needles are cut out. Switches to light ink under `prefers-color-scheme: dark`, so it also serves as the web favicon. |
| `app-icon.svg` | App icon: mark at 70 % on a navy rounded square (primary launcher / touch icon). |
| `app-icon-light.svg` | Light variant of the app icon. |

## Colours

| Token | Hex | Where |
|---|---|---|
| Ink | `#14243A` | Dials, ground of the horizon, app-icon background |
| Ink on dark | `#EEF2F7` | Dials and ground on dark backgrounds |
| Sky (accent) | `#E8962E` | Sky of the horizon |
| Light ground | `#EEF1F5` | Background of the light app icon |

## Geometry (64 × 64 grid, before the `translate(0.5 0.5)` that centres the mark)

- Small dials: circles r 8 at (12, 12), (42, 10), (10, 42); needles 2.75 wide, round caps:
  (12, 12)→(8.8, 8.8), (42, 10)→(45.9, 7.75), (10, 42)→(10, 37.5).
- Horizon: circle r 19 at (42, 42); ground `M25.55 51.5 L58.45 32.5 A19 19 0 0 1 25.55 51.5 Z` (30° bank).
- Keep the needles as cut-outs (mask) or in the background colour; never in a third colour.
