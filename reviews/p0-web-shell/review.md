# Code Review

Date: 2026-10-20

Task: p0-web-shell - Application shell: navigation, top bar, palette skeleton, placeholders

Scope: task commits `d600444a71bf87e29e298e4ce64598d70aa8cd22..7fea35d` on `main` (implementation commit `ca6af09` and test-writer commit `7fea35d`). Committed changes only. The working tree was clean.

Requirements:
- `tasks/p0-web-shell.md` acceptance criteria and its five recorded assumptions (theme-init.js, hotkey rules, GTD URLs at the root, provider tokens, lazy shell)
- `SPEC.md` §4.4, §4.5, §4.7, §9.1
- `docs/ARCHITECTURE.md` (web stack, i18n, zoneless/signals conventions, test rules), `docs/QUALITY.md`
- `AGENTS.md` project notes (feature folders, touches, lint, i18n)
- Parent instructions: confirm or refine test-writer finding TW-1 (`reviews/p0-web-shell/tests.md`)

Git range:
- Branch: main
- HEAD: 7fea35d
- Base/start: d600444a71bf87e29e298e4ce64598d70aa8cd22
- Commits reviewed: 2

Commands run (read-only, outputs to `.tmp/` or Git-ignored `dist/`):
- `cd web && npm run check` -> api:check pass, lint pass ("All files pass linting"), `test:ci` **1 failed / 119 passed** (`app.spec.ts` > "wires the navigation commands into the palette": expected the 18 nav labels, received `[]`), so `ng build` was not reached
- `cd web && npx ng build` -> pass, initial total 451.66 kB (budget warning at 500 kB), shell lazy chunk 69.84 kB, every feature routes file in its own lazy chunk
- `cd web && npx ng extract-i18n --output-path ../.tmp/xlf-review` -> 75 messages; the result is byte-identical to the committed `web/src/locale/messages.xlf`
- Source check of `node_modules/@angular/cdk/fesm2022/dialog.mjs` (lines 535-558) and `@angular/material/fesm2022/dialog.mjs` (line 371) to confirm the dialog injector root cause

Summary: 0 P0, 1 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | The command palette can't see the PALETTE_PROVIDERS from the shell route, so it is always empty in the real app (TW-1 confirmed)**
    - File: `web/src/app/shell/shell.ts:80` (with `web/src/app/app.routes.ts:15`)
    - Category: Correctness / Integration
    - Problem: `app.routes.ts` registers `provideNavigationPalette()`, `provideCapture()` and `provideSearch()` on the shell route, which puts them in the route's environment injector. `Shell.openPalette()` calls `this.dialog.open(CommandPalette, {...})` with no `injector` and no `viewContainerRef`. `MatDialog` is `providedIn: 'root'`, and the CDK dialog builds the content injector as `parent: config.injector || config.viewContainerRef?.injector || this._injector` (cdk `dialog.mjs`:535-558). Here `this._injector` is the root injector. `CommandPalette` therefore resolves `PALETTE_PROVIDERS` from the root factory (`() => []`) and always shows "Nothing matches". The "Go to" commands and any future web-search provider never appear. `shell.spec.ts` and `command-palette.spec.ts` don't catch this because they register the providers at the TestBed root. The test-writer's integration test in `app.spec.ts:75-104` goes through the real routes and fails.
    - Why it matters: AC3 says palette contents are provided via `PALETTE_PROVIDERS`, and that does not work in the shipped wiring. AC5 says `npm run check` passes, and it fails, so `.taskcheck` fails too. web-search (`provideSearch()`) would hit the same bug.
    - Suggested fix: Pass the shell's injector to the dialog: `private readonly injector = inject(Injector)` in `Shell`, then `this.dialog.open(CommandPalette, { ..., injector: this.injector })`. This is the component's node injector, which chains to the route environment injector. A `viewContainerRef` would also work. Keep the failing `app.spec.ts` test as the regression guard. Don't move the providers to `app.config.ts`: the README and the assumptions say feature tasks plug in through `app.routes.ts`.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | The capture placeholder receives the route injector but doesn't pass it to the dialog it opens**
    - File: `web/src/app/features/capture/capture-placeholder.ts:23`
    - Category: Quality / Integration
    - Problem: `openCapturePlaceholder(injector)` only uses the injector to get `MatDialog`. It opens `CapturePlaceholder` without `{ injector }`, so the dialog content gets the root injector, the same pattern as CR-1. The placeholder content needs nothing route-scoped, so nothing breaks today. This file is the template web-capture is told to replace, though (README, task assumption).
    - Why it matters: If the real capture dialog injects anything provided on the shell route, it fails silently in the same way as CR-1.
    - Suggested fix: Open the dialog with `{ width: '480px', maxWidth: '95vw', injector }`. Optionally add a one-line comment in `shell-tokens.ts` on `CaptureDialog`: implementations that open a dialog must pass their injector.

- [ ] **CR-3 | The rule for hotkeys while a dialog is open differs from the recorded assumption**
    - File: `web/src/app/shell/shell.ts:61`
    - Category: Correctness (documentation consistency)
    - Problem: The task assumption says "Single-key shortcuts are ignored while a dialog is open". The code ignores every action except `palette`, so `Ctrl+Shift+Space` (not a single key) is also ignored while a dialog is open. The tests only cover `Q` in this situation (`shell.spec.ts` "does not open capture while a dialog is open").
    - Why it matters: Humans review the assumptions to understand the behavior, and this one describes it wrongly. Blocking capture over an open dialog is a reasonable choice, but it should be recorded as the decision.
    - Suggested fix: Either reword the assumption with `tasks.py assume` ("all shortcuts except Ctrl+K are ignored while a dialog is open") and add a `Ctrl+Shift+Space` case to that test, or exempt `Ctrl+Shift+Space` in `onKeydown`.

## Areas checked without findings

- **AC1 navigation:** All 16 sections from SPEC §9.1 plus Logbook and Trash are in `nav-sections.ts`, in groups. The `<nav>` has a label, `aria-current="page"` is set and the dashboard link uses an exact match. Below 900 px (`(max-width: 899.98px)`) the navigation is a drawer with `mode="over"`. The CDK `BreakpointObserver` emits its first value synchronously, so a narrow screen doesn't flash the side navigation. On compact screens the drawer closes after a navigation.
- **AC2 routing:** Every section lazy-loads its routes file, which has a default export and renders `ComingSoon` with a route `title`. All 18 files are listed in `features/README.md`. The GTD routes are mounted at `''` after the other sections and before `**`, so `/inbox` and the others resolve correctly, and unknown URLs fall through to the not-found page (the 409-byte gtd chunk is loaded on the way, which is harmless). The shell is a lazy `loadComponent` with `path: ''`, so p0-web-auth can add `/login` before it and put a guard on it.
- **AC3 hotkeys:** `shellActionFor` ignores `repeat`, `isComposing` and `defaultPrevented`. `Q` and `?` are suppressed in text inputs, textarea, select and contenteditable. That covers the future CodeMirror and TipTap editors, and typing in the palette's own input. `Ctrl/Meta+K` and `Ctrl+Shift+Space` work while typing. The palette doesn't open twice. The `switchMap` per query in `CommandPalette` drops stale provider results, and `catchError` per provider keeps a failing provider from emptying the list.
- **AC4 theme:** `public/theme-init.js` is a synchronous, same-origin `<script src>` in `<head>`. It works with a strict `script-src 'self'` CSP without a hash or nonce, and it only accepts the three valid values, wrapped in try/catch. `ThemeService` reads and writes the same key inside try/catch. `data-theme="system"` falls back to `color-scheme: light dark` in `src/theme/_theme.scss`. The menu uses `menuitemradio` with `aria-checked`.
- **Angular conventions:** Every new component is standalone and OnPush, uses signals and `inject()`, and needs no zone (no `NgZone` or zone-dependent APIs). Lint passes with `--max-warnings=0`.
- **i18n:** Every user-facing string goes through `i18n` or `$localize` with an explicit id, `messages.xlf` is current (identical to a fresh extraction), and `app.i18n.spec.ts` checks the translated shell texts.
- **Bundle:** 451.66 kB initial, under the 500 kB warning.
- **Security:** No secrets, no `innerHTML`/`bypassSecurityTrust*`, and no URLs built from user input (palette navigation uses fixed paths).
- **Tests:** The tests check behavior through the DOM and the router. Apart from the CR-1 regression test, the suite passes. No existing test was weakened. In `app.i18n.spec.ts` the old tests for the `App` title and scaffold were replaced because that markup is gone. The responsive behavior is tested with a stubbed `BreakpointObserver`, and no browser run was done for the "before first paint" part (the test-writer already reported both as not executed in a browser).

## Review conclusion

Material issues found; fix before completing the task.
