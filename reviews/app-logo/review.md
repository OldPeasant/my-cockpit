# Code Review

Date: 2026-10-22

Task: app-logo - Show the brand mark in the web top bar, web login/setup and Android login

Scope: `git diff 4ded718a..9dfe408b` (task code commits 56903887 implementation and 9dfe408b tests; committed changes only)

Requirements:
- tasks/app-logo.md acceptance criteria and the three recorded assumptions
- docs/brand/mark.svg and docs/brand/README.md (brand source of truth)
- SPEC.md / docs/ARCHITECTURE.md / AGENTS.md project conventions (lint, feature folders, theme tokens)
- Parent instructions: previous round CR-1 (placement specs) and CR-2 (unused `topbar-logo` class)

Git range:
- Branch: main
- HEAD: 9dfe408b75111c886119418b822d115c9445407f
- Base/start: 4ded718abd60ff67cdc7596f55e27ca18e229ceb (`git merge-base HEAD origin/main`)
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Android decorative-logo test asserts on the whole screen, not on the logo**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/auth/LoginScreenTest.kt:225`
    - Category: Tests
    - Problem: the test checks that no node on the entire LoginScreen defines `ContentDescription` (count 0). It actually checks "the screen has no content descriptions at all", not "the logo has none".
    - Why it matters: any later, legitimate content description on this screen (for example a password visibility toggle or an icon button, which `and-passkeys` / `web-totp-reenroll`-style follow-ups are likely to add) breaks this test even though the logo is still correct. Someone will then have to loosen it.
    - Suggested fix: give the Image a test tag (or wrap it in a tagged Box) and assert on that node only, e.g. that the tagged node has no `ContentDescription` and no `Role.Image`. Or, at minimum, assert that no node carries an Image role or a description that matches the app name.

## Notes (verified, no finding)

- AC1: `logo.ts` is a standalone OnPush component with inline SVG and no `<image>`/`<use>`/`<mask>`. Its path data matches the Android launcher geometry and the README (dials r 8, needle stadiums of half width 1.375, horizon r 19, same ground path). The ink is `light-dark(#14243a, #eef2f7)`, which works because `web/src/theme/_theme.scss` sets `color-scheme` from `prefers-color-scheme` (`light dark`) and from `[data-theme]`. The sky stays `#e8962e`. The host is `aria-hidden="true"`, the SVG is `focusable="false"`, and there is a `size` input (default 28) exposed as `--logo-size`. Every target browser of Angular 22's default browserslist supports `light-dark()`.
- AC2: the brand link holds the logo plus a `<span i18n="@@app.title">`, so the accessible name is still "My Cockpit" and the i18n id is kept. In the compact layout the top bar gets one extra decorative 28 px logo after the menu button. This matches the recorded assumption that the brand link lives in the side nav. `auth-layout.ts` renders a 64 px logo above the app name and the projected form. The shell and auth-layout specs check this placement, so the previous CR-1 is resolved.
- Previous CR-2: `topbar-logo` still has no style rule. The new compact-layout spec now uses it as a selector (`header.topbar app-logo.topbar-logo`), so it is no longer dead. Not reported again.
- AC3: `drawable/ic_logo.xml` and `drawable-night/ic_logo.xml` have a transparent background, use the brand inks with `#E8962E` sky, and contain the same geometry. `LoginScreen` shows the drawable at 72 dp above the title with `contentDescription = null`, and the top padding moved from the title to the logo. `LogoDrawableTest` checks the night/notnight ink.
- AC4: I re-ran the focused web specs here: `cd web && npx ng test --watch=false --include src/app/shell/shell.spec.ts --include src/app/shell/logo.spec.ts --include src/app/features/auth/auth-layout.spec.ts` -> 3 files, 30 tests passed. I did not run the full `npm run check` or the Android Gradle suites in this review. The parent must record them with `tasks.py verify` (AC1-AC4 were not ticked yet at review time).
- No secrets, no new dependencies, no security-relevant changes.

## Review conclusion

No material issues found.
