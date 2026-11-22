---
title: Runner: a task created while working on another reaches main right away
status: in_progress
owner: dell26/agent-1
deps: [web-reminders-spec-clock]
touches: [agents.py,tasks.py,tests,AGENTS.md]
prio: 1
lease_until: 2026-11-22T12:57:30Z
---
## Goal
AGENTS.md step 7 says "Missing task discovered? `tasks.py new ...`, commit, push", but agents never push to main: their
commits go to `wip/<id>` and reach main only with `done`. A task an agent creates while working on another therefore
stays on that wip branch until the creating task is done, and no agent can claim it.

This deadlocked on 2026-11-22: `and-cal-initial-scroll` found that `.taskcheck` fails on main (date-dependent
`task-reminders.spec.ts`) and created `web-reminders-spec-clock` to fix it (commit `e8d87f2b8`, only on
`origin/wip/and-cal-initial-scroll`). `done` kept failing because of exactly that bug, so the fix task never reached
main, nobody claimed it, and the agent released three times and finally put its task on hold. A human had to copy the
task file to main by hand.

## Acceptance criteria
- [x] `tasks.py new` publishes the new task file to main by itself (its own commit containing only that task file,
  pushed with the same retry/race handling as `claim`/`release`), independent of the creating agent's work branch;
  without a remote it commits it to the local main as today
  - verified (2026-11-22 13:36+0100 by dell26/agent-1): python3 tests/test_new_task.py (local bare remote) -> 18 OK: new pushes its own commit with only tasks/c.md to origin/main (test_new_task_reaches_main_alone_while_work_stays_on_wip), refusal/race handling via publish(); NoRemote tests: local commit of only the task file
- [x] The creating agent's clone ends up with the task file once, without a later merge conflict when its own task
  is done (e.g. the file is committed on main and the work branch rebases onto it)
  - verified (2026-11-22 13:36+0100 by dell26/agent-1): python3 tests/test_new_task.py -> OK: creator HEAD = work commit on top of 'new: c' commit, tasks/c.md in history once, HEAD..origin/main empty, later pull/push of own work without conflict
- [x] If the push fails (no access, offline), `new` says so clearly and how to retry; it never leaves a half-created task
  - verified (2026-11-22 13:36+0100 by dell26/agent-1): python3 tests/test_new_task.py -> OK: refused push (pre-receive hook), 5 lost races and offline remote each print PUSH FAILED/NOT created + retry command, no file in clone or on main, HEAD unchanged
- [x] AGENTS.md step 7 describes the new behaviour (no manual "commit, push" for the task file), kept in sync with
  `tfp/` as described for back-ports
  - verified (2026-11-22 13:36+0100 by dell26/agent-1): read AGENTS.md step 7 (git diff e6dd4d5b1 HEAD -- AGENTS.md) -> describes --body, automatic commit+push to main, NOT created/retry; tfp/ clone absent here, back-port left to a human (recorded assumption)
- [x] Tests in `tests/` cover: new task visible on origin/main while the creator's work stays on `wip/<id>`; another
  clone can `claim` it right away; push race with another agent
  - verified (2026-11-22 13:36+0100 by dell26/agent-1): python3 tests/test_new_task.py -> 18 OK incl. test_new_task_reaches_main_alone_while_work_stays_on_wip, test_another_clone_can_claim_it_right_away, test_push_race_with_another_agent_is_retried (+GH006 race); also test_push_race 35, test_push_failure 31, test_done_gate 16, test_hung_session 32, test_overbudget 9 OK
- [x] `sh scripts/check.sh` passes
  - verified (2026-11-22 13:36+0100 by dell26/agent-1): sh scripts/check.sh on 5fae6b550 (final code) -> exit 0, all checks passed

## Notes
Planned 2026-11-22 by the owner after the `and-cal-initial-scroll` / `web-reminders-spec-clock` deadlock.
- ASSUMPTION (2026-11-22 13:11+0100 by dell26/agent-1): tfp/ (upstream TFP clone) is not present in this agent clone, so the back-port is left to a human: the tasks.py/AGENTS.md/tests changes are self-contained for copying to tfp/ as-is
- ASSUMPTION (2026-11-22 13:11+0100 by dell26/agent-1): Added 'tasks.py new --body FILE': since new now publishes immediately, the Goal/criteria must be in the file before the push (an empty skeleton would be claimable); AGENTS.md step 7 tells agents to write the body first. Without a remote, new now commits the file locally (criterion: 'commits it to the local main').
