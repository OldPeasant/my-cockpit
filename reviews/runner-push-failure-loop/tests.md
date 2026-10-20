# Test Report

Date: 2026-10-20

Task: runner-push-failure-loop - Runner: stop the loop when release/hold cannot push

Scope: base 47fb83c689b30c6b16369e3cd17121a4988faccc (given by the parent) to measured HEAD
5e7a0d16293fd6d13635ef3a4d6bc123f862db49 on branch main (`git diff 47fb83c..HEAD`). Task-owned commits (named by
the parent, confirmed with `git log --oneline 47fb83c..HEAD`, which lists only these two): cdf25b5 (PUSH FAILED in
tasks.py, blocked state in the runner) and 5e7a0d1 (tests with a remote that refuses pushes).
Changed production files: `tasks.py`, `agents.py`. Changed test files: `tests/test_overbudget.py` (Runner hooks),
`tests/test_push_failure.py` (new). Uncommitted inputs: only this test-writer's change to `tests/test_push_failure.py`
and this report.
Tested snapshot (sha256, the same before and after every run below):
- `agents.py` 51f9b8972f20919151b1d8aec3d6b4fe1cbae6daab8e7dfe4b98deccff040652
- `tasks.py` 1f42caf7d1f1054d06124c0ff4ca9d5dd0834a1a55a4c51226f21ec01170900e
- `tests/test_push_failure.py` 7c63dd535e8253eab79eed4819050f2510ce814a49300768dda7ad0ca634b00a (with the tests added here)
- `tests/test_overbudget.py` b59f1cd2bc6048f4f26ca1d91b55e31f3c33d440ff0c117cd24bc2ce26e02e6f

Requirements:
- tasks/runner-push-failure-loop.md acceptance criteria 1-4 and goal
- AGENTS.md (Evidence, quality gate), docs/ARCHITECTURE.md "Coverage"
- Parent instructions: cover a done streak broken by a different error or by a successful session, and a task
  taken away by a human while blocked

## Test files changed

- `tests/test_push_failure.py`

## Tests added or updated

Count: 9 (2 in `TasksPy`, 7 new runner test classes; 1 new scripted fake agent `SEQ_AGENT`, 1 hook variant `MSG_HOOK`)

- `TasksPy.test_release_with_wip_saved_but_board_push_refused`: the remote takes `wip/a` but refuses `main` (protected
  branch). `release` exits non-zero with `PUSH FAILED: <git error>`, no traceback, the task stays in progress and
  owned, the work is on `wip/a`, the failure file names `cmd=release`.
- `TasksPy.test_hold_of_unclaimed_task_with_refused_board_push`: `hold` of a todo task when the board push is refused:
  PUSH FAILED, no traceback, no `ON HOLD`, no hold on the remote, the failure file names the task.
- `HoldRefusedInSession`: `hold` failing to push inside a session blocks the runner at once (cmd is not `done`), no
  second session.
- `DoneStreakDifferentErrors`: `done` push errors one, two, one, two, two, two: the counter goes 1,1,1,1,2,3, so a
  different error restarts the streak and the same error 3 times in a row blocks (after exactly 6 sessions).
- `DoneStreakBrokenBySession`: `done` fails, fails, a session without a push failure, then fails 3 times: counter
  1,2,1,2,3, blocked after exactly 6 sessions.
- `TakenAwayWhileBlocked`: blocked on `a`, a human puts `a` on hold with `--force`: the runner logs "a is no longer
  ours", parks a's refused local commits, never retries the release of `a`, claims `b`, and its status leaves
  `blocked` with `error` cleared.
- `DroppedWhileBlocked`: the same with `drop --force` instead of hold.
- `OldPushFailureIgnored`: a failure file left from before the session (`at=0`) does not block and is consumed.
- `OtherTaskPushFailureIgnored`: a fresh failure file for a task the runner does not own does not block.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. release/hold: failed push to `wip/<id>` or of the board commit prints `PUSH FAILED: <git error>`, exits non-zero, no traceback; task still put on hold locally or agent told only a human can fix it | `TasksPy.test_release_with_refused_wip_push`, `test_hold_with_refused_wip_push`, `test_release_with_refused_board_push`, `test_release_with_wip_saved_but_board_push_refused`, `test_hold_of_unclaimed_task_with_refused_board_push`, `test_done_with_refused_push`, `test_release_still_works_when_pushes_do` | `python3 tests/test_push_failure.py` | Pass |
| 2. after force_release()/a review the runner checks task state and exit code; logs "release FAILED", goes `blocked` in `logs/<agent>.status.json` with the error, retries at most every --blocked-retry (default 1800 s), no new session until fixed or the push works | `ReviewReleaseRefused`, `ReleaseRefused`, `HoldRefusedInSession`, `RetryAfterFix`, `TakenAwayWhileBlocked`, `DroppedWhileBlocked`, `OldPushFailureIgnored`, `OtherTaskPushFailureIgnored` | `python3 tests/test_push_failure.py` | Pass |
| 3. the same push error from `done` in 3 sessions in a row blocks the same way | `DoneRefused`, `DoneStreakDifferentErrors`, `DoneStreakBrokenBySession` | `python3 tests/test_push_failure.py` | Pass |
| 4. tests simulate a remote that rejects pushes (pre-receive hook in a bare repo); `sh scripts/check.sh` passes | all of `tests/test_push_failure.py` (hooks `WORKFLOW_HOOK`, `MSG_HOOK`, `REFUSE_ALL_HOOK`, protected-main hook); `scripts/check.sh` | `python3 tests/test_push_failure.py`; `sh scripts/check.sh` | Pass |
| Runner hooks added to `tests/test_overbudget.py` keep the old over-budget behaviour | `tests/test_overbudget.py` (all) | `python3 tests/test_overbudget.py` | Pass |

Not covered by an automated test: the default 1800 s retry interval itself (tests use `--blocked-retry 5` and 1800 and
check that no session or retry happens within ~8 s while blocked; waiting 30 min is not practical), and
`force_release()` failing with a non-zero exit code but no push-failure file (no practical way to produce it without
changing production code; the code path is shared with the tested push-failure case).

## Focused test execution

Command:

`python3 tests/test_push_failure.py TasksPy HoldRefusedInSession OldPushFailureIgnored OtherTaskPushFailureIgnored`,
then `... DoneStreakDifferentErrors DoneStreakBrokenBySession`, then `... TakenAwayWhileBlocked DroppedWhileBlocked`

Result:

Pass (after fixing two test mistakes of my own: the blocked loop idles up to 60 s per round, so the wait for
"no longer ours" needed > 60 s; and the "parked local work" log line comes before "no longer ours", not after).

## Full test execution

Command:

`sh .tmp/tw-suite.sh` (2026-10-20 17:17:16 to 17:22:05 +02:00), which runs:
`python3 tests/test_push_failure.py` (18 tests, 210 s, OK), `python3 tests/test_overbudget.py` (9 tests, 44 s, OK),
`python3 tests/test_quality_gate.py -q` (18 tests, OK), `python3 tests/test_assumptions.py` (6, OK),
`python3 tests/test_done_gate.py` (16, OK), `python3 tests/test_skip_touches.py` (7, OK). All exit 0.

Result:

Pass

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (2026-10-20 17:17:16 to 17:21:15 +02:00, exit 0, "all checks passed"; tfp gate tests,
server, web and android steps all ran).

## Bugs found

No production defect in the task's changed behaviour. Two observations in pre-existing code that the task did not
change, for the parent to decide whether they are in scope (no failing test was added because they are not
acceptance criteria of this task):

- Observation (Medium, pre-existing, `agents.py` `hand_off()`): on a usage limit the runner calls `tasks.py release
  --handoff`; if that fails (as it now does with PUSH FAILED when the remote refuses `wip/<id>`), it force-pushes to
  `wip/<id>` itself (fails the same way), then runs `git reset --hard @{u}` and releases again. With a remote that
  refuses the commits, the local work is then only in the reflog and the task is released with nothing saved: the
  same push-refused scenario as this task, on the usage-limit path. Found by reading the code, not reproduced by a test.
- Observation (Low, pre-existing, `tasks.py` `cmd_hold()`): it decides between "just mark a todo task" and
  `give_back()` from `load()` before syncing. In a stale clone that still sees the task as `todo`, a human's
  `hold <id> --force` on a task an agent is working on only sets `hold:` and leaves it `in_progress` with the agent
  (seen while writing `TakenAwayWhileBlocked`: output "ON HOLD a", remote still `status: in_progress`, owner
  `box/agent-1`; a blocked runner then stays blocked). The test pulls the human's clone first.

## Coverage

Status: unavailable. The project's configured coverage tooling (docs/ARCHITECTURE.md "Coverage") covers only
server (JaCoCo), web (Vitest v8) and android (JaCoCo); nothing is configured for the Python TFP scripts, and the
Python `coverage` package is not installed (`python3 -c "import coverage"` -> `ModuleNotFoundError: No module named
'coverage'`, Python 3.14.8). Installing it is outside the test-writer's authority. Additionally, the code under test
runs in subprocesses (`agents.py run`, `tasks.py` called by the fake agent and the runner), which would need
subprocess coverage setup.

Metric: whole-file coverage of task-changed production files: not measured.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: runner-push-failure-loop, base
  47fb83c689b30c6b16369e3cd17121a4988faccc, HEAD 5e7a0d16293fd6d13635ef3a4d6bc123f862db49, commits cdf25b5 and 5e7a0d1
  from the parent, confirmed by `git log 47fb83c..HEAD`
- Source/test snapshot identifier (including uncommitted inputs): sha256 hashes in "Scope" above
- Exact included files; renamed/deleted/excluded files and reasons: in scope `tasks.py`, `agents.py` (both
  unmeasured: no provider); excluded the test files (tests, not production)
- Provider/version, command, working directory, and test selection: none available (see Status)
- Run start/completion time and exit status: no coverage run
- Generated report paths and report hashes/run identifier: none
- Evidence outputs are fresh and inputs stayed unchanged: test runs above used the hashed snapshot; hashes rechecked
  after the runs, unchanged
- Final run after latest test additions and production fixes: yes for tests (no production fixes); no coverage run

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `tasks.py` | N/A (not measured) | N/A | N/A | N/A |
| `agents.py` | N/A (not measured) | N/A | N/A | N/A |

No aggregate: both scoped files are unmeasured.

Manual review of the changed branches instead of coverage numbers:

| File and changed branch | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `tasks.py` `pushed()`, `push_failed()`, `save_work()`, `update()` failure exit | criterion 1 | covered by the 7 `TasksPy` tests (wip push refused, board push refused, wip saved but main refused, unclaimed hold, done, pushes working) |
| `tasks.py` `push_failed()` `except OSError` (failure file not writable) | defensive | not tested: needs an unwritable git dir; non-material |
| `agents.py` `push_failure()` `at < since` and invalid/missing file | stale failures must not block | `OldPushFailureIgnored`; missing file covered by every session without a failure |
| `agents.py` session handler: `pf.task` not owned | must not block for another task | `OtherTaskPushFailureIgnored` |
| `agents.py` session handler: `cmd != done` (release, hold) | criterion 2 | `ReleaseRefused`, `HoldRefusedInSession` |
| `agents.py` session handler: done streak, different error, `elif not why` clear | criterion 3 | `DoneRefused`, `DoneStreakDifferentErrors`, `DoneStreakBrokenBySession` |
| `agents.py` `force_release()` after a review, success and failure | criterion 2 | `ReviewReleaseRefused` (failure), `RetryAfterFix` and `test_overbudget.py` (success) |
| `agents.py` blocked loop: idle until retry, retry, unblock when no longer ours | criterion 2 | `ReleaseRefused`, `RetryAfterFix`, `TakenAwayWhileBlocked`, `DroppedWhileBlocked` |
| `agents.py` session killed (`why` set) with a done push failure | the streak must not be cleared by a timeout | not tested: needs a session timing out; low risk |

## Unable to test

- The default 30 min retry interval exactly: not practical in a unit run; the tests use a short and a long
  `--blocked-retry` and check the behaviour around it. No follow-up needed.
- `force_release()` with a non-zero exit but no push-failure file: no way to produce it from outside without
  changing production code. Low risk (same code path as the tested case).
- Coverage numbers: no Python coverage provider configured or installed (see Coverage). Follow-up only if the
  project wants coverage for the TFP scripts.

## Summary

Tests added and all validation passed. No production defects found in the task's changes; two observations in
pre-existing code (`hand_off()` on refused pushes, `cmd_hold()` on a stale clone) are reported for the parent to
decide on. Task-scoped coverage could not be measured (no Python coverage tooling configured).
