---
title: Server check: make .taskcheck's server tests fast again
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/build.gradle.kts,server/src/test,scripts/check.sh,.github/workflows/ci.yml,docs/ARCHITECTURE.md]
prio: 1
lease_until: 2026-11-08T19:01:09Z
extended: dell26/agent-1
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
- [x] The slowest server test classes are measured (from the Gradle JUnit XML reports) and the 20 slowest are listed with their times in `docs/ARCHITECTURE.md` "Testing" or the task's evidence
  - verified (2026-11-08 19:24+0100 by dell26/agent-1): python .tmp/slow.py on server/build/test-results/test JUnit XML (attribute time) of baseline run (main before task, ./gradlew cleanTest check, 536 s, 390 classes, sum 517 s). 20 slowest: BackupSecretsTest 47.0s, MailBackupContributorTests 30.8, RestoreCliTests 21.8, TotpEnrollmentWebTests 18.1, CockpitServerApplicationTests 17.5, FilesBackupContributorTests 12.1, RestoreWebTests 10.9, CalendarBackupContributorTests 10.5, SetupEdgeCaseTests 10.1, MasterKeyStartupTests 9.0, GtdBackupContributorTests 8.7, OAuthEdgeCaseWebTests 8.6, SetupWebTests 7.4, FilesWebTests 7.3, BookmarkWebTests 7.3, DeviceTokenEdgeCaseTests 7.2, LoginEdgeCaseTests 6.6, ContactsBackupContributorTests 6.4, PasskeyTotpReenrollmentTests 5.9, JournalBackupContributorTests 5.9. How to repeat is in ARCHITECTURE.md Testing (XML in server/build/test-results/test/)
- [x] The server `test` task runs faster, using one or more of: test classes in parallel (`maxParallelForks` / JUnit parallel execution, with a safe heap per fork and the shared Postgres Testcontainer), fewer distinct Spring contexts (shared test configuration instead of per-class `@MockitoBean`/properties), slow end-to-end style tests (e.g. full backup/restore round trips) moved to a tagged suite outside `check` that CI still runs; each moved test is listed and still runs in CI
  - verified (2026-11-08 19:37+0100 by dell26/agent-1): server/build.gradle.kts tasks.test maxParallelForks = 1 per 4 cores (max 4, 2g heap each, -PtestForks/COCKPIT_TEST_FORKS), per-fork Postgres container + data dir build/test-data/fork-{worker}; cheap Argon2 + backup KDF in all Spring test contexts (support/FastTestCrypto via META-INF/spring.factories) -> ./gradlew cleanTest check 536 s -> 242 s on dell26. No test moved out of test (none needed), so nothing to list; CI check-server still runs all.
- [x] `cd server && ./gradlew check` wall time is at least halved compared to the measured baseline on the same machine, with the same machine load; both times are recorded
  - verified (2026-11-08 19:24+0100 by dell26/agent-1): dell26 (16 cores, 3 agents running), back to back: cd server && ./gradlew --quiet cleanTest check (compile warm) on main before the task (worktree at 8af08396d) -> 536 s (load 8.4/11.0/12.2 at start, 10.9/11.9/12.0 at end); same command on task code e68803c9d..d55caf130 (4 forks, cheap test Argon2) -> 242 s (load 3.9/10.4/11.6 at start, 11.6/12.9/12.5 at end), i.e. -55%. 15-min load ~12 in both runs.
- [x] No flakiness introduced: `cd server && ./gradlew check` passes 3 runs in a row; CI (`.github/workflows/ci.yml`) still runs every server test somewhere
  - verified (2026-11-08 19:37+0100 by dell26/agent-1): 3 consecutive runs of cd server && ./gradlew --quiet cleanTest check on final code (fab402e79, 4 forks) on dell26 under load ~12: rc=0 each, 237/270/256 s, 5211 tests, 0 failures each; build/test-data held only the current run's 4 fork dirs. CI: .github/workflows/ci.yml check-server runs ./gradlew check (all non-external tests, 1 fork on 4-core runners), integrationTest job the external ones; no test moved.
- [ ] `docs/ARCHITECTURE.md` "Testing"/"Coverage" describe the new split and how to run the moved tests; `sh scripts/check.sh` passes

## Notes
Created 2026-11-08 by the owner after `and-mail-attachments` (check > 900 s) and repeated reports of check.sh
not finishing within 10 min in agent sessions. Related: `ci-split-check` (CI jobs per part),
`fix-flaky-pagefetcher-size-test` (server test failing on one machine).
Until this is done, finishing a task may need `TASK_CHECK_TIMEOUT=1800` (the owner used it for
`and-mail-attachments` and `and-mail-compose-plus`).
- extended by dell26/agent-1: Implemented and committed (3c33df2e8..2d1d57218): parallel test forks (1 per 4 cores, max 4, 2g heap, per-fork Postgres/data dir, -PtestForks/COCKPIT_TEST_FORKS), cheap Argon2/backup KDF in Spring tests via support/FastTestCrypto + spring.factories, order-independent temp-dir tests, ARCHITECTURE.md Testing notes; tests.md + review.md committed (review CR-1 P2 was addressed by test-writer commit 2d1d57218). Left: (1) re-run code-reviewer on final code (review predates 2d1d57218); (2) measure baseline (-PtestForks=1) vs new ./gradlew check wall time on same load, list 20 slowest classes from build/test-results/test XML in ARCHITECTURE.md or evidence; (3) 3 consecutive green ./gradlew check runs; (4) sh scripts/check.sh; (5) verify #1-#5, write quality.json (CR-1 disposition), done.
