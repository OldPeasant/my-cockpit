# Test Report

Date: 2026-10-20

Task: p0-web-deps - Web dependencies, theme and i18n baseline

Scope: base `fda2f0926bf562603f7243a8ceb9abd40c8163c4` .. HEAD `ca3afc3a299e482eb20ec0438f6ac0723c383d69` on `main`
(task commits `4b778d3`, `ca3afc3`, supplied by the parent worker; `git diff fda2f09..HEAD -- web docs`).
Working tree before this run: clean. Uncommitted inputs at measurement time: only the test files listed below.

Requirements:
- `tasks/p0-web-deps.md` acceptance criteria and its recorded assumptions
- `docs/ARCHITECTURE.md` (stack table, "Coverage", conventions: externalized i18n strings)
- `docs/QUALITY.md` (report contract)
- `AGENTS.md` project notes

## Test files changed

- `web/src/app/app.spec.ts` (3 tests added; existing 2 tests unchanged; Prettier-formatted the existing `configureTestingModule` chain)
- `web/src/app/app.i18n.spec.ts` (new)

## Tests added or updated

Count: 5

- `App > renders the English source title without the $localize message id metadata` - exact `h1` text `My Cockpit` (the `:@@app.title:` metablock is stripped, so `$localize` is the real runtime)
- `App > renders the i18n-marked scaffold text in the English source locale` - exact `<p i18n="@@app.scaffold">` text
- `App > provides the $localize runtime global (polyfill @angular/localize/init)` - `$localize` is a function in the test runtime and returns the source text for a message with an id
- `App i18n > translates the title through $localize using the app.title message id` - after `loadTranslations({'app.title': 'Mein Cockpit', ...})` the `h1` renders the translation, which proves the title goes through `$localize` with id `app.title` (a static string would fail)
- `App i18n > translates the i18n template text using the app.scaffold message id` - the `i18n` template text renders the loaded translation for id `app.scaffold`

The translation tests sit in their own spec file because Angular caches a component's i18n template
constants on its first render. Translations are loaded before the first render and cleared in `afterEach`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: package.json adds the listed libraries; `npm ci` works from the lockfile | None (dependency manifest; no runtime behavior to unit-test). `npm ls --depth=0` reported no missing/invalid packages against the installed tree | `cd web && npm ls --depth=0` | Not testable as a unit test. `npm ci` itself not run: it reinstalls dependencies, which the test-writer may not do. The parent should run it for the AC1 evidence |
| AC2: Material 3 tokens on `:root`, light + dark via `prefers-color-scheme` and `data-theme` override | No unit test (see "Unable to test"). Checked in the compiled build output `web/dist/web/browser/styles-LD4DV62L.css`: `:root{color-scheme:light dark;--mat-sys-*: light-dark(...)...}`, `--app-bg: var(--mat-sys-surface)`, `--app-space-4: 24px`, `--mat-sys-body-medium: 400 .875rem / 1.25rem Roboto, ...`, `:root[data-theme=light]{color-scheme:light}`, `:root[data-theme=dark]{color-scheme:dark}`, `body{...background:var(--app-bg);color:var(--app-fg);font:var(--mat-sys-body-medium)}` | `cd web && npm run check` (ng build), then `grep -oE` on the built `styles-*.css` | Pass (build-output inspection, not an automated test) |
| AC2: no hard-coded colors in `app.*` | No unit test. Literal-color scan of `web/src/app/app.ts`, `app.html`, `app.scss` (hex, `rgb()/rgba()`, `hsl()/hsla()`, common named colors): no match. `app.scss` uses only `--app-*` vars | `grep -nEi '#[0-9a-f]{3,8}\b\|rgba?\(\|hsla?\(\|\b(white\|black\|red\|blue\|green\|gray\|grey)\b' web/src/app/app.{ts,html,scss}` | Pass (manual scan) |
| AC3: `@angular/localize` set up, `$localize` usable, English source locale | `renders the English source title without the $localize message id metadata`, `provides the $localize runtime global (polyfill @angular/localize/init)`, `translates the title through $localize using the app.title message id` | `cd web && npm run test:ci` | Pass |
| AC3: `i18n` usable in templates | `renders the i18n-marked scaffold text in the English source locale`, `translates the i18n template text using the app.scaffold message id` | `cd web && npm run test:ci` | Pass |
| AC3: English source locale (`angular.json` `i18n.sourceLocale: en`, `src/locale/messages.xlf` `source-language="en"`) | Not covered by a unit test. Confirmed in the diff | inspection of `git diff fda2f09..HEAD -- web/angular.json web/src/locale/messages.xlf` | Not testable in Vitest (build config) |
| AC4: `npm run check` passes | Whole web suite, including the new tests | `cd web && npm run check` | Pass (lint "All files pass linting.", 2 test files / 7 tests passed, `ng build` succeeded) |
| AC4: ARCHITECTURE.md stack table updated | Not testable (documentation). Diff shows the updated Web row and install note | `git diff fda2f09..HEAD -- docs/ARCHITECTURE.md` | Not testable |

## Focused test execution

Command:

`cd web && npm run test:ci`

Result:

Pass - 2 test files, 7 tests passed (baseline before changes: 1 file, 2 tests passed).

## Full test execution

Command:

`cd web && npm run check` (= `npm run lint && npm run test:ci && ng build`)

Result:

Pass - lint clean, 7/7 tests passed, production build succeeded (`styles-LD4DV62L.css` 9.59 kB).
Also: `npx prettier --check` on the two spec files passes after formatting.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` was refused by this session's command permissions (approval required).
The web part of it (`npm run check`) ran and passed. The parent worker must run `.taskcheck` itself.
Server and Android are untouched by this task.

## Bugs found

None. No production defect was found.

## Coverage

Status: verified (for the instrumentable TypeScript/template files in scope; see exclusions).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured for the two instrumented files by intersecting the changed lines of
`git diff fda2f09..HEAD` with the source-mapped `DA` records in `lcov.info`: `web/src/app/app.ts` line 11
(hits 6) and `web/src/app/app.html` line 3 (hits 6) -> 2/2 changed executable lines covered (100%).
Changed-branch coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-web-deps`; base `fda2f0926bf562603f7243a8ceb9abd40c8163c4`; HEAD `ca3afc3a299e482eb20ec0438f6ac0723c383d69`; task commits `4b778d3`, `ca3afc3` as supplied by the parent worker and matching the task file's assumptions; file scope from `git diff --name-only fda2f09..HEAD -- web/src`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `ca3afc3` + uncommitted test files, sha256 (paths relative to `web/`):
  - `src/app/app.ts` `a679d1eb28f95978da745c27f05ac7ab0f0557115a0ddd5e26f5f21a2c722ce9`
  - `src/app/app.html` `2b8bb2d1debe58746fbeee00acdf8c94268e1fb85ee1741b4165d6efb471718d`
  - `src/app/app.scss` `8409e873f48e0c65c6a258eb973b758b6f1d5f2b70511430c457dfa615c24107`
  - `src/main.ts` `739f0bb61f0fc5da35837c512b3dd6e6bdfc4c410d8cdbef925014e06bbe06fc`
  - `src/app/app.spec.ts` `fb5ceba3a4ed63bf60b9511cf4b47db7b6bd9c85c9a014a18522aeb2e2251e20`
  - `src/app/app.i18n.spec.ts` `845c8f8a124804782d6eaee8b89c62f33d9b69dba99d0a73e0fa9612d90209d1`
- Exact included files: `web/src/app/app.ts`, `web/src/app/app.html`. Excluded: `web/src/main.ts` (bootstrap entry point, not imported by any spec, so v8 has no data for it; one-line formatting change plus the `@angular/localize` types reference: no instrumentable behavior), `web/src/app/app.scss`, `web/src/styles.scss`, `web/src/theme/_theme.scss` (Sass, not instrumentable by v8; checked through the build output above), `web/src/locale/messages.xlf` (generated i18n source), `web/angular.json`, `web/package.json`, `web/package-lock.json`, `web/tsconfig.app.json`, `web/tsconfig.spec.json` (configuration), `docs/ARCHITECTURE.md` (documentation). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` 5.0.5 via `@angular/build:unit-test`; `npm run coverage`; working directory `web/`; all specs (`app.spec.ts`, `app.i18n.spec.ts`).
- Run start/completion time and exit status: 2026-10-20T08:35:42Z - 08:35:44Z (UTC); 2 files / 7 tests passed, coverage summary printed (exit 0).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 `2a3b1113652bc5af7526719d9f87b00940ca02b6fd3c544a585eb7a3550dd1a1`), `web/coverage/web/coverage-summary.json`, `web/coverage/web/lcov.info`, `web/coverage/web/lcov-report/` (all Git-ignored).
- Evidence outputs are fresh and inputs stayed unchanged: report files were rewritten at 10:35 local (08:35Z) by this run; the inputs were hashed before the run and not edited during or after it.
- Final run after latest test additions and production fixes: yes for the test additions. No production fixes were made. Any later production change makes these numbers stale.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/app.ts` | 6/6 (100%) | 8/9 (88.88%) | 1/1 (100%) | 2/2 (100%) |
| `web/src/app/app.html` | 7/7 (100%) | N/A (0/0) | N/A (0/0) | 4/4 (100%) |
| Aggregate (these 2 files only) | 13/13 (100%) | 8/9 (88.88%) | 1/1 (100%) | 6/6 (100%) |

The aggregate excludes `web/src/main.ts` (unmeasured, see above) and the Sass/config/doc files (not instrumentable).
These numbers do not cover the theme. The theme was checked only through the compiled CSS.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/app.ts:10` branch 1 (`BRDA:10,1,0,0`, an `if` source-mapped onto the `export class App` line) | None. It is the Angular-compiler-generated factory path (`ɵfac` for subclass/inherited construction), not task code | Left uncovered: generated code, not reachable through the public component API |
| `web/src/main.ts` (no data) | Bootstrap with `appConfig`. The task only reformatted it and added a types reference | Left unmeasured: entry point. `ng build` in `npm run check` compiles it |
| `web/src/theme/_theme.scss`, `web/src/styles.scss`, `web/src/app/app.scss` | AC2 tokens and light/dark overrides | No automated test, see "Unable to test". Checked in the build output |

## Unable to test

- **Compiled global CSS (theme tokens on `:root`, `color-scheme`, `data-theme` overrides) and "no literal colors in `src/app`" as automated Vitest tests.** Unit tests are bundled by `@angular/build:unit-test` against `tsconfig.spec.json` (types: `vitest/globals`, `@angular/localize`). Global styles are not injected into the jsdom test page, and jsdom does not resolve `light-dark()` or `color-scheme`. Reading or compiling the SCSS from a spec would need `node:fs`/`sass` imports. The project has no `@types/node`, so those imports fail type-checking unless we add a new ambient declaration file or a dependency. Neither fits the current conventions or the test-writer's permissions. The behavior was checked by inspecting the build output and scanning the sources (see the table above). Follow-up (optional): if the project wants this as a regression test, a small Playwright check is the natural place, in the existing slow non-check suite: set `<html data-theme="dark">`, emulate `prefers-color-scheme`, and assert the computed `--mat-sys-surface` and `color-scheme`. That belongs to a task that sets up the E2E suite.
- **`npm ci` from the lockfile** (AC1): not run because it reinstalls `node_modules`. The parent worker should run `cd web && npm ci` and record it.
- **`.taskcheck`**: not run because this session lacked permission (see above).
- **Runtime light/dark switching in a real browser**: needs a browser. Not run.

## Summary

Tests added and all validation that ran passed (`npm run test:ci`, `npm run check`, `npm run coverage`). No production defects were found. `$localize`/`i18n` behavior is covered by tests that would fail if the strings were not localized. The theme acceptance criterion was checked only in the build output, not by an automated test. The parent worker must still run `.taskcheck` and `npm ci`.
