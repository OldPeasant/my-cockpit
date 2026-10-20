# Code Review

Date: 2026-10-20

Task: runner-push-failure-loop - Runner: stop the loop when release/hold cannot push

Scope: the full task diff `git diff acf29ae65f1be4dc2bfb6b9d985e2ba949ed015e..HEAD` for agents.py, tasks.py, tests/test_push_failure.py and tests/test_overbudget.py. The focus is commit dd1f9a27, which adds the `ReviewDoneRefused` test for the previous review's CR-1. This round also confirms that the production code has not changed since the last review. Reviewed revision: dd1f9a27d013257a3c78370483485a28f50629c7

Requirements:
- tasks/runner-push-failure-loop.md (goal, 4 acceptance criteria, notes and assumptions)
- AGENTS.md (task protocol, Evidence, quality gate)
- docs/QUALITY.md (report contract)
- The previous review report (on 9e8fe809) and reviews/runner-push-failure-loop/tests.md (claims checked against the code)
- Existing conventions in agents.py, tasks.py and tests/

Git range:
- Branch: main
- HEAD: dd1f9a27d013257a3c78370483485a28f50629c7
- Base/start: acf29ae65f1be4dc2bfb6b9d985e2ba949ed015e (`git merge-base HEAD origin/main`)
- Commits reviewed: 15 (a8efa700, 73e0fcce, 6e5ea4f3, ba1a2b17, cf01c543, 14de7e48, 1c06cc16, bd12cf58, 0aef6f88, 619f0b39, cc746488, afe06d4a, 1a4fc713, 7c22f7f2, dd1f9a27). The new commit since the last review is dd1f9a27.

The working tree was clean when the review started.

Production code unchanged since the last review:
- `git diff 9e8fe809 1a4fc713 -- agents.py tasks.py` is empty. 9e8fe809 is the revision the last review covered, from before the rebase.
- `git diff --stat 1a4fc713 HEAD` lists only reviews/runner-push-failure-loop/review.md, reviews/runner-push-failure-loop/tests.md and tests/test_push_failure.py (+31 lines).
- agents.py and tasks.py are therefore byte-identical to the code reviewed last time.
- `git diff --stat 9e8fe809 HEAD` also lists dashboard/app/* files. They come from main between the two bases, not from this task.

Validation run by the reviewer:
- `python -m unittest tests.test_push_failure.ReviewDoneRefused` -> 1 test, OK (10.4 s).
- The committed tests/test_push_failure.py has sha256 ad432559...ce133. This is the snapshot that tests.md says was tested.
- `python tasks.py verify runner-push-failure-loop` shows that no criterion has recorded evidence yet. The parent must record it before `done`.

Status of the previous review's finding:
- CR-1 (P3: no test covered a refused `done` inside an over-budget review): resolved by dd1f9a27.
  - The fake agent: `REVIEW_DONE_AGENT` replaces the review's `extend` branch with `tp("done", tid, "--force")`. An `assert` guards that the replacement actually happened.
  - The run: with budget 1, session 1 claims and commits `.github/workflows/ci.yml`. Session 2 is the review. Its `done` is refused, so `pf.cmd == "done"`. `refused` is false at agents.py:777, so `force_release()` runs. Its push to `wip/a` is refused, which logs "release FAILED for a" and calls `block(a, err)` with no hold reason (agents.py:587-588). Then the session's `done` failure, read at agents.py:765 before the release, logs "done could not push a (1/3 ...)" (agents.py:796) without changing the block. The blocked loop then retries with a release (agents.py:663).
  - The assertions match this path exactly: blocked after 2 sessions, "release FAILED for a", "(1/3 sessions in a row)" with no "(2/3", no "released a for another agent", no hold on the board, a "retrying to release a" retry, no "retrying to put a on hold", and still 2 sessions.
  - The test catches the regressions CR-1 named. If `!= "done"` were dropped, the review would skip `force_release()`, and the task would only be blocked by the streak, after 4 sessions. "release FAILED" would be missing, "(2/3" would appear, and the retry would be a hold, so several assertions would fail. If `pf` were read after `force_release()` again, `give_up()` would consume the session's record, and the "done could not push a (1/3" assertion would fail.
  - Timing: the test is not racy in practice. The log lines it reads are written in the same main-loop iteration as "BLOCKED on a" (agents.py:543, then 796). They are read only after `assert_blocked_without_new_sessions` has waited for the blocked status and slept 8 s. The release retry (retry = 5 s) is awaited with `wait_for` and a 30 s timeout. A retry that runs during the sleep leaves the published state `blocked` (`block()` republishes it), and it starts no session. That is the behavior under test.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

Notes on the criteria (the code is unchanged since the last review, so the earlier analysis still holds; the new test strengthens criteria 2 and 3):
- Criterion 1: met. A refused push to `wip/<id>` (save_work in tasks.py) or of the board commit (`update()`/`publish()`) prints `PUSH FAILED: <git error>` with an "Only a human can fix it" hint. It exits non-zero with no traceback. The TasksPy tests cover both cases.
- Criterion 2: met.
  - After `force_release()`, a review session or a hand-off, `give_up()` checks the exit code and `owned_ids()`. It logs "<label> FAILED", never "released".
  - `block()` publishes state `blocked` with the error to `logs/<agent>.status.json`.
  - Retries are spaced by `--blocked-retry` (default 1800 s), and no session starts on the task in between.
  - ReviewHoldRefused, ReviewReleaseRefused and now ReviewDoneRefused cover the review branches.
- Criterion 3: met. The same `done` push error (commit hashes normalised) in 3 sessions in a row blocks the task, with the error in the log and status. ReviewDoneRefused also shows that a refused review `done` counts once in the streak.
- Criterion 4: the tests use pre-receive hooks in a bare remote as asked. The parent must still record a passing `sh scripts/check.sh` with `tasks.py verify`, together with evidence for criteria 1-3.
