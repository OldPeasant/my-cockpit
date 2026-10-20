# Fix Report

Date: 2026-10-20

Task: p0-web-shell - Application shell: navigation, top bar, palette skeleton, placeholders

Scope: current task. Findings TW-1 (`reviews/p0-web-shell/tests.md`) and CR-1, CR-2, CR-3 (`reviews/p0-web-shell/review.md`), as approved by the parent worker. Base commit d600444a71bf87e29e298e4ce64598d70aa8cd22.

## Fixed findings

### CR-1 / TW-1 (P1) - The command palette can't see the PALETTE_PROVIDERS from the shell route

Files changed:
- `web/src/app/shell/shell.ts`

Problem:
`Shell.openPalette()` opened `CommandPalette` through the root `MatDialog` without an injector, so the dialog content resolved `PALETTE_PROVIDERS` from the root injector (empty default) instead of the shell route's environment injector where `app.routes.ts` registers `provideNavigationPalette()`, `provideCapture()` and `provideSearch()`. The palette was always empty in the real app; the integration test in `web/src/app/app.spec.ts` failed.

Fix:
`Shell` now injects its `Injector` and passes it as `injector` in the `MatDialog` config of the command palette and, for consistency, of the shortcuts dialog. The node injector chains to the route environment injector. `app.spec.ts` was not changed.

Validation:
- `cd web && npm run check` - Pass (test:ci 13 files, 121 tests passed, including `app.spec.ts` "wires the navigation commands into the palette: Ctrl+K, type, Enter navigates", which failed before the fix)

### CR-2 (P3) - The capture placeholder doesn't pass the route injector to its dialog

Files changed:
- `web/src/app/features/capture/capture-placeholder.ts`

Problem:
`openCapturePlaceholder(injector)` used the injector only to get `MatDialog` and opened `CapturePlaceholder` with the root injector, the same pattern as CR-1, in the file web-capture is meant to copy.

Fix:
The dialog is opened with `{ width: '480px', maxWidth: '95vw', injector }`, plus a one-line doc comment explaining why.

Validation:
- `cd web && npm run check` - Pass (`app.spec.ts` "wires the capture placeholder into the shell: Q opens its dialog" and `capture.providers.spec.ts` pass)

### CR-3 (P3) - Hotkey rule while a dialog is open differs from the recorded assumption

Files changed:
- `web/src/app/shell/shell.ts`
- `web/src/app/shell/shell.spec.ts`

Problem:
The assumption said only single-key shortcuts are ignored while a dialog is open; the code ignores every shortcut except Ctrl+K, and only `Q` was tested. Per parent decision the behaviour stays; the assumption was corrected by the parent worker.

Fix:
No behaviour change. The comment in `Shell.onKeydown` now states the rule (every shortcut except Ctrl+K is ignored while any dialog is open, including Q, Ctrl+Shift+Space and ?). New test in `shell.spec.ts`: "does not open capture with Ctrl+Shift+Space while a dialog is open" (opens the shortcut overview, sends Ctrl+Shift+Space, expects capture not opened and the event not default-prevented).

Validation:
- `cd web && npm run check` - Pass (new test included in the 121 passing tests)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx prettier --check src/app/shell src/app/features/capture` - first run flagged `capture-placeholder.ts` formatting; fixed with `npx prettier --write` on that single file (generated API code not touched)
- `cd web && npm run check` - Pass (api:check up to date, lint "All files pass linting", test:ci 13 files / 121 tests passed, `ng build` success, initial total 451.66 kB)

Full test suite:
- `cd web && npm run check` - Pass (see above)

Lint/type-check/build:
- `npm run lint` (ng lint --max-warnings=0) and `ng build` as part of `npm run check` - Pass

`.taskcheck`:
- Pass (`sh scripts/check.sh` exit code 0, "all checks passed")

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
