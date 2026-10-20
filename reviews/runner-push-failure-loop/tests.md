# Test Report

Date: 2026-10-20

Task: runner-push-failure-loop - Runner: stop the loop when release/hold cannot push

Scope: base 5148ba763db8db8ef82261a0d664dd0d186cbdf6 (given by the parent) to measured HEAD
e8da7ec32026006fb04b8e72c808f029ca772f2f on branch main. Task-owned commits (named by the parent, all reachable
from HEAD via the merge e8da7ec): c81fead, 2996ab1, 2fd764a, 8aca456, 6dcdc71, c896015
(c896015c1702e5f2d806171a42d0999b6ce57700 is the latest production change: a shared give_up() for release and
hand-off, and tasks blocked by a `done` push streak are put on hold on retry). The other commits in
`git log 5148ba7..HEAD` are board commits (claim/release/heartbeat) and the merge.
Changed production files (`git diff --stat 5148ba7 HEAD -- agents.py tasks.py tests/`): `agents.py`, `tasks.py`.
Changed test files: `tests/test_overbudget.py` (Runner hooks), `tests/test_push_failure.py`.
This report replaces the earlier one, which described the pre-c896015 revision c40fc6a and is stale.
Uncommitted inputs: only this test-writer's change to `tests/test_push_failure.py` and this report.
Tested snapshot (sha256, identical before and after the final runs):
- `agents.py` 108e78da3744174efd5d83b1936535ad62ee2bf6834942a4d8affee252253f70 (= HEAD)
- `tasks.py` 1f42caf7d1f1054d06124c0ff4ca9d5dd0834a1a55a4c51226f21ec01170900e (= HEAD)
- `board.py` 8e03cb8241d7718c45cbe1b4dba48a52047dc087e7b8f704d346084e8932e646 (= HEAD, unchanged by the task, copied by the tests)
- `tests/test_push_failure.py` 0b3bb472d30387ea2d59fdb3c1ad206560802d5601ca1ea6d017adafc7f23b5a (with the test added here)
- `tests/test_overbudget.py` b59f1cd2bc6048f4f26ca1d91b55e31f3c33d440ff0c117cd24bc2ce26e02e6f (= HEAD)

Requirements:
- tasks/runner-push-failure-loop.md acceptance criteria 1-4, goal, and its recorded assumption (hand_off() fix in scope)
- AGENTS.md (Evidence, quality gate), docs/ARCHITECTURE.md "Coverage"
- Parent instructions: re-test the final code on HEAD after c896015

## Test files changed

- `tests/test_push_failure.py`

## Tests added or updated

Count: 1 (new runner test class)

- `DoneBlockedHoldRefused.test_refused_hold_retry_stays_blocked_then_holds_once_pushes_work`: `done` is refused
  3 sessions in a row and the remote also refuses the hold retry (WORKFLOW_HOOK on every ref, so the push to
  `wip/a` fails). Covers the c896015 path that the existing `DoneRefusedOnlyOnMain` (hold retry succeeds at once)
  and `DoneRefused` (retry interval 1800 s, never retried in the test) did not: the retry logs
  `retrying to put a on hold` and `hold FAILED for a` with git's push error, at least twice; never
  `retrying to release a` (the `done` cause survives the re-block by give_up()); no session starts while blocked;
  `a` stays in progress, ours, not on hold; status `blocked` on `a` with the error. After the hook is removed (a
  human fixed the token), the next retry puts `a` on hold ("done failed to push 3 sessions in a row"), logs
  `put a on hold for a human`, saves the work to `wip/a`, and still never releases it.

Mutation check: in a scratch copy under `.tmp/` with `block()` changed to forget the remembered command
(`cmd]` instead of `cmd or blocked.get(tid, [None] * 3)[2]]`), the new test fails (after one failed hold the
runner switches to `retrying to release a`); all existing tests in the file would not detect this. The scratch
copy was removed afterwards.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. release/hold: failed push to `wip/<id>` or of the board commit prints `PUSH FAILED: <git error>`, exits non-zero, no traceback; task still put on hold locally or agent told only a human can fix it | `TasksPy.test_release_with_refused_wip_push`, `test_hold_with_refused_wip_push`, `test_release_with_refused_board_push`, `test_release_with_wip_saved_but_board_push_refused`, `test_hold_of_unclaimed_task_with_refused_board_push`, `test_done_with_refused_push`, `test_release_still_works_when_pushes_do` | `python3 -m unittest tests.test_push_failure` | Pass |
| 2. after force_release()/a review the runner checks task state and exit code; logs "release FAILED", goes `blocked` in `logs/<agent>.status.json` with the error, retries at most every --blocked-retry (default 1800 s), no new session until fixed or the push works | `ReviewReleaseRefused`, `ReviewReleaseConflict`, `ReviewReleaseConflictWipRefused`, `ReleaseRefused`, `HoldRefusedInSession`, `RetryAfterFix`, `TakenAwayWhileBlocked`, `DroppedWhileBlocked`, `TakenAwayWithNothingLeft`, `OldPushFailureIgnored`, `OtherTaskPushFailureIgnored` | `python3 -m unittest tests.test_push_failure` | Pass |
| 2 (same loop on the usage-limit path, task assumption): hand_off() with refused pushes keeps the work and blocks; with a non-push failure and a working fallback push it still saves to wip and releases | `UsageLimitHandOffRefused`, `UsageLimitHandOffConflictWipRefused`, `UsageLimitHandOffConflict` | `python3 -m unittest tests.test_push_failure` | Pass |
| 3. the same push error from `done` in 3 sessions in a row blocks the same way; no more sessions on that task | `DoneRefused`, `DoneStreakDifferentErrors`, `DoneStreakBrokenBySession`, `DoneRefusedOnlyOnMain`, `DoneBlockedHoldRefused` (new) | `python3 -m unittest tests.test_push_failure` | Pass |
| 4. tests simulate a remote that rejects pushes (pre-receive hook in a bare repo); `sh scripts/check.sh` passes | all of `tests/test_push_failure.py` (hooks `WORKFLOW_HOOK`, `MAIN_WORKFLOW_HOOK`, `MSG_HOOK`, `REFUSE_ALL_HOOK`, protected-main hook); `scripts/check.sh` | `python3 -m unittest tests.test_push_failure`; `sh scripts/check.sh` | Pass |
| Runner hooks added to `tests/test_overbudget.py` keep the old over-budget and usage-limit behaviour | `tests/test_overbudget.py` (all) | `python3 tests/test_overbudget.py` | Pass |

Not covered by an automated test: the default 1800 s retry interval itself (tests use `--blocked-retry 5` or
1800 and check that no session or retry happens within ~8 s while blocked; waiting 30 min is not practical).

## Focused test execution

Command:

`python3 -m unittest tests.test_push_failure.DoneBlockedHoldRefused`

Result:

Pass (1 test, 20.3 s), after fixing my own first assertion: it expected 3 sessions in total, but once `a` is on
hold the runner correctly claims `b` (a 4th session); the test now checks that no session starts between
`BLOCKED on a` and `put a on hold for a human`.

## Full test execution

Command:

`python3 -m unittest tests.test_push_failure` before adding the test (18:09:41 to 18:14:54 +02:00: 25 tests,
312.8 s, OK) and on the final snapshot (18:15:13 to 18:20:35 +02:00: 26 tests, 322.4 s, OK, exit 0); then
`python3 tests/test_overbudget.py` (9 OK), `tests/test_quality_gate.py` (18 OK), `tests/test_assumptions.py` (6 OK),
`tests/test_done_gate.py` (16 OK), `tests/test_skip_touches.py` (7 OK) (18:20:35 to 18:22:02 +02:00, each exit 0).

Result:

Pass. Not run: `tests/test_publish.sh` (not part of `scripts/check.sh`; an earlier attempt to run it was denied
by this environment's permission settings).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (18:22:02 to 18:28:00 +02:00, exit 0, "== all checks passed"; tfp gate tests,
server, web and android steps all ran) on the final snapshot.

## Bugs found

No production defect in the task's changed behaviour at c896015 / HEAD e8da7ec.

The earlier Low observation about the status showing `limited` while a blocked hand-off waits out a usage limit
is resolved by c896015 (`wait_limited()` keeps `state: blocked`, asserted in `UsageLimitHandOffRefused` and
`UsageLimitHandOffConflictWipRefused`). The pre-existing `cmd_hold()` stale-clone observation stays out of scope
as recorded in the task's assumption.

## Coverage

Status: unavailable. The project's configured coverage tooling (docs/ARCHITECTURE.md "Coverage") covers only
server (JaCoCo), web (Vitest v8) and android (JaCoCo); nothing is configured for the Python TFP scripts, and the
Python `coverage` package is not installed (`python3 -c "import coverage"` -> `ModuleNotFoundError: No module named
'coverage'`, Python 3.14.8, rechecked for this revision). Installing it is outside the test-writer's authority.
The code under test also runs in subprocesses (`agents.py run`, `tasks.py` called by the fake agent and the
runner), which would need subprocess coverage setup. Follow-up, if wanted: a task that adds Python coverage
tooling for the TFP scripts.

Metric: whole-file coverage of task-changed production files: not measured.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: runner-push-failure-loop, base
  5148ba763db8db8ef82261a0d664dd0d186cbdf6, HEAD e8da7ec32026006fb04b8e72c808f029ca772f2f, commits c81fead,
  2996ab1, 2fd764a, 8aca456, 6dcdc71, c896015 and production files `agents.py`, `tasks.py` from the parent,
  confirmed by `git log`/`git diff --stat 5148ba7 HEAD`
- Source/test snapshot identifier (including uncommitted inputs): sha256 hashes in "Scope" above
- Exact included files; renamed/deleted/excluded files and reasons: in scope `agents.py`, `tasks.py` (both
  unmeasured: no provider); excluded the test files (tests, not production) and the review reports (documentation);
  no renames or deletions
- Provider/version, command, working directory, and test selection: none available (see Status)
- Run start/completion time and exit status: no coverage run
- Generated report paths and report hashes/run identifier: none
- Evidence outputs are fresh and inputs stayed unchanged: the final test runs and `scripts/check.sh` used the
  hashed snapshot; hashes taken before and after the runs are identical
- Final run after latest test additions and production fixes: yes for tests (c896015 code on HEAD e8da7ec, with
  the test added here); no coverage run

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `agents.py` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |
| `tasks.py` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |

No aggregate: both scoped files are unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `agents.py` blocked loop, `cmd == "done"` retry when the hold itself fails (found by reading the code, not by a coverage tool) | criterion 3: a `done`-blocked task must not be released or get new sessions while pushes are refused | `DoneBlockedHoldRefused` added |
| `agents.py` blocked loop, default `--blocked-retry 1800` | criterion 2: retries no more than every 30 min | not tested: waiting 30 min is impractical; the tests use short intervals and check no retry/session within ~8 s at 1800 s |

## Unable to test

- The 30 min default retry interval in real time (see above); no follow-up needed beyond the short-interval tests.
- Line/branch coverage of `agents.py`/`tasks.py`: no Python coverage tooling configured (see Coverage); follow-up
  only if the project wants coverage numbers for the TFP scripts.

## Summary

Tests added and all validation passed. No production defects found; task-scoped coverage could not be measured
(no Python coverage tooling configured).
