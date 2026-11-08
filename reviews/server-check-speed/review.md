# Code Review

Date: 2026-11-08

Task: server-check-speed - Server check: make .taskcheck's server tests fast again

Scope: `git diff 8af08396d..HEAD` (commits d0d4eae74, 802620357, 76d08ab22), committed changes only

Requirements:
- tasks/server-check-speed.md acceptance criteria
- SPEC.md §10.2 (backup secrets: Argon2id-derived key), docs/ARCHITECTURE.md "Testing"/"Coverage"
- AGENTS.md project notes (lint, test conventions, CI must still run every server test)
- Parent agent focus: production safety of `BackupSecrets.encryptionKdf`, fork isolation, heap per fork, docs accuracy, BackupJobService/RestoreService changes

Git range:
- Branch: main
- HEAD: 76d08ab22
- Base/start: 8af08396db17f257978bbc5c770ba893ebd363cc
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | The production default of the backup secrets KDF is no longer covered by any test**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupSecretsCryptoTests.kt:71`
      (production value: `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupSecrets.kt:59`)
    - Category: Tests
    - Problem: `encrypt`'s default changed from the constant `DEFAULT_KDF` to the mutable `encryptionKdf`, whose
      initializer is `DEFAULT_KDF`. The test `the default KDF is 64 MiB, 3 passes, one lane and is what encrypt uses`
      now sets `BackupSecrets.encryptionKdf = BackupSecrets.DEFAULT_KDF` itself before calling `encrypt`, so it only
      proves that `encrypt` honours `encryptionKdf` and that the `DEFAULT_KDF` constant has the right values. It no
      longer checks what production actually uses: if the initializer at `BackupSecrets.kt:59` were changed to a weak
      value (for example `Kdf(8, 1, 1)`, the same as `FAST_KDF`), every test would still pass. Spring tests set
      `FAST_KDF` anyway, and `FastTestCryptoTests` passes the KDF explicitly. Before this change the old test covered
      this (`encrypt(plaintext, PASSPHRASE)` with the compile-time default `DEFAULT_KDF`).
    - Why it matters: `ZipBackupWriter.kt:135` calls `BackupSecrets.encrypt(...)` without a KDF, so this initializer
      sets how strongly every real backup's secrets (IMAP/SMTP/CalDAV passwords) are protected against brute-forcing
      the passphrase offline (SPEC.md §10.2). I checked the current code and production is safe: no code under
      `src/main` assigns `encryptionKdf`, and the only writer is `FastTestCrypto`, which is registered in
      `src/test/resources/META-INF/spring.factories` and is not part of the boot jar. What is missing is the
      regression guard for this security parameter, and the change itself took it away.
    - Suggested fix: assert the production initial value in a way that does not depend on test order. For example,
      have `FastTestCrypto` keep the value it replaces on its first write (a `productionKdf` captured before
      `encryptionKdf = FAST_KDF`; the current value if no Spring context ran yet in this fork) and assert that equals
      `DEFAULT_KDF`. Another option is to make the default a constructor/bean parameter of the writer instead of a
      mutable global, so tests override a bean and the production default stays a constant that the old test
      covers. Keep the existing round-trip with `DEFAULT_KDF`.

## P3 Low

None.

## Notes (checked, no finding)

- `BackupSecrets.encryptionKdf` in production: `internal` plus `@Volatile`, assigned only in
  `support/FastTestCrypto.kt` (test source set) and restored in a try/finally in `BackupSecretsCryptoTests`. Nothing
  in `src/main` writes it. `ContextCustomizerFactory` only runs in the Spring TestContext framework, so `bootRun` or a
  jar cannot get the weak KDF. Restores read the KDF from the envelope, and `FastTestCryptoTests` covers that the 8 KiB
  / 1 pass parameters pass `decrypt`'s lower bounds.
- Password encoder swap: `SecurityConfiguration.passwordEncoder()` returns `PasswordEncoder` backed by
  `Argon2PasswordEncoder`. The BeanPostProcessor that `customizeContext` adds before refresh replaces it with an
  equally typed `Argon2PasswordEncoder`. The production parameters (`m=16384,t=2,p=1`) and the production
  encoder's ability to verify the fast hashes are covered by `FastTestCryptoTests`. The customizer is one singleton
  object (identity equality), so it adds the same entry to every context cache key and does not split the cache.
- Fork isolation: no `junit-platform.properties` exists, so classes run one after another inside each fork. Each
  fork gets its own Postgres container (`PostgresTestBase` companion, one per JVM), its own Spring context cache,
  and its own data dir (`cockpit.data-dir=build/test-data/fork-${org.gradle.test.worker:0}`; Gradle sets that system
  property in each test worker JVM). The shared `java.io.tmpdir` (`build/tmp/test-jvm/test`, emptied in `doFirst`
  before any fork starts) is listed only by `BackupWorkDirCleanupTests`, which now uses its own `@TempDir` through
  `tempRoot`. All other `createTempDirectory`/`createTempFile` calls in tests use unique random names. Other
  `listDirectoryEntries` checks (thumbnails tmp, mail tmp, pre-restore) are inside the per-fork data dir or a
  per-class dir.
- Order-dependence fixes: `HabitEdgeCaseTests.reset()` also runs from `@BeforeEach`, so deleting every reminder
  protects the `fireDue` counts from leftovers of earlier classes. `GoogleContactsSyncTests` now also cleans up in
  `@AfterEach`. Both changes are sound.
- `BackupJobService`/`RestoreService` `tempRoot`: production never sets it (null means the old
  `Files.createTempDirectory(prefix)`). Behaviour is unchanged, and `destroy()` still deletes only the lazily created
  work dir.
- Fork count and heap: `availableProcessors / 4` capped at 4 (an override clamped to at least 1), with 2 GB heap per
  fork. Docs and the build comment match the code. GitHub's 4-core runners get 1 fork, so CI behaves as before and
  still runs every test. The fork count does not take available memory into account: on a 16-core machine with 3
  agents running the check at once, up to 12 test JVMs with 2 GB heap each plus 12 Postgres containers can run. Not
  reported as a finding because nothing shows it failed, but the parent should watch for OOM or swap during the
  3-run validation.
- Jacoco: the Gradle jacoco agent appends to one exec file and supports parallel forks, so the
  `jacocoTestReport` coverage instructions in ARCHITECTURE.md still apply.

## Commands for the parent to run (sub-agents cannot run Gradle)

- `cd server && ./gradlew test --tests '*BackupSecretsCryptoTests' --tests '*FastTestCryptoTests' --tests '*BackupWorkDirCleanupTests'`
- `cd server && ./gradlew check -PtestForks=1` (the serial path still passes after the order-dependence fixes)
- `cd server && ./gradlew check` (default forks; the 3-run flakiness criterion)

## Review conclusion

Material issues found; fix before completing the task. CR-1 is P2: production is safe today, but the change
removed the test that guards the backup KDF default.
