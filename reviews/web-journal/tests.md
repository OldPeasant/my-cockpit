# Test Report

Date: 2026-10-25

Task: web-journal - Journal UI (SPEC.md §8.5)

Scope: `fc87c85caf88a97d6b5fe23b30f101f024dd45aa..1b93ef6d482847b7ccca6d3a545538a3d89de5be` (task commits
236096dc1, ff18f4fac, f8b80211e, 1b93ef6d4 on `main`), plus the uncommitted new test files listed below.

Requirements:
- `tasks/web-journal.md` acceptance criteria and its two recorded assumptions (opening a day creates no entry; today = the server's heatmap `to`, future dates redirect)
- SPEC.md §8.5 (Journal), §4.2 (ETag / If-Match)
- docs/ARCHITECTURE.md "Coverage", existing feature spec conventions (`features/habits`, `features/notes/editor`)

## Test files changed

- `web/src/app/features/journal/testing.ts` (new, fictitious fixtures `journalEntry`, `journalHeatmap`)
- `web/src/app/features/journal/journal.service.spec.ts` (new)
- `web/src/app/features/journal/journal-dates.spec.ts` (new)
- `web/src/app/features/journal/journal-heatmap.spec.ts` (new)
- `web/src/app/features/journal/journal-page.spec.ts` (new)
- `web/src/app/features/journal/journal.routes.spec.ts` (new)

No existing test was changed, weakened or skipped. No production code was changed.

## Tests added or updated

Count: 81 (service 8, dates 20, heatmap 11, page 41, routes 1)

## Requirement coverage

Command for all rows marked Pass: `cd web && npx ng test --watch=false --include 'src/app/features/journal/**/*.spec.ts'` (5 files, 81 tests passed), and again inside `npm run check` (171 files, 3535 tests passed).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 "Today" opens today's entry without creating one | `JournalPage > today > opens today without creating an entry`, `does not create an entry for whitespace only`, `shows today's existing entry with its mood and tags`, `images > uploads a pasted image ...` (placeholder alone creates nothing) | see above | Pass |
| AC1 "Today" creates today's entry on the first edit: PUT without If-Match | `creates today's entry with the first edit, by a PUT without If-Match`, `creates the entry from a mood and tags alone`, `JournalService > creates the entry of a date with a PUT without If-Match` | see above | Pass |
| Later edits send the version as If-Match (SPEC §4.2) | `sends the version as If-Match for the edits after the entry exists`, `clears the mood with a second tap on it`, `JournalService > replaces an existing entry with its version as If-Match ...` | see above | Pass |
| Autosave debounce, one save at a time, edits during a save, leaving saves | `saves a burst of typing once, after the debounce`, `saves in flight > *` (3 tests), `navigation > saves the edits of a day before opening the next one`, `stays on a day whose save failed`, `saves the edits when leaving the journal`, `asks before closing the window with unsaved edits` | see above | Pass |
| AC1 CodeMirror editor + image paste uploads and inserts Markdown | `images > uploads a pasted image and saves its Markdown in the entry`, `never saves the placeholder of an image still uploading`, `shows a failed upload and removes its placeholder`, `puts an image still uploading in place before opening another day`, `JournalService > uploads an image as multipart form data` | see above | Pass |
| AC1 calendar heatmap renders entries and mood levels | `JournalHeatmap > *` (11 tests: grid, mood levels incl. entry without mood, labels, count plural, legend, selected/tab stop, click, padding/week start, month labels, arrow keys), `journal dates > heatmapWeeks > *` (7), `JournalPage > heatmap > *` (3 incl. update after save) and `saves in flight > adds a saved day to the heatmap in date order` / `saves a day older than the heatmap ...` | see above | Pass |
| Heatmap opens a day | `JournalPage > heatmap > shows the entries of the last year and opens a day on click` | see above | Pass |
| AC1 "on this day" in previous years | `JournalPage > on this day > *` (list with years-ago text, excerpt / mood / title fallback, links; empty state; failure state), `JournalService > loads "on this day" for today or a given date` | see above | Pass |
| Navigation; future or invalid date leads to today | `navigation > steps to the previous and next day`, `leads a date after today to today`, `leads a date that is no date to today without loading it`, `opens a past day directly`, `ignores the answer of a day that was left before it arrived`, `journal routes > *` | see above | Pass |
| Error and conflict handling | `errors > *` (load failure + retry, entry load failure + retry, failed save + retry, non-API error message, 412 keep mine, 409 use theirs, keep-mine/use-theirs reload failure) | see above | Pass |
| AC2 Vitest component/service tests cover today entry creation and heatmap | the 81 tests above | see above | Pass |
| AC2 `cd web && npm run check` passes | whole web check (api:check, lint, e2e typecheck, 171 test files / 3535 tests, build) | `cd web && npm run check` | Pass |
| AC1 in a real browser (Playwright `web/e2e/journal/journal.spec.ts`) | not run by the test-writer | - | Not run: the parent worker runs the Playwright e2e against the stack in the background; the test-writer was told not to start docker/e2e |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/journal/**/*.spec.ts'`

Result:

Pass (5 test files, 81 tests; run repeatedly, stable). Lint: `npx eslint --max-warnings=0 src/app/features/journal/` and `npx prettier --check src/app/features/journal/` pass.

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass (exit 0, 2026-10-25T21:32:00Z-21:32:50Z; 171 test files, 3535 tests). An earlier full `npm run coverage` run at 21:28Z under a load average of about 20 (other agents on the machine) had 91 timeouts in 14 unrelated and journal files (`Test timed out in 5000ms`); the same suites passed in the later full runs, so this was machine load, not a defect.

## .taskcheck

Result:

Pass (`sh scripts/check.sh` from `web/` via `sh ../scripts/check.sh`, exit 0, 21:33:06Z-21:34:15Z, "all checks passed"; the build prints the existing initial bundle budget warning).

## Bugs found

None. No production defect was found by the new tests.

## Coverage

Status: verified (whole-file coverage of the task's production files, journal specs only).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All files except `journal.routes.ts` were added by the task (their whole file is changed code); `journal.routes.ts` is fully covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-journal`; base `fc87c85caf88a97d6b5fe23b30f101f024dd45aa` (from the parent); HEAD `1b93ef6d482847b7ccca6d3a545538a3d89de5be` (committed 21:25:59Z, before the first coverage run; differs from f8b80211e only in `web/e2e/`); task commits = `git log fc87c85c..HEAD` as stated by the parent.
- Source/test snapshot identifier (including uncommitted inputs): production files unchanged from HEAD (no uncommitted production changes); uncommitted test files sha256: `journal-dates.spec.ts` 471b27fc..., `journal-heatmap.spec.ts` 437727ab..., `journal-page.spec.ts` 8df0f247..., `journal.routes.spec.ts` 272acecf..., `journal.service.spec.ts` 8df2589f..., `testing.ts` fa96d1aa.... The hashes of `features/journal/*.ts` were the same before and after the run.
- Exact included files: `web/src/app/features/journal/journal-dates.ts`, `journal-heatmap.ts`, `journal-page.ts`, `journal.routes.ts`, `journal.service.ts`. Excluded: `*.html` / `*.scss` templates and styles (not instrumented by the provider; templates are exercised through the component tests), `web/e2e/journal/journal.spec.ts`, `web/src/app/app.spec.ts`, `app.i18n.spec.ts` (tests), `testing.ts` (test helper).
- Provider/version, command, working directory, and test selection: `@vitest/coverage-v8` 5.0.5 / vitest 5.0.5; `npm run coverage -- --include 'src/app/features/journal/**/*.spec.ts'` in `web/`; the five journal spec files only.
- Run start/completion time and exit status: 2026-10-25T21:31:48Z - 21:31:56Z, exit 0, 81/81 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 a272866a73487ad9c84bf40da1421e00e9477a9b1e9258fe2814e6018e32b105; `web/coverage/web/coverage-summary.json` sha256 037d010769839a1ec35af284eeebb6e30f9bf8a8f23d02198bec4a9a7ee59c28.
- Evidence outputs are fresh and inputs stayed unchanged: yes (Vitest rewrote `coverage/` in this run; source hashes checked before and after).
- Final run after latest test additions and production fixes: yes (no production fixes so far; if the fixer changes production code, this must be measured again).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/journal/journal-dates.ts` | 40/40 (100%) | 36/36 (100%) | 8/8 (100%) | 35/35 (100%) |
| `web/src/app/features/journal/journal-heatmap.ts` | 44/44 (100%) | 23/25 (92%) | 9/9 (100%) | 34/34 (100%) |
| `web/src/app/features/journal/journal-page.ts` | 244/254 (96.06%) | 137/155 (88.38%) | 50/52 (96.15%) | 202/204 (99.01%) |
| `web/src/app/features/journal/journal.routes.ts` | 3/3 (100%) | N/A (0/0) | 2/2 (100%) | 3/3 (100%) |
| `web/src/app/features/journal/journal.service.ts` | 16/16 (100%) | 15/15 (100%) | 6/6 (100%) | 12/12 (100%) |
| **Total (summed counters, 5 files, none missing)** | 347/357 (97.20%) | 211/231 (91.34%) | 75/77 (97.40%) | 286/288 (99.31%) |

No coverage threshold is defined (ARCHITECTURE.md: coverage informs, not a gate).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `journal-heatmap.ts:25` (inject default), `:51` (`?? []` for no weeks) | none (defensive defaults; the heatmap always has at least one week) | justified gap |
| `journal-page.ts:133` (a newer load arrives while the first heatmap is loading) | race on the very first load | not tested: needs two route changes before the first heatmap answers; later-load races are tested (`ignores the answer of a day that was left before it arrived`) |
| `journal-page.ts:134[1]`, `:157` (step past today guard) | step beyond today | not reachable from the UI: the Next button is disabled on today; future URLs are tested |
| `journal-page.ts:146[1]`, `:241[1]`, `:251`, `:257[1]`, `:325[1]` (stale-sequence guards for late errors / keep-mine after a day change) | late answers after another day was opened | partly covered by the late-success test; the late-failure variants are left as a minor gap |
| `journal-page.ts:217[1]` (flush with no change while in error state) | keeps "Not saved" | minor gap |
| `journal-page.ts:278`, `:280` (a newer route while leaving; staying on the failed day from the today route) | navigation while a save runs | partly covered (`stays on a day whose save failed` covers the date route); the today-route variant and the double-navigation-while-saving race are untested, minor |
| `journal-page.ts:305-306` (upload placeholder deleted by the user before leaving) | image upload result dropped when its placeholder is gone | minor gap |
| `journal-page.ts:350`, `:359` (save answer after another day was opened) | not reachable through `open()`, which waits for `leave()` before switching the day | justified gap (defensive) |
| `journal-page.ts:354[1]`, `:355`, `:420[1..2]` (destroyed during a save with edits equal to the draft in flight) | leaving during a save | the "edits differ" path is tested (`saves the last edits once when the journal is left during a save`); the equal-draft branch is a minor gap |

## Unable to test

- AC1 in a real browser (CodeMirror paste with a real clipboard, the heatmap layout, the full stack): covered by the Playwright spec `web/e2e/journal/journal.spec.ts`, which the parent worker runs. The test-writer did not run it (instruction: do not start docker/e2e). Needs the parent's e2e result as evidence.
- `.html`/`.scss` are not instrumented by the coverage provider; template behavior is checked through the component tests.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
