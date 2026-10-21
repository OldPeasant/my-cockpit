---
title: App icon and favicon from the brand mark
status: todo
owner:
deps: [p0-web-shell,and-shell]
touches: [docs/brand,web/public,web/src/index.html,android/app/src/main/res/drawable/ic_launcher.xml,android/app/src/main/res/mipmap-anydpi-v26,android/app/src/main/res/drawable/ic_launcher_foreground.xml,android/app/src/main/res/drawable/ic_launcher_background.xml,android/app/src/main/res/drawable/ic_launcher_monochrome.xml,android/app/src/main/AndroidManifest.xml]
prio: 2
---
## Goal
Web and Android show the chosen My Cockpit mark (instrument panel with a 30° artificial horizon) instead of
the placeholder icons. Source of truth: `docs/brand/` (`README.md` has colours and exact geometry,
`mark.svg`, `app-icon.svg`, `app-icon-light.svg`). Symbol only, no wordmark.

## Acceptance criteria
- [ ] Web: `web/public/favicon.svg` (from `docs/brand/mark.svg`, keeps its dark-mode switch), a regenerated `web/public/favicon.ico` (16, 32, 48 px) and `web/public/apple-touch-icon.png` (180 px, from `app-icon.svg`); `web/src/index.html` links the SVG favicon first, the ICO as fallback and the touch icon; `cd web && npm run check` passes and `ng build` output contains the three files
- [ ] Android: adaptive launcher icon in `res/mipmap-anydpi-v26/ic_launcher.xml` (+ `ic_launcher_round.xml`) with vector `ic_launcher_background` (ink `#14243A`), `ic_launcher_foreground` (mark inside the 66 dp safe zone of the 108 dp canvas, colours as in `app-icon.svg`) and `ic_launcher_monochrome` (themed icons, Android 13+); the old placeholder `drawable/ic_launcher.xml` is replaced or removed and the manifest references `@mipmap/ic_launcher` / `@mipmap/ic_launcher_round`; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes with no new lint warnings
- [ ] The rendered icons match `docs/brand/` (render the web SVG/PNGs and an Android launcher preview or `assembleDebug` + inspection of the vector, and compare with `docs/brand/app-icon.svg`); record how it was checked
- [ ] How the icons are generated (tool and command, e.g. ImageMagick for ICO/PNG) is written into `docs/brand/README.md` so they can be regenerated when the mark changes

## Notes
- The owner chose the mark on 2026-10-21 in a design session; do not redesign it. Small optical fixes for tiny sizes are fine if recorded with `tasks.py assume`.
- The dark app icon (`app-icon.svg`) is the default launcher/touch icon; `app-icon-light.svg` is an alternative, not required here.
- Out of scope: a logo in the web top bar or the Android login screen (separate task if wanted).
