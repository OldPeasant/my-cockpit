# Code Review

Date: 2026-10-20

Task: runner-version-skew - Runner: same tasks.py as agents.py; lost push races are not refusals

Scope: `git diff 4ffe348d..407ab308` (agents.py, tasks.py, tests/test_push_race.py)

Requirements:
- tasks/runner-version-skew.md (acceptance criteria and recorded assumptions)
- AGENTS.md task protocol (meaning of LOST / REFUSED / PUSH FAILED for agents)
- docs/QUALITY.md artifact contract
- Existing runner behavior in agents.py (push_failure record, block(), give_up())

Git range:
- Branch: main
- HEAD: 407ab308abdb5445d297b00b996f38cf7cc64a09
- Base/start: 4ffe348dce176cba462448e5f9cc46768ffde0e9
- Commits reviewed: 2 (40815b1c, 407ab308); no uncommitted changes

Summary: 0 P0, 0 P1, 2 P2, 2 P3

Note: running the test suites was not permitted in the reviewer's sandbox, so test results come from
reading the code, not from a run. The acceptance-criterion evidence (`tasks.py verify`) has not been
recorded yet. The parent worker must record it.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A forced wip push that the server denies as non-fast-forward is classified as a lost race**
    - File: `tasks.py:309` (REFUSED_ERRORS), used by `tasks.py:958-966` (save_work)
    - Category: Correctness
    - Problem: If a remote has `receive.denyNonFastForwards=true` (the default of `git init --bare --shared`), git's receive-pack rejects a force push with `remote: error: denying non-fast-forward refs/heads/wip/<id> (you should pull first)` and ` ! [remote rejected] HEAD -> wip/<id> (non-fast-forward)`. The text contains the race word `non-fast-forward` and no refusal word (`denying` does not match `denied`). So `lost_race()` returns True. save_work retries 5 times and exits `BUSY ... try again`, where it used to exit `PUSH FAILED` with the "only a human" hint. A forced push cannot lose a non-fast-forward race, so this is always a real refusal. The agent is told to retry something that can never work. The runner retries it 3x2 times and then blocks the task anyway, with a BUSY message instead of the real cause. A plain push that loses a real race between ref advertisement and update fails with `cannot lock ref ... expected`, not `denying non-fast-forward`. Treating `denying` as a refusal therefore does not misclassify real races.
    - Why it matters: Criterion 2 requires real refusals to print PUSH FAILED and write the record. A server-side policy refusal is now reported as a transient race.
    - Suggested fix: Add `denying` to REFUSED_ERRORS (it covers `denying non-fast-forward` and `denying ref deletion`). Add the receive-pack message to `Classify.test_refusals`.

- [ ] **CR-2 | Transient push errors that are neither race nor refusal now fail immediately and block the task**
    - File: `tasks.py:506-509` (update), `tasks.py:960` (save_work)
    - Category: Correctness
    - Problem: Before this change, update() retried any failed push up to 5 times (with a sync in between) and only then called push_failed. Now any error without a race word calls push_failed on the first attempt. That includes transient server or transport failures such as `RPC failed; HTTP 500`, `the remote end hung up unexpectedly`, or `! [remote rejected] main -> main (Internal Server Error)`. push_failed writes the push-failure record. At session end the runner calls `block(tid, ...)` for every non-`done` command (agents.py, `pf.get("cmd") != "done"` branch). So one transient 5xx during e.g. `tasks.py verify` or `assume` now stops sessions on the task for `--blocked-retry` (30 min), shows "a human must fix it", and tells the agent "Only a human can fix it: stop working". The recorded assumption says unknown errors "stay refusals as before", but before they got 5 attempts. Criterion 2 also says only real refusals (auth, permission, protected branch, hook) should print PUSH FAILED.
    - Why it matters: Before, these blips were absorbed by the retries. Now they block the task and stop the agent: a resilience regression introduced by this task.
    - Suggested fix: Treat only errors with a refusal word as immediate refusals. Retry race-word and unknown errors within the 5 attempts. After 5 attempts, report unknown errors as before (PUSH FAILED) and race-only errors as BUSY. Alternatively, keep the immediate refusal only when a REFUSED_ERRORS word matches. Correct the assumption text either way.

## P3 Low

- [ ] **CR-3 | Sustained lost races still end in block() with "a human must fix it"**
    - File: `agents.py:572-601` (give_up)
    - Category: Correctness
    - Problem: When release/hold keeps returning BUSY, give_up runs it 3 times. It then saves wip, resets to `@{u}` and runs it 3 more times. If all still lose, it takes the line starting with `BUSY` as `err` and calls `block(tid, err)`. That logs `BLOCKED on <id>: pushes fail ... until a human fixes it` and stops sessions for `--blocked-retry`. This takes about 30 lost races, so it is unlikely. But the outcome contradicts criterion 2 ("don't block the task") and the BUSY text itself ("is not blocked; try again").
    - Why it matters: The block message misleads (it calls for a human), and it costs 30 minutes on a task nobody refused.
    - Suggested fix: On a final BUSY in give_up, don't block. Log it and leave the task to the next main-loop round (or use a short retry with a non-"human" status text).

- [ ] **CR-4 | The runner's BUSY retry in give_up is untested**
    - File: `tests/test_push_race.py`
    - Category: Tests
    - Problem: The tests cover tasks.py's BUSY exits (board and wip push) and the behind-clone runner. Nothing checks that agents.py `give_up` retries a release that returned BUSY and then succeeds, rather than blocking. That path is new in agents.py:575-581.
    - Why it matters: The retry loop and its `RACE_HINT` coupling to tasks.py's message text could regress silently.
    - Suggested fix: Add a runner test where the remote's `refs/heads/main.lock` exists while the fake agent's session ends and the runner force-releases, and is removed shortly after. Assert that the task gets released and no `BLOCKED on` line appears.

## Review conclusion

Material issues found; fix before completing the task.

Criterion 1 is met: import_tasks() loads tasks.py from agents.py's folder with TASKS_ROOT pointing at
the clone, and the BehindClone test exercises it. The race/refusal split in criterion 2 works for the
listed cases, but CR-1 and CR-2 are classification gaps against that criterion. Fix both (they are
small), or record a deliberate decision on them, before `done`.
