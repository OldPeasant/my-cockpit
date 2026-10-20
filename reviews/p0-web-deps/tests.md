# Test Report

Date: 2026-10-20

Task: p0-web-deps - Web dependencies, theme and i18n baseline

Scope: final task revision `c5af6ea15b6537db42936d7c1d68179f63a34e33` ("p0-web-deps: reword i18n spec isolation comment"),
measured at its rebased equivalent HEAD `eb2681fe093bc03dce0bbfff77370f8e2be88b8f` on `main`. The base is
`ac8d94516a08bf8422ff2039a79ef4a49c6f5cf4` (parent of the first task commit after the latest rebase).

Task commits after the rebase: `08043e0`, `7d63aff`, `61f270b`, `771cb9e`, `a81c577`, `eb2681f`. Before the
rebase they were `75cc358`, `40710a8`, `faf31ed`, `3a50c06`, `11176b0`, `c5af6ea`, on base `ef7ea3f`, as
supplied by the parent worker. Diff: `git diff ac8d945..eb2681f -- web docs`.

`git diff c5af6ea eb2681f -- web` is empty, and the blob of `web/src/app/app.i18n.spec.ts` is
`84492783323e9cef781295a3fffd40e00a91a49f` in both. So the tested web code at `eb2681f` is byte-identical
to `c5af6ea`. The rebase only brought in other tasks' commits (board/verify commits, `p0-server-deps`
server and docs files).

This report replaces an earlier one written for a pre-rebase revision. Code changes since that revision:
- `11176b0` (now `a81c577`) adds `"options": {"isolate": true}` on the `test` target in `web/angular.json`, plus a comment in `web/src/app/app.i18n.spec.ts`. This addresses review finding CR-3.
- `c5af6ea` (now `eb2681f`) rewords that comment only (2 comment lines, no code change).

Note on concurrency: while this report was being written, the parent's `tasks.py verify` / `pull --rebase --autostash`
rewrote history several times. `npm run check` and `npm run coverage` ran on `c5af6ea` (08:47Z) and again
on `eb2681f` (08:48Z), with identical results. HEAD was confirmed to be `eb2681f` before and after the final runs.
The single-worker robustness runs and the probe ran on `11176b0`. Their web inputs differ from `eb2681f`
only by the reworded comment, so they still apply.

Uncommitted inputs: none under `web/`. During the final runs, the working tree differed from HEAD only in
`reviews/p0-web-deps/tests.md` and `reviews/p0-web-deps/review.md`. These are reports, not test inputs,
and the test-writer did not change `review.md`. The temporary probe files (see "i18n spec robustness")
existed only during the `11176b0` run and were deleted.

Requirements:
- `tasks/p0-web-deps.md` acceptance criteria and its recorded assumptions
- `reviews/p0-web-deps/review.md` finding CR-3 (i18n spec robustness)
- `docs/ARCHITECTURE.md` (stack table, "Coverage", conventions: externalized i18n strings)
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
Vitest/jsdom setup can test (see "Unable to test"). More tests would only repeat what these already check.

## i18n spec robustness (CR-3)

Revision: `11176b0`. Its test and production inputs are identical to `eb2681f` except for the reworded comment.

Method: two temporary Vitest runner configs passed with `--runner-config`. Each set `maxWorkers: 1`,
`fileParallelism: false` and a custom `sequence.sequencer` that forces the spec file order and prints it.
The configs and the probe spec were deleted after the run.

| Run (all from `web/`) | Isolation | Forced order (printed by sequencer) | Result |
|---|---|---|---|
| `npx ng test --watch=false --runner-config=tw-probe-i18n-last.config.mjs --reporters=verbose` | `true` (angular.json) | `app.spec.ts` -> `app.i18n.spec.ts` | Pass, 2 files / 7 tests |
| `npx ng test --watch=false --runner-config=tw-probe-i18n-first.config.mjs --reporters=verbose` | `true` (angular.json) | `app.i18n.spec.ts` -> `app.spec.ts` | Pass, 2 files / 7 tests |
| same, plus `--isolate=false` | `false` (negative control) | `app.spec.ts` -> `app.i18n.spec.ts` | Pass, 2 files / 7 tests |
| same, plus `--isolate=false` | `false` (negative control) | `app.i18n.spec.ts` -> `app.spec.ts` | Pass, 2 files / 7 tests |

The negative control also passed, so I checked the caching premise directly with a temporary probe spec
(`npx ng test --watch=false --include=src/app/tw-probe.spec.ts`, then deleted). In one test, one module
graph, the probe rendered `App` with no translations, called `TestBed.resetTestingModule()`, loaded
translations, and rendered again. Observed values: before = `Scaffold. The application shell is built by
the board's tasks.`; after: `h1` = `Mein Cockpit`, `p` = `Gerüst.`. The template `i18n` text was **not**
stuck on the first render's English. In this setup, TestBed resets between tests already make the
translations take effect.

Conclusion: the i18n spec passes with one worker in both file orders, with and without isolation.
CR-3 is resolved: `isolate: true` is harmless and costs nothing measurable (about 0.6 s either way).
At `c5af6ea`/`eb2681f`, the comment no longer claims that the spec relies on isolation.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: package.json adds the listed libraries; `npm ci` works from the lockfile | None (dependency manifest, no runtime behavior). `npm ls --depth=0` lists every required package (`@angular/material`/`cdk`/`localize` 22.2.3, `@codemirror/*` + `lang-markdown`, `@fullcalendar/angular`/`core`/`rrule` 7.1.3 + `fullcalendar` 7.1.3 per the recorded v7 assumption, `@tiptap/core`/`starter-kit`/`extension-link`/`extension-image` 3.32.0, `pdfjs-dist` 6.4.299, `ng-openapi-gen` 1.1.0, `@playwright/test` 1.64.1, `otplib` 13.5.0, `@axe-core/playwright` 4.14.0). No missing or invalid entries | `npm --prefix web ls --depth=0` (on `11176b0`; `web/package*.json` unchanged since) | Not testable as a unit test. `npm ci` not run by the test-writer, because it reinstalls dependencies. The parent reports that `npm ci` succeeded and must record that evidence itself |
| AC2: Material 3 tokens on `:root`, light + dark via `prefers-color-scheme` and `data-theme` override | No automated test (see "Unable to test"). The build output `web/dist/web/browser/styles-LD4DV62L.css` (same file name and content hash in the `eb2681f` build) contains `:root{color-scheme:light dark`, `--app-bg: var(--mat-sys-surface)`, `:root[data-theme=light]{color-scheme:light}`, `:root[data-theme=dark]{color-scheme:dark}`, `body{margin:0;background:var(--app-bg);color:var(--app-fg);font:var(--mat-sys-body-medium)}` | `npm --prefix web run check`, then `grep -oE` on the built `styles-LD4DV62L.css` | Pass (build-output inspection, not an automated test) |
| AC2: no hard-coded colors in `app.*` | No unit test. Literal-color scan of `web/src/app/app.ts`, `app.html` and `app.scss` (hex, `rgb()/rgba()`, `hsl()/hsla()`, common named colors): no match (grep exit 1) | `grep -nEi '#[0-9a-f]{3,8}\b\|rgba?\(\|hsla?\(\|\b(white\|black\|red\|blue\|green\|gray\|grey)\b' web/src/app/app.{ts,html,scss}` | Pass (manual scan) |
| AC3: `@angular/localize` set up, `$localize` usable, English source locale | `renders the English source title without the $localize message id metadata`, `provides the $localize runtime global (polyfill @angular/localize/init)`, `translates the title through $localize using the app.title message id` | `npm --prefix web run check` (its `test:ci` step) on `eb2681f`; also the four single-worker runs above | Pass |
| AC3: `i18n` usable in templates | `renders the i18n-marked scaffold text in the English source locale`, `translates the i18n template text using the app.scaffold message id` | `npm --prefix web run check` on `eb2681f`; also the four single-worker runs above | Pass |
| AC3: English source locale in the build config (`angular.json` `i18n.sourceLocale: en`, `src/locale/messages.xlf` `source-language="en"`) | No unit test. Confirmed in the diff | `git diff ac8d945..eb2681f -- web/angular.json web/src/locale/messages.xlf` | Not testable in Vitest (build config) |
| AC4: `npm run check` passes | Whole web suite | `npm --prefix web run check` on `eb2681f` | Pass: lint "All files pass linting.", 2 test files / 7 tests passed, `ng build` succeeded |
| AC4: ARCHITECTURE.md stack table updated | Not testable (documentation). After the rebase's conflict resolution, the task diff still shows the updated Web row and the `npm ci` / Playwright install note | `git diff 9a8930b..69412d6 -- docs/ARCHITECTURE.md` (an intermediate rebase of the same commits) | Not testable |
| CR-3: i18n spec independent of worker layout and spec order | Both spec files | Four single-worker runs (table above) | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --runner-config=tw-probe-i18n-last.config.mjs --reporters=verbose` and the same with `tw-probe-i18n-first.config.mjs` (temporary single-worker configs, deleted after the run; see above)

Result:

Pass: 2 files, 7 tests in each forced order. The negative controls with `--isolate=false` also passed.

## Full test execution

Command:

`npm --prefix web run check` (= `npm run lint && npm run test:ci && ng build`)

Result:

Pass on HEAD `eb2681f` (= `c5af6ea` for `web/`): lint clean, 2 files / 7 tests passed (run at
2026-10-20T08:48:49Z), production build succeeded (`main-EFEVWPV5.js` 208.52 kB, `styles-LD4DV62L.css` 9.59 kB).
Identical earlier passes: on `c5af6ea` at 08:47:20Z, and on `11176b0` at 08:41:39Z.
Server and Android are untouched by this task and were not run.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` was refused by this session's command permissions (approval required).
Its web part (`npm run check`) ran and passed, see above. The parent worker must run `.taskcheck`.

## Bugs found

None. No production defect was found.

Note for the parent (not a production defect, no fix required): the earlier note about the comment in
`web/src/app/app.i18n.spec.ts` was addressed in `c5af6ea`. The comment still says Angular caches i18n
template constants on first render. The probe shows this does not bite across TestBed resets in this
setup, so the claim is conservative and harmless. No change is needed.

## Coverage

Status: verified for the instrumentable TypeScript/template files in scope (see exclusions).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting the added/modified lines of the task diff (`git diff -U0`)
with the source-mapped `DA` records in `web/coverage/web/lcov.info`. Results: `web/src/app/app.ts` line 11
(`DA:11,6`) and `web/src/app/app.html` line 3 (`DA:3,6`), so 2/2 changed executable lines are covered (100%).
`web/src/main.ts` changed lines (1-2, 7) are unmeasured, see exclusions. Changed-branch coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source:
  - Task `p0-web-deps`, measured HEAD `eb2681fe093bc03dce0bbfff77370f8e2be88b8f`.
  - It is the rebased equivalent of `c5af6ea15b6537db42936d7c1d68179f63a34e33`; `git diff c5af6ea eb2681f -- web` is empty.
  - Base `ac8d94516a08bf8422ff2039a79ef4a49c6f5cf4`, the parent of the first task commit `08043e0`.
  - Task commits are the six `p0-web-deps:` / `wip: p0-web-deps` commits on top of that base. They match the parent-supplied list and the task file.
  - File scope comes from `git diff --name-status` over the task commits, `-- web/src`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `eb2681f`, no uncommitted inputs under `web/`. Git blob ids, identical at `c5af6ea`:
  - `web/src/app/app.ts` `9d2dc11691fdccfd467d18b75086dffbf1f4ab18`
  - `web/src/app/app.html` `e3367aac3d739b8e9d3e665e93d98b1a7ad3b00b`
  - `web/src/main.ts` `dc13603859a4fee0cf1551355d1d94f70181f456`
  - `web/src/app/app.spec.ts` `5e6f52aafb7bebb610fb525bd4c58207ba0ba3b0`
  - `web/src/app/app.i18n.spec.ts` `84492783323e9cef781295a3fffd40e00a91a49f`
  - `web/angular.json` `ec7436b1cc10aa4d5ab5357bd74a12cf75dc608c`
- Exact included files: `web/src/app/app.ts`, `web/src/app/app.html`.
- Excluded files, all modified or added, none renamed or deleted:
  - `web/src/main.ts`: the bootstrap entry point. No spec imports it, so v8 has no data for it. The change is a `@angular/localize` types reference plus one formatting line, with no behavior to instrument. `ng build` compiles it.
  - `web/src/app/app.scss`, `web/src/styles.scss`, `web/src/theme/_theme.scss`: Sass, which v8 cannot instrument. Checked through the build output above.
  - `web/src/locale/messages.xlf`: the extracted i18n source.
  - `web/angular.json`, `web/package.json`, `web/package-lock.json`, `web/tsconfig.app.json`, `web/tsconfig.spec.json`: configuration.
  - `docs/ARCHITECTURE.md`: documentation.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` 5.0.5 via `@angular/build:unit-test` (`isolate: true`); `npm --prefix web run coverage` (= `ng test --watch=false --coverage` with text-summary/json-summary/json/lcov reporters); working directory `web/`; all specs (`app.spec.ts`, `app.i18n.spec.ts`).
- Run start/completion time and exit status: final run on `eb2681f`, Vitest start 2026-10-20T08:48:52Z, finished by 08:48:53Z (UTC). 2 files / 7 tests passed, coverage summary printed, exit 0. An identical run on `c5af6ea` started at 08:47:25Z.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (git blob `9c704df0a79cfa4dcaabace02993fcfc9593255a`, identical across the `11176b0`, `c5af6ea` and `eb2681f` runs, as expected for unchanged code), `web/coverage/web/coverage-summary.json`, `web/coverage/web/lcov.info`, `web/coverage/web/lcov-report/` (all Git-ignored).
- Evidence outputs are fresh and inputs stayed unchanged: `lcov.info` has mtime 10:48 local (08:48Z), written by the final run. `git rev-parse HEAD` returned `eb2681f` immediately before and after the check and coverage runs.
- Final run after latest test additions and production fixes: yes. The final run is on `eb2681f` (= `c5af6ea` for `web/`). No tests were added and no production fixes were made in this run.

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

- **Compiled global CSS (theme tokens on `:root`, `color-scheme`, `data-theme` overrides) as automated Vitest tests.** `@angular/build:unit-test` does not inject global styles into the jsdom page, and jsdom does not resolve `light-dark()` or `color-scheme`. Reading or compiling the SCSS from a spec would need `node:fs`/`sass` with `@types/node`, which the project does not have. Adding it would be a new dependency, outside the test-writer's permissions. Checked by build-output inspection and a source scan instead. Optional follow-up: a Playwright check in the slow non-check suite (`data-theme="dark"`, emulated `prefers-color-scheme`, assert the computed `--mat-sys-surface` and `color-scheme`), for a task that sets up E2E.
- **`npm ci` from the lockfile** (AC1): not run by the test-writer, because it reinstalls `node_modules`. The parent states that it succeeded and must record that evidence.
- **`.taskcheck`**: not run because this session lacked permission. The parent must run it.
- **Runtime light/dark switching in a real browser**: needs a browser. Not run.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. `npm run check` and
`npm run coverage` pass on `eb2681f`, the rebased equivalent of `c5af6ea` with byte-identical `web/`.
The i18n spec passes in a single worker in both spec orders, both with `isolate: true` and without it,
so CR-3 is resolved. No tests were added: the existing ones cover `$localize`/`i18n` meaningfully, and
the remaining criteria cannot be unit-tested. The theme was checked only in the build output. The parent
worker must still run `.taskcheck` and record the `npm ci` evidence.
