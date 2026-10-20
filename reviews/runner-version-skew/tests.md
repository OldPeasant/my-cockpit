# Test Report

Date: 2026-10-20

Task: runner-version-skew - Runner: same tasks.py as agents.py; lost push races are not refusals

Scope: final revision HEAD `80cce15e6a3bd14290b3f737c59b5f067b43ce32` on branch `main`. Base
`13cf3432d1edef3434cede93e3f3f664727b98a2` (`git merge-base HEAD origin/main`, given by the parent). Task commits
`13cf3432..80cce15e`: 218f223e, 6ebf297e, 1dea885a, 74a68af2, 3d21943d, 1674e4b5, 80cce15e (the earlier commits
were rebased, so their hashes differ from those in the previous report). This re-run focuses on 80cce15e, which
added `"push declined"` and `"rule violation"` to `REFUSED_ERRORS` in `tasks.py` (review CR-1). Production files
changed by the task: `agents.py`, `tasks.py`. Also included: this test-writer's uncommitted edit to
`tests/test_push_race.py`.

Requirements:
- `tasks/runner-version-skew.md` acceptance criteria 1-3 and its recorded assumptions
- review finding CR-1 (GitHub repository-ruleset rejections must be refusals), `reviews/runner-version-skew/fixes.md`
- `AGENTS.md` (Evidence, quality gate), `docs/QUALITY.md`, `docs/ARCHITECTURE.md` "Coverage"

## Test files changed

- `tests/test_push_race.py`

## Tests added or updated

Count: 1 (in this re-run)

- `Classify.test_ruleset_wordings_are_refusals_on_their_own`: 80cce15e added a GH013 sample to
  `test_more_real_world_refusals`, but that sample contains both `push declined` and `rule violation`.
  Removing either substring from `REFUSED_ERRORS` would therefore not fail any test. The new test gives one
  message that contains only `rule violation` (the GH013 header line) and one that contains only `push declined`.
  I checked by hand that each matches exactly one `REFUSED_ERRORS` entry. Both must be `refused()` and not
  `lost_race()`, even though they contain `failed to push some refs`.

The tests from earlier runs are still in place: `test_more_real_world_refusals` and
`test_without_an_argument_the_last_push_error_is_classified`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| CR-1: a GitHub ruleset rejection (GH013, "push declined due to repository rule violations") is a refusal, not a race | `Classify.test_more_real_world_refusals` (full GH013 output), `Classify.test_ruleset_wordings_are_refusals_on_their_own` (new, each substring alone) | `python -m unittest tests.test_push_race.Classify tests.test_push_race.Races tests.test_push_race.ImportTasks tests.test_push_race.BehindClone -v` | Pass |
| AC1: the runner uses the `tasks.py` next to `agents.py` (falls back to the clone's); no `AttributeError` when the clone is behind | `ImportTasks` (2 tests), `BehindClone.test_runner_on_a_clone_behind_main_does_not_crash` | same | Pass |
| AC2: lost races are told apart from refusals and unknown errors | all of `Classify` (7 tests) | same | Pass |
| AC2: 5 lost races give BUSY, not PUSH FAILED, and the task is not blocked; real refusals fail at once with PUSH FAILED; unknown errors are retried and fail after 5 attempts | all of `Races` | same | Pass |
| AC2 (runner side): give_up and blocked retries handle lost races | `AgentReleaseLosesRaces`, `ForceReleaseLosesRaces`, `ForceReleaseKeepsLosingRaces`, `BlockedRetryLosesRaces` | not re-run by me; the parent ran the full suite at 80cce15e | Pass (parent: full suite 114 OK) |
| AC3: `tests/test_push_failure.py` passes | all 31 tests | not re-run by me; the fixer ran it at 80cce15e | Pass (fixer: 31 OK) |
| AC3: the whole tooling suite and `sh scripts/check.sh` pass | whole suite | `python -m unittest discover -s tests` (parent) / `.taskcheck` | Full suite: Pass (parent: 114 OK, before my added test). `.taskcheck`: not run by me |

## Focused test execution

Command:

`python -m unittest tests.test_push_race.Classify tests.test_push_race.Races tests.test_push_race.ImportTasks tests.test_push_race.BehindClone -v`
(repo root, the system `python`, started 2026-10-20T23:05:57+02:00, finished 23:06:29+02:00)

Result:

Pass: `Ran 24 tests in 31.704s`, `OK`, exit 0. This includes the new test.

## Full test execution

Command:

`python -m unittest discover -s tests`

Result:

Not run by the test-writer. The parent reported 114 OK at 80cce15e, before I added the new test. The new test
is a pure in-process classification check, and it passed in the focused run above.

## .taskcheck

Result:

Not run by the test-writer. This is `sh scripts/check.sh`, and the parent runs it as part of final validation.

## Bugs found

None. Adding `push declined` and `rule violation` doesn't misclassify any existing race sample:
`Classify.test_lost_races` and all `Races` tests pass.

## Coverage

Status: unavailable. No coverage tooling is configured for the root Python tooling (`agents.py`, `tasks.py`).
`python -m coverage --version` gives `No module named coverage` (checked again in this run).
`docs/ARCHITECTURE.md` "Coverage" defines providers only for server, web and android. Installing a provider is
outside the test-writer's authority. The stdlib `trace` module is not a substitute: most tests run `tasks.py` and
`agents.py` as subprocesses.

Metric: whole-file coverage of task-changed production files: not measured.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: runner-version-skew; base
  13cf3432d1edef3434cede93e3f3f664727b98a2; HEAD 80cce15e6a3bd14290b3f737c59b5f067b43ce32. The parent gave the
  scope; I confirmed it with `git log 13cf3432..HEAD` and `git diff --stat 13cf3432..HEAD`, which show `agents.py`,
  `tasks.py` and `tests/test_push_race.py` outside `reviews/`/`tasks/`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 `agents.py`
  be6e6be50c0664f7752f0bf7221b5e5ace31e77052cfa5c95be7202f7b75a54a, `tasks.py`
  3dcebcc5a5193dd1d5afb68e936ac7fd62dcfbd9d988db533dab56122b379bbc, `tests/test_push_race.py` (uncommitted, with
  the new test) a399efd66a03827b82d60ef9a0dbc6c14aacfea1a2ce4b1462a09497032b06a1. The hashes were the same before
  and after the focused run.
- Exact included files; renamed/deleted/excluded files and reasons: the scope would be `agents.py` and `tasks.py`.
  Excluded: `tests/test_push_race.py` (test file) and `reviews/runner-version-skew/*` (docs). No renames or
  deletions.
- Provider/version, command, working directory, and test selection: none available (see Status).
- Run start/completion time and exit status: there was no coverage run. The focused test run is listed above
  (exit 0).
- Generated report paths and report hashes/run identifier: none.
- Evidence outputs are fresh and inputs stayed unchanged: the inputs didn't change during the focused run.
  There is no coverage output.
- Final run after latest test additions and production fixes: yes for the focused tests (HEAD 80cce15e plus the
  new test). Coverage was not run.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `agents.py` | N/A (not measured) | N/A | N/A | N/A |
| `tasks.py` | N/A (not measured) | N/A | N/A | N/A |

Instead of measured coverage, I checked by hand which test drives each changed branch. This is a review, not a
measurement:

| File and changed location | Required behavior or risk | Test that drives it, or the gap |
|---|---|---|
| `tasks.py` `REFUSED_ERRORS` `"push declined"`, `"rule violation"` (80cce15e) | CR-1 | `test_more_real_world_refusals`, `test_ruleset_wordings_are_refusals_on_their_own` (each entry alone) |
| `tasks.py` `refused()` / `lost_race()` incl. default argument | AC2 classification | `Classify` (7 tests) |
| `tasks.py` `update()` / `save_work()` retry loops, BUSY vs PUSH FAILED exits | AC2 | `Races` |
| `agents.py` `import_tasks()` both branches | AC1 | `ImportTasks`, `BehindClone` |
| `agents.py` give_up race loop and blocked reset | AC2 runner | `AgentReleaseLosesRaces`, `ForceRelease*`, `BlockedRetryLosesRaces` (parent's full run) |
| `agents.py` `BUSY` prefix in the error picker together with a push-failure record | shows an error | Not driven directly: the current `tasks.py` doesn't produce this combination. Low risk; I left it uncovered. |

## Unable to test

- Coverage numbers: no Python coverage provider is installed or configured. Getting them would need a separate
  decision to add `coverage` to the tooling stack.
- Real hosted remotes (GitHub rulesets, GitLab): not run. The tests use a local bare remote, and the
  classification tests use the real-world error text.
- The full suite and `.taskcheck` after my added test: left to the parent.

## Summary

Tests added and all validation passed. 80cce15e was tested, but only with a sample that contains both new
substrings. I added one test that pins each substring on its own. The 24 requested focused tests pass. No
production defects found. Task-scoped coverage could not be measured because there is no Python coverage
tooling.
