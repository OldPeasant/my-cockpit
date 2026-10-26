# Test Report

Date: 2026-10-26

Task: test-tmp-dir-leaks - Other server tests leak their companion temp data dirs into /tmp

Scope: The task branch adds no code: task base `6464c0c67e9bf161880577806be010ad284ad907`, measured HEAD
`e7afc6c8caa9f4f597058ba93fe7ba8fa09eea79` (diff base..HEAD = `tasks/backup-workdir-tmp-leak.md` only). The change the
task asks for is already on main in `6b8afa8ae48a4b128e786ba7d35e181f146d51df` ("test: delete companion-object temp
data dirs after each test class", 12 test files, +96 lines, test-only). Reviewed: `git show 6b8afa8ae` plus the
current state of `server/src/test/kotlin` (all static temp-dir creations, not only the 12 files). Task ownership of
6b8afa8ae was established by the parent worker (recorded as an assumption on the task) and by matching its file
list against the classes named in the task Goal.

Requirements:
- `tasks/test-tmp-dir-leaks.md` acceptance criteria
- `AGENTS.md` (scratch files in `.tmp/`, `.taskcheck`), `docs/QUALITY.md` (report contract)

## Test files changed

- None. No test files were added or modified by the test-writer.

## Tests added or updated

Count: 0

Why no test was added: the change is itself test infrastructure (an `@AfterAll` cleanup in test companion
objects). A unit test of "the test cleans up after itself" would have to assert on JUnit lifecycle behavior, not
product behavior. The criterion is verified instead by running the real suite with a redirected `java.io.tmpdir`
and counting what is left behind (below). That `@AfterAll` also runs when a test fails is guaranteed by JUnit 5
(class-level after-all callbacks run regardless of test outcome, also when instance creation / Spring context
loading fails), so no forced-failure experiment was run.

## Static temp-dir audit

Searched `server/src/test/kotlin` for `createTempDirectory`, `createTempFile`, `createTempDir(`,
`kotlin.io.path.createTemp*`, `File.createTempFile` and `java.io.tmpdir`. `server/src/` has only `main` and
`test` source sets.

Every static (companion-object) temp dir has a `@JvmStatic @AfterAll` function calling `deleteRecursively()` in the
same companion object (13 of 13; `@JvmStatic` present on all 13 cleanup functions):

| Test class | Static temp dir | Cleanup |
|---|---|---|
| `backup/BackupRoundTripTest` | `cockpit-round-trip-data` (l.151) | `@AfterAll` l.155 (pre-existing, model) |
| `backup/BackupSecretsTest` | `cockpit-secrets-data` (l.530) | `@AfterAll` l.534 |
| `backup/RestoreCliTests` | `cockpit-restore-cli-data` (l.395) | `@AfterAll` l.399 |
| `backup/RestoreWebTests` | `cockpit-restore-data` (l.656) | `@AfterAll` l.660 |
| `core/spa/SpaFallbackTests` | `cockpit-spa` web root (l.47) | `@AfterAll` l.60 (`deleteWebRoot`) |
| `files/backup/FilesBackupContributorTests` | `cockpit-files-backup-data` (l.637) | `@AfterAll` l.641 |
| `gtd/backup/GtdBackupContributorTests` | `cockpit-gtd-backup-data` (l.824) | `@AfterAll` l.828 |
| `calendar/backup/CalendarBackupContributorTests` | `cockpit-calendar-backup-data` (l.995) | `@AfterAll` l.999 |
| `mail/backup/MailBackupContributorTests` | `cockpit-mail-backup-data` (l.876) | `@AfterAll` l.880 |
| `journal/backup/JournalBackupContributorTests` | `cockpit-journal-backup-data` (l.574) | `@AfterAll` l.578 |
| `contacts/backup/ContactsBackupContributorTests` | `cockpit-contacts-backup-data` (l.426) | `@AfterAll` l.430 |
| `notes/backup/NotesBackupContributorTests` | `cockpit-notes-backup-data` (l.508) | `@AfterAll` l.512 |
| `bookmarks/backup/BookmarksBackupContributorTests` | `cockpit-bookmarks-backup-data` (l.344) | `@AfterAll` l.348 |

All other temp files/dirs in tests use JUnit `@TempDir` (cleaned by JUnit) or `Files.createTempFile(work, ...)`
inside such a `@TempDir`. No top-level `val` or `object` creates a temp dir. Missing cleanups found: none.

Minor note (not a finding): in `SpaFallbackTests` the companion initializer writes files after
`createTempDirectory`; if that write threw, class initialization would fail and the dir could stay. That is an
environment failure (disk full), not a test failure, and outside the criterion.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: every test class with a static temp dir deletes it after the class, also on failure; behavior unchanged | the 13 classes above (static audit) + full server suite | `grep -rnE "createTempDirectory\(\"\|@AfterAll\|deleteRecursively\|java\.io\.tmpdir\|File\.createTempFile\|createTempDir\(\|kotlin\.io\.path\.createTemp" server/src/test/kotlin`; `sh .tmp/tw-check-tmp.sh` | Pass (13/13 have `@JvmStatic @AfterAll` + `deleteRecursively()`; suite passes unchanged) |
| AC2: `./gradlew check` passes; affected temp dirs in tmp same before/after | full server `check` with `java.io.tmpdir` redirected to a fresh `.tmp/tw-jtmp-20261026091447` | `sh .tmp/tw-check-tmp.sh` (runs `cd server && JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$PWD/.tmp/tw-jtmp-20261026091447 ./gradlew check --rerun-tasks --no-daemon -q`) | Pass: exit 0; before 0 entries, after 0 companion dirs (`cockpit-*-data`, `cockpit-spa`). 16 empty dirs remain (14 `cockpit-backups*`, 2 `cockpit-restores*`) from production code, see "Unable to test" |
| AC3: `sh scripts/check.sh` passes | `.taskcheck` | `sh scripts/check.sh` | Pass (exit 0, "all checks passed", 09:21:31-09:22:35) |

## Focused test execution

Command:

`sh .tmp/tw-check-tmp.sh` (the affected classes are Spring/DB tests spread over all server modules, so the focused
run is the server `check` itself with a redirected tmpdir)

Result:

Pass (exit 0, 2026-10-26T09:14:47+01:00 to 09:21:23+01:00, log `.tmp/tw-check.log`)

## Full test execution

Command:

`cd server && ./gradlew check --rerun-tasks --no-daemon` (inside the script above); `sh scripts/check.sh`

Result:

Pass

## .taskcheck

Result:

Pass (`sh scripts/check.sh`, exit 0, 64 s)

## Bugs found

None in the task scope (test code). One related leak in production code was confirmed, already tracked by the
parent as task `backup-workdir-tmp-leak`, so it is not a finding of this task:
`BackupJobService.kt:49` and `RestoreService.kt:70` create `cockpit-backups` / `cockpit-restores` lazily with
`Files.createTempDirectory` per Spring context and never delete them (the run left 14 + 2 empty dirs).

## Coverage

Status: not applicable - the task scope contains no production files.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `test-tmp-dir-leaks`, base `6464c0c67e9bf161880577806be010ad284ad907`, HEAD `e7afc6c8caa9f4f597058ba93fe7ba8fa09eea79`; task-owned change `6b8afa8ae` (already on main) plus the task branch diff, both per parent worker and `git show --stat`.
- Source/test snapshot identifier (including uncommitted inputs): working tree clean before and after the run; `git ls-files -s server/src/test/kotlin | sha256sum` = `05c462205ee718fc1fa90be469649981af4627a343d63e617aec238a29239c08` before and after.
- Exact included files; renamed/deleted/excluded files and reasons: production files in scope: none. Excluded: the 12 test files of `6b8afa8ae` (test code, not production) and `tasks/backup-workdir-tmp-leak.md` (task metadata).
- Provider/version, command, working directory, and test selection: no coverage run; with an empty production scope a coverage report would carry no task information. The server suite run above is the behavioral evidence.
- Run start/completion time and exit status: N/A for coverage (suite run: 09:14:47-09:21:23, exit 0).
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: yes (fresh tmpdir per run; snapshot hash unchanged).
- Final run after latest test additions and production fixes: yes (no additions or fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files in scope) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none | test-only change; production coverage is unaffected | none needed |

## Unable to test

- The literal "/tmp" of AC2 could not be inspected (writes and listings outside the clone are denied). Measured
  instead with `java.io.tmpdir` redirected into `.tmp/` for all test JVMs, which is where the tests' temp dirs go.
- "Same count before and after" holds for the companion dirs this task covers; the 16 empty
  `cockpit-backups*` / `cockpit-restores*` dirs from production code still leak. Follow-up: `backup-workdir-tmp-leak`.
- Cleanup on a failing test was not demonstrated by forcing a failure (would require editing a test); it relies on
  JUnit 5 `@AfterAll` semantics.

## Summary

No additional tests were necessary for the reviewed scope. All 13 static temp dirs in server tests are deleted by
`@JvmStatic @AfterAll` + `deleteRecursively()`; server `check` and `.taskcheck` pass, and no companion data dir is
left after a full run. The remaining leak is in production code and tracked as `backup-workdir-tmp-leak`.
