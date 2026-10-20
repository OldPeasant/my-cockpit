---
title: Runner: stop the loop when release/hold cannot push
status: in_progress
owner: dell26/agent-7
deps: []
touches: [agents.py,tasks.py,tests]
prio: 1
lease_until: 2026-10-20T16:41:39Z
releases: 1
extended: 
---
## Goal
A push that keeps failing (e.g. a token without `workflow` scope, so GitHub rejects `.github/workflows/*`) must
stop the agent's work on the task instead of looping. Seen on p0-ci (2026-10-20, 12:10-15:59): `done` failed
at the push, `hold`/`release` failed too because `save_work()` pushes to `wip/<id>` before recording anything,
so the release counter never reached MAX_RELEASES. `agents.py` `force_release()` then logged "released p0-ci
for another agent: error: failed to push ..." and the runner resumed the same task: ~210 sessions, each running
the full check.

## Acceptance criteria
- [ ] `tasks.py release`/`hold`: a failed push to `wip/<id>` (or of the board commit) prints a clear `PUSH FAILED: <git error>` and exits non-zero; no traceback. The task is still put on hold locally, or the agent is told plainly that only a human can fix it
- [ ] `agents.py`: after `force_release()` or a review session, the runner checks the actual task state (and the exit code of `tasks.py`). If the task is still ours, it logs "release FAILED" (not "released"), stops working on that task and goes to a `blocked` state in `logs/<agent>.status.json` with the error. It retries no more than every 30 min and never starts a new session for that task before a human fixes it or the push works again
- [ ] The same push error from `done` in N sessions in a row (e.g. 3) is treated the same way: blocked, with the error in the status/log; no more sessions on that task
- [ ] Tests in `tests/` simulate a remote that rejects pushes (e.g. a pre-receive hook in a bare repo) and show: release/hold exit non-zero with the message; the runner logic does not resume the task. `sh scripts/check.sh` passes

## Notes
- ASSUMPTION (2026-10-20 17:28+0200 by dell26/agent-7): Fixed the test-writer's hand_off() observation in scope (same refused-push loop on the usage-limit path); left the pre-existing cmd_hold() stale-clone observation (Low, human command path) out of scope
- release by dell26/agent-7: Implementation done (HEAD: give_up() shared by release/hand-off; done-blocked tasks go on hold on retry); python -m unittest tests.test_push_failure -> 25 OK. Left: code changed after the last review, so re-run test-writer + code-reviewer on HEAD, write reviews/runner-push-failure-loop/quality.json, tasks.py verify #1-#4, then done. Note: sh scripts/check.sh had 1 failing server Gradle test (1337 run) unrelated to agents.py/tasks.py; recheck on latest main.
