# Test Report

Date: 2026-10-20

Task: web-trash — Trash view (web/src/app/features/trash)

Scope: `git diff 12d11ecb4e8412f4a7eee75af4639780698259c8..13a477634db125d03f771c3c836925784febfc55`
(one task commit, 13a4776 "web-trash: Trash view with restore and permanent delete"), plus the
uncommitted test-only edits listed below. Branch `main`, HEAD 13a477634db125d03f771c3c836925784febfc55.

Requirements:
- `tasks/web-trash.md` acceptance criteria and recorded assumptions (snackbar text, 404 drops the row,
  'purged in N days' rounding up / 'purged today' / urgent at <= 3 days, 'Show more' paging of 50)
- SPEC.md §4.2 (Trash: list, restore, permanent delete)
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md, AGENTS.md

## Test files changed

- `web/src/app/features/trash/trash-page.spec.ts` (uncommitted additions, this run)
- `web/src/app/features/trash/trash.service.spec.ts` (uncommitted additions, this run)

Already in the task commit (reviewed, unchanged by this run): the original
`trash-page.spec.ts` / `trash.service.spec.ts` tests and `web/src/app/app.spec.ts` (/trash moved to
the built routes).

This run continued an interrupted earlier test-writer run and kept all of its edits. The test
'does nothing when the confirmation is dismissed with Escape', reported as failing earlier, passes
on the current snapshot: 1 focused run with app.spec, 5 repeated focused runs, `npm run check`,
`npm run coverage` and `.taskcheck` (8 runs, 0 failures). It dispatches a bubbling `keydown` Escape
event from inside the dialog overlay, which reaches the CDK `OverlayKeyboardDispatcher` listener on
`document.body`. If Escape stopped closing the dialog, `vi.waitFor` would time out and the test
would fail, so it does not pass vacuously. No change to it was needed and it shows no production bug.

## Tests added or updated

Count: 16 (14 in `trash-page.spec.ts`, 2 in `trash.service.spec.ts`)

trash-page.spec.ts:
- marks the countdown urgent from 3 days left on, not at 4
- labels the actions of each row with its title
- does nothing when the confirmation is dismissed with Escape
- disables the row while the permanent delete runs
- drops an item whose permanent delete answers 404 without an error
- runs actions on different rows independently, whatever order they answer in
- clears the previous action error when the next action starts
- shows the unreachable server message when the restore cannot reach it
- shows a generic message for an unexpected failure
- moves the focus to the previous row when the last row is restored
- leaves the focus alone when the user moved it elsewhere during the restore
- moves the focus to the heading when the only row is restored
- disables "Show more" while the next page loads and drops it after the last page
- retries the next page after a failure

trash.service.spec.ts:
- rejects a failed restore with the problem detail and does not toast it
- rejects a failed list without toasting it

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/trash/**/*.spec.ts' --include='src/app/app.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Trash lists deleted items of all types (server order) | shows a loading state, then lists items of all types in server order; app.spec /trash checks | F | Pass |
| AC1: deletion date and 'purged in N days' (rounded up, 'purged today' at 0, urgent at <= 3) | daysUntilPurge rounds partial days up / is 0 once the purge is due; shows the deletion date and the days until the purge; marks items purged soon; marks the countdown urgent from 3 days left on, not at 4 | F | Pass |
| AC1: empty and load-error states with retry | says so when the Trash is empty; shows a load error with a retry; rejects a failed list without toasting it | F | Pass |
| AC1: restore (snackbar, row dropped, errors, 404, focus) | restores an item, drops it from the list and says so; keeps the item and shows the error when the restore fails; drops an item that is no longer in the Trash (404) without an error; shows the unreachable server message ...; shows a generic message for an unexpected failure; clears the previous action error ...; moves the focus to the next row / previous row / heading ...; leaves the focus alone ...; restores an item with POST; rejects a failed restore with the problem detail ... | F | Pass |
| AC1: delete permanently with confirmation | asks before deleting permanently and does nothing on cancel; does nothing when the confirmation is dismissed with Escape; deletes permanently after the confirmation; disables the row while the permanent delete runs; keeps the item and shows the error when the permanent delete fails; drops an item whose permanent delete answers 404 without an error; deletes an item permanently with DELETE; rejects with the server problem | F | Pass |
| Paging via 'Show more' (assumption, 50 per page) | lists a page with the page size ...; passes the cursor of the next page; loads more pages with the cursor and skips items already shown; shows an error when the next page fails and keeps the button; disables "Show more" while the next page loads and drops it after the last page; retries the next page after a failure | F | Pass |
| Overlapping actions on different rows, answering out of order | runs actions on different rows independently, whatever order they answer in | F | Pass |
| Accessible action labels | labels the actions of each row with its title | F | Pass |
| AC2: Vitest component/service tests cover restore and delete flows | all of the above | F | Pass (85 tests, 3 files) |
| AC2: `cd web && npm run check` passes | whole web suite, lint, api check, e2e typecheck, build | `cd web && npm run check` | Pass (exit 0; 43 files, 668 tests; build only warns about the existing initial-bundle budget) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/trash/**/*.spec.ts' --include='src/app/app.spec.ts'`

Result:

Pass: 3 test files, 85 tests. The trash specs alone, repeated 5 times
(`npx ng test --watch=false --include="src/app/features/trash/**/*.spec.ts"`): 37 passed each time.

## Full test execution

Command:

`cd web && npm run check` (api:check, lint `--max-warnings=0`, e2e:typecheck, test:ci, ng build)

Result:

Pass: exit 0, "All files pass linting.", 43 test files, 668 tests passed, build complete.

## .taskcheck

Result:

Pass on the second run. `sh scripts/check.sh` ran 2026-10-20T19:45:36Z to 19:52:02Z, exit 0, "== all checks passed"
(tfp gate, server, web 668/668, android).

The first run failed, exit 1, in the server stage, so the web and android stages did not run:
`ch.sonensei.cockpit.core.persistence.ClockConfigurationTests` "the application clock is the
system clock in UTC" failed with `2026-10-20T19:44:23.106430Z should be >= 2026-10-20T19:44:23.106430384Z`.
This is a timing-dependent server test (microsecond-truncated clock compared to a nanosecond
reading). It is not part of this task, which touches no server code, and it passed on the rerun.
I report it to the parent as a pre-existing flaky test. It is not a web-trash finding. The check also
takes about 5-6.5 min, longer than the 2 min AGENTS.md recommends (the limit is 15 min). Most of
that time is the server stage.

## Bugs found

None. No test exposed production behavior that violates the acceptance criteria, SPEC.md §4.2 or
the recorded assumptions.

## Coverage

Status: verified (fresh run on the snapshot below, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured by intersecting the diff. All scoped files except
`trash.routes.ts` are new in this task, and every executable line of all five scoped files is
covered (line metric 165/165 below). So every changed executable line is covered. Changed-branch
coverage is not inferred from this.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-trash; base
  12d11ecb4e8412f4a7eee75af4639780698259c8 (given by the parent); HEAD
  13a477634db125d03f771c3c836925784febfc55; the one task commit 13a4776 (`git diff --stat 12d11ecb..HEAD`),
  which matches the task's `touches: web/src/app/features/trash` (plus app.spec.ts, as the task notes say).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 13a4776 plus uncommitted
  test edits. SHA-256:
  trash-page.spec.ts a42f895133983deb7d24f63ce0f9858cdc100433c8de0660a19ba27d6352191f,
  trash.service.spec.ts e5b2b1f51dd2cc63a550bc0a1385b507ee3a3ee1968f14b6a6f7dbf70f378e78,
  app.spec.ts 4b1233dd81c5a914405c90d68a717ffdfe71411a499deb8ecaee104882d23330.
  Production files are unchanged from HEAD:
  trash-page.ts c93a92df9349ec91fcd23f17042c993422ac6196eab3961025240e9b579e94a3,
  trash-page.html 5e5821017f13d7c6f4890beceaf67dcc348b60dfec6c15c627c61befc5732df6,
  purge-dialog.ts be7f3ef6537447a681920e28ef5725cec23fe3337bc93aaca85c151d53b564d4,
  trash.service.ts e1eb0f7fc4ad09b0b601ba18900248ef82b587cf8478182be57e3311ed200623,
  trash.routes.ts 81fee8f4975b24b27cc4b2a3e8076f78b9af86090d455ac03723b32d5622fe35.
  The hashes were the same before and after the coverage run.
- Exact included files; renamed/deleted/excluded files and reasons: included
  `web/src/app/features/trash/{trash-page.ts, trash-page.html, purge-dialog.ts, trash.service.ts, trash.routes.ts}`.
  Excluded: `trash-page.scss` (styles only, nothing to execute), `*.spec.ts` and `app.spec.ts` (tests).
  No renamed or deleted files. No scoped file is missing from the report.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with
  `@vitest/coverage-v8` through the Angular unit-test builder; `npm run coverage` in `web/`
  (`ng test --watch=false --coverage` with text-summary, json-summary, json and lcov reporters); full web suite.
- Run start/completion time and exit status: 2026-10-20T19:39:00Z to 19:39:23Z, exit 0, 43 files and
  668 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json`
  (sha256 34be85d20156730f1f844f553d03ace50012ee125dda6fb4af7828ac97b16ca8),
  `web/coverage/web/coverage-summary.json`
  (sha256 187a2126b2cb74aef4fb72f16a7d89a40982235a6e1f63570e3549b0a37acf37), both written
  2026-10-20 21:39:23 +0200 by this run. `lcov.info` and `lcov-report/` were written by the same run.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtimes fall inside the run
  window, and the source hashes were identical before and after.
- Final run after latest test additions and production fixes: yes. No production fixes were made, and
  all 16 added tests were in the snapshot.

Values below use covered/total (percentage), from provider counters in coverage-summary.json.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/trash/trash-page.ts` | 105/110 (95.45%) | 47/58 (81.03%) | 29/29 (100%) | 87/87 (100%) |
| `web/src/app/features/trash/trash-page.html` | 91/91 (100%) | 12/12 (100%) | 5/5 (100%) | 58/58 (100%) |
| `web/src/app/features/trash/purge-dialog.ts` | 23/23 (100%) | 8/9 (88.88%) | 1/1 (100%) | 11/11 (100%) |
| `web/src/app/features/trash/trash.service.ts` | 10/10 (100%) | 5/5 (100%) | 4/4 (100%) | 7/7 (100%) |
| `web/src/app/features/trash/trash.routes.ts` | 2/2 (100%) | N/A (0/0) | 1/1 (100%) | 2/2 (100%) |
| **Aggregate (summed counters, 5 files, none missing; .scss excluded)** | 231/236 (97.88%) | 72/84 (85.71%) | 40/40 (100%) | 165/165 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `trash-page.ts:84, 88` (stale first-page result/error ignored when `seq !== loadSeq`) | Out-of-order first-page loads | Gap left on purpose: a second `load()` can only start from "Try again", which is rendered only after the previous load failed. No two first-page loads overlap through the UI. |
| `trash-page.ts:94` (`loadMore` guard: no cursor or already loading) | Double "Show more" | Covered at the UI level: "disables 'Show more' while the next page loads ..." checks that the button is disabled, so the guard cannot be reached through the UI. |
| `trash-page.ts:100, 109` (stale next page after a reload) | A page for an earlier load is ignored | Gap left on purpose: a reload is only possible from the load-error state, which hides the list and its "Show more" button. |
| `trash-page.ts:102, 104` (`items() ?? []` / `items ?? []` null side) | Defensive null handling | Not reachable: "Show more" is rendered only when `items()` is non-null. |
| `trash-page.ts:167` (`run` busy guard) | Double restore or delete of one row | Covered at the UI level: "disables the row while the permanent delete runs" and the busy-state checks in the restore tests. The buttons are disabled while busy and the dialog is modal. |
| `trash-page.ts:188-190` (`drop` when the item is not in the list; `?.` null sides) | Defensive | Not reachable: `drop` is only called for a row that is shown. |
| `purge-dialog.ts:36`, 1 of 9 branches | None (the uncovered branch comes from compiler-emitted code for the class/`inject` line, not task logic) | Not material. Both dialog outcomes (confirm, cancel/Escape) are tested. |

## Unable to test

- Browser end-to-end flows (Playwright) were not run. The acceptance criteria name only Vitest
  component/service tests and `npm run check`, and the slow suites are outside this scope.
- Visual styling (`trash-page.scss`, the red urgent colour) is only checked through the `soon` CSS
  class, not by rendering. No follow-up is needed for the criteria.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient and no production defects
were found. One unrelated flaky server test (`ClockConfigurationTests`) failed the first `.taskcheck`
run and passed on the rerun.
