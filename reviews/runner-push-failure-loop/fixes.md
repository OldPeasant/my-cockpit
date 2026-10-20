# Fix Report

Date: 2026-10-20

Task: runner-push-failure-loop - Runner: stop the loop when release/hold cannot push

Scope: findings CR-1..CR-3 of reviews/runner-push-failure-loop/review.md (reviewed revision 8237fd5), plus a
racy test the parent reported (RetryAfterFix). The parent committed a draft fix in 85900c2. This fixer checked
that draft against each finding and added the test fixes.

## Fixed findings

### CR-1 - The agent's refused hold is retried as a release, so the hold reason is lost

Files changed (draft 85900c2, checked here and kept as is):
- `tasks.py`
- `agents.py`
- `tests/test_push_failure.py`

Problem:
When an agent's `tasks.py hold` push was refused, `block(tid, err)` stored no command. The blocked retry then
ran `release --handoff`, which put the task back on `todo` with no hold and dropped the agent's reason.

Fix:
`tasks.py` now stores the hold reason (`REASON[0]`, set from `a.reason` only for `hold`) in the push-failure
JSON. The session path calls `block(tid, err, hold=pf.get("reason"))` when `pf["cmd"] == "hold"`. A `done`
streak passes its own hold text. The blocked loop retries `tasks.py hold <id> <reason>` whenever a hold reason
is stored. If that hold retry is refused again, `give_up()` calls `block(tid, err)` with no reason, and
`blocked.get(tid)[2]` keeps the stored reason. TasksPy.test_hold_with_refused_wip_push checks the stored
reason. HoldRefusedInSession now removes the hook and checks that the task ends `todo` with hold
"cannot finish", that no release was tried, that there is no "handed back" note, and that no session ran while
the task was blocked.

Validation:
- `python -m unittest tests.test_push_failure` - Pass (27 tests)

### CR-2 - The hold retry of a done-blocked task can throw away uncommitted changes

Files changed (draft 85900c2, checked here and kept as is):
- `agents.py`
- `tests/test_push_failure.py`

Problem:
The `done`-streak hold retry called `give_up()` without committing a dirty tree first. The
`reset --hard @{u}` fallback in `give_up()` could then delete uncommitted changes.

Fix:
`give_up()` now aborts a merge in progress and commits a dirty tree (`wip: <id> (before <label>)`) before it
runs `tasks.py`. That covers every caller: the hold retry, `force_release()` and `hand_off()`. Those two still
commit first themselves, which does no harm. The new test DoneRefusedDirtyTree leaves an uncommitted file after
each failed `done`. It checks that all 3 lines end up on `wip/a` and that the commit "wip: a (before hold)"
exists.

Validation:
- `python -m unittest tests.test_push_failure` - Pass (DoneRefusedDirtyTree included)

### CR-3 - Stale comment for `blocked`

Files changed (draft 85900c2, checked here and kept as is):
- `agents.py`

Problem:
The comment described a 2-element value. The entries actually have 3, and the third one decides between a hold
retry and a release.

Fix:
The comment now reads `[push error, next retry (epoch), hold reason or None]`, says that the retry is
`hold <reason>` when a reason is stored and a release otherwise, and points to `block()`. The `block()`
docstring and the module docstring say the same.

Validation:
- Code read at HEAD.

### PARENT-1 - Racy test: wait on the board, then assert a log line

Files changed:
- `tests/test_push_failure.py`

Problem:
RetryAfterFix waited until "pushes had failed" showed up in the board task, then asserted the log line
"released a for another agent". `force_release()` writes that line only after `give_up()` returns, and that
happens after the board push. The test could therefore read the log before the line was written. The same
pattern (wait for `hold` on the board, then assert "put a on hold for a human" in the log) was in
DoneRefusedOnlyOnMain (and DoneRefusedDirtyTree, which inherits it), DoneBlockedHoldRefused and
HoldRefusedInSession. In those tests, the `blocked` slice that ends at that log line could also include later
sessions.

Fix:
After the board wait, each of these tests now also waits (`wait_for`, 30 s) for its log line before it reads
the log. No assertion was weakened. TakenAwayWhileBlocked was checked and needs no change: "parked local work"
is logged before the session that claims b. UsageLimitHandOffConflict already waits for its log line.

Validation:
- `python -m unittest tests.test_push_failure` - Pass (27 tests, 344.9 s)

## Not fixed

None.

## Validation summary

Focused tests:
- `python -m unittest tests.test_push_failure` - Pass (27 tests in 344.9 s)

Full test suite:
- `python -m unittest discover tests` - Pass (83 tests in 433.2 s)

Lint/type-check/build:
- Not applicable (Python runner scripts; no lint configured for agents.py/tasks.py)

`.taskcheck`:
- Not run by the fixer (`sh scripts/check.sh` is left to the parent worker before `done`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
