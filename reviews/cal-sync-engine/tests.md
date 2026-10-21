# Test Report

Date: 2026-10-21

Task: cal-sync-engine - Calendar sync engine

Scope: Task commits `git log 93da5938..fb5b7ca5` (base `93da59385314233b39dd326aaaa5c9c55aa9a09a`, HEAD `fb5b7ca5138290c7d115e270e87c1795c1345a67`, branch main, no uncommitted changes). Only the cal-sync-engine commits count (5cad3296, 0d73c50c, 2930c32e, b904da97, 5489a214, e23e97bf, 470124b5, 4b473e41, e123d6eb, 4c125b5a, bfab6fd9, c172e0d9, fb5b7ca5); heartbeat/extend commits of other tasks in the range touch no files of this task. The last code change is c172e0d9 (the parent's brief calls it 17528b0f, which is the same change before the rebase): an invalid provider answer to a write-through -> 502, a test documenting the accepted lossy write-back (CR-1), and a comment fix (CR-2). The earlier test-writer tests are in bfab6fd9 (called 1183a3a4 in the brief).

Requirements:
- tasks/cal-sync-engine.md (acceptance criteria 1-6 and the recorded assumptions, including CR-1 accepted as lossy, CR-2 deferred, and invalid provider answer -> 502)
- SPEC.md §6.2, §4.2
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md, AGENTS.md
- reviews/cal-sync-engine/review.md, reviews/cal-sync-engine/fixes.md

## Test files changed

- None in this round. The existing task tests already cover every acceptance criterion and the final code change (see below). This report is the only new file.

Existing task test files (reviewed):
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncEdgeCaseTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncOverrideTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSyncUnitTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/FakeCalendarAdapter.kt` (fake adapter)

## Tests added or updated

Count: 0 (in this round). The sync package has 91 tests in total.

Final code change (c172e0d9) is covered by:
- `a provider answering a write-through with an event it cannot be stored as fails the edit with 502, the local copy stays` (the new `try { copyFrom } catch IllegalArgumentException -> CalendarProviderException` path; JaCoCo shows the lines covered)
- `a local edit of a normalized event writes the normalized fields back to the provider (accepted by assumption)` (CR-1 assumption: it asserts what reached the fake provider: location changed, `tel:` conference URL, invalid attendee and negative reminder removed)
- The CR-2 change is a comment only; the `tryLock` skip is covered by `a refetch does not wait for a sync of the same source that is in progress`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `CalendarProviderAdapter` interface (list calendars, incremental fetch with cursor, create/update/delete with etag) + registry by source type | `the registry finds each adapter by its source type and supports only those types`, `the registry rejects two adapters for the same type`, `the registry rejects an adapter for LOCAL sources`, `an empty registry syncs nothing`, `pages with hasMore are fetched in one run with the returned cursors, and the last cursor is stored`, `creating, changing and deleting an event of a synced calendar goes to the provider first` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.sync.*'` | Pass |
| AC2 scheduler polls each source at its interval, stores cursor/last sync/error | `a scheduler tick syncs the due sources`, `a failing scheduler tick is swallowed, and the next tick tries again`, `the scheduler ticks every 30 seconds without configuration`, `the scheduler is off when disabled, as in the tests`, `syncDue syncs sources whose poll interval passed since the last attempt`, `syncDue skips a source whose run is in progress`, `syncDue never syncs local sources or types without adapter`, `the first sync creates the calendars and events, stores the cursor and records success`, `a failing calendar is recorded as error, the others still sync, the next success clears it` | same | Pass |
| AC2 write-through: local edits of external events go to the adapter first; 412 -> refetch, 409 to client; other failures -> 502 (assumption) | `creating, changing and deleting an event of a synced calendar goes to the provider first`, `changing one occurrence of a synced series creates the override at the provider`, `a remote change since the last sync makes a local edit fail with 409 and fetches the new version`, `a remote change makes a local delete fail with 409, the event stays and shows the new version`, `the 409 for a remote change is returned even when the refetch fails`, `a remote change of an override makes a local edit of it fail with 409 and fetches the new version`, `a failing provider makes the edit fail with 502 and leaves the local copy unchanged`, `a provider answering a write-through with an event it cannot be stored as fails the edit with 502, the local copy stays`, `a failing write in the middle of a split rolls the local edit back, the next sync brings the provider's state` | same | Pass |
| AC3 `GET /api/v1/calendar/sources` incl. sync status; `POST .../{id}/sync` | `GET sources lists every source with its sync status, POST sync syncs one now`, `POST sync of a local, unsupported or unknown source fails`, `GET sources shows a source without adapter as not syncable, without status`, `a source removed while it syncs gets no status, and the sync reports it as not found` | same | Pass |
| AC4 fake-adapter tests: incremental merge, deletions, conflict path | `a later sync fetches only the changes since the cursor and merges changes, additions and deletions`, `a complete listing removes local events the provider no longer has`, `an event deleted at the provider loses its links and its search entry`, `a series deleted at the provider goes with its overrides and their links`, `series overrides are merged after their series, a removed occurrence becomes an EXDATE`, the 409 tests above | same | Pass |
| AC5 sync suite passes (Postgres via Testcontainers) | all 91 tests in `ch.sonensei.cockpit.calendar.sync.*` | same | Pass (91 tests, 0 failures, 0 errors, 0 skipped) |
| AC6 `api/openapi.yaml` up to date, `OpenApiContractTest` passes without the flag | `OpenApiContractTest` | `cd server && ./gradlew test --tests '*OpenApiContractTest'` | Pass (1 test). The regeneration with `UPDATE_OPENAPI=1` was not re-run here; the test passing without the flag shows the committed file matches the code. |
| Assumption: remote normalization (CR-2 of round 1), lossy write-back accepted (CR-1 of round 2) | `remote events are kept within the API's limits - ...`, `a local edit of a normalized event writes the normalized fields back to the provider (accepted by assumption)`, `the provider's answer to a write-through is normalized like pulled events`, `an invalid remote change of a known event is skipped and leaves the local copy unchanged`, plus the `CalendarSyncUnitTests` normalization tests | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.sync.*'` | Pass |
| Changed `calendar/api` code (validation helpers moved, synced-calendar rules) does not break the event API | all of `ch.sonensei.cockpit.calendar.*` | `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.*' jacocoTestReport` | Pass (337 tests, 0 failures) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.sync.*'`

Result:

Pass: 91 tests, 0 failures, 0 errors, 0 skipped (BUILD SUCCESSFUL in 1m 54s).

Command:

`cd server && ./gradlew test --tests '*OpenApiContractTest'`

Result:

Pass: 1 test (BUILD SUCCESSFUL in 48s).

## Full test execution

Command:

`cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.*' jacocoTestReport`

Result:

Pass: 337 tests, 0 failures, 0 errors, 0 skipped (BUILD SUCCESSFUL in 46s). The full server suite outside `calendar.*` was covered by `.taskcheck` below.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` -> exit 0, "== all checks passed" (server, web 57 files / 1074 tests, android).

## Bugs found

None. The production bug reported in the previous round (invalid provider answer -> 500 instead of 502) is fixed in c172e0d9, and its test passes.

## Coverage

Status: verified (fresh JaCoCo run on HEAD fb5b7ca5, clean tree).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (JaCoCo here produces no diff-based report).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: cal-sync-engine; base `93da59385314233b39dd326aaaa5c9c55aa9a09a` (given by the parent); HEAD `fb5b7ca5138290c7d115e270e87c1795c1345a67`; files from `git diff --name-only 93da5938..HEAD -- server/src/main`, checked against the task commits listed in Scope.
- Source/test snapshot identifier (including uncommitted inputs): no uncommitted changes; `git ls-files -s server/src/main/kotlin/ch/sonensei/cockpit/calendar server/src/test/kotlin/ch/sonensei/cockpit/calendar | sha256sum` = `ef7cbb8e3f4d31810d9ec7557460f9ae4c81954850f1f17293ed12eb581a512b`, the same before and after all runs.
- Exact included files: `calendar/sync/CalendarProviderAdapter.kt`, `CalendarProviderRegistry.kt`, `CalendarSourceController.kt`, `CalendarSyncScheduler.kt`, `CalendarSyncService.kt`, `CalendarSyncState.kt`, `CalendarWriteThrough.kt`, `RemoteEventMapping.kt`; `calendar/api/CalendarEventController.kt`, `CalendarEventService.kt`, `CalendarService.kt` (all under `server/src/main/kotlin/ch/sonensei/cockpit/`). Excluded: `server/src/main/resources/db/migration/calendar/V202610210900__calendar_sync.sql` (SQL migration, not instrumentable by JaCoCo; exercised by every Testcontainers test). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (Gradle 9.8.1, plugin default tool version); `./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.*' jacocoTestReport` in `server/`; selection: all `ch.sonensei.cockpit.calendar.*` tests (337).
- Run start/completion time and exit status: 2026-10-21 about 02:06:10 to 02:06:59 +02:00; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 `8db697a3bb17db18bef172d64683c79b14ac7fc9170b53d285cb53eac366d9e3`, mtime 02:06:59.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun` forced the test task to execute, and jacocoTestReport ran (not UP-TO-DATE). The snapshot hash did not change.
- Final run after latest test additions and production fixes: yes. No tests were added in this round; the run is on the final code (c172e0d9 + fb5b7ca5 report-only).

Values below use covered/total (percentage) from the JaCoCo per-sourcefile counters (statements = INSTRUCTION, functions = METHOD); N/A = no counter (no branches in the file).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `sync/CalendarProviderAdapter.kt` | 308/314 (98.1%) | N/A | 36/37 (97.3%) | 39/40 (97.5%) |
| `sync/CalendarProviderRegistry.kt` | 80/80 (100.0%) | 8/8 (100.0%) | 3/3 (100.0%) | 10/10 (100.0%) |
| `sync/CalendarSourceController.kt` | 248/266 (93.2%) | 16/18 (88.9%) | 23/23 (100.0%) | 55/55 (100.0%) |
| `sync/CalendarSyncScheduler.kt` | 34/34 (100.0%) | N/A | 4/4 (100.0%) | 10/10 (100.0%) |
| `sync/CalendarSyncService.kt` | 1848/1867 (99.0%) | 160/178 (89.9%) | 47/47 (100.0%) | 249/249 (100.0%) |
| `sync/CalendarSyncState.kt` | 55/58 (94.8%) | N/A | 13/14 (92.9%) | 15/16 (93.8%) |
| `sync/CalendarWriteThrough.kt` | 637/655 (97.3%) | 66/76 (86.8%) | 16/16 (100.0%) | 98/98 (100.0%) |
| `sync/RemoteEventMapping.kt` | 406/406 (100.0%) | 54/54 (100.0%) | 4/4 (100.0%) | 56/56 (100.0%) |
| `api/CalendarEventController.kt` | 653/659 (99.1%) | 35/36 (97.2%) | 12/14 (85.7%) | 72/73 (98.6%) |
| `api/CalendarEventService.kt` | 2928/2964 (98.8%) | 337/348 (96.8%) | 51/53 (96.2%) | 360/363 (99.2%) |
| `api/CalendarService.kt` | 506/535 (94.6%) | 46/48 (95.8%) | 22/23 (95.7%) | 66/67 (98.5%) |
| **Aggregate (11 files, summed counters)** | 7703/7838 (98.3%) | 722/766 (94.3%) | 231/238 (97.1%) | 1030/1037 (99.3%) |

Package `ch.sonensei.cockpit.calendar.sync` alone (8 files, summed): statements 3616/3680 (98.3%), branches 304/334 (91.0%), functions 146/148 (98.6%), lines 532/534 (99.6%). The `calendar/api` files are whole-file numbers and include code from before this task. Excluded from the aggregate: the SQL migration (see above). No scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `sync/CalendarWriteThrough.kt:124,130,137` (adapter missing at write time, delete of an event without externalId, override of a series without externalId) | Defensive guards. `isSynced` already requires an adapter, and creates are always pushed before the commit, so local events of synced calendars always have an externalId. | Justified gap: not reachable through the API in the current design. |
| `sync/CalendarWriteThrough.kt:46,71,103,158` (partial branches: null calendar/source, refetch on commit, seriesShareEtag combinations) | Null-safety and flag combinations. The main paths (409 + refetch after rollback, series-wide etag writes) are tested. | Justified gap: low risk. |
| `sync/CalendarSyncService.kt:364,383,392` (`isDeleted` -> restore / unchanged check) | A synced event in the Trash; by task assumption synced events are deleted for good, so this is defensive. | Justified gap. |
| `sync/CalendarSyncService.kt:406,419-427` (partial branches of EXDATE handling and orphaned-override cleanup: non-recurring series, date kind mismatch, duplicate EXDATE, null externalId) | Merge edge cases. The main paths (remote occurrence deletion -> EXDATE, removed override in a complete listing) are tested. | Justified gap: low risk, converges on the next full fetch. |
| `sync/CalendarSyncService.kt:140,144,174,217,453,461` | Null-safety branches (missing source/externalId/adapter, blank message default is tested). | Justified gap. |
| `sync/CalendarSourceController.kt:89-90` (`checkNotNull` failure branches of createdAt/updatedAt) | Persisted entities always have timestamps. | Justified gap: unreachable. |
| `sync/CalendarProviderAdapter.kt:35,114,132` (interface default `seriesShareEtag`, data class default args) | The fake adapter overrides the default; Kotlin default-argument synthetic code. | Justified gap: no behavior. |
| `sync/CalendarSyncState.kt:37` (one generated accessor) | Generated JPA/data code. | Justified gap. |
| `api/*` remaining lines (e.g. `CalendarService.kt:26-31`, `CalendarEventService.kt:90,117,238-239,...`) | Mostly code from before this task (cal-local-api). | Outside the changes of this task; not chased. |

## Unable to test

- AC6 regeneration step (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) was not re-run, so that the committed `api/openapi.yaml` and generated web client were not rewritten by a helper. The contract test without the flag passes, which shows the committed file is current. No follow-up needed.
- Real provider adapters (Google, CalDAV, ICS) are out of scope for this task; all tests use the fake adapter as the criterion asks.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. All acceptance criteria have passing tests, the final code change (invalid provider answer -> 502, CR-1 lossy write-back assumption) is covered, the sync suite (91), the calendar suite (337), `OpenApiContractTest` and `.taskcheck` pass.
