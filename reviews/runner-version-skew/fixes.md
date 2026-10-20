# Fix Report

Date: 2026-10-20

Task: runner-version-skew - Runner: same tasks.py as agents.py; lost push races are not refusals

Scope: current task, findings CR-1 to CR-4 of `reviews/runner-version-skew/review.md` (base 55fc9344,
task commits 7d853924, d75752e4, 27f9dd39)

## Fixed findings

### CR-1 — A forced wip push that the server denies as non-fast-forward is classified as a lost race

Files changed:
- `tasks.py`
- `tests/test_push_race.py`

Problem:
`remote: error: denying non-fast-forward refs/heads/wip/<id>` plus `! [remote rejected] ... (non-fast-forward)`
(a remote with `receive.denyNonFastForwards`) matched the race word `non-fast-forward` and no refusal word, so
save_work retried 5 times and exited BUSY instead of PUSH FAILED.

Fix:
Added `denying` to `REFUSED_ERRORS`. Added a `refused()` helper (any refusal word). `lost_race()` now uses it.
Tests: the receive-pack message is in `Classify.test_refusals` (not a race, a refusal), and the new
`Races.test_forced_wip_push_denied_as_non_fast_forward_is_refused` runs a real bare remote with
`receive.denyNonFastForwards=true`. It checks that `release` prints PUSH FAILED, writes the record and
does not print BUSY.

Validation:
- `python3 tests/test_push_race.py` — Pass (20 tests)

### CR-2 — Transient push errors that are neither race nor refusal now fail immediately and block the task

Files changed:
- `tasks.py`
- `tests/test_push_race.py`

Problem:
update() and save_work called push_failed on the first error without a race word. That included transient
errors such as `RPC failed; HTTP 500` and `the remote end hung up unexpectedly`. Before this task, these errors
were retried 5 times.

Fix:
update() and save_work now stop retrying early only when a refusal word matches (`refused()`). They retry race
errors and unknown errors within the 5 attempts. After the loop, the last error decides the outcome. A lost race
exits with `BUSY ... lost the push race 5 times`. A refusal, or an error that is still unknown after 5 attempts,
exits with PUSH FAILED and writes the push-failure record, as before this task. The comment above
`RACE_ERRORS` describes this.
Tests: `Classify.test_unknown_errors_are_neither` covers HTTP 500, a dropped connection, `Could not read from
remote repository` and an empty error. The new `flaky_hook` test helper kills receive-pack, so git prints
`fatal: the remote end hung up unexpectedly`. Three new tests use it:
- `test_board_push_that_fails_once_for_an_unknown_reason_is_retried`: the release succeeds, with no PUSH
  FAILED and no record.
- `test_wip_push_that_fails_once_for_an_unknown_reason_is_retried`: the wip is saved and the release succeeds.
- `test_board_push_that_keeps_failing_for_an_unknown_reason_fails_after_5_attempts`: PUSH FAILED and the record
  appear after exactly 5 attempts, with no BUSY.

Validation:
- `python3 tests/test_push_race.py` — Pass (20 tests)

### CR-3 — Sustained lost races still end in block() with "a human must fix it"

Files changed:
- `agents.py`
- `tests/test_push_race.py`

Problem:
When give_up's release or hold still lost every race after all retries, it called `block()`. That logged
"BLOCKED ... until a human fixes it" and paused the task for `--blocked-retry`.

Fix:
In give_up, the output can contain `tasks.RACE_HINT` while there is no push-failure record and no wip-push
error. In that case it logs `<label> of <id> lost every push race to other agents, trying again later: <BUSY
line>` and returns None without blocking. The task stays ours, and the next main-loop round retries it. If the
task was already blocked by an earlier real refusal, its retry time is moved to 60 s from now, so the retry
doesn't wait the full `--blocked-retry`. The docstrings of give_up and force_release and the comment in
hand_off now describe this.
Test: the new `ForceReleaseKeepsLosingRaces` runner test makes the runner's force-release lose all 30 races
(6 runs of 5 attempts). It checks that the log contains the new line and has no `release FAILED` or `BLOCKED`,
and that the next round releases the task.

Validation:
- `python3 tests/test_push_race.py` — Pass (20 tests)

## Not fixed

### CR-4 — The runner's BUSY retry in give_up is untested

Status:
Invalid

Reason:
The finding is no longer valid: commit 27f9dd39 added `ForceReleaseLosesRaces` (`tests/test_push_race.py`). In
that test the runner's own release in give_up loses all 5 races once, then retries after the pause and
succeeds. The test asserts that no `release FAILED`, no `BLOCKED` and no Traceback appear, and that the task
is released. `AgentReleaseLosesRaces` covers the agent-side case. The new `ForceReleaseKeepsLosingRaces`
(CR-3) also covers the case where every retry is used up. No further change was needed.

## Validation summary

Focused tests:
- `python3 tests/test_push_race.py` — Pass, 20 tests
- `python3 tests/test_push_failure.py` — Pass, 31 tests

Full test suite:
- `python3 tests/test_overbudget.py` — Pass, 9 tests
- `python3 tests/test_assumptions.py` — Pass, 6 tests
- `python3 tests/test_done_gate.py` — Pass, 16 tests
- `python3 tests/test_quality_gate.py` — Pass, 20 tests
- `python3 tests/test_skip_touches.py` — Pass, 7 tests
- `bash tests/test_publish.sh` — ALL PASSED

Lint/type-check/build:
- Included in `.taskcheck` (server, web and android lint, unit tests and build).

`.taskcheck`:
- Pass (`sh scripts/check.sh` -> `== all checks passed`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.

The parent worker still needs to correct the recorded assumption. It says unknown errors "stay refusals as before"
and that "a real refusal now fails at once instead of after 5 attempts". The behavior now is: an unknown error is
retried 5 times and then reported as PUSH FAILED (as before this task), only a refusal word fails at once,
`denying` counts as a refusal, and give_up does not block on a final BUSY.
