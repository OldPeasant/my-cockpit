---
title: Runner: same tasks.py as agents.py; lost push races are not refusals
status: in_progress
owner: dell26/agent-2
deps: []
touches: [agents.py,tasks.py,tests]
prio: 1
lease_until: 2026-10-20T20:09:21Z
---
## Goal
Two runner bugs seen on 2026-10-20 after the runner-push-failure-loop fix:
1. **Version skew:** `agents.py` (from the human's checkout) imports `tasks` from the agent's clone
   (`agents.py` `sys.path.insert(0, str(clone)); import tasks`). Restarted runners whose clones were still
   on an older main crashed after their first session: `AttributeError: module 'tasks' has no attribute
   'PUSH_FAILURE'` (dell26/agent-2 at 21:04; agent-1/3/5 had the same old clone at start).
2. **False "remote refuses the push":** `tasks.py update()` prints "the remote refuses the push (auth,
   permissions, a hook?), so retrying won't help ... Only a human can fix it" after any 5 failed attempts,
   also when all 5 were lost races with other agents (non-fast-forward, `cannot lock ref`, `remote rejected
   ... (failed)`). It also records a push failure for the runner, which can block the task for 30 min.

## Acceptance criteria
- [ ] The runner never runs with a `tasks.py` that doesn't match its `agents.py`: e.g. it fast-forwards its clone to the latest main before importing `tasks` (and refuses with a clear message if that isn't possible), or imports `tasks.py` from its own folder; whichever is chosen is recorded with `tasks.py assume`. No `AttributeError` when a clone is behind
- [ ] `tasks.py` tells lost races (non-fast-forward / fetch first / cannot lock ref / transient `remote rejected ... (failed)`) apart from real refusals (auth, permission, protected branch, hook): only real refusals print `PUSH FAILED` with the "only a human can fix it" hint and write the push-failure record; 5 lost races say so plainly ("lost the push race 5 times, try again") and don't block the task
- [ ] Tests in `tests/`: a runner started on a clone that is behind main (missing a new `tasks.py` attribute) works; a remote that rejects with a race-style error is not reported as refused, one that rejects with an auth/hook error still is. Existing `tests/test_push_failure.py` and the other tooling tests pass, and `sh scripts/check.sh` passes

## Notes
- After done: back-port to upstream TFP (`tfp/`, OldPeasant/tfp), the tooling files are identical there.

## Notes
- ASSUMPTION (2026-10-20 21:36+0200 by dell26/agent-2): Runner imports tasks.py from agents.py's own folder (the human's checkout, same commit as agents.py) instead of fast-forwarding the clone: the clone may hold unpushed task work that a fast-forward can't move past. Falls back to the clone's tasks.py only if none sits next to agents.py. Subprocess calls (tasks.py heartbeat/release in the clone) still use the clone's copy, which tasks.py's own sync updates.
- ASSUMPTION (2026-10-20 21:36+0200 by dell26/agent-2): Push errors are classified by git's text: race words (non-fast-forward, fetch first, cannot lock ref, failed to update ref, stale info, '(failed)') count as a lost race only if no refusal word (hook declined, denied, permission, protected branch, authentication, error: 403, refusing, ...) is present; unknown errors (e.g. network) stay refusals as before. 5 lost races exit with 'BUSY: ... lost the push race 5 times ... try again' (not 'LOST', which AGENTS.md treats as 'stop'); a real refusal now fails at once instead of after 5 attempts. The runner's give_up retries a BUSY release/hold up to 3 times.
