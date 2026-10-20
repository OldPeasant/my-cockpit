# Test Report

Date: 2026-10-20

Task: p0-ci - GitHub Actions CI

Scope: base 0995d28bff12d70c79389824cbd8771569b539bd (claim commit) to measured HEAD
90cbf45d7003d44cad59c8483f7d457fe84b9d37 on branch main. Task-owned commits: 6a9d7eb, 5d8364f (assume notes in
`tasks/p0-ci.md`) and 90cbf45 (implementation). The parent named e15125e as the task commit; it is the same patch
before a rebase (`git diff e15125e 90cbf45 --stat` changes only `tasks/p0-ci.md`, 2 lines of notes), so
`.github/workflows/ci.yml` and `docs/ARCHITECTURE.md` are identical in both.
Changed files: `.github/workflows/ci.yml` (new, 65 lines), `docs/ARCHITECTURE.md` (+4 lines, CI paragraph in
"Testing"), `tasks/p0-ci.md` (notes). Uncommitted inputs: none (`git status --short` empty before this report).
Tested snapshot: `.github/workflows/ci.yml` sha256 a8e7826490fad3d9817efff5317a661e190b7626a1a37c4f6f09b52ab9793763.

Requirements:
- tasks/p0-ci.md acceptance criteria and notes (assumptions: 3 parallel jobs on ubuntu-24.04; setup-android installs `platforms;android-37.0` and `build-tools;36.0.0`; COCKPIT_NO_ANDROID not set)
- docs/ARCHITECTURE.md "Install", "Testing" and "Coverage"; AGENTS.md
- Parent instructions: decide whether tests are warranted, run actionlint, run the commands the workflow invokes where practical

## Test files changed

None.

## Tests added or updated

Count: 0

No test was added. The task changes only CI configuration and documentation; there is no executable production
code. The project has no test harness for workflow files, and adding one (e.g. a Python test that parses the
YAML) would duplicate actionlint and would only assert the literal strings that the workflow contains. The
workflow is validated by actionlint, and the commands it runs were run locally (see below).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: workflow triggers on push and pull_request | actionlint schema check; manual review of `on:` (push, pull_request, no filters) | `docker run --rm -v "$PWD:/repo:Z" -w /repo docker.io/rhysd/actionlint:latest -no-color -verbose` | Pass (0 errors, exit 0) |
| AC1: JDK 21, Node from `.nvmrc`, Android SDK platform 37 + build-tools 36 | Manual comparison of the workflow with the sources (see "Version comparison") and actionlint input check of setup-java/setup-node/setup-android/setup-gradle | same actionlint command | Pass (static). A real GitHub runner was not run: the action downloads and the SDK install happen only on GitHub |
| AC1: job runs `sh scripts/check.sh` | the same command locally (with local JDK/Node/Android SDK) | `sh scripts/check.sh` | Pass (exit 0; tfp gate, server check, web 56 tests in 6 files, android ktlint + unit tests + lint, "all checks passed") |
| AC1: job runs `./gradlew integrationTest` (working-directory server) | the same task locally, forced to rerun | `server/gradlew -p server integrationTest --rerun-tasks` | Pass (exit 0, BUILD SUCCESSFUL; ExternalToolingSmokeTests 2 tests, 0 failures/errors/skipped) |
| AC1: job runs `docker build .` | the same build locally | `docker build -t cockpit-server:ci-test .` (local `docker` is Podman 5.8.9, not GitHub's Docker/BuildKit) | Pass (exit 0, image committed; test image removed afterwards) |
| AC2: versions match `.nvmrc` / Gradle toolchains | Manual comparison (see below) | `grep` of `.nvmrc`, `server/build.gradle.kts`, `android/app/build.gradle.kts`, `docs/ARCHITECTURE.md`, `Dockerfile` | Pass |
| AC2: workflow validated with actionlint | actionlint run plus a negative control: a copy with a misspelled input (`node-version-fil`) in `.tmp/ci-broken.yml` | `docker run --rm -v "$PWD/.tmp:/w:Z" -w /w docker.io/rhysd/actionlint:latest -no-color ci-broken.yml` | Pass (real file: 0 errors; broken copy: 1 error `input "node-version-fil" is not defined in action "actions/setup-node@v5"`, exit 1, so the linter checks action inputs and is not a no-op) |
| Workflow runs green on GitHub | none | - | Not run: needs a push to GitHub, outside the test-writer's authority. The owner checks the first run (as the criterion allows) |

actionlint: rhysd/actionlint 1.7.12 (`docker.io/rhysd/actionlint:latest`, `-version`), with shellcheck 0.11.0
bundled in the image, so the `run:` scripts were shellchecked too.

Version comparison:
- JDK: workflow `JAVA_VERSION: "21"`, Temurin; `server/build.gradle.kts:16` toolchain `JavaLanguageVersion.of(21)`;
  `android/app/build.gradle.kts:37-38` `JavaVersion.VERSION_21`; Dockerfile `eclipse-temurin:21-jdk` / `21-jre`. Match.
- Node: workflow `node-version-file: .nvmrc`; `.nvmrc` is `24`; Dockerfile `node:24-bookworm-slim`. Match.
- Android: workflow `platforms;android-37.0 build-tools;36.0.0`; `docs/ARCHITECTURE.md:71` installs the same
  packages; `android/app/build.gradle.kts:17` `compileSdk = 37`. Match.
- Gradle: workflow uses the wrappers (`gradle/actions/setup-gradle@v5` only sets up caching); both wrappers are 9.8.1.

## Focused test execution

Command:

`docker run --rm -v "$PWD:/repo:Z" -w /repo docker.io/rhysd/actionlint:latest -no-color -verbose`

Result:

Pass (exit 0, "Found total 0 errors" for `.github/workflows/ci.yml`)

## Full test execution

Command:

`sh scripts/check.sh`; `server/gradlew -p server integrationTest --rerun-tasks`; `docker build -t cockpit-server:ci-test .`

Result:

Pass (all three exit 0, run on 2026-10-20 at HEAD 90cbf45 with a clean working tree)

## .taskcheck

Result:

Pass (`.taskcheck` is `sh scripts/check.sh`, run above, exit 0)

## Bugs found

None.

## Coverage

Status: not applicable. The task changes no source code: only `.github/workflows/ci.yml` (CI configuration),
`docs/ARCHITECTURE.md` and `tasks/p0-ci.md` (documentation). docs/ARCHITECTURE.md "Coverage" defines coverage for
server (Kover/JaCoCo), web and Android source; none of those parts has a changed file in this task, so the task
coverage scope is empty. No coverage command was run and no percentage is reported (an empty scope is not 100%).

Metric: whole-file coverage of task-changed production files: N/A (no production files in scope).
Changed-line coverage: not measured (no production files in scope).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-ci; base 0995d28bff12d70c79389824cbd8771569b539bd (from the parent, the claim commit); HEAD 90cbf45d7003d44cad59c8483f7d457fe84b9d37; commits 6a9d7eb, 5d8364f, 90cbf45 from `git log 0995d28..HEAD`
- Source/test snapshot identifier (including uncommitted inputs): HEAD 90cbf45, clean tree; ci.yml sha256 a8e7826490fad3d9817efff5317a661e190b7626a1a37c4f6f09b52ab9793763
- Exact included files; renamed/deleted/excluded files and reasons: included none; excluded `.github/workflows/ci.yml` (configuration, validated by actionlint), `docs/ARCHITECTURE.md` and `tasks/p0-ci.md` (documentation)
- Provider/version, command, working directory, and test selection: none (not applicable)
- Run start/completion time and exit status: N/A
- Generated report paths and report hashes/run identifier: N/A
- Evidence outputs are fresh and inputs stayed unchanged: the validation runs above were made on 2026-10-20 at HEAD 90cbf45; ci.yml hash was the same before and after
- Final run after latest test additions and production fixes: yes (no tests or fixes were added)

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (none: no production source changed) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `.github/workflows/ci.yml` (whole file) | Workflow actually runs green on GitHub-hosted ubuntu-24.04 (action downloads, `setup-android` installing platform 37, Docker/BuildKit instead of local Podman, `npm` cache path) | Not testable here; covered statically by actionlint and locally by running the same commands. The owner checks the first CI run |

## Unable to test

- The workflow running on GitHub Actions: needs a push, which the test-writer must not do. Follow-up: the owner checks the first run on GitHub (allowed by AC2).
- Whether `android-actions/setup-android@v3` can install `platforms;android-37.0` on the runner: only observable on GitHub. Same follow-up.
- `docker build .` with GitHub's Docker/BuildKit: locally only Podman 5.8.9 is available (the build passed there). Same follow-up.

## Summary

No additional tests were necessary for the reviewed scope. The workflow passes actionlint 1.7.12 (with
shellcheck), a broken copy fails as expected, the versions match `.nvmrc` and the Gradle toolchains, and the three
commands it runs pass locally. Task-scoped coverage is not applicable because no source code changed. No production
defects found.
