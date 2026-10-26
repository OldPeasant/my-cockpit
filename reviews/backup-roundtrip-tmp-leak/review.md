# Code Review

Date: 2026-10-26

Task: backup-roundtrip-tmp-leak - BackupRoundTripTest leaks a temp data dir per run

Scope: `git diff 2025f6562 2eac0d71d62d45e31e192c8945a92e9bf18fc001` (one task commit; the only code change is in `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupRoundTripTest.kt`; `tasks/` change is claim metadata)

Requirements:
- `tasks/backup-roundtrip-tmp-leak.md` acceptance criteria
- `AGENTS.md` (project notes, conventions)
- `docs/QUALITY.md` (report contract)
- Existing test conventions in `server/src/test/kotlin/ch/sonensei/cockpit/backup/`

Git range:
- Branch: main
- HEAD: 2eac0d71d62d45e31e192c8945a92e9bf18fc001
- Base/start: 2025f6562
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## What was checked

- Commands run (read-only): `git diff 2025f6562 2eac0d71d...`, read `BackupRoundTripTest.kt` in full, grep of
  `server/src/test` for other users of `TestFixtureBackupConfiguration`, subclasses of the test, and other
  `Files.createTempDirectory` data dirs, grep of `server/src/main` for `cockpit.data-dir` consumers and `@Scheduled`
  jobs. Gradle was not run by the reviewer; the parent reported
  `server/gradlew -p server test --tests 'ch.sonensei.cockpit.backup.*'` -> BUILD SUCCESSFUL, 176 tests, 0 failures,
  `/tmp/cockpit-round-trip-data*` count 10 before and 10 after.
- Criterion 1 (delete after the class, also on failure; round trip unchanged): the companion gets a
  `@JvmStatic @AfterAll fun deleteDataDir()` calling `dataDir.toFile().deleteRecursively()`. With `@JvmStatic`, Kotlin
  emits a static method on `BackupRoundTripTest` carrying the annotation, which is the form JUnit Jupiter requires for
  `@AfterAll` under the default per-method lifecycle. JUnit runs `@AfterAll` after the class's tests regardless of
  test failures (and also when Spring context loading fails, since that happens during test-instance post-processing,
  after the companion has already created the directory). The test body, `wipe()`, and the `@DynamicPropertySource`
  are unchanged, so the round trip is unchanged.
- Cached Spring context still pointing `cockpit.data-dir` at the deleted directory: the context cache key includes
  the class's `@DynamicPropertySource` methods and `@Import(TestFixtureBackupConfiguration::class)`. No other class
  extends `BackupRoundTripTest` or reuses that property-source method (`RestoreWebTests` imports an additional
  configuration and has its own property source), so no other test class can receive this context. The context
  stays cached until JVM exit; the `@Scheduled` jobs in `server/src/main` (trash purge at a nightly cron, sync
  schedulers, IMAP eviction) do not create files under the data dir in the remaining run time, and the data-dir
  stores (`BlobStore`, `ChunkStore`, `ThumbnailStore`, `AndroidApkStore`, `RestoreService`) only write on demand.
  If the class were somehow re-run in the same JVM, `wipe()` already calls `Files.createDirectories(dataDir)` first.
- Criterion 2: covered by the parent's reported run (count unchanged before/after). Criterion 3 (`sh scripts/check.sh`)
  is the parent's to run and record; not evaluated here.
- Security / quality: test-only change, no secrets, no production code touched; the KDoc line is accurate.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Out-of-scope observation (not a finding for this task)

The same leak pattern (a companion-level `Files.createTempDirectory(...)` data dir with no cleanup) exists in other
test classes outside this task's `touches`, for example `RestoreWebTests.kt:655` (`cockpit-restore-data`),
`RestoreCliTests.kt:394`, `FilesBackupContributorTests.kt:636`, `GtdBackupContributorTests.kt:823`,
`CalendarBackupContributorTests.kt:994`, `MailBackupContributorTests.kt:736`, `JournalBackupContributorTests.kt:573`,
`ContactsBackupContributorTests.kt:425`, `NotesBackupContributorTests.kt:507`, and `SpaFallbackTests.kt:46`. Their size
per run is likely much smaller than the round-trip dir, but they still accumulate in `/tmp`. The parent may want to
file a follow-up task (`tasks.py new`) rather than widen this one.

## Review conclusion

No material issues found.
