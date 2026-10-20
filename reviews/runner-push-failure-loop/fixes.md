# Fix Report

Date: 2026-10-20

Task: runner-push-failure-loop - Runner: stop the loop when release/hold cannot push

Scope: findings CR-1 and CR-2 of reviews/runner-push-failure-loop/review.md (reviewed revision 9a6fd2f5). Base
commit 5cc4dcf6, fixed on top of HEAD 4485d57e. Earlier findings (old CR-1..CR-3 and PARENT-1, on 8237fd5) were
fixed in an earlier fix round, and the review confirms that.

## Fixed findings

### CR-1 - A refused `hold` chosen in an over-budget review is replaced by a release, so the hold and its reason are lost

Files changed:
- `agents.py`
- `tests/test_push_failure.py`

Problem:
In the session loop, the over-budget review branch called `force_release()` before the runner read the
session's push failure. Suppose the agent ran `tasks.py hold` in its review and the remote refused the push.
`force_release()` then ran a `tasks.py release` of its own. That release was refused too, and it overwrote the
push-failure record: `cmd=release`, with no reason. `give_up()` consumed that record and blocked the task with
no hold reason. When pushes worked again, the blocked retry released the task back to `todo`, and the agent's
hold and reason were lost. The finding was confirmed with the new test before the fix: it logged "release
FAILED for a" and then "retrying to release a", and the test failed.

Fix:
`pf = push_failure(t0)` is now read right after the session ends, before the review branch. If `pf` belongs to
the reviewed task and its `cmd` is not `done`, the review's own `release`/`hold` was refused. The runner then
does not call `force_release()`: that release would be refused too, and it would overwrite the hold. The task
stays in `now_mine`, so it is not added to `avoid`, and the existing `pf` handling calls
`block(tid, err, hold=pf.get("reason"))`. A refused review `done` keeps the earlier behaviour (force_release,
then the done-streak count), because skipping the release there would only start another review session.
Regression test `ReviewHoldRefused` uses a fake agent whose review always runs `hold`, against WORKFLOW_HOOK.
It asserts that the task is blocked with no new sessions, that the retry is a hold, and that no
"release FAILED", "retrying to release a" or "released a for another agent" line is logged. After the hook is
removed, it asserts that the task ends `todo` with hold "which database? SPEC.md says nothing", has no
"review that decided nothing" or "handed back by" note, has its work on `wip/a`, and that no session ran while
the task was blocked.

Validation:
- `python -m unittest tests.test_push_failure.ReviewHoldRefused` before the fix: Fail (reproduced: "release FAILED for a", then the release retries)
- `python -m unittest tests.test_push_failure.ReviewHoldRefused` after the fix: Pass (1 test, 15.2 s)

### CR-2 - `force_release()` and `hand_off()` repeat the merge-abort and dirty-commit steps that `give_up()` now runs

Files changed:
- `agents.py`

Problem:
`give_up()` aborts an unfinished merge and commits a dirty tree for every caller. `force_release()` and
`hand_off()` still ran both steps themselves first, so the same logic existed in three places.

Fix:
Removed both copies. A short comment and a docstring line in each caller say that `give_up()` does this. It
was safe: `give_up()` runs the same steps first, before it calls `tasks.py`, for every task in `hand_off()`'s
loop. For `force_release()` the commit message stays "wip: <id> (before release)", because its label is
"release". For `hand_off()` the message is now "wip: <id> (before hand-off)" instead of
"wip: <id> (handed off: <reason>)". The reason is still in the board note ("handed off by ...: <reason>"), and
no test or tool reads the commit message.

Validation:
- `python -m unittest tests.test_push_failure` - Pass (includes the UsageLimitHandOff*, ReviewReleaseConflict* and DoneRefusedDirtyTree/MergeInProgress tests)
- `python -m unittest tests.test_overbudget` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `python -m unittest tests.test_push_failure.ReviewHoldRefused` - Fail before the fix (finding reproduced), Pass after it

Full test suite:
- `python -m unittest tests.test_push_failure` - Pass (30 tests in 401.9 s)
- `python -m unittest tests.test_overbudget` - Pass (9 tests in 44.4 s)

Lint/type-check/build:
- Not applicable (Python runner scripts; no lint configured for agents.py/tasks.py)

`.taskcheck`:
- Not run by the fixer (`sh scripts/check.sh` is left to the parent worker before `done`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
