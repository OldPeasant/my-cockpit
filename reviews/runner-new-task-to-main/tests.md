# Test Report

Date: 2026-11-22

Task: runner-new-task-to-main - Runner: a task created while working on another reaches main right away

Scope: base e6dd4d5b179285d3d2f149fa85825a621c3f8e35 (git merge-base HEAD origin/main, given by the parent) to
task commit 3e677822d412d840dfba5ef80d668cb29c53b619 (HEAD). Production change: `tasks.py` (`cmd_new`, new
`--body` option, `shlex` import). Docs: `AGENTS.md` step 7. Tests: `tests/test_new_task.py` (new in the task).
Working tree at measurement: clean apart from the test changes below and the untracked `reviews/` folder.

Requirements:
- `tasks/runner-new-task-to-main.md` acceptance criteria and recorded assumptions (`--body`, tfp/ back-port left to a human)
- `AGENTS.md` (task protocol, step 7), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`

## Test files changed

- `tests/test_new_task.py` (extended: 10 tests added, `NoRemote` refactored to a shared setUp)
- `tests/test_done_gate.py` (one test updated to the new contract of `new`, see below)

## Tests added or updated

Count: 11 (10 added in `tests/test_new_task.py`, 1 updated in `tests/test_done_gate.py`)

Added in `tests/test_new_task.py`:
- `test_protected_branch_race_gh006_is_retried` - a lost race answered with GitHub GH006 (protected_race_hook, 2 races) is retried and succeeds; the file is in the creator's history once, its work commit on top
- `test_transient_gh006_without_race_is_retried` - GH006 without a race (gh006_hook, once) is retried, not reported as a refusal
- `test_losing_every_race_creates_nothing` - 5 lost races: `PUSH FAILED`, "lost the push race 5 times" (the RACE_HINT agents.py retries on), "NOT created", no file on origin/main or in the clone, the creator's work commit is still HEAD
- `test_creators_uncommitted_and_staged_work_survives` - main moved meanwhile; the creator's modified tracked file, staged new file and untracked file survive `new` unchanged (staged stays staged), none of them reach main; history is other work, then the task commit, then the creator's work
- `test_quality_exempt_goes_through_the_published_path` - `--quality-exempt` plus `--body` lands on origin/main with `quality: exempt`, the reason and the body
- `test_empty_quality_exempt_reason_creates_nothing` - blank reason is refused before anything is written or pushed
- `test_without_body_the_skeleton_is_published` - without `--body`, the skeleton (Goal / Acceptance criteria) and `--prio` are published
- `test_existing_local_file_is_refused_without_push` - a hand-written local `tasks/c.md` is refused (`exists`), left untouched, remote main unchanged
- `test_creator_finishes_its_task_without_conflict_on_the_new_file` - criterion 2: after `new`, the creator pulls and pushes its work to main without conflict; origin/main history touches `tasks/c.md` exactly once
- `NoRemote.test_without_remote_other_staged_changes_stay_out_of_the_commit` - without a remote, the local commit contains only the task file; other staged changes stay staged

Updated in `tests/test_done_gate.py`:
- `Touches.test_next_prefers_tasks_away_from_work_in_progress` - it ran `new` and then `git add -A; git commit -qm c; git push`.
  With this task `new` commits and pushes by itself, so `git commit` failed with "nothing to commit" (the test failed at
  HEAD 3e677822d). The manual commit/push lines were obsolete under the new contract (criterion 4: no manual "commit,
  push"). They were replaced by assertions that the task file is already on origin/main and the clone has no
  uncommitted tracked changes. The test's actual assertions (the `next` ranking and the claim) are unchanged. This is a
  test that the task itself should have updated; `scripts/check.sh` runs only `tests/test_quality_gate.py`, so
  `.taskcheck` would not have caught it.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. `new` publishes the task file to main in its own commit (only that file), with the claim/release retry and race handling, independent of the work branch | `test_new_task_reaches_main_alone_while_work_stays_on_wip`, `test_push_race_with_another_agent_is_retried`, `test_protected_branch_race_gh006_is_retried`, `test_transient_gh006_without_race_is_retried`, `test_losing_every_race_creates_nothing`, `test_same_id_created_by_another_agent_first`, `test_quality_exempt_goes_through_the_published_path`, `test_without_body_the_skeleton_is_published` | `python3 tests/test_new_task.py` | Pass |
| 1. Without a remote: local commit on main | `NoRemote.test_without_remote_it_is_a_local_commit`, `NoRemote.test_without_remote_other_staged_changes_stay_out_of_the_commit` | `python3 tests/test_new_task.py` | Pass |
| 2. Creator's clone has the file once, no later merge conflict when its task is done | `test_new_task_reaches_main_alone_while_work_stays_on_wip`, `test_another_clone_can_claim_it_right_away` (creator pulls cleanly after), `test_creator_finishes_its_task_without_conflict_on_the_new_file`, `test_creators_uncommitted_and_staged_work_survives` | `python3 tests/test_new_task.py` | Pass |
| 3. Push failure (no access, offline): clear message, how to retry, nothing half-created | `test_refused_push_creates_nothing_and_says_how_to_retry`, `test_offline_creates_nothing`, `test_losing_every_race_creates_nothing`, `test_missing_body_file`, `test_empty_quality_exempt_reason_creates_nothing`, `test_existing_local_file_is_refused_without_push` | `python3 tests/test_new_task.py` | Pass |
| 4. AGENTS.md step 7 describes the new behaviour; kept in sync with `tfp/` | none (documentation; `tfp/` is absent from this clone, recorded as an assumption by the parent) | not applicable | Not testable: documentation content; reviewed by reading the diff, step 7 matches the implemented messages (`PUSH FAILED`, retry command, `--body`) |
| 5. Tests cover: visible on origin/main while work stays on wip; another clone can claim right away; push race | `test_new_task_reaches_main_alone_while_work_stays_on_wip`, `test_another_clone_can_claim_it_right_away`, `test_push_race_with_another_agent_is_retried`, `test_protected_branch_race_gh006_is_retried` | `python3 tests/test_new_task.py` | Pass |
| 6. `sh scripts/check.sh` passes | none | not run | Not run: the parent asked the test-writer not to run Gradle or scripts/check.sh; the parent runs it |
| Existing `new` behaviour used by other suites still works | `Touches.test_next_prefers_tasks_away_from_work_in_progress` (updated), all of `test_done_gate.py`, `test_assumptions.py`, `test_skip_touches.py`, `test_publish.sh` | see "Full test execution" | Pass |

## Focused test execution

Command:

`python3 tests/test_new_task.py`

Result:

Pass (18 tests, about 8.6 s). Final run on the test snapshot listed under Coverage.

`python3 tests/test_quality_gate.py -q`

Result:

Pass (22 tests).

## Full test execution

Command (each run separately from the clone root, after the test changes):

- `python3 tests/test_done_gate.py` - Pass, 16 tests (failed with 1 failure before the update described above)
- `python3 tests/test_assumptions.py` - Pass, 6 tests
- `python3 tests/test_skip_touches.py` - Pass, 7 tests
- `python3 tests/test_push_race.py` - Pass, 35 tests (300 s)
- `python3 tests/test_push_failure.py` - Pass, 31 tests (360 s)
- `python3 tests/test_sync_merge.py` - Pass, 4 tests
- `python3 tests/test_overbudget.py` - Pass, 9 tests
- `python3 tests/test_hung_session.py` - Pass, 32 tests
- `sh tests/test_publish.sh` - Pass ("ALL PASSED")

Result:

Pass. Not run: Gradle builds, web/android suites and `tests/test_check_sh.sh` (out of the task's scope; the parent runs `scripts/check.sh`).

## .taskcheck

Result:

Not run (parent instruction: the parent runs `sh scripts/check.sh`).

## Bugs found

No production defects found in `tasks.py` `cmd_new`. Every new test passed against the unchanged production code.

The only failure found was the obsolete test `Touches.test_next_prefers_tasks_away_from_work_in_progress` in
`tests/test_done_gate.py` (described above). It was a stale test, not a production defect, and is updated in the
working tree; the parent needs to commit that change with the task.

## Coverage

Status: unavailable. `python3 -m coverage` fails with "No module named coverage": coverage.py is not installed, and
installing tooling is outside the test-writer's authority. `docs/ARCHITECTURE.md` "Coverage" names coverage tooling
only for the server, web and android parts, not for `tasks.py`. Even with coverage.py, the tests run `tasks.py` as
a subprocess in temporary clones (a copy of `tasks.py` per fixture), so measuring it would need subprocess coverage
configuration (`COVERAGE_PROCESS_START` and a startup hook) that does not exist in this repository.

Metric: whole-file coverage of task-changed production files. Not measured.
Changed-line coverage: not measured (same reason).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: runner-new-task-to-main; base e6dd4d5b179285d3d2f149fa85825a621c3f8e35; HEAD 3e677822d412d840dfba5ef80d668cb29c53b619; task-owned commit 3e677822d named by the parent and confirmed with `git show --stat 3e677822d`
- Source/test snapshot identifier (including uncommitted inputs): sha256 of `tasks.py` 631565c300b13c05f6c07a03105c66b190dafd316024515f5c62c842520d9275 (unchanged from HEAD), `tests/test_new_task.py` 16b6be84e054a9b956a983b433a3604e76938294eae446ed1b3c3b408dc3f7c9, `tests/test_done_gate.py` bf168bf90ae97ee007a1f2c89be84d1afdcc6b2a52650ce6018d93f60d914074 (both uncommitted)
- Exact included files; renamed/deleted/excluded files and reasons: in scope `tasks.py` (`cmd_new` and the `new` parser options). Excluded: `AGENTS.md` (documentation), `tests/test_new_task.py` (test). No renames or deletions
- Provider/version, command, working directory, and test selection: none available (see Status)
- Run start/completion time and exit status: no coverage run; `python3 -m coverage --version` exited 1 at about 13:15 +01:00
- Generated report paths and report hashes/run identifier: none
- Evidence outputs are fresh and inputs stayed unchanged: not applicable (no coverage output)
- Final run after latest test additions and production fixes: no coverage run; tests were last run at 13:28 +01:00 on the snapshot above, no production fixes happened

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `tasks.py` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |

Manual branch review of `cmd_new` instead of measured coverage (each branch, and the test exercising it):

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `tasks.py` `cmd_new`: `p.exists()` before anything else | refuse a duplicate id | `test_existing_local_file_is_refused_without_push` |
| `tasks.py` `cmd_new`: blank `--quality-exempt` | refuse, nothing created | `test_empty_quality_exempt_reason_creates_nothing` |
| `tasks.py` `cmd_new`: `--body` read and its OSError | body published; unreadable file refused | `test_new_task_reaches_main_alone_while_work_stays_on_wip`, `test_missing_body_file` |
| `tasks.py` `cmd_new`: no upstream, local commit | criterion 1 (no remote) | both `NoRemote` tests |
| `tasks.py` `cmd_new`: `sync()` raises SystemExit | criterion 3 (offline) | `test_offline_creates_nothing` |
| `tasks.py` `cmd_new`: `p.exists()` after sync | another agent created the same id first | `test_same_id_created_by_another_agent_first` |
| `tasks.py` `cmd_new`: publish succeeds | criterion 1 | several tests |
| `tasks.py` `cmd_new`: `refused()` then break, refusal message | criterion 3 (no access) | `test_refused_push_creates_nothing_and_says_how_to_retry` |
| `tasks.py` `cmd_new`: `lost_race()`, retry without pause; message after 5 races | criterion 1 (race handling) | `test_push_race_with_another_agent_is_retried`, `test_protected_branch_race_gh006_is_retried`, `test_losing_every_race_creates_nothing` |
| `tasks.py` `cmd_new`: neither race nor refusal, `time.sleep(RETRY_PAUSE)` and retry | transient failure (GH006 without a race) | `test_transient_gh006_without_race_is_retried` |
| `tasks.py` `cmd_new`: final message "the push failed (offline?)" after 5 unknown failures | message wording only | not tested: needs 5 consecutive unknown push errors, with a 2 s pause between tries; low risk, same code path as the tested messages |
| `tasks.py` `publish()`: warning when the creator's commits cannot be replayed | not reachable from `new` in practice: the published commit only adds the new task file, which `new` refuses if it exists in the clone | justified gap (shared pre-existing function) |

## Unable to test

- Criterion 4 (AGENTS.md wording, `tfp/` sync): documentation; `tfp/` is not in this clone. No follow-up from tests.
- Criterion 6 (`sh scripts/check.sh`): not run by instruction; the parent must run it.
- Task-scoped coverage: coverage.py is not installed, and `tasks.py` runs as a subprocess in fixtures (see Coverage). Follow-up only if the project decides to add coverage tooling for the TFP scripts.

## Summary

Tests added and all validation passed. No production defects found. One obsolete test in `tests/test_done_gate.py`,
broken by the task's intended behaviour change, was updated to the new contract and must be committed by the parent.
Coverage could not be measured (no coverage tooling for `tasks.py`); a manual branch review is recorded instead.
