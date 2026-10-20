# Fix Report

Date: 2026-10-20

Task: runner-version-skew - Runner: same tasks.py as agents.py; lost push races are not refusals

Scope: current task, finding CR-1 (P3) of the latest `reviews/runner-version-skew/review.md`. Earlier
fix rounds (CR-1 to CR-4 of the previous review) are described in this file's git history.

## Fixed findings

### CR-1 — GitHub ruleset rejections are classified as unknown and retried 5 times before PUSH FAILED

Files changed:
- `tasks.py`
- `tests/test_push_race.py`

Problem:
GitHub rejects a push that violates a repository ruleset with `remote: error: GH013: Repository rule
violations found ...` and `! [remote rejected] main -> main (push declined due to repository rule
violations)`. No word in `REFUSED_ERRORS` matched (`declined` was only listed as `hook declined`), and no
race word matched either. So the error was "unknown": update() and save_work() made 5 attempts before
PUSH FAILED instead of failing at once on a policy refusal.

Fix:
Added `"push declined"` and `"rule violation"` to `REFUSED_ERRORS` (tasks.py:310-311). Neither occurs in
any `RACE_ERRORS` word or in any lost-race message in the tests, so no race is reclassified. Added the
GitHub ruleset rejection (GH013 line, `push declined due to repository rule violations`, `failed to push
some refs`) to `Classify.test_more_real_world_refusals`, which asserts `refused()` is true and
`lost_race()` is false. With the tasks.py change reverted, this test fails (verified), so it is a real
regression test.

Validation:
- `python3 -m unittest tests.test_push_race.Classify -v` — Pass (5 tests)
- same command with the tasks.py change temporarily reverted — Fail (failures=1), as expected

## Not fixed

None.

## Validation summary

Focused tests:
- `python3 -m unittest tests.test_push_race.Classify -v` -> 5 tests OK
- `python3 -m unittest tests.test_push_race.Classify tests.test_push_race.Races tests.test_push_race.ImportTasks` -> 22 tests OK (29 s)

Full test suite:
- `python3 -m unittest tests.test_push_failure` -> 31 tests OK (385 s)
- Other tooling test files and the slower runner classes of tests/test_push_race.py: not run by the fixer
  (the change only adds two words to a constant tuple).

Lint/type-check/build:
- Not applicable (Python tooling script; the changed line is 117 characters, within the file's style).

`.taskcheck`:
- Not run (`sh scripts/check.sh` covers server/web/android; the change touches only tasks.py and its tests).
  The parent worker runs it before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
