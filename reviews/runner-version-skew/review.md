# Code Review

Date: 2026-10-20

Task: runner-version-skew - Runner: same tasks.py as agents.py; lost push races are not refusals

Scope: `git diff eff883da..106f3253 -- agents.py tasks.py tests` (the whole task at the final revision, with tasks/ and reviews/ left out). The emphasis is on what changed since the previous review: commit 9686ebeb, which adds `Classify.test_ruleset_wordings_are_refusals_on_their_own` to tests/test_push_race.py. agents.py and tasks.py were reviewed again in context.

Requirements:
- tasks/runner-version-skew.md (acceptance criteria 1-3 and the three recorded assumptions; the third supersedes the second)
- AGENTS.md task protocol (meaning of REFUSED / LOST / BUSY / PUSH FAILED for agents)
- docs/QUALITY.md artifact contract and freshness rules
- Previous review (the review.md committed in 9686ebeb, reviewed revision 80cce15e) and reviews/runner-version-skew/fixes.md

Git range:
- Branch: main
- HEAD: 106f3253ecdcfd0cf9612e67ad166d2ea2361857 (merge of origin/wip/runner-version-skew into main)
- Base/start: eff883daf5014628783f68be7342c3ca020d6081 (`git merge-base HEAD origin/main`)
- Commits reviewed: 9 (059ed5b3, 1551be49, f3f1219d, 08f925f0, 8dde6ab4, b98e269d, 49f12537, 9686ebeb, merge 106f3253)
- Reviewed revision: 106f3253ecdcfd0cf9612e67ad166d2ea2361857
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Changes since the previous review

- `git diff 49f12537 HEAD -- agents.py tasks.py tests` shows only the 8 added lines of `Classify.test_ruleset_wordings_are_refusals_on_their_own` (tests/test_push_race.py:177-183). agents.py and tasks.py did not change after 49f12537. The merge commit 106f3253 adds no code changes of its own to these files.
- The pre-rebase commit 80cce15e is no longer in this clone (`git cat-file` cannot find it), so I could not diff against it directly. I relied on the parent's statement that 49f12537 is the rebased 80cce15e. The code matches what the previous review describes: `REFUSED_ERRORS` at tasks.py:310-311 includes `"push declined"` and `"rule violation"`, `lost_race()` is at tasks.py:321-324, and agents.py has `import_tasks()` and the BUSY/RACE_HINT handling in `give_up()`. The production diff (agents.py +30/-9, tasks.py +45/-10) matches the behavior the earlier reviews validated.
- I read the full production diff again (`git diff eff883da HEAD -- agents.py tasks.py`) and found no new issues.

## New test review

`test_ruleset_wordings_are_refusals_on_their_own` checks each of the two ruleset refusal words on its own:
- The GH013 message contains `rule violation` but not `push declined`.
- The `(push declined)` message contains `push declined` but not `rule violation`.

Both messages include the common git suffix `failed to push some refs`. That suffix does not match the race word `(failed)`, which needs the parentheses, so the test does not pass for the wrong reason. Each assertion shows that removing either word from `REFUSED_ERRORS` would make the test fail, which `test_more_real_world_refusals` alone (the full GH013 output with both words) did not show. The test uses fictitious URLs (`example.test`) and asserts behavior through the public `refused()`/`lost_race()` functions. It is sound.

## Verification performed by the reviewer

- `python -m unittest tests.test_push_race -v` -> Ran 28 tests in 271.404s, OK. This covers every class: Classify (7, including the new test), Races, ImportTasks, BehindClone, AgentReleaseLosesRaces, ForceReleaseKeepsLosingRaces, ForceReleaseLosesRaces and BlockedRetryLosesRaces.
- As instructed, I did not run the full tooling suite, `tests/test_push_failure.py` or `sh scripts/check.sh`, because another run is in progress. The parent worker records that evidence for criterion 3.

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

Criterion 1 is met. The runner imports the tasks.py next to agents.py and falls back to the clone's copy only when there is none. The first assumption records this choice, and BehindClone and ImportTasks cover it.

Criterion 2 is met as refined by the third assumption:
- Refusal words, including GitHub ruleset rejections, fail at once with PUSH FAILED and write the push-failure record.
- 5 lost races exit BUSY with "lost the push race 5 times", write no record, and do not block the task.
- Unknown errors are retried and end as PUSH FAILED.

Criterion 3 is covered by tests/test_push_race.py, and its 28 tests pass. The parent worker still has to record the results of the full suite and of `sh scripts/check.sh`.
