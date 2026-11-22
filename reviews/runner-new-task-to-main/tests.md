# Test Report

Date: 2026-11-22

Task: runner-new-task-to-main - Runner: a task created while working on another reaches main right away (round 2, final code)

Scope: base e6dd4d5b179285d3d2f149fa85825a621c3f8e35 (given by the parent) to HEAD
5fae6b550 (final code). Task-owned commits: 3e677822d (implementation), 7d0558ccb (tests and round-1 reports),
6fd0ab02d (fix: `new` refuses without `--body` when a remote exists, hints in `drop` and `review-assumption` name
`--body FILE`, AGENTS.md step 7 covers `NOT created` from a failed catch-up), 5fae6b550 (fixer report only).
Production change: `tasks.py` (`cmd_new`, `--body` option, `shlex` import, two hint strings). Docs: `AGENTS.md`
step 7. Tests: `tests/test_new_task.py`, `tests/test_done_gate.py`. Working tree clean at measurement (no
uncommitted inputs); this round changed no test files.

Requirements:
- `tasks/runner-new-task-to-main.md` acceptance criteria and recorded assumptions (`--body`, tfp/ back-port left to a human)
- `AGENTS.md` (task protocol, step 7), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`
- `reviews/runner-new-task-to-main/fixes.md` (what the fix commit changed)

## Test files changed

- none in this round (the round-1 and fix-commit test changes are already committed in 7d0558ccb and 6fd0ab02d)

## Tests added or updated

Count: 0 in this round.

Review of the final behaviour against the existing tests (all in `tests/test_new_task.py` unless noted):
- Refusal without `--body` when a remote exists (fix commit): `test_without_body_nothing_is_published` asserts a
  nonzero exit, `NOT created`, the `--body .tmp/c.md` hint, no local file, remote main unchanged and local HEAD
  unchanged. This covers "nothing written, nothing pushed".
- The refusal comes after the duplicate-id and blank `--quality-exempt` checks: `test_existing_local_file_is_refused_without_push`
  (no `--body`, still gets `exists`) and `test_empty_quality_exempt_reason_creates_nothing` (no `--body`, still gets
  `needs a reason`) both pass, so the check order is pinned.
- Without a remote, `new` without `--body` still commits the skeleton locally: `NoRemote.test_without_remote_it_is_a_local_commit`
  and `NoRemote.test_without_remote_other_staged_changes_stay_out_of_the_commit`. Other suites (`test_quality_gate.py`,
  `test_assumptions.py`, `test_skip_touches.py`, `test_overbudget.py`, `test_done_gate.py` setUp) call `new` without
  `--body` before a remote exists and pass, so the local path is exercised broadly.
- `tests/test_done_gate.py` `Touches.test_new_writes_touches` and `Touches.test_next_prefers_tasks_away_from_work_in_progress`
  now pass `--body` (they run with a remote), via the `Touches.body` helper.
- `test_same_id_created_by_another_agent_first` now gives the other clone a `--body` file too.
- Catch-up failure (`sync()` raising, AGENTS.md step 7 `NOT created` wording): `test_offline_creates_nothing`
  asserts `NOT created` and `Retry`. A CONFLICT in the creator's own work goes through the same `except SystemExit`
  branch; no separate test was added because it would exercise the identical code path.
- The two hint-string changes (`drop` of a done task, `review-assumption ... rejected`) are message wording only;
  no test asserts their exact text and none was added (low risk, no behaviour).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. `new` publishes the task file to main in its own commit (only that file), with the claim/release retry and race handling, independent of the work branch | `test_new_task_reaches_main_alone_while_work_stays_on_wip`, `test_push_race_with_another_agent_is_retried`, `test_protected_branch_race_gh006_is_retried`, `test_transient_gh006_without_race_is_retried`, `test_losing_every_race_creates_nothing`, `test_same_id_created_by_another_agent_first`, `test_quality_exempt_goes_through_the_published_path` | `python3 tests/test_new_task.py` | Pass |
| 1. Without a remote: local commit on main | `NoRemote.test_without_remote_it_is_a_local_commit`, `NoRemote.test_without_remote_other_staged_changes_stay_out_of_the_commit` | `python3 tests/test_new_task.py` | Pass |
| 1/3. With a remote and no `--body`: refused, nothing written or pushed (fix commit) | `test_without_body_nothing_is_published` | `python3 tests/test_new_task.py` | Pass |
| 2. Creator's clone has the file once, no later merge conflict when its task is done | `test_new_task_reaches_main_alone_while_work_stays_on_wip`, `test_another_clone_can_claim_it_right_away`, `test_creator_finishes_its_task_without_conflict_on_the_new_file`, `test_creators_uncommitted_and_staged_work_survives` | `python3 tests/test_new_task.py` | Pass |
| 3. Push failure (no access, offline): clear message, how to retry, nothing half-created | `test_refused_push_creates_nothing_and_says_how_to_retry`, `test_offline_creates_nothing`, `test_losing_every_race_creates_nothing`, `test_missing_body_file`, `test_empty_quality_exempt_reason_creates_nothing`, `test_existing_local_file_is_refused_without_push`, `test_without_body_nothing_is_published` | `python3 tests/test_new_task.py` | Pass |
| 4. AGENTS.md step 7 describes the new behaviour; kept in sync with `tfp/` | none (documentation; `tfp/` is absent from this clone, recorded as an assumption by the parent) | not applicable | Not testable: documentation content; read in the diff, step 7 matches the implemented messages (`NOT created`, `PUSH FAILED`, retry command, `--body`) |
| 5. Tests cover: visible on origin/main while work stays on wip; another clone can claim right away; push race | `test_new_task_reaches_main_alone_while_work_stays_on_wip`, `test_another_clone_can_claim_it_right_away`, `test_push_race_with_another_agent_is_retried`, `test_protected_branch_race_gh006_is_retried` | `python3 tests/test_new_task.py` | Pass |
| 6. `sh scripts/check.sh` passes | none | not run | Not run: the parent asked the test-writer not to run Gradle or scripts/check.sh; the parent runs it |
| Existing `new` callers in other suites still work with the final contract | `Touches.test_new_writes_touches`, `Touches.test_next_prefers_tasks_away_from_work_in_progress`, all of `test_done_gate.py`, `test_quality_gate.py`, `test_skip_touches.py`, `test_assumptions.py`, `test_overbudget.py`, `test_publish.sh` | see "Full test execution" | Pass |

## Focused test execution

Command:

`python3 tests/test_new_task.py`

Result:

Pass (18 tests, 8.9 s).

## Full test execution

Command (each run separately from the clone root on HEAD 5fae6b550, clean tree):

- `python3 tests/test_done_gate.py` - Pass, 16 tests (21.3 s)
- `python3 tests/test_quality_gate.py -q` - Pass, 22 tests (23.0 s)
- `python3 tests/test_skip_touches.py` - Pass, 7 tests (2.8 s)
- `python3 tests/test_assumptions.py` - Pass, 6 tests (3.5 s)
- `python3 tests/test_overbudget.py` - Pass, 9 tests (37.9 s)
- `sh tests/test_publish.sh` - Pass ("ALL PASSED"). Section 8 prints a `BrokenPipeError` traceback from
  `cmd_claim`: line 94 of the script pipes `tp A claim a` into `grep -q`, which closes the pipe early. The check
  still passes; the script and `cmd_claim` are unchanged by this task, so this is pre-existing output noise, not a
  task defect.

Result:

Pass. Not run (parent instruction or out of scope): Gradle, `scripts/check.sh`, web/android suites,
`tests/test_push_race.py`, `tests/test_push_failure.py`, `tests/test_sync_merge.py`, `tests/test_hung_session.py`
(round 1 ran them on 3e677822d and they passed; the fix commit does not touch the push or sync code they exercise).

## .taskcheck

Result:

Not run (parent instruction: the parent runs `sh scripts/check.sh`).

## Bugs found

No production defects found in the final code. Every listed test passed against HEAD 5fae6b550.

## Coverage

Status: unavailable. coverage.py is not installed (`python3 -m coverage` fails with "No module named coverage"),
and installing tooling is outside the test-writer's authority. `docs/ARCHITECTURE.md` "Coverage" names coverage
tooling only for the server, web and android parts, not for `tasks.py`. Even with coverage.py, the tests run
`tasks.py` as a subprocess in temporary clones (a copy of `tasks.py` per fixture), so measuring it would need
subprocess coverage configuration (`COVERAGE_PROCESS_START` and a startup hook) that does not exist here.

Metric: whole-file coverage of task-changed production files. Not measured.
Changed-line coverage: not measured (same reason).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: runner-new-task-to-main; base e6dd4d5b179285d3d2f149fa85825a621c3f8e35; HEAD 5fae6b550; task-owned commits 3e677822d, 7d0558ccb, 6fd0ab02d, 5fae6b550 named by the parent and confirmed with `git log --oneline e6dd4d5b1..HEAD` and `git diff --stat e6dd4d5b1..HEAD`
- Source/test snapshot identifier (including uncommitted inputs): HEAD 5fae6b550, clean tree; sha256 `tasks.py` a50f8f5f65b59c44ea629824843f4ed6adeccec520afff135906fb905575f089, `tests/test_new_task.py` 67b07bb7a75dfdb396e0a5d44bcba12ff13d1039f62442efaa82e970b1f873e4, `tests/test_done_gate.py` 6d72283448a3d23894c1c6f97723e8cbda09b7a154dcc16180273c6a22ee63c5, `AGENTS.md` cc96ff37ab4c033837ad02c47142b0c1dd0937ce7facaad08bd8ab7e21779c92
- Exact included files; renamed/deleted/excluded files and reasons: in scope `tasks.py` (`cmd_new`, the `new` parser options, two hint strings). Excluded: `AGENTS.md` (documentation), `tests/` (tests), `reviews/` (reports). No renames or deletions
- Provider/version, command, working directory, and test selection: none available (see Status)
- Run start/completion time and exit status: no coverage run
- Generated report paths and report hashes/run identifier: none
- Evidence outputs are fresh and inputs stayed unchanged: not applicable (no coverage output); the tests ran on the clean HEAD snapshot above
- Final run after latest test additions and production fixes: yes for the tests (run on 5fae6b550, after the fix commit); no coverage run

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `tasks.py` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |

Manual branch review of `cmd_new` on the final code instead of measured coverage:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `tasks.py` `cmd_new`: `p.exists()` before anything else | refuse a duplicate id | `test_existing_local_file_is_refused_without_push` |
| `tasks.py` `cmd_new`: blank `--quality-exempt` | refuse, nothing created | `test_empty_quality_exempt_reason_creates_nothing` |
| `tasks.py` `cmd_new`: no `--body` and an upstream exists (fix commit) | refuse, nothing written or pushed | `test_without_body_nothing_is_published` |
| `tasks.py` `cmd_new`: `--body` read and its OSError | body published; unreadable file refused | `test_new_task_reaches_main_alone_while_work_stays_on_wip`, `test_missing_body_file` |
| `tasks.py` `cmd_new`: no upstream, local commit (with or without `--body`) | criterion 1 (no remote) | both `NoRemote` tests, plus the setUp of other suites |
| `tasks.py` `cmd_new`: `sync()` raises SystemExit | criterion 3 (offline, or a conflict in the creator's work) | `test_offline_creates_nothing` (same branch for both causes) |
| `tasks.py` `cmd_new`: `p.exists()` after sync | another agent created the same id first | `test_same_id_created_by_another_agent_first` |
| `tasks.py` `cmd_new`: publish succeeds | criterion 1 | several tests |
| `tasks.py` `cmd_new`: `refused()` then break, refusal message | criterion 3 (no access) | `test_refused_push_creates_nothing_and_says_how_to_retry` |
| `tasks.py` `cmd_new`: `lost_race()`, retry without pause; message after 5 races | criterion 1 (race handling) | `test_push_race_with_another_agent_is_retried`, `test_protected_branch_race_gh006_is_retried`, `test_losing_every_race_creates_nothing` |
| `tasks.py` `cmd_new`: neither race nor refusal, `time.sleep(RETRY_PAUSE)` and retry | transient failure (GH006 without a race) | `test_transient_gh006_without_race_is_retried` |
| `tasks.py` `cmd_new`: final message "the push failed (offline?)" after 5 unknown failures | message wording only | not tested: needs 5 consecutive unknown push errors with a pause between tries; low risk, same code path as the tested messages |
| `tasks.py` `cmd_drop` / `cmd_review_assumption`: hint strings now name `--body FILE` | message wording only | not tested: text only, no behaviour change |
| `tasks.py` `publish()`: warning when the creator's commits cannot be replayed | not reachable from `new` in practice: the published commit only adds the new task file, which `new` refuses if it exists in the clone | justified gap (shared pre-existing function) |

## Unable to test

- Criterion 4 (AGENTS.md wording, `tfp/` sync): documentation; `tfp/` is not in this clone. No follow-up from tests.
- Criterion 6 (`sh scripts/check.sh`): not run by instruction; the parent must run it.
- Task-scoped coverage: coverage.py is not installed, and `tasks.py` runs as a subprocess in fixtures (see Coverage). Follow-up only if the project decides to add coverage tooling for the TFP scripts.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The existing tests already cover the
final behaviour, including the fix commit's refusal without `--body`, so this round added no tests (the code review
stays fresh). Coverage could not be measured (no coverage tooling for `tasks.py`); a manual branch review is
recorded instead.
