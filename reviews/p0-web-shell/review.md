# Code Review

Date: 2026-10-20

Task: p0-web-shell - Application shell: navigation, top bar, palette skeleton, placeholders

Scope: re-review of the final code. Task commits `d600444a71bf87e29e298e4ce64598d70aa8cd22..2ac2815c9eb422e8435ccac43d8daf485e330e26` on `main`: implementation `307e57f`, test-writer `ce2c6f1`, first review `3acd2f7`, fixes `a999e94` (CR-1/TW-1, CR-2, CR-3), re-test `2ac2815`. The other commits in the range only change `tasks/` (claims, assumptions, heartbeats, partly from other agents' tasks). Committed changes only. The working tree was clean. This report replaces the earlier review of `7fea35d`.

Requirements:
- `tasks/p0-web-shell.md` acceptance criteria and its six recorded assumptions, including the corrected hotkey rule (every shortcut except Ctrl+K is ignored while a dialog is open)
- `SPEC.md` §4.4, §4.5, §9.1
- `docs/ARCHITECTURE.md` (web stack, conventions, test rules), `docs/QUALITY.md`
- `AGENTS.md` project notes (feature folders, touches, lint, i18n)
- Parent instructions: verify the fixes in `reviews/p0-web-shell/fixes.md`, re-check the whole task, run `cd web && npm run check`

Git range:
- Branch: main
- HEAD: 2ac2815c9eb422e8435ccac43d8daf485e330e26
- Base/start: d600444a71bf87e29e298e4ce64598d70aa8cd22
- Commits reviewed: 5 task commits (plus task-board-only commits in the range)

Commands run (read-only):
- `git diff 7fea35d..HEAD -- web/ tasks/p0-web-shell.md`, plus a full read of `shell.ts`, `capture-placeholder.ts`, `capture.providers.ts`, `app.routes.ts`, `command-palette.ts`
- `npm --prefix web run check` (same as `cd web && npm run check`) -> pass: api:check "src/app/api/generated is up to date.", lint "All files pass linting." (`--max-warnings=0`), test:ci 13 files / 124 tests passed, `ng build` success, initial total 451.66 kB (under the 500 kB warning), shell lazy chunk 69.91 kB, each feature routes file in its own lazy chunk
- `python tasks.py verify p0-web-shell` (read-only listing) -> all 5 criteria still unticked; the parent records evidence after the review (AGENTS.md quality step 7)

Summary: 0 P0, 0 P1, 0 P2, 0 P3 open (3 earlier findings resolved, see below)

## Earlier findings and their disposition

- [x] **CR-1 (P1) | Command palette could not see the PALETTE_PROVIDERS of the shell route (TW-1)** - Resolved in `a999e94`. `Shell` now injects its `Injector` (`web/src/app/shell/shell.ts:39`) and passes it as `injector` when opening `CommandPalette` (`shell.ts:98`) and `ShortcutsDialog` (`shell.ts:80-84`). The node injector chains to the shell route's environment injector, so `provideNavigationPalette()` and `provideSearch()` from `app.routes.ts:15` are visible. Verified by tests that go through the real wiring: `app.spec.ts` "wires the navigation commands into the palette: Ctrl+K, type, Enter navigates" (failed before the fix, passes now) and `shell.spec.ts` "Shell with route-level palette providers" (a second multi provider on the route shows up as its own group, the way web-search will plug in).
- [x] **CR-2 (P3) | Capture placeholder did not pass the route injector to its dialog** - Resolved in `a999e94`. `openCapturePlaceholder` opens the dialog with `{ width, maxWidth, injector }` (`web/src/app/features/capture/capture-placeholder.ts:23-25`), with a doc comment explaining why. The injector is the one of `PlaceholderCaptureDialog`, which is provided on the shell route, so it is the route environment injector. Covered by `capture.providers.spec.ts` "opens the dialog with the injector that provides CAPTURE_DIALOG" (a token that exists only in a child injector is resolvable from the dialog) and `app.spec.ts` "opens the shortcut overview and the capture dialog with the shell route providers".
- [x] **CR-3 (P3) | Hotkey rule with an open dialog differed from the recorded assumption** - Resolved by keeping the behavior and correcting the record. The task file now has the assumption "Correction: while any dialog is open, every shell shortcut except Ctrl+K is ignored (Q, ?, and Ctrl+Shift+Space)". `shell.ts:62-63` states the same rule in a comment, and the code matches it (`shell.ts:64`). `shell.spec.ts` "does not open capture with Ctrl+Shift+Space while a dialog is open" covers the case that was untested, including that the event is not default-prevented.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Acceptance criteria re-check

- **AC1 navigation:** All SPEC §9.1 sections plus Logbook and Trash are in `nav-sections.ts`. Below 900 px (`(max-width: 899.98px)`) the navigation is an `over` drawer that closes after navigation. Tested in `shell.spec.ts` with a stubbed `BreakpointObserver`. The real media query was not run in a browser (the test-writer reports this as not run); the code path is the standard CDK one.
- **AC2 routing:** `app.routes.ts` lazy-loads one routes file per section (the build output shows a lazy chunk per routes file). Each renders `ComingSoon`. All 18 routes files are listed in `features/README.md`. GTD sections are mounted at `''` before `**`, so the not-found page still works.
- **AC3 top bar and hotkeys:** Search field and Ctrl/Meta+K open the palette (once; also in inputs and over another dialog). `+`, `Q` and `Ctrl+Shift+Space` open capture; `Q` and `?` are ignored while typing; every shortcut except Ctrl+K is ignored while a dialog is open, as now recorded. `CAPTURE_DIALOG` and `PALETTE_PROVIDERS` have placeholder implementations and, after the CR-1/CR-2 fixes, work in the real route wiring.
- **AC4 theme:** `public/theme-init.js` loaded synchronously in `<head>` applies the stored mode before first paint; `ThemeService` reads and writes `cockpit.theme` inside try/catch; tests cover a throwing `localStorage`. No-flash in a real browser was not run.
- **AC5 tests and check:** Navigation rendering, hotkeys ignored in inputs and theme switching are covered by Vitest. `cd web && npm run check` passes (124 tests).

## Areas checked without findings

- **Fix scope:** The fixes change only `shell.ts` and `capture-placeholder.ts` plus specs, all inside the task's `touches`. No behavior change besides the injector; no test was weakened or removed.
- **Angular conventions:** Standalone, OnPush, signals, `inject()`, zoneless; lint clean.
- **Security:** No secrets, no `innerHTML`/`bypassSecurityTrust*`, palette navigation uses fixed paths. Test data is fictitious ("Fictitious note").
- **Async:** The palette `switchMap`s per query and isolates provider errors with `catchError`, so stale or failing providers cannot overwrite or empty newer results.

## Review conclusion

No material issues found.
