# Test Report

Date: 2026-10-21

Task: web-gtd-convert-note - Inbox/task detail: to reference (round 3, after the CR-5/CR-6 fixes)

Scope: task commits `d12d5eb4a2e21d90cc8289fd5a1abfc2707357f1..79a8ad13315e318b9b79992cb6f7bc74e6d74cde` (base and commit list given by the parent worker; 7 commits, branch `main`). This round focuses on the last code change, 96c82af2 (CR-5: per-task converting state `convertingId` in `task-detail.ts`; CR-6: `runSeq` ordering of `lastNote` in `inbox-page.ts`). It also checks that both acceptance criteria are still covered. Uncommitted inputs: only the two spec files listed below (this round's test additions). No production file differs from HEAD.

Requirements:
- tasks/web-gtd-convert-note.md (acceptance criteria and the recorded assumptions: the inbox shows an "Open note" link and does not navigate; the task detail navigates to /notes/:id after a confirm)
- SPEC.md §5.3 (inbox processing action "To reference (note)")
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md
- reviews/web-gtd-convert-note/review-2.md (CR-5, CR-6), fixes.md, tests-1.md, tests-2.md

## Test files changed

- `web/src/app/features/gtd/inbox/inbox-page.spec.ts`
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`

## Tests added or updated

Count: 3 (added; no existing test changed)

- `InboxPage > To reference > a slow earlier action answering after a later conversion keeps that note link`. This is the second half of CR-6, which 96c82af2 did not test: a "Next" move of t1 answers after the conversion of t2. The `Open note` link for t2 must stay. Before the fix, every non-reference answer cleared `lastNote`, so this test would have failed.
- `TaskDetail > to reference > keeps the conversion of the new task running when the previous one answers first, then opens its note`. This covers the `finally` guard `convertingId() === task.id`. The user converts t1, switches to t2 and converts t2 too. Then t1 answers: t2 must still show "Moving to reference…", stay disabled, and not navigate. When t2 answers, the editor navigates exactly once, to `/notes/n2`. With the old component-wide flag, the conversion of t2 could not start at all.
- `TaskDetail > to reference > shows the running conversion again and opens the note when the editor comes back to the task`. The user switches t1 to t2 and back to t1 while the conversion of t1 runs. The running state shows again (computed `converting`), and the late success opens t1's note.

The 96c82af2 specs already cover the other fixed paths. `counts two overlapping conversions that answer in reverse order` checks the reverse-order note link. `a slow conversion answering after a quicker later action leaves no note link` checks that a slow conversion followed by a fast trash leaves no link. `does not open the note nor block the new task when a conversion of the previous task succeeds late` checks that t2 is free and there is no navigation.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: the action converts and navigates/offers to open the note (inbox: status line with an "Open note" link; task detail: navigate to /notes/:id after a confirm) | inbox-page.spec `To reference` block (R key/button converts with If-Match, the item leaves the queue, `Open note` href `/notes/n-t1`, a link click goes to `/notes/n-t1`, latest note offered, ordering cases incl. the new one); task-detail.spec `to reference` block (confirm/cancel, dirty confirm, If-Match + SILENT_ERRORS, `navigate(['/notes','n1'])`, running state, 400/412/500 paths, task switch during conversion incl. the 2 new tests); task-detail.service.spec convertToNote | `npm --prefix web run test:ci -- --include=src/app/features/gtd/inbox/inbox-page.spec.ts --include=src/app/features/gtd/task-detail/task-detail.spec.ts --include=src/app/features/gtd/data/task-store.spec.ts` | Pass (3 files, 176 tests). The navigation target is verified through the link href and the router spies, not end to end, because the `/notes/:id` route comes with web-notes-editor (recorded assumption). |
| AC2: Vitest component/service tests cover that the action calls convert and updates the inbox | task-store.spec `convertToNote` tests (optimistic removal, rollback on error); inbox-page.spec (queue/left/progress updated, failed conversion brings the item back) | same as above | Pass |
| AC2: `cd web && npm run check` passes | whole web suite, lint, api:check, e2e:typecheck, build | `npm --prefix web run check` (= `cd web && npm run check`), 07:59:51 to 08:00:56 | Pass: lint clean, 83 files / 1550 tests passed, build OK (the existing initial-bundle budget warning is not an error) |
| CR-5 fix: a late conversion of the previous task neither navigates nor blocks the new task; per-task running state | `does not open the note nor block the new task ... succeeds late`, `shows no error for the new task when a conversion of the previous task fails late`, the 2 new task-detail tests | focused command above | Pass |
| CR-6 fix: only the latest action sets or clears the note link | `counts two overlapping conversions that answer in reverse order`, `a slow conversion answering after a quicker later action leaves no note link`, the new inbox test, `a failed conversion keeps the note offered by the previous one` | focused command above | Pass |

## Focused test execution

Command:

`npm --prefix web run test:ci -- --include=src/app/features/gtd/inbox/inbox-page.spec.ts --include=src/app/features/gtd/task-detail/task-detail.spec.ts --include=src/app/features/gtd/data/task-store.spec.ts`

Result:

Pass: 3 files, 176 tests (run twice, before and after Prettier formatted the new inbox spec; the second run is on the final snapshot).

## Full test execution

Command:

`npm --prefix web run check` (the web part of `.taskcheck`)

Result:

Pass at 07:59:51 to 08:00:56 on the final snapshot: 83 files, 1550 tests, lint clean, build OK. Two earlier runs: the first passed with 1550 tests (exit status not captured). In the second, 9 tests timed out at 5 s in suites unrelated to this task (app.i18n, reminder-notifications, capture-dialog, files.routes, task-store "reopens a done task", list-pages, project-form, task-checklist, delete-dialog). The host load average was 105 to 131 on 16 cores at that time, from other agents. The final run with the load around 40 passed fully. The timeouts are environment flakiness, not task defects, but the parent may see them in `.taskcheck` under high load.

## .taskcheck

Result:

Not run (the parent worker asked not to run `scripts/check.sh`; it runs it itself).

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: the lcov `DA` lines (executable, source-mapped) of each file intersected with the added/modified lines of `git diff -U0 d12d5eb4..HEAD` for that file. Result 49/49 changed executable lines covered (task-store.ts 7/7, inbox-actions.ts 0/0, inbox-page.ts 8/8, inbox-page.html 5/5, task-detail.service.ts 1/1, task-detail.ts 21/21, task-detail.html 7/7). Changed-branch coverage was not measured separately.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-gtd-convert-note; base `d12d5eb4a2e21d90cc8289fd5a1abfc2707357f1` and commits base..HEAD come from the parent worker; HEAD `79a8ad13315e318b9b79992cb6f7bc74e6d74cde`. File scope: `git diff --name-only d12d5eb4..HEAD -- web/src` minus `*.spec.ts`, which matches the task's touches plus the recorded `gtd/data/task-store.ts` assumption.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 79a8ad13 plus uncommitted `inbox-page.spec.ts` sha256 `ff1126f244a40b5e5653c067d8ea53bfef8ba0052e9f6c96a5f65c4cacb8754d` and `task-detail.spec.ts` sha256 `80996b2f2546cecff5b428c393694df315968b3ce070c4e2d75a24ac8d01cef7` (both last modified 07:47:27 and 07:46:37, before the coverage run; `git status` before and after: only these two files modified).
- Exact included files: `web/src/app/features/gtd/data/task-store.ts`, `inbox/inbox-actions.ts`, `inbox/inbox-page.ts`, `inbox/inbox-page.html`, `task-detail/task-detail.service.ts`, `task-detail/task-detail.ts`, `task-detail/task-detail.html`. Excluded: `inbox/inbox-page.scss` and `task-detail/task-detail.scss` (styles only, no executable behavior). No renames or deletions. All 7 included files are in the report (none missing).
- Provider/version, command, working directory, and test selection: Vitest + `@vitest/coverage-v8` 5.0.5. Command `npm --prefix web run coverage -- --include='src/app/features/gtd/**/*.spec.ts'` (the project's `coverage` script; working directory `web/`), limited to all 29 gtd spec files.
- Run start/completion time and exit status: 2026-10-21T07:58:37+02:00 to 07:59:02+02:00, exit 0, 29 files / 621 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 `bc474017ece3f35864b20e5e98ea913f7b6bfa0f369fd75e73947f3dda8dc30c`, `web/coverage/web/coverage-summary.json` sha256 `e90c8b82842fef66b6c32bc2899b49191e63702f3113f4dc1837282ecd8dee4e`, `web/coverage/web/lcov.info`.
- Evidence outputs are fresh and inputs stayed unchanged: the `coverage/` directory was recreated by the run (mtime 07:58:46, files 07:59:01). The source and spec hashes were unchanged after the run.
- Final run after latest test additions and production fixes: yes (it includes the 3 new tests and production code at 96c82af2, unchanged since).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/data/task-store.ts` | 71/71 (100%) | 75/78 (96.15%) | 24/24 (100%) | 56/56 (100%) |
| `web/src/app/features/gtd/inbox/inbox-actions.ts` | 22/22 (100%) | 22/22 (100%) | 7/7 (100%) | 18/18 (100%) |
| `web/src/app/features/gtd/inbox/inbox-page.ts` | 158/162 (97.53%) | 88/92 (95.65%) | 36/37 (97.30%) | 131/132 (99.24%) |
| `web/src/app/features/gtd/inbox/inbox-page.html` | 221/223 (99.10%) | 20/20 (100%) | 19/19 (100%) | 124/124 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.service.ts` | 13/13 (100%) | 5/5 (100%) | 9/9 (100%) | 11/11 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 192/194 (98.97%) | 124/131 (94.66%) | 43/43 (100%) | 155/155 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.html` | 337/344 (97.97%) | 48/51 (94.12%) | 23/26 (88.46%) | 194/196 (98.98%) |
| Aggregate (summed counters, 7 files; scss excluded, none missing) | 1014/1029 (98.54%) | 382/399 (95.74%) | 161/165 (97.58%) | 689/692 (99.57%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `task-detail.ts:252` branch 0 (early return of `if (!task \|\| this.saving() \|\| this.converting()) return;` in `toReference`) | The guard against a double conversion or a conversion during a save | Left uncovered: the UI disables the button in both states, and the tests `shows the running conversion and neither converts nor saves twice meanwhile` and `cannot convert while a save is running` check that. The return is defensive and not reachable through the DOM. |
| `task-detail.ts:85, 128[1], 180[1], 211[0], 215[1], 236[1]` | Existing editor code (DI factory, null-draft fallbacks) | Not task behavior (lines not changed by this task); non-material. |
| `inbox-page.ts:223[0]` (`if (!task) return;` in `run`) | Defensive: `run` is only reached with a current item | Non-material; not reachable through the UI. |
| `inbox-page.ts:40[0], 165[0], 265[7], 295` | Existing code (DI factory, `apply` switch default/calendar case, which is disabled) | Not changed by this task; non-material. |
| `task-store.ts:54[1], 92[1], 110[1]` (`current ?? task` fallbacks in `update`/`complete`/`reopen`) | Existing store code | Not changed by this task; the task's `convertToNote` lines are 7/7 covered. |
| `inbox-page.html` 2 statements, `task-detail.html:160, 183`, 3 functions / 3 branches | Template parts outside the task's added lines (changed template lines 5/5 and 7/7 covered) | Not changed by this task; non-material. |

## Unable to test

- Opening the note end to end (`/notes/:id` renders the note): the route comes with the web-notes-editor task, so it lands on Not Found for now (recorded assumption). It is verified through the `Open note` href, the `navigateByUrl` call on a click, and the `router.navigate(['/notes', id])` spy. Follow-up: an end-to-end check once web-notes-editor is done (not part of this task's criteria).

## Summary

Tests added and all validation passed. Three tests close the remaining ordering gaps of the CR-5/CR-6 fixes. The focused suite (176 tests) and `npm run check` (1550 tests, lint, build) pass. Task-scoped coverage is 49/49 changed executable lines and 99.57% whole-file lines. No production defects were found.
