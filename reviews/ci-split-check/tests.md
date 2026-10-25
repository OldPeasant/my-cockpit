# Test Report

Date: 2026-10-25

Task: ci-split-check - CI: split the fast check into parallel jobs per part

Scope: `git diff 258d81e7dd4e7841ab219ce64eb2b4422bad338e..d200068745c679aa3509f3fa99eaa1c03d50fbed`
(base and final revision supplied by the parent). Round 2: final revision d20006874 (fix CR-1/CR-2). Task commits:
809185779 (implementation), f1452a7fb (this test and the round-1 reports), d20006874 (fixer: only
`.github/workflows/ci.yml`, check-web and check-android `timeout-minutes` -> 35 and the timeout comment rewritten;
`git diff f1452a7fb d20006874 --stat` shows only `ci.yml` and `reviews/ci-split-check/fixes.md`).
Changed files: `scripts/check.sh`, `.github/workflows/ci.yml`, `docs/ARCHITECTURE.md`, `tasks/ci-split-check.md`,
`tests/test_check_sh.sh`. Measured at HEAD d200068745c679aa3509f3fa99eaa1c03d50fbed, clean working tree apart from
this report.

Requirements:
- `tasks/ci-split-check.md` acceptance criteria
- `AGENTS.md` (check.sh = `.taskcheck`, `COCKPIT_NO_ANDROID=1`), `docs/ARCHITECTURE.md` "Test"/CI section
- Parent instructions (no Gradle/npm in the sub-agent; cheap shell test with stubs)

## Test files changed

- `tests/test_check_sh.sh` (new)

## Tests added or updated

Count: 1 new scenario script with 9 scenarios and 45 assertions.

It copies `scripts/check.sh` into a fixture tree under `.tmp/test-check-sh.XXXXXX` (removed on exit).
In that tree the part commands are stubs that log their arguments: `tests/test_quality_gate.py`,
`server/gradlew`, `android/gradlew`, and `npm` on PATH. No Gradle, npm or Android SDK is needed. The copy is
run from an unrelated directory, so the test also checks that the script changes to its own repo root.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC2: `check.sh` without arguments behaves as before (all parts, same commands, same order, plain "all checks passed") | scenario 1 | `sh tests/test_check_sh.sh` | Pass |
| AC2: `COCKPIT_NO_ANDROID=1` still skips android without arguments (`.taskcheck` on machines without the SDK) | scenario 2 | `sh tests/test_check_sh.sh` | Pass |
| AC2: part selector runs only the named part with the unchanged command (`sh scripts/check.sh server`, ...) | scenario 3 (each of tfp/server/web/android), scenario 4 (several parts, duplicates, script order, all four named) | `sh tests/test_check_sh.sh` | Pass |
| AC2: `COCKPIT_NO_ANDROID=1` with `android` selected skips and exits 0; it does not affect other parts | scenario 5 | `sh tests/test_check_sh.sh` | Pass |
| Unknown part -> exit 2 with message, nothing runs (also with a valid part before it, an empty argument, wrong case, `all`) | scenario 6 | `sh tests/test_check_sh.sh` | Pass |
| A failing part still fails the check (nonzero exit, later parts not run, no success line), with or without a selector | scenario 7 | `sh tests/test_check_sh.sh` | Pass |
| Web dependency install (`npm ci` when the lockfile is newer) still happens inside the selected web part | scenario 8 | `sh tests/test_check_sh.sh` | Pass |
| AC1/AC2: ci.yml runs `sh scripts/check.sh tfp|server|web|android`, one per part, for each of the four parts, and nothing else calls check.sh (static grep of the workflow) | scenario 9 | `sh tests/test_check_sh.sh` | Pass |
| AC1: separate jobs, only the setup each needs, own `timeout-minutes` | Reviewed by reading the ci.yml diff at d20006874 (tfp: checkout only; server: java + gradle; web: node; android: java + android SDK + gradle; timeouts tfp 10, server 35, web 35, android 35). The parent ran actionlint on the final code -> 0 errors. No automated test: YAML structure and runner behaviour can only be proven on GitHub. | n/a | Not testable here (needs a GitHub run, AC3) |
| AC3: a push/PR run on GitHub shows all jobs green, with durations | none | n/a | Not run: needs a GitHub Actions run; no GitHub access from this sub-agent |
| AC3: ci.yml header and ARCHITECTURE.md updated | Reviewed by reading the diff: both describe the per-part jobs and the selector | n/a | Not testable (documentation) |

## Focused test execution

Command:

`sh tests/test_check_sh.sh` (repo root)

Result:

Round 1 (809185779): Pass, 45 ok, 0 FAIL.
Round 2 (final revision d20006874, 2026-10-25T22:56+01:00): Pass, 45 ok, 0 FAIL, "all check.sh selector tests
passed", exit 0. The test was not changed: the fix only touched timeouts and a comment in ci.yml, and scenario 9
(static check of the per-part `sh scripts/check.sh` calls) still holds.

## Full test execution

Command:

`sh scripts/check.sh` (no arguments, the full `.taskcheck`)

Result:

Not run by the test-writer: sub-agents can't run Gradle/npm here (parent instruction). Parent results: the full
no-argument `sh scripts/check.sh` passed at 809185779; `scripts/check.sh` is unchanged since (same sha256 at
d20006874). Earlier also `sh scripts/check.sh bogus` -> exit 2 with message, `sh scripts/check.sh tfp` -> 20 tests
OK, and actionlint on the final code -> 0 errors. The ci.yml fix does not affect what check.sh runs locally.

## .taskcheck

Result:

Not run by the test-writer (same reason, see above). Parent: passed at 809185779 with an identical `scripts/check.sh`.

## Bugs found

None. Every assertion passed against the selector logic of `scripts/check.sh` and the ci.yml calls at d20006874.

Notes for the parent (not defects):
- `tests/test_check_sh.sh` is not run by `scripts/check.sh tfp`, which only runs `tests/test_quality_gate.py`
  (the other `tests/test_*` scripts aren't run by it either). Wiring it in would change `scripts/check.sh` and is
  the parent's call. Run it by hand with `sh tests/test_check_sh.sh` (or `bash`, ~1 s).
- Repeated parts run only once and parts always run in the script's order, not the order given
  (`android web` runs web, then android). Nothing in the spec says otherwise; the test fixes the current behaviour.

## Coverage

Status: not applicable. The project has no coverage tooling for POSIX shell or GitHub Actions YAML
(`docs/ARCHITECTURE.md` "Coverage" covers server (Kover/JaCoCo), web (Vitest) and android only). Adding a shell
coverage tool such as kcov or bashcov would mean installing a dependency, which is outside the test-writer's
authority.

Metric: whole-file coverage of task-changed production files: not measured (no provider).
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: ci-split-check; base
  258d81e7dd4e7841ab219ce64eb2b4422bad338e; final revision and measured HEAD
  d200068745c679aa3509f3fa99eaa1c03d50fbed (from the parent); task commits 809185779, f1452a7fb, d20006874.
- Source/test snapshot identifier (including uncommitted inputs): sha256 `scripts/check.sh`
  d26b0b38ea9b37013a27722d00af105ddc93bcacfcd01105831a52a2c199165e, `.github/workflows/ci.yml`
  931dd494a1d4d124994d4d657e077cd4f7206a5bff8814ffcb5e6d84388cb79d (round 1: fccdccb5...), `tests/test_check_sh.sh`
  bb0827372d13cf0db6fa374d627e06be16a5e6dc1513cfc889c3752c3c149f53.
- Exact included files; renamed/deleted/excluded files and reasons: in scope `scripts/check.sh` and
  `.github/workflows/ci.yml`, both with no coverage provider. Excluded: `docs/ARCHITECTURE.md` (documentation)
  and `tasks/ci-split-check.md` (board metadata).
- Provider/version, command, working directory, and test selection: none (no provider configured).
- Run start/completion time and exit status: n/a for coverage. The round-2 test run on 2026-10-25 at 22:56+01:00 exited 0.
- Generated report paths and report hashes/run identifier: none.
- Evidence outputs are fresh and inputs stayed unchanged: there are no coverage outputs. The test ran against the
  snapshot hashed above.
- Final run after latest test additions and production fixes: yes, the test ran on the final revision d20006874 after the fixer's ci.yml change.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `scripts/check.sh` | N/A (no provider) | N/A | N/A | N/A |
| `.github/workflows/ci.yml` | N/A (not executable locally) | N/A | N/A | N/A |

Branches of `scripts/check.sh` exercised by the new test, counted by hand (not a tool measurement): the
validation loop (valid part, unknown part), the default `PARTS`, all four `selected` branches taken and not
taken, the `COCKPIT_NO_ANDROID` skip and the android run, the web `npm ci` and skip, the success line with and
without a suffix, and failure propagation under `set -e`. Not exercised: the `python` fallback in
`PY=$(command -v python3 || command -v python)` and the `ANDROID_HOME` default. Both are older code, not changed
by this task.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `scripts/check.sh` `PY=` python fallback, `ANDROID_HOME` default | Older environment setup, not changed by this task | Left uncovered: out of task scope |
| `.github/workflows/ci.yml` job setup and timeouts | AC1/AC3: jobs run green on GitHub | Only a GitHub run can show this (AC3). The static check in scenario 9 covers the check.sh calls |

## Unable to test

- AC3 (all jobs green on GitHub, with durations and a run id): needs a push/PR run on GitHub Actions. The
  parent must record the run id, or skip with a reason.
- AC1 "only the setup it needs" and the timeout headroom: can only be shown by a real run. Here they were
  reviewed by reading the diff, and the parent ran actionlint.
- The full no-argument `sh scripts/check.sh` with the real Gradle/npm: the parent runs it.

## Summary

Tests added and all validation passed. Round 2 on the final revision d20006874: the focused run passed (45/45)
without test changes. No production defects found. The GitHub run (AC3) is left to the parent worker.
