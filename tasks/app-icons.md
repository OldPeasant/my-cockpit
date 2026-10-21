---
title: App icon and favicon from the brand mark
status: in_progress
owner: dell26/agent-3
deps: [p0-web-shell,and-shell]
touches: [docs/brand,web/public/favicon.svg,web/public/favicon.ico,web/public/apple-touch-icon.png,web/src/index.html,android/app/src/main/res/drawable/ic_launcher.xml,android/app/src/main/res/mipmap-anydpi-v26,android/app/src/main/res/drawable/ic_launcher_foreground.xml,android/app/src/main/res/drawable/ic_launcher_background.xml,android/app/src/main/res/drawable/ic_launcher_monochrome.xml,android/app/src/main/AndroidManifest.xml]
prio: 2
lease_until: 2026-10-21T16:56:38Z
---
## Goal
Web and Android show the chosen My Cockpit mark (instrument panel with a 30° artificial horizon) instead of
the placeholder icons. Source of truth: `docs/brand/` (`README.md` has colours and exact geometry,
`mark.svg`, `app-icon.svg`, `app-icon-light.svg`). Symbol only, no wordmark.

## Acceptance criteria
- [x] Web: `web/public/favicon.svg` (from `docs/brand/mark.svg`, keeps its dark-mode switch), a regenerated `web/public/favicon.ico` (16, 32, 48 px) and `web/public/apple-touch-icon.png` (180 px, from `app-icon.svg`); `web/src/index.html` links the SVG favicon first, the ICO as fallback and the touch icon; `cd web && npm run check` passes and `ng build` output contains the three files
  - verified (2026-10-21 18:38+0200 by dell26/agent-3): node docs/brand/generate-icons.mjs -> favicon.svg (=mark.svg, dark-mode media query kept), favicon.ico (3 PNG entries 16/32/48), apple-touch-icon.png 180x180 from app-icon.svg; index.html links favicon.svg (sizes=any) first, favicon.ico, apple-touch-icon; cd web && npm run check -> pass (2364 tests incl. new app-icons.spec.ts, 6 passed); ng build dist/web/browser contains favicon.svg, favicon.ico, apple-touch-icon.png
- [ ] Android: adaptive launcher icon in `res/mipmap-anydpi-v26/ic_launcher.xml` (+ `ic_launcher_round.xml`) with vector `ic_launcher_background` (ink `#14243A`), `ic_launcher_foreground` (mark inside the 66 dp safe zone of the 108 dp canvas, colours as in `app-icon.svg`) and `ic_launcher_monochrome` (themed icons, Android 13+); the old placeholder `drawable/ic_launcher.xml` is replaced or removed and the manifest references `@mipmap/ic_launcher` / `@mipmap/ic_launcher_round`; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes with no new lint warnings
- [ ] The rendered icons match `docs/brand/` (render the web SVG/PNGs and an Android launcher preview or `assembleDebug` + inspection of the vector, and compare with `docs/brand/app-icon.svg`); record how it was checked
- [ ] How the icons are generated (tool and command, e.g. ImageMagick for ICO/PNG) is written into `docs/brand/README.md` so they can be regenerated when the mark changes

## Notes
- The owner chose the mark on 2026-10-21 in a design session; do not redesign it. Small optical fixes for tiny sizes are fine if recorded with `tasks.py assume`.
- The dark app icon (`app-icon.svg`) is the default launcher/touch icon; `app-icon-light.svg` is an alternative, not required here.
- Out of scope: a logo in the web top bar or the Android login screen (separate task if wanted).
- ASSUMPTION (2026-10-21 18:17+0200 by dell26/agent-3): Adaptive icon XMLs live in res/mipmap-anydpi/ instead of mipmap-anydpi-v26/: minSdk is 29, so lint reports the -v26 qualifier as an ObsoleteSdkInt error; same effect on every supported device.
- ASSUMPTION (2026-10-21 18:22+0200 by dell26/agent-3): Apple touch icon is rendered as a full square (app-icon.svg without rx): iOS applies its own corner mask and fills transparent pixels black.
- ASSUMPTION (2026-10-21 18:22+0200 by dell26/agent-3): Themed (monochrome) Android icon draws the sky half of the horizon at 50 % alpha, since a one-colour icon cannot show the accent; needles are cut out as in mark.svg.
- ASSUMPTION (2026-10-21 18:22+0200 by dell26/agent-3): Icons are rendered with Playwright Chromium (already a web dev dependency) via docs/brand/generate-icons.mjs, not ImageMagick; the ICO is assembled by the script (PNG entries 16/32/48).
