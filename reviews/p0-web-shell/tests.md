# Test Report

Date: 2026-10-20

Task: p0-web-shell - Application shell: navigation, top bar, palette skeleton, placeholders

Scope: task commits `d600444a71bf87e29e298e4ce64598d70aa8cd22..a999e943a3b8905c58fbc9ae3aeb5bfafc1f69d3` on branch `main`. Code commits: `307e57f` (implementation), `ce2c6f1` (first test-writer tests and report) and `a999e94` (fixes for TW-1/CR-1, CR-2 and CR-3, see `reviews/p0-web-shell/fixes.md`). The parent worker supplied the base and the range. The other commits in the range only change `tasks/` (claim, assume, verify and heartbeat commits, some from other agents' tasks) and `reviews/`. The scope also includes the uncommitted test changes listed below. There are no uncommitted production changes. This is a re-test after the fixes. It replaces the earlier report, which described the pre-fix revision `ca6af09` (now `307e57f`).

Requirements:
- `tasks/p0-web-shell.md`: acceptance criteria and recorded assumptions, including the corrected hotkey rule (every shortcut except Ctrl+K is ignored while a dialog is open)
- `reviews/p0-web-shell/fixes.md` (fixes to verify) and `reviews/p0-web-shell/review.md` (CR-1..CR-3)
- `SPEC.md` §9.1 (cited by the task), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- Existing test conventions in `web/src/app/**/*.spec.ts` (Vitest via `@angular/build:unit-test`, jsdom, zoneless TestBed, `RouterTestingHarness`)

## Test files changed

This run (uncommitted, on top of HEAD `a999e94`):

- `web/src/app/app.spec.ts`: 1 test added
- `web/src/app/shell/shell.spec.ts`: 1 test added in a new `describe`
- `web/src/app/features/capture/capture.providers.spec.ts`: 1 test added

Earlier test changes are already committed (`ce2c6f1` and the fixer's `a999e94`): `app.spec.ts`, `shell/shell.spec.ts`, `shell/command-palette.spec.ts`, `shell/hotkeys.spec.ts`, `shell/theme-init.spec.ts`, `shell/pages.spec.ts` and `features/capture/capture.providers.spec.ts`.

No existing test was removed, weakened or skipped. The TW-1 regression test in `app.spec.ts` was not changed.

## Tests added or updated

Count: 3 new tests in this run. The web suite went from 121 tests (after the fixer) to 124.

- `app.spec.ts` > `App` > `opens the shortcut overview and the capture dialog with the shell route providers`. Uses the real `app.routes.ts`. It presses `?` and checks that the `ShortcutsDialog` component injector resolves `PALETTE_PROVIDERS` to the shell route's `[NavigationPaletteProvider]`, not to the root default `[]`. It then closes the dialog, presses `Q`, and checks the same for the lazily opened `CapturePlaceholder`. This guards the CR-2 fix and the `injector: this.injector` change for the shortcuts dialog in `shell.ts`.
- `capture.providers.spec.ts` > `opens the dialog with the injector that provides CAPTURE_DIALOG`. It creates a child `EnvironmentInjector` with `provideCapture()` and a token that exists only there, opens `CAPTURE_DIALOG` from that injector, and checks that the dialog component can inject the token. This is a unit-level regression guard for CR-2 (`openCapturePlaceholder` passes its injector to `MatDialog.open`).
- `shell.spec.ts` > `Shell with route-level palette providers` > `shows the rows of every provider registered on the shell route`. It loads `Shell` on a route that registers `provideNavigationPalette()` plus a second fictitious `PALETTE_PROVIDERS` (multi) provider, the same way `provideSearch()` will. It clicks the search field and checks that the palette shows both groups (`Go to`, `Notes`). This covers the documented plug-in point for the search feature. The earlier shell and palette specs registered providers only at the TestBed root, which is how TW-1 slipped through.

The fixer's test was also verified: `shell.spec.ts` > `does not open capture with Ctrl+Shift+Space while a dialog is open` (CR-3).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Left navigation with all SPEC §9.1 sections incl. Logbook and Trash | shell.spec `renders a link for every navigation section, Logbook and Trash included`; app.spec `lazy-loads a coming soon page for %s` (18 cases, active link + aria-current) | `cd web && npm run check` (test:ci) | Pass |
| AC1 Responsive: drawer below 900 px | shell.spec `shows the navigation side by side on wide screens`, `collapses the navigation to a drawer below 900 px`, `switches between side navigation and drawer when the width changes`, `keeps the side navigation open after following a link on wide screens`. BreakpointObserver is stubbed. | `cd web && npm run check` (test:ci) | Pass in jsdom with a stubbed breakpoint. The real media query in a browser was not run. |
| AC2 `app.routes.ts` lazy-loads every `features/<feature>/<feature>.routes.ts`; each renders 'coming soon' | app.spec `lazy-loads a coming soon page for %s` (18), `renders the shell with the dashboard placeholder at /`, `shows a not found page for unknown URLs`; pages.spec `NotFound explains the dead end ...` | `cd web && npm run check` (test:ci) | Pass |
| AC2 Routes file names listed in `features/README.md` | Not automated: the jsdom test build has no file-system access. Checked by reading `web/src/app/features/README.md` against `git diff --name-only d600444..HEAD`: all 18 routes files are listed. | manual read | Pass (manual) |
| AC3 Search field and `Ctrl+K` open the palette (also in inputs, also over another dialog, only once) | shell.spec `opens the command palette with Ctrl+K and from the search field, only once`, `opens the palette with Ctrl+K while typing in an input and while another dialog is open`; hotkeys.spec | `cd web && npm run check` (test:ci) | Pass |
| AC3 Capture '+' button, `Q` / `Ctrl+Shift+Space`, ignored while typing; all shortcuts except Ctrl+K ignored while a dialog is open (corrected assumption, CR-3) | shell.spec `opens capture with Q, Ctrl+Shift+Space and the + button`, `ignores Q and ? while typing in an input`, `opens capture with Ctrl+Shift+Space while typing in an input`, `does not open capture while a dialog is open`, `does not open capture with Ctrl+Shift+Space while a dialog is open` (fixer); hotkeys.spec | `cd web && npm run check` (test:ci) | Pass |
| AC3 `?` shortcut overview | shell.spec `opens the shortcut overview with ?`, `does not open the shortcut overview twice`; pages.spec `ShortcutsDialog lists every shell shortcut ...` | `cd web && npm run check` (test:ci) | Pass |
| AC3 `CAPTURE_DIALOG` placeholder implementation, wired via `app.routes.ts`, dialog opened with the route injector (CR-2) | capture.providers.spec (3 tests, incl. new `opens the dialog with the injector that provides CAPTURE_DIALOG`); app.spec `wires the capture placeholder into the shell: Q opens its dialog`, new `opens the shortcut overview and the capture dialog with the shell route providers` | `cd web && npm run check` (test:ci); focused verbose run (see below) | Pass |
| AC3 Palette contents via `PALETTE_PROVIDERS` with a placeholder implementation, in the real app wiring (TW-1 / CR-1) | app.spec `wires the navigation commands into the palette: Ctrl+K, type, Enter navigates` (TW-1 regression test, unchanged); shell.spec new `shows the rows of every provider registered on the shell route`; command-palette.spec (7) | `cd web && npm run check` (test:ci); focused verbose run | Pass. The TW-1 test failed on `ca6af09` and now passes. |
| AC4 Theme toggle light/dark/system, `ThemeService` exposes it | theme.service.spec (5); shell.spec `switches the theme from the theme menu`, `marks the current theme in the theme menu` | `cd web && npm run check` (test:ci) | Pass |
| AC4 Remembered in localStorage, read/write in try/catch | theme.service.spec `keeps working when localStorage throws`; theme-init.spec `falls back to system without throwing when localStorage is unavailable` | `cd web && npm run check` (test:ci) | Pass |
| AC4 Applied before first paint (`public/theme-init.js`, loaded from `index.html`) | theme-init.spec (10 tests that run the real script) | `cd web && npm run check` (test:ci) | Pass for the script's logic. That `web/src/index.html` loads it with a synchronous `<script src="theme-init.js">` in `<head>` was checked by reading the file. No-flash in a real browser: Not run (no browser run in this task). |
| AC5 Vitest tests cover navigation rendering, hotkeys ignored in inputs, theme switching | see rows above | `cd web && npm run check` (test:ci) | Pass |
| AC5 `cd web && npm run check` passes | whole web check | `cd web && npm run check` (run as `npm --prefix web run check`) | Pass (exit 0): api:check up to date, lint clean, test:ci 13 files / 124 tests passed, `ng build` success, initial total 451.66 kB |

## Focused test execution

Command:

`npm --prefix web run test:ci -- --reporters=verbose --include src/app/app.spec.ts --include src/app/shell/shell.spec.ts --include src/app/features/capture/capture.providers.spec.ts`

Result:

Pass: 3 files, 44 tests. The verbose output shows these as passed: the TW-1 test `wires the navigation commands into the palette: Ctrl+K, type, Enter navigates`, the fixer's `does not open capture with Ctrl+Shift+Space while a dialog is open`, and the 3 new tests.

Not run: a mutation check of the 3 new tests against the pre-fix `shell.ts` / `capture-placeholder.ts` from `307e57f`. It needs a scratch copy of `web/`, and the copy commands (`rsync`/`cp -r`) required approval that this session does not have. Production files in the clone must not be changed. By reading the code, each new assertion depends on the dialog injector chaining to the route injector, which the pre-fix code did not do. Pre-fix, the dialogs would see the root default `PALETTE_PROVIDERS = []`, the child-only token would be `null`, and the palette would show no groups. So the new tests would be expected to fail on the pre-fix revision. The TW-1 test is the executed proof: it failed on `ca6af09` in the earlier run and passes now.

## Full test execution

Command:

`cd web && npm run check` (run as `npm --prefix web run check`, 2026-10-20 ~11:53:55 +02:00)

Result:

Pass (exit 0). `api:check`: `src/app/api/generated is up to date.` `lint`: `All files pass linting.` `test:ci`: 13 files, 124 tests passed. `ng build`: success, initial total 451.66 kB.

Formatting of the changed spec files: `npx --prefix web prettier --check web/src/app/app.spec.ts web/src/app/shell/shell.spec.ts web/src/app/features/capture/capture.providers.spec.ts` -> all use Prettier style. Prettier was run only on these 3 spec files, never on `src/app/api/generated`.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` ran 2026-10-20 11:54:36 -> 11:54:51 +02:00, exit 0, output ended with `== all checks passed`. The web step showed 13 files / 124 tests passed.

## Bugs found

None new in this run.

Previous findings and their disposition after the fixes in `a999e94`:

### Command palette never sees the route-level PALETTE_PROVIDERS (resolved)

Finding ID:

`TW-1` (same defect as CR-1)

Test:

`web/src/app/app.spec.ts` > `App` > `wires the navigation commands into the palette: Ctrl+K, type, Enter navigates`

Production source:

`web/src/app/shell/shell.ts` (`openPalette()`, now `injector: this.injector`)

Requirement:

AC3: palette contents are provided via `PALETTE_PROVIDERS`; the shell adds 'Go to <section>' commands; `provideSearch()` on the shell route adds more.

Expected behavior:

With the real routes, Ctrl+K opens a palette that lists the 18 'Go to' commands, and `trash` + Enter navigates to `/trash`.

Actual behavior:

On `a999e94` it works as expected and the test passes. On `ca6af09` the palette was always empty.

Severity:

High

Fix required:

`No` — fixed in `a999e94` and verified by the unchanged regression test plus the new route-level test in `shell.spec.ts`. Disposition: fixed.

CR-2 (capture placeholder dialog opened without the route injector) is fixed and verified by the 2 new tests listed above. CR-3 (hotkey rule while a dialog is open) is verified by the fixer's test, which passes. The behavior matches the corrected assumption in the task file.

## Unable to test

- Real-browser behavior: the 900 px media query in a real viewport, and whether `theme-init.js` prevents a theme flash before Angular boots. jsdom has no layout or paint, and no Playwright run is part of this task. The script's logic is tested by running the real file. This needs follow-up only if the parent wants browser evidence (a Playwright check outside `.taskcheck`).
- `features/README.md` listing every routes file: checked by hand, as described above. No follow-up needed.
- The pre-fix mutation run of the 3 new tests: not run (approval needed for a scratch copy; see "Focused test execution"). No follow-up needed, because the TW-1 regression test already demonstrated the failure on the pre-fix code.

## Summary

Tests added and all validation passed. The fixes for TW-1/CR-1, CR-2 and CR-3 are verified:
- the TW-1 regression test in `app.spec.ts` passes;
- 3 new tests guard the route-injector wiring for the palette, the shortcut overview and the capture placeholder;
- `cd web && npm run check`, `npm run coverage` and `.taskcheck` pass on HEAD `a999e94` plus the new uncommitted tests.

No production defects remain open. Task-scoped coverage: 100% statements (290/290), lines (222/222) and functions (75/75), and 95.73% branches (157/164), over 35 files. The 7 uncovered branches are non-material compiler-generated or fallback branches.

## Coverage

Status: verified. The run is fresh, exited 0, and measured the final revision `a999e94` plus the uncommitted tests from this run.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured with a separate tool. All 35 scoped executable files are new in this task except `app.ts` and `app.routes.ts`. Every executable line of every scoped file is covered (222/222), so no changed executable line is uncovered. That includes the lines changed by the fix commit `a999e94` in `shell.ts` (32/32 lines) and `capture-placeholder.ts` (7/7 lines). Changed-branch coverage is not inferred from this.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-web-shell`. Base `d600444a71bf87e29e298e4ce64598d70aa8cd22` (supplied by the parent). Measured HEAD `a999e943a3b8905c58fbc9ae3aeb5bfafc1f69d3`. Task-owned code commits: `307e57f`, `ce2c6f1`, `a999e94`. The file scope comes from `git diff --name-only d600444..HEAD -- web/src web/public`, minus `*.spec.ts`. It matches the task `touches`, plus `web/public/theme-init.js` and `web/src/index.html` per the recorded assumption.
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD `a999e94`, with no uncommitted production changes. Uncommitted test files (sha256 prefixes): `web/src/app/app.spec.ts` e6dd160bf9c6401f, `web/src/app/shell/shell.spec.ts` 7994384afdc02d1a, `web/src/app/features/capture/capture.providers.spec.ts` 7fb8989386df9391. Fixed production files for reference: `shell/shell.ts` 82a3811e887a79a2, `features/capture/capture-placeholder.ts` c9388efffcf6612f. The hashes and `git status` were taken at 11:54:08 (before coverage) and again after `.taskcheck`, and they were identical.
- Exact included files (35): `web/public/theme-init.js`, `web/src/app/app.ts`, `web/src/app/app.routes.ts`, the 23 `.ts` files under `web/src/app/features/**` that the task changed, and `web/src/app/shell/{coming-soon,command-palette,hotkeys,nav-sections,navigation-palette.provider,not-found,shell-tokens,shell,shortcuts-dialog,theme.service}.ts`. Excluded files:
  - `app.html`, `shell/shell.html` and `shell/command-palette.html`: templates compiled into the component files and counted there;
  - `app.scss`, `shell/shell.scss` and `shell/command-palette.scss`: styles;
  - `features/README.md`: documentation;
  - `web/src/index.html`: static markup;
  - `web/src/locale/messages.xlf`: generated i18n.

  No files were renamed or deleted, and no scoped file is missing from the report.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` via `@angular/build:unit-test`, working directory `web/` (through `npm --prefix web`). Two runs:
  - (1) `npm run coverage`: all specs, a repository-wide report; only the totals are recorded here.
  - (2) `npm run coverage -- --coverage-include 'src/app/shell/**/*.ts' --coverage-include 'src/app/features/**/*.ts' --coverage-include 'src/app/app.ts' --coverage-include 'src/app/app.routes.ts' --coverage-include 'public/theme-init.js'`: all 13 spec files, 124 tests, nothing deselected. `--coverage-include` only restricts the report to the task scope and makes unexecuted scoped files visible.

  No configuration was changed.
- Run start/completion time and exit status: (1) ~11:54:15 -> 11:54:22 +02:00, exit 0, 124 passed. Its repository-wide totals were 512/656 statements, 237/376 branches, 113/137 functions and 394/527 lines. These are not task metrics. (2) 11:54:25 -> 11:54:29 +02:00, exit 0, 124 passed.
- Generated report paths and report hashes/run identifier (run 2, Git-ignored): `web/coverage/web/coverage-summary.json` sha256 a5eb1644e7414822c1251495523b47803ebd04a4866907991aeab1f0ef9d8a11; `web/coverage/web/coverage-final.json` sha256 eff71ece669de8b46fdac4c11099acaa046bd67344e031d7bca61c5a0958401f; `web/coverage/web/lcov.info` sha256 2f02ea430ddd531183ac026694d2b8cfe6ed44950e26c4d8ec7f432afe85948f.
- Evidence outputs are fresh and inputs stayed unchanged: all report files have mtime 2026-10-20 11:54:29, which is run 2's completion time. The summary lists exactly the 35 scoped files. The input hashes were the same before and after.
- Final run after latest test additions and production fixes: yes. The run covers the fixer's revision `a999e94` and all 3 tests added in this run.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Run (2).

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
| `web/src/app/shell/shell.ts` | 39/39 (100%) | 22/22 (100%) | 10/10 (100%) | 32/32 (100%) |
| `web/src/app/shell/shortcuts-dialog.ts` | 29/29 (100%) | 10/11 (90.91%) | 1/1 (100%) | 15/15 (100%) |
| `web/src/app/shell/theme.service.ts` | 20/20 (100%) | 9/9 (100%) | 5/5 (100%) | 17/17 (100%) |
| **Aggregate (35 files, summed counters)** | **290/290 (100%)** | **157/164 (95.73%)** | **75/75 (100%)** | **222/222 (100%)** |

The aggregate leaves out only the non-executable files named under "Exact included files". No scoped executable file is unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `app.ts:12`, `capture-placeholder.ts:20`, `command-palette.ts:22`, `not-found.ts:32`, `shortcuts-dialog.ts:53`, `coming-soon.ts:32` (one branch each, mapped to the `export class` line) | None. These branches are generated by the Angular compiler for component metadata (dev-mode/class-metadata guards). | Non-material: tests cannot reach them in a meaningful way |
| `coming-soon.ts:33` (`snapshot.title ?? ''` fallback) | Placeholder page for a route without a `title` | Non-material: every placeholder routes file sets a title (asserted for all 18 sections in app.spec) |
| `shell.ts` dialog `injector` config (lines changed in `a999e94`) | AC3: palette and dialogs see the route-level providers (TW-1/CR-1, CR-2) | Covered by executed lines and by behavior: TW-1 regression test, plus new tests in app.spec and shell.spec |
| `capture-placeholder.ts:24` `openCapturePlaceholder` with `injector` | CR-2 | Covered: new capture.providers.spec test and new app.spec test |
