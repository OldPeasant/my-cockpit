# Code Review

Date: 2026-10-21

Task: app-icons - App icon and favicon from the brand mark

Scope: `git diff f4493067c68a58be29d5a19dd352a7ed0f957d7d..e5876e07200fc0fd53983bed1ddc4f26b38be729` (committed changes only; working tree clean)

Requirements:
- `tasks/app-icons.md` acceptance criteria and its four recorded assumptions (`mipmap-anydpi` instead of `-v26`, touch icon as a full square, monochrome sky at 50 % alpha, rendering with Playwright Chromium instead of ImageMagick)
- `docs/brand/README.md`, `docs/brand/mark.svg`, `docs/brand/app-icon.svg`
- `docs/QUALITY.md`, `AGENTS.md` (project notes)
- Parent instructions: check geometry and colours against docs/brand, the safe zone, favicon link order and browser selection, ICO validity, needle cut-out paths, generator maintainability and the tests

Git range:
- Branch: main
- HEAD: e5876e07200fc0fd53983bed1ddc4f26b38be729
- Base/start: f4493067c68a58be29d5a19dd352a7ed0f957d7d
- Commits reviewed: 2 (6ad3dbca implementation, e5876e07 tests and test report)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Verification performed

| Check | Command / method | Result |
|---|---|---|
| ICO structure | Python parse of `web/public/favicon.ico` (header, directory, embedded PNG IHDR) | reserved 0, type 1, 3 entries; 16/32/48 px, planes 1, 32 bpp, offsets 54/711/1848 contiguous, length 3717 bytes = end of last entry; each entry is a valid PNG (RGBA, colour type 6) whose IHDR matches its directory size |
| Touch icon | Python parse of the IHDR of `web/public/apple-touch-icon.png`; image viewed | 180 x 180, colour type 2 (RGB, opaque); navy full square, mark at 70 %, matches `app-icon.svg` without `rx` (recorded assumption) |
| ICO content | 48 px entry extracted to `.tmp/cr-icons/ico48.png` and viewed | ink mark of the light scheme, needles cut out, transparent background |
| `favicon.svg` | diff against `docs/brand/mark.svg` | byte-identical, keeps the `prefers-color-scheme: dark` rule |
| Foreground group transform | by hand: `app-icon.svg` maps p to 32 + 0.7 (p - 31.5) on 64; Android maps p to 54 + s (p - 31.5) with s = 0.7 x 72 / 64 = 0.7875 | translate 54 - 31.5 x 0.7875 = 29.19375 matches the XML; the mark has the same size relative to the visible 72 dp as in `app-icon.svg` relative to 64 |
| Safe zone | distance from (31.5, 31.5): dial (12, 12) 27.58 + 8 = 35.58; dials (42, 10)/(10, 42) 23.93 + 8 = 31.93; horizon 14.85 + 19 = 33.85 (grid units) | maximum 35.58 x 0.7875 = 28.0 dp, inside the 33 dp radius of the 66 dp safe zone |
| Needle cut-outs | recomputed each stadium (perpendicular offset 1.375 to the needle direction) and the direction of each `sweep=0` arc | all six corner points match to 3 decimals ((12.972, 11.028)/(9.772, 7.828)/(7.828, 9.772)/(11.028, 12.972); (42.687, 11.191)/(46.587, 8.941)/(45.213, 6.559)/(41.313, 8.809); (11.375, 42)/(11.375, 37.5)/(8.625, 37.5)/(8.625, 42)); the tip arcs bulge towards the needle tips and the hub arcs away from them, so the shapes equal the round-capped 2.75 strokes of `mark.svg`; each stadium lies fully inside its r 8 dial, so `evenOdd` cuts holes |
| Horizon paths | compared with README geometry | ground path is identical to the README; the chord (25.55, 51.5)-(58.45, 32.5) has midpoint (42, 42), so the monochrome sky path (`sweep 0`) is exactly the complementary half |
| Colours | XML vs `app-icon.svg` / README | background `#14243A`, dials and ground `#EEF2F7`, sky `#E8962E`; monochrome uses one colour, sky at `fillAlpha 0.5` (recorded assumption) |
| Visual comparison | viewed the parent's `.tmp/preview-light.png` and `.tmp/preview-dark.png` | Android foreground (circle and rounded-square masks, safe-zone circle drawn) matches `app-icon.svg`; themed icon readable; favicon light/dark switch works |
| Old placeholder | `git grep ic_launcher` | no remaining reference to `@drawable/ic_launcher`; manifest uses `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round` |
| Build output | `web/angular.json` assets | `public/**` is copied as-is, so the three files reach the `ng build` output (the parent and the test-writer listed them in `web/dist/web/browser`) |
| Static serving | `SecurityConfiguration.kt` | `GET /**` is `permitAll`, so the icons load before login |
| Favicon selection in a browser | `.tmp/cr-icons/fav.mjs`: Playwright Chromium (headless) with all requests routed to `web/src/index.html` and `web/public` | headless Chromium requests no favicon at all, and Playwright Firefox is not installed, so which link a real browser picks could not be observed here (see notes) |

The generator was not run again: it writes into tracked `web/public/` files, which this review must not change. A copy into `.tmp/` was blocked by the sandbox.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes (not findings)

- Link order: `index.html` lists the SVG (`sizes="any"`) first and the ICO (`sizes="16x16 32x32 48x48"`) second, as the criterion requires. Browsers that choose by `sizes` and type pick the SVG. Some guides put the ICO first because some browsers prefer the last suitable link. This review could not show that a supported browser picks the ICO here (see the verification table), so it is not reported as a defect. If the parent wants certainty, check the tab icon in a headed Firefox with the dark scheme.
- Generator: the `.replace(' rx="14"', '')` in `docs/brand/generate-icons.mjs` does nothing if `app-icon.svg` ever changes its radius. The test `apple-touch-icon.png is opaque` would then fail (the corners become transparent and give an RGBA PNG), so such a regression is caught.
- The `roundIcon` manifest attribute is checked only by reading the manifest. The test report states this. It is a one-line, low-risk attribute.

## Review conclusion

No material issues found.
