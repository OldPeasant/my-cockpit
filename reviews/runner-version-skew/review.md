# Code Review

Date: 2026-10-20

Task: runner-version-skew - Runner: same tasks.py as agents.py; lost push races are not refusals

Scope: `git diff 82ea1576..f39b2990` (task commits fe58efd9, 89aacea4, ecc0cc4f, a06ae304, f39b2990), production files agents.py and tasks.py reviewed in full context; tests/test_push_race.py read for coverage

Requirements:
- tasks/runner-version-skew.md (acceptance criteria 1-3 and the three recorded assumptions, the third superseding the second)
- AGENTS.md task protocol (meaning of REFUSED / LOST / PUSH FAILED for agents)
- docs/QUALITY.md artifact contract
- Earlier review (revision 407ab308) and reviews/runner-version-skew/fixes.md

Git range:
- Branch: main
- HEAD: f39b2990f1ec38756347cef05c42300e445f87bd
- Base/start: 82ea1576484141d4b2629e048930ffe0864f2bc9
- Commits reviewed: 5. Reviewed revision: f39b2990f1ec38756347cef05c42300e445f87bd. One uncommitted change exists in tests/test_push_race.py (a concurrent test-writer). It is not reviewed.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of earlier findings (review of 407ab308)

- CR-1 (forced wip push denied as non-fast-forward was classified as a race) is resolved. `denying` is in `REFUSED_ERRORS` (tasks.py:311), and a refusal word wins over race words (`lost_race`, tasks.py:321-324). The real-remote test `Races.test_forced_wip_push_denied_as_non_fast_forward_is_refused` covers it.
- CR-2 (unknown or transient errors failed at once) is resolved. update() (tasks.py:513) and save_work() (tasks.py:969) stop early only on `refused()`. Unknown errors are retried within the 5 attempts and end as PUSH FAILED, as before this task. The third assumption records this.
- CR-3 (give_up blocked after sustained lost races) is resolved. give_up returns None without block() when the output carries `RACE_HINT` and there is no push-failure record (agents.py:599-604). A task blocked earlier is retried after 60 s.
- CR-4 (runner BUSY retry untested) is resolved. ForceReleaseLosesRaces, ForceReleaseKeepsLosingRaces, BlockedRetryLosesRaces and AgentReleaseLosesRaces cover it.

## Verification performed by the reviewer

- `python3 -m unittest tests.test_push_race.Classify tests.test_push_race.Races tests.test_push_race.ImportTasks` -> 22 tests OK (26 s)
- `python3 -m unittest tests.test_push_race.BehindClone` -> 1 test OK. The runner whose clone's tasks.py lacks `PUSH_FAILURE` runs 2 sessions with no Traceback or AttributeError.
- The reviewer did not run the slower runner classes, `tests/test_push_failure.py`, the other tooling tests, or `sh scripts/check.sh`.
- `python tasks.py verify runner-version-skew` shows criteria 1-3 still unticked. The parent worker must record evidence before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | GitHub ruleset rejections are classified as unknown and retried 5 times before PUSH FAILED**
    - File: `tasks.py:310-311` (REFUSED_ERRORS)
    - Category: Correctness
    - Problem: GitHub rejects pushes that violate a repository ruleset with `remote: error: GH013: Repository rule violations found for refs/heads/main` and `! [remote rejected] main -> main (push declined due to repository rule violations)`. None of the refusal words match: `declined` is only listed as `hook declined`. None of the race words match either. So the error is "unknown": update() runs 5 sync-and-push attempts, and save_work runs 5 attempts with 1 s pauses, before PUSH FAILED. The end result is correct (PUSH FAILED plus the record), but it takes 5 attempts where the task's intent is to fail at once on a policy refusal.
    - Why it matters: The impact is small (some extra seconds and network round trips). It is the one common hosted-remote policy rejection that the refusal list misses.
    - Suggested fix: Add `push declined` (or `declined`) and/or `rule violations` to `REFUSED_ERRORS`, with a case in `Classify.test_more_real_world_refusals`.

## Review conclusion

No material issues found.

Criterion 1 is met. `import_tasks()` (agents.py:400-407) imports the tasks.py next to agents.py, and `TASKS_ROOT` points at the clone, set at agents.py:432 before the import. The clone's copy is used only as a fallback. The choice is recorded as an assumption. Subprocess calls (`heartbeat`, `release`/`hold` in give_up) still run the clone's tasks.py, as the assumption states. agents.py only depends on in-process attributes from the matching module, and a missing `RACE_HINT` in an old clone's output just skips the retry.

Criterion 2 is met as refined by the third assumption. Refusal words fail at once with PUSH FAILED and the record. 5 lost races exit `BUSY: ... lost the push race 5 times ... try again` without writing the record, so the runner does not block the task. Errors that are still unknown after 5 attempts keep the pre-task PUSH FAILED behavior. This goes beyond the criterion's literal "only real refusals" wording, but the assumption records it deliberately.

Criterion 3 is covered by tests/test_push_race.py. The parent worker still has to record the full-suite and `sh scripts/check.sh` evidence.
