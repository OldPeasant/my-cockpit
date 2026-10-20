# Test Report

Date: 2026-10-20

Task: p0-ci - GitHub Actions CI (re-run on the final revision after the CR-1 fix)

Scope: base 0995d28bff12d70c79389824cbd8771569b539bd (claim commit, from the parent) to measured HEAD
609e21a9ed364d04058b6e4da4a4ef6e67aecdff on branch main. Task-owned commits (from `git log 0995d28..HEAD`, filtered to
p0-ci): 6a9d7eb, 5d8364f (assume notes), 0a01809 (implementation, previously tested as 90cbf45 before a rebase) and
609e21a (CR-1 fix plus review reports). Commits by other agents in the range (p0-auth-setup and p0-web-shell
heartbeats/assumes) touch only `tasks/p0-auth-setup.md` and `tasks/p0-web-shell.md` and are excluded.
Changed task files: `.github/workflows/ci.yml` (new), `docs/ARCHITECTURE.md` (CI paragraph in "Testing"),
`tasks/p0-ci.md` (notes), `reviews/p0-ci/*` (evidence). Uncommitted inputs: none besides this report and the
scratch file `.tmp/actionlint-neg/ci-broken.yml` (Git-ignored).
Tested snapshot: `.github/workflows/ci.yml` sha256 eb98e793e8e259ad5b90b8849847f19c8279fa10532f51adb0f9b12b8defa2d4.

Changes since the previous report (HEAD 90cbf45, `git diff 90cbf45 HEAD -- .github docs`):
- `ci.yml`: `push` limited to `branches: [main]`; `paths-ignore` (`tasks/**`, `reviews/**`, `BOARD.md`) on `push`
  and `pull_request`; `cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}`; header comment extended.
- `docs/ARCHITECTURE.md`: CI sentence describes the new triggers.
- The three jobs (steps, setup actions, versions and `run:` commands) are unchanged.

Requirements:
- tasks/p0-ci.md acceptance criteria and notes (assumptions: 3 parallel jobs on ubuntu-24.04; setup-android installs `platforms;android-37.0` and `build-tools;36.0.0`; COCKPIT_NO_ANDROID not set)
- docs/ARCHITECTURE.md "Install", "Testing" and "Coverage"; AGENTS.md
- reviews/p0-ci/review.md (CR-1) and reviews/p0-ci/fixes.md
- Parent instructions: re-run actionlint on the final revision; earlier local runs of the job commands may be reused if the commands are unchanged

## Test files changed

None.

## Tests added or updated

Count: 0

No test was added. The task changes only CI configuration and documentation; there is no executable production
code and the project has no test harness for workflow files. A YAML-parsing test would only restate the literal
strings in the workflow and duplicate actionlint.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: workflow triggers on push and pull_request | actionlint schema check of `on:` (push to `main` with `paths-ignore`, pull_request with `paths-ignore`); manual review against CR-1 | `docker run --rm -v /home/eric/work/my-cockpit-agents/agent-1:/repo:Z -w /repo docker.io/rhysd/actionlint:latest -no-color -verbose` (final revision 609e21a) | Pass (0 errors). Note: pushes to non-main branches no longer trigger CI by design (CR-1); PRs still do |
| CR-1 fix: filters and conditional cancellation are valid syntax and expressions | actionlint on the final file plus a negative control `.tmp/actionlint-neg/ci-broken.yml` with a misspelled `paths-ignor` key and `github.reff` in the `cancel-in-progress` expression | `docker run --rm -v /home/eric/work/my-cockpit-agents/agent-1/.tmp/actionlint-neg:/w:Z -w /w docker.io/rhysd/actionlint:latest -no-color ci-broken.yml` | Pass (real file 0 errors; broken copy exit 1 with both errors reported: `unexpected key "paths-ignor" for "push" section` and `property "reff" is not defined`, so actionlint type-checks the new trigger keys and the expression) |
| AC1: JDK 21, Node from `.nvmrc`, Android SDK platform 37 + build-tools 36 | Manual comparison (see "Version comparison"); actionlint input check of setup-java/setup-node/setup-android/setup-gradle | actionlint command above | Pass (static; unchanged since 90cbf45). Not run on a real GitHub runner |
| AC1: job runs `sh scripts/check.sh` | same command locally | `sh scripts/check.sh` | Pass, reused from the previous revision 90cbf45 (exit 0, "all checks passed"); the job's commands are unchanged. The fixer also ran it after the fix: Pass (reviews/p0-ci/fixes.md) |
| AC1: job runs `./gradlew integrationTest` (working-directory server) | same task locally | `server/gradlew -p server integrationTest --rerun-tasks` | Pass, reused from revision 90cbf45 (exit 0; ExternalToolingSmokeTests 2 tests, 0 failures); job unchanged |
| AC1: job runs `docker build .` | same build locally | `docker build -t cockpit-server:ci-test .` (local `docker` is Podman 5.8.9) | Pass, reused from revision 90cbf45 (exit 0); job and Dockerfile unchanged |
| AC2: versions match `.nvmrc` / Gradle toolchains | Manual comparison | `grep` of `.nvmrc`, `server/build.gradle.kts`, `android/app/build.gradle.kts`, `docs/ARCHITECTURE.md`, `Dockerfile` | Pass (unchanged since 90cbf45) |
| AC2: workflow validated with actionlint | actionlint on final revision + negative control (above) | actionlint commands above | Pass |
| Workflow runs green on GitHub | none | - | Not run: needs a push to GitHub, outside the test-writer's authority. The owner checks the first run (as the criterion allows) |

actionlint: 1.7.12 (`docker.io/rhysd/actionlint:latest -version`), bundling shellcheck, so `run:` scripts are
shellchecked too.

Version comparison (unchanged since the previous report):
- JDK: workflow `JAVA_VERSION: "21"`, Temurin; `server/build.gradle.kts` toolchain 21; `android/app/build.gradle.kts`
  `JavaVersion.VERSION_21`; Dockerfile `eclipse-temurin:21-*`. Match.
- Node: `node-version-file: .nvmrc` (`24`); Dockerfile `node:24-bookworm-slim`. Match.
- Android: `platforms;android-37.0 build-tools;36.0.0`, same as ARCHITECTURE.md "Install"; `compileSdk = 37`. Match.
- Gradle: wrappers (9.8.1); `gradle/actions/setup-gradle@v5` only adds caching.

Observation (not a defect): with `paths-ignore`, a commit touching only `tasks/`, `reviews/` or `BOARD.md` skips CI.
`scripts/check.sh` runs `tests/test_quality_gate.py`, which lives under `tests/` (not ignored), so tooling changes
still trigger CI. Board/evidence-only commits are validated by `tasks.py done` running `.taskcheck` locally.

## Focused test execution

Command:

`docker run --rm -v /home/eric/work/my-cockpit-agents/agent-1:/repo:Z -w /repo docker.io/rhysd/actionlint:latest -no-color -verbose`

Result:

Pass ("Found total 0 errors" for `.github/workflows/ci.yml`, final revision 609e21a, 2026-10-20 ~11:47 +02:00)

## Full test execution

Command:

`sh scripts/check.sh`; `server/gradlew -p server integrationTest --rerun-tasks`; `docker build -t cockpit-server:ci-test .`

Result:

Pass, reused from revision 90cbf45 (all three exit 0, 2026-10-20). Not re-run by the test-writer on 609e21a: the fix
changed only workflow triggers/concurrency and a doc sentence, not the commands or anything they build. The fixer
ran `sh scripts/check.sh` after the fix: Pass.

## .taskcheck

Result:

Pass on 90cbf45 (test-writer) and after the fix (fixer, reviews/p0-ci/fixes.md); `.taskcheck` is `sh scripts/check.sh`.
The parent runs it again before `done`.

## Bugs found

None.

## Coverage

Status: not applicable. The task changes no source code: only `.github/workflows/ci.yml` (CI configuration),
`docs/ARCHITECTURE.md`, `tasks/p0-ci.md` and `reviews/p0-ci/*` (documentation/evidence). docs/ARCHITECTURE.md
"Coverage" defines coverage for server, web and Android source; none of those parts has a changed file in this task,
so the task coverage scope is empty. No coverage command was run and no percentage is reported (an empty scope is not 100%).

Metric: whole-file coverage of task-changed production files: N/A (no production files in scope).
Changed-line coverage: not measured (no production files in scope).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-ci; base 0995d28bff12d70c79389824cbd8771569b539bd (from the parent); HEAD 609e21a9ed364d04058b6e4da4a4ef6e67aecdff; commits 6a9d7eb, 5d8364f, 0a01809, 609e21a from `git log 0995d28..HEAD`
- Source/test snapshot identifier (including uncommitted inputs): HEAD 609e21a, clean tree before this report; ci.yml sha256 eb98e793e8e259ad5b90b8849847f19c8279fa10532f51adb0f9b12b8defa2d4
- Exact included files; renamed/deleted/excluded files and reasons: included none; excluded `.github/workflows/ci.yml` (configuration, validated by actionlint), `docs/ARCHITECTURE.md`, `tasks/p0-ci.md`, `reviews/p0-ci/*` (documentation/evidence)
- Provider/version, command, working directory, and test selection: none (not applicable)
- Run start/completion time and exit status: N/A
- Generated report paths and report hashes/run identifier: N/A
- Evidence outputs are fresh and inputs stayed unchanged: actionlint ran on 609e21a on 2026-10-20; the job-command runs are from 90cbf45, whose job definitions are identical to 609e21a
- Final run after latest test additions and production fixes: yes for actionlint; job commands reused from the pre-fix revision because the fix did not change them

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (none: no production source changed) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `.github/workflows/ci.yml` (whole file) | Workflow actually runs green on GitHub-hosted ubuntu-24.04 (action downloads, setup-android installing platform 37, Docker/BuildKit instead of local Podman, npm cache path), and trigger filters/cancellation behave as intended on GitHub | Not testable here; covered statically by actionlint and locally by running the same commands. The owner checks the first CI run |

## Unable to test

- The workflow running on GitHub Actions, including the `branches`/`paths-ignore` filters and the conditional `cancel-in-progress`: needs a push, which the test-writer must not do. Follow-up: the owner checks the first runs on GitHub (allowed by AC2).
- Whether `android-actions/setup-android@v3` can install `platforms;android-37.0` on the runner: only observable on GitHub. Same follow-up.
- `docker build .` with GitHub's Docker/BuildKit: locally only Podman 5.8.9 is available (the build passed there). Same follow-up.

## Summary

No additional tests were necessary for the reviewed scope. The final workflow (609e21a) passes actionlint 1.7.12,
and a broken copy of the new trigger/concurrency settings fails as expected. The job commands are unchanged since
90cbf45, where all three passed locally. Task-scoped coverage is not applicable because no source code changed. No
production defects found.
