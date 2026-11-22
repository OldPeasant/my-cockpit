# Code Review

Date: 2026-11-22

Task: runner-new-task-to-main - Runner: a task created while working on another reaches main right away

Scope: round 2, the whole task diff `git diff e6dd4d5b1 5fae6b550 -- tasks.py AGENTS.md tests/` (the fix commit 6fd0ab02d included), plus a check that the round-1 findings CR-1..CR-3 are resolved. Committed changes only. The working tree was clean.

Requirements:
- tasks/runner-new-task-to-main.md acceptance criteria and notes, including the two recorded assumptions (the tfp/ back-port is left to a human; the new `--body FILE` option)
- AGENTS.md (task protocol, step 7, Evidence, Merge conflicts)
- docs/ARCHITECTURE.md (TFP tooling tests `tests/test_*.py`, `tests/test_publish.sh`)
- existing tasks.py conventions (`publish()`, `sync()`, `pushed()`, `refused()`/`lost_race()`, RACE_HINT handling in agents.py)
- the round-1 review and reviews/runner-new-task-to-main/fixes.md

Git range:
- Branch: main
- HEAD: 5fae6b5509b66aeca28da0a921e9e25bfce73d8a
- Base/start: e6dd4d5b179285d3d2f149fa85825a621c3f8e35
- Commits reviewed: 4 (3e677822d, 7d0558ccb, 6fd0ab02d, 5fae6b550)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Round-1 findings

- CR-1 (test_done_gate.py fails because `new` now commits and pushes): resolved. `Touches.test_next_prefers_tasks_away_from_work_in_progress` no longer commits and pushes by hand. It now asserts that the file is on the remote main and that the clone is clean. `python3 tests/test_done_gate.py` -> 16 tests OK.
- CR-2 (an empty skeleton is published without `--body`): resolved. `cmd_new` (tasks.py:617-620) exits with "NOT created" and a `--body .tmp/ID.md` hint before it writes or pushes anything, as long as an upstream exists. The path without a remote still commits the template locally. `test_without_body_nothing_is_published` checks that the remote main, the local HEAD and the working tree stay unchanged. The hints in `drop` and `reject` now mention `--body FILE`.
- CR-3 (AGENTS.md step 7 only mentioned PUSH FAILED): resolved. Step 7 now covers both "NOT created" paths: a push failure, and a failed catch-up with main (offline, or a CONFLICT). It points to "Merge conflicts" for the conflict case, and the lines are wrapped like the lines around them. Both failure paths in `cmd_new` print "NOT created" (tasks.py:642 and tasks.py:655), so the text matches the code.

## Criteria check

- Criterion 1 (published to main by itself, in its own commit, with claim/release-style retry; a local commit without a remote): met. `publish()` builds the commit on the upstream with a private index and pushes only that commit. The loop retries lost races, including a protected-branch GH006 when the branch moved, and transient GH006 errors. It stops on a refusal. Without an upstream, `git commit -- rel` commits only the task file, and other staged changes stay out of the commit (tested).
- Criterion 2 (the file reaches the creator's clone once, with no later conflict): met. After the push, `publish()` moves the remote-tracking ref and rebases the local commits onto the new commit. The file then sits in HEAD exactly once, and origin/main is an ancestor, so `done`'s merge has nothing to merge for this file. Tested, including a simulated push of the finished work.
- Criterion 3 (a clear message on push failure, never a half-created task): met. When the push fails, `publish()` removes the file again because HEAD does not have it. A `sync()` failure exits before anything is written. Every failure message prints "NOT created" and a retry command built with `shlex.quote`. Tested for a refused push, for offline, and for losing all 5 races.
- Criterion 4 (AGENTS.md step 7): met for AGENTS.md. The tfp/ sync is covered by the recorded assumption that tfp/ is missing from this clone.
- Criterion 5 (tests: visible on origin/main while the work stays on wip; another clone claims right away; push race): covered by `test_new_task_reaches_main_alone_while_work_stays_on_wip`, `test_another_clone_can_claim_it_right_away`, `test_push_race_with_another_agent_is_retried`, plus GH006, lost-every-race, same-id race and dirty-worktree tests.
- Criterion 6 (`sh scripts/check.sh`): not run by this reviewer, as the parent asked. The parent worker owns it.

Other checks with no finding: the exists check runs again after each `sync()` (another agent may create the same id). Uncommitted, staged and untracked work survives the catch-up and the replay (tested). A lost race is not written to the agents.py PUSH_FAILURE marker. That is right, because the failure belongs to the new id and not to the claimed task, and the RACE_HINT text is still in the message, so agents.py retries on it.

Tests run during this review: `python3 tests/test_new_task.py` -> 18 tests OK; `python3 tests/test_done_gate.py` -> 16 tests OK. The other TFP suites were not run here. fixes.md reports them as passing.

Outside this task's touches, for information only (not findings): besides docs/QUALITY.md:86 and docs/deploy-runbook.md:256, TEAM.md:79 still says "Add a task for it (`python tasks.py new ...`), commit, push". With a remote, `new` now refuses to run without `--body` and its error message explains why, so a human following TEAM.md gets clear guidance. A later docs task could align these texts.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | `--body` with an empty file, or a file without acceptance criteria, still publishes a claimable task with no content**
    - File: `tasks.py:621-625`
    - Category: Correctness
    - Problem: the CR-2 guard checks only that `--body` was given. When the file is empty or whitespace only, `body` becomes "\n". When the file has no `## Acceptance criteria` section, or only empty boxes, the task is pushed to main with `status: todo` and no criteria. `criteria()` (tasks.py:144) ignores empty boxes, so such a task can also pass `done` with nothing to verify.
    - Why it matters: this is the same failure the CR-2 fix was meant to prevent (a contentless task that is claimable at once). It can now happen only through an explicit mistake, for example a wrong or empty scratch file, which is why it is low priority.
    - Suggested fix: when publishing, refuse a body for which `criteria(body)` is empty, with the same "NOT created" style of message. Add one test for it.

## Review conclusion

No material issues found.
