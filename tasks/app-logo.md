---
title: Show the brand mark in the web top bar, web login/setup and Android login
status: in_progress
owner: dell26/agent-2
deps: [app-icons]
touches: [web/src/app/shell/logo.ts,web/src/app/shell/logo.spec.ts,web/src/app/shell/shell.html,web/src/app/shell/shell.scss,web/src/app/features/auth/auth-layout.ts,web/src/app/features/auth/auth.scss,android/app/src/main/res/drawable/ic_logo.xml,android/app/src/main/java/ch/sonensei/cockpit/auth/LoginScreen.kt]
prio: 3
lease_until: 2026-10-21T23:15:09Z
---
## Goal
The My Cockpit mark appears where the app introduces itself: next to "My Cockpit" in the web top bar,
on the web login and first-run setup pages, and on the Android login screen. Source of truth:
`docs/brand/` (`mark.svg`, colours and geometry in `README.md`). Symbol only: the existing
"My Cockpit" text stays as it is.

## Acceptance criteria
- [x] Web: a standalone `LogoComponent` (`web/src/app/shell/logo.ts`) renders the mark as inline SVG (no extra request, needles as cut-outs), with a size input; its ink follows the light/dark theme (`--app-*`/`--mat-sys-*` tokens or the brand ink colours, both `prefers-color-scheme` and `data-theme`), the sky stays `#E8962E`; it is decorative (`aria-hidden="true"`), the brand link keeps its accessible name
  - verified (2026-10-22 01:00+0200 by dell26/agent-2): web/src/app/shell/logo.ts reviewed + logo.spec.ts (renders inline svg, no <mask>/<img>, aria-hidden, size input -> --logo-size) and shell.spec.ts (brand link name stays 'My Cockpit') run in npm --prefix web run check -> 133 files / 2676 tests passed; theme ink light-dark() seen switching in Playwright screenshots (prefers-color-scheme and data-theme=dark), sky #e8962e
- [x] Web: the top bar shows the logo (about 28 px) before the "My Cockpit" link text, also in the collapsed drawer layout < 900 px; `auth-layout.ts` shows it (about 64 px) above the login and setup forms
  - verified (2026-10-22 01:00+0200 by dell26/agent-2): shell.spec.ts (.brand app-logo 28px before text, .topbar-logo only when compact <900px) + auth-layout.spec.ts (logo 64px above title/form) in npm --prefix web run check -> passed; Playwright screenshots .tmp/topbar-*.png, topbar-compact-light.png (600px), login-*.png, setup-light.png show it
- [x] Android: `res/drawable/ic_logo.xml` vector of the mark (transparent background, light/dark via theme colours or a `drawable-night` variant) shown above the title on `LoginScreen` (about 72 dp), `contentDescription = null`
  - verified (2026-10-22 01:00+0200 by dell26/agent-2): drawable/ic_logo.xml + drawable-night/ic_logo.xml; LogoDrawableTest (light/night inks, transparent, sky constant) + LoginScreenTest (logo has no contentDescription) via android/gradlew -p android testDebugUnitTest --rerun -> 1353 tests, 0 failures; Robolectric screenshot shows 72dp logo above title
- [x] Tests: `logo.spec.ts` (renders, aria-hidden, size input), shell and auth-layout specs still pass; `cd web && npm run check` and `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` pass
  - verified (2026-10-22 01:00+0200 by dell26/agent-2): npm --prefix web run check -> exit 0, 2676 tests passed (incl. logo/shell/auth-layout specs); android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug -> BUILD SUCCESSFUL, plus testDebugUnitTest --rerun -> 1353/1353 passed
- [x] Seen working: screenshots of the web top bar (light and dark), web login page and the Android login screen (Compose preview or emulator), compared with `docs/brand/mark.svg`; record how they were made
  - verified (2026-10-22 00:48+0200 by dell26/agent-2): node .tmp/shots.mjs (Playwright chromium on ng build output web/dist, auth API mocked) -> topbar light/dark (prefers-color-scheme) + data-theme=dark override + compact 600px + login light/dark + setup screenshots, logo visible, ink switches navy/white, sky orange; node .tmp/compare.mjs renders docs/brand/mark.svg vs logo.ts SVG at 256px -> 20/65536 px differ (antialiasing); Android: Robolectric NATIVE graphics captureToImage of LoginScreen in CockpitTheme (w411dp xxhdpi, day and night qualifiers) -> logo 72dp above title, navy ink light, white ink night, matches mark.svg

## Notes
- The owner chose the mark on 2026-10-21; do not redesign it.
- Ordered before `web-totp-reenroll` and `and-passkeys`, which change the same auth screens.
- ASSUMPTION (2026-10-22 00:34+0200 by dell26/agent-2): Web: the 'My Cockpit' brand link lives in the side navigation, not the top bar; the logo sits inside that link (both layouts, visible in the drawer < 900 px) and, in the compact layout only, an extra decorative 28 px logo after the menu button so the visible top bar also shows the mark
- ASSUMPTION (2026-10-22 00:34+0200 by dell26/agent-2): Web logo: needles drawn as evenodd stadium cut-outs (same path data as the Android launcher drawables) instead of mark.svg's <mask>, so no SVG id can collide when two logos are on one page; ink via CSS light-dark(#14243A, #EEF2F7), which follows color-scheme set by both prefers-color-scheme and data-theme; render diff vs mark.svg: 20 of 65536 px (antialiasing)
- ASSUMPTION (2026-10-22 00:34+0200 by dell26/agent-2): Android: light/dark via drawable/ic_logo.xml + drawable-night/ic_logo.xml (fixed brand inks), matching CockpitTheme, which follows the system night mode; the logo is left-aligned above the left-aligned title like the web auth layout
