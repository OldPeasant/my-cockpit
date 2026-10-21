# Test Report

Date: 2026-10-21

Task: cal-microsoft - Microsoft Graph calendar adapter

Scope: task cal-microsoft, base 56df48eaf8823eb85b5572575b56d75ea0cb0806, task commits 613e1115..9a8214e8 (HEAD 9a8214e81df2f706c6a908765d3d6f9c41a9e48f, 6 commits, all cal-microsoft), plus this run's uncommitted test changes. This pass covers the final code after the fixes for review findings CR-1 to CR-6 (c1e66634).

Requirements:
- tasks/cal-microsoft.md acceptance criteria 1-3 and recorded assumptions (sync window, relisting on removals, derived EXDATEs, diff-only updates, zones)
- SPEC.md §6.2 (Graph delta queries, remote is the source of truth, 412 is "changed remotely", THIS/FOLLOWING/ALL)
- reviews/cal-microsoft/review.md (CR-1 to CR-6) and reviews/cal-microsoft/fixes.md
- docs/ARCHITECTURE.md "Coverage", AGENTS.md conventions (fictitious test data, lint)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/GraphEventMappingTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapterTests.kt`

## Tests added or updated

Count: 12 added, none changed or removed (microsoft.* went from 148 to 160 tests).

GraphEventMappingTests (unit tests of `keepAttendeeTypes`, CR-4):
- `attendee types are matched by email ignoring case`: Graph has `Sam.Sample@Example.TEST` (optional) and `room.1@EXAMPLE.test` (resource), and the cockpit sends other casings. Both keep their Graph type.
- `attendees Graph has without type or address leave the sent attendees required`
- `a diff without attendees is left as it is`

MicrosoftCalendarAdapterTests (WireMock):
- CR-1 `a split keeps only the exceptions before it - the one the shortened pattern no longer has is not put back`: this has the shape of a FOLLOWING split. The series ends on 15 November, the 19 October exception is dropped by Graph and patched back (without If-Match), and the 7 December exception after the split is not patched. The master PATCH carries If-Match.
- CR-1 `an exception that cannot be put back after a pattern change does not fail the change of the series`: the restore PATCH gets 500, and `updateEvent` still returns the series (best effort).
- CR-1 `a series whose rule is removed is patched with no recurrence, without reading or restoring its exceptions`: covers the branch `event.rrule == null && master`, which was not covered before.
- CR-3 `a moved exception found only in the whole window is changed with If-Match`: the update path of the whole-window fallback. Until now only delete was tested.
- CR-3 `an occurrence in neither the weeks around it nor the window is gone - delete succeeds, change fails`: both lookups are made, and there is no DELETE or PATCH.
- CR-3 `an occurrence found in the weeks around it is not looked up in the whole window`: exactly one `/instances` request.
- CR-4 `a changed occurrence keeps the attendee types Graph has, matched by email ignoring case`: the `writeInstance` path, with different casing between Graph and the cockpit.
- CR-2 `a series of a shared calendar is read, and its occurrences deleted, through that calendar`: the delta, master, `/instances` and occurrence DELETE requests all go through `/me/calendars/AAMkCalShared/events/...`, with no request to the primary calendar or to `/me/events`.
- `a delta item without id is skipped`: covers the `requireId` failure in the pull.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Calendars | `listCalendars follows the next links ...`, `calendars beyond the page limit are refused` | focused command below | Pass |
| AC1 calendarView/delta incremental sync | `the first fetch lists the window ...`, `an incremental fetch follows the delta link ...`, `a delta that removed something ...`, `410 for the delta token ...`, cursor tests, `a delta item without id is skipped`, sync test `a first sync stores the calendar ...` | focused command below | Pass |
| AC1 writes (create/update/delete, If-Match, 412) | `createEvent ...`, `updateEvent ...`, `412 and 404 on update ...`, `a series whose rule is removed ...`, sync tests `an event created in the cockpit ...`, `412 from Graph fails the edit with 409 ...` | focused command below | Pass |
| AC1 occurrence edits (incl. CR-3 fallback, CR-4 attendee types) | `changing one occurrence patches its instance ...`, `a cancelled override and a deleted occurrence ...`, `an exception moved further ... (delete)`, `a moved exception found only in the whole window is changed with If-Match`, `an occurrence in neither ...`, `an occurrence found in the weeks around it ...`, `changed attendees keep the type ...`, `a changed occurrence keeps the attendee types ... ignoring case`, mapping `attendee types are matched by email ignoring case` (+2) | focused command below | Pass |
| AC1 'following' split (incl. CR-1 exception restore) | `ending a series before an occurrence ...`, `a changed pattern puts back the exceptions ...`, `a split keeps only the exceptions before it ...`, `an exception that cannot be put back ...`, sync test `changing this and following occurrences ...` | focused command below | Pass |
| CR-2 calendar-scoped event paths | `events are read, changed and deleted through their calendar`, `a series of a shared calendar is read, and its occurrences deleted, through that calendar` | focused command below | Pass |
| AC2 Tests against WireMock recordings of Graph | MicrosoftCalendarAdapterTests (55), MicrosoftCalendarSyncTests (4) with recordings in `src/test/resources/.../calendar/infra/microsoft` | focused command below | Pass |
| AC3 `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*'` passes, Postgres via Testcontainers | all 160 microsoft.* tests; MicrosoftCalendarSyncTests extends `PostgresTestBase` (Testcontainers) | `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*' jacocoTestReport` | Pass (160 tests, 0 failures, 0 errors, 0 skipped) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintFormat ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*'`

Result:

Pass. BUILD SUCCESSFUL. ktlintFormat ran on the new tests and ktlintCheck passes with no suppressions. Final run (`test --rerun`, 2026-10-21T11:34Z): GraphEventMappingTests 44, GraphRecurrenceTests 37, MicrosoftCalendarAdapterTests 55, MicrosoftCalendarSyncTests 4 (Postgres via Testcontainers), WindowsZonesTests 20. Total 160 tests, 0 failures, 0 errors, 0 skipped.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Pass. BUILD SUCCESSFUL in 8m 58s: ktlint plus all server unit tests, 240 test classes, 0 failures, 0 errors, 0 skipped. The web and Android parts were not run, because this task and these test changes touch only `server/`.

## .taskcheck

Result:

Not run by the test-writer. `sh scripts/check.sh` runs lint and unit tests of all parts, and the parent noted it takes more than 10 minutes under load. Its server part (`./gradlew check`) passed above. The parent runs `.taskcheck` before `done`.

## Bugs found

None. Every new test passed against the final code. The CR-1 to CR-4 fixes behave as described in fixes.md for the added edge cases: an exception after the split is not restored, a failed restore is best effort, the update path falls back to the whole window, both lookups miss leads to delete-succeeds and update-fails, matching ignores case on the occurrence path, and series and instances of shared calendars are read through their calendar.

## Coverage

Status: verified (fresh focused run on a stable snapshot)

Metric: whole-file coverage of task-changed production files. It includes hits from tests outside this run's additions, but only microsoft.* tests were executed for the report.
Changed-line coverage: not measured separately. All 5 files are new in this task (`A` in the diff), so whole-file coverage equals changed-line coverage for them.

Evidence:
- Task id cal-microsoft, immutable base 56df48eaf8823eb85b5572575b56d75ea0cb0806, measured HEAD 9a8214e81df2f706c6a908765d3d6f9c41a9e48f. Task ownership comes from the parent's commit range 613e1115..HEAD (`git log` shows only cal-microsoft commits) and the task `touches`.
- Source/test snapshot: HEAD 9a8214e8 plus the uncommitted test files `GraphEventMappingTests.kt` sha256 fe0b7800...38aa and `MicrosoftCalendarAdapterTests.kt` sha256 26799b0b...968f. Production sha256: MicrosoftCalendarAdapter.kt 917ab015...5008, GraphEventMapping.kt 4869f021...adc2, GraphRecurrence.kt ea1571c2...14e2, GraphClient.kt 926569e8...11b4, WindowsZones.kt 39327688...fb23. Hashes were taken before and after the runs and are identical.
- Included files (`git diff --name-status 56df48ea..HEAD -- server/src/main web/src android/app/src/main`, all added): `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/{GraphClient,GraphEventMapping,GraphRecurrence,MicrosoftCalendarAdapter,WindowsZones}.kt`. No renamed, deleted or excluded production files. All 5 are present in the JaCoCo report, so none are missing.
- Provider: JaCoCo via the Gradle `jacoco` plugin (project config, unchanged). Command: `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*' jacocoTestReport`, working directory `server/`, test selection microsoft.*.
- Run: started after 2026-10-21T11:34:09Z, completed by 11:34:42Z, exit status 0 (BUILD SUCCESSFUL). `test.exec` was written 11:34:36Z.
- Report: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 def87ed1a661a87e98b10bc5891cebaa223941a470a425db24e2674fda674c9f. The later `./gradlew check` overwrote `test.exec` with full-suite data but did not regenerate the XML.
- Freshness: `--rerun` forced the test task. The report was written after the snapshot hashes were taken, and the inputs did not change during or after the run.
- Final run after the latest test additions: yes. No production code changed in this pass.

Values below use covered/total (percentage). JaCoCo "Statements" are instructions.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt` | 2261/2384 (94.8%) | 177/213 (83.1%) | 39/40 (97.5%) | 304/310 (98.1%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/GraphEventMapping.kt` | 1110/1112 (99.8%) | 133/138 (96.4%) | 17/17 (100.0%) | 122/122 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/GraphRecurrence.kt` | 1758/1920 (91.6%) | 233/302 (77.2%) | 14/14 (100.0%) | 179/179 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/GraphClient.kt` | 537/543 (98.9%) | 34/38 (89.5%) | 14/14 (100.0%) | 75/75 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/WindowsZones.kt` | 912/912 (100.0%) | 14/14 (100.0%) | 3/3 (100.0%) | 151/151 (100.0%) |
| **Aggregate (summed counters, 5 of 5 files, none excluded or missing)** | 6578/6871 (95.7%) | 591/705 (83.8%) | 87/88 (98.9%) | 831/837 (99.3%) |

Before this pass (same command, 2026-10-21T11:32Z, report sha256 ea8e7be6...e4ce), MicrosoftCalendarAdapter.kt was at branches 174/213 (81.7%) and instructions 2255/2384. The aggregate was branches 588/705 (83.4%).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MicrosoftCalendarAdapter.kt:335` (rule removed from a master) | Converting a series to a single event | Covered now: `a series whose rule is removed ...` |
| `MicrosoftCalendarAdapter.kt:574` (event without id) | Malformed Graph item in delta | Covered now: `a delta item without id is skipped` |
| `MicrosoftCalendarAdapter.kt:150, 156, 211, 487-489, 541-543, 553-555` | Rethrow of non-parse RuntimeExceptions, and Graph answering a write with an unreadable event | Defensive paths. They need malformed write answers that Graph does not send, so no test was added (low risk). |
| `MicrosoftCalendarAdapter.kt:486` | Series gone at Graph right after create/update (`RemoteEventChangedException`) | Race with a remote delete, not reproducible from the recordings in a meaningful way. Left uncovered, low risk. |
| `MicrosoftCalendarAdapter.kt:271, 428` (`minByOrNull` comparator) | Several instances within 12 h of one occurrence | Graph patterns repeat at most daily (recorded assumption), so this cannot happen with valid data. Left uncovered. |
| `MicrosoftCalendarAdapter.kt:450-453` | Restore skips an exception that cannot be read, or one already equal to the plain occurrence | Edge branches of a best-effort path. The main restore and failure paths are tested. Left uncovered. |
| `MicrosoftCalendarAdapter.kt:349, 397` (`ifMatch == null` with an empty diff), `393` (unreadable instance), `471-472` (all EXDATEs outside the window), `583-584` (cursor JSON without mode/anchor), `615` (non-UUID connection id), `73` (`pollInterval` getter), `111`, `197`, `218` | Small branch variants of tested behavior | Not material. The surrounding behavior is tested (stale-etag refusal, invalid cursor, EXDATE window filter, missing connection). |
| `GraphRecurrence.kt` 69 of 302 branches (lines 55-88, 160-164, 205-245, 287-312) | RRULE <-> Graph pattern mapping: refusals of rare rule parts, and absolute/relative monthly/yearly variants | Outside the CR focus of this pass. Each line is executed (179/179 lines), and the missing branches are combinations of `when`/null checks in the refusal and normalization paths. They are covered in principle by the parameterized `rules Graph can express survive the round trip` and `rules Graph cannot express are refused`. Remaining gap: candidate for follow-up if more rule shapes matter. |
| `GraphEventMapping.kt:75, 192, 194, 218` | Branch variants (reminder minutes not numeric, minutes changed without switch present, attendee entries without address in the diff) | Not material. |
| `GraphClient.kt:40, 54, 70, 96` | API URL without path, null query values, link without path | Not material. |

## Unable to test

- Real Exchange behavior when a series' recurrence is patched (whether exceptions are actually dropped, CR-1) and when shared-calendar events are addressed via `/me/calendars/{id}/events/{id}` (CR-2). These are tested against WireMock stubs that model the behavior described in review.md, not against recorded Graph responses for these exact calls. A follow-up recording against a real tenant would confirm them, but that is not possible here (no Microsoft account, no network access to Graph).
- Calendar ids containing characters that need encoding (e.g. `=`). This was not exercised. GraphClient encodes the template variables, but whether Graph accepts the encoded form needs a real recording.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient, and no production defects were found.
