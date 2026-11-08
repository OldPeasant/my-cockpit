# Fix Report

Date: 2026-11-08

Task: server-check-speed (faster server check: parallel test forks and cheap test crypto)

Scope: current task, finding CR-2 from `reviews/server-check-speed/review.md` (base revision 220f80815)

## Fixed findings

### CR-2 — Per-fork test data dirs are never cleared, and get a new name on most runs

Files changed:
- `server/build.gradle.kts`
- `docs/ARCHITECTURE.md`

Problem:
`cockpit.data-dir=build/test-data/fork-${org.gradle.test.worker:0}` (test `config/application.properties`) uses
Gradle's worker id. That id keeps growing within a daemon, so most runs create new `fork-*` folders. Nothing deleted
them, and the old shared `build/test-data/blobs` kept growing too. Confirmed in this clone: `build/test-data`
holds `android`, `blobs`, `fork-17`..`fork-20`, `fork-34`, `fork-36`, `fork-38`, `fork-39`, and more.

Fix:
`tasks.withType{Test}().configureEach` now deletes `build/test-data` in its `doFirst`, before the existing
temp-dir cleanup. It runs before any fork starts. The cleanup sits in the shared `configureEach` block, not only in
`tasks.test`, because `integrationTest` and `updateOpenApi` use the same test resources and the same data dir, so
they leaked in the same way. Gradle does not run two tasks of the same project at once, so one test task cannot
delete the data of another task that is still running. Updated the build-file comments and the docs/ARCHITECTURE.md
Testing note, which now reads `build/test-data/fork-{worker id}`, emptied at the start of each test task.

Check that nothing relies on data surviving between runs: grep of `server/src/test` for `data-dir`, `dataDir` and
`test-data` shows that tests either use their own `@TempDir`/temp directories or write fresh data into the
configured data dir during the run. `BackupFixture` even states that seeding expects an empty data dir. Each run
also starts fresh Postgres Testcontainers, so files left from an earlier run were orphans with no database rows
pointing to them. No other build config refers to `build/test-data`.

Validation:
- Gradle not run by the fixer (sub-agents cannot run Gradle here). The parent must run `cd server && ./gradlew check`
  (ktlint for the build script plus all tests), then confirm that `server/build/test-data` only holds the fork
  folders of the latest run.
- `git diff` inspected: only the two files above changed, plus this report.

## Not fixed

None.

## Validation summary

Focused tests:
- Not run by the fixer. Parent: `cd server && ./gradlew check`.

Full test suite:
- Not run by the fixer. Parent: `sh scripts/check.sh`.

Lint/type-check/build:
- Not run by the fixer. ktlint runs on the build script as part of `./gradlew check`. Edits follow the existing
  style: tabs, no new lines above 120 characters.

`.taskcheck`:
- Not run (parent runs it).

Remaining unresolved findings:
0

## Final status

All validated findings fixed. The parent still has to run the Gradle validation.
