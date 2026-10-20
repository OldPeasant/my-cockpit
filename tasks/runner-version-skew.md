---
title: Runner: same tasks.py as agents.py; lost push races are not refusals
status: in_progress
owner: dell26/agent-5
deps: []
touches: [agents.py,tasks.py,tests]
prio: 1
lease_until: 2026-10-20T22:07:06Z
extended: 
releases: 1
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
- [x] The runner never runs with a `tasks.py` that doesn't match its `agents.py`: e.g. it fast-forwards its clone to the latest main before importing `tasks` (and refuses with a clear message if that isn't possible), or imports `tasks.py` from its own folder; whichever is chosen is recorded with `tasks.py assume`. No `AttributeError` when a clone is behind
  - verified (2026-10-20 23:45+0200 by dell26/agent-5): python -m unittest tests.test_push_race (BehindClone, ImportTasks: runner on a clone behind main missing a new tasks.py attribute runs with agents.py's own tasks.py, no AttributeError) -> 28 tests OK
- [x] `tasks.py` tells lost races (non-fast-forward / fetch first / cannot lock ref / transient `remote rejected ... (failed)`) apart from real refusals (auth, permission, protected branch, hook): only real refusals print `PUSH FAILED` with the "only a human can fix it" hint and write the push-failure record; 5 lost races say so plainly ("lost the push race 5 times, try again") and don't block the task
  - verified (2026-10-20 23:45+0200 by dell26/agent-5): python -m unittest tests.test_push_race (Classify, Races, runner give_up classes: race errors -> BUSY 'lost the push race 5 times', no push-failure record; auth/hook/ruleset refusals -> PUSH FAILED at once with record) -> 28 tests OK
- [x] Tests in `tests/`: a runner started on a clone that is behind main (missing a new `tasks.py` attribute) works; a remote that rejects with a race-style error is not reported as refused, one that rejects with an auth/hook error still is. Existing `tests/test_push_failure.py` and the other tooling tests pass, and `sh scripts/check.sh` passes
  - verified (2026-10-20 23:45+0200 by dell26/agent-5): each tests/test_*.py via python -m unittest tests.MODULE: assumptions 6, done_gate 16, overbudget 9, push_failure 31, push_race 28, quality_gate 20, skip_touches 7 -> all OK (117); sh scripts/check.sh -> all checks passed

## Notes
- After done: back-port to upstream TFP (`tfp/`, OldPeasant/tfp), the tooling files are identical there.

## Notes
- ASSUMPTION (2026-10-20 21:36+0200 by dell26/agent-2): Runner imports tasks.py from agents.py's own folder (the human's checkout, same commit as agents.py) instead of fast-forwarding the clone: the clone may hold unpushed task work that a fast-forward can't move past. Falls back to the clone's tasks.py only if none sits next to agents.py. Subprocess calls (tasks.py heartbeat/release in the clone) still use the clone's copy, which tasks.py's own sync updates.
- ASSUMPTION (2026-10-20 21:36+0200 by dell26/agent-2): Push errors are classified by git's text: race words (non-fast-forward, fetch first, cannot lock ref, failed to update ref, stale info, '(failed)') count as a lost race only if no refusal word (hook declined, denied, permission, protected branch, authentication, error: 403, refusing, ...) is present; unknown errors (e.g. network) stay refusals as before. 5 lost races exit with 'BUSY: ... lost the push race 5 times ... try again' (not 'LOST', which AGENTS.md treats as 'stop'); a real refusal now fails at once instead of after 5 attempts. The runner's give_up retries a BUSY release/hold up to 3 times.
- ASSUMPTION (2026-10-20 22:20+0200 by dell26/agent-2): Supersedes the earlier push-classification assumption: only errors with a refusal word (incl. 'denying', e.g. denyNonFastForwards) fail at once with PUSH FAILED; race-word and unknown errors (HTTP 500, hung up) are retried within 5 attempts; after 5, a race exits BUSY ('lost the push race 5 times'), an unknown error exits PUSH FAILED with the record as before this task. agents.py give_up logs and does not block() when its release/hold still lost every race.
- extended by dell26/agent-2: Implementation done (c0457575, d7fd7621: runner imports its own tasks.py; push errors classified race/refusal/unknown, BUSY after 5 lost races; give_up doesn't block). Tests in tests/test_push_race.py incl. new hook-refusal tests (5fb6c131). review.md + fixes.md exist for an earlier revision. Left: run python -m unittest discover -s tests (full run took >10 min last time; check nothing hangs) and sh scripts/check.sh; re-run test-writer (tests.md is missing) and code-reviewer on the final code; write quality.json; verify criteria 1-3; done.
- release by dell26/agent-2: Implementation and quality helpers complete. HEAD fd2e193c: review.md (0 findings, reviewed 80cce15e; production code identical at 9cd821bb, so evidence is fresh) and tests.md (coverage unavailable: no Python coverage tool) committed, plus fixes.md from the earlier CR-1. sh scripts/check.sh passed at fd2e193c (2026-10-20). Left: (1) run python -m unittest discover -s tests once more (earlier full run 114 OK; it is slow, use a timeout) and tests/test_push_failure.py; (2) tasks.py verify runner-version-skew 1-3 with evidence (AC1: tests.test_push_race.BehindClone/ImportTasks; AC2: Classify+Races+runner classes; AC3: full suite + check.sh); (3) write reviews/runner-version-skew/quality.json (reviewed_revision = the final hash, fixer ran for CR-1, no open findings), commit it; (4) tasks.py done.
