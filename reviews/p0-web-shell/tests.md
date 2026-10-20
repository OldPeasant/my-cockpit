# Test Report

Date: 2026-10-20

Task: p0-web-shell - Application shell: navigation, top bar, palette skeleton, placeholders

Scope: task commits `d600444a71bf87e29e298e4ce64598d70aa8cd22..ca6af091c5db9a0c02e17e628d73b58ea08b8817` (one commit, `ca6af09 p0-web-shell: app shell ...`, branch `main`), as supplied by the parent worker and checked against the task's acceptance criteria and `touches`. Plus the uncommitted test files listed below (no uncommitted production changes).

Requirements:
- `tasks/p0-web-shell.md` acceptance criteria and recorded assumptions (theme-init.js, hotkey rules, GTD URLs, provider tokens, lazy shell)
- `SPEC.md` §9.1 (via the task), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- Existing test conventions in `web/src/app/**/*.spec.ts` (Vitest via `@angular/build:unit-test`, jsdom, zoneless TestBed)

## Test files changed

- `web/src/app/app.spec.ts` (modified: 2 integration tests through the real `app.routes.ts`)
- `web/src/app/shell/shell.spec.ts` (modified: 5 tests)
- `web/src/app/shell/command-palette.spec.ts` (modified: 1 test)
- `web/src/app/shell/hotkeys.spec.ts` (modified: 1 assertion added to an existing test)
- `web/src/app/shell/theme-init.spec.ts` (new: pre-paint script `public/theme-init.js`)
- `web/src/app/shell/pages.spec.ts` (new: `NotFound`, `ShortcutsDialog`)
- `web/src/app/features/capture/capture.providers.spec.ts` (new: placeholder `CAPTURE_DIALOG`)

No existing test was removed, weakened or skipped.

## Tests added or updated

Count: 22 new tests (98 -> 120 in the web suite), 1 existing test extended.

- theme-init.spec.ts: `applies the stored mode light|dark|system to <html>` (3), `falls back to system when nothing is stored`, `falls back to system for the invalid stored value "neon"|""|"DARK"|"\"dark\""` (4), `falls back to system without throwing when localStorage is unavailable`, `reads what ThemeService remembers, so the next page load starts with the chosen theme`. The script is re-executed per test with `vi.resetModules()` + dynamic import, i.e. the real file shipped in `public/` is run.
- capture.providers.spec.ts: `provides CAPTURE_DIALOG with the placeholder implementation`, `opens the lazily loaded placeholder dialog with a close button`.
- pages.spec.ts: `NotFound explains the dead end and links back to the dashboard`, `ShortcutsDialog lists every shell shortcut with its keys and description`.
- app.spec.ts: `wires the capture placeholder into the shell: Q opens its dialog`, `wires the navigation commands into the palette: Ctrl+K, type, Enter navigates` (**fails, TW-1**).
- shell.spec.ts: `opens the palette with Ctrl+K while typing in an input and while another dialog is open`, `opens capture with Ctrl+Shift+Space while typing in an input`, `does not open the shortcut overview twice`, `marks the current theme in the theme menu`, `keeps the side navigation open after following a link on wide screens`.
- command-palette.spec.ts: `shows the empty state without providers and ignores Enter and arrow keys`.
- hotkeys.spec.ts: `maps Q and Ctrl+Shift+Space to capture` now also covers a space key reported without `code`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Left navigation with all SPEC §9.1 sections incl. Logbook and Trash | shell.spec `renders a link for every navigation section, Logbook and Trash included` (existing); app.spec `lazy-loads a coming soon page for %s` (existing, 18 cases, active link + aria-current) | `cd web && npx ng test --watch=false` | Pass |
| AC1 Responsive: drawer below 900 px | shell.spec `shows the navigation side by side on wide screens`, `collapses the navigation to a drawer below 900 px`, `switches between side navigation and drawer when the width changes` (existing); `keeps the side navigation open after following a link on wide screens` (new). BreakpointObserver is stubbed; the real 899.98 px media query in a browser was not exercised | `cd web && npx ng test --watch=false` | Pass (jsdom, breakpoint stubbed) |
| AC2 `app.routes.ts` lazy-loads every `features/<feature>/<feature>.routes.ts`; each renders 'coming soon' | app.spec `lazy-loads a coming soon page for %s` (existing, all 18 sections), `renders the shell with the dashboard placeholder at /`, `shows a not found page for unknown URLs` (existing); pages.spec `NotFound explains the dead end and links back to the dashboard` (new) | `cd web && npx ng test --watch=false` | Pass |
| AC2 Routes file names listed in `features/README.md` | Not automated (no file-system access from the jsdom test build). Checked manually: all 18 routes files under `web/src/app/features/**` appear in the README table | manual read of `web/src/app/features/README.md` vs `git diff --name-only` | Pass (manual) |
| AC3 Search field opens the palette; `Ctrl+K` opens the palette (also in inputs, also over another dialog, only once) | shell.spec `opens the command palette with Ctrl+K and from the search field, only once` (existing); `opens the palette with Ctrl+K while typing in an input and while another dialog is open` (new); hotkeys.spec (existing) | `cd web && npx ng test --watch=false` | Pass |
| AC3 Capture '+' button, `Q` / `Ctrl+Shift+Space`, ignored while typing | shell.spec `opens capture with Q, Ctrl+Shift+Space and the + button`, `ignores Q and ? while typing in an input`, `does not open capture while a dialog is open` (existing); `opens capture with Ctrl+Shift+Space while typing in an input` (new); hotkeys.spec (existing + extended) | `cd web && npx ng test --watch=false` | Pass |
| AC3 `?` shortcut overview | shell.spec `opens the shortcut overview with ?` (existing), `does not open the shortcut overview twice` (new); pages.spec `ShortcutsDialog lists every shell shortcut ...` (new) | `cd web && npx ng test --watch=false` | Pass |
| AC3 `CAPTURE_DIALOG` placeholder implementation, wired via `app.routes.ts` | capture.providers.spec (2 new); app.spec `wires the capture placeholder into the shell: Q opens its dialog` (new) | `cd web && npx ng test --watch=false` | Pass |
| AC3 Palette contents provided via `PALETTE_PROVIDERS` with placeholder implementation (shell 'Go to' commands) | command-palette.spec (existing 6, new 1; providers registered at TestBed root) | `cd web && npx ng test --watch=false` | Pass |
| AC3 Same, in the real app wiring (providers registered on the shell route in `app.routes.ts`) | app.spec `wires the navigation commands into the palette: Ctrl+K, type, Enter navigates` (new) | `cd web && npx ng test --watch=false` | **Fail - TW-1** |
| AC4 Theme toggle light/dark/system, `ThemeService` exposes it | theme.service.spec (existing 5); shell.spec `switches the theme from the theme menu` (existing), `marks the current theme in the theme menu` (new) | `cd web && npx ng test --watch=false` | Pass |
| AC4 Remembered in localStorage, read/write wrapped in try/catch | theme.service.spec `keeps working when localStorage throws` (existing); theme-init.spec `falls back to system without throwing when localStorage is unavailable` (new) | `cd web && npx ng test --watch=false` | Pass |
| AC4 Applied before first paint (`public/theme-init.js`, loaded from index.html) | theme-init.spec (10 new, runs the real script) | `cd web && npx ng test --watch=false` | Pass for the script's behaviour. That `index.html` loads it synchronously before the bundle was checked by reading `web/src/index.html` (`<script src="theme-init.js">` in `<head>`); no-flash in a real browser: Not run (no browser/Playwright run in this task) |
| AC5 Vitest tests cover navigation rendering, hotkeys ignored in inputs, theme switching | see rows above | `cd web && npx ng test --watch=false` | Pass (those tests pass) |
| AC5 `cd web && npm run check` passes | whole web check | `cd web && npm run check` | **Fail** (api:check pass, lint pass, test:ci 1 failed / 119 passed - TW-1; `ng build` not reached in that run, passed separately with `npx ng build`) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/shell` (and per new spec file with `--include <file>`)

Result:

Pass (6 files, 45 tests). `--include src/app/app.spec.ts`: 1 failed (TW-1), 22 passed.

## Full test execution

Command:

`cd web && npm run check` (= `npm run api:check && npm run lint && npm run test:ci && ng build`); also `npm run lint`, `npx ng build` separately

Result:

Fail. `api:check`: generated client up to date. `lint`: All files pass linting. `test:ci`: 13 files, 120 tests, 1 failed (TW-1), 119 passed. `npx ng build` run on its own: success.

## .taskcheck

Result:

Fail. `sh scripts/check.sh` (2026-10-20 11:44:48 -> 11:45:24 +02:00): the web step failed on the single TW-1 test (1 failed / 119 passed); earlier steps in the script output (18 Python tests) passed. Before the new tests were added the web suite was green (98/98), so the failure is the new regression test exposing TW-1, not a broken test.

## Bugs found

### Command palette never sees the route-level PALETTE_PROVIDERS: it is always empty in the real app

Finding ID:

`TW-1`

Test:

`web/src/app/app.spec.ts` > `App` > `wires the navigation commands into the palette: Ctrl+K, type, Enter navigates`

Production source:

`web/src/app/shell/shell.ts:85` (`this.dialog.open(CommandPalette, {...})` in `openPalette()`), in combination with `web/src/app/app.routes.ts:15` (`providers: [provideNavigationPalette(), provideCapture(), provideSearch()]` on the lazy shell route) and the root default `web/src/app/shell/shell-tokens.ts:37` (`factory: () => []`).

Requirement:

AC3: "Capture and palette contents are provided via injection tokens (`CAPTURE_DIALOG`, `PALETTE_PROVIDERS`) with placeholder implementations"; task note: "extra PALETTE_PROVIDERS (multi) from features/search/search.providers.ts provideSearch(); the shell adds 'Go to <section>' palette commands."

Expected behavior:

With the real `routes`, pressing Ctrl+K (or clicking the search field) opens the palette listing the 18 "Go to <section>" commands; typing `trash` + Enter navigates to `/trash`. Providers added later by `provideSearch()` must also appear.

Actual behavior:

The palette shows only "Nothing matches. Try another word." for every query. `MatDialog` is root-provided and `open()` is called without `injector`/`viewContainerRef`, so the CDK dialog builds the component injector on the root injector (`node_modules/@angular/cdk/fesm2022/dialog.mjs:535-558`: `parent: userInjector || this._injector`). `CommandPalette` therefore injects the root default `PALETTE_PROVIDERS = []` instead of the providers registered on the shell route. The existing `shell.spec.ts` / `command-palette.spec.ts` miss this because they register `provideNavigationPalette()` at the TestBed root. Confirmed by a throw-away copy of the new test with `provideNavigationPalette()` additionally at the root: 23/23 passed (copy deleted afterwards).

Severity:

High (the palette is non-functional in the shipped app, and the search feature's documented plug-in point `provideSearch()` cannot work).

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

(Direction for the fixer, not a requirement: open the dialog with the shell's own injector, e.g. `injector` or `viewContainerRef` in the `MatDialog.open` config. The same root-injector effect applies to `openCapturePlaceholder` and `ShortcutsDialog`; harmless today because those dialogs inject nothing route-scoped.)

## Coverage

Status: partial-failed. The project's coverage command `cd web && npm run coverage` exited non-zero (1 failing test, TW-1) and wrote no report (`web/coverage/web` absent after the run). The numbers below come from a diagnostic run of the same command that deselects only the TW-1 test with `--filter`, so they are diagnostic, not a passing validation. In that run the palette-wiring path of TW-1 is not exercised.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All 33 executable scoped files are new in this task except `app.ts` and `app.routes.ts`; since every executable line of every scoped file is covered in the diagnostic run (221/221), no changed executable line is uncovered in that run.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-web-shell`; base `d600444a71bf87e29e298e4ce64598d70aa8cd22` (supplied by the parent); measured HEAD `ca6af091c5db9a0c02e17e628d73b58ea08b8817`; task-owned commit `ca6af09` (only commit in `d600444..HEAD`); file scope from `git diff --name-only d600444..HEAD -- web/src web/public`, matching the task `touches` (plus `web/public/theme-init.js` and `web/src/index.html` per the recorded assumption).
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD `ca6af09` (no uncommitted production changes). Uncommitted test files, sha256 prefixes: `app.spec.ts` bf682a1cad2a5466, `shell/command-palette.spec.ts` af05c82893d7a03b, `shell/hotkeys.spec.ts` 29b07f978a2e0b6a, `shell/pages.spec.ts` 31b2a600b613664b, `shell/shell.spec.ts` 8e5153fc29dc541f, `shell/theme-init.spec.ts` 1fad11140b11df3f, `shell/theme.service.spec.ts` (unchanged) b165b5c01083b786, `features/capture/capture.providers.spec.ts` ecbc609da3b42356. Hashes identical before and after the final runs.
- Exact included files: `web/public/theme-init.js`, `web/src/app/app.ts`, `web/src/app/app.routes.ts`, all 23 `.ts` files under `web/src/app/features/**` changed by the task, and `web/src/app/shell/{coming-soon,command-palette,hotkeys,nav-sections,navigation-palette.provider,not-found,shell-tokens,shell,shortcuts-dialog,theme.service}.ts` (35 files). Excluded: `shell/shell.html`, `shell/command-palette.html`, `app.html` (templates compiled into the component files, counted there), `*.scss`, `app.scss` (styles), `features/README.md`, `web/src/index.html` (static markup), `web/src/locale/messages.xlf` (generated i18n). No renamed or deleted files. No scoped file is missing from the report.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` 5.0.5, Node v24.21.0, via `@angular/build:unit-test`; working directory `web/`. (1) `npm run coverage` (all specs). (2) `npm run coverage -- --filter '^(?!.*wires the navigation commands into the palette)' --coverage-include 'src/app/shell/**/*.ts' --coverage-include 'src/app/features/**/*.ts' --coverage-include 'src/app/app.ts' --coverage-include 'src/app/app.routes.ts' --coverage-include 'public/theme-init.js'` (all 13 spec files, 119 tests run, 1 deselected). `--coverage-include` only restricts the report to the task scope and makes unexecuted scoped files visible. No config file changed.
- Run start/completion time and exit status: (1) 2026-10-20 11:45:29 -> ~11:45:36 +02:00, exit non-zero (1 failed / 119 passed), no report written. (2) 11:45:36 -> 11:45:41 +02:00, exit 0, 119 passed / 1 skipped by filter.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 62745a961224f63df0227547a8e45f9b43a1c419a1dd07ecb7cd05cdc42e8047; `web/coverage/web/coverage-final.json` sha256 fe746b2af5cd74fd5c49415fd11a12120cc5e5630576b50627d46c97150c4c94; `web/coverage/web/lcov.info` sha256 b055b19b23c984d9dd1e3f319a93fd4b2be4590a7e2f5a4ce29a99ea377c0d54 (Git-ignored).
- Evidence outputs are fresh and inputs stayed unchanged: run (1) left no `coverage/web` directory, so run (2)'s files (all mtimes 11:45:4x) were created by run (2). Test file hashes and `git status` were the same before and after.
- Final run after latest test additions and production fixes: yes for the latest test additions; no production fix has been made yet (TW-1 open), so this measures pre-fix revision `ca6af09`. Coverage must be re-measured after the fix.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Diagnostic run (2).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/public/theme-init.js` | 7/7 (100%) | 5/5 (100%) | 1/1 (100%) | 7/7 (100%) |
| `web/src/app/app.routes.ts` | 16/16 (100%) | N/A (0/0) | 15/15 (100%) | 16/16 (100%) |
| `web/src/app/app.ts` | 5/5 (100%) | 9/10 (90%) | 1/1 (100%) | 1/1 (100%) |
| `web/src/app/features/capture/capture-placeholder.ts` | 17/17 (100%) | 8/9 (88.89%) | 2/2 (100%) | 7/7 (100%) |
| `web/src/app/features/capture/capture.providers.ts` | 7/7 (100%) | 5/5 (100%) | 3/3 (100%) | 4/4 (100%) |
| `web/src/app/features/gtd/gtd.routes.ts` | 7/7 (100%) | N/A (0/0) | 6/6 (100%) | 7/7 (100%) |
| `web/src/app/features/search/search.providers.ts` | 1/1 (100%) | N/A (0/0) | 1/1 (100%) | 1/1 (100%) |
| 18 placeholder routes files: `features/{bookmarks,calendar,contacts,dashboard,files,habits,journal,mail,notes,review,settings,trash}/*.routes.ts`, `features/gtd/{inbox,next,projects}/*.routes.ts`, `features/gtd/lists/{logbook,someday,waiting}.routes.ts` | 1/1 (100%) each, 18/18 total | N/A (0/0) | N/A (0/0) | 1/1 (100%) each, 18/18 total |
| `web/src/app/shell/coming-soon.ts` | 12/12 (100%) | 9/11 (81.82%) | 1/1 (100%) | 5/5 (100%) |
| `web/src/app/shell/command-palette.ts` | 53/53 (100%) | 26/27 (96.30%) | 19/19 (100%) | 44/44 (100%) |
| `web/src/app/shell/hotkeys.ts` | 27/27 (100%) | 41/41 (100%) | 2/2 (100%) | 27/27 (100%) |
| `web/src/app/shell/nav-sections.ts` | 3/3 (100%) | N/A (0/0) | 1/1 (100%) | 2/2 (100%) |
| `web/src/app/shell/navigation-palette.provider.ts` | 11/11 (100%) | 5/5 (100%) | 5/5 (100%) | 9/9 (100%) |
| `web/src/app/shell/not-found.ts` | 15/15 (100%) | 8/9 (88.89%) | 1/1 (100%) | 7/7 (100%) |
| `web/src/app/shell/shell-tokens.ts` | 3/3 (100%) | N/A (0/0) | 1/1 (100%) | 3/3 (100%) |
| `web/src/app/shell/shell.ts` | 38/38 (100%) | 22/22 (100%) | 10/10 (100%) | 31/31 (100%) |
| `web/src/app/shell/shortcuts-dialog.ts` | 29/29 (100%) | 10/11 (90.91%) | 1/1 (100%) | 15/15 (100%) |
| `web/src/app/shell/theme.service.ts` | 20/20 (100%) | 9/9 (100%) | 5/5 (100%) | 17/17 (100%) |
| **Aggregate (35 files, summed counters)** | **289/289 (100%)** | **157/164 (95.73%)** | **75/75 (100%)** | **221/221 (100%)** |

Aggregate excludes only the non-executable files listed under "Exact included files"; no scoped executable file is unmeasured. Diagnostic only: the run deselected the failing TW-1 test.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `app.ts:12`, `capture-placeholder.ts:20`, `command-palette.ts:22`, `not-found.ts:32`, `shortcuts-dialog.ts:53`, `coming-soon.ts:32` (one `if` branch each, on the `export class` line) | None: branches generated by the Angular compiler for component metadata (dev-mode/class-metadata guards), mapped to the class declaration | Non-material; not reachable from tests in a meaningful way |
| `coming-soon.ts:33` (`snapshot.title ?? ''` fallback) | Placeholder page of a route without `title` | Non-material: every placeholder routes file sets a title (asserted for all 18 sections in app.spec) |
| Before the new tests: `command-palette.ts:68,71,91-92` (no rows / no providers), `shell-tokens.ts:37` (root default), `shell.ts:102` (link click on wide screen), `hotkeys.ts:56` (space without `code`) | Empty-state and edge behavior of palette, drawer, hotkeys | Covered by the new tests `shows the empty state without providers ...`, `keeps the side navigation open ...`, extended `maps Q and Ctrl+Shift+Space to capture` |
| `shell.ts:85` palette opened with the real route-level providers | AC3 palette contents via `PALETTE_PROVIDERS` | Line is executed, but the behavior is wrong: TW-1 (failing test kept) |

## Unable to test

- Real-browser behavior: the 900 px media query in a real viewport, and that `theme-init.js` prevents a theme flash before Angular boots. jsdom has no layout/paint; no Playwright run is part of this task. The script logic itself is tested by running the real file. Follow-up only if the parent wants browser evidence (a Playwright check, outside `.taskcheck`).
- `features/README.md` listing every routes file: the unit-test build has no file-system access, so this was checked manually (all 18 files listed). No follow-up needed.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. TW-1 (High): the command palette opened by the shell resolves `PALETTE_PROVIDERS` from the root injector, so the route-level navigation and search providers never appear and the palette is always empty in the real app. The regression test in `web/src/app/app.spec.ts` fails until this is fixed; `cd web && npm run check` and `.taskcheck` fail on that test only. Coverage was not verified: the standard coverage command failed, and the diagnostic numbers (100% statements/lines/functions, 95.73% branches over 35 files) skip the failing test and must be re-measured after the fix.
