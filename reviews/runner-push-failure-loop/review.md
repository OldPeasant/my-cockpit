# Code Review

Date: 2026-10-20

Task: runner-push-failure-loop - Runner: stop the loop when release/hold cannot push

Scope: `git diff b08d78b..HEAD` (tasks.py, agents.py, tests/test_push_failure.py, tests/test_overbudget.py); production code reviewed at HEAD. Reviewed revision: c40fc6a77ee64093512809b30271c72f14abcb85

Requirements:
- tasks/runner-push-failure-loop.md (goal, 4 acceptance criteria, notes/assumptions)
- AGENTS.md (task protocol, quality gate)
- Existing conventions in tasks.py / agents.py (hand_off(), give_back(), MAX_RELEASES)

Git range:
- Branch: main
- HEAD: c40fc6a77ee64093512809b30271c72f14abcb85
- Base/start: b08d78b78e37fa6866a6591bab802b10c2ba615b
- Commits reviewed: 4 (5c8607b, d273702, 0670cce, c40fc6a)

Uncommitted changes at review time: `tests/test_push_failure.py` (the test-writer adding tests at the same time); not reviewed.

Validation run by the reviewer: HEAD versions of `tests/test_push_failure.py`, `tests/test_overbudget.py`, `tasks.py`, `agents.py`, `board.py` copied to `.tmp/headtests/` and run with `python3 .tmp/headtests/tests/test_push_failure.py` -> `Ran 19 tests in 219.792s, OK`.

Summary: 0 P0, 0 P1, 2 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | force_release() blocks on any release failure, so a merge conflict now needs a human and shows a help-text line as the error**
    - File: `agents.py:568-578` (also `agents.py:548-557`, `tasks.py:347-352`)
    - Category: Correctness
    - Problem: `force_release()` treats any non-zero `tasks.py release` as a push failure. When the release fails for another reason, most likely `CONFLICT while catching up with main` from `sync()` in `save_work()`, there is no push-failure file, so `err = last`. `last` is the last output line, which for a conflict is the last line of `CONFLICT_HELP` (`d. the tasks disagree on design: ...`). `block()` then logs and publishes "pushes for <id> fail, a human must fix it: d. the tasks disagree ...". The rebase is left in progress. Each retry 30 min later aborts it, runs the release again and hits the same conflict, so the task stays blocked until a human steps in. `hand_off()` already recovers from this case: it pushes HEAD to `wip/<id>` itself, resets to `@{u}` and releases again. `force_release()` doesn't.
    - Why it matters: Conflicts are common with parallel agents. A conflict the runner could solve itself (as `hand_off()` does) now blocks the task, and the status shows a misleading error. The criterion asks for the blocked state on a push failure, not on every release failure.
    - Suggested fix: Share the `hand_off()` fallback with `force_release()`. When the release fails without a recorded push failure, abort the merge, push HEAD to `wip/<id>` (block only if that push fails), reset to `@{u}` and release again. Also take `err` from the `CONFLICT:`/`git ... failed` line, not from the last output line. A runner test where the release conflicts would cover it.

- [ ] **CR-2 | A blocked `done` push streak is released as an uncounted hand-off, so the task can keep bouncing**
    - File: `agents.py:632-638` (the retry calls `force_release(..., handoff=True)`), `agents.py:753-761`
    - Category: Correctness
    - Problem: After 3 `done` sessions in a row fail to push, the task is blocked. 30 min later the runner retries `release --handoff`. `--handoff` means `count=False` in `give_back()` (`tasks.py:966-967`), so `releases` never goes up and the `MAX_RELEASES` auto-hold never fires. If the remote takes `wip/<id>` and board pushes but refuses the `done` push (for example a protected `main`, or a hook that only guards `refs/heads/main`), the release works and the task goes back to `todo` with no hold. The same runner can claim it again right away, because a blocked release never adds it to `avoid` and so never puts it in `TASK_AVOID`. That starts another 3 full-check `done` sessions, then 30 min blocked, and so on without end. The criterion says "no more sessions on that task". The workflow-scope case from the goal doesn't loop, but only because there the `wip/<id>` push fails too.
    - Why it matters: This is the loop the task should stop, just slower (3 sessions with the full check every ~30 min, on any agent).
    - Suggested fix: When the block came from the `done` streak, give the task back as a counted release, or better, `tasks.py hold <id> "<push error>"` so a human sees it. At least add it to `avoid`. A test with a hook that refuses only `refs/heads/main` would show the task is not resumed again.

## P3 Low

- [ ] **CR-3 | The status keeps the old `error` after a task is no longer blocked**
    - File: `agents.py:621-623`, `agents.py:639-653` (only the session start at `agents.py:680` sets `error=None`)
    - Category: Quality
    - Problem: `block()` and the blocked `idle()` put `error` into the shared `status` dict. When a human takes the task away (lines 621-623) and the runner then goes to `waiting`, `finished` or `stopped` without starting a session, `logs/<agent>.status.json` and `status/<owner>` still show the old push error.
    - Why it matters: `tasks.py watch` and the status file show an old error for an agent that is no longer blocked.
    - Suggested fix: Clear `error` when an entry leaves `blocked` (for example `status["error"] = None` there), or in every `publish` with a non-blocked state.

## Review conclusion

Material issues found; fix before completing the task.

Notes on the criteria:
- Criterion 1 is met. `pushed()`/`push_failed()` print `PUSH FAILED: <git error>` and a plain "only a human can fix it" hint, then exit 1 with no traceback, for the `wip/<id>` push in `save_work()` and for the board push in `update()`. Covered by the TasksPy tests.
- Criterion 2 is met for push failures. `force_release()` checks the exit code and `owned()`, logs "release FAILED", blocks, and retries every `--blocked-retry` (default 1800 s) with no sessions in between. CR-1 is the non-push case.
- Criterion 3 is met for the runner itself (the streak key ignores commit hashes, and the streak resets after a different error or a clean session). CR-2 is what happens after the block.
- Criterion 4: the HEAD tests use a pre-receive hook and passed for the reviewer. The new suites are not part of `scripts/check.sh`, which matches how `tests/test_overbudget.py` is handled. The reviewer did not run `sh scripts/check.sh`.
