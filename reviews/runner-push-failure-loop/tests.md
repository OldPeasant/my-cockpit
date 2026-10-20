# Test Report

Date: 2026-10-20

Task: runner-push-failure-loop - Runner: stop the loop when release/hold cannot push

Scope: base 5cc4dcf620d2c9a11cd6e2b444224cefb649e1d3 (given by the parent) to measured HEAD
9a6fd2f5965342550b37931d08675272e456f2d6 on branch main (the reviewed revision). Task-owned commits, named by the
parent and confirmed with `git log --oneline 5cc4dcf6..HEAD -- agents.py tasks.py tests/`: 9c1da9f1 .. e357353e
(9c1da9f1, de4232d9, 70e3fdcb, 4b3fd146, 08393073, b2893e6e, 6ae15386, f8347bdc, 819fe8d9, e357353e). They reach
HEAD through the merge 9a6fd2f5. The other commits in the range are board commits and the web-search task, which
are not in scope.
Changed production files (`git diff --stat 5cc4dcf6 HEAD -- agents.py tasks.py tests/`): `agents.py`, `tasks.py`.
Changed test files: `tests/test_push_failure.py`, `tests/test_overbudget.py` (Runner hooks).
This report replaces the earlier one, which described c896015/e8da7ec and is stale. Since then, f8347bdc fixed
CR-1..CR-3 (a refused agent hold is retried as that hold with its reason; give_up() aborts a merge and commits a
dirty tree first), and 819fe8d9 and e357353e changed the tests.
Uncommitted inputs: this test-writer's change to `tests/test_push_failure.py`, and this report.
`reviews/runner-push-failure-loop/review.md` was also modified in the working tree by someone else (not by this
test-writer; it is not a test input).
Tested snapshot (sha256, identical before and after every run below):
- `agents.py` 84dde9735710c48a8d38027b61b7b1a2e2ae53115c4392a1dc9b0c2738165436 (= HEAD)
- `tasks.py` e29e7f52c2a2e23dfd57090c1df429ceab5831017b8394af4815f73d8699263f (= HEAD)
- `board.py` 8e03cb8241d7718c45cbe1b4dba48a52047dc087e7b8f704d346084e8932e646 (= HEAD, not changed by the task; the tests copy it)
- `tests/test_push_failure.py` 2ec943739e170f094d31776a1b755a36e63967ee85a1dc44ae83cd8e328f1391 (HEAD + the test added here)
- `tests/test_overbudget.py` b59f1cd2bc6048f4f26ca1d91b55e31f3c33d440ff0c117cd24bc2ce26e02e6f (= HEAD)

Requirements:
- tasks/runner-push-failure-loop.md: acceptance criteria 1-4, the goal, and the recorded assumption (the hand_off() fix is in scope)
- AGENTS.md (Evidence, quality gate), docs/ARCHITECTURE.md "Coverage"
- Parent instructions: re-check the tests on the current HEAD after the CR-1..CR-3 fixes and add tests only for real gaps

## Test files changed

- `tests/test_push_failure.py`

## Tests added or updated

Count: 1 (new runner test class)

- `DoneStreakErrorNamesCommit.test_same_error_with_a_different_commit_hash_counts_as_the_same`. Real remotes often
  name the refused commit in their error (GitHub's rule violations do, for example). To count "the same push
  error in N sessions in a row" (criterion 3), the runner replaces hex hashes in the error with `<sha>`
  (`agents.py`, `key = re.sub(r"\b[0-9a-f]{7,40}\b", "<sha>", err)`). Every earlier hook printed a fixed message,
  so no test exercised this. The new hook prints `commit $new:`, and each session commits new work. The test
  checks three things: the logged errors carry 3 different commit hashes, the streak still counts `[1, 2, 3]`,
  and the task becomes `blocked` with no 4th session.
  A mutation check (removing the normalization in a scratch copy) was denied by this environment's permission
  settings and was not run. The test's assertion that the raw errors differ shows that it depends on the
  normalization: without it, every session would restart the streak at 1.

I re-checked the CR-1..CR-3 fixes against the existing tests. HoldRefusedInSession (the agent's refused hold is
retried as that hold, with its reason), DoneRefusedDirtyTree (a dirty tree is committed before give_up),
DoneRefusedMergeInProgress (a merge is aborted before the commit), DoneBlockedHoldRefused (a re-block keeps the
hold reason) and `TasksPy.test_hold_with_refused_wip_push` (the reason is stored in the failure file) cover them.
No other gap needed a new test.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. release/hold: a failed push to `wip/<id>` or of the board commit prints `PUSH FAILED: <git error>`, exits non-zero, no traceback; the task is still put on hold locally or the agent is told only a human can fix it | `TasksPy.test_release_with_refused_wip_push`, `test_hold_with_refused_wip_push`, `test_release_with_refused_board_push`, `test_release_with_wip_saved_but_board_push_refused`, `test_hold_of_unclaimed_task_with_refused_board_push`, `test_done_with_refused_push`, `test_release_still_works_when_pushes_do` | `python3 -m unittest tests.test_push_failure` | Pass |
| 2. after force_release()/a review, the runner checks the task state and exit code; logs "release FAILED"; goes `blocked` in `logs/<agent>.status.json` with the error; retries at most every --blocked-retry (default 1800 s); no new session until fixed or the push works | `ReviewReleaseRefused`, `ReviewReleaseConflict`, `ReviewReleaseConflictWipRefused`, `ReleaseRefused`, `HoldRefusedInSession`, `RetryAfterFix`, `TakenAwayWhileBlocked`, `DroppedWhileBlocked`, `TakenAwayWithNothingLeft`, `OldPushFailureIgnored`, `OtherTaskPushFailureIgnored` | `python3 -m unittest tests.test_push_failure` | Pass |
| 2 (same loop on the usage-limit path, task assumption): hand_off() with refused pushes keeps the work and blocks | `UsageLimitHandOffRefused`, `UsageLimitHandOffConflictWipRefused`, `UsageLimitHandOffConflict` | `python3 -m unittest tests.test_push_failure` | Pass |
| 3. the same push error from `done` in 3 sessions in a row blocks the same way; no more sessions on that task | `DoneRefused`, `DoneStreakDifferentErrors`, `DoneStreakBrokenBySession`, `DoneStreakErrorNamesCommit` (new), `DoneRefusedOnlyOnMain`, `DoneRefusedDirtyTree`, `DoneRefusedMergeInProgress`, `DoneBlockedHoldRefused` | `python3 -m unittest tests.test_push_failure` | Pass |
| 4. tests simulate a remote that rejects pushes (pre-receive hook in a bare repo); `sh scripts/check.sh` passes | all of `tests/test_push_failure.py` (pre-receive hooks in a bare `remote.git`); `scripts/check.sh` | `python3 -m unittest tests.test_push_failure`; `sh scripts/check.sh` | Pass |
| Runner hooks added to `tests/test_overbudget.py` keep the old over-budget and usage-limit behaviour | `tests/test_overbudget.py` (all) | `python3 -m unittest tests.test_overbudget ...` (see below) | Pass |

No automated test covers the default 1800 s retry interval itself. The tests use `--blocked-retry 5`, or 1800
with a check that no session or retry happens within ~8 s while blocked; waiting 30 min is not practical.

## Focused test execution

Command:

`python3 -m unittest tests.test_push_failure.DoneStreakErrorNamesCommit`

Result:

Pass (1 test, 15.5 s).

## Full test execution

Command:

`python3 -m unittest tests.test_push_failure` (started about 19:15:30 +02:00 on the hashed snapshot: 29 tests,
382.1 s, OK, exit 0). Then `python3 -m unittest tests.test_overbudget tests.test_quality_gate tests.test_assumptions
tests.test_done_gate tests.test_skip_touches` (56 tests, 98.5 s, OK, exit 0).

Result:

Pass. Not run: `tests/test_publish.sh`, which is not part of `scripts/check.sh` and does not exercise the task's
push-failure paths.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (finished about 19:24:25 +02:00, exit 0, "== all checks passed"). The tfp gate tests,
server, web and android steps all ran on the hashed snapshot. The web build printed a bundle-budget warning
(508.63 kB vs 500 kB), which has nothing to do with this task and did not fail the check.

## Bugs found

No production defect found in the task's changed behaviour at HEAD 9a6fd2f5.

## Coverage

Status: unavailable. docs/ARCHITECTURE.md "Coverage" configures coverage tooling only for server (JaCoCo), web
(Vitest v8) and android (JaCoCo). Nothing is configured for the Python TFP scripts, and the Python `coverage`
package is not installed: `python3 -c "import coverage"` -> `ModuleNotFoundError: No module named 'coverage'`
(Python 3.14.8, rechecked at 19:15 +02:00 for this revision). Installing it is outside the test-writer's
authority. The code under test also runs in subprocesses (`agents.py run`, and `tasks.py` called by the fake agent
and by the runner), so measuring it would also need subprocess coverage setup. Follow-up, if wanted: a task that
adds Python coverage tooling for the TFP scripts.

Metric: whole-file coverage of task-changed production files. Not measured.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: runner-push-failure-loop, base
  5cc4dcf620d2c9a11cd6e2b444224cefb649e1d3, HEAD 9a6fd2f5965342550b37931d08675272e456f2d6, commits 9c1da9f1..e357353e
  and files from the parent, confirmed by `git log`/`git diff --stat 5cc4dcf6..HEAD`
- Source/test snapshot identifier (including uncommitted inputs): sha256 hashes in "Scope" above
- Exact included files; renamed/deleted/excluded files and reasons: in scope `agents.py` and `tasks.py`, both
  unmeasured because there is no provider. Excluded: the test files (tests, not production) and the review
  reports (documentation). No renames or deletions.
- Provider/version, command, working directory, and test selection: none available (see Status)
- Run start/completion time and exit status: no coverage run
- Generated report paths and report hashes/run identifier: none
- Evidence outputs are fresh and inputs stayed unchanged: the test runs and `scripts/check.sh` used the hashed
  snapshot; the hashes taken before and after the runs are identical
- Final run after latest test additions and production fixes: yes for the tests (HEAD 9a6fd2f5 plus the test added
  here); no coverage run

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `agents.py` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |
| `tasks.py` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |

No aggregate: both scoped files are unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `agents.py` done-streak key, hash normalization (found by reading the code, not by a coverage tool) | criterion 3: the same error with a different commit hash must still count as the same | `DoneStreakErrorNamesCommit` added |
| `agents.py` blocked loop, default `--blocked-retry 1800` | criterion 2: retries no more than every 30 min | Not tested in real time (waiting 30 min is impractical). The tests use short intervals and check that no retry or session happens within ~8 s at 1800 s |

## Unable to test

- The default 30 min retry interval in real time (see above). No follow-up needed beyond the short-interval tests.
- Line/branch coverage of `agents.py`/`tasks.py`: no Python coverage tooling is configured (see Coverage). Follow-up
  only if the project wants coverage numbers for the TFP scripts.
- Mutation check of the new test: denied by the environment's permission settings (see above).

## Summary

Tests added and all validation passed. No production defects found. Task-scoped coverage could not be measured,
because no Python coverage tooling is configured.
