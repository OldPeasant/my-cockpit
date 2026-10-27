# Test Report

Date: 2026-10-27

Task: ci-oom-and-test-reports (CI: give the Docker server build its heap settings; make failing tests visible)

Scope: `git diff f03bd63fc50fd95676cbcd3ea7966a864fcaaee9..6e19bbfc0b62df4303352729bb42b15b2a424c5a`
(one task commit, 6e19bbfc0, on branch main; HEAD = 6e19bbfc0b62df4303352729bb42b15b2a424c5a).
Changed files: `.github/workflows/ci.yml`, `Dockerfile`, `server/build.gradle.kts`, `android/app/build.gradle.kts`.
Uncommitted changes present and deliberately excluded from scope: `server/src/test/kotlin/ch/sonensei/cockpit/contacts/aggregate/CorrespondenceQueryTest.kt`
and `android/app/src/test/java/ch/sonensei/cockpit/TestLibrariesSmokeTest.kt` are temporarily broken by the parent worker
for acceptance criterion 2 and must be reverted before commit; the test-writer did not touch them.

Requirements:
- `tasks/ci-oom-and-test-reports.md` acceptance criteria 1-4
- `AGENTS.md` (Evidence, project notes), parent-worker instructions

## Test files changed

- None. Only this report was written.

## Tests added or updated

Count: 0

The task changes build and CI configuration only (Dockerfile, Gradle build scripts, GitHub Actions workflow). There is no
production source to unit-test, and the project has no test harness for build scripts or workflows. The acceptance criteria
name the verification commands themselves (image build, check.sh with a broken test, workflow validation), which the
parent worker runs; adding automated tests would mean new test infrastructure, which is out of scope.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Dockerfile server stage copies `server/gradle.properties` before the Gradle steps; image builds with `--no-cache` | Static inspection (test-writer); image build (parent) | `grep -n gradle Dockerfile`, `cat .dockerignore`; parent: `podman build --no-cache -t cockpit-server:ci .` to `.tmp/podman-build.log` | Static: Pass (COPY on line 20 precedes `RUN ./gradlew ... dependencies` on line 22 and `bootJar` on line 25; `.dockerignore` re-includes `server/` and excludes only `server/build/`, `server/.gradle/`, `server/.kotlin/`, `**/.env`, so `server/gradle.properties` is in the context). Build: Not run by test-writer: parent run still in progress at 19:43 (log reached STEP 5/8 with the new COPY step succeeding); the parent must record the final result |
| 2. Failing server/android test prints class, method, assertion message and stack trace; passing tests silent | Static inspection of `testLogging` blocks (test-writer); broken-test runs (parent) | parent: `sh scripts/check.sh server` to `.tmp/check-server-broken.log`, `sh scripts/check.sh android` to `.tmp/check-android-broken.log` | Static: Pass (both build scripts set `events(FAILED)` and `exceptionFormat = FULL` on the lifecycle and the quiet level, matching `scripts/check.sh`, which runs Gradle with `--quiet` on lines 33 and 48; no PASSED/STARTED/standard-stream events are enabled). Runtime: Not run by test-writer: both logs held only their section header at 19:43; the parent must confirm the output |
| 3. ci.yml uploads test reports on failure for fast check (server/android) and integration; workflow valid | YAML parse plus structural check | `python -c "import yaml; d=yaml.safe_load(open('.github/workflows/ci.yml')); ..."` (lists upload steps and their `if:` per job) | Pass: parses (exit 0); `check-server`, `check-android` and `integration` each have one `Upload test reports` step with `if: failure()` using `actions/upload-artifact@v4`, paths `server/build/reports/tests/` + `server/build/test-results/` (server, integration) and `android/app/build/reports/tests/` + `android/app/build/test-results/` (android), distinct artifact names. `actionlint` is not installed, so only the YAML fallback named in the criterion was used |
| 4. `sh scripts/check.sh` passes | none | parent runs it after reverting the broken tests | Not run by test-writer: sub-agents cannot run Gradle here, and the working tree currently contains the deliberately broken tests |

## Focused test execution

Command:

`python -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"` plus the structural listing above

Result:

Pass

## Full test execution

Command:

`sh scripts/check.sh`

Result:

Not run: Gradle runs are owned by the parent worker (sub-agents cannot run Gradle in this environment), and the working tree
holds two intentionally broken tests for criterion 2.

## .taskcheck

Result:

Not run (same reason; the parent runs it after reverting the broken tests).

## Bugs found

None.

Observations (not defects):
- The Dockerfile runs Gradle with `--no-daemon`; `org.gradle.jvmargs` still applies because Gradle then starts a single-use
  daemon with those JVM arguments, and `kotlin.daemon.jvmargs` applies to the Kotlin compile daemon. Whether this removes the
  OOM is shown only by the parent's `--no-cache` build reaching and passing `:compileKotlin`.
- `if-no-files-found: ignore` means a failure before tests ran (e.g. ktlint) uploads nothing without a warning; this is
  acceptable for the criterion.

## Coverage

Status: not applicable.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (not applicable).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: ci-oom-and-test-reports; base
  f03bd63fc50fd95676cbcd3ea7966a864fcaaee9; HEAD 6e19bbfc0b62df4303352729bb42b15b2a424c5a; single task commit 6e19bbfc0 supplied by the parent and
  confirmed with `git diff --stat`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 `ci.yml` bd0bda6bfc56..., `Dockerfile` 552cec62981d...,
  `server/build.gradle.kts` b20f35b8b662..., `android/app/build.gradle.kts` b30b8a4e9093...; the two uncommitted broken
  test files are parent demo inputs, not part of the scope.
- Exact included files; renamed/deleted/excluded files and reasons: no file qualifies for the coverage scope. All four changed
  files are configuration: `Dockerfile` (image build), `.github/workflows/ci.yml` (CI workflow), and two Gradle Kotlin DSL
  build scripts, which run inside Gradle at configuration time and are not instrumented by JaCoCo (server) or the Android
  JaCoCo setup, nor are they part of any module's source sets. No file under `server/src/main`, `web/src` or `android/app/src/main` changed.
- Provider/version, command, working directory, and test selection: none run; JaCoCo cannot measure build scripts or YAML.
- Run start/completion time and exit status: N/A.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: N/A (no coverage run).
- Final run after latest test additions and production fixes: N/A (no tests added, no production code changed).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `Dockerfile` | N/A | N/A | N/A | N/A |
| `.github/workflows/ci.yml` | N/A | N/A | N/A | N/A |
| `server/build.gradle.kts` | N/A | N/A | N/A | N/A |
| `android/app/build.gradle.kts` | N/A | N/A | N/A | N/A |

No aggregate is reported: the coverage scope contains no instrumentable production files (this is not 100% coverage).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| All four changed files | Criteria 1-3 | Verified by executing the named commands (image build, broken-test check.sh runs, YAML validation) instead of line coverage |

## Unable to test

- Criterion 1 image build and criterion 2 log output: run by the parent; logs were still in progress when this report was
  written, so the parent must record their final results with `tasks.py verify`.
- Criterion 4 (`sh scripts/check.sh` passes): parent, after reverting the broken tests.
- The upload steps actually running on GitHub Actions after a failure: needs a real failing CI run; `gh` is not available here.
  Follow-up: check the artifact on the next failing CI run.
- `actionlint` is not installed; the YAML parse fallback allowed by criterion 3 was used.

## Summary

No additional tests were necessary for the reviewed scope (build and CI configuration only); static checks pass, and the
runtime criteria depend on the parent's image build and broken-test check.sh runs.
