# Test Report

Date: 2026-10-26

Task: runner-hung-session - Runner: end a session that hangs after its final result

Scope: `git diff f9778f9f2..862d54ce6 -- agents.py tests/test_hung_session.py` (base f9778f9f2a49e6bdbffc8124d2ea509e83cf72a9 = `git merge-base HEAD origin/main`; measured HEAD 862d54ce6c9108b365de49dfdb433645f1c9473c; task code commits 4424d1103 implementation, 0727bb7c5 tests, 632e53f47 fixes for CR-1, CR-2, TW-1), plus this pass's uncommitted additions to `tests/test_hung_session.py`.

Requirements:
- `tasks/runner-hung-session.md` acceptance criteria and its ASSUMPTION note (background-task detection from stream-json)
- `reviews/runner-hung-session/review.md` and `fixes.md` (CR-1, CR-2, TW-1)
- `AGENTS.md`, `docs/QUALITY.md`, `docs/ARCHITECTURE.md` "Coverage"

## Test files changed

- `tests/test_hung_session.py`

## Tests added or updated

Count: 3 (new unit tests in `SettledUnit`; existing tests unchanged)

- `test_task_tool_alias_counts_like_agent`: a background `Task` tool call (older name of `Agent`) counts as pending until its task notification.
- `test_tool_result_for_unknown_tool_use_is_ignored`: a tool_result without a matching tool_use does not count as background and does not unsettle the session.
- `test_odd_content_blocks_are_skipped_and_textless_agent_result_is_not_background`: non-object content blocks are skipped; an Agent tool_result without text is not treated as a background launch (covers `first_text` returning "").

The existing tests already covered every criterion and fixed finding; these three close small gaps in the background-detection heuristic.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: a session that printed a successful result and then stays silent for the grace period is ended with `kill_tree()` and logged `session ended: idle Ns after final result` | `EndsAfterGrace.test_hung_session_ends_after_grace_as_normal_exit_and_runner_continues`, `SettledUnit.test_result_then_silence_is_settled`, `test_idle_counts_from_last_output` | `python -m unittest tests.test_hung_session` | Pass |
| AC1: `--result-grace` option, default 60 s (`run` and `start`) | `Options.test_run_default_grace_is_60`, `test_run_grace_option`, `test_start_default_grace_is_60` | same | Pass |
| AC1: new output during the grace period resets it | `OutputResetsGrace.test_output_after_result_restarts_grace_period`, `SettledUnit.test_new_turn_after_result_unsettles_until_next_result`, `test_idle_counts_from_last_output`, `test_blank_lines_do_not_restart_quiet_time` | same | Pass |
| AC1: a session still running tools (or background tasks) is never killed by this rule | `RunningToolNotEnded.test_session_running_a_tool_after_a_result_runs_until_timeout`, `PendingBackgroundAgentNotEnded.test_session_with_pending_background_agent_runs_until_timeout`, `SettledUnit.test_open_tool_call_is_not_settled`, `test_background_agents_keep_it_unsettled_until_their_notifications`, `test_foreground_agent_and_background_bash`, `test_extra_notification_does_not_go_below_zero`, `test_task_tool_alias_counts_like_agent` | same | Pass |
| AC2: an early end is a normal exit 0: no failure count, no back-off, final result in the log line, runner continues with the actual task state (claims next task) | `EndsAfterGrace.test_hung_session_ends_after_grace_as_normal_exit_and_runner_continues` (two tasks, both done, no `backoff`/`retrying`/`failed sessions`), `EndedSessionResumesUnfinishedTask.test_unfinished_task_is_resumed_after_an_early_end` | same | Pass |
| AC3: fake `--cmd` printing a result then sleeping: ended after the grace period, not after `--timeout` | `EndsAfterGrace...` (asserts GRACE <= n < TIMEOUT and total time < 2 x TIMEOUT, no `timed out`) | same | Pass |
| AC3: output after the result resets the grace period | `OutputResetsGrace.test_output_after_result_restarts_grace_period` | same | Pass |
| AC3: a session without a result is not ended early | `NoResultNotEnded.test_session_without_result_runs_until_timeout`, `SettledUnit.test_no_result_or_failed_result_is_not_settled`, `test_error_result_after_success_unsettles` | same | Pass |
| AC3: `sh scripts/check.sh` passes | whole project check | `sh scripts/check.sh` | Pass |
| CR-1 (grace <= 0 killed every session) | `Options.test_grace_below_1_is_rejected`, `SettledUnit.test_unsettled_idle_is_none_so_no_grace_ends_it` | `python -m unittest tests.test_hung_session` | Pass |
| CR-2 (foreground Agent report mentioning async/background over-counted) | `SettledUnit.test_foreground_agent_report_mentioning_async_or_background_is_not_background`, `test_launch_acknowledgement_in_block_list_is_background`, `test_finished_agent_without_background_hint_is_not_background` | same | Pass |
| TW-1 (non-object `message`/`origin` raised AttributeError in drain()) | `SettledUnit.test_json_with_non_object_message_or_origin_does_not_raise`, `test_non_object_json_only_restarts_quiet_time`, `UnusualJsonLineDoesNotCrashRunner.test_runner_survives_json_line_with_string_message` | same | Pass |

## Focused test execution

Command:

`python -m unittest tests.test_hung_session.Options tests.test_hung_session.SettledUnit`

Result:

Pass (23 tests, 0.02 s)

## Full test execution

Command:

`python -m unittest tests.test_hung_session` (final snapshot, 2026-10-26 01:57:56 to 02:00:15 +01:00)

Result:

Pass (30 tests, 139.4 s, exit 0)

Other `tests/` suites (`test_overbudget`, `test_push_failure`, tasks.py tests) were not rerun in this pass: only tests were added here, and the fixer's report records `python -m unittest tests.test_overbudget tests.test_push_failure` passing on the same production code.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` -> "== all checks passed", exit 0 (2026-10-26 02:00:18 to 02:04:28 +01:00, 250 s). That is above the 2 min warning threshold but well under the 15 min timeout. Most of the time goes to the server/web/android parts, which this task does not touch.

## Bugs found

None. All 30 tests pass on the final code. The earlier findings CR-1, CR-2 and TW-1 are fixed (see `fixes.md`), and the tests above guard them against regressions.

## Coverage

Status: partial. The in-process unit tests were measured with the Python standard library `trace` module. The runner-loop changes run only in `agents.py run` subprocesses, so they are not instrumented.

Metric: whole-file line coverage of `agents.py` (in-process tests only), plus changed-line coverage.
Changed-line coverage: measured. Method: the added lines from `git diff -U0 f9778f9f2 -- agents.py` (hunk `+start,count` ranges), intersected with the executable lines in the `trace` `.cover` annotation (a line counts as executable when it has a hit count or the `>>>>>>` missing marker). Removed and non-executable lines are not in the denominator. Branch coverage was not measured: `trace` has no branch counters.

Why this is not a configured provider: `docs/ARCHITECTURE.md` "Coverage" configures coverage only for server (JaCoCo), web (Vitest v8) and android (JaCoCo). Nothing is configured for the root Python scripts. `coverage.py` is not installed (`python -c "import coverage"` -> ModuleNotFoundError), and installing it is outside a test-writer's authority. The stdlib `trace` module ships with Python 3.14.8 and needs no installation or config change. Measuring the subprocess runner tests would need `coverage.py` with subprocess support (`COVERAGE_PROCESS_START`), which does not exist here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: runner-hung-session; base f9778f9f2a49e6bdbffc8124d2ea509e83cf72a9; HEAD 862d54ce6c9108b365de49dfdb433645f1c9473c; the parent named the commits 4424d1103, 0727bb7c5 and 632e53f47, and `git diff --stat f9778f9f2..HEAD` confirms them (agents.py, tests/test_hung_session.py, reviews/ only).
- Source/test snapshot identifier (including uncommitted inputs): sha256 `agents.py` 7bcc4632bbd8470da982b2f73feb09889051af44454014571bb72354da1c76f0 (unchanged from HEAD); `tests/test_hung_session.py` bed2d8548d227b4510e7d337e3939c33bc96f961d82ce7190da53e99f8155e7a (HEAD plus this pass's 3 tests, uncommitted).
- Exact included files; renamed/deleted/excluded files and reasons: included `agents.py`. Excluded `tests/test_hung_session.py` (test file) and `reviews/` (documentation). No renames or deletions.
- Provider/version, command, working directory, and test selection: Python 3.14.8 stdlib `trace`; `python -m trace --count --missing --coverdir=.tmp/tw-hung/trace --ignore-dir=/usr/lib64 --ignore-dir=/usr/lib --module unittest tests.test_hung_session.Options tests.test_hung_session.SettledUnit`, run from the clone root. Changed-line intersection: `python .tmp/tw-hung/changed.py .tmp/tw-hung/trace/agents.cover`.
- Run start/completion time and exit status: 2026-10-26T01:55:57+01:00 to 01:55:57+01:00, exit 0, 23 tests OK.
- Generated report paths and report hashes/run identifier: `.tmp/tw-hung/trace/agents.cover` sha256 a1d647ed9d837bdb8cf6140b48ef9ac3caa358235547d23d3624c9a4ccbe2481 (Git-ignored). The output directory was deleted and recreated just before the run.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The snapshot hashes were taken before the run and compared after it ("unchanged").
- Final run after latest test additions and production fixes: yes. It ran after the fixer's commit 632e53f47 and after the last test addition in this pass.

Values below use covered/total (percentage); unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `agents.py` (whole file, in-process tests only) | N/A (trace has line counters only) | N/A | N/A | 125/713 (17.5%) |
| `agents.py` (changed lines only, in-process tests only) | N/A | N/A | N/A | 55/69 (79.7%) |

The low whole-file figure is expected: most of `agents.py` (start/stop/run loop, git and tasks.py integration) runs only in subprocesses. No files in scope are unmeasured apart from the subprocess-only lines listed below.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `agents.py:245` (`continue` for non-object content block), `agents.py:269` (`first_text` returns "") | Robustness of the background heuristic | Added `test_odd_content_blocks_are_skipped_and_textless_agent_result_is_not_background`; covered in the final run |
| `agents.py:818, 828, 842, 859-862, 865, 868-870, 917` (`cmd_run`: Settled wiring, the grace check with `kill_tree`, `rc = 0` for an early end, the `session ended: idle` log line, failure/crash accounting) | AC1, AC2: end after grace, treat it as exit 0, no back-off | Not instrumentable in-process. Exercised in subprocesses by `EndsAfterGrace`, `OutputResetsGrace`, `EndedSessionResumesUnfinishedTask`, `NoResultNotEnded`, `RunningToolNotEnded`, `PendingBackgroundAgentNotEnded` and `UnusualJsonLineDoesNotCrashRunner`, which assert the log line, timings, no back-off and task state (all pass). Line 917's crash branch with `rc` 0 is checked indirectly: `EndsAfterGrace` sessions end in under 20 s and the test asserts no `retrying`. |
| `agents.py:391-392` (`cmd_start` passes `--result-grace` to `run`) | AC1 option reaches the runner | Not covered: `cmd_start` launches detached runners and is stubbed in `Options`. Parsing of `start --result-grace` is covered (`test_start_default_grace_is_60`, `test_grace_below_1_is_rejected`). The pass-through line is trivial; no test added. |

## Unable to test

- Line-level coverage of the subprocess runner loop (`cmd_run`) and of `cmd_start`'s argv. The only tool available is `trace`, which cannot follow subprocesses, and `coverage.py` is not installed. The behavior itself is tested end-to-end through the subprocess tests above. Follow-up (optional, for the humans): configure Python coverage for the root scripts if task-scoped numbers are wanted for agents.py/tasks.py.
- A real `claude -p` session that hangs after background sub-agents (the original incident). The tests use a scripted fake `--cmd` that emits the same stream-json shape, which is what AC3 asks for.

## Summary

Tests added and all validation passed. The final code has no production defects. Changed-line coverage of the in-process unit tests is 55/69 lines; the remaining lines are subprocess-only runner code covered behaviorally by the runner tests.
