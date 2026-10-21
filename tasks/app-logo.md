---
title: Show the brand mark in the web top bar, web login/setup and Android login
status: in_progress
owner: dell26/agent-2
deps: [app-icons]
touches: [web/src/app/shell/logo.ts,web/src/app/shell/logo.spec.ts,web/src/app/shell/shell.html,web/src/app/shell/shell.scss,web/src/app/features/auth/auth-layout.ts,web/src/app/features/auth/auth.scss,android/app/src/main/res/drawable/ic_logo.xml,android/app/src/main/java/ch/sonensei/cockpit/auth/LoginScreen.kt]
prio: 3
lease_until: 2026-10-21T22:48:03Z
---
## Goal
The My Cockpit mark appears where the app introduces itself: next to "My Cockpit" in the web top bar,
on the web login and first-run setup pages, and on the Android login screen. Source of truth:
`docs/brand/` (`mark.svg`, colours and geometry in `README.md`). Symbol only: the existing
"My Cockpit" text stays as it is.

## Acceptance criteria
- [ ] Web: a standalone `LogoComponent` (`web/src/app/shell/logo.ts`) renders the mark as inline SVG (no extra request, needles as cut-outs), with a size input; its ink follows the light/dark theme (`--app-*`/`--mat-sys-*` tokens or the brand ink colours, both `prefers-color-scheme` and `data-theme`), the sky stays `#E8962E`; it is decorative (`aria-hidden="true"`), the brand link keeps its accessible name
- [ ] Web: the top bar shows the logo (about 28 px) before the "My Cockpit" link text, also in the collapsed drawer layout < 900 px; `auth-layout.ts` shows it (about 64 px) above the login and setup forms
- [ ] Android: `res/drawable/ic_logo.xml` vector of the mark (transparent background, light/dark via theme colours or a `drawable-night` variant) shown above the title on `LoginScreen` (about 72 dp), `contentDescription = null`
- [ ] Tests: `logo.spec.ts` (renders, aria-hidden, size input), shell and auth-layout specs still pass; `cd web && npm run check` and `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` pass
- [ ] Seen working: screenshots of the web top bar (light and dark), web login page and the Android login screen (Compose preview or emulator), compared with `docs/brand/mark.svg`; record how they were made

## Notes
- The owner chose the mark on 2026-10-21; do not redesign it.
- Ordered before `web-totp-reenroll` and `and-passkeys`, which change the same auth screens.
