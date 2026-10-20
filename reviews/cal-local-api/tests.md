# Test Report

Date: 2026-10-20

Task: cal-local-api - Calendar API for local calendars

Scope: base `b1708640253e120e461364a0e5ce82230387e099` to HEAD `90b74a95c14c0016216d107abda4df9091ef230a` (branch `main`). Task commits (`git log b1708640..HEAD`): 7437436, 9c6c881, 910ec4c, 12502dc, 7e9b5ca, 2d7193c, 100b65e, 6fc9bfc, 90b74a9. This final round focuses on HEAD 90b74a9 (fixes CR-4 and CR-5 in `CalendarEventService.kt` and `EventCommands.kt`), and checks that the earlier tests still cover the whole task. Uncommitted at measurement: my changes to two test files (hashes under "Coverage"). No production file was uncommitted.

Requirements:
- `tasks/cal-local-api.md`: 5 acceptance criteria and the ASSUMPTION notes, in particular "the time fields are the new times of that occurrence and the series moves by the same wall-clock offset" and the 20:20 assumption (a series changes its all-day kind only when `allDay` is sent; CANCELLED overrides keep their status)
- SPEC.md §6.4 and §4.2, as referenced by the task
- `reviews/cal-local-api/review.md` (CR-4, CR-5) and `fixes.md` (second cycle)
- docs/ARCHITECTURE.md "Coverage"
- Parent instructions: check that the CR-4/CR-5 fixes are covered (normal and edge cases, FOLLOWING as well as ALL, `allDay` sent explicitly), add tests only where coverage is missing

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/api/CalendarSeriesEdgeCaseWebTests.kt` (6 new tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/api/EventCommandsTest.kt` (2 new tests)

## Tests added or updated

Count: 8 (all new)

The fixer added 3 tests: CR-4 ALL/FOLLOWING with `startDate` rejected, CR-4 ALL with `start` through an all-day override, and CR-5 cancel and un-cancel with ALL. These gaps were still open:

| New test | Gap closed |
|---|---|
| `PATCH FOLLOWING with a start through an all-day override splits the timed series and keeps it timed` | CR-4 normal case for FOLLOWING. The new series stays timed and starts at the requested time, the later EXDATE moves with it, and every occurrence on both sides is timed. |
| `PATCH ALL with allDay sent through an all-day override still turns the timed series all-day` | CR-4: when `allDay` is sent explicitly, the kind still changes (ALL). The series becomes all-day and moves by one day, the exceptions of the old kind are dropped (documented), and the addressed override is removed. |
| `PATCH FOLLOWING with allDay sent through an all-day override continues the series all-day` | The same for FOLLOWING: the old part stays timed and the new part is all-day from the requested date. |
| `PATCH ALL with allDay false and a start through an all-day override moves the series like without allDay` | CR-4 edge case: `allDay:false` is sent, which is the series' own kind. **Fails, TW-2.** |
| `PATCH ALL through a timed override of an all-day series keeps the series all-day` | CR-4 mirror case (all-day series, timed override). Without `allDay`, `start` is rejected with 400 and nothing changes. With `startDate`, the series moves by days and stays all-day. |
| `un-cancelling the following occurrences does not bring back a deleted occurrence` | CR-5 for FOLLOWING. The series is cancelled with ALL, then un-cancelled with FOLLOWING. The occurrence deleted with THIS after the split point stays removed (not listed, not in search), and the rest of the new series is CONFIRMED. |
| `EventCommandsTest` `propagate gives a new status to an override that still had the old one` | Status propagation still works for overrides that are not cancelled. The existing unit test only covered an override whose status had diverged. |
| `EventCommandsTest` `propagate keeps a cancelled override cancelled when the series is un-cancelled` | Unit-level CR-5 check. The other fields (title) still propagate to the cancelled override. |

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `GET/POST/PATCH /api/v1/calendar/calendars`; `GET /api/v1/calendar/events?from=&to=&calendarIds=` returns expanded occurrences | `CalendarWebTests` (6), range/calendar tests in `CalendarEventEdgeCaseWebTests` and `CalendarEventWebTests`, `CalendarSeriesRequestWebTests` (calendarIds forms, malformed parameters, moved override listing), DST expansion in `CalendarSeriesWebTests` | `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.api.*' --tests '*OpenApiContractTest'` | Pass |
| AC2: `POST/PATCH/DELETE /api/v1/calendar/events/{id}?scope=THIS\|FOLLOWING\|ALL&occurrence=` with correct series splitting/overrides | `CalendarEventWebTests`, `CalendarSeriesWebTests` (15), `CalendarSeriesEdgeCaseWebTests` (43, incl. 6 new CR-4/CR-5 tests), `CalendarSeriesRequestWebTests` (7), `EventCommandsTest` (31), `CalendarEventControllerRaceTest` (4) | same | **Fail**: 1 test, `PATCH ALL with allDay false and a start through an all-day override moves the series like without allDay` (TW-2). All other AC2 tests pass, including every other CR-4/CR-5 test. |
| AC3: ChangeEvents, search, trash for events | `CalendarEventWebTests`, `CalendarSeriesEdgeCaseWebTests` (change events, search after split, cancelled overrides leave search, new FOLLOWING un-cancel search check, Trash), `CalendarWebTests` | same | Pass |
| AC4: `./gradlew test --tests 'ch.sonensei.cockpit.calendar.api.*'` passes (Postgres via Testcontainers) | all 142 tests in `ch.sonensei.cockpit.calendar.api.*` (Testcontainers Postgres via `PostgresTestBase`, Docker available) | same | **Fail**: 141 passed, 1 failed (TW-2) |
| AC5: `api/openapi.yaml` regenerated and `OpenApiContractTest` passes without the flag | `OpenApiContractTest`, `CalendarApiContentTypeTests` | same (without `UPDATE_OPENAPI`) | Pass. Regeneration with the flag was not re-run: the CR-4/CR-5 fix changed no API annotation. |

## Focused test execution

Command:

`cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.api.*' --tests '*OpenApiContractTest'`

Result:

Fail: 143 tests, 1 failed (TW-2), 0 skipped. Before my additions the fixer reported 135 tests passing for the same selection. My 8 new tests bring it to 143. 7 of them pass.

Lint: `cd server && ./gradlew ktlintFormat` (no changes beyond my files), then `./gradlew ktlintCheck`. Pass.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Fail: 1787 tests, 1 failed. The failure is the same TW-2 test, and every other test passed. That includes `core.reminders.ReminderServiceEdgeCaseTests`, which failed in the previous round and was fixed in 6fc9bfc. Run took 4m31s.

## .taskcheck

Result:

Not run. `scripts/check.sh` runs the server `./gradlew check` first, and that fails on TW-2 (above), so `.taskcheck` would fail at the same step. Rerun it after TW-2 is fixed.

## Bugs found

### Sending `allDay:false` with a start through an all-day override of a timed series moves the series to the wrong time

Finding ID:

`TW-2`

Test:

`CalendarSeriesEdgeCaseWebTests` > `PATCH ALL with allDay false and a start through an all-day override moves the series like without allDay`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt:295` (`seriesBase`). The same helper is used for ALL at `:266` and for FOLLOWING at `:319`. The shift itself is computed in `EventCommands.kt` `Timing.shifted`.

Requirement:

Task assumption: "the time fields are the new times of that occurrence and the series moves by the same wall-clock offset". The 20:20 assumption (CR-4 fix) says the series changes its kind only when `allDay` is sent. Sending `allDay:false` to a timed series is no kind change, so the result should be the same as for the request without `allDay`. The fixer's test `PATCH ALL with a start through an all-day override moves the timed series and keeps its exceptions` asserts that result.

Expected behavior:

Setup: the weekly series starts Mondays at 09:00 Europe/Zurich. The occurrence of 9 March was made all-day with THIS. Then `PATCH /events/{override}?scope=ALL {"allDay":false,"start":"2026-03-10T08:00:00Z","timeZone":"Europe/Zurich"}` is sent. That occurrence now starts on 10 March at 09:00 local, so the series should move by one day and keep its 09:00 start: master start `2026-03-03T08:00:00Z`.

Actual behavior:

The master start becomes `2026-03-03T17:00:00Z` (18:00 local). `seriesBase` uses the override's all-day times as the base whenever `allDay` is present (`input.allDay != null`), even when the value equals the series' kind. The offset is then taken from midnight of the all-day override (9 March 00:00) to 10 March 09:00, which is 1 day and 9 hours, not from the original occurrence time (09:00). The duration also falls back to the 60-minute default instead of the series' duration. The addressed occurrence gets the requested 09:00, and every other occurrence moves to 18:00. FOLLOWING goes through the same `seriesBase` path, so it is affected too, though I did not test it separately. A likely fix is to take the override as the base only when the requested kind (`input.allDay ?: series kind`) equals the override's kind, and otherwise use the original occurrence. The parent decides the fix.

Severity:

Low. It needs an all-day override in a timed series plus a client that sends `allDay:false` explicitly. Once that happens, though, the whole series silently moves to the wrong time.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

Not a defect, but worth noting for the parent: the CR-5 trade-off is documented in fixes.md and in the 20:20 assumption. Overrides that were cancelled together with the series stay cancelled after un-cancelling. No test asserts it.

## Coverage

Status: partial-failed. This is a fresh run, but the test command failed (exit non-zero) on TW-2, so these numbers are diagnostic only. All 7 scoped files were measured.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All 7 production files were added by this task, so whole-file coverage equals coverage of the task's lines. For the CR-4/CR-5 lines in HEAD (`CalendarEventService.kt` 265-266, 286-295, 319; `EventCommands.kt` 227-240), JaCoCo reports no missed instructions or branches.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `cal-local-api`; base `b1708640253e120e461364a0e5ce82230387e099` (from the parent); HEAD `90b74a95c14c0016216d107abda4df9091ef230a`; task commits are those of `git log b1708640..HEAD`. Production scope: `git diff --name-only b1708640..HEAD -- server/src/main web/src android/app/src/main api`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `90b74a9` plus modified `CalendarSeriesEdgeCaseWebTests.kt` sha256 `6d19ffa4e4f35dae296b73f14ad71fd3ed7c1c8c18064a10c85cc26d477be4ef` and `EventCommandsTest.kt` sha256 `5eb51076dee16fc5a6c9c115d425515261ab7c17c7a8dc92a2141816cdc14618`. The hashes were the same before and after the run, and there were no other uncommitted changes.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/{CalendarController,CalendarDtos,CalendarEventController,CalendarEventService,CalendarService,EventCommands,EventTrashContributor}.kt`. Excluded: `api/openapi.yaml` (generated contract with no executable code; checked by `OpenApiContractTest`) and `web/src/app/api/generated/**` (generated client, not task-authored logic). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin, project default version), run in `server/`. Command: `./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.api.*' --tests '*OpenApiContractTest'`. Because the test task failed, the report was then generated from that run's exec data with `./gradlew jacocoTestReport -x test`.
- Run start/completion time and exit status: 2026-10-20 18:25:00Z to 18:25:27Z. The test task failed (1 of 143). `jacocoTestReport -x test` ended with BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` (written 18:25:22Z), sha256 `7143f861d50a4ae605410da925d79be806990078d98134dd6737747dd1e34cce`. `server/build/reports/jacoco/test/jacocoTestReport.xml` (written 18:25:27Z), sha256 `9c8967f7f15836a41bb1b8d0315fcb3fa932e6721c5cecc62f3d48fb7ff6fba3`. Counters come from the `sourcefile` counters of package `ch/sonensei/cockpit/calendar/api`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun` forced the run, both files were rewritten by it, and the test-file hashes did not change.
- Final run after latest test additions and production fixes: yes for HEAD 90b74a9 with my tests. A TW-2 fix will make these numbers stale, so they need a fresh run then.

Values below use covered/total (percentage). "Statements" are JaCoCo instructions, because JaCoCo has no statement counter.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarController.kt` | 362/366 (98.9%) | 25/26 (96.2%) | 10/11 (90.9%) | 40/40 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarDtos.kt` | 1411/1505 (93.8%) | 34/40 (85.0%) | 104/122 (85.2%) | 213/213 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventController.kt` | 653/659 (99.1%) | 35/36 (97.2%) | 12/14 (85.7%) | 72/73 (98.6%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt` | 2814/2852 (98.7%) | 325/338 (96.2%) | 49/51 (96.1%) | 340/343 (99.1%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarService.kt` | 496/525 (94.5%) | 44/46 (95.7%) | 22/23 (95.7%) | 64/65 (98.5%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/EventCommands.kt` | 1098/1192 (92.1%) | 119/128 (93.0%) | 47/55 (85.5%) | 140/141 (99.3%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/EventTrashContributor.kt` | 199/211 (94.3%) | 24/28 (85.7%) | 6/7 (85.7%) | 31/31 (100.0%) |
| **Aggregate (7 files, summed counters)** | 7033/7310 (96.2%) | 606/642 (94.4%) | 250/283 (88.3%) | 900/906 (99.3%) |

Aggregate scope: all 7 task production files were measured and none is missing. Excluded: `api/openapi.yaml` and `web/src/app/api/generated/**` (generated, see above).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarEventService.kt:295` (`seriesBase`, `allDay` sent with the series' own kind) | CR-4 / the wall-clock offset assumption | Covered by the new test, which fails: TW-2. |
| `CalendarEventService.kt:524` | Override addressed while its master is not active | Unreachable since the TW-1 fix, because `load` already returns 404. The behaviour is covered by `an override of a trashed series is not found` and `unknown and trashed events cannot be changed or deleted`. Justified gap. |
| `CalendarEventService.kt:228-229` | Rethrow of an integrity violation when updating an existing override | Defensive code. The 409 path for a new override is tested. Without fault injection there is no realistic trigger. Justified gap. |
| `CalendarEventService.kt:87`, `:114` (partial branches) | Empty `calendarIds` list falls back to visible calendars; missing title | The fallback has no stated requirement. A missing title is rejected by bean validation first (tested). Low risk. Justified gap. |
| `CalendarEventService.kt:490, 496, 567, 652, 675` (partial branches) | Kotlin null-safe/lambda branches in rule parsing, `isGenerated` and the URL check | Generated bytecode branches. The behaviours are tested. Justified gap. |
| `CalendarEventService.kt:371, 378, 688` | Default-argument bridge of `carryExceptions`; companion initializer | Synthetic Kotlin code. Justified gap. |
| `EventCommands.kt:236-242` (partial branches) | `propagate` for overrides whose description/attendees/reminders/transparency/URL diverged | The CR-5 line 240 is fully covered (new unit tests). The remaining branches are "diverged" sides of fields that are not tested one by one. The rule is the same for every field, and title, location and status are tested. Low risk. Justified gap. |
| `EventCommands.kt:25-37, 157, 160, 164, 183, 202, 208-214` | Data-class synthetics; `Timing.resolve`/`compareTo` branches for mixed kinds | Synthetic code, or a documented invariant (one kind per series). Resolve paths are covered by `EventCommandsTest.Resolve`. Justified gap. |
| `CalendarService.kt:25-30, 119`; `CalendarDtos.kt:244-263`; `CalendarController.kt:158, 174`; `CalendarEventController.kt:225, 254, 273`; `EventTrashContributor.kt:31, 41, 48, 55, 65` | Default constructors, companions, the `"body"` fallback for Jackson errors, Trash paths for override ids | Unchanged since the previous round. Synthetic code, or paths with no input that reaches them through the API (see the reasons in the earlier report: overrides are never trashed on their own). Justified gap. |

## Unable to test

- `.taskcheck` was not run, because it would fail at the server step on TW-2. It needs to run after the fix.
- `api/openapi.yaml` regeneration (`UPDATE_OPENAPI=1`) was not re-run because the API did not change. The check without the flag passed.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. The CR-4 and CR-5 fixes are covered for ALL and FOLLOWING, for an explicitly sent `allDay:true`, for the all-day series mirror case, and at unit level. All of those tests pass except TW-2 (Low): sending `allDay:false` explicitly through an all-day override of a timed series moves the series to the wrong time. Every other test in the server suite (1786 of 1787) passes. Task-scoped coverage stays high (99.3% lines, 94.4% branches), but it is diagnostic only because of the failing run.
