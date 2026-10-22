# Test Report

Date: 2026-10-22

Task: web-bookmarks - Bookmarks UI

Scope: commit range `1c092335a2909e97cec38e57d680049129ca72ef..7dadda74fb47f73d4c8b1a6ed52aa3cf986865cf` (one task commit, `7dadda74 web-bookmarks: read-later queue, bookmarks by tag, add by URL, mark read, snapshot reader`), branch `main`, plus the uncommitted test-file changes listed below. No uncommitted production changes.

Requirements:
- `tasks/web-bookmarks.md` acceptance criteria and recorded assumptions (snapshot via `/files/{id}/preview`, search left to global search)
- `SPEC.md` §8.4 (Bookmarks), §4.2 (ETag/If-Match, Problem Details)
- `docs/ARCHITECTURE.md` "Coverage"
- `AGENTS.md` project notes
- Parent-supplied gap list (quiet reload, add with "Read later" unchecked, tag-filtered add, stale events, 404 on row action, snapshot error and conflict in the reader)

## Test files changed

- `web/src/app/features/bookmarks/bookmarks-page.spec.ts`
- `web/src/app/features/bookmarks/bookmark-reader.spec.ts`
- `web/src/app/features/bookmarks/bookmarks.service.spec.ts`
- `web/src/app/app.spec.ts` (outside the task's `touches`; see "Bugs found" TW-1)

## Tests added or updated

Count: 35 added, 1 updated (the `BUILT` list in `app.spec.ts`).

- `bookmarks-page.spec.ts`: 25 added (describe `gaps`)
- `bookmark-reader.spec.ts`: 7 added (describe `gaps`)
- `bookmarks.service.spec.ts`: 2 added
- `app.spec.ts`: 1 added (`lazy-loads the bookmarks page at /bookmarks`), `/bookmarks` added to `BUILT` so the coming-soon case no longer runs for it

## Requirement coverage

Focused command (from `web/`): `npx ng test --watch=false --include 'src/app/features/bookmarks/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Read-later queue | `shows the read-later queue ...`, `marks a bookmark read: it leaves the queue`, `takes a bookmark out of the queue ...`, `drops a changed bookmark that left the queue (read elsewhere)`, `lists the read-later queue as unread bookmarks ...` (service) | focused command | Pass |
| AC1 All bookmarks by tag | `lists all bookmarks of a tag through the query string`, `shows the empty texts of the all view and of a tag`, `does not reload when the query string names the same view`, `ignores a list that answers for an earlier view`, `puts a bookmark into the queue in the all view, where it stays`, `lists all bookmarks of a tag from a cursor` (service) | focused command | Pass |
| AC1 Add by URL | `adds a URL to the queue with its tags`, `refuses an address that is not a web address ...`, `adds a bookmark with "Read later" unchecked in the all view`, `does not show a bookmark added with "Read later" unchecked in the queue`, `shows an added bookmark in a tag view only when it has that tag, ignoring case`, `shows the server error of an add and keeps the address`, `shows an added bookmark once when its CREATED event reloaded the list first`, display-helper tests | focused command | Pass |
| AC1 Mark read | `marks a bookmark read ...`, `marks a read bookmark unread in the all view ...`, `shows a conflict next to the bookmark ...`, `drops the row without an error when a row action answers 404`, `shows a server error next to the bookmark without reloading it, and clears it on the next try`, `drops the bookmark when it is gone while reloading after a conflict`, reader `says when there is no snapshot, and marks the bookmark read`, reader `toasts a conflict when marking read and shows the current version`, `keeps the bookmark when reloading after a conflict fails`, `toasts another failure when marking read without reloading` | focused command | Pass |
| AC1 Snapshot view | reader `renders a Markdown snapshot, safely ...`, `shows a plain snapshot as text and says when it is cut`, `shows the bookmark with an error when its snapshot cannot be loaded`, `tries loading the bookmark again after an error`, `ignores a bookmark and a snapshot that answer for the previous id`, `ignores a failed load that answers for the previous id`, service `loads a snapshot ...`, `encodes the file id and treats a snapshot without a type as plain text` | focused command | Pass |
| Live updates (SPEC §8.4 metadata arrives in background) | `replaces a bookmark when its fetched metadata arrives`, `reloads the first page quietly on a CREATED event and on a resync`, `keeps the list when a quiet reload fails`, `does not reload the first page quietly while more pages are loaded`, `does not reload quietly while a change of a bookmark is running`, `ignores a change event whose bookmark is not newer than the shown one`, `does not load a changed bookmark that is not shown`, `drops a changed bookmark that has gone meanwhile (404)`, `keeps a bookmark whose refresh fails with another error than 404`, `does not load a bookmark from a change event while it is being changed here`, `ignores a refreshed bookmark when a change of it started meanwhile`, `ignores a quiet reload and a "Show more" that answer after the view changed` | focused command | Pass |
| Paging | `loads more bookmarks from the cursor`, `toasts a failed "Show more" and keeps the button` | focused command | Pass |
| /bookmarks route is the real page (no placeholder) | `App > lazy-loads the bookmarks page at /bookmarks` | `npx ng test --watch=false --include src/app/app.spec.ts` | Pass (44 tests) |
| AC2 Vitest tests cover queue actions; `cd web && npm run check` passes | all of the above | `cd web && npm run check` | Pass (exit 0; 142 files / 2889 tests; lint clean; build OK with budget warnings, see TW-2). Without the `app.spec.ts` update, HEAD `7dadda74` fails (TW-1). |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/bookmarks/**/*.spec.ts'`

Result:

Pass - 4 files, 61 tests (was 27 before this report).

## Full test execution

Command:

`cd web && npm run check` (api:check, `ng lint --max-warnings=0`, e2e typecheck, `ng test --watch=false`, `ng build`)

Result:

Pass - exit 0, 142 test files, 2889 tests passed. Build prints two budget WARNINGs (initial bundle 671 kB, pre-existing; `bookmarks-page.scss` 4.34 kB over the 4 kB component-style budget, new, TW-2).

Before the `app.spec.ts` update, the first full run (`npm run coverage`) failed: `src/app/app.spec.ts > App > lazy-loads a coming soon page for /bookmarks` (expected the `app-coming-soon` heading "Bookmarks", got undefined).

## .taskcheck

Result:

Not run: `sh scripts/check.sh` also runs the server Gradle check, the Android build and may run `npm ci`; the web part it runs (`npm run -s check`) was run directly and passed. The parent runs `.taskcheck` through `tasks.py done`.

## Bugs found

### Task commit breaks the existing App shell test for /bookmarks

Finding ID:

`TW-1`

Test:

`web/src/app/app.spec.ts > App > lazy-loads a coming soon page for /bookmarks`

Production source:

`web/src/app/features/bookmarks/bookmarks.routes.ts:3` (the route no longer shows the coming-soon placeholder, as intended)

Requirement:

AC2: `cd web && npm run check` passes.

Expected behavior:

`npm run check` passes at the task commit.

Actual behavior:

At `7dadda74` the shell test still expects the placeholder at `/bookmarks`, so `ng test` (and therefore `npm run check` / `.taskcheck`) fails with 1 failed test. This is a stale test, not a production defect: the task correctly replaced the placeholder. The test-writer updated `app.spec.ts` (added `/bookmarks` to `BUILT` and a positive test that `/bookmarks` lazy-loads `app-bookmarks-page` with title "Bookmarks"), in the same way the task already changed `app.i18n.spec.ts`. The parent must commit this change and should record that `app.spec.ts` is outside the task's `touches`, as was done for `app.i18n.spec.ts`.

Severity:

High (blocks AC2 and `.taskcheck` until the test change is committed)

Fix required:

`No production fix - the parent worker must commit the updated web/src/app/app.spec.ts.`

### Component style budget warning for bookmarks-page.scss

Finding ID:

`TW-2`

Test:

`cd web && npm run check` (the `ng build` step)

Production source:

`web/src/app/features/bookmarks/bookmarks-page.scss`

Requirement:

AGENTS.md: "Lint is part of the contract: fix warnings, don't suppress them."

Expected behavior:

The build adds no new warnings.

Actual behavior:

`WARNING: src/app/features/bookmarks/bookmarks-page.scss exceeded maximum budget. Budget 4.00 kB was not met by 338 bytes with a total of 4.34 kB.` The build still succeeds (exit 0), so AC2 is met; the warning is new with this task.

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer (trim the stylesheet below 4 kB), or the parent decides and records why the warning is accepted.`

## Coverage

Status: verified (fresh run after the last test addition; no production changes since the task commit).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (all production files in scope are new in this task, so whole-file coverage equals coverage of the added lines for these files; no separate changed-line tooling used).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-bookmarks`; base `1c092335a2909e97cec38e57d680049129ca72ef` (supplied by the parent); HEAD `7dadda74fb47f73d4c8b1a6ed52aa3cf986865cf`; `git log 1c092335..HEAD` shows only the one task commit; scope from `git diff --stat 1c092335..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `7dadda74` plus uncommitted test files with sha256: `bookmarks-page.spec.ts` 1aeea1a7...d312e3, `bookmark-reader.spec.ts` 46771140...9094d, `bookmarks.service.spec.ts` 7525feb1...607e, `app.spec.ts` 928085d5...82994. Production sha256 unchanged from HEAD: `bookmarks-page.ts` 2083f277...c514, `bookmark-reader.ts` ab8c25b8...333c, `bookmarks.service.ts` c6c18939...4281, `bookmark-display.ts` e7b76fe1...6e04, `bookmarks.routes.ts` 31a25e64...e76f.
- Exact included files: `web/src/app/features/bookmarks/bookmarks-page.ts`, `bookmark-reader.ts`, `bookmarks.service.ts`, `bookmark-display.ts`, `bookmarks.routes.ts`. Excluded: `testing.ts` (test fixture helper), `*.spec.ts`, `app.i18n.spec.ts` (tests), `*.scss` (styles, no executable behavior), `*.html` templates (compiled into the components; V8 coverage reports them through the component `.ts` files, not as separate entries). No renamed or deleted files. No scoped file was missing from the report.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` (v8), Node v24.21.0; `npm run coverage` (`ng test --watch=false --coverage` with text-summary, json-summary, json, lcov reporters); working directory `web/`; full suite (all 142 test files).
- Run start/completion time and exit status: started 2026-10-22T01:56:11Z, reports written 2026-10-22T01:56:57Z (03:56:57 +0200); exit 0; 142 files / 2889 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 23346a96307a2302390423b18653f432e39c2519f8d722d96a7dafcc9c060aee; `web/coverage/web/coverage-final.json` sha256 5e9daec4367cff918fd6b56659e00bdf6bb5128c6f10f81f9c08206329f77390; `web/coverage/web/lcov.info` sha256 85fc6d967eaff9ee6b1a40a9b56383b95af3cc2d7bbef1fb88f0ee975bd97508.
- Evidence outputs are fresh and inputs stayed unchanged: report files are timestamped by this run (Vitest rewrites `coverage/`); production and test hashes were identical before and after the run.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/bookmarks/bookmarks-page.ts` | 177/180 (98.33%) | 104/114 (91.23%) | 44/44 (100%) | 147/147 (100%) |
| `web/src/app/features/bookmarks/bookmark-reader.ts` | 52/53 (98.11%) | 29/34 (85.29%) | 7/7 (100%) | 43/43 (100%) |
| `web/src/app/features/bookmarks/bookmarks.service.ts` | 18/18 (100%) | 14/15 (93.33%) | 8/8 (100%) | 15/15 (100%) |
| `web/src/app/features/bookmarks/bookmark-display.ts` | 26/27 (96.30%) | 28/28 (100%) | 6/6 (100%) | 20/21 (95.24%) |
| `web/src/app/features/bookmarks/bookmarks.routes.ts` | 3/3 (100%) | N/A (0/0) | 2/2 (100%) | 3/3 (100%) |
| **Aggregate (5 files above, summed counters)** | 276/281 (98.22%) | 175/191 (91.62%) | 67/67 (100%) | 228/229 (99.56%) |

Aggregate excludes `testing.ts` (test helper) and templates/styles as listed above; no scoped file is unmeasured.

Before the tests in this report (same command, run 01:50, full suite): `bookmarks-page.ts` statements 173/180, branches 98/114; `bookmark-reader.ts` statements 51/53, branches 26/34.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `bookmarks-page.ts:102` (loadMore guard), `:134` (add guard), `:281` (row busy guard) | Double-click protection | Not reachable through the UI: the buttons are disabled while running (tests assert the disabled state). Justified gap. |
| `bookmarks-page.ts:96` (stale load error), `:114` (stale "Show more" error) | Ordering: a failure for an earlier view must be ignored | The success variants of the same guards are tested (`ignores a list that answers for an earlier view`, `ignores a quiet reload and a "Show more" ...`). Low risk; left. |
| `bookmarks-page.ts:110`, `:228`, `:236` (`?? []` / `?? null` when the list is null) | Defensive null handling | Not reachable in normal flow (rows exist only when the list is loaded). Justified gap. |
| `bookmarks-page.ts:307` (non-404 error while reloading after a conflict) | The error stays shown | Low risk; the reader's equivalent is tested. Left. |
| `bookmarks-page.ts:27`, `bookmark-reader.ts:31` | Decorator branches emitted by the compiler | Not behavior. |
| `bookmark-reader.ts:85` (busy guard) | Double-click protection | Button disabled while busy (asserted). Justified gap. |
| `bookmark-reader.ts:76` (stale snapshot error) | Ordering | Success variant tested (`ignores a bookmark and a snapshot that answer for the previous id`). Left. |
| `bookmark-reader.ts:56` (`?? ''` for a missing `:id`), `:105` (non-ApiError message) | Defensive | The route always has `:id`; HTTP errors are always `ApiError` through the interceptor. Justified gap. |
| `bookmarks.service.ts:91` (`?? ''` for a missing `Content-Type`) | Plain text fallback | `encodes the file id and treats a snapshot without a type as plain text` flushes without an explicit type, but the testing backend still sets one, so V8 counts the branch as missed. Behavior asserted; gap accepted. |
| `bookmark-display.ts:29` (`catch` in `normalizeUrl`) | Input that passes the regex checks but fails `new URL` | Pre-filtered by the earlier checks; low risk. Left. |

## Unable to test

- Browser (Playwright) flows for bookmarks: not part of the acceptance criteria and not run here.
- `.taskcheck` (`sh scripts/check.sh`): not run by the test-writer (server/Android parts and possible `npm ci`); its web part passed. Follow-up: the parent runs it via `tasks.py done`.
- Visual design and real HTML sanitization in a browser: covered only through jsdom (`script` tag removed from rendered Markdown).

## Summary

Tests added and all validation passed (focused 61/61, `npm run check` 2889/2889). No production behavior defects were found. The task commit alone fails `npm run check` because of the stale `app.spec.ts` placeholder test (TW-1); the updated `app.spec.ts` must be committed with the task. TW-2 (new style-budget build warning) is a low-severity item for the parent to fix or accept.
