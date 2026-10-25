# Test Report

Date: 2026-10-26

Task: web-journal - Journal UI (SPEC.md §8.5)

Scope: `67d344023bcf6ea4ffe0bb5c72f29aee651960f9..164a790e8946273f3451f3b0d05661970ac99fe4` on `main` (task
commits 14c237056, faefe15dc, 10466ff0a, c51e08ae7, 066cbb472, d74da6ffe, e2407df42, b3502a86b, 164a790e8,
plus commits that touch only `tasks/` and `reviews/`, all as given by the parent). Production code last changed
in d74da6ffe (`git log d74da6ffe..HEAD -- web/src` changes only `journal-page.spec.ts`). No uncommitted
source or test changes. The only uncommitted file in the working tree is `reviews/web-journal/review.md`,
which the reviewer is editing and which is not a test input.

Requirements:
- `tasks/web-journal.md` acceptance criteria and its recorded assumptions (opening a day creates no entry; today = the server's heatmap `to`, future dates redirect; CR-5/CR-6 rollover and conflict-leave deferred)
- SPEC.md §8.5 (Journal), §4.2 (ETag / If-Match)
- docs/ARCHITECTURE.md "Coverage"; `reviews/web-journal/review.md` (CR-1..CR-3 deferred to `web-journal-rollover`)

## Test files changed

None in this run. The task's test files (all committed at HEAD) are:

- `web/src/app/features/journal/testing.ts` (fictitious fixtures)
- `web/src/app/features/journal/journal.service.spec.ts`
- `web/src/app/features/journal/journal-dates.spec.ts`
- `web/src/app/features/journal/journal-heatmap.spec.ts`
- `web/src/app/features/journal/journal-page.spec.ts` (extended in d74da6ffe, e2407df42, b3502a86b, 164a790e8)
- `web/src/app/features/journal/journal.routes.spec.ts`
- `web/e2e/journal/journal.spec.ts` (Playwright, not run here)

No existing test was weakened or skipped. No production code was changed.

## Tests added or updated

Count: 0 in this run. The suite now has 97 Vitest tests: service 8, dates 20, heatmap 11, routes 2, and page 56. The page count is 54 `it` blocks, one of them parameterized over 2 actions. The previous report covered 81.
I re-checked the tests against the criteria. I found no real gap for criterion 2 (today entry creation, heatmap), so I did not add tests.

## Requirement coverage

Focused command (rows marked Pass): `npm --prefix web run test:ci -- --include 'src/app/features/journal/**/*.spec.ts'`, which is the same as `cd web && npx ng test --watch=false --include ...`. Result: 5 files, 97 tests passed. All rows also passed inside `npm --prefix web run check` (171 files, 3561 tests).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1/AC2 "Today" opens today's entry without creating one | `JournalPage > today > opens today without creating an entry`, `does not create an entry for whitespace only`, `shows today's existing entry with its mood and tags` | focused | Pass |
| AC1/AC2 the first edit creates today's entry by a PUT without If-Match; later edits send If-Match | `creates today's entry with the first edit, by a PUT without If-Match`, `creates the entry from a mood and tags alone`, `sends the version as If-Match for the edits after the entry exists`, `clears the mood with a second tap on it`, `JournalService > creates the entry ... without If-Match`, `replaces an existing entry with its version as If-Match` | focused | Pass |
| Autosave: debounce, one save at a time, edits during a save, leaving | `saves a burst of typing once, after the debounce`, `saves in flight > *` (5), `navigation > saves the edits of a day before opening the next one`, `keeps the page between today and another day ...`, `opens only the last day chosen while the save of the day left is running`, `saves the edits when leaving the journal`, `stays on a day whose save failed`, `stays on today when its save failed`, `asks before closing the window with unsaved edits` | focused | Pass |
| AC1 CodeMirror + image paste | `images > *` (9: paste upload + Markdown, placeholder never saved, failed upload, upload finishing during Preview / after Preview / on leave / before another day) | focused | Pass |
| AC1/AC2 calendar heatmap | `JournalHeatmap > *` (11), `journal dates > heatmapWeeks > *`, `JournalPage > heatmap > *` (3: opens a day on click, week start, updates a day when its mood is saved), `saves in flight > adds a saved day to the heatmap in date order`, `saves a day older than the heatmap without changing the heatmap` | focused | Pass |
| AC1 "on this day" | `JournalPage > on this day > *` (3), `JournalService > loads "on this day" ...` | focused | Pass |
| Navigation and out-of-order answers | `navigation > *` (13 incl. `ignores the answer/failures of a day that was left before ...`, `opens only the day chosen last when the route changes before the first heatmap arrived`), `journal routes > *` | focused | Pass |
| Errors and conflicts (409/412/428, retry, late reloads) | `errors > *` (12 incl. the parameterized late "keep mine"/"use theirs" reload success and failure) | focused | Pass |
| AC2 `cd web && npm run check` passes | api:check, lint, e2e typecheck, 171 test files / 3561 tests, build | `npm --prefix web run check` | Pass |
| AC1 in a real browser (`web/e2e/journal/journal.spec.ts`) | Playwright spec | - | Not run: the parent told me not to run Playwright/docker. The parent recorded criterion 1 as verified (31 passed). |
| CR-3 (late heatmap response from a superseded load overwrites the newer heatmap) | none | - | Not tested: a known review finding, deferred by the parent to task `web-journal-rollover` |

## Focused test execution

Command:

`npm --prefix web run test:ci -- --include 'src/app/features/journal/**/*.spec.ts'` (equivalent to `cd web && npx ng test --watch=false --include 'src/app/features/journal/**/*.spec.ts'`; the `cd` form was not permitted in this sandbox)

Result:

Pass (5 test files, 97 tests, 2026-10-25T23:17:34Z, 5.9 s). There were no timeouts. The load average was about 5.

## Full test execution

Command:

`npm --prefix web run check`

Result:

Pass, run twice (2026-10-25 about 23:18Z and 23:19-23:20Z). It ran api:check, lint ("All files pass linting."), e2e typecheck, test:ci and build. The build ran, which needs every earlier `&&` step to pass. A separate `npm --prefix web run test:ci` gave 171 test files and 3561 tests passed. The build prints the existing initial bundle budget warning (677 kB > 500 kB), which is not a failure.

## .taskcheck

Result:

Pass (`sh scripts/check.sh`, about 23:21-23:22Z): tfp, server, web (171 files / 3561 tests) and android all ran, ending with "== all checks passed".

## Bugs found

None found by tests. One known defect is not covered by a test: review finding CR-3 (a late heatmap response from a superseded first load can overwrite the newer heatmap at `journal-page.ts:143`/`:333`). The parent deferred it to `web-journal-rollover`, so I added no failing test for it here.

## Coverage

Status: verified (whole-file coverage of the task's production files, journal specs only).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Every file except `journal.routes.ts` was added by the task, so the whole file is changed code. `journal.routes.ts` is fully covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-journal`. Base `67d344023bcf6ea4ffe0bb5c72f29aee651960f9` and the task commits come from the parent. HEAD is `164a790e8946273f3451f3b0d05661970ac99fe4`. File scope: `git diff --name-only 67d344023..HEAD -- web/src`, without specs and `testing.ts`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 164a790e8 with no uncommitted inputs. Test file sha256 prefixes: `journal-dates.spec.ts` 471b27fccf0a, `journal-heatmap.spec.ts` 437727ab83ef, `journal-page.spec.ts` 328b18836100, `journal.routes.spec.ts` 90d7b4f23aef, `journal.service.spec.ts` 8df2589f20e5, `testing.ts` fa96d1aa3545. Production `features/journal/*.ts` and `*.html` were hashed before the runs (23:17:25Z) and checked after them (23:22:58Z): unchanged.
- Exact included files: `web/src/app/features/journal/journal-dates.ts`, `journal-heatmap.ts`, `journal-page.ts`, `journal.routes.ts`, `journal.service.ts`. Excluded: the `*.html` and `*.scss` templates and styles, because the provider does not instrument them; the component tests exercise the templates. Also excluded: `testing.ts` (test helper), the specs, `web/e2e/journal/journal.spec.ts`, `web/src/app/app.spec.ts` and `app.i18n.spec.ts` (tests).
- Provider/version, command, working directory, and test selection: `@vitest/coverage-v8` / vitest 5.0.5. Command: `npm --prefix web run coverage -- --include 'src/app/features/journal/**/*.spec.ts'`, run in `web/` through `--prefix`. Test selection: the five journal spec files.
- Run start/completion time and exit status: started 2026-10-25T23:17:46Z, reports written 23:17:54Z. 97/97 tests passed and the report was generated. The exit status was not captured separately, but Vitest reported no failures.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 7bc98f3270dbc697e976cb812b3a55bc930909168d77aa7bf712fa4859cab6a4; `web/coverage/web/coverage-summary.json` sha256 893943fea4d8ad2be9e234cdb821422c22881ad4ab5fe6108816481ca4ff1d6b (mtime 2026-10-26 00:17:54 +0100).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote `coverage/` in this run (new mtime and new hashes compared with the previous report), and the source hashes matched after the run.
- Final run after latest test additions and production fixes: yes. It measured HEAD 164a790e8; production code is unchanged since d74da6ffe.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/journal/journal-dates.ts` | 40/40 (100%) | 36/36 (100%) | 8/8 (100%) | 35/35 (100%) |
| `web/src/app/features/journal/journal-heatmap.ts` | 44/44 (100%) | 23/25 (92%) | 9/9 (100%) | 34/34 (100%) |
| `web/src/app/features/journal/journal-page.ts` | 253/257 (98.44%) | 150/158 (94.94%) | 53/53 (100%) | 204/205 (99.51%) |
| `web/src/app/features/journal/journal.routes.ts` | 7/7 (100%) | 4/4 (100%) | 2/2 (100%) | 5/5 (100%) |
| `web/src/app/features/journal/journal.service.ts` | 16/16 (100%) | 15/15 (100%) | 6/6 (100%) | 12/12 (100%) |
| **Total (summed counters, 5 files, none missing)** | 360/364 (98.90%) | 228/238 (95.80%) | 78/78 (100%) | 290/291 (99.66%) |

No coverage threshold is defined (ARCHITECTURE.md: coverage informs the test-writer and is not a gate). Total excludes templates and styles, which are not instrumented (see above).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `journal-heatmap.ts:25[0]` (inject default), `:51[1]` (`?? []` for no weeks) | none (defensive defaults; there is always at least one week) | justified gap |
| `journal-page.ts:145[1]` (`today() ?? ''` after the heatmap loaded) | none: `loadHeatmap()` always sets today first | justified gap (defensive) |
| `journal-page.ts:168` (step past today) | step beyond today | not reachable from the UI: Next is disabled on today. Future URLs are tested (`leads a date after today to today`) |
| `journal-page.ts:228[1]` (flush with no change while in the error state keeps "Not saved") | an edit reverted to the saved text after a failed save | minor gap. Not part of AC1/AC2. Failed save + retry is tested |
| `journal-page.ts:368`, `:377` (save answer or failure after another day was opened) | not reachable through `open()`, which waits for `leave()` before switching the day | justified gap (defensive) |
| `journal-page.ts:372[1]`, `:373` (destroyed during a save with edits equal to the draft in flight) | leaving during a save | the "edits differ" path is tested (`saves the last edits once when the journal is left during a save`); the equal-draft branch is a minor gap |
| `journal-page.ts:439[1..2]` (`sameDraft` mood/tags comparison when the text is equal) | part of the branch above | minor gap |
| `journal-page.ts:143`/`:333` (CR-3: stale heatmap write from a superseded first load) | the heatmap could show an outdated state after two quick route changes | the lines are executed, but the defect is not asserted. Deferred by the parent to `web-journal-rollover` |

## Unable to test

- AC1 in a real browser (real clipboard paste into CodeMirror, heatmap layout, full stack): covered by `web/e2e/journal/journal.spec.ts`. The parent ran it and recorded criterion 1; the test-writer did not run it (instruction: no Playwright/docker).
- `.html`/`.scss` are not instrumented by the coverage provider. The component tests check template behavior.
- CR-1/CR-2/CR-3 behaviors (midnight rollover, conflict edits on leave, stale heatmap) are deferred to `web-journal-rollover` and are not tested here.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
