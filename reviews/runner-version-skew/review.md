# Code Review

Date: 2026-10-20

Task: runner-version-skew - Runner: same tasks.py as agents.py; lost push races are not refusals

Scope: `git diff 13cf3432..80cce15e` (whole task at the final revision). The emphasis is on the two commits since the previous review: 1674e4b5 (more Classify tests) and 80cce15e (fix for the earlier CR-1). agents.py and tasks.py were reviewed in context, and tests/test_push_race.py was read for coverage.

Requirements:
- tasks/runner-version-skew.md (acceptance criteria 1-3 and the three recorded assumptions; the third supersedes the second)
- AGENTS.md task protocol (meaning of REFUSED / LOST / BUSY / PUSH FAILED for agents)
- docs/QUALITY.md artifact contract
- Previous review (revision f39b2990) and reviews/runner-version-skew/fixes.md

Git range:
- Branch: main
- HEAD: 80cce15e6a3bd14290b3f737c59b5f067b43ce32
- Base/start: 13cf3432d1edef3434cede93e3f3f664727b98a2 (origin/main, an ancestor of HEAD)
- Commits reviewed: 7 (218f223e, 6ebf297e, 1dea885a, 74a68af2, 3d21943d, 1674e4b5, 80cce15e)
- Reviewed revision: 80cce15e6a3bd14290b3f737c59b5f067b43ce32
- Uncommitted changes: tests/test_push_race.py has 8 uncommitted added lines. They come from a concurrent test-writer and add `Classify.test_ruleset_wordings_are_refusals_on_their_own`. That change is not part of the reviewed revision.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of earlier findings

- I compared the previously reviewed code with the rebased code. `git diff f39b2990 3d21943d -- tasks.py agents.py tests` is empty. The rebase changed no code, so the conclusions of the previous review still apply to commits 218f223e through 3d21943d.
- CR-1 (P3: GitHub ruleset rejections classified as unknown and retried 5 times) is resolved. In 80cce15e, `REFUSED_ERRORS` (tasks.py:310-311) now includes `"push declined"` and `"rule violation"`. GitHub's GH013 output (`Repository rule violations found` / `(push declined due to repository rule violations)`) now counts as a refusal and fails at once with PUSH FAILED. `Classify.test_more_real_world_refusals` covers the full GH013 wording.

## Misclassification check for the new refusal words

`lost_race()` (tasks.py:321-324) returns False whenever any refusal word is present. Adding refusal words could therefore turn a race into a refusal, so I checked them:
- No git race wording contains either new substring. The race wordings checked were: `(non-fast-forward)`, `(fetch first)`, `cannot lock ref ... is at X but expected Y`, `Unable to create '...lock': File exists`, `(reference already exists)`, `(failed)`, `(failed to update ref)`, `(stale info)` and `(incorrect old value provided)`. Git uses "declined" only for hooks (`hook declined`, already a refusal) and for hosting-policy rejections, so the bare substring `declined` was not added. The more specific `push declined` was used instead, which is appropriate.
- `Classify.test_lost_races` still passes for every race message, including the upper-case variant. `test_unknown_errors_are_neither` still passes for HTTP 500, hung-up and disconnect errors. The new words did not change the unknown class.
- `test_without_an_argument_the_last_push_error_is_classified` (1674e4b5) checks the default-argument path that update() and save_work() use. It covers one race, one refusal and one unknown error, and restores `push_error[0]` through addCleanup. The test is sound.

## Verification performed by the reviewer

- `python3 -m unittest tests.test_push_race.Classify` -> 6 tests OK. This run used the working tree, which includes the uncommitted test.
- `python3 -m unittest tests.test_push_race.Races tests.test_push_race.ImportTasks tests.test_push_race.BehindClone` -> 18 tests OK (32 s).
- I did not run the slower runner classes (AgentReleaseLosesRaces, ForceReleaseKeepsLosingRaces, BlockedRetryLosesRaces, ForceReleaseLosesRaces), `tests/test_push_failure.py`, the other tooling tests, or `sh scripts/check.sh`. The parent worker must record that evidence for criterion 3.

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

Criterion 1 is met. The runner imports the tasks.py next to agents.py and falls back to the clone's copy only when there is none. The first assumption records this choice, and BehindClone covers it.

Criterion 2 is met as refined by the third assumption:
- Refusal words, now including GitHub ruleset rejections, fail at once with PUSH FAILED and write the push-failure record.
- 5 lost races exit BUSY with "lost the push race 5 times", write no record, and do not block the task.
- Unknown errors are retried and end as PUSH FAILED.

Criterion 3 is covered by tests/test_push_race.py. The parent worker still has to record the full-suite and `sh scripts/check.sh` results with `tasks.py verify`. The parent also has to decide whether to commit the concurrent test-writer's uncommitted test. That test only adds to the test file, so it does not affect code freshness.
