# Code Review

Date: 2026-10-20

Task: runner-push-failure-loop - Runner: stop the loop when release/hold cannot push

Scope: the full task diff `git diff 6645dab9d8afdcd12747f1d372a5103ab90abec1..HEAD` for agents.py, tasks.py, tests/test_push_failure.py and tests/test_overbudget.py (task commits 174b994a..9e8fe809). The production code was read in full at HEAD, with a focus on the fix commit 9e8fe809 (earlier CR-1 and CR-2). Reviewed revision: 9e8fe8090c562beee3731a7b80ea01fcf65a49e8

Requirements:
- tasks/runner-push-failure-loop.md (goal, 4 acceptance criteria, notes and assumptions)
- AGENTS.md (task protocol, Evidence, quality gate)
- docs/QUALITY.md (report contract)
- reviews/runner-push-failure-loop/fixes.md (the fixer's claims, checked against the code)
- Existing conventions in agents.py and tasks.py

Git range:
- Branch: main
- HEAD: 9e8fe8090c562beee3731a7b80ea01fcf65a49e8
- Base/start: 6645dab9d8afdcd12747f1d372a5103ab90abec1
- Commits reviewed: 13 (174b994a, 3965319e, 33bebc29, 8ad21aaf, 87579784, 6cf2569d, f09c182e, aa636597, a6056170, ecf43167, 2961d1a8, 3b6396bf, 9e8fe809)

Uncommitted changes at review time: `tests/test_push_failure.py` has 25 added lines in the working tree. A test-writer was adding them at the same time. They were not reviewed. Only the committed code was reviewed.

Validation run by the reviewer: `python -m unittest tests.test_push_failure.ReviewHoldRefused tests.test_push_failure.ReviewReleaseRefused tests.test_push_failure.ReviewReleaseConflict tests.test_push_failure.UsageLimitHandOffRefused tests.test_push_failure.UsageLimitHandOffConflict` -> 5 tests OK (29.5 s). The working tree contains the test-writer's uncommitted additions, but they only add lines after line 408 and do not change these classes' committed tests. `python tasks.py verify runner-push-failure-loop` shows that no criterion has recorded evidence yet. The parent must record it before `done`.

Status of the previous review's findings (on 9a6fd2f5):
- CR-1 (a refused `hold` chosen in an over-budget review was replaced by a release): fixed. `pf = push_failure(t0)` is now read before the review branch (agents.py:765). If the review's own non-`done` command was refused (`refused`, agents.py:777), `force_release()` is skipped (agents.py:778). The task stays in `now_mine`, so it is not added to `avoid`. The block at agents.py:788-791 then stores the hold reason, and the blocked loop retries `hold <reason>` (agents.py:656-661). The regression test ReviewHoldRefused checks the whole sequence and passed in the reviewer's run. A refused `done` in a review still goes through `force_release()`: `refused` is false for `cmd == "done"`. Its own refused release blocks the task, and the session's `done` failure, which is now read first, still counts toward the done streak (agents.py:792-801). There is no regression there. The streak now also counts in this path, which it did not before, because the release's record used to overwrite it.
- CR-2 (duplicated merge-abort/dirty-commit steps): fixed. `give_up()` (agents.py:558-562) is now the only copy. `force_release()` and `hand_off()` call it directly. The hand-off commit message is now "wip: <id> (before hand-off)". No test, tool or doc reads the old "handed off: <reason>" commit subject (checked by grep). The reason is still in the board note "handed off by ...".

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | No test covers a refused `done` inside an over-budget review, the branch the CR-1 fix deliberately kept**
    - File: `agents.py:777` (`pf.get("cmd") != "done"` in `refused`)
    - Category: Tests
    - Problem: The fix splits the review path on the session's push-failure command. A refused `release`/`hold`/`extend` skips `force_release()`. A refused `done` still runs it, and the done streak is then counted on top. ReviewHoldRefused covers the first branch. ReviewReleaseRefused and ReviewReleaseConflict* cover reviews that decide nothing, with no session push failure. No test runs a review session whose agent calls `done` against a refusing remote. So no test covers the `cmd == "done"` side of the new condition, or the interplay between the block set by `force_release()` and the streak count that follows.
    - Why it matters: The condition is new and easy to invert or widen in a later edit, for example by dropping the `!= "done"`. Nothing would fail. A review `done` would then skip the release, and the task would get another review session instead of being blocked.
    - Suggested fix: Add a runner test with a fake agent whose review runs `done` against WORKFLOW_HOOK. Assert "release FAILED for a" and "BLOCKED on a", a "(1/3 sessions in a row)" log line, and no new sessions (`assert_blocked_without_new_sessions`).

## Review conclusion

No material issues found.

Notes on the criteria (from reading the code at HEAD, plus the focused test run above):
- Criterion 1: met. A refused push to `wip/<id>` (save_work, tasks.py:932-936) or of the board commit (update(), tasks.py:488-493, including the `publish()` path) prints `PUSH FAILED: <git error>` with an "Only a human can fix it" hint. It exits non-zero through `sys.exit`, with no traceback. The TasksPy tests cover both cases.
- Criterion 2: met. After `force_release()`, a review session or a hand-off, `give_up()` checks the exit code and `owned_ids()`. It logs "<label> FAILED", never "released", and `block()` publishes state `blocked` with the error to `logs/<agent>.status.json`. Retries are spaced by `--blocked-retry` (default 1800 s), and no session starts on the task in between. The block is cleared when a human takes the task away.
- Criterion 3: met. The same `done` push error (commit hashes normalised) in 3 sessions in a row blocks the task, with the error in the log and status. The retry is a hold, so the task cannot bounce back to the board.
- Criterion 4: the tests use pre-receive hooks in a bare remote as asked. `sh scripts/check.sh` passing must still be recorded with `tasks.py verify`, together with criteria 1-3.
