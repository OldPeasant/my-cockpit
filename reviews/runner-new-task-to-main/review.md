# Code Review

Date: 2026-11-22

Task: runner-new-task-to-main - Runner: a task created while working on another reaches main right away

Scope: committed task change `git diff e6dd4d5b1 3e677822d -- tasks.py AGENTS.md tests/` (one commit). Uncommitted edits to `tests/test_new_task.py` that were in the working tree during the review (probably from the parallel test-writer) were not reviewed.

Requirements:
- tasks/runner-new-task-to-main.md acceptance criteria and notes, including the two recorded assumptions (tfp/ back-port left to a human, new `--body FILE` option)
- AGENTS.md (task protocol, step 7, Evidence)
- docs/ARCHITECTURE.md (the TFP tooling test row: `tests/test_*.py`, `tests/test_publish.sh`)
- existing tasks.py conventions (`update()`, `publish()`, `sync()`, `refused()`/`lost_race()`)

Git range:
- Branch: main
- HEAD: 3e677822d412d840dfba5ef80d668cb29c53b619
- Base/start: e6dd4d5b179285d3d2f149fa85825a621c3f8e35
- Commits reviewed: 1 (3e677822d)

Summary: 0 P0, 1 P1, 1 P2, 1 P3

Checked and found correct:
- `publish()` on a failed push: the new file is not in HEAD, so it is unlinked and nothing is left in the working tree or index (a private index is used). On success the remote-tracking ref is moved and local commits are rebased onto the new commit, so the creator has the file once in its history.
- `sync()` failures (offline fetch/pull, conflict, an unfinished merge) raise SystemExit before anything is written. `cmd_new` catches them and prints "NOT created" with a retry command. On a conflict the CONFLICT_HELP text is kept in the message.
- Exists checks: before sync (a local file) and again after each sync (a task another agent created first, also after a lost race). The test that creates the same id first covers this.
- No-remote path: `upstream()` is None also for a clone of an empty bare remote, so the Board fixture in tests/test_overbudget.py (and the seed in tests/test_publish.sh) gets local commits. Its later `git add -A` / `git commit -qm init` still has tasks.py and board.py to commit, and `push -u` publishes everything.
- Tests run during the review: `python3 tests/test_new_task.py` -> 8 passed; `tests/test_overbudget.py` -> 9 passed; `tests/test_quality_gate.py` -> 22 passed; `tests/test_skip_touches.py` -> 7 passed; `tests/test_assumptions.py` -> 6 passed; `bash tests/test_publish.sh` -> ALL PASSED; `tests/test_done_gate.py` -> 1 failure (CR-1).

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | `new` now commits and pushes by itself, so an existing test in test_done_gate.py fails**
    - File: `tests/test_done_gate.py:190`
    - Category: Tests
    - Problem: `Touches.test_next_prefers_tasks_away_from_work_in_progress` runs `tasks.py new c ...` after the remote exists and then runs `git add -A`, `git commit -qm c`, `git push`. At the base commit `new` only wrote the file. Now it commits and pushes the file itself, so `git commit` finds nothing to commit and the test fails: `python3 tests/test_done_gate.py` -> `FAILED (failures=1)`, "git commit -qm c failed: nothing to commit, working tree clean". `scripts/check.sh` only runs `tests/test_quality_gate.py`, so `.taskcheck` does not catch this. docs/ARCHITECTURE.md lists the other `tests/test_*.py` as the TFP tooling tests.
    - Why it matters: the TFP test suite is red after this change. The change in behaviour breaks an existing caller pattern, and nothing in the check shows it.
    - Suggested fix: remove the manual add/commit/push after `new` in that test, because `new` publishes the file now. Optionally assert that `tasks/c.md` is on the remote main. Then run all `tests/test_*.py` and `tests/test_publish.sh`, not only test_new_task.py.

## P2 Medium

- [ ] **CR-2 | Without `--body`, `new` publishes an empty, immediately claimable task to main**
    - File: `tasks.py:617`
    - Category: Correctness
    - Problem: `--body` is optional. When it is omitted and a remote exists, the default skeleton (empty Goal, a single empty `- [ ] ` criterion, `status: todo`) is pushed to main at once. `ranked()`/`offered()` and `cmd_claim` do not filter tasks without content, so an idle agent can claim it right away (`claim` without an id picks the top ready task). Before this change a human or agent ran `new`, filled the file in, then committed and pushed. That workflow still looks valid (hints at tasks.py:1185 and tasks.py:1331 still say "python tasks.py new ..."), but it now races against running agents. The recorded assumption gives this as the reason for `--body`, but nothing enforces it.
    - Why it matters: an agent can claim a task with no goal and no criteria within seconds. This wastes a session, or the agent invents requirements. Humans who plan with `new` on a live board are affected most.
    - Suggested fix: when an upstream exists, refuse to publish without `--body` (or without a non-empty Acceptance criteria section), with a message that explains how to write the body first. Alternatively, publish a skeleton created without `--body` as `hold` so no agent can claim it. Add a test for the chosen behaviour.

## P3 Low

- [ ] **CR-3 | AGENTS.md step 7 mentions only `PUSH FAILED`, but `new` can also fail during the catch-up with main**
    - File: `AGENTS.md:28`
    - Category: Quality
    - Problem: step 7 says "If it prints `PUSH FAILED`, nothing was created: retry the command it prints later." When `sync()` fails (offline fetch, or a conflict between the creator's own commits and main), `new` exits with git's error or `CONFLICT ...` followed by "Task ... was NOT created ... Retry with: ...", and it does not print `PUSH FAILED`. On a conflict the clone is left in an unfinished rebase, and retrying fails until that is resolved. The changed line is also 160 characters long, which is out of line with the surrounding list items.
    - Why it matters: an agent that follows step 7 literally may not recognise the CONFLICT case as "not created, resolve first, then retry".
    - Suggested fix: change the sentence to say that when `new` prints "NOT created" (a push failure or a conflict while catching up with main), the task does not exist, a conflict must first be resolved as described in "Merge conflicts", and then the printed command can be retried. Wrap the line like the neighbouring ones.

## Review conclusion

Material issues found; fix before completing the task.
