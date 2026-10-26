# Code Review

Date: 2026-10-26

Task: runner-hung-session - Runner: end a session that hangs after its final result

Scope: committed task diff `git diff 7f4b36c2..6283c761b` (agents.py, tests/test_hung_session.py). Uncommitted edits to tests/test_hung_session.py that showed up in the working tree during the review (+133 lines, apparently from the test-writer) are not part of this review.

Requirements:
- tasks/runner-hung-session.md (acceptance criteria and the recorded ASSUMPTION note)
- AGENTS.md (task protocol, Evidence, quality gate)
- docs/ARCHITECTURE.md ("tests/" section: TFP tooling tests run separately from check.sh)
- Existing agents.py conventions (timeout/lease/stop handling, kill_tree, detached)

Git range:
- Branch: main
- HEAD: 6283c761b704b6bd6daf9f0f5fdb413869a42dda
- Base/start: 7f4b36c271d8cceb4c7a5cfae8f1345d73e82e91
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 2 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | `--result-grace 0` (or a negative value) kills every session at its first poll and reports it as a clean exit**
    - File: `agents.py:840` (with `agents.py:253-257` and `agents.py:920`, `agents.py:933`)
    - Category: Correctness
    - Problem: `Settled.idle()` returns `0` whenever the session is *not* settled (no result yet, an open tool call, or a pending background task). The poll loop tests `settled.idle() >= a.result_grace`. With `--result-grace 0`, that becomes `0 >= 0`, which is always true. So the runner calls `kill_tree()` on the first poll (about 5 s after start) for every session, including sessions that are still running tools or have printed nothing. It then sets `quiet = 0`, which makes `rc = 0` and `failed = False`. A negative value does the same. The option is not validated, and its help text does not say 0 is invalid. Users often pass 0 to mean "disable".
    - Why it matters: This breaks the criterion that "a session that is still running tools is never killed by this rule". The runner would kill all real work over and over and log each kill as a normal exit, so it is not counted as a failure, there is no back-off, and the fails >= 3 hand-off never triggers. That is an endless loop of 5-second sessions that looks healthy.
    - Suggested fix: Return `None` (or check settledness explicitly) when the session is not settled, and only end it when it is settled and `idle >= grace`. Also either reject `--result-grace < 1` in argparse or make `0` explicitly mean "disabled" (and say so in the help). Add a unit or runner test for the 0 case.

- [ ] **CR-2 | The background-task heuristic over-counts foreground Agent calls, so the early end often never happens in normal TFP sessions**
    - File: `agents.py:215`, `agents.py:243-251`
    - Category: Correctness
    - Problem: An `Agent`/`Task` tool_use counts as "may be background" unless `run_in_background` is explicitly `false`. When its tool_result arrives, `BACKGROUND_HINT` (`background|async|agentId`, case-insensitive) is searched across the *whole* serialized result content. A foreground sub-agent's tool_result is the sub-agent's full final report. In this project those reports come from test-writer and code-reviewer, which run in every implementation session and very often contain "async" or "background". The code-reviewer instructions themselves list "out-of-order async results", and this task's own review talks about background tasks throughout. Claude Code may also add an `agentId:` resume hint to foreground Agent results. Each match does `self.background += 1`, but no `task-notification` result ever arrives to bring it back down. `idle()` then returns 0 for the rest of the session.
    - Why it matters: The recorded assumption accepts over-counting as a safe fallback to `--timeout`. But here the over-count is the common case, not a rare one. Any session that ran a foreground reviewer or test-writer whose report mentions async code loses the early end, so the hang this task fixes (a 30-minute stall with the dashboard showing "working") still happens in typical sessions. The unit test `test_finished_agent_without_background_hint_is_not_background` only uses the text "all fine", so it does not cover this.
    - Suggested fix: Detect "launched in background" from the start of the tool_result or its structured form, not anywhere in the report. For example, match only an anchored launch phrase (`^Async agent launched` / `running in background with ID`) on the first text block, or use the structured `tool_use_result` status field if the stream provides one. Add a unit test where a foreground Agent's report contains "async"/"background"/"agentId" and the session still settles after the result.

## P3 Low

None.

## Verified without findings

- The idle rule: any newline-terminated output updates `last`. Any stream-json event other than `result` clears `result`, so a new turn unsettles the session. Open tool_use ids block the early end, and so do pending background tasks. `last` is set when the line is drained, not when it was written, so the quiet time is under-counted, which is the safe direction.
- Precedence in the poll loop: stop flag, then a lost lease, then `--timeout`, all checked before the idle rule. A timeout that coincides with the idle end is still logged as a timeout and not counted as a failure, same as before.
- Treated as exit 0: `rc` is forced to 0, so SIGTERM (-15) on POSIX and `taskkill /F` (1) on Windows do not count as failures. The log line uses `final[0]` (the last successful result). Budget accounting and claiming the next task follow the normal path. The `< 20 s` crash back-off cannot trigger with the default 60 s grace.
- Windows/macOS: the change reuses `kill_tree()` and `detached()`, the same pattern as the timeout path, and adds no platform-specific code.
- `--result-grace` is passed through from `start` to `run`.
- Tests: the runner tests cover ending after the grace period with no back-off and the next task claimed, output after the result resetting the grace period, no result meaning no early end, and an open tool call meaning no early end. They follow the existing `tests/test_*.py` runner-test pattern, which is not part of `scripts/check.sh` (consistent with docs/ARCHITECTURE.md). The tests could not be run in this review session (command execution was denied), so the pass/fail evidence has to come from the parent worker or the test-writer.

## Review conclusion

Material issues found; fix before completing the task.
