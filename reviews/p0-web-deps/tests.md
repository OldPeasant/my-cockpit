# Test Report

Date: 2026-10-20

Task: p0-web-deps - Web dependencies, theme and i18n baseline

Scope: final task revision HEAD `bdb91418024288742e38bb73feac34c115fe6518` ("p0-web-deps: document Vitest
isolation for web unit tests (CR-4)") on `main`. Base `3ecd166e11427f923055712ea7d90cb0aa3f61ff`, as supplied
by the parent worker. Diff: `git diff 3ecd166..bdb9141 -- web docs`.

Task commits (from the parent, matching `git log --oneline 3ecd166..bdb9141`): `d492875`, `a9d3055`,
`0456442`, `74d6136`, `4ca96bd`, `d8e43bf`, `bdb9141`. `db4b907` only adds review evidence under `reviews/`.

This report replaces the one written for `c5af6ea` / its rebased equivalent `eb2681f`. The `web/` tree is
byte-identical in all of them: `git rev-parse c5af6ea:web eb2681f:web d8e43bf:web bdb9141:web` returns
`42af3bd42eb3450918250194706b02e368e30b60` four times. The only task change since is `bdb9141`. It adds three
lines to `docs/ARCHITECTURE.md` "Testing" about Vitest `isolate: true` (fixer, CR-4) plus
`reviews/p0-web-deps/fixes.md`. Neither is a test input. `npm run check` and `npm run coverage` were still re-run on
`bdb9141`, as the parent requested.

Uncommitted inputs: none. `git status --short` was empty before and after the runs, and HEAD was `bdb9141`
both times.

Requirements:
- `tasks/p0-web-deps.md` acceptance criteria and its recorded assumptions
- `reviews/p0-web-deps/review.md` findings CR-3 (i18n spec robustness) and CR-4 (document isolation)
- `docs/ARCHITECTURE.md` (stack table, "Testing", "Coverage", conventions: externalized i18n strings)
- `docs/QUALITY.md` (report contract)
- `AGENTS.md` project notes

## Test files changed

- None in this run. The task's test files at HEAD are `web/src/app/app.spec.ts` (3 tests added by the task next to the 2 scaffold tests) and `web/src/app/app.i18n.spec.ts` (new, 2 tests). They were re-run and assessed, not modified.

## Tests added or updated

Count: 0

The existing tests are adequate for the testable acceptance criteria (see the table below):
- `App > renders the English source title without the $localize message id metadata`
- `App > renders the i18n-marked scaffold text in the English source locale`
- `App > provides the $localize runtime global (polyfill @angular/localize/init)`
- `App i18n > translates the title through $localize using the app.title message id`
- `App i18n > translates the i18n template text using the app.scaffold message id`

The translation tests would fail if the title were a static string or the paragraph lacked `i18n="@@app.scaffold"`.
The remaining criteria (dependency manifest, Sass theme, documentation) have no runtime behavior that the
Vitest/jsdom setup can test (see "Unable to test"). The docs-only change in `bdb9141` adds no testable behavior.

## i18n spec robustness (CR-3), carried over

These runs were done in the earlier test-writer pass on `11176b0`. Its `web/` inputs differ from `bdb9141` only by
a reworded comment in `app.i18n.spec.ts`. They were not repeated in this pass. Method: temporary Vitest runner
configs (`maxWorkers: 1`, `fileParallelism: false`, a custom sequencer forcing the spec order), deleted afterwards.

| Run (all from `web/`) | Isolation | Forced order | Result |
|---|---|---|---|
| `npx ng test --watch=false --runner-config=tw-probe-i18n-last.config.mjs --reporters=verbose` | `true` (angular.json) | `app.spec.ts` -> `app.i18n.spec.ts` | Pass, 2 files / 7 tests |
| `npx ng test --watch=false --runner-config=tw-probe-i18n-first.config.mjs --reporters=verbose` | `true` (angular.json) | `app.i18n.spec.ts` -> `app.spec.ts` | Pass, 2 files / 7 tests |
| same, plus `--isolate=false` | `false` (negative control) | `app.spec.ts` -> `app.i18n.spec.ts` | Pass, 2 files / 7 tests |
| same, plus `--isolate=false` | `false` (negative control) | `app.i18n.spec.ts` -> `app.spec.ts` | Pass, 2 files / 7 tests |

A temporary probe spec also showed that after `TestBed.resetTestingModule()` and loading translations, the
template `i18n` text re-rendered translated (`h1` = `Mein Cockpit`, `p` = `Gerüst.`). So it was not stuck on
the first render's English. Conclusion: the spec passes in a single worker in both orders, with and without
isolation. `isolate: true` is harmless.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: package.json adds the listed libraries; `npm ci` works from the lockfile | None (dependency manifest, no runtime behavior). In the earlier pass, `npm ls --depth=0` listed every required package with no missing or invalid entries. `web/package*.json` is unchanged since | `npm --prefix web ls --depth=0` (earlier pass, same `web/` tree) | Not testable as a unit test. `npm ci` not run by the test-writer, because it reinstalls dependencies. The parent must record its own `npm ci` evidence |
| AC2: Material 3 tokens on `:root`, light + dark via `prefers-color-scheme` and `data-theme` override | No automated test (see "Unable to test"). The `bdb9141` build emits the same `styles-LD4DV62L.css` (same content hash) that was inspected earlier: `:root{color-scheme:light dark`, `--app-bg: var(--mat-sys-surface)`, `:root[data-theme=light]{color-scheme:light}`, `:root[data-theme=dark]{color-scheme:dark}` | `npm --prefix web run check` (build step) | Pass (build-output inspection, not an automated test) |
| AC2: no hard-coded colors in `app.*` | No unit test. The earlier literal-color scan of `web/src/app/app.{ts,html,scss}` found no match. The files are unchanged | `grep -nEi '#[0-9a-f]{3,8}\b\|rgba?\(\|hsla?\(\|\b(white\|black\|red\|blue\|green\|gray\|grey)\b' web/src/app/app.{ts,html,scss}` (earlier pass, same tree) | Pass (manual scan) |
| AC3: `@angular/localize` set up, `$localize` usable, English source locale | `renders the English source title without the $localize message id metadata`, `provides the $localize runtime global (polyfill @angular/localize/init)`, `translates the title through $localize using the app.title message id` | `npm --prefix web run check` (its `test:ci` step) on `bdb9141` | Pass |
| AC3: `i18n` usable in templates | `renders the i18n-marked scaffold text in the English source locale`, `translates the i18n template text using the app.scaffold message id` | `npm --prefix web run check` on `bdb9141` | Pass |
| AC3: English source locale in the build config (`angular.json` `i18n.sourceLocale: en`, `messages.xlf` `source-language="en"`) | No unit test. Confirmed in the diff | `git diff 3ecd166..bdb9141 -- web/angular.json web/src/locale/messages.xlf` | Not testable in Vitest (build config) |
| AC4: `npm run check` passes | Whole web suite | `npm --prefix web run check` on `bdb9141` | Pass: lint "All files pass linting.", 2 test files / 7 tests passed, `ng build` succeeded |
| AC4: ARCHITECTURE.md stack table updated | Not testable (documentation). The task diff on `docs/ARCHITECTURE.md` (8 lines) contains the Web stack row / `npm ci` + Playwright install note and the new "Testing" isolation line | `git diff --stat 3ecd166..bdb9141 -- docs` | Not testable |
| CR-3: i18n spec independent of worker layout and spec order | Both spec files | Four single-worker runs (table above, earlier pass on same `web/` tree) | Pass |

## Focused test execution

Command:

`npm --prefix web run test:ci` as part of `npm --prefix web run check` on `bdb9141`. The test suite has only the 2 task-relevant spec files, so the focused and full web runs are the same.

Result:

Pass: 2 files, 7 tests (Vitest 5.0.5, start 10:52:19 local / 08:52:19Z, 545 ms).

## Full test execution

Command:

`npm --prefix web run check` (= `npm run lint && npm run test:ci && ng build`)

Result:

Pass on HEAD `bdb9141`, finished at 2026-10-20T08:52:20Z. Lint was clean and 2 files / 7 tests passed.
The production build succeeded (`main-EFEVWPV5.js` 208.52 kB, `styles-LD4DV62L.css` 9.59 kB, the same hashes as
the earlier pass). Server and Android are untouched by this task and were not run.

## .taskcheck

Result:

Not run: this session's command permissions refused `sh scripts/check.sh` in the earlier pass. In this pass,
compound shell commands were refused as well. Its web part (`npm run check`) ran and passed, see above.
The parent worker must run `.taskcheck`.

## Bugs found

None. No production defect was found.

Documentation note for the parent (not a production defect, no test failure): the new `docs/ARCHITECTURE.md`
line from `bdb9141` says `app.i18n.spec.ts` "needs" `isolate: true` "(Angular caches i18n template constants on
first render)". The carried-over negative controls (`--isolate=false`, both spec orders, single worker) passed,
and the probe showed that translations take effect after a TestBed reset. So in this setup the spec does not
strictly need isolation. Keeping `isolate: true` as a safeguard is reasonable. The parent/reviewer can decide
whether to soften "needs this" to something like "keeps this safe". Severity: Low (wording only).

## Coverage

Status: verified for the instrumentable TypeScript/template files in scope (see exclusions).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting the added/modified lines of the task diff with the
source-mapped `DA` records in `web/coverage/web/lcov.info` from this run. Results: `web/src/app/app.ts` line 11
(`DA:11,6`) and `web/src/app/app.html` line 3 (`DA:3,6`), so 2/2 changed executable lines are covered (100%).
`web/src/main.ts` changed lines are unmeasured, see exclusions. Changed-branch coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: task `p0-web-deps`, base `3ecd166e11427f923055712ea7d90cb0aa3f61ff`, measured HEAD `bdb91418024288742e38bb73feac34c115fe6518`. The task commits are those listed above (parent-supplied, matching `git log 3ecd166..bdb9141`). File scope: `git diff --stat 3ecd166..bdb9141 -- web docs`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `bdb9141`, `web` tree `42af3bd42eb3450918250194706b02e368e30b60`, clean working tree. Blob ids at HEAD:
  - `web/src/app/app.ts` `9d2dc11691fdccfd467d18b75086dffbf1f4ab18`
  - `web/src/app/app.html` `e3367aac3d739b8e9d3e665e93d98b1a7ad3b00b`
  - `web/src/main.ts` `dc13603859a4fee0cf1551355d1d94f70181f456`
  - `web/src/app/app.spec.ts` `5e6f52aafb7bebb610fb525bd4c58207ba0ba3b0`
  - `web/src/app/app.i18n.spec.ts` `84492783323e9cef781295a3fffd40e00a91a49f`
  - `web/angular.json` `ec7436b1cc10aa4d5ab5357bd74a12cf75dc608c`
- Exact included files: `web/src/app/app.ts`, `web/src/app/app.html`.
- Excluded files, all modified or added, none renamed or deleted:
  - `web/src/main.ts`: the bootstrap entry point. No spec imports it, so v8 has no data for it. The change is a `@angular/localize` types reference plus formatting, with no behavior to instrument. `ng build` compiles it.
  - `web/src/app/app.scss`, `web/src/styles.scss`, `web/src/theme/_theme.scss`: Sass, which v8 cannot instrument. Checked through the build output.
  - `web/src/locale/messages.xlf`: the extracted i18n source.
  - `web/angular.json`, `web/package.json`, `web/package-lock.json`, `web/tsconfig.app.json`, `web/tsconfig.spec.json`: configuration.
  - `docs/ARCHITECTURE.md`: documentation.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + v8 coverage via `@angular/build:unit-test` (`isolate: true`). Command: `npm --prefix web run coverage` (= `ng test --watch=false --coverage` with text-summary/json-summary/json/lcov reporters). Working directory: `web/`. Test selection: all specs (`app.spec.ts`, `app.i18n.spec.ts`).
- Run start/completion time and exit status: the bundle was built at 2026-10-20T08:52:24Z and Vitest started at 10:52:24 local (08:52:24Z), duration 598 ms. Result: 2 files / 7 tests passed, coverage summary printed, exit 0.
- Generated report paths and report hashes/run identifier (all Git-ignored):
  - `web/coverage/web/coverage-final.json`: git blob `9c704df0a79cfa4dcaabace02993fcfc9593255a`, identical to the earlier runs, as expected for an unchanged `web/` tree.
  - `web/coverage/web/lcov.info`: git blob `a2f4037d67e3db85a56712ec8b9367f46c5a78e1`.
  - `web/coverage/web/coverage-summary.json` and `web/coverage/web/lcov-report/`.
- Evidence outputs are fresh and inputs stayed unchanged: every file in `web/coverage/web/` has mtime 10:52 local (08:52Z), written by this run. HEAD was `bdb9141` and the working tree was clean before and after the runs.
- Final run after latest test additions and production fixes: yes. The final run is on `bdb9141`. No tests were added, and no production code changed after the run.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/app.ts` | 6/6 (100%) | 8/9 (88.88%) | 1/1 (100%) | 2/2 (100%) |
| `web/src/app/app.html` | 7/7 (100%) | N/A (0/0) | N/A (0/0) | 4/4 (100%) |
| Aggregate (these 2 files only) | 13/13 (100%) | 8/9 (88.88%) | 1/1 (100%) | 6/6 (100%) |

The aggregate excludes `web/src/main.ts` (unmeasured, see above) and the Sass, config and doc files (not
instrumentable). These numbers do not cover the theme. The theme was checked only through the compiled CSS.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/app.ts:10` branch 1 (`BRDA:10,1,0,0`, source-mapped onto `export class App`) | None. It is the compiler-generated factory path (`ɵfac` for inherited construction), not task code | Left uncovered: generated code, not reachable through the component's public API |
| `web/src/main.ts` (no data) | Bootstrap with `appConfig`. The task only added a types reference and reformatted it | Left unmeasured: entry point. `ng build` in `npm run check` compiles it |
| `web/src/theme/_theme.scss`, `web/src/styles.scss`, `web/src/app/app.scss` | AC2 tokens and light/dark overrides | No automated test, see "Unable to test". Checked in the build output |

## Unable to test

- **Compiled global CSS (theme tokens on `:root`, `color-scheme`, `data-theme` overrides) as automated Vitest tests.** `@angular/build:unit-test` does not inject global styles into the jsdom page, and jsdom does not resolve `light-dark()` or `color-scheme`. Reading the SCSS from a spec would need `node:fs`/`sass` and `@types/node`, a new dependency outside the test-writer's permissions. Optional follow-up: a Playwright check in the slow suite, for a task that sets up E2E.
- **`npm ci` from the lockfile** (AC1): not run by the test-writer, because it reinstalls `node_modules`. The parent must record that evidence.
- **`.taskcheck`**: not run, because this session lacked permission. The parent must run it.
- **Runtime light/dark switching in a real browser**: needs a browser. Not run.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. `npm run check` and
`npm run coverage` pass on the final revision `bdb9141`, whose `web/` tree is identical to the earlier tested
revisions. Coverage numbers and report hashes are unchanged. No tests were added. The parent worker must still run
`.taskcheck` and record the `npm ci` evidence. It may also want to soften the wording of the new ARCHITECTURE.md
isolation line (Low, documentation only).
