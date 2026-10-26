# Code Review

Date: 2026-10-26

Task: runner-hung-session - Runner: end a session that hangs after its final result

Scope: `git diff 54f311b0aa9b2bd39a0c90f492df5cdfdd1124d5..67e0c1b32c1607a39f4a0f064fd15312eb494911 -- agents.py tests/test_hung_session.py`. The range also holds heartbeat, claim and verify commits and other tasks' work (and-cal-cache, deploy-oauth-env, and-gtd-load-error). Those changes and all `tasks/` and `reviews/` files are out of scope. This is a re-review of the final code after the fixer resolved the earlier CR-1 and CR-2 findings and test-writer finding TW-1.

Requirements:
- tasks/runner-hung-session.md (acceptance criteria 1-3 and the recorded ASSUMPTION about background-task detection)
- AGENTS.md (task protocol, Evidence, quality gate)
- docs/ARCHITECTURE.md (`tests/` runner tests run separately from `scripts/check.sh`)
- reviews/runner-hung-session/fixes.md (the fixes to confirm)
- Existing agents.py conventions (timeout, lease and stop handling, `kill_tree`, `detached`, `describe`)

Git range:
- Branch: main
- HEAD: 67e0c1b32c1607a39f4a0f064fd15312eb494911
- Base/start: 54f311b0aa9b2bd39a0c90f492df5cdfdd1124d5
- Commits reviewed: 3 task code commits (fcdf8eec8 implementation, 154b6ab86 tests, 67e0c1b32 fixes). The other 22 commits in the range do not touch agents.py or tests/test_hung_session.py. The working tree was clean.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Confirmation of earlier findings

- **CR-1 (grace 0 or negative kills every session): fixed.** `Settled.idle()` now returns `None` while the session is unsettled: no successful result yet, an open tool call, or a pending background task. The poll loop ends the session only when `q is not None and q >= a.result_grace` (agents.py:859). Separately, the argparse type `positive` rejects values below 1 for both `run` and `start` (agents.py:272-276, 939, 952). Either change alone would close the hole. Covered by `Options.test_grace_below_1_is_rejected` and `SettledUnit.test_unsettled_idle_is_none_so_no_grace_ends_it`.
- **CR-2 (background heuristic over-counting): fixed.** `BACKGROUND_HINT` is now anchored with `re.match` to the launch acknowledgement phrases (`async agent launched` / `command running in background`). It is applied only to the first text of the tool_result content, through `first_text`, which handles both a string and a list of blocks (agents.py:216, 252, 263-269). A foreground report that mentions async, background or agentId, or that has the launch phrase in a later block, no longer counts as background. Covered by the two new `SettledUnit` tests. The runner test `PendingBackgroundAgentNotEnded` still shows that a real launch acknowledgement blocks the early end.
- **TW-1 (AttributeError on a non-object `message`/`origin`): fixed.** There are now `isinstance(..., dict)` guards at agents.py:234-235 and 240-241. Covered by a unit test and by the runner test `UnusualJsonLineDoesNotCrashRunner`.

## Acceptance criteria check

1. **Early end after the grace period.** A successful result followed by `--result-grace` seconds (default 60, a command-line option, passed from `start` to `run`) of no output leads to `kill_tree()`. The runner then logs `session ended: idle <n>s after final result: <result>`. Every non-blank line resets `last`. Any stream-json event other than a result clears `result`, so a new turn started by a task notification unsettles the session until its own result arrives. Open tool_use ids and pending background launches block the early end. The stop flag, a lost lease and `--timeout` are still checked first. Met.
2. **Treated like a normal exit 0.** When `quiet` is set, `rc` is forced to 0, so SIGTERM on POSIX and taskkill on Windows do not count as failures. `failed` and the crash back-off use `rc`. The log line uses `final[0]`. Budget accounting and the next claim run on the real task state. The runner tests `EndsAfterGrace` (claims b after a, no back-off) and `EndedSessionResumesUnfinishedTask` (the unfinished task is resumed) show this. Met.
3. **Tests.** tests/test_hung_session.py uses a fake `--cmd` that prints a stream-json result and sleeps. It shows the session ending after the grace period and not after `--timeout`, output resetting the grace period, a session without a result running until `--timeout`, and a running tool not being cut off. I ran `python -m unittest tests.test_hung_session`: 27 tests passed in 138 s. I did not run `sh scripts/check.sh` in this review. fixes.md reports that it passed. The parent worker must record the `.taskcheck` evidence. Met. The criteria are not yet recorded with `tasks.py verify`, which is expected at this stage and is the parent worker's step.

## Verified without findings

- If a JSON `tool_use` has a non-object `input` or an unhashable `id`, `Settled.feed` still raises. I checked this with a direct call. This is not new: `describe()` already crashes on the same non-object `input` (`tool_doing` calls `inp.get`), it runs right after `Settled.feed`, and Claude Code's stream-json always sends an object there. So the change adds no new failure.
- Losing the early end is the safe direction: background launches that never get a task notification, or several notifications combined into one result turn, leave the session running until `--timeout`. The recorded assumption accepts this.
- `last` is set when a line is drained, not when it was written, so the quiet time can only be under-counted. Partial lines without a newline are not fed, which is fine for line-based stream-json.
- The new code is platform-neutral: it reuses `kill_tree()` and `detached()`, the same as the timeout path.
- The module docstring documents the new behavior and the option. No dependency or configuration file changed. No secrets or sensitive data are logged.

## Review conclusion

No material issues found.
