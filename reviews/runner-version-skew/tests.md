# Test Report

Date: 2026-10-20

Task: runner-version-skew - Runner: same tasks.py as agents.py; lost push races are not refusals

Scope: task commits `82ea1576484141d4b2629e048930ffe0864f2bc9..f39b2990f1ec38756347cef05c42300e445f87bd`
(fe58efd9, 89aacea4, ecc0cc4f, a06ae304, f39b2990) on branch `main`, plus this test-writer's
uncommitted edit to `tests/test_push_race.py`. Production files changed by the task: `agents.py`, `tasks.py`.

Requirements:
- `tasks/runner-version-skew.md` acceptance criteria 1-3 and its recorded assumptions (the 22:20 assumption
  supersedes the 21:36 push-classification one)
- `AGENTS.md` (Evidence, quality gate), `docs/QUALITY.md`, `docs/ARCHITECTURE.md` "Coverage"

## Test files changed

- `tests/test_push_race.py`

## Tests added or updated

Count: 2 (both in `Classify`)

- `test_more_real_world_refusals`: an SSH key refusal (`Permission denied (publickey)`), a GitLab
  protected-branch message, and a GitHub "Write access ... not granted ... error: 403" are all classified
  as refusals and not as lost races.
- `test_without_an_argument_the_last_push_error_is_classified`: `refused()` / `lost_race()` without an
  argument classify `push_error[0]` (the way `update()` and `save_work()` call them) for a race, a refusal
  and an unknown error. Restores `push_error` afterwards.

The task's existing tests (`tests/test_push_race.py`, 25 tests before this edit, and
`tests/test_push_failure.py`) already covered every acceptance criterion end to end. I found no other
gaps worth adding a test for.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: the runner uses the `tasks.py` next to `agents.py` (falls back to the clone's); no `AttributeError` when the clone is behind | `ImportTasks.test_uses_the_tasks_py_next_to_agents_py`, `ImportTasks.test_falls_back_to_the_clones_tasks_py_without_one_next_to_agents_py`, `BehindClone.test_runner_on_a_clone_behind_main_does_not_crash` | `python -m unittest tests.test_push_race tests.test_push_failure -v` | Pass |
| AC2: lost races (non-fast-forward, fetch first, cannot lock ref, `(failed)`) are told apart from refusals | `Classify.test_lost_races`, `test_refusals`, `test_unknown_errors_are_neither`, `test_more_real_world_refusals` (new), `test_without_an_argument_the_last_push_error_is_classified` (new) | same | Pass |
| AC2: 5 lost races give "BUSY ... lost the push race 5 times ... try again", no PUSH FAILED, no push-failure record, the task is not blocked (board, wip, hold, done) | `Races.test_board_push_that_loses_every_race_is_not_refused`, `test_wip_push_that_loses_every_race_is_not_refused`, `test_board_push_that_loses_to_concurrent_pushes_is_not_refused`, `test_hold_that_loses_every_race_is_not_refused`, `test_done_that_loses_every_race_is_not_refused_and_keeps_the_code`, `test_board_push_that_loses_four_races_succeeds_in_the_fifth_attempt` | same | Pass |
| AC2: real refusals still print PUSH FAILED + "Only a human can fix it" and write the record (and fail at once) | `Races.test_hook_refusal_with_race_words_is_still_refused`, `test_forced_wip_push_denied_as_non_fast_forward_is_refused`, `test_board_push_refused_by_the_remote_fails_at_once`, `test_done_push_refused_by_the_remote_fails_at_once_and_keeps_the_code`, `test_wip_push_refused_by_the_remote_fails_at_once_and_keeps_the_work`; all of `tests/test_push_failure.py` | same | Pass |
| AC2 (assumption): unknown errors are retried; after 5 attempts they give PUSH FAILED | `Races.test_board_push_that_fails_once_for_an_unknown_reason_is_retried`, `test_wip_push_that_fails_once_for_an_unknown_reason_is_retried`, `test_board_push_that_keeps_failing_for_an_unknown_reason_fails_after_5_attempts`, `test_wip_push_that_keeps_failing_for_an_unknown_reason_fails_after_5_attempts` | same | Pass |
| AC2 (runner side): lost races don't block the task; give_up retries; a blocked retry that only lost races retries again soon | `AgentReleaseLosesRaces`, `ForceReleaseLosesRaces`, `ForceReleaseKeepsLosingRaces`, `BlockedRetryLosesRaces` | same | Pass |
| AC3: existing `tests/test_push_failure.py` passes | all 31 tests in `tests/test_push_failure.py` (27 in `test_push_race`) | same | Pass |
| AC3: other tooling tests and `sh scripts/check.sh` pass | (whole suite) | `python -m unittest discover -s tests`, `sh scripts/check.sh` | Not run: the parent asked me not to run the whole suite because it is running it at the same time |

## Focused test execution

Command:

`python -m unittest tests.test_push_race tests.test_push_failure -v` (repo root, Python 3.14.8, started
2026-10-20T22:45+02:00, finished 22:56:34+02:00)

Result:

Pass: `Ran 58 tests in 677.308s`, `OK`, exit 0. Before that, `python -m unittest tests.test_push_race.Classify -v`
gave 5 tests, OK.

## Full test execution

Command:

`python -m unittest discover -s tests`

Result:

Not run by the test-writer: the parent worker is running it at the same time and asked me not to duplicate it.

## .taskcheck

Result:

Not run by the test-writer. This is `sh scripts/check.sh`, and the parent runs it as part of final validation.

## Bugs found

None. No test failed, and I found no production behavior that contradicts the acceptance criteria or the
recorded assumptions.

## Coverage

Status: unavailable. No coverage tooling is configured for the root Python tooling (`agents.py`,
`tasks.py`). `python -m coverage` gives `No module named coverage`, the repository has no venv, and
`docs/ARCHITECTURE.md` "Coverage" defines providers only for server (JaCoCo), web (Vitest v8) and
android (JaCoCo). Installing a provider is outside the test-writer's authority. The stdlib `trace`
module is not a substitute either: almost all tests run `tasks.py` / `agents.py` as subprocesses, so
it would only see the in-process `Classify` calls.

Metric: whole-file coverage of task-changed production files: not measured.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: runner-version-skew;
  base 82ea1576484141d4b2629e048930ffe0864f2bc9; HEAD f39b2990f1ec38756347cef05c42300e445f87bd; task
  commits and file scope given by the parent and confirmed with `git log`/`git diff --stat 82ea1576..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 `agents.py` be6e6be5...a54a,
  `tasks.py` 777e5f86...3fd, `tests/test_push_race.py` (uncommitted, with the new tests) 6fa7d104...db57;
  identical before and after the focused run.
- Exact included files; renamed/deleted/excluded files and reasons: scope would be `agents.py`, `tasks.py`.
  Excluded: `tests/test_push_race.py` (test), `reviews/runner-version-skew/*` (docs). No renames or deletions.
- Provider/version, command, working directory, and test selection: none available (see Status).
- Run start/completion time and exit status: no coverage run. The focused test run is above (exit 0).
- Generated report paths and report hashes/run identifier: none.
- Evidence outputs are fresh and inputs stayed unchanged: inputs were unchanged during the focused test run.
  No coverage output exists.
- Final run after latest test additions and production fixes: yes for the focused tests (they ran on HEAD
  f39b2990 with the new tests). Coverage was not run.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `agents.py` | N/A (not measured) | N/A | N/A | N/A |
| `tasks.py` | N/A (not measured) | N/A | N/A | N/A |

In place of measured coverage, I checked each changed production branch by hand against a test that
drives it. This is a review, not a measurement:

| File and changed location | Required behavior or risk | Test that drives it, or the gap |
|---|---|---|
| `agents.py` `import_tasks()`, both branches | AC1 | `ImportTasks` (both branches), `BehindClone` |
| `agents.py` give_up `run()` 3-attempt loop on `RACE_HINT` | AC2 runner | `ForceReleaseLosesRaces` (2nd attempt succeeds), `ForceReleaseKeepsLosingRaces` (all 3 lose) |
| `agents.py` give_up "lost every push race" branch, `blocked[tid][1]` reset | AC2 runner, task not blocked | `ForceReleaseKeepsLosingRaces`, `AgentReleaseLosesRaces`, `BlockedRetryLosesRaces` (asserts the retry comes after about 60 s) |
| `agents.py` `BUSY` prefix in the error picker | an error is shown when a BUSY comes with a push-failure record or an err | not driven directly. This combination needs a BUSY and a fresh push-failure record in the same command, which the current `tasks.py` doesn't produce. Low risk, left uncovered. |
| `tasks.py` `refused()` / `lost_race()` incl. default argument | AC2 classification | `Classify` (5 tests, 2 new) |
| `tasks.py` `update()`: publish and commit+push branches, `refused()` break, BUSY vs PUSH FAILED exits | AC2 | `Races` board/hold tests (publish path), the done tests (`with_code` path), refusal and unknown-error tests |
| `tasks.py` `save_work()` 5-attempt loop, BUSY and PUSH FAILED exits | AC2 wip | `Races` wip tests: lost race, refused, `denyNonFastForwards`, unknown once, unknown always |

## Unable to test

- Coverage numbers: no Python coverage provider is installed or configured. Follow-up, if humans want
  numbers for the tooling: add `coverage` to the tooling stack, which is a separate decision.
- AC3 "`sh scripts/check.sh` passes" and the whole `tests/` suite: the parent is running these itself
  and asked me not to. The parent must record their results.
- The interplay with real hosted remotes (GitHub/GitLab) wasn't run. The tests use a local bare remote
  with hooks and lock files, and the classification tests use real-world error text.

## Summary

Tests added and all validation passed. I added 2 classification tests and ran 58 focused tests: all
passed. No production defects found. Task-scoped coverage couldn't be measured (no Python coverage
tooling). The full suite and `.taskcheck` are left to the parent.
