# Code Review

Date: 2026-10-20

Task: runner-push-failure-loop - Runner: stop the loop when release/hold cannot push

Scope: the full task diff `git diff 5cc4dcf620d2c9a11cd6e2b444224cefb649e1d3..HEAD` for agents.py, tasks.py, tests/test_push_failure.py and tests/test_overbudget.py (task commits 9c1da9f1..e357353e, merged into HEAD). Production code was read in full at HEAD. Reviewed revision: 9a6fd2f5965342550b37931d08675272e456f2d6

Requirements:
- tasks/runner-push-failure-loop.md (goal, 4 acceptance criteria, notes and assumptions)
- AGENTS.md (task protocol, Evidence, quality gate, project notes)
- docs/QUALITY.md (report contract)
- Existing conventions in tasks.py and agents.py (give_back(), save_work(), update(), MAX_RELEASES, hand_off(), force_release())

Git range:
- Branch: main
- HEAD: 9a6fd2f5965342550b37931d08675272e456f2d6
- Base/start: 5cc4dcf620d2c9a11cd6e2b444224cefb649e1d3
- Commits reviewed: 11 task commits (9c1da9f1, de4232d9, 70e3fdcb, 4b3fd146, 08393073, b2893e6e, 6ae15386, a89b15a6, f8347bdc, 819fe8d9, e357353e), plus board-only claim/release/merge commits and unrelated web-search commits in the range, which were not reviewed

Uncommitted changes at review time: `tests/test_push_failure.py` is modified in the working tree. A test-writer was editing it at the same time, so the uncommitted edits were not reviewed. The tests reviewed are the ones committed at HEAD.

Validation: the reviewer could not run the test suites, because permission to run tests was denied in this session. The test-writer, fixer and parent report the runs: `python -m unittest tests.test_push_failure` passed with 27 tests and later 28 tests, and `python -m unittest discover tests` passed with 83 tests. `python tasks.py verify runner-push-failure-loop` shows that no criterion has evidence recorded yet. The parent must record it before `done`.

Status of the previous review's findings (on 8237fd5):
- Old CR-1 (an agent's refused `hold` was retried as a release): fixed for the normal session path. tasks.py now puts `reason` in the push-failure JSON for `hold` (tasks.py:294, 311-312, 1401). The session path calls `block(tid, err, hold=pf.get("reason"))` (agents.py:796), and the blocked loop retries `hold <reason>` (agents.py:665-670). A re-block keeps the stored reason (agents.py:550). HoldRefusedInSession checks this end to end. The over-budget review path still has the same problem; see CR-1 below.
- Old CR-2 (a hold retry could reset away uncommitted changes): fixed. `give_up()` now aborts an unfinished merge and commits a dirty tree before it runs tasks.py (agents.py:563-567), so every caller is covered. Tests: DoneRefusedDirtyTree, DoneRefusedMergeInProgress.
- Old CR-3 (stale comment on `blocked`): fixed (agents.py:614-615).

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A refused `hold` chosen in an over-budget review is replaced by a release, so the hold and its reason are lost**
    - File: `agents.py:782-787` (review branch calls `force_release()`) runs before `agents.py:792` (`pf = push_failure(t0)`)
    - Category: Correctness
    - Problem: The REVIEW prompt offers hold as one of its choices. Take the p0-ci case: the agent chooses `tasks.py hold <id> "<reason>"` in the review, and the remote refuses the push. tasks.py writes the push-failure file with `cmd=hold` and the reason, and the task is still ours. The runner then gets to the review branch first. `now_mine[tid]["extended"] != a.owner` is true, so it calls `force_release()`. That runs a counted `tasks.py release`, which is refused again. That run overwrites the push-failure file with `cmd=release` and no reason, and `give_up()` consumes it through `push_failure()`. `block(tid, err)` then stores no hold reason. Line 792 finds no file, so the session's hold failure is never seen. When pushes work again, the blocked retry runs `release --handoff` (agents.py:671-674). The task goes back to `todo` with no hold, and the agent's reason is not kept anywhere. The old CR-1 fix covers only the normal session path. No test covers a review session in which the hold is refused: ReviewReleaseRefused uses a review that decides nothing.
    - Why it matters: Criterion 1 says a failed hold leaves the task on hold, or the agent is told plainly that only a human can fix it. Here the runner overrides the decision the agent made in its review and returns the task to the board. Another agent can then claim a task its owner said needs a human, which is the bouncing this task is meant to stop. The extra `release` attempt is also pointless, because the session has just shown that pushes are refused.
    - Suggested fix: Read the session's push failure before the review branch. For example, move `pf = push_failure(t0)` above the `review` handling. If `pf` is for the reviewed task, skip `force_release()` and use the existing `block(tid, err, hold=...)` handling, so a refused `hold` keeps its reason. Add a runner test: a review session whose fake agent runs `hold` against WORKFLOW_HOOK. It should assert that no "release FAILED" line is logged, the task is blocked, and once the hook is removed the task ends on hold with the agent's reason.

## P3 Low

- [ ] **CR-2 | `force_release()` and `hand_off()` repeat the merge-abort and dirty-commit steps that `give_up()` now runs**
    - File: `agents.py:600-604` (force_release), `agents.py:506-510` (hand_off), duplicated by `agents.py:563-567` (give_up)
    - Category: Quality
    - Problem: Since the CR-2 fix, `give_up()` aborts an unfinished merge and commits a dirty tree for every caller. `force_release()` still does both steps before it calls `give_up()`, and `hand_off()` does the same. The copies in the callers never find anything left to do in `give_up()`. The only difference is the commit message: `hand_off()` uses "handed off: <reason>".
    - Why it matters: There are now three copies of the "save the tree before giving the task back" logic. A later change to one copy, such as a new merge state or the untracked-file handling, can quietly drift from the others.
    - Suggested fix: Drop the two steps from `force_release()`. If the message in `hand_off()` matters, pass it to `give_up()` as its label. Otherwise drop the steps there too.

## Review conclusion

Material issues found; fix before completing the task.

Notes on the criteria (from reading the code at HEAD; the reviewer did not run the tests):
- Criterion 1: met for `release` and `hold`. A refused push to `wip/<id>` (save_work, tasks.py:932-936) or of the board commit (update(), tasks.py:488-493, including the `publish()` path) prints `PUSH FAILED: <git error>`, adds a plain "Only a human can fix it" hint and exits 1 through `sys.exit`, with no traceback. TasksPy tests cover the wip push, the board push, a protected main, and a hold of an unclaimed task. CR-1 is about what the runner then does with a hold refused during a review.
- Criterion 2: met. After `force_release()` or a review session, the runner checks the exit code and `owned_ids()`. It logs "release FAILED" (or "hand-off FAILED"/"hold FAILED"), never "released", and goes to state `blocked` with the error in `logs/<agent>.status.json`. It retries every `--blocked-retry` s (default 1800) and starts no session on the task in between. The blocked entry is cleared when a human takes the task away.
- Criterion 3: met. Three sessions in a row with the same `done` push error block the task; the key ignores commit hashes. The error is shown in the log and the status, and the retry is a hold, so the task can't bounce back.
- Criterion 4: the tests use pre-receive hooks in a bare remote, as the criterion asks. They show that release and hold exit non-zero with the message and that the runner does not resume the task. `sh scripts/check.sh` must still be run and recorded by the parent. As noted above, no criterion has recorded evidence yet.
