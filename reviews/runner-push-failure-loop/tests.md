# Test Report

Date: 2026-10-20

Task: runner-push-failure-loop - Runner: stop the loop when release/hold cannot push

Scope: base b08d78b78e37fa6866a6591bab802b10c2ba615b (given by the parent) to measured HEAD
c40fc6a77ee64093512809b30271c72f14abcb85 on branch main (`git diff b08d78b..HEAD`). Task-owned commits (named by
the parent, confirmed with `git log --oneline b08d78b..HEAD`, which lists exactly these four): 5c8607b (PUSH FAILED in
tasks.py, blocked state in the runner), d273702 (tests with a remote that refuses pushes), 0670cce (more push-failure
tests, test report), c40fc6a (hand-off on a usage limit keeps the work and blocks when pushes are refused).
Changed production files: `tasks.py`, `agents.py`. Changed test files: `tests/test_overbudget.py` (Runner hooks),
`tests/test_push_failure.py`. This report replaces the earlier one, which described a pre-c40fc6a revision
(different base and HEAD after a rebase) and is stale.
Uncommitted inputs: only this test-writer's change to `tests/test_push_failure.py` and this report
(`reviews/runner-push-failure-loop/review.md` is untracked too; it is the code-reviewer's, not part of this run).
Tested snapshot (sha256, identical before and after every run below):
- `agents.py` f2b3406f3d4207353eb26c523046960f652fcee7b2806d098512de84ab684ecf (= HEAD)
- `tasks.py` 1f42caf7d1f1054d06124c0ff4ca9d5dd0834a1a55a4c51226f21ec01170900e (= HEAD)
- `tests/test_push_failure.py` 3eb8f5ca020d64280518d0c09e1ed8712355e2ba62f924c2e4ed2f934d500cba (with the tests added here)
- `tests/test_overbudget.py` b59f1cd2bc6048f4f26ca1d91b55e31f3c33d440ff0c117cd24bc2ce26e02e6f (= HEAD)

Requirements:
- tasks/runner-push-failure-loop.md acceptance criteria 1-4, goal, and its recorded assumption (hand_off() fix in scope)
- AGENTS.md (Evidence, quality gate), docs/ARCHITECTURE.md "Coverage"
- Parent instructions: re-check the final code after c40fc6a; check that hand_off() still behaves as before when the
  release fails but the fallback wip push succeeds

## Test files changed

- `tests/test_push_failure.py`

## Tests added or updated

Count: 2 (new runner test classes; plus a scripted fake agent `CONFLICT_LIMIT_AGENT` and a shared base
`ConflictLimitRunner`)

- `UsageLimitHandOffConflict.test_hand_off_after_conflict_saves_work_to_wip_and_releases`: on a usage limit, another
  clone has pushed a conflicting `notes.txt` to main, so `release --handoff` stops with CONFLICT (a failure that is
  not a push failure, no failure file); the remote accepts pushes. Shows the pre-c40fc6a behaviour is kept: the
  rebase is aborted, the fallback push saves the work (`work on a`, `notes for a`) to `wip/a`, the clone is reset,
  the second release succeeds (`handed off a: RELEASED a`), `a` is unclaimed again, uncounted (`releases: 0`), not held, the
  work is not on main, no `hand-off FAILED`, no `BLOCKED`, status `limited`.
- `UsageLimitHandOffConflictWipRefused.test_hand_off_after_conflict_with_refused_wip_push_blocks`: the same CONFLICT,
  but the remote refuses the fallback push to `wip/a` (the agent's commit touches `.github/workflows/`). Covers the
  new `w.returncode` branch of hand_off() (no tasks.py push-failure file, the runner's own push fails): logs
  `hand-off FAILED for a` with git's push error, never `handed off a`, the status carries the error, `a` stays
  in progress and ours, the work commits are still in the clone (not reset away), the clone is clean (rebase
  aborted), no `wip/a` on the remote, one session only.

Mutation check: with `agents.py` from 0670cce (before c40fc6a) in a scratch copy under `.tmp/`,
`UsageLimitHandOffConflictWipRefused` and the existing `UsageLimitHandOffRefused` fail, and
`UsageLimitHandOffConflict` passes, i.e. the new tests detect the old reset-away behaviour and confirm the
successful-fallback path is unchanged. The scratch copy was removed afterwards.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. release/hold: failed push to `wip/<id>` or of the board commit prints `PUSH FAILED: <git error>`, exits non-zero, no traceback; task still put on hold locally or agent told only a human can fix it | `TasksPy.test_release_with_refused_wip_push`, `test_hold_with_refused_wip_push`, `test_release_with_refused_board_push`, `test_release_with_wip_saved_but_board_push_refused`, `test_hold_of_unclaimed_task_with_refused_board_push`, `test_done_with_refused_push`, `test_release_still_works_when_pushes_do` | `python3 tests/test_push_failure.py` | Pass |
| 2. after force_release()/a review the runner checks task state and exit code; logs "release FAILED", goes `blocked` in `logs/<agent>.status.json` with the error, retries at most every --blocked-retry (default 1800 s), no new session until fixed or the push works | `ReviewReleaseRefused`, `ReleaseRefused`, `HoldRefusedInSession`, `RetryAfterFix`, `TakenAwayWhileBlocked`, `DroppedWhileBlocked`, `OldPushFailureIgnored`, `OtherTaskPushFailureIgnored` | `python3 tests/test_push_failure.py` | Pass |
| 2 (same loop on the usage-limit path, task assumption): hand_off() with refused pushes keeps the work and blocks; with a non-push failure and a working fallback push it still saves to wip and releases | `UsageLimitHandOffRefused`, `UsageLimitHandOffConflictWipRefused`, `UsageLimitHandOffConflict` | `python3 tests/test_push_failure.py` | Pass |
| 3. the same push error from `done` in 3 sessions in a row blocks the same way | `DoneRefused`, `DoneStreakDifferentErrors`, `DoneStreakBrokenBySession` | `python3 tests/test_push_failure.py` | Pass |
| 4. tests simulate a remote that rejects pushes (pre-receive hook in a bare repo); `sh scripts/check.sh` passes | all of `tests/test_push_failure.py` (hooks `WORKFLOW_HOOK`, `MSG_HOOK`, `REFUSE_ALL_HOOK`, protected-main hook); `scripts/check.sh` | `python3 tests/test_push_failure.py`; `sh scripts/check.sh` | Pass |
| Runner hooks added to `tests/test_overbudget.py` keep the old over-budget and usage-limit behaviour | `tests/test_overbudget.py` (all) | `python3 tests/test_overbudget.py` | Pass |

Not covered by an automated test: the default 1800 s retry interval itself (tests use `--blocked-retry 5` and 1800 and
check that no session or retry happens within ~8 s while blocked; waiting 30 min is not practical), and
`force_release()` failing with a non-zero exit code but no push-failure file (no practical way to produce it without
changing production code; the code path is shared with the tested push-failure case).

## Focused test execution

Command:

`python3 tests/test_push_failure.py UsageLimitHandOffConflict UsageLimitHandOffConflictWipRefused UsageLimitHandOffRefused`

Result:

Pass (after fixing three mistakes of my own: the first version let the second class inherit the first class's test;
the `field()` helper reads an empty `owner:` as the next line, so the test now checks the owner is not the runner;
and during the usage-limit wait the status state is `limited`, so the test checks the status `error`, not
`state == blocked`, see Observation below).

## Full test execution

Command:

`python3 tests/test_push_failure.py` (2026-10-20 17:38:37 to 17:42:22 +02:00: 21 tests, 224.8 s, OK), then
`python3 tests/test_overbudget.py`, `python3 tests/test_quality_gate.py -q`, `python3 tests/test_assumptions.py`,
`python3 tests/test_done_gate.py`, `python3 tests/test_skip_touches.py` (17:42:34 to 17:44:04 +02:00: each OK).

Result:

Pass. Not run: `bash tests/test_publish.sh` (the command was denied by this environment's permission settings; the
script exercises tasks.py publish paths and is not part of `scripts/check.sh`; `check.sh` below ran
`tests/test_quality_gate.py`).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (2026-10-20 17:44:10 to 17:48:57 +02:00, exit 0, "== all checks passed"; tfp gate tests,
server, web and android steps all ran).

## Bugs found

No production defect in the task's changed behaviour.

Observations for the parent (no failing test added: they are not clear violations of an acceptance criterion):

- Observation (Low, `agents.py` `hand_off()` followed by `wait_limited()`): when hand_off() blocks a task, `block()`
  publishes `state: blocked`, but the runner then immediately waits out the usage limit with
  `idle(..., task=None, state="limited")`, so `logs/<agent>.status.json` shows `state: limited`, `task: null` (the
  `error` field is kept) until the limit ends (up to 6 h); only then does the blocked loop show `blocked`. Seen in
  `UsageLimitHandOffConflictWipRefused`. No session starts on the task meanwhile, so the loop is stopped; only the
  visibility of the blocked state during the usage-limit wait is affected.
- Observation (Low, pre-existing, `tasks.py` `cmd_hold()`, carried over from the earlier report and recorded by the
  parent as out of scope): in a stale clone that still sees the task as unclaimed, a human's `hold <id> --force` only
  sets `hold:` and leaves the task `in_progress`. The tests pull the human's clone first.

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
  b08d78b78e37fa6866a6591bab802b10c2ba615b, HEAD c40fc6a77ee64093512809b30271c72f14abcb85, commits 5c8607b, d273702,
  0670cce, c40fc6a and production files `tasks.py`, `agents.py` from the parent, confirmed by
  `git log`/`git diff --stat b08d78b..HEAD`
- Source/test snapshot identifier (including uncommitted inputs): sha256 hashes in "Scope" above
- Exact included files; renamed/deleted/excluded files and reasons: in scope `tasks.py`, `agents.py` (both
  unmeasured: no provider); excluded the test files (tests, not production) and the review reports (documentation)
- Provider/version, command, working directory, and test selection: none available (see Status)
- Run start/completion time and exit status: no coverage run
- Generated report paths and report hashes/run identifier: none
- Evidence outputs are fresh and inputs stayed unchanged: the test runs above used the hashed snapshot; hashes
  rechecked after `scripts/check.sh`, unchanged
- Final run after latest test additions and production fixes: yes for tests (the final revision c40fc6a, with the
  tests added here); no coverage run

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `tasks.py` | N/A (not measured) | N/A | N/A | N/A |
| `agents.py` | N/A (not measured) | N/A | N/A | N/A |

No aggregate: both scoped files are unmeasured.

Manual review of the changed branches instead of coverage numbers:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `tasks.py` `pushed()`, `push_failed()` | AC1: PUSH FAILED with git's error, failure file for the runner | Exercised by `TasksPy.*` and every runner test; the `OSError` branch when the failure file can't be written is not reachable without breaking the git dir: justified gap |
| `tasks.py` `update()` after 5 failed pushes, `save_work()` refused wip push | AC1 board-commit and wip paths | `test_release_with_refused_board_push`, `test_release_with_wip_saved_but_board_push_refused`, `test_release_with_refused_wip_push`, `test_hold_with_refused_wip_push`, `test_done_with_refused_push` |
| `agents.py` `hand_off()`: pf branch, `w.returncode` branch, successful fallback branch | Usage-limit path must not reset refused work away; old behaviour kept when the fallback push works | `UsageLimitHandOffRefused` (pf), `UsageLimitHandOffConflictWipRefused` (w.returncode, added here), `UsageLimitHandOffConflict` (fallback succeeds, added here) |
| `agents.py` `force_release()` non-zero exit without failure file (`err = last`) | AC2 "release FAILED" on any failure | Not produced by a test (no practical trigger without changing production code); shares the block path with the tested case: justified gap |
| `agents.py` `push_failure()` stale/other-task/invalid file | Old or foreign failures must not block | `OldPushFailureIgnored`, `OtherTaskPushFailureIgnored`; invalid JSON branch not tested: low risk, returns None |
| `agents.py` blocked loop: idle, retry, unblock when taken away | AC2 retry interval, no sessions, recovery | `RetryAfterFix`, `TakenAwayWhileBlocked`, `DroppedWhileBlocked`, `assert_blocked_without_new_sessions`; 1800 s default not waited out: justified gap |
| `agents.py` `done` streak counter | AC3 | `DoneRefused`, `DoneStreakDifferentErrors`, `DoneStreakBrokenBySession` |

## Unable to test

- Line/branch coverage of `tasks.py` and `agents.py`: no Python coverage provider configured or installed (see
  Coverage). Follow-up only if the project wants Python coverage for the TFP scripts (a tooling decision).
- The real 30-min default retry interval: not waited out; the interval logic is tested with short and long values.
- `bash tests/test_publish.sh`: not run, the command was denied by this environment's permissions. Not a task
  criterion; the parent may run it.

## Summary

Tests added and all validation passed. No production defects found; two Low observations (status shows `limited`
rather than `blocked` during a usage-limit wait after a refused hand-off; the pre-existing `cmd_hold()` stale-clone
case) are left to the parent. Coverage of the Python scripts could not be measured (no provider).
