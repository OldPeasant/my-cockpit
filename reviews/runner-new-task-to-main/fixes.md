# Fix Report

Date: 2026-11-22

Task: runner-new-task-to-main - Runner: a task created while working on another reaches main right away

Scope: current task, findings CR-1, CR-2 and CR-3 from `reviews/runner-new-task-to-main/review.md` (base e6dd4d5b1, reviewed HEAD 7d0558ccb). Fix commit: 6fd0ab02d.

## Fixed findings

### CR-1 - `new` now commits and pushes by itself, so an existing test in test_done_gate.py fails

Files changed:
- none by the fixer for CR-1 itself (the test-writer already changed `tests/test_done_gate.py` in 7d0558ccb); `tests/test_done_gate.py` was changed again for CR-2, see below

Problem:
`Touches.test_next_prefers_tasks_away_from_work_in_progress` committed and pushed the task file by hand after `new`, which now does that itself, so `git commit` found nothing to commit.

Fix:
Verified the test-writer's change in 7d0558ccb: the manual add/commit/push was removed and the test now asserts that the file is on the remote main and that the clone is clean. After the CR-2 change, this test (and `test_new_writes_touches`) also pass `--body`.

Validation:
- `python3 tests/test_done_gate.py` - Pass (16 tests, OK)

### CR-2 - Without `--body`, `new` publishes an empty, immediately claimable task to main

Files changed:
- `tasks.py`
- `tests/test_new_task.py`
- `tests/test_done_gate.py`

Problem:
When a remote exists and `--body` is not given, `new` pushed the empty skeleton (empty Goal, one empty criterion, open and unclaimed) to main, where an idle agent could claim it at once.

Fix:
`cmd_new` now exits before anything is written or pushed when `upstream()` exists and `--body` is missing. The message says the task was NOT created, explains that `new` publishes to main at once, and tells the user to write Goal, Acceptance criteria and Notes into a file (e.g. `.tmp/ID.md`) and run the command again with `--body`. The no-remote path still writes the empty template as a local commit. The exists check still comes first. The hints in the `drop` guard (done task) and in `accept`/`reject` output now say `python tasks.py new ... --body FILE`.
Tests: `test_without_body_the_skeleton_is_published` became `test_without_body_nothing_is_published`. It checks that the command exits non-zero, prints "NOT created" and the `--body .tmp/c.md` hint, writes no file, and leaves both the remote main and the local HEAD unchanged. `test_same_id_created_by_another_agent_first` now creates the other clone's task with `--body`. In `tests/test_done_gate.py`, the two `Touches` tests that call `new` after the remote exists now write a body file and pass `--body` (small `body()` helper). Board fixtures in test_done_gate, test_quality_gate, test_skip_touches, test_assumptions, test_overbudget and test_publish.sh call `new` before the first push (no upstream), so they keep the template path unchanged and pass.

Validation:
- `python3 tests/test_new_task.py` - Pass (18 tests, OK)
- `python3 tests/test_done_gate.py` - Pass (16 tests, OK)

### CR-3 - AGENTS.md step 7 mentions only `PUSH FAILED`

Files changed:
- `AGENTS.md`

Problem:
Step 7 only mentioned `PUSH FAILED`. A failure while catching up with main (offline, or a conflict in the creator's own work) was not described, and the line was 160 characters long.

Fix:
Step 7 now says: if `new` prints `NOT created` (`PUSH FAILED`, or it could not catch up with main first: offline, or a CONFLICT in your own work), nothing was created. Resolve a conflict as in "Merge conflicts", then retry the printed command. The lines are wrapped to the width of the neighbouring lines.

Validation:
- the change is in documentation only; checked against the `cmd_new` messages, which print "NOT created" in both failure paths

## Not fixed

None.

## Validation summary

Focused tests:
- `python3 tests/test_new_task.py` - 18 tests OK
- `python3 tests/test_done_gate.py` - 16 tests OK

Full test suite:
- `python3 tests/test_quality_gate.py -q` - 22 tests OK
- `python3 tests/test_skip_touches.py` - 7 tests OK
- `python3 tests/test_assumptions.py` - 6 tests OK
- `python3 tests/test_overbudget.py` - 9 tests OK
- `sh tests/test_publish.sh` - ALL PASSED

Lint/type-check/build:
- Not run (the parent asked not to run Gradle or scripts/check.sh)

`.taskcheck`:
- Not run (parent's instruction; the parent worker runs it)

Remaining unresolved findings:
0

Note: `docs/QUALITY.md:86` and `docs/deploy-runbook.md:256` still show `python tasks.py new ...` without `--body`. `docs/` is outside this task's touches. The error message from `new` explains `--body` when it is missing, so these docs were left unchanged.

## Final status

All validated findings fixed and validation passed.
