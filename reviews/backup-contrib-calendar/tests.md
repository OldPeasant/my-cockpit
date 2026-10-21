# Test Report

Date: 2026-10-21

Task: backup-contrib-calendar - Backup contributor: calendar

Scope: `git diff 1ec99d7dc5d5915e84b1d8797c542c32ca7a80a2..6bd32b4ce07bd9c00de05bd4ddae603b11c8daab -- server` (task commits eb8ad547, ae8178e3, 6bd32b4c on branch main), plus this run's uncommitted test additions in `CalendarBackupContributorTests.kt`. Production file in scope: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/backup/CalendarBackupContributor.kt`. Test files in scope: `CalendarBackupContributorTests.kt` and `CalendarBackupFixture.kt`.

Requirements:
- tasks/backup-contrib-calendar.md (criteria 1 and 2, three recorded assumptions, extension note)
- SPEC.md §10.2 (backup format, secrets, external calendar data as id refs)
- reviews/backup-contrib-calendar/review.md (CR-1..CR-4; CR-2/3/4 fixed in 6bd32b4c)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md project notes

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/backup/CalendarBackupContributorTests.kt` (44 lines added; no existing test changed)

## Tests added or updated

Count: 3 added (the suite now has 21 tests, up from 18)

- `a backup without calendar data restores no calendar rows and has no restore warnings`: importing a backup from an install without calendar sources is a no-op. This covers the `?: return` / `.orEmpty()` branches at `CalendarBackupContributor.kt:127` and `:177`.
- `an install with only external sources has calendar data but no ICS mirror`: the mirror holds LOCAL calendars only, so no `readable/calendar/` file is written when there are only external sources.
- `calendars with the same name are numbered by id, so every export names the files the same way`: when two LOCAL calendars share a name, the tie-break is by id (`:118`), not by database row order.

The existing 18 tests already covered every point the parent listed (see the table below), so no further tests were needed.

## Requirement coverage

All rows were run by `cd server && ./gradlew ktlintCheck test --tests '*BackupRoundTripTest' --tests '*CalendarBackupContributorTests' jacocoTestReport --rerun-tasks` (2026-10-21T17:43:52Z to 17:44:50Z, BUILD SUCCESSFUL; CalendarBackupContributorTests 21 tests, 0 failures, 0 skipped; BackupRoundTripTest 1 test, 0 failures).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| C1: `data/calendar_sources.json` holds no secrets; credentials only in backup secrets and restored with the passphrase (CR-1) | `sources json holds every source with its config but no credentials, ...`; `with a passphrase the credentials are only in the encrypted secrets, neither in plain text nor as stored`; `import restores credentials with the passphrase and leaves them empty without, warning about external sources` | above | Pass |
| C1: `calendars.json` holds every calendar | `sources json holds every source ...`; `import restores sources, calendars, local events and EXDATEs ...` | above | Pass |
| C1: `events.jsonl` holds LOCAL events fully (Trash, EXDATEs, overrides, attendees, reminders, masters before overrides) | `local events are complete, with Trash, EXDATEs, overrides, attendees and reminders, masters before overrides`; `import restores sources, calendars, local events and EXDATEs with their ids, times, versions and Trash state` | above | Pass |
| C1: external events only as id refs | `events of external sources are only id refs, without title or other content`; `import restores external events as placeholders ...` | above | Pass |
| C1: `readable/calendar/<name>.ics` is importable, parsed back strictly with ical4j (CR-1) | `each local calendar is an ICS file that ical4j parses strictly, ...` (relaxed parsing asserted off, `validate()` has no errors, UID/RRULE/EXDATE/RECURRENCE-ID/VTIMEZONE checked, Trash absent); `ICS keeps UTC and zero-length events and escaped multi-line text readable for ical4j`; `file names replace reserved characters ...`; `calendars with the same name are numbered by id ...` (new); `an install with only external sources has calendar data but no ICS mirror` (new) | above | Pass |
| No empty ICS files (CR-4) | `every mirrored ICS file passes ical4j validation, also for local calendars without events or with only Trash` | above | Pass |
| Inert placeholders (CR-2) | `import restores external events as placeholders ...` (CANCELLED, placeholder etag); `until a sync fills them in, placeholders are not shown and edits or deletes never overwrite the provider's events`; `the first sync after a restore fills in placeholders with the same ids and removes the ones the provider no longer has` | above | Pass |
| Accurate credential warnings (CR-3) | `import restores credentials with the passphrase and leaves them empty without, ...`; `restore warnings and the log count only CalDAV sources as missing their password, OAuth and feed sources need none`; `a backup with only local sources has no restore warnings`; `a backup without calendar data restores no calendar rows and has no restore warnings` (new) | above | Pass |
| Search index after restore | `import indexes local events for search, but not placeholders, events in the Trash or cancelled occurrences` | above | Pass |
| C2: round trip with `CalendarBackupFixture` (local recurring events with overrides, cancelled occurrence, EXDATEs, Trash, external CalDAV source) | `BackupRoundTripTest.every contributor's data survives export, wipe and import unchanged` | above | Pass |

Note: `BackupRoundTripTest` exports without a passphrase and compares only `data/` and `files/`. The ICS mirror and the secrets path are therefore covered by `CalendarBackupContributorTests`, not by the round trip.

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests '*BackupRoundTripTest' --tests '*CalendarBackupContributorTests' jacocoTestReport --rerun-tasks`

Result:

Pass (ktlintCheck clean; 21/21 and 1/1 tests passed). An earlier run on the unchanged committed tests (17:41:44Z to 17:43:05Z) also passed with 18/18 and 1/1.

## Full test execution

Command:

`sh scripts/check.sh` (lint and unit tests of server, web and android)

Result:

Pass ("== all checks passed", 2026-10-21T17:45:05Z to 17:52:28Z). The web build printed its existing initial-bundle budget warning, which is unrelated to this task.

## .taskcheck

Result:

Pass (`.taskcheck` = `sh scripts/check.sh`, same run as above, about 7.4 min, under the 15 min limit).

## Bugs found

None. No production defects were found.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equals whole-file line coverage. `CalendarBackupContributor.kt` is a new file in this task (every line is added in eb8ad547/6bd32b4c), so all of its executable lines are changed lines: 335/336.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: backup-contrib-calendar; base 1ec99d7dc5d5915e84b1d8797c542c32ca7a80a2; HEAD 6bd32b4ce07bd9c00de05bd4ddae603b11c8daab; commits eb8ad547, ae8178e3, 6bd32b4c supplied by the parent and matched with `git log 1ec99d7d..HEAD` and `git diff --name-only 1ec99d7d..HEAD -- server/src/main` (one production file).
- Source/test snapshot identifier (including uncommitted inputs): sha256 `CalendarBackupContributor.kt` 60aa942eed640ce3837f58c2c400a58838c568c6880d8f1fc3e06f5335c3f6a7 (committed); `CalendarBackupContributorTests.kt` fcd2105a0c336e28178c28f1d88a59e01cc8c4706d112d7fad7f1f73a1ddd464 (uncommitted, with the 3 new tests); `CalendarBackupFixture.kt` 8536a3c3071a262f5a656d770064cc64e7898b1e3a63f0183771c926118af9fb (committed). Hashes were identical before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/calendar/backup/CalendarBackupContributor.kt`. No renames or deletions. The JaCoCo package `ch/sonensei/cockpit/calendar/backup` also lists a `BackupContributor.kt` source entry. It is excluded because it holds inlined default methods of `backup/domain/BackupContributor.kt`, which this task did not change.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (default toolVersion of Gradle 9.8.1, configured in `server/build.gradle.kts`); `./gradlew ktlintCheck test --tests '*BackupRoundTripTest' --tests '*CalendarBackupContributorTests' jacocoTestReport --rerun-tasks`; working directory `server/`; tests limited to those two classes.
- Run start/completion time and exit status: 2026-10-21T17:43:52Z to 17:44:50Z, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 c22cff8d72e3c0e060ba9f8d900263de1c86ba93c9e679a23cc8928f6bc79897 (Git-ignored).
- Evidence outputs are fresh and inputs stayed unchanged: the old XML report and `build/jacoco/test.exec` were deleted before the run, and `--rerun-tasks` forced a fresh run. Only the two selected test result XMLs exist, and the source hashes did not change.
- Final run after latest test additions and production fixes: yes. It ran on HEAD 6bd32b4c, which includes the CR-2/3/4 fixes, plus the new tests.

Values below use covered/total (percentage). JaCoCo counts bytecode instructions, not statements, so the Statements column shows instructions.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/backup/CalendarBackupContributor.kt` | 2239/2262 (98.98%) | 102/102 (100.00%) | 95/101 (94.06%) | 335/336 (99.70%) |

Aggregate: identical to the single file above. No scoped files are missing or unmeasured.

Before the new tests (same command on the committed tests, report sha256 c1cdae952da522cff0378dc88967cbe6e05a637c195a783d638b9da7a131e76b), coverage was instructions 2231/2262, branches 100/102, methods 94/101, lines 335/336.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarBackupContributor.kt:336-342` (`EventRow` property getters `getId`, `getExternalId`, `getParentEventId`, `getRecurrenceIdAt`, `getRecurrenceIdDate`, `getContent`; line 342 is the only line with no hit) | None: Kotlin-generated getters of a private inner class. The class reads the backing fields directly, and the values are tested through `toBackup`. | Not material; no test added |
| `CalendarBackupContributor.kt:315` `eventRows` (5 instructions) | Closing the stream when the callback throws (`use` exception path) | Not material; would need fault injection into JDBC. No test added |
| `CalendarBackupContributor.kt:127`, `:177` (null branch: backup without `calendar_sources`) | Restore of a backup from an install without calendars | Covered by the new test `a backup without calendar data restores no calendar rows and has no restore warnings` |
| `CalendarBackupContributor.kt:118` (tie-break lambda `{ it.id }`) | Deterministic file names for calendars with the same name | Covered by the new test `calendars with the same name are numbered by id, ...` |

## Unable to test

- Importing the mirror into third-party calendar apps was not done. Importability is shown by strict ical4j parsing plus `validate()`, as criterion 1 specifies. No follow-up needed.
- Placeholder behavior is tested only against `FakeCalendarAdapter`, not a real CalDAV/Google/Microsoft provider (none is reachable in tests). The fake honours `If-Match`, which is the property the inert-placeholder design relies on. A real provider's 412 behavior is assumed.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Three small tests were added for gaps found by coverage, and all validation passed: focused tests, ktlintCheck and `.taskcheck`.
