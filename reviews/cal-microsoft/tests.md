# Test Report

Date: 2026-10-21

Task: cal-microsoft - Microsoft Graph calendar adapter

Scope: round 3. Task cal-microsoft, base 0a701f27cd0e4db92d84a54985f0f5ce2ae0c04d, measured HEAD 7480faa19774a204b1bddb221121f6581933d112 (`git log 0a701f27..HEAD`), plus this round's uncommitted test change. This round covers the round-2 fixes for the second review (321a89e2, rebased as bc79ea7e: `RestoredEtags`, an empty diff succeeds whatever the etag, `instancesOrNull` / 404 from `/instances`). The production files have not changed since that commit (`git log 0a701f27..HEAD -- server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft` shows 7c33b3c8, 6d334f70, bc79ea7e). The previous rounds' report is kept below the separator.

Requirements:
- tasks/cal-microsoft.md acceptance criteria 1-3 and its assumptions, including the 2026-10-21 14:24 one (an empty diff succeeds whatever the If-Match etag, restored-exception etags kept in memory only, bounded to 1000)
- SPEC.md §6.2 (412 is "changed remotely", THIS/FOLLOWING/ALL)
- reviews/cal-microsoft/review.md (second review CR-1 P2, CR-2 P3) and reviews/cal-microsoft/fixes.md (round 2)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md conventions (fictitious test data, lint)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapterTests.kt`

## Tests added or updated

Count: 8 added, none changed or removed (MicrosoftCalendarAdapterTests went from 59 to 67, microsoft.* from 165 to 173). There is one new helper, `movedOverrideJson()`.

Section "round-2 fixes (restored etags, empty diffs, series gone)" in MicrosoftCalendarAdapterTests (WireMock):
- `after a pattern change, cancelling an override deletes the exception Graph put back with its current etag`: covers the cancelled-override path of `writeInstance` with `RestoredEtags`. The DELETE carries `If-Match: W/"restored"` (Graph's etag of the put-back exception), not the cockpit's stale `DwAAABYAAAA4`. Before this round only the PATCH path was tested.
- `an exception put back after a pattern change and then changed in Outlook is a change at Microsoft`: the instance's etag moved on after the restore (`outlook-edit`). The remembered mapping must not apply, so the cockpit's own etag is sent, 412 follows and the result is `RemoteEventChangedException`. No request carries `If-Match: restored`. This protects against `RestoredEtags` hiding a real remote change.
- `an exception Graph kept unchanged through a pattern change is not patched, and its override is written with Graph's new etag`: the new `changes.isEmpty -> instance` branch of `restoreExceptions`. Graph keeps the exception with a new etag, there is no restore PATCH, and the next override write uses `If-Match: W/"kept"`.
- `an exception Graph kept but changed through a pattern change is left alone and its old etag stays stale`: the `type == exception` with changes branch. It is not patched and not remembered, so the later write sends the stale etag (412 -> `RemoteEventChangedException`).
- `an exception that could not be put back is not remembered - writing its override with the old etag is a change at Microsoft`: the restore PATCH fails (500), and the following override write sends the cockpit's etag, not the plain occurrence's.
- `a series gone at Microsoft by the time the whole window is asked has no such occurrence`: CR-2 for the second lookup in `findInstance`. The weeks around the occurrence are empty, and the whole window answers 404. Delete returns null, and a change raises `RemoteEventChangedException`, with no PATCH or DELETE. Before this round only a 404 on the first lookup was tested.
- `an unchanged series writes nothing and returns Graph's series, whatever the etag`: the empty-diff rule on the master path of `updateEvent` for a series. It returns `currentSeries` with Graph's etag `DwAAABYAAAA3`. Before this round only a single event and an occurrence were tested.
- `a changed series with a stale etag is sent with it, and Graph's 412 is a change at Microsoft`: the counterpart. A non-empty diff on a series still sends the caller's stale If-Match, and 412 becomes `RemoteEventChangedException`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 writes: empty diff succeeds whatever the etag (assumption 14:24), real changes still go out with the caller's If-Match | `updateEvent without changes writes nothing and returns Graph's version, whatever the etag`, `an unchanged occurrence writes nothing ...`, `an unchanged series writes nothing ...`, `a changed occurrence with a stale etag is sent with it ...`, `a changed series with a stale etag is sent with it ...` | focused command below | Pass |
| AC1 occurrence edits / 'following' split: second review CR-1, overrides written after a pattern change (`RestoredEtags`) | `after a pattern change, an override is written against the exception Graph put back ...`, `after a pattern change, an override with an etag other than the exception's ...`, `after a pattern change, cancelling an override deletes ...`, `an exception put back ... then changed in Outlook ...`, `an exception Graph kept unchanged ...`, `an exception Graph kept but changed ...`, `an exception that could not be put back is not remembered ...`, sync test `changing the pattern of a whole series with an exception succeeds, the exception keeps Graph's new etag` | focused command below | Pass |
| AC1 occurrence edits: second review CR-2, series gone at Microsoft (404 from `/instances`) | `a series gone at Microsoft has no occurrences - delete succeeds, change is a change at Microsoft`, `a series gone at Microsoft by the time the whole window is asked has no such occurrence` | focused command below | Pass |
| AC1 rest (calendars, delta sync, writes, occurrence edits, split) | unchanged from the previous round (see below) | focused command below | Pass |
| AC2 Tests against WireMock recordings of Graph | MicrosoftCalendarAdapterTests (67), MicrosoftCalendarSyncTests (5) | focused command below | Pass |
| AC3 `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*'` passes, Postgres via Testcontainers | all 173 microsoft.* tests; MicrosoftCalendarSyncTests extends `PostgresTestBase` (Testcontainers) | `cd server && ./gradlew ktlintCheck test --rerun --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*' jacocoTestReport -Pkotlin.daemon.jvmargs=-Xmx3g` | Pass (173 tests, 0 failures, 0 errors, 0 skipped) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --rerun --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*' jacocoTestReport -Pkotlin.daemon.jvmargs=-Xmx3g`

Result:

Pass. BUILD SUCCESSFUL, run from 2026-10-21T12:28:09Z to 12:28:50Z. JUnit: GraphEventMappingTests 44, GraphRecurrenceTests 37, MicrosoftCalendarAdapterTests 67, MicrosoftCalendarSyncTests 5 (Postgres via Testcontainers), WindowsZonesTests 20. Total 173 tests, 0 failures, 0 errors, 0 skipped. ktlintCheck passes with no suppressions. A first run reported one line over 140 characters in a new test, and it was rewrapped by hand. `ktlintFormat` was not run, so that no production file could be touched. `-Pkotlin.daemon.jvmargs=-Xmx3g` is a command-line property only. No configuration was changed.

## Full test execution

Command:

`cd server && ./gradlew check -Pkotlin.daemon.jvmargs=-Xmx3g`

Result:

Not completed (environment). After 8m 33s the Gradle test executor was killed with exit value 137 (SIGKILL, out of memory: the machine had 856 MB free and 8 GB swap fully used, with other agents running). Up to the kill, 132 test classes with 1457 tests had finished, with 0 failures and 0 errors. This is not a passing full run. The parent's `.taskcheck` must provide it.

## .taskcheck

Result:

Not run by the test-writer (`sh scripts/check.sh` runs all parts and was noted to take more than 10 minutes under load, and the server part was already killed by memory pressure above). The parent runs it before `done`.

## Bugs found

None. Every new test passed against the round-2 code. The `RestoredEtags` mapping is applied only when the caller's etag is the one the exception had and Graph's instance still has the etag the adapter saw after putting it back. In every other case (remote edit after the restore, a failed restore, an exception Graph kept with other fields) the caller's etag is sent and a real remote change still ends in 409. The empty-diff rule holds for single events, occurrences and series, and a non-empty diff still carries the caller's If-Match. A 404 on either `/instances` lookup is treated as "no such occurrence".

## Coverage

Status: verified (fresh focused run on a stable snapshot)

Metric: whole-file coverage of task-changed production files. Only microsoft.* tests were executed for the report.
Changed-line coverage: not measured separately. All 5 files are added by this task (`git diff --name-status 0a701f27..HEAD -- server/src/main web/src android/app/src/main` lists only `A` entries), so whole-file coverage equals changed-line coverage for the task.

Evidence:
- Task id cal-microsoft, immutable base 0a701f27cd0e4db92d84a54985f0f5ce2ae0c04d (given by the parent), measured HEAD 7480faa19774a204b1bddb221121f6581933d112. Task ownership: the parent's range 0a701f27..HEAD, the task `touches` (`calendar/infra/microsoft` main and test), and the commits touching those paths, which are all "cal-microsoft:". The range also contains other tasks' commits (cal-google, heartbeats), and they are excluded because they are outside the task's paths.
- Source/test snapshot: HEAD 7480faa1 plus uncommitted `MicrosoftCalendarAdapterTests.kt` sha256 3d8409b05102c51467d9d65419970ef813ccdb047e873aeb28bb4fe21819cf98. Production sha256: MicrosoftCalendarAdapter.kt be68d6c7...23e3, GraphEventMapping.kt 4869f021...adc2, GraphRecurrence.kt ea1571c2...14e2, GraphClient.kt 926569e8...11b4, WindowsZones.kt 39327688...fb23. The other test files are unchanged (MicrosoftCalendarSyncTests.kt 414f7bd4...3af7). Hashes were taken after the run and match the inputs of the run.
- Included files: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/{GraphClient,GraphEventMapping,GraphRecurrence,MicrosoftCalendarAdapter,WindowsZones}.kt`. No renamed, deleted or excluded files. All 5 are in the JaCoCo report.
- Provider: JaCoCo via the Gradle `jacoco` plugin (project config, unchanged). Command as in "Focused test execution", working directory `server/`, test selection `ch.sonensei.cockpit.calendar.infra.microsoft.*`.
- Run: 2026-10-21T12:28:09Z to 12:28:50Z, exit status 0. `build/jacoco/test.exec` was written 12:28:46Z, and the XML report 12:28:49Z.
- Report: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 5fc34f002e643b148fc69686f502e672e6c60e8764833ebf28e886a4a478073b. The later, killed `./gradlew check` did not regenerate the XML (same hash afterwards), but it did overwrite `test.exec`.
- Freshness: `--rerun` forced the test task, and the inputs did not change during or after the run.
- Final run after the latest test additions: yes. No production code changed in this round.

Values below use covered/total (percentage). JaCoCo "Statements" are instructions.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarAdapter.kt` | 2403/2541 (94.6%) | 190/229 (83.0%) | 46/47 (97.9%) | 318/324 (98.1%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/GraphEventMapping.kt` | 1110/1112 (99.8%) | 133/138 (96.4%) | 17/17 (100.0%) | 122/122 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/GraphRecurrence.kt` | 1758/1920 (91.6%) | 233/302 (77.2%) | 14/14 (100.0%) | 179/179 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/GraphClient.kt` | 537/543 (98.9%) | 34/38 (89.5%) | 14/14 (100.0%) | 75/75 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/WindowsZones.kt` | 912/912 (100.0%) | 14/14 (100.0%) | 3/3 (100.0%) | 151/151 (100.0%) |
| **Aggregate (summed counters, 5 of 5 files, none excluded or missing)** | 6720/7028 (95.6%) | 604/721 (83.8%) | 94/95 (98.9%) | 845/851 (99.3%) |

The adapter's counters grew with the round-2 code (`RestoredEtags`, `instancesOrNull`), from 2384 to 2541 instructions and from 213 to 229 branches. Uncovered branches went from 36 to 39: lines 243, 483 and 652 below. All other new branches of 321a89e2 are covered, and JaCoCo shows lines 396-411, 427, 430, 472 and 474 fully covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MicrosoftCalendarAdapter.kt:396-411` (`writeInstance` with `RestoredEtags`: DELETE, stale-after-restore, not remembered) | Second review CR-1: override written after a pattern change | Covered now by the 5 restored-etag tests above |
| `MicrosoftCalendarAdapter.kt:472-474` (`restoreExceptions`: kept unchanged / kept changed) | CR-1: Graph keeping exceptions through a pattern change | Covered now: `an exception Graph kept unchanged ...`, `... kept but changed ...` |
| `MicrosoftCalendarAdapter.kt:430` (404 on the whole-window lookup) | Second review CR-2 | Covered now: `a series gone at Microsoft by the time the whole window is asked ...` |
| `MicrosoftCalendarAdapter.kt:243` (`instances()` throws on 404) | The series master was just read successfully and `/instances` then answers 404 (pull in `readSeries`, the pre-PATCH exception read, the restore or EXDATE read). The result is a provider error (502) for that run | Race with a remote delete within one request sequence. The next poll sees the removal, and fixes.md states this behavior is intended. Left uncovered, low risk. |
| `MicrosoftCalendarAdapter.kt:652` (`removeEldestEntry` beyond 1000 entries), `662` (`remember` with a missing or equal etag), `672` (instance without etag) | Bound of the in-memory map, and degenerate etags | Eviction needs more than 1000 restores against WireMock. The assumption accepts a lost entry (a false 409 until the next sync). Graph always sends etags. Not material, left uncovered. |
| `MicrosoftCalendarAdapter.kt:483` (`now.text("id") ?: continue`) | Instance without id | Graph always sends ids. Not material. |
| `MicrosoftCalendarAdapter.kt:512-513, 566-567, 578-579` and partial branches `113, 152, 158, 199, 213, 220, 349, 406, 467-468, 496-497, 510, 556, 608-609, 640` | Defensive rethrows, unreadable Graph answers, and small branch variants | As in the previous round (see the table below, line numbers shifted by the round-2 changes). Not material. |
| `GraphRecurrence.kt`, `GraphEventMapping.kt`, `GraphClient.kt` | Unchanged since the previous round | Same gaps and reasons as the previous round below |

## Unable to test

- Real Exchange behavior around a pattern PATCH: whether an exception gets a new etag when it is put back, and whether Graph sometimes keeps exceptions. The `RestoredEtags` tests model this with WireMock stubs, not with recordings of these exact calls. There is no Microsoft tenant or network access here, as in the previous round.
- Eviction of `RestoredEtags` beyond 1000 entries and the loss of entries on restart. These are accepted by the recorded assumption and not tested.

## Summary

Tests added and the focused validation passed. Task-scoped coverage is sufficient, and no production defects were found. The full `./gradlew check` could not finish here (killed by the OS for lack of memory, 0 failures up to then). The parent's `.taskcheck` run still has to supply full-suite evidence.

---

# Test Report (previous round, kept for reference)

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
