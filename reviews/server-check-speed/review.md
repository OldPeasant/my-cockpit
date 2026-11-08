# Code Review

Date: 2026-11-08

Task: server-check-speed - Server check: make .taskcheck's server tests fast again

Scope: `git diff bb487db91..HEAD -- . ':!reviews'` (re-review of the final code), committed changes only

Requirements:
- tasks/server-check-speed.md acceptance criteria
- SPEC.md §10.2 (backup secrets: Argon2id-derived key), docs/ARCHITECTURE.md "Testing"/"Coverage"
- AGENTS.md project notes (lint, test conventions, CI must still run every server test)
- Parent focus: production never uses the cheap test KDF/hashing, per-fork isolation, CI still runs every server
  test, status of the earlier finding CR-1
- Context: previous review (reviewed 76d08ab22 before the rebase) and reviews/server-check-speed/tests.md

Git range:
- Branch: main
- HEAD: d55caf13069c0053a795cdbcd2d7ddff0105f4e3 (reviewed revision; code last changed in 568abb8b0, d55caf130 only adds reports)
- Base/start: bb487db914cd59cc0dcd661523f992d6f3ef6ca4
- Commits reviewed: 5 (e68803c9d, d33ce2f2f, 7e608c1ea, 568abb8b0 code; d55caf130 reports only)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of the previous finding

- CR-1 (P2, production default of the backup secrets KDF not covered): **resolved**. The new test
  `a freshly loaded BackupSecrets encrypts with the default KDF, so production is not lowered like Spring tests`
  (`server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupSecretsCryptoTests.kt:88`) loads a separate copy of
  `BackupSecrets` in an isolated `URLClassLoader` (main classes, Kotlin stdlib, Bouncy Castle, platform loader as
  parent), checks that the class really came from that loader, and asserts that both `DEFAULT_KDF` and the initial
  `encryptionKdf` are 64 MiB / 3 passes / 1 lane. Changing the initializer at `BackupSecrets.kt:59` to a weak value now
  fails this test, whatever the test order or fork. The class's static initializer only needs `Kdf` (same output dir),
  `SecureRandom` and the inlined `BackupFormat.DATA_DIR` constant, so the reduced classpath is enough. The parent
  still has to run it (sub-agents cannot run Gradle).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | Per-fork test data dirs are never cleared, and get a new name on most runs**
    - File: `server/src/test/resources/config/application.properties:9` (cleanup only for the temp dir:
      `server/build.gradle.kts:141`)
    - Category: Quality
    - Problem: `cockpit.data-dir=build/test-data/fork-${org.gradle.test.worker:0}` uses Gradle's worker id. That id
      is not a fork index from 1 to N: it keeps counting across runs in the same Gradle daemon. So most runs write a new
      set of `fork-*` folders, and nothing deletes the old ones. `doFirst` clears only `build/tmp/test-jvm/{task}`.
      The clone under review already has `fork-5`..`fork-8`, `fork-17`..`fork-20`, `fork-34`, `fork-36`, up to 77 MB
      each. This leak existed before in a milder form: the old single `build/test-data` (its `blobs` folder is 13 GB
      here) was reused, not cleared. The change adds a new folder set on each run on top of that.
    - Why it matters: agent machines run the check on every `tasks.py done`. Disk use in `server/build` grows by about
      100 to 200 MB per run until someone runs `clean`. This is the same kind of build-dir/tmp exhaustion that has held
      tasks before.
    - Suggested fix: clear `build/test-data` in the `tasks.test` `doFirst` (as is done for the temp dir), before the
      forks start. This also deletes the old shared folders. Optionally update the "Testing" note in
      docs/ARCHITECTURE.md, which calls the folders `fork-{n}` as if `n` were a fork index.

## Notes (checked, no finding)

- Production never uses the cheap crypto. `BackupSecrets.encryptionKdf` is `internal @Volatile`, its initializer is
  `DEFAULT_KDF`, and nothing under `server/src/main` assigns it (grep: the only writers are `support/FastTestCrypto.kt`
  and the save/restore in `BackupSecretsCryptoTests`). `FastTestCrypto` and `META-INF/spring.factories` are in
  the test source set, and a `ContextCustomizerFactory` runs only in the Spring TestContext framework, so `bootRun` and
  the boot jar keep `DEFAULT_KDF` and the `m=16384,t=2,p=1` password encoder. `FastTestCryptoTests` covers the
  production encoder's parameters, and tests that the fast hashes and fast-KDF envelopes still pass production
  verification and `decrypt`'s lower bounds (8 KiB, 1 pass).
- Per-fork isolation. No `junit-platform.properties` exists, so test classes run one after another in each fork. Each
  fork JVM has its own `PostgresTestBase` container (static companion), its own Spring context cache and its own
  default data dir. Classes that need their own data dir (`RestoreTestSupport`, `BackupRoundTripTest`, the
  `*BackupContributorTests`) use `Files.createTempDirectory(...)`, which gives each one a unique name in the shared JVM
  temp dir. `BackupWorkDirCleanupTests` was the only test that listed the shared temp dir. It now uses its own `@TempDir`
  through `tempRoot`. The two new production-path tests (`tempRoot` null) find their own work dir by its unique zip,
  so other forks that create or delete `cockpit-*` folders at the same time cannot affect them. `destroy()` is
  idempotent, so calling it twice in those tests is safe. Fixed output paths written by `test` (for example
  `PerfBaselineTests`' report) have one writer each.
- `tempRoot` in `BackupJobService`/`RestoreService`: null in production, which keeps the old
  `Files.createTempDirectory(prefix)` behaviour. Both branches now have tests with assertions.
- Order-independence fixes (`HabitEdgeCaseTests` deletes all reminders, `GoogleContactsSyncTests` also resets in
  `@AfterEach`) are correct, and each class's `@BeforeEach` keeps it independent of earlier classes.
- CI: the `check-server` job in `.github/workflows/ci.yml` still runs `sh scripts/check.sh server`, that is
  `./gradlew check` with every non-external server test. The `integrationTest` job runs the `external` ones. No test
  was moved out of `test`, and docs/ARCHITECTURE.md says so. The default fork count is `availableProcessors / 4` capped
  at 1..4, so CI's 4-core runners get 1 fork (the old serial behaviour). The override `-PtestForks`/`COCKPIT_TEST_FORKS`
  is clamped to at least 1.
- Heap: 2 GB per fork, unchanged per JVM. As the previous review noted, the fork count does not take memory into
  account (up to 4 JVMs plus 4 Postgres containers per check). Not a finding without an observed failure.
- Not reviewable from code (parent evidence): criterion 1 (20 slowest classes and their times), criterion 3 (baseline
  and new wall time on the same load), criterion 4 (3 green runs in a row), and `sh scripts/check.sh` passing. The new
  tests from 568abb8b0 have not been compiled or run by any helper yet. The parent must run them before recording
  evidence.

## Commands for the parent to run (sub-agents cannot run Gradle)

- `cd server && ./gradlew test --tests '*BackupSecretsCryptoTests' --tests '*FastTestCryptoTests' --tests '*BackupWorkDirCleanupTests'`
- `cd server && ./gradlew check -PtestForks=1` (baseline / serial path)
- `cd server && ./gradlew check` three times (default forks)

## Review conclusion

No material issues found. CR-1 is resolved. CR-2 (P3) is optional cleanup of the test data dirs and does not block
completion.
