# Test Report

Date: 2026-10-20

Task: cal-model - Calendar data model

Scope: second pass after fixes. Task commits `a0a3bc2..5d62ee6` on `main` (merge base `a0a3bc24969a6fc628c33346ffe123c258e4490f` = `git merge-base HEAD origin/main`): a397226 (implementation), a93d577 (edge-case tests, reports), 676a3df (fixes for TW-1, CR-1..CR-3), 5d62ee6 (fixer report). Plus one uncommitted test change from this pass (see below). Production scope: `git diff --name-only a0a3bc2..HEAD -- server/src/main`.

Requirements:
- `tasks/cal-model.md` acceptance criteria and recorded assumptions
- `reviews/cal-model/review.md` (CR-1..CR-4), `reviews/cal-model/fixes.md`, previous `reviews/cal-model/tests.md` (TW-1)
- SPEC.md §6.1, §6.2, §4.3/§4.4
- docs/ARCHITECTURE.md "Coverage"

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/model/CalendarModelEdgeCaseTests.kt` (one test added in this pass, uncommitted)

The tests from the first pass and the fixer (`CalendarModelEdgeCaseTests.kt`, `CalendarItemsAndSearchEdgeCaseTests.kt`, plus the renamed lookup in `CalendarModelTests.kt`) are committed and unchanged here.

## Tests added or updated

Count: 1 (this pass): `range candidates keep every override of a live series, cancelled or outside the range`.

Why: the CR-1 fix restructured `findCandidatesInRange` (overrides now need a live master; date conditions limited to non-overrides). The existing tests covered the exclusion side only. The new test pins the documented inclusion side: a CANCELLED override of a live series (expansion needs it to remove the occurrence) and an override moved outside the range are still returned, together with the master.

Fixed behaviors and the tests that cover them (all checked, all passing):

| Fix | Test |
|---|---|
| TW-1: cancelled override does not resolve | `CalendarItemsAndSearchEdgeCaseTests.a cancelled override does not resolve` (resolve null, exists false); `a cancelled single event still resolves and is indexed` guards the non-override case |
| CR-1: range excludes overrides of a trashed master | `CalendarModelEdgeCaseTests.range candidates exclude series and zero-length events at or after the end, and include long spans` (excluded while master is trashed, back after `restore()`); new test above for the inclusion side |
| CR-2: override must be in the master's calendar | `CalendarModelEdgeCaseTests.an override must be in the calendar of its series` (IllegalArgumentException, event left unchanged) |
| CR-3: iCalendar overrides share the master's external_id | `CalendarModelEdgeCaseTests.iCalendar overrides may share their master's external id, and the lookup by external id returns the master` (two overrides with the master's UID save; lookup returns the master; a second master with that UID is rejected); `event external ids are unique per calendar only` |

## Requirement coverage

Focused command (F): `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.model.*'` (Testcontainers PostgreSQL) -> BUILD SUCCESSFUL, 46 tests (CalendarModelTests 11, CalendarModelEdgeCaseTests 17, CalendarItemsAndSearchTests 7, CalendarItemsAndSearchEdgeCaseTests 11), 0 failed, 0 skipped.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| calendar_source types LOCAL/GOOGLE/MICROSOFT/CALDAV/ICS_FEED, config object, credentials | `every source type of SPEC 6_1 is accepted by the database`, `a source keeps its config and encrypts its credentials`, `the database rejects an unknown source type and a non-object config` | F | Pass |
| calendar: color, visible, readOnly, default (single default), external id per source | `a calendar keeps its flags and only one calendar is the default`, `calendars are listed per source and external ids are unique per source only`, `a new calendar is visible, writable and not the default` | F | Pass |
| calendar_event: status/transparency/sync_state/attendees, reminders, conference URL, external_id/etag | existing round-trip tests in `CalendarModelTests`, `the database rejects unknown status, transparency and sync state values and non-array attendees` | F | Pass |
| calendar_event: start/end with tz id, or all-day dates | `the database rejects mixed timed and all-day columns`, `switching an event between timed and all-day clears the other form, and setAllDay defaults to one day`, round-trip tests | F | Pass |
| RRULE, EXDATEs | `exdates hold exactly one form and are unique per event`, `exdates can be added and removed on a saved series` | F | Pass |
| Override parent + recurrence id (incl. CR-2 same calendar) | `the database rejects inconsistent override columns`, `an all-day override stores its recurrence id as a date`, `an event overrides at most one occurrence and an override cannot be overridden`, `overrideOf clears an rrule set before`, `an override must be in the calendar of its series` | F | Pass |
| external_id unique per calendar among masters/singles; overrides share it (CR-3) | `event external ids are unique per calendar only`, `iCalendar overrides may share their master's external id, and the lookup by external id returns the master` | F | Pass |
| Range candidate query (CR-1) | `range candidates exclude series and zero-length events at or after the end, and include long spans`, `range candidates keep every override of a live series, cancelled or outside the range` | F | Pass |
| Resolver EVENT (subtitle, zone/DST, repeats, deep link, deleted, override of deleted series, cancelled override TW-1) | `CalendarItemsAndSearchTests` resolver tests, `the subtitle uses the event's own zone and its daylight saving offset`, `the subtitle shows the local date when the zone moves the start past midnight`, `a timed series is marked as repeating and an override of an active series resolves without it`, `a cancelled override does not resolve`, `a cancelled single event still resolves and is indexed`, `a soft-deleted event resolves again after it is restored` | F | Pass |
| Search indexing hooks | `indexed events are found by title, description and location`, `deleted events, cancelled overrides and overrides of a deleted series are not indexed, restore reindexes`, `unindexing a calendar removes the documents of all its events`, and the 5 indexer edge-case tests | F | Pass |
| Criterion 3: the focused test command passes | all of the above | F | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.model.*'`

Result:

Pass: BUILD SUCCESSFUL, 46 tests, 0 failed, 0 skipped; ktlint main and test source sets clean. (The first attempt failed only on a ktlint style error in the new test, `Expected newline before '.'`; the test was reformatted and the command rerun.)

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport`

Result:

Pass: BUILD SUCCESSFUL in 4m 10s; 1107 tests in `build/test-results/test/TEST-*.xml`, 0 failures, 0 errors, 0 skipped.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` exit 0, "== all checks passed" (server, web, android), run on HEAD 5d62ee6 plus the uncommitted test change.

## Bugs found

None in this pass. TW-1 from the first pass is fixed in 676a3df and its test now passes.

## Coverage

Status: verified (fresh full-suite run, exit 0, after the latest test addition and on the fixed production code).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately; every production file in scope was added by this task, so whole-file coverage equals changed-file coverage.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: cal-model; base a0a3bc24969a6fc628c33346ffe123c258e4490f (`git merge-base HEAD origin/main`); HEAD 5d62ee6d84dc72e303af756d7699268944d1aeb9; task commits a397226, a93d577, 676a3df, 5d62ee6 (parent-supplied; matches `git diff --stat a0a3bc2..HEAD`, which only touches the calendar model, its migration, tests and reviews/cal-model).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 5d62ee6 with clean production tree; uncommitted `CalendarModelEdgeCaseTests.kt` sha256 3fd44a7b6b1d723f27de2f76186ed17cf16dc5c285f0355f90e38f5229d8baed. Other inputs (sha256): `CalendarItemsAndSearchEdgeCaseTests.kt` bd0575977df1d3f27c42182460ae3bdd70855c4c6d9e60339b685f7e504526a3, `CalendarItemsAndSearchTests.kt` a75a3f7138fa370561c4b9a7818102f92d494e032533c5de231fc9a67644d2b6, `CalendarModelTests.kt` ced327ab7ff46b93780944a0bee5d3da8cd72d1cd98a42c6a7ce209e02933fcb, `CalendarEvent.kt` 3d8b10960413ef6d3063ce6535a5d5acf592818aa00ea19b53803322286c854f, `EventItemResolver.kt` 842f408b754767e1aafbe22a87cc000dcb74a9941b7f9032a9d3a3dd7c8ac314, `CalendarRepositories.kt` 821c182a8a3e0503a98566b502e1158d565861c084d4e087cd2bb6331a0db16f, migration 27615aa51d2d5936fdbdb43269d2004a773a4b439a2fb8f1525cd5f11ae8a69f. Hashes checked identical before and after the coverage run and after `.taskcheck`.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/model/{Calendar,CalendarEnums,CalendarEvent,CalendarEventIndexer,CalendarRepositories,CalendarSource,EventItemResolver}.kt`. Excluded: `server/src/main/resources/db/migration/calendar/V202610201351__calendar_model.sql` (SQL, not JVM bytecode; its constraints are exercised by the DB rejection tests). `CalendarRepositories.kt` holds only Spring Data interfaces and has no JaCoCo counters (N/A, measured but empty). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (default tool version, no `toolVersion` set); `./gradlew test jacocoTestReport`; working dir `server/`; full server test suite.
- Run start/completion time and exit status: started 16:32:07 +02:00, `test.exec` written 16:36:14, report 16:36:19; exit 0 (BUILD SUCCESSFUL).
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` sha256 a3ae41857331f424661ae98fbeee8abdf2acd5a353420920930533da868155db; `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 a5bee2e1bea719051df1a5574154d4db4620c6ccc660fe204087a05110ee1bfd.
- Evidence outputs are fresh and inputs stayed unchanged: yes; both artifacts were rewritten by this run (previous report was from 16:08:28 on the pre-fix revision), and input hashes were unchanged.
- Final run after latest test additions and production fixes: yes.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `calendar/model/CalendarEvent.kt` | 461/465 (99.1%) | 33/34 (97.1%) | 62/63 (98.4%) | 82/82 (100%) |
| `calendar/model/CalendarEventIndexer.kt` | 119/119 (100%) | 14/14 (100%) | 4/4 (100%) | 16/16 (100%) |
| `calendar/model/EventItemResolver.kt` | 127/131 (96.9%) | 16/16 (100%) | 5/7 (71.4%) | 23/23 (100%) |
| `calendar/model/Calendar.kt` | 69/82 (84.1%) | N/A | 14/18 (77.8%) | 14/15 (93.3%) |
| `calendar/model/CalendarSource.kt` | 47/54 (87.0%) | N/A | 8/10 (80.0%) | 10/11 (90.9%) |
| `calendar/model/CalendarEnums.kt` | 149/149 (100%) | N/A | 5/5 (100%) | 19/19 (100%) |
| `calendar/model/CalendarRepositories.kt` | N/A | N/A | N/A | N/A |
| **Aggregate (summed counters over the 6 files with counters; repositories N/A; SQL migration excluded)** | 972/1000 (97.2%) | 63/64 (98.4%) | 98/107 (91.6%) | 164/166 (98.8%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarEvent.kt:41` (4 instr.), `Calendar.kt:20` (7), `CalendarSource.kt:26` (7) | Kotlin synthetic default-argument constructor variants | Non-material, generated code |
| `CalendarEvent.kt:171` (1 branch: `master.isOverride` true) | Overriding an override | Unreachable through the entity API (an override's rrule is cleared, so `isRecurring` short-circuits); behavior asserted by `an event overrides at most one occurrence and an override cannot be overridden` |
| `Calendar.kt:36,39` (getters of externalId / externalEtag) | Trivial accessors | Non-material; columns covered by repository lookup and round-trip tests |
| `EventItemResolver.kt:48-49` (2 synthetic companion accessors) | Generated code | Non-material |

All lines changed by the fix commit 676a3df (resolver cancelled check, `overrideOf` calendar check, repository query/lookup) are executed; both outcomes of the new resolver condition and of the new `require` are covered.

## Unable to test

- Nothing in the task scope. The SQL migration has no JaCoCo coverage; its constraints (including the changed partial unique index `calendar_event_external_id`) are verified by the DB tests listed above.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. All fixes (TW-1, CR-1, CR-2, CR-3) are covered by passing tests; one test was added for the inclusion side of the CR-1 range query. The new test is uncommitted and must be committed by the parent worker together with this report.
