# Test Report

Date: 2026-10-21

Task: web-gtd-convert-note - Inbox/task detail: to reference

Scope: task commits 4e0c4b53 (implementation), 59c9af2b (tests of an earlier test-writer run) and 21ae7bdb (fixer, CR-3/CR-4), on top of base 7ce21cc072525cedb46378ee80989f449382cd1a (merge-base with origin/main, supplied by the parent). Measured HEAD 21ae7bdbe3757db5e2efc894907ef9c830c67620 on branch main, plus the uncommitted spec changes of this run (listed below). `git log 7ce21cc0..HEAD` lists exactly these three commits, so the diff contains no work from other tasks.

Requirements:
- `tasks/web-gtd-convert-note.md` acceptance criteria and the two recorded assumptions (inbox shows an "Open note" link (/notes/:id) and keeps processing; task detail confirms, then navigates to /notes/:id; inline clarification changes are not saved before converting)
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md` (ETag/If-Match, SPEC.md §4.2)
- Parent instructions: check convert with If-Match, inbox update/rollback, Open note link, task detail confirm/navigation/error paths
- `reviews/web-gtd-convert-note/fixes.md` (CR-3, CR-4 regression tests)

## Test files changed

- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`
- `web/src/app/features/gtd/inbox/inbox-page.spec.ts`

## Tests added or updated

Count: 5 added (3 in task-detail.spec.ts, 2 in inbox-page.spec.ts). Also, Prettier formatting only: `task-detail.spec.ts` (it was already unformatted at HEAD) and `const HOME ={` -> `const HOME = {` in `inbox-page.spec.ts`. No existing assertion was changed.

The existing tests from 59c9af2b/21ae7bdb already covered most criteria (see the table). The new tests cover gaps that were left:
- `TaskDetail > to reference > drops a background reload that was already running when the conversion started`: a background GET started by a change event before the conversion answers 404 late. The view must not switch to "not found" and must still navigate to the note. This tests the `loadSeq++` in `toReference()`, which had no test before (CR-4's test only covers events that arrive after the conversion started).
- `TaskDetail > to reference > still opens the note when the Trash event of the task arrives before the answer`: the server's DELETED event arrives while the request is still pending. Navigation still happens exactly once.
- `TaskDetail > to reference > shows field errors of a 400 answer and opens no note`: a 400 with field errors shows the field message, does not navigate, and lets the user try again.
- `InboxPage > To reference > counts two overlapping conversions that answer in reverse order`: two pending conversions answer in reverse order. Both items stay out of the queue, both are counted (progress 67), and a single status line is shown.
- `InboxPage > To reference > a failed conversion keeps the note offered by the previous one`: a failed second conversion brings the item back and leaves the first note's link up.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 inbox: "To reference" converts the current item (convert called with If-Match of its version) | inbox-page.spec `R converts the item to a note, it leaves the queue and the note is offered`, `runs from the enabled To reference button, marked with its shortcut`; task-store.spec `converts a task to a note: it leaves the list at once and the note is returned`; inbox-actions.spec `offers To reference with the key R`, `maps r to reference` | `npm --prefix web run test:ci -- --include=...` (the 5 task spec files, see "Focused test execution") and `npm --prefix web run coverage` | Pass |
| AC1 inbox: offers to open the note (status line with Open note link to /notes/:id) | inbox-page.spec `R converts ...` (href `/notes/n-t1`), `opens the note with the Open note link` (Router.navigateByUrl `/notes/n-t1`), `offers the latest note after converting several items`, `forgets the offered note when processing starts again`, `forgets the offered note once a later item gets another action, not when it fails` (CR-3), `a failed conversion keeps the note offered by the previous one` (new) | same | Pass |
| AC1 inbox: inline attributes are not saved before converting (assumption) | inbox-page.spec `does not save the inline attributes before converting` | same | Pass |
| AC1 task detail: confirm, convert with If-Match, then navigate to /notes/:id | task-detail.spec `converts the task silently with If-Match after confirming, then opens the note`, `does nothing when the user cancels the confirmation`, `warns that unsaved changes are discarded and converts the saved version`, `does not convert a dirty task when the user cancels, and keeps the changes`; task-detail.service.spec `converts a task to a note with If-Match of its version and returns the note` | same | Pass |
| AC1 task detail: error paths (412 conflict reload + retry with new version, other errors, 400 field errors, unexpected error, late failure after switching task) | task-detail.spec `on 412 says the task changed elsewhere and reloads it, without opening a note`, `shows another error in the save bar, keeps the form and allows another try`, `shows a fallback message for an unexpected conversion error`, `shows no error for the new task when a conversion of the previous task fails late`, `shows field errors of a 400 answer and opens no note` (new); task-detail.service.spec `passes a failed conversion on to the caller` | same | Pass |
| AC1 task detail: concurrency (no double convert/save, no convert during save, change events during the conversion) | task-detail.spec `shows the running conversion and neither converts nor saves twice meanwhile`, `cannot convert while a save is running`, `does not reload on a change event while the conversion runs` (CR-4), `drops a background reload that was already running when the conversion started` (new), `still opens the note when the Trash event of the task arrives before the answer` (new) | same | Pass |
| AC2 Vitest tests: the action calls convert and updates the inbox (optimistic removal, rollback on failure, stale list answer) | task-store.spec `converts a task to a note ...`, `brings a task back when converting it to a note fails, and rethrows the error`, `keeps a converted task out of a list answer requested before the conversion`; inbox-page.spec `brings a failed conversion back as the current item, without a note link`, `brings an item back when its conversion fails after the next item came up`, `counts two overlapping conversions that answer in reverse order` (new) | same | Pass |
| AC2 `cd web && npm run check` passes | whole web suite, lint, api:check, e2e:typecheck, build | `npm --prefix web run check` | Pass (83 files, 1545 tests; build prints the existing initial-bundle budget warning, which this task did not cause) |

## Focused test execution

Command:

`npm --prefix web run test:ci -- --include=src/app/features/gtd/inbox/inbox-page.spec.ts --include=src/app/features/gtd/task-detail/task-detail.spec.ts --include=src/app/features/gtd/task-detail/task-detail.service.spec.ts --include=src/app/features/gtd/data/task-store.spec.ts --include=src/app/features/gtd/inbox/inbox-actions.spec.ts`

Result:

Pass: 5 files, 215 tests.

## Full test execution

Command:

`npm --prefix web run check` (api:check, lint, e2e:typecheck, test:ci, ng build)

Result:

Pass: "All files pass linting.", 83 test files and 1545 tests passed, build output written to `web/dist/web`. Only the existing initial-bundle budget warning was printed. This ran before the Prettier-only reformat of `task-detail.spec.ts`. After the reformat, `npm --prefix web run lint` passed again, and the final coverage run (below) ran the whole suite again: 83 files, 1545 tests passed.

## .taskcheck

Result:

Not run. The parent asked for the web check. `scripts/check.sh` also runs the server/ and android/ checks, and this task does not touch those parts. The parent runs `.taskcheck` before `done`.

## Bugs found

None. All new tests passed against the HEAD production code.

Observation for the parent (not a defect against a stated requirement, no test asserts it): in the task detail, if the user switches the editor to another task while a conversion is still running and the conversion then succeeds, `toReference()` still navigates to the old task's note (`task-detail.ts:259`). The failure path is guarded (`task.id === this.taskId()`), but the success path is not. Neither the spec nor the assumptions say what should happen here. In the inbox, when overlapping conversions answer in reverse order, the status line shows whichever note answered last, not the one started last. That is also undefined by the requirements, and the new test deliberately does not assert which note is shown.

## Coverage

Status: verified (fresh run, exit status 0, all tests passed).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 45/45 (100%) executable changed lines covered. Method: added/modified lines from `git diff -U0 7ce21cc0..HEAD` per scoped file intersected with the provider's `DA:` lines in `web/coverage/web/lcov.info` (script `.tmp/changed-lines.cjs`, Git-ignored). Removed and non-executable lines are not counted. Changed-branch coverage was not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-gtd-convert-note; base 7ce21cc072525cedb46378ee80989f449382cd1a; HEAD 21ae7bdbe3757db5e2efc894907ef9c830c67620; task commits 4e0c4b53, 59c9af2b, 21ae7bdb (supplied by the parent, confirmed with `git log 7ce21cc0..HEAD`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 21ae7bdb plus uncommitted `inbox-page.spec.ts` sha256 93065676ffce1c17e8f2dc8e685b2f0e8fe42be37cd0a8ab5a8a8adbb2b66028 and `task-detail.spec.ts` sha256 73ff09f699167bd8edd8dd05805fc21a523d59ce1ddb67f0c85b90324b8007cc. Production files are unmodified from HEAD, e.g. `task-detail.ts` 79cba8bb08d998fb7d79204b86c4039cc7a88e4d3394a5f0b3d7bc0b0c8a6214, `inbox-page.ts` 373ccec8e6ddfe8b78b2082f684cc8f32b46e853fc5637ee047340a126276476, `task-store.ts` 88603c437b82fea075f2ca89b5ba8155d9aae59d907ca5850bfb32db6e5f0a0d. The untracked `reviews/web-gtd-convert-note/review.md` (not a test input) exists but was not written by this run.
- Exact included files: `web/src/app/features/gtd/data/task-store.ts`, `inbox/inbox-actions.ts`, `inbox/inbox-page.ts`, `inbox/inbox-page.html`, `task-detail/task-detail.service.ts`, `task-detail/task-detail.ts`, `task-detail/task-detail.html` (the templates are in the v8 report through Angular source maps). Excluded: `inbox/inbox-page.scss` and `task-detail/task-detail.scss` (styles, no executable behavior), all `*.spec.ts`, and `reviews/` docs. There are no renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` (^5.0.5) via `@angular/build:unit-test`, Node v24.21.0. Command: `npm --prefix web run coverage` (`ng test --watch=false --coverage ...`), run from the clone root (the script runs in `web/`). Test selection: the full web suite.
- Run start/completion time and exit status: started 2026-10-21T05:24:33Z, report written 05:25:09Z, checked 05:25:16Z; 83 files and 1545 tests passed. An earlier identical run (05:23:06Z) gave the same counters, before the spec reformat.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 6c628d114785a81d7a8d13193e3b83df07a270c18e2113a5c3956ce033661be2; `coverage-summary.json` 39fa3bca5cb7d8f24d7c8a6540b98f1c9104edec3157d45e6dbedb0699ba80d2; `lcov.info` c97f0bb569514fdcb139ef41ecb7de6c0788c73ee484489cd58f466850a66301.
- Evidence outputs are fresh and inputs stayed unchanged: report mtime 07:25:09 local (05:25:09Z) is after the run start. The spec and production hashes were the same before and after the run.
- Final run after latest test additions and production fixes: yes. It ran on HEAD 21ae7bdb (which includes the CR-3/CR-4 fixes) with all tests of this run.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/data/task-store.ts` | 71/71 (100%) | 75/78 (96.15%) | 24/24 (100%) | 56/56 (100%) |
| `web/src/app/features/gtd/inbox/inbox-actions.ts` | 22/22 (100%) | 22/22 (100%) | 7/7 (100%) | 18/18 (100%) |
| `web/src/app/features/gtd/inbox/inbox-page.ts` | 155/159 (97.48%) | 88/92 (95.65%) | 36/37 (97.29%) | 128/129 (99.22%) |
| `web/src/app/features/gtd/inbox/inbox-page.html` | 223/223 (100%) | 20/20 (100%) | 19/19 (100%) | 124/124 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.service.ts` | 13/13 (100%) | 5/5 (100%) | 9/9 (100%) | 11/11 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 188/190 (98.94%) | 120/127 (94.48%) | 42/42 (100%) | 154/154 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.html` | 337/344 (97.96%) | 48/51 (94.11%) | 23/26 (88.46%) | 194/196 (98.97%) |
| Aggregate (summed counters, 7 files; excluded: 2 scss files) | 1009/1022 (98.73%) | 378/395 (95.70%) | 160/164 (97.56%) | 685/688 (99.56%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `task-detail.ts:250` (early `return` when `saving()`/`converting()`) | Guard against a double conversion | Justified gap. The button is disabled while saving or converting, so the guard cannot be reached through the UI. The tests `shows the running conversion and neither converts nor saves twice meanwhile` and `cannot convert while a save is running` cover the behavior through the UI. |
| `task-detail.ts:85,126,178,209,213,234` (branches) | Older task-detail code (draft helpers, recurrence, discard), not changed by this task | Out of task scope (those lines are not in the diff). |
| `task-detail.html:44,160,76,183` | Older template parts (completed badge, rule input, Enter in title, context chips) | Out of task scope (not changed lines). The new button (lines 47-58) is fully covered. |
| `task-store.ts:54,92,110` (`?? task` fallbacks) | Older move/complete/uncomplete code. This task only reformatted those lines (a whitespace diff) | Out of task scope. `convertToNote` (lines 124-138) is fully covered. |
| `inbox-page.ts:40,124,163,221,261,291` | Older inbox code (viewChild focus, `choose()`/`run()` guards without a current item, a prompt-action branch) | Out of task scope. The new `reference` branch and the `lastNote` handling (lines 233-242, 135) are covered. |

## Unable to test

- Actually opening `/notes/:id` in a real browser: the route comes with web-notes-editor (CR-2, out of scope), and the criteria do not name an E2E run. The unit tests check the link href, the `navigateByUrl`/`navigate` calls and their arguments. No follow-up for this task.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
