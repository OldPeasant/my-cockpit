# Code Review

Date: 2026-10-26

Task: test-tmp-dir-leaks - Other server tests leak their companion temp data dirs into /tmp

Scope: commit 6b8afa8ae ("test: delete companion-object temp data dirs after each test class", already on main, an
ancestor of the task HEAD) and how it fits the current `server/src/test/kotlin`. The task branch itself
(6464c0c67..e7afc6c8c) changes only `tasks/backup-workdir-tmp-leak.md` and adds no code.

Requirements:
- `tasks/test-tmp-dir-leaks.md` acceptance criteria and notes
- `AGENTS.md` (project notes, conventions), `docs/QUALITY.md`
- the reference pattern in `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupRoundTripTest.kt:149-158`
- the parent agent's scope instructions

Git range:
- Branch: main
- HEAD: e7afc6c8caa9f4f597058ba93fe7ba8fa09eea79
- Base/start: 6464c0c67e9bf161880577806be010ad284ad907 (task base); reviewed code commit 6b8afa8ae48a4b128e786ba7d35e181f146d51df
- Commits reviewed: 1 code commit (6b8afa8ae), plus the 2 task-branch commits (task files only)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Commands run and results

- `git show --stat 6b8afa8ae` -> 12 test files, +96 lines, test code only: `BackupSecretsTest`, `RestoreCliTests`,
  `RestoreWebTests`, `SpaFallbackTests` and the 8 `*BackupContributorTests` (files, gtd, calendar, mail, journal,
  contacts, notes, bookmarks), each gets an `org.junit.jupiter.api.AfterAll` import and a `@JvmStatic @AfterAll`
  companion function calling `deleteRecursively()` on its static dir.
- `git diff --stat 6464c0c67..HEAD` -> only `tasks/backup-workdir-tmp-leak.md`; `git merge-base --is-ancestor
  6b8afa8ae HEAD` -> true; `git diff 6b8afa8ae HEAD` on the touched backup/spa test files -> no later change.
- `grep -rlc 'createTempDirectory("cockpit-' server/src/test/kotlin` -> 13 classes; `grep -rl 'fun deleteDataDir\|fun
  deleteWebRoot'` -> the same 13 classes (the 12 of the commit plus `BackupRoundTripTest`). Every static temp dir has
  its cleanup.
- `grep` for other static temp places (`java.io.tmpdir`, `deleteOnExit`, `createTempDir`, `createTempFile`) in server
  tests -> only `Files.createTempFile(work, ...)` where `work` is a per-test `@TempDir` (JUnit deletes it).
  `server/src/test/resources/config/application.properties:9` sets `cockpit.data-dir=build/test-data` for all other
  Spring tests, i.e. inside the build dir, not /tmp.
- Lint and test run: not re-run by the reviewer (Gradle invocation was not permitted in this review session). The
  parent's measurement (`./gradlew check --rerun-tasks` with `java.io.tmpdir` redirected to `.tmp/jtmp` -> exit 0, no
  companion dirs left) covers ktlint and the test suite on this code.

## Analysis

- **Cleanup also when tests fail:** JUnit Jupiter runs class-level `@AfterAll` callbacks after all tests of the class
  regardless of test failures, and also when the Spring context fails to load (that failure surfaces per test
  instance, the class-level callback still runs). `@JvmStatic` on a companion function makes it a static method of
  the test class, as JUnit requires in the default `PER_CLASS`-less lifecycle; none of the 13 classes uses
  `@TestInstance(PER_CLASS)` or `@Nested`. Identical to the `BackupRoundTripTest` model.
- **Static dir created only when the class executes:** the dirs are created in the outer class's static initializer;
  JUnit discovery loads classes without initializing them, so a filtered-out class creates no dir that its
  `@AfterAll` would then miss.
- **Spring context caching:** after `@AfterAll`, each class's cached context still holds `cockpit.data-dir` (or
  `cockpit.spa.location`) pointing at the deleted dir. No other class can reuse it: the context cache key includes the
  class's own `@DynamicPropertySource` method (each class declares its own, with its own database name), and none of
  these classes is open, abstract or nested, so no subclass inherits the key. Nothing in the cached context writes to
  `cockpit.data-dir` on its own (no `@Scheduled` writer; `RestoreService.destroy()` only discards pending uploads in its
  own `workDir`), so the deleted dir is not recreated when the context closes at JVM exit.
- **Behavior unchanged:** the commit adds only the `@AfterAll` functions and imports; no assertion, fixture or
  property changed. Data written during the tests is unaffected because the delete runs after the last test.
- **Remaining leaks:** the empty `cockpit-backups*` / `cockpit-restores*` dirs the parent measured come from
  production `BackupJobService`/`RestoreService` work dirs (one per Spring context), not from a static place in test
  code, so they are outside this task's `touches: [server/src/test/kotlin]` and test-only scope. The parent created
  `tasks/backup-workdir-tmp-leak.md` for them and recorded an assumption; criterion 2 ("affected temp dirs") is read as
  the companion-object dirs this task names. This is a scoping decision for humans to confirm, not a defect in the
  reviewed code.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
