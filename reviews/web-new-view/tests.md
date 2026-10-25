# Test Report

Date: 2026-10-25

Task: web-new-view: Combined New view (GTD inbox + mail inbox)

Scope: `614a515158d13f77a258d0eb380156a4f2a2bc39..5784aa66e63e1d99422e5be40a508421b33fb3ec` (task commits 98d81e0d, 5784aa66, given by the parent worker), limited to `web/`. Branch `main`, HEAD 5784aa66. Uncommitted when measured: only the two test files changed by this report (`new-page.spec.ts`, `new-feed.spec.ts`).

Requirements:
- `tasks/web-new-view.md` acceptance criteria 1-4 and the recorded assumptions (filter keys 1/2/3, `?source=inbox|mail`, held-back tasks while more mail can load, j/k/o/Enter/t keys)
- SPEC.md §5.3 "New view", §9.1
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md
- Instructions from the parent worker (edge cases to cover)

## Test files changed

- `web/src/app/features/new/new-page.spec.ts`
- `web/src/app/features/new/new-feed.spec.ts`

## Tests added or updated

Count: 29 (25 in `new-page.spec.ts`, counting each `it.each` case; 4 in `new-feed.spec.ts`). No existing test was changed in substance. I added `taskFailure`/`mailFailure` switches to the fake API, and `press()` can now send a key to any element with any options.

## Requirement coverage

Command for every row: `cd web && npx ng test --watch=false --include='src/app/features/new/**/*.spec.ts'` (51 tests: 49 passed, 2 failed, both TW-1).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 merge newest first, source icon + label | `merges both inboxes newest first and shows each row source`; mergeNew `merges tasks by creation...`, `orders equal times...`, `sorts an unparsable date last...` | focused | Pass |
| AC1 mail paging keeps the order (button, IntersectionObserver, keyboard) | `keeps the order when older mail loads`; `infinite scroll > loads the next mail page when the end of the list comes into view`; `infinite scroll > shows a failed next page and loads it again on the button`; `infinite scroll > loads no mail pages under the GTD Inbox filter`; `loads older mail when the keyboard cursor reaches the last row`; mergeNew `holds back...`, `only appends...`, `shows a task exactly as old as the oldest loaded mail, holds back one just older`, `holds nothing back once the last mail page is loaded, or without mails` | focused | Pass |
| AC1 row content (sender, snippet, no-subject fallback, unread, date format) | `shows sender, snippet, a subject fallback and the read state of mail rows`; `dates > shows the time today, the day this year and the full date before` | focused | Pass |
| AC1 route `/new` + nav entry | `app.spec.ts` (existing, changed by the task) | `npm run check` (test:ci) | Pass |
| AC2 filters All / GTD Inbox / Mail by button, key and URL | `filters by the URL` (3 cases); `switches the filter with the buttons and keys and keeps it in the URL`; `does not navigate when the shown filter is chosen again`; mergeNew `filters by source`; parseFilter (5 cases) | focused | Pass |
| AC2 keys ignored while typing, with modifiers, on repeat, with a dialog open; Enter on a button | `keys > ignores keys typed into an input`; `keys > ignores keys with modifiers and repeated keys`; `keys > leaves Enter on a focused button to the button`; `keys > ignores keys while a dialog is open`; `keeps the cursor at the first row on k`; `shows the empty state, where the row keys do nothing` | focused | Pass |
| AC2 opening a row (task detail / mail read view) | `opens a task in its detail view`; `opens a mail in the read view with the keyboard`; `opens a mail in the read view on click` | focused | Pass |
| AC2/AC3 "→ Inbox task" (mail-gtd API), mail stays, errors, double submit | `makes an Inbox task of a mail and keeps the mail`; `reports a failed "→ Inbox task"`; `reports an unexpected "→ Inbox task" failure with a generic message`; `sends "→ Inbox task" once while it runs (double click, then t)` | focused | Pass |
| AC2/AC3 SSE add/remove | `adds new captures and new mail live and drops processed items`; `live updates > drops a mail archived elsewhere after the folder refetch`; `live updates > drops a task processed in another view`; `live updates > refetches both inboxes after the event stream reconnects`; mergeNew `keeps every row of the selected source as new rows arrive (SSE refetch)` | focused | Pass |
| Load errors + retry | `load errors > shows a failed Inbox load and retries it`; `load errors > shows a failed mail load and reloads it`; `load errors > ignores the failure of the hidden source` (2 cases) | focused | Pass |
| Loading state ends after a failed load | `load errors > stops showing "Loading…" when the Inbox load failed`; `... when the mail load failed` | focused | Fail (TW-1) |
| AC3 only existing APIs | The fake `Api` throws on any operation other than `listTasks`, `listMailMessages`, `mailToTask`; every page test passes through it | focused | Pass |
| AC3 `cd web && npm run check` passes | whole web check | `cd web && npm run check` | Fail: lint and api:check pass, test:ci fails on the 2 TW-1 tests (3513 passed, 2 failed of 3515), so `ng build` did not run |
| AC4 Playwright `e2e/new/new.spec.ts` | `e2e/new/new.spec.ts` (not changed by me) | none | Not run: Docker is not available on this machine, so the e2e stack cannot start. `npm run check` includes `e2e:typecheck`, which passed, but that only type-checks the spec and does not count as running it |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/new/**/*.spec.ts'`

Result:

Fail: 51 tests, 49 passed, 2 failed (the TW-1 tests only).

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, build). Also run separately: `npx prettier --check src/app/features/new/` (Pass) and `npx ng lint --max-warnings=0` (Pass).

Result:

Fail: 168 test files, 3515 tests, 3513 passed, 2 failed (TW-1). No other test in the repository fails.

## .taskcheck

Result:

Not run. Its web part runs the same `npm run check`, which fails on TW-1 for certain. Its server part needs Docker for the DB tests, and Docker is not available on this machine.

## Bugs found

### "Loading…" never ends when one source fails to load under the All filter

Finding ID:

`TW-1`

Test:

`NewPage > load errors > stops showing "Loading…" when the Inbox load failed` and `... when the mail load failed`

Production source:

`web/src/app/features/new/new-page.ts:72-76` (the `loading` computed) and `web/src/app/features/new/new-page.html:32`

Requirement:

AC2/SPEC §5.3 New view behaviour. The error handling convention of the sibling views (Inbox page, mail list page) is to show the error instead of the loading note. The test-writer rule also requires that the loading state ends after a failure and the screen can be retried.

Expected behavior:

When the GTD Inbox or the mail list fails to load under the All filter, the page shows the error with "Try again" and no longer shows "Loading…". The rows of the source that did load should still be visible (suggested, not asserted).

Actual behavior:

`loading` stays true because `tasks.loaded()` stays false or `mail.items()` stays `null` after the failure. The page then shows the error alert and "Loading…" (`role="status"`) together, indefinitely. It also hides all rows of the source that loaded successfully, until a retry succeeds. Under the GTD Inbox or Mail filter the hidden source's failure is ignored correctly (tests pass).

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed. The final coverage run passed (exit 0), but it left out the 2 failing TW-1 tests by name with `--filter`. A run of the whole suite fails because of TW-1, and `npm run coverage` then writes no report (exit 1, no `coverage/web/` directory). The numbers below describe the current production code, so they are valid as diagnostic numbers. They are not a verified, all-tests-passing measurement.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (every task-changed file under `features/new` is new, so whole-file coverage equals changed-line coverage there; `app.routes.ts` and `nav-sections.ts` are fully covered anyway).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-new-view; base 614a515158d13f77a258d0eb380156a4f2a2bc39; HEAD 5784aa66e63e1d99422e5be40a508421b33fb3ec; commits 98d81e0d and 5784aa66 as given by the parent, checked with `git diff --stat 614a5151..HEAD` (12 files, all under `web/`)
- Source/test snapshot identifier (including uncommitted inputs): SHA-256 before and after the run, unchanged: new-feed.ts f8298357…; new-page.ts a72fc82a…; new-page.html 522bd1e2…; new-page.scss 62f5dcf1…; new.routes.ts 4c0da351…; app.routes.ts c20c6e01…; nav-sections.ts a96b4f3f…; new-page.spec.ts 5fda12b2… (uncommitted); new-feed.spec.ts c3b1a173… (uncommitted)
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/new/{new-feed.ts,new-page.ts,new-page.html,new-page.scss,new.routes.ts}`, `web/src/app/app.routes.ts`, `web/src/app/shell/nav-sections.ts`. Excluded: `*.spec.ts`, `web/e2e/new/new.spec.ts` (tests); no renamed or deleted files
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 5.0.5 (via `@angular/build:unit-test`); working directory `web/`; `npx ng test --watch=false --filter='^(?!.*stops showing "Loading…").*$' --coverage --coverage-include='src/app/features/new/**' --coverage-include='src/app/app.routes.ts' --coverage-include='src/app/shell/nav-sections.ts' --coverage-reporters=text --coverage-reporters=json-summary --coverage-reporters=json`; whole suite except the 2 TW-1 tests (3513 passed, 2 skipped by the filter)
- Run start/completion time and exit status: 2026-10-25T22:46:33Z to 22:47:15Z, exit 0
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 b2c9389e2dd33ca23cf01a45405f8168db25b710c35fde8e051fa6f7db0dd15c), `web/coverage/web/coverage-final.json` (sha256 d51b302cb7856a00a717cf3097651ebf6e45c4176fd5bdeb7cfff52d07509c57)
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report was written by this run (the earlier failing `npm run coverage` had left no `coverage/web`), and the snapshot hashes match before and after
- Final run after latest test additions and production fixes: yes, after the latest test additions. No production fix has been made yet; after the TW-1 fix, coverage must be measured again

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/new/new-feed.ts` | 19/19 (100%) | 21/21 (100%) | 6/6 (100%) | 16/16 (100%) |
| `web/src/app/features/new/new-page.ts` | 116/117 (99.1%) | 84/85 (98.8%) | 31/32 (96.9%) | 89/89 (100%) |
| `web/src/app/features/new/new-page.html` | 109/109 (100%) | 20/20 (100%) | 5/5 (100%) | 69/69 (100%) |
| `web/src/app/features/new/new-page.scss` | N/A | N/A | N/A | N/A |
| `web/src/app/features/new/new.routes.ts` | 1/2 (50%) | N/A | 0/1 (0%) | 1/2 (50%) |
| `web/src/app/app.routes.ts` | 20/20 (100%) | N/A | 19/19 (100%) | 20/20 (100%) |
| `web/src/app/shell/nav-sections.ts` | 3/3 (100%) | N/A | 1/1 (100%) | 2/2 (100%) |
| **Aggregate (summed counters, all 7 files)** | 268/270 (99.3%) | 125/126 (99.2%) | 62/64 (96.9%) | 197/198 (99.5%) |

No measured files are missing. The scss file has no executable code.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `new-page.ts:40` (1 statement, 1 branch, 1 function in the `@Component` metadata, `providers`) | None; this is decorator metadata emitted by the compiler | Left uncovered: not reachable as behavior |
| `new.routes.ts:7` (lazy `loadComponent` callback) | The route `/new` loads the page | Not covered by unit tests: unit tests mount the page directly. The `app.spec.ts` route check covers the route entry. AC4 e2e exercises the lazy load but was not run (no Docker) |

Before my additions (first scoped run, New specs only), `new-page.ts` had 114/117 statements and 80/85 branches, and `new-page.html` had 108/109 statements and 19/20 branches. The gaps were the no-subject fallback, the snippet, the date formats and choosing the shown filter again. Tests now cover all of them.

## Unable to test

- AC4 Playwright `e2e/new/new.spec.ts` against the e2e stack: Docker is not available on this machine. This needs follow-up: run it where Docker is available.
- `.taskcheck`: not run, for the reasons above.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. TW-1 (Low): "Loading…" stays next to the load error and hides the other source's rows under the All filter. Everything else in criteria 1-3 is covered and passes. AC4 (e2e) was not run because Docker is not available.
