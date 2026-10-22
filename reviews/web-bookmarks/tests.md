# Test Report

Date: 2026-10-22

Task: web-bookmarks - Bookmarks UI (second pass, final revision after the fixer)

Scope: `7e5c50644d247bc7ee46c1d4279a5c815d2f42bb..e51757341f167a4570428ec253c4bce28d2b464f` on branch `main` (task commits `a77cc233` implementation, `1bf79432` tests and reports, `e5175734` fixer changes for CR-1..CR-4 and TW-2), plus one uncommitted test file (`bookmarks-page.spec.ts`, this pass). No uncommitted production changes.

Requirements:
- `tasks/web-bookmarks.md` acceptance criteria and recorded assumptions (snapshot via `/files/{id}/preview`, search left to global search, no third-party favicons, `app.spec.ts`/`app.i18n.spec.ts` updated outside touches)
- `SPEC.md` §8.4 (Bookmarks)
- `docs/ARCHITECTURE.md` "Coverage"
- `AGENTS.md` project notes
- `reviews/web-bookmarks/fixes.md` (new logic to check: letter tile instead of favicons, insert of unlisted bookmarks on change events, `changeSeq`/per-id race guards, reader back link keeps view/tag)
- Server ordering of the list: `BookmarkService` sorts `createdAt desc, id desc` (used to check the client's `insert()` order)

## Test files changed

- `web/src/app/features/bookmarks/bookmarks-page.spec.ts` (this pass, uncommitted)

## Tests added or updated

Count: 5 added in this pass (describe `gaps` of `bookmarks-page.spec.ts`), none changed or removed.

- `places a bookmark that entered the view by id when it was created at the same time` - `insert()` tie-break (same `createdAt`, id desc as on the server)
- `puts a bookmark that entered the view between the loaded ones while more pages exist` - `insert()` in the middle while `nextCursor` is set
- `does not add a refreshed bookmark that answers after the view changed` - `refreshOne()` `loadSeq` guard on the new insert path
- `does not load a created bookmark while the list loads` - `CREATED` event with no list shown: no single-bookmark GET, the list load is the truth
- `shows a bookmark added here once when its CREATED event loads it while more pages exist` - add + `CREATED` refresh race (per-id `changedAt` guard / dedupe), no duplicate row

The fixer's own tests for CR-1..CR-4 (12 page tests, 2 reader tests) were reviewed against the fixes and kept as they are.

## Requirement coverage

Focused command (from `web/`): `npx ng test --watch=false --include 'src/app/features/bookmarks/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Read-later queue | `shows the read-later queue with title, host, icon, tags and pending metadata`, `marks a bookmark read: it leaves the queue`, `takes a bookmark out of the queue and moves one to the Trash`, `drops a changed bookmark that left the queue (read elsewhere)`, service `lists the read-later queue ...` | focused command | Pass |
| AC1 All by tag | `lists all bookmarks of a tag through the query string`, `shows the empty texts of the all view and of a tag`, `does not reload when the query string names the same view`, `ignores a list that answers for an earlier view`, `puts a bookmark into the queue in the all view, where it stays`, `adds a changed bookmark at the end when no more pages exist` (tag match ignoring case) | focused command | Pass |
| AC1 Add by URL | `adds a URL to the queue with its tags`, `refuses an address that is not a web address ...`, `adds a bookmark with "Read later" unchecked in the all view`, `does not show a bookmark added with "Read later" unchecked in the queue`, `shows an added bookmark in a tag view only when it has that tag, ignoring case`, `shows the server error of an add and keeps the address`, `shows an added bookmark once when its CREATED event reloaded the list first`, `shows a bookmark added here once when its CREATED event loads it while more pages exist` (new), display-helper tests | focused command | Pass |
| AC1 Mark read | `marks a bookmark read ...`, `marks a read bookmark unread in the all view ...`, `shows a conflict next to the bookmark ...`, `drops the row without an error when a row action answers 404`, `shows a server error next to the bookmark ...`, `drops the bookmark when it is gone while reloading after a conflict`, reader mark-read/conflict tests | focused command | Pass |
| AC1 Snapshot view | reader `renders a Markdown snapshot, safely ...`, `shows a plain snapshot as text and says when it is cut`, snapshot error/retry/stale-id tests, `leads back to the view and tag it was opened from`, `leads back to the read-later queue when opened from it`, page `opens the reader with the view and tag, so it can lead back to them` | focused command | Pass |
| CR-1 No third-party favicon (task assumption) | `shows the read-later queue ...` (no `<img>`, letter `E` for a bookmark with a favicon) | focused command | Pass |
| CR-2 Live updates insert bookmarks that entered the view | `loads only the created bookmark while more pages are loaded, and puts it on top`, `does not put a created bookmark older than the last shown one on the loaded pages`, `loads only the created bookmark while a change of another one is running`, `adds a changed bookmark that entered the view, in its place by date`, `adds a changed bookmark at the end when no more pages exist`, `does not add a changed bookmark that does not belong to the view`, `does not load a changed bookmark while the list loads`, plus new `places a bookmark ... same time`, `puts a bookmark ... between the loaded ones while more pages exist`, `does not add a refreshed bookmark that answers after the view changed`, `does not load a created bookmark while the list loads` | focused command | Pass |
| CR-3 Quiet reload / refresh must not undo a local change | `drops a quiet reload that overlaps a change made here`, `drops a quiet reload that overlaps an add, a delete or a queue change`, `drops a refreshed bookmark that was deleted here meanwhile`, `keeps a refreshed bookmark when another bookmark was changed here meanwhile`, `ignores a 404 of a refreshed bookmark that was changed here meanwhile`, `ignores a refreshed bookmark when a change of it started meanwhile`, `does not reload quietly while a change of a bookmark is running` | focused command | Pass |
| Paging | `loads more bookmarks from the cursor`, `toasts a failed "Show more" and keeps the button`, `ignores a quiet reload and a "Show more" that answer after the view changed` | focused command | Pass |
| /bookmarks route is the real page | `App > lazy-loads the bookmarks page at /bookmarks` (`app.spec.ts`, committed in `1bf79432`) | `cd web && npm run check` | Pass |
| AC2 Vitest tests cover queue actions; `cd web && npm run check` passes | all of the above | `cd web && npm run check` | Pass (exit 0) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/bookmarks/**/*.spec.ts'`

Result:

Pass - 4 files, 78 tests (73 at `e5175734` + 5 added in this pass).

## Full test execution

Command:

`cd web && npm run check` (api:check, `ng lint --max-warnings=0`, e2e typecheck, `ng test --watch=false`, `ng build`)

Result:

Pass - exit 0; "All files pass linting."; 149 test files, 3014 tests passed; build OK. The only build warning is the pre-existing initial bundle budget (672.04 kB vs 500 kB), not caused by this task. The `bookmarks-page.scss` budget warning (TW-2 in the previous pass) is gone.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` also runs the server Gradle check and the Android build; its web part (`npm run check`) was run directly and passed. The parent runs `.taskcheck` through `tasks.py done`.

## Bugs found

None in this pass. Previous findings: TW-1 (stale `app.spec.ts`) was resolved by committing the test update in `1bf79432`; TW-2 (style budget) was fixed in `e5175734` and the warning no longer appears.

Observation, not a defect (no failing test, no fix requested): `insert()` compares `createdAt` with `Date.parse`, i.e. at millisecond precision, while the server sorts by the full-precision `Instant`. Two bookmarks created within the same millisecond could be placed by id instead of by their microsecond order until the next reload. This is cosmetic and practically unreachable for a single user.

## Coverage

Status: verified (fresh full-suite run on the final production revision `e5175734` plus this pass's test additions; exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All production files in scope are new in this task (`bookmarks.routes.ts` was a placeholder and is rewritten), so whole-file coverage is effectively coverage of the task's lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-bookmarks`; base `7e5c50644d247bc7ee46c1d4279a5c815d2f42bb` (supplied by the parent); HEAD `e51757341f167a4570428ec253c4bce28d2b464f`; `git log 7e5c5064..HEAD` lists exactly the three task commits `a77cc233`, `1bf79432`, `e5175734`; scope from `git diff --stat 7e5c5064..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `e5175734` plus uncommitted `bookmarks-page.spec.ts` sha256 `fdcfc1d2a38a0e483b6ce87cf721b55a202598bf361eb5ac08f67e554a5f0bc0`. Production sha256 (identical to HEAD): `bookmarks-page.ts` 56cb75e540b0698090bd5b6d45a22051ced922932adc0439f1d5c5c160cd9839, `bookmark-reader.ts` ab8c25b81a3c649ad8941e21522a26237993eb880e8c8f3c3719866a1141333c, `bookmarks.service.ts` c6c18939e863c573394ba8ba4c2f098e83d490ed5fc0c12e67ec238535384281, `bookmark-display.ts` a062e112f277b1f1cf12308cd594b30b503fcf0ad3902183d12f21f0b649ea88, `bookmarks.routes.ts` 31a25e64c1bc6c9979ad4a745de11ccdcd4edee39d88242650359eeb40e2c76f, `bookmarks-page.html` 6f6c6edb6694119ad2be1838df3e88ebd71d2ebaecac6d3ab91f31b49f2e21ca, `bookmark-reader.html` b1318d1a9c0ff3ff902ee53e6edf82ced99e933b3938a665207a68f6cb3762ac.
- Exact included files: `web/src/app/features/bookmarks/bookmarks-page.ts`, `bookmark-reader.ts`, `bookmarks.service.ts`, `bookmark-display.ts`, `bookmarks.routes.ts`. Excluded: `testing.ts` (test fixture helper), `*.spec.ts`, `app.spec.ts`, `app.i18n.spec.ts` (tests), `*.scss` (styles, no executable behavior), `*.html` templates (compiled into the components; V8 does not report them as separate entries). No renamed or deleted files. No scoped file was missing from the report.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` (v8); `npm run coverage` (`ng test --watch=false --coverage` with text-summary, json-summary, json, lcov reporters); working directory `web/`; full suite (149 test files). Per-file counters read from `coverage-summary.json` / `coverage-final.json`.
- Run start/completion time and exit status: started 2026-10-22T02:07:00Z, reports written 2026-10-22T02:07:58Z; exit 0; 149 files / 3014 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 d2343f92bc714d7d8ad91b17728afa699424f29529e56e16b8c6febd6207e226; `web/coverage/web/coverage-final.json` sha256 8d509301f4bd78a7b9d66b0149d24aabeda3df066d396837f8e7b27caad155fc; `web/coverage/web/lcov.info` sha256 19604b0558fb621aeee50e400ce25348c57adde4ed8c6e3498682fc8cedfc82d.
- Evidence outputs are fresh and inputs stayed unchanged: the report files carry this run's timestamp (Vitest rewrites `coverage/`); production and test hashes were identical before and after the run.
- Final run after latest test additions and production fixes: yes (after the fixer's `e5175734` and after the 5 tests of this pass; no later changes).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/bookmarks/bookmarks-page.ts` | 207/211 (98.10%) | 125/136 (91.91%) | 51/51 (100%) | 173/173 (100%) |
| `web/src/app/features/bookmarks/bookmark-reader.ts` | 52/53 (98.11%) | 29/34 (85.29%) | 7/7 (100%) | 43/43 (100%) |
| `web/src/app/features/bookmarks/bookmarks.service.ts` | 18/18 (100%) | 14/15 (93.33%) | 8/8 (100%) | 15/15 (100%) |
| `web/src/app/features/bookmarks/bookmark-display.ts` | 25/26 (96.15%) | 24/24 (100%) | 5/5 (100%) | 19/20 (95.00%) |
| `web/src/app/features/bookmarks/bookmarks.routes.ts` | 3/3 (100%) | N/A (0/0) | 2/2 (100%) | 3/3 (100%) |
| **Aggregate (5 files above, summed counters)** | 305/311 (98.07%) | 192/209 (91.87%) | 73/73 (100%) | 253/254 (99.61%) |

Aggregate excludes `testing.ts`, specs, templates and styles as listed above; no scoped file is unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `bookmarks-page.ts:266` (`insert()` returns `current` when the list is null or the id is already shown) | Defensive duplicate guard | Not reachable: `refreshOne` checks `shown` and calls `insert` synchronously after it; the list is non-null there. Duplicate behavior is asserted end to end (`shows a bookmark added here once ...`). Justified gap. |
| `bookmarks-page.ts:120`, `:152`, `:337` (`loadMore`, `add`, `run` re-entry guards) | Double-click protection | Not reachable through the UI: the buttons are disabled while running (tests assert the disabled state). Justified gap. |
| `bookmarks-page.ts:114`, `:132` (failure that answers for an earlier view) | Ordering | The success variants of the same `loadSeq` guards are tested. Low risk; left. |
| `bookmarks-page.ts:128`, `:249`, `:257` (`?? []` / `?? null` when the list is null) | Defensive null handling | Rows exist only when the list is loaded. Justified gap. |
| `bookmarks-page.ts:367` (non-404 error while reloading after a conflict) | The row error stays shown | Pre-existing logic, unchanged by the fixer; the reader's equivalent is tested. Low risk; left. |
| `bookmarks-page.ts:34`, `bookmark-reader.ts:31` | Decorator branches emitted by the compiler | Not behavior. |
| `bookmark-reader.ts:85` (busy guard), `:76` (stale snapshot error), `:56` (`?? ''` for a missing `:id`), `:105` (non-ApiError message) | Defensive / ordering | Unchanged since the first pass; see justification there: button disabled while busy, success variant of the stale guard tested, route always has `:id`, HTTP errors are `ApiError`. |
| `bookmarks.service.ts:91` (`?? ''` for a missing `Content-Type`) | Plain-text fallback | Behavior asserted (`encodes the file id and treats a snapshot without a type as plain text`), but the testing backend still sets a type, so V8 counts the branch as missed. Accepted. |
| `bookmark-display.ts:29` (`catch` in `normalizeUrl`) | Input that passes the regex checks but fails `new URL` | Pre-filtered by the earlier checks; low risk. Left. |

All branches of the logic the fixer added (`insert`, `noteChange`/`changedAt`, the `changeSeq` check in `reloadQuietly`, the `created` path of `reloadQuietly`, `viewParams`) are covered except the defensive `insert()` guard above.

## Unable to test

- Browser (Playwright) flows for bookmarks: not part of the acceptance criteria; not run.
- `.taskcheck`: not run by the test-writer (server/Android parts); its web part passed. Follow-up: the parent runs it via `tasks.py done`.
- Real CSP enforcement of the removed favicons: only checked in jsdom by asserting that no `<img>` is rendered.

## Summary

Tests added and all validation passed. Focused 78/78, `npm run check` exit 0 (3014/3014 tests, lint clean, build OK with only the pre-existing bundle budget warning). Task-scoped coverage: statements 305/311, branches 192/209, functions 73/73, lines 253/254. No production defects found on the final revision `e5175734`. The parent must commit the updated `bookmarks-page.spec.ts` together with this report.
