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

## Generating the icons

The icons in the apps are generated from the files above; regenerate them whenever the mark changes.

**Web** (`web/public/`): from the repository root, after `cd web && npm ci` (needs the Playwright Chromium the
web part already uses for E2E, `npx playwright install chromium`):

```sh
node docs/brand/generate-icons.mjs
```

It copies `mark.svg` to `favicon.svg` (keeps the dark-mode switch), renders `mark.svg` in the light colour scheme
at 16, 32 and 48 px into `favicon.ico` (an ICO with PNG entries, written by the script itself) and `app-icon.svg`
at 180 px into `apple-touch-icon.png`. The touch icon is rendered without the rounded corners (`rx`), because iOS
rounds them itself and would fill transparent corners black. ImageMagick is not needed.

**Android** (`android/app/src/main/res/`): hand-written vector drawables, no tool. `mipmap-anydpi/ic_launcher.xml`
and `ic_launcher_round.xml` are adaptive icons (minSdk 29, so no `-v26` qualifier) made of:

- `drawable/ic_launcher_background.xml`: a 108 dp square in ink `#14243A`.
- `drawable/ic_launcher_foreground.xml`: the mark in the colours of `app-icon.svg`. The path data uses the
  64 × 64 grid above unchanged; the `<group>` places it: `scale = 0.7 × 72 / 64 = 0.7875` (same size relative to
  the visible 72 dp as in `app-icon.svg`), `translate = 54 − 31.5 × 0.7875 = 29.19375` (mark centre (31.5, 31.5)
  incl. the 0.5 offset → canvas centre). Its farthest point is 28 dp from the centre, inside the 66 dp safe zone.
  VectorDrawable has no masks, so the needles are stadium shapes (half width 1.375, round ends) cut out of the
  dial path with `fillType="evenOdd"`.
- `drawable/ic_launcher_monochrome.xml` (themed icons, Android 13+): the same shapes in one colour; the sky half
  of the horizon at 50 % alpha so the bank stays visible.

To check them, build (`cd android && ./gradlew assembleDebug`) and look at the launcher, or render the vectors
next to `app-icon.svg` in a browser (path data and group transform map 1:1 to SVG `d` and `transform`).
