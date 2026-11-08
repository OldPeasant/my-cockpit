---
title: Server check: make .taskcheck's server tests fast again
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/build.gradle.kts,server/src/test,scripts/check.sh,.github/workflows/ci.yml,docs/ARCHITECTURE.md]
prio: 1
lease_until: 2026-11-08T18:15:45Z
---
## Goal
`sh scripts/check.sh` (`.taskcheck`, run by every `tasks.py done`) should take < 2 min but no longer finishes in
`TASK_CHECK_TIMEOUT` (900 s) on smaller agent machines. On 2026-11-08 `and-mail-attachments` went on hold because
the check exceeded 900 s on felix's machine (8 cores, load ~2, so not CPU contention); finishing it on dell26
(16 cores, 3 agents running) the check took 1180 s (and 507 s for the next task, depending on load and Gradle caching). The server `test` task
dominates: its test times add up to ~1390 s and it runs every class one after another in a single JVM
(`server/build.gradle.kts`, `tasks.test`). Backup tests are the largest (RestoreWebTests ~100 s,
FilesBackupContributorTests ~98 s, CalendarBackupContributorTests ~90 s, BackupSecretsTest ~83 s, ...).
Make the server part of the check fast without dropping coverage that matters for every task.

## Acceptance criteria
- [ ] The slowest server test classes are measured (from the Gradle JUnit XML reports) and the 20 slowest are listed with their times in `docs/ARCHITECTURE.md` "Testing" or the task's evidence
- [ ] The server `test` task runs faster, using one or more of: test classes in parallel (`maxParallelForks` / JUnit parallel execution, with a safe heap per fork and the shared Postgres Testcontainer), fewer distinct Spring contexts (shared test configuration instead of per-class `@MockitoBean`/properties), slow end-to-end style tests (e.g. full backup/restore round trips) moved to a tagged suite outside `check` that CI still runs; each moved test is listed and still runs in CI
- [ ] `cd server && ./gradlew check` wall time is at least halved compared to the measured baseline on the same machine, with the same machine load; both times are recorded
- [ ] No flakiness introduced: `cd server && ./gradlew check` passes 3 runs in a row; CI (`.github/workflows/ci.yml`) still runs every server test somewhere
- [ ] `docs/ARCHITECTURE.md` "Testing"/"Coverage" describe the new split and how to run the moved tests; `sh scripts/check.sh` passes

## Notes
Created 2026-11-08 by the owner after `and-mail-attachments` (check > 900 s) and repeated reports of check.sh
not finishing within 10 min in agent sessions. Related: `ci-split-check` (CI jobs per part),
`fix-flaky-pagefetcher-size-test` (server test failing on one machine).
Until this is done, finishing a task may need `TASK_CHECK_TIMEOUT=1800` (the owner used it for
`and-mail-attachments` and `and-mail-compose-plus`).
