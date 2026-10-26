---
title: Runner: end a session that hangs after its final result
status: in_progress
owner: dell26/agent-2
deps: []
touches: [agents.py,tests]
prio: 1
lease_until: 2026-10-26T01:32:38Z
extended: dell26/agent-2
---
## Goal
A `claude -p` session that has printed its final successful `result` event but does not exit must not block
the agent until the session timeout. Seen on web-journal (2026-10-26): agent-2's session (started 00:16:45)
ran `tasks.py done` and printed its final result at 00:26:11, then stayed alive and idle (no child processes,
no output) for 15+ minutes. It had used background sub-agents (test-writer, code-reviewer), and the log holds
three `result` events, two of them with `origin.kind: task-notification`. `agents.py` only moves on when the
process exits (`while p.poll() is None`), so the dashboard kept showing agent-2 "working" on a task that was
already done, until the 1800 s `--timeout` killed it.

## Acceptance criteria
- [x] `agents.py`: once a session has printed a successful `result` event and then prints nothing more for a
  grace period (default 60 s, a command-line option like `--timeout`), the runner ends it with `kill_tree()` and
  logs e.g. `session ended: idle <n>s after final result`. Any new output during the grace period (e.g. a
  further turn started by a task notification) resets the grace period; a session that is still running tools
  is never killed by this rule
  - verified (2026-10-26 02:16+0100 by dell26/agent-2): python -m unittest tests.test_hung_session (EndsAfterGrace, OutputResetsGrace, running-tool and pending-background tests; --result-grace option tests) -> 30 passed in 139 s; log shows 'session ended: idle <n>s after final result'
- [x] A session ended this way is treated like a normal exit 0: not counted as a failure (no back-off), its
  final result is used for the log line, and the runner continues with the actual task state (claims the next
  task if the old one is done)
  - verified (2026-10-26 02:17+0100 by dell26/agent-2): python -m unittest tests.test_hung_session: EndsAfterGrace (rc forced 0, no back-off, claims next task b) and EndedSessionResumesUnfinishedTask -> pass (30/30)
- [ ] Tests in `tests/` use a fake `--cmd` that prints a stream-json `result` line and then sleeps, and show: the
  runner ends it after the grace period (not after `--timeout`); output after the result resets the grace period;
  a session without a result is not ended early. `sh scripts/check.sh` passes

## Notes
- ASSUMPTION (2026-10-26 01:00+0100 by dell26/agent-2): Background tasks are detected from stream-json only: an Agent/Task tool_use (unless run_in_background:false) or Bash run_in_background:true whose tool_result mentions background/async/agentId counts as pending, and each result with origin.kind task-notification settles one. If this over-counts, the session just falls back to --timeout (safe); open tool calls (tool_use without tool_result) and any non-result stream-json event after a result also block the early end.
- extended by dell26/agent-2: Implementation, fixes (CR-1, CR-2, TW-1) and re-review (0 findings, reviews/runner-hung-session/review.md) are committed. Left: run test-writer on the final code (no reviews/runner-hung-session/tests.md exists yet; it must include task-scoped coverage), run python -m unittest tests.test_hung_session and sh scripts/check.sh, record verify 1-3, write quality.json (reviewed_revision = HEAD of final code), then done.
