# Code Review

Date: 2026-10-20

Task: p0-ci - GitHub Actions CI

Scope: task commits from base 0995d28bff12d70c79389824cbd8771569b539bd (claim) to HEAD 90cbf45; reviewed files `.github/workflows/ci.yml` (new) and `docs/ARCHITECTURE.md` (CI paragraph). Board-only changes in `tasks/` were not reviewed. Only committed changes were reviewed; `reviews/p0-ci/` is untracked.

Requirements:
- tasks/p0-ci.md acceptance criteria and recorded assumptions (3 parallel jobs on ubuntu-24.04, major-tag action pins, Android checked in CI)
- docs/ARCHITECTURE.md "Stack", "Install", runtime pinning rules, "Test"
- AGENTS.md (project notes, evidence rules)
- Parent instructions: compare versions with .nvmrc, server/build.gradle.kts, android/app/build.gradle.kts, Dockerfile, ARCHITECTURE.md Install; validate with actionlint; check security, runner compatibility of scripts/check.sh, caching

Git range:
- Branch: main
- HEAD: 90cbf45d7003d44cad59c8483f7d457fe84b9d37
- Base/start: 0995d28bff12d70c79389824cbd8771569b539bd
- Commits reviewed: 3 (6a9d7eb, 5d8364f assume notes; 90cbf45 implementation)

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Unfiltered push trigger + per-ref cancel-in-progress: main CI will almost never finish while agents run, and every wip backup runs the full pipeline**
    - File: `.github/workflows/ci.yml:6-15`
    - Category: Integration
    - Problem: `on: push:` has no branch or path filter, and `concurrency: group: ci-${{ github.ref }}` with `cancel-in-progress: true` applies to `main` too. Most pushes to this repository's `main` change only the board. In the last day `origin/main` got 279 commits, most of them `heartbeat:` (58), `assume:` (45), `verify:` (42) and `claim:` (21), often seconds apart (e.g. 11:39:35 to 11:41:28). Each push starts all 3 jobs (check up to 40 min, integration and image up to 30 min each) and cancels the previous run on `main`. While agents work, a `main` run will almost never get to finish, so a `done` merge that breaks the build won't show a red CI result. The agent runner also force-pushes `wip/<id>` backups (`agents.py:458`), and each one starts another full 3-job run. (`status/*` branches are not affected: their commits only contain `status.json`, so they have no workflow file.)
    - Why it matters: The task goal is "CI that runs what agents run", so it should give a usable result for each finished task on `main`. With constant cancellations it mostly reports "cancelled", and it uses a lot of runner minutes on board-only and backup commits.
    - Suggested fix: Limit `push` to `branches: [main]` (wip backups and status branches are excluded that way; PRs still run through `pull_request`). Add `paths-ignore` for board and review evidence (`tasks/**`, `reviews/**`, `BOARD.md`) so heartbeat/claim/assume/verify commits don't start CI. Don't cancel runs on `main`, e.g. `cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}`, so every task merge gets a complete result. Record the trigger policy with `tasks.py assume`.

## P3 Low

None.

## Verified without findings

- Acceptance criterion 1: triggers on `push` and `pull_request`. JDK 21 through `actions/setup-java` (Temurin) in the `check` and `integration` jobs. Node through `node-version-file: .nvmrc`. Android SDK through `android-actions/setup-android` with `platforms;android-37.0` and `build-tools;36.0.0`. The jobs run `sh scripts/check.sh`, `./gradlew integrationTest` (working-directory `server`) and `docker build .`.
- Acceptance criterion 2, version match:
  - JDK: `JAVA_VERSION: "21"` matches the `server/build.gradle.kts:16` toolchain (21), `android/app/build.gradle.kts:37-38` (`VERSION_21`) and the Dockerfile (`eclipse-temurin:21-jdk`/`21-jre`).
  - Node: `.nvmrc` is `24`, which fits `engines` `^24.15.0` (setup-node resolves the latest 24.x) and the Dockerfile's `node:24-bookworm-slim`.
  - Android: the SDK packages are the same as in the ARCHITECTURE.md Install section, and `compileSdk = 37`.
  - Gradle: both wrappers are 9.8.1. `setup-gradle` only adds caching and uses the wrappers.
- actionlint: `docker run --rm -v "<clone>:/repo:Z" -w /repo docker.io/rhysd/actionlint:latest -no-color` -> exit 0, no findings.
- Security:
  - Workflow-level `permissions: contents: read`.
  - No `pull_request_target`, and no `${{ github.event.* }}` interpolation into `run:` scripts.
  - No secrets used.
  - The image is built without a push.
- scripts/check.sh on ubuntu-24.04:
  - `ANDROID_HOME` is exported by setup-android (and preset on the runner), so the `~/Android/Sdk` fallback isn't used.
  - `python3` is preinstalled.
  - `tests/test_quality_gate.py` sets `user.email`/`user.name` locally in every temp clone it commits in (lines 35-36, 228-229), so it needs no global git identity.
  - Docker Engine is available for Testcontainers.
  - Without `node_modules`, `npm ci` runs.
  - `COCKPIT_NO_ANDROID` is unset, so Android is checked.
  - `server/gradlew`, `android/gradlew` and `scripts/check.sh` are committed with mode 100755.
- Caching: the npm cache is keyed on `web/package-lock.json`, and setup-gradle caches `~/.gradle` for both Gradle projects. The integration job doesn't need Node: `integrationTest` is a Gradle test task in the server only.
- Docs: the ARCHITECTURE.md CI paragraph matches the workflow and the actionlint command.
- Evidence: the criteria aren't verified yet (`tasks.py verify p0-ci` shows both unchecked). Before `done`, the parent must record actionlint and the local runs, and skip "workflow runs green on GitHub" with a reason, as the criterion allows.

## Review conclusion

Material issues found; fix before completing the task.
