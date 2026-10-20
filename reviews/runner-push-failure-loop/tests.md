# Test Report

Date: 2026-10-20

Task: runner-push-failure-loop - Runner: stop the loop when release/hold cannot push

Scope: base acf29ae65f1be4dc2bfb6b9d985e2ba949ed015e (`git merge-base HEAD origin/main`, given by the parent) to
measured HEAD 7c22f7f28a7d6f16952eb785ac1a233e30c2d5a0 on branch main. The parent named the task-owned commits:
those on agents.py, tasks.py, tests/test_push_failure.py and tests/test_overbudget.py since the base. I confirmed
them with `git log --oneline acf29ae6..HEAD -- agents.py tasks.py tests/test_push_failure.py tests/test_overbudget.py`:
a8efa700, 73e0fcce, 6e5ea4f3, ba1a2b17, cf01c543, 14de7e48, 1c06cc16, 0aef6f88, 619f0b39, cc746488, afe06d4a,
1a4fc713. The last production change is 1a4fc713 (review CR-1/CR-2 fixes). 7c22f7f2 only adds the review report.
Changed production files (`git diff --stat acf29ae6..HEAD -- agents.py tasks.py tests`): `agents.py` (+181/-),
`tasks.py` (+45/-). Changed test files: `tests/test_push_failure.py`, `tests/test_overbudget.py`.
This report replaces the earlier one, which described 9a6fd2f5 and is stale.
Uncommitted inputs: `tests/test_push_failure.py` (the `ReviewDoneRefused` test, started by the previous test-writer
run and improved here), and this report.
Tested snapshot (sha256). The hashes were identical before the first run and after the last run below:
- `agents.py` 6235f047bc356afc2b6ad4221ddc4c5ab48e503db078930a36ace311d8e091b8 (= HEAD)
- `tasks.py` e29e7f52c2a2e23dfd57090c1df429ceab5831017b8394af4815f73d8699263f (= HEAD)
- `board.py` 8e03cb8241d7718c45cbe1b4dba48a52047dc087e7b8f704d346084e8932e646 (= HEAD, not changed by the task; the tests copy it)
- `tests/test_push_failure.py` ad4325597286ca2e5765cee3c59c01c57794a7e8ae14e940f71ddae8090ce133 (HEAD + `ReviewDoneRefused`)
- `tests/test_overbudget.py` b59f1cd2bc6048f4f26ca1d91b55e31f3c33d440ff0c117cd24bc2ce26e02e6f (= HEAD)

Requirements:
- tasks/runner-push-failure-loop.md: acceptance criteria 1-4, the goal, and the recorded assumption (the hand_off() fix is in scope)
- reviews/runner-push-failure-loop/review.md: finding P3 CR-1 (no test for a refused `done` inside an over-budget review)
- AGENTS.md (Evidence, quality gate), docs/ARCHITECTURE.md "Coverage"
- Parent instructions: check that `ReviewDoneRefused` covers CR-1 and improve it if needed, run the task modules and the full Python suite, and measure coverage or give the reason it can't be measured

## Test files changed

- `tests/test_push_failure.py`

## Tests added or updated

Count: 1 (new runner test class `ReviewDoneRefused`, plus the `REVIEW_DONE_AGENT` fake agent)

- `ReviewDoneRefused.test_refused_done_in_a_review_still_force_releases_and_blocks` (review CR-1). The fake agent
  runs `done --force` in every over-budget review, against the WORKFLOW_HOOK remote. Budget 1, so session 1 claims
  `a` and commits `.github/workflows/ci.yml`, and session 2 is the review. In the review, `done` is refused, and
  then the runner's own `force_release()` is refused too.
  The previous run's version already asserted what the review suggested: "BLOCKED on a", "release FAILED for a",
  "done could not push a (1/3 sessions in a row)", and `assert_blocked_without_new_sessions(2)`. It also asserted
  no "released a for another agent" and an empty hold. I checked it against the mutations the review names:
  - Dropping `!= "done"` from `refused` (agents.py, `refused = ...`): `force_release()` would be skipped, so there
    would be no "release FAILED". The task would get more review sessions until the done streak reached 3, so the
    session count would not stay at 2. The test fails either way.
  - Reading `pf` after `force_release()` again (the earlier CR-1 bug): the release's failure record would replace
    the `done` record, so the "1/3" streak line would be missing. The test fails.
  I made these additions:
  - `retry = 5`, plus a wait for "retrying to release a" with no "retrying to put a on hold". This pins down that
    the block comes from the runner's release, not from the done-streak hold, and that its retry is a release.
  - No "(2/3 sessions in a row)", and the session count is still 2 after the retry. This shows the review `done`
    counts exactly once and no session starts while the task is blocked.
  The mutation checks above are reasoning from the code. They were not run, because the test-writer must not edit
  agents.py, not even temporarily.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. release/hold: a failed push to `wip/<id>` or of the board commit prints `PUSH FAILED: <git error>`, exits non-zero, no traceback; the task is still put on hold locally or the agent is told only a human can fix it | `TasksPy.test_release_with_refused_wip_push`, `test_hold_with_refused_wip_push`, `test_release_with_refused_board_push`, `test_release_with_wip_saved_but_board_push_refused`, `test_hold_of_unclaimed_task_with_refused_board_push`, `test_done_with_refused_push`, `test_release_still_works_when_pushes_do` | `python -m unittest tests.test_push_failure tests.test_overbudget` | Pass |
| 2. after force_release()/a review, the runner checks the task state and exit code; logs "release FAILED"; goes `blocked` in `logs/<agent>.status.json` with the error; retries at most every --blocked-retry (default 1800 s); no new session until fixed or the push works | `ReviewReleaseRefused`, `ReviewHoldRefused`, `ReviewDoneRefused` (new), `ReviewReleaseConflict`, `ReviewReleaseConflictWipRefused`, `ReleaseRefused`, `HoldRefusedInSession`, `RetryAfterFix`, `TakenAwayWhileBlocked`, `DroppedWhileBlocked`, `TakenAwayWithNothingLeft`, `OldPushFailureIgnored`, `OtherTaskPushFailureIgnored` | `python -m unittest tests.test_push_failure tests.test_overbudget` | Pass |
| 2 (same loop on the usage-limit path, task assumption): hand_off() with refused pushes keeps the work and blocks | `UsageLimitHandOffRefused`, `UsageLimitHandOffConflict`, `UsageLimitHandOffConflictWipRefused` | `python -m unittest tests.test_push_failure tests.test_overbudget` | Pass |
| 3. the same push error from `done` in 3 sessions in a row blocks the same way; no more sessions on that task | `DoneRefused`, `DoneStreakDifferentErrors`, `DoneStreakBrokenBySession`, `DoneStreakErrorNamesCommit`, `DoneRefusedOnlyOnMain`, `DoneRefusedDirtyTree`, `DoneRefusedMergeInProgress`, `DoneBlockedHoldRefused`, `ReviewDoneRefused` (a review `done` counts once in the streak) | `python -m unittest tests.test_push_failure tests.test_overbudget` | Pass |
| 4. tests simulate a remote that rejects pushes (pre-receive hook in a bare repo); `sh scripts/check.sh` passes | all of `tests/test_push_failure.py` (pre-receive hooks in a bare `remote.git`); `scripts/check.sh` | `python -m unittest tests.test_push_failure tests.test_overbudget`; `sh scripts/check.sh` | Pass |
| Runner hooks added to `tests/test_overbudget.py` keep the old over-budget and usage-limit behaviour | `tests/test_overbudget.py` (all) | `python -m unittest tests.test_push_failure tests.test_overbudget` | Pass |

No automated test covers the default 1800 s retry interval itself. The tests use `--blocked-retry 5`, or 1800
with a check that no session or retry happens within ~8 s while the task is blocked. Waiting 30 min is not practical.

## Focused test execution

Command:

`python -m unittest tests.test_push_failure.ReviewDoneRefused`, then
`python -m unittest tests.test_push_failure tests.test_overbudget` (finished 2026-10-20T19:53:07+02:00)

Result:

Pass. `ReviewDoneRefused`: 1 test, 10.4 s, OK. Both modules: 40 tests, 457.3 s, OK, exit 0.

## Full test execution

Command:

`python -m unittest discover tests` (2026-10-20T19:53:20+02:00 to 20:01:54+02:00, on the hashed snapshot)

Result:

Pass: 87 tests, 513.2 s, OK, exit 0.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (2026-10-20T20:02:07+02:00 to 20:02:49+02:00, exit 0, "== all checks passed"), on
the hashed snapshot.

## Bugs found

No production defect found in the task's changed behaviour at HEAD 7c22f7f2.

## Coverage

Status: unavailable. docs/ARCHITECTURE.md "Coverage" configures coverage tooling only for server (JaCoCo), web
(Vitest v8) and android (JaCoCo). Nothing is configured for the Python TFP scripts, and the Python `coverage`
package is not installed: `python -c "import coverage"` -> `ModuleNotFoundError: No module named 'coverage'`
(Python 3.14.8, rechecked 2026-10-20 for this revision). Installing it is outside the test-writer's authority. The
code under test also runs in subprocesses (`agents.py run`, plus `tasks.py` called by the fake agent and by the
runner), so measuring it would also need subprocess coverage setup. Follow-up, if wanted: a task that adds Python
coverage tooling for the TFP scripts.

Metric: whole-file coverage of task-changed production files. Not measured.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: runner-push-failure-loop, base
  acf29ae65f1be4dc2bfb6b9d985e2ba949ed015e, HEAD 7c22f7f28a7d6f16952eb785ac1a233e30c2d5a0. The commits and files
  come from the parent and were confirmed with `git log`/`git diff --stat acf29ae6..HEAD` (see Scope).
- Source/test snapshot identifier (including uncommitted inputs): the sha256 hashes in "Scope" above
- Exact included files; renamed/deleted/excluded files and reasons: in scope `agents.py` and `tasks.py`. Both are
  unmeasured because there is no provider. Excluded: the test files (tests, not production) and the review reports
  (documentation). No renames or deletions.
- Provider/version, command, working directory, and test selection: none available (see Status)
- Run start/completion time and exit status: no coverage run
- Generated report paths and report hashes/run identifier: none
- Evidence outputs are fresh and inputs stayed unchanged: the test runs and `scripts/check.sh` used the hashed
  snapshot. The hashes taken before and after the runs are identical.
- Final run after latest test additions and production fixes: yes for the tests (HEAD 7c22f7f2 plus
  `ReviewDoneRefused`); no coverage run

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `agents.py` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |
| `tasks.py` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |

No aggregate: both scoped files are unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `agents.py` review path, `refused = ... pf.get("cmd") != "done"`, the `done` side (found by the code review, not by a coverage tool) | criteria 2/3: a refused review `done` must still lead to the runner's release, block at once and count once in the done streak | `ReviewDoneRefused` added (review CR-1) |
| `agents.py` blocked loop, default `--blocked-retry 1800` | criterion 2: retries no more than every 30 min | Not tested in real time (waiting 30 min is not practical). The tests use short intervals and check that no retry or session happens within ~8 s at 1800 s |

## Unable to test

- The default 30 min retry interval in real time (see above). No follow-up needed beyond the short-interval tests.
- Line/branch coverage of `agents.py`/`tasks.py`: no Python coverage tooling is configured (see Coverage). Follow-up
  only if the project wants coverage numbers for the TFP scripts.
- Running the mutation checks for `ReviewDoneRefused`: that would mean editing production code, which is outside
  the test-writer's authority. The reasoning is in "Tests added or updated".

## Summary

Tests added and all validation passed. No production defects were found. Task-scoped coverage could not be
measured because no Python coverage tooling is configured.
