# Code Review

Date: 2026-10-20

Task: runner-push-failure-loop - Runner: stop the loop when release/hold cannot push

Scope: `git diff 5148ba7..HEAD` for agents.py, tasks.py, tests/test_push_failure.py (and tests/test_overbudget.py hooks). Production code read at HEAD. Reviewed revision: 8237fd534343d76dd9b63b3d0a97857871f3fe03

Requirements:
- tasks/runner-push-failure-loop.md (goal, 4 acceptance criteria, notes/assumptions)
- AGENTS.md (task protocol, quality gate, project notes)
- Existing conventions in tasks.py / agents.py (give_back(), save_work(), MAX_RELEASES, hand_off())

Git range:
- Branch: main
- HEAD: 8237fd534343d76dd9b63b3d0a97857871f3fe03
- Base/start: 5148ba763db8db8ef82261a0d664dd0d186cbdf6
- Commits reviewed: 7 task commits (c81fead, 2996ab1, 2fd764a, 8aca456, 6dcdc71, c896015, 8237fd5), plus board-only claim/heartbeat/merge commits in the range

Uncommitted changes at review time: none.

Validation: the reviewer ran no tests. The parent reported `python -m unittest tests.test_push_failure -> 26 OK` and `sh scripts/check.sh -> all checks passed`.

Status of the previous review's findings (on c40fc6a):
- Old CR-1 (any release failure blocked, e.g. a conflict): fixed. `give_up()` aborts the merge, saves HEAD to `wip/<id>`, resets to `@{u}` and runs the command again. It blocks only if the wip push fails or the second run fails, and the error comes from the `PUSH FAILED`/`CONFLICT`/... line. Tests: ReviewReleaseConflict*, UsageLimitHandOffConflict*.
- Old CR-2 (a blocked `done` streak got released and bounced back): fixed. Its retry is now `tasks.py hold`, and `cmd="done"` survives a re-block. Tests: DoneRefusedOnlyOnMain, DoneBlockedHoldRefused.
- Old CR-3 (an old `error` stayed in the status): fixed. `status["error"] = None` is set when the task is taken away or given back, and every session start sends `error=None`.

Summary: 0 P0, 0 P1, 2 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A hold the agent asked for, whose push failed, is retried as a release, so the hold reason is lost**
    - File: `agents.py:785-786` (`block(tid, err)` with no `cmd` for any non-`done` failure), `agents.py:661-664`
    - Category: Correctness
    - Problem: An agent runs `tasks.py hold a "<what a human must decide>"` and the push is refused. tasks.py then records `cmd="hold"` and prints "the runner waits for the fix". The runner calls `block(tid, err)` without `cmd`, so the stored command is `None`. Once pushes work again, the blocked retry runs `force_release(..., handoff=True)`, which is an uncounted `release --handoff`. The task goes back to `todo` with no `hold`, and the agent's reason is not kept anywhere: the push-failure file has no reason. Another agent can then claim a task that its previous owner said only a human can unblock. HoldRefusedInSession only checks the immediate block, not the retry.
    - Why it matters: Criterion 1 says a failed hold leaves the task on hold, or the agent is told plainly that only a human can fix it. Here the runner quietly turns the agent's hold into a hand-off. That defeats the hold and can start the bouncing that hold exists to prevent.
    - Suggested fix: When `pf["cmd"] == "hold"`, block with a hold retry, as for `done`. The simplest way is for tasks.py to record the hold reason in the push-failure JSON, and for the blocked loop to retry `tasks.py hold <id> <reason>`. Extend HoldRefusedInSession: remove the hook and assert that the task ends on hold, not released.

- [ ] **CR-2 | The hold retry of a done-blocked task can throw away uncommitted changes**
    - File: `agents.py:654-658` (hold retry, no commit of a dirty tree first) together with `agents.py:568-577` (`give_up()` fallback `reset --hard @{u}`)
    - Category: Correctness
    - Problem: `hand_off()` and `force_release()` commit a dirty tree before they call `give_up()`. The `cmd == "done"` retry branch calls `give_up(tid, ["hold", ...])` straight away. Take a clone with unpushed commits and uncommitted changes, for example ones the agent made after its failing `done` in the same session. `save_work()` stops with `REFUSED: commit your changes first` and records no push failure. The `give_up()` fallback then pushes only the commits to `wip/<id>` and runs `git reset -q --hard @{u}`. That deletes the uncommitted changes without saving them anywhere: not to `wip/`, and not to a `lost/` branch the way `park()` does.
    - Why it matters: Agent work is lost silently. This hits exactly the done-only-refused case (DoneRefusedOnlyOnMain), where the wip push works.
    - Suggested fix: Before the hold retry, commit a dirty tree as `force_release()` does (`git add -A; git commit -qm "wip: <id> (before hold)"`). A cleaner fix puts that step inside `give_up()`, so every caller is covered. A test could leave an uncommitted file before the retry and assert that it ends up on `wip/<id>`.

## P3 Low

- [ ] **CR-3 | The comment for `blocked` describes a 2-element value, but the entries have 3**
    - File: `agents.py:605`
    - Category: Quality
    - Problem: The comment says `task id -> [push error, next retry (epoch)]`. `block()` stores `[err, retry, cmd]`, and the main loop unpacks three values (`err, retry, cmd = blocked[tid]`). `cmd` decides between a hold retry and a release retry, and the comment doesn't mention it.
    - Why it matters: Someone changing the retry logic could miss that `cmd` decides between hold and release.
    - Suggested fix: Update the comment to `[push error, next retry (epoch), cmd that blocked it ("done" -> retry is a hold)]`.

## Review conclusion

Material issues found; fix before completing the task.

Notes on the criteria:
- Criterion 1: met for release, hold and done. `pushed()`/`push_failed()` print `PUSH FAILED: <git error>` and a plain "only a human can fix it" hint, then exit 1 with no traceback, for the `wip/<id>` push and the board push (TasksPy tests). CR-1 is about what the runner does afterwards with a failed hold.
- Criterion 2: met. After `force_release()` or a review session, the runner checks the exit code and `owned()`. It logs "release FAILED", goes to `blocked` with the error in `logs/<agent>.status.json`, and retries only every `--blocked-retry` s (default 1800) with no sessions in between. The review path skips `avoid` for blocked tasks.
- Criterion 3: met. The streak key ignores commit hashes and resets after a different error or a session without a push failure. After 3 sessions the task is blocked and its retry is a hold, so it can't bounce back.
- Criterion 4: the tests use pre-receive hooks in a bare remote, which matches the criterion. The parent reported 26 OK and a passing `scripts/check.sh`; the reviewer did not re-run them. As with `tests/test_overbudget.py`, these suites are not part of `scripts/check.sh`.
