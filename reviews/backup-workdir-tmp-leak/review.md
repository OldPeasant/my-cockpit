# Code Review

Date: 2026-10-26

Task: backup-workdir-tmp-leak - BackupJobService/RestoreService leak their lazy temp work dirs

Scope: `git diff 60cf97e18663aaa3496bdb8c4b3bfeeb1caf9557..3fd960bf8` (task commits 59fa3e4d2 production fix, 3fd960bf8 tests + test report); committed changes only, working tree clean

Requirements:
- `tasks/backup-workdir-tmp-leak.md` acceptance criteria
- `AGENTS.md` project notes and conventions
- `docs/QUALITY.md` (report contract)
- Parent worker instructions (scope, base commit)

Git range:
- Branch: main
- HEAD: 3fd960bf867a82404c515c30ada8b242f3b321d7
- Base/start: 60cf97e18663aaa3496bdb8c4b3bfeeb1caf9557
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review notes

- AC1: Both services split the `by lazy` delegate into a `Lazy<Path>` field plus a delegated property, and `destroy()`
  (both already implement `DisposableBean`, so Spring calls it on context close) deletes the dir with
  `deleteRecursively()` only when `isInitialized()` is true. Destroying a service that was never used therefore creates
  no temp dir. `runCatching` keeps shutdown from failing, as with the existing zip cleanup. `RestoreService.destroy()`
  stays `@Synchronized` and still calls `discardPending()` first. The normal backup/restore paths are unchanged:
  only `destroy()` touches the new code, and the files still go into the same lazily created dir.
- Deletion is limited to the instance's own dir. Tests cover a sibling `cockpit-backups*`/`cockpit-restores*` dir
  surviving destroy.
- Tests (`BackupWorkDirCleanupTests`, 10 cases): they check that an unused service creates no dir, that destroy
  deletes the dir after a completed, failed or invalid backup/upload, that one service keeps one shared dir, that
  destroying twice is safe, and that other dirs survive. The before/after diff of the temp dir is deterministic
  because the server tests run in one fork and no JUnit parallel config exists (`build.gradle.kts` sets no
  `maxParallelForks`, and there is no `junit-platform.properties`). The test style (kotest matchers, no Spring) matches
  the existing backup tests. The test data is fictitious.
- AC2/AC3 evidence comes from the parent worker (temp dir count 0 with `java.io.tmpdir` redirected) and the
  test-writer (`./gradlew check` and `sh scripts/check.sh` green). This review did not re-run them.
- An observation that is not a finding: `BackupJobService.destroy()` calls `executor.shutdownNow()` without waiting
  for it to finish. If a backup job is still running at shutdown, it can in theory recreate a file or first initialize
  the lazy dir after the cleanup. This window already existed for the zip cleanup, it only matters at JVM shutdown
  during a running backup, and the task does not require waiting for jobs. No action needed.

## Review conclusion

No material issues found.
