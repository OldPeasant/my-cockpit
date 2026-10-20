# Test Report

Date: 2026-10-20

Task: cal-local-api - Calendar API for local calendars

Scope: base `b1708640253e120e461364a0e5ce82230387e099` to HEAD `d46c83e1e0669ee2321863e1fe7c78cc2874ec2d` (branch `main`), task commits `git log b1708640..HEAD`. This final round checks the TW-2 fix in HEAD d46c83e (`seriesBase` in `CalendarEventService.kt`) and re-measures the whole task. Uncommitted at measurement: my one new test in `CalendarSeriesEdgeCaseWebTests.kt` (hash under "Coverage"). No production file was uncommitted. `reviews/cal-local-api/review.md` was also modified by the code-reviewer; it is not a test or production input.

Requirements:
- `tasks/cal-local-api.md`: 5 acceptance criteria and the ASSUMPTION notes, in particular "the series moves by the same wall-clock offset" and the 20:20 assumption (a series changes its all-day kind only when `allDay` is sent)
- `reviews/cal-local-api/fixes.md` "third cycle" (TW-2 fix), `review.md` (CR-6, accepted by the parent without a fix)
- docs/ARCHITECTURE.md "Coverage"
- Parent instructions: check that the TW-2 fix is covered for ALL and FOLLOWING with `allDay` true, false and absent; add tests only where a gap remains; do not add a failing test for CR-6

Execution note: the permission system blocked me from running Gradle in this session. The coordinator ran the commands below from the clone root, on HEAD d46c83e plus my uncommitted test. I checked the results myself, read-only: I read the JUnit XML in `server/build/test-results/test`, parsed the JaCoCo XML, and hashed the artifacts.

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/api/CalendarSeriesEdgeCaseWebTests.kt` (1 new test)

## Tests added or updated

Count: 1 (new)

Coverage of the TW-2 fix. `seriesBase` now uses the addressed override as the base only when its kind matches `input.allDay ?: series kind`:

| Case | ALL | FOLLOWING |
|---|---|---|
| Timed series, all-day override, `allDay` absent | `PATCH ALL with a start through an all-day override moves the timed series and keeps its exceptions` | `PATCH FOLLOWING with a start through an all-day override splits the timed series and keeps it timed` |
| Timed series, all-day override, `allDay:true` (kind change) | `PATCH ALL with allDay sent through an all-day override still turns the timed series all-day` | `PATCH FOLLOWING with allDay sent through an all-day override continues the series all-day` |
| Timed series, all-day override, `allDay:false` (own kind: the TW-2 case) | `PATCH ALL with allDay false and a start through an all-day override moves the series like without allDay` (failed in the previous round, passes now) | `PATCH FOLLOWING with allDay false and a start through an all-day override splits like without allDay` (added by the fixer in d46c83e) |
| All-day series, timed override, `allDay` absent | `PATCH ALL through a timed override of an all-day series keeps the series all-day` | not tested on its own (same `seriesBase` branch as ALL) |
| All-day series, timed override, `allDay:true` (own kind: mirror of TW-2) | **new:** `PATCH ALL or FOLLOWING with allDay true through a timed override of an all-day series behaves like without allDay` | same new test |
| All-day series, timed override, `allDay:false` (kind change) | CR-6. The parent accepted it without a fix, so no test was added, as instructed | same |

The new test closes the mirror direction of the fix. Before d46c83e, sending `allDay:true` through a timed override of an all-day series also used the timed override as the base. The test asserts the following:
- ALL: the series moves by one day, stays all-day, and its occurrences fall on 3-7 March.
- FOLLOWING: the new series starts on 4 April as an all-day series, and the old part keeps 1-2 April.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `GET/POST/PATCH /api/v1/calendar/calendars`; `GET /api/v1/calendar/events?from=&to=&calendarIds=` returns expanded occurrences | `CalendarWebTests` (6), `CalendarEventWebTests` (17), `CalendarEventEdgeCaseWebTests` (17), `CalendarSeriesRequestWebTests` (7), DST expansion in `CalendarSeriesWebTests` (15) | `server/gradlew -p server ktlintFormat test --rerun --tests 'ch.sonensei.cockpit.calendar.api.*' --tests '*OpenApiContractTest'` | Pass |
| AC2: `POST/PATCH/DELETE /api/v1/calendar/events/{id}?scope=THIS\|FOLLOWING\|ALL&occurrence=` with correct series splitting/overrides | `CalendarSeriesEdgeCaseWebTests` (45, incl. all TW-2 cases above), `CalendarSeriesWebTests` (15), `CalendarSeriesRequestWebTests` (7), `EventCommandsTest` (31), `CalendarEventControllerRaceTest` (4), `CalendarEventWebTests` | same | Pass |
| AC3: ChangeEvents, search, trash for events | `CalendarEventWebTests`, `CalendarSeriesEdgeCaseWebTests` (change events, search after split, cancelled overrides, Trash), `CalendarWebTests` | same | Pass |
| AC4: `./gradlew test --tests 'ch.sonensei.cockpit.calendar.api.*'` passes (Postgres via Testcontainers) | all 144 tests in `ch.sonensei.cockpit.calendar.api.*` (Testcontainers Postgres via `PostgresTestBase`, Docker available) | same | Pass (144/144) |
| AC5: `api/openapi.yaml` regenerated and `OpenApiContractTest` passes without the flag | `OpenApiContractTest`, `CalendarApiContentTypeTests` | same (without `UPDATE_OPENAPI`) | Pass. Regeneration was not re-run because the TW-2 fix changed no API annotation. |

## Focused test execution

Command:

`server/gradlew -p server ktlintFormat test --rerun --tests 'ch.sonensei.cockpit.calendar.api.*' --tests '*OpenApiContractTest'` (run by the coordinator from the clone root)

Result:

Pass. BUILD SUCCESSFUL with 145 tests, 0 failures, 0 errors and 0 skipped (144 in `calendar.api.*` plus `OpenApiContractTest`). The JUnit XML has 45 tests in `CalendarSeriesEdgeCaseWebTests`, including my new one, all passing. `ktlintFormat` changed nothing beyond my test file.

## Full test execution

Command:

`server/gradlew -p server jacocoTestReport` (run by the coordinator; it reran the full server `test` task)

Result:

Pass. BUILD SUCCESSFUL in 2m9s. The JUnit XML holds 153 suites with 1789 tests, 0 failures, 0 errors and 0 skipped (timestamps 2026-10-20T18:38:15Z to 18:40:22Z). This was the server `test` task, not `./gradlew check`, so lint beyond `ktlintFormat` did not run in this round. Web and Android suites were not run because the task changed no hand-written web or Android code.

## .taskcheck

Result:

Not run in this round, either by me (Gradle execution blocked) or by the coordinator. The parent runs it before `done`.

## Bugs found

None in this round. TW-2 is fixed, and its test now passes for ALL and FOLLOWING.

CR-6 (code-reviewer, P3) is the mirror case: `allDay:false` through a timed override of an all-day series uses the override as the base. The parent accepted it without a fix, and as instructed no failing test was added for it.

## Coverage

Status: verified. This is a fresh, successful run on HEAD d46c83e plus my new test.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All 7 production files were added by this task, so whole-file coverage equals coverage of the task's lines. In the TW-2 fix (`CalendarEventService.kt:292-299`), JaCoCo reports no missed instructions and no partial branches.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `cal-local-api`; base `b1708640253e120e461364a0e5ce82230387e099` (from the parent); HEAD `d46c83e1e0669ee2321863e1fe7c78cc2874ec2d`; scope from `git diff --name-status b1708640..HEAD -- server/src/main web/src android/app/src/main api`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD d46c83e plus the modified `CalendarSeriesEdgeCaseWebTests.kt`, sha256 `a7e6a6ce8b1e8c72a5c1fbbc0a185542786f06bf1eb3bb18c04c24536f77068a` (hashed after the run). The coordinator reports that `git status` showed only that test file and the reports as modified, so no production input changed.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/{CalendarController,CalendarDtos,CalendarEventController,CalendarEventService,CalendarService,EventCommands,EventTrashContributor}.kt`, all added (A) by the task. Excluded: `api/openapi.yaml` (a generated contract with no executable code, checked by `OpenApiContractTest`) and `web/src/app/api/generated/**` (a generated client, not task-authored logic). No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin, project default version). Command `server/gradlew -p server jacocoTestReport`, run from the clone root, with the full server `test` task as the test selection. Counters are the `sourcefile` counters of package `ch/sonensei/cockpit/calendar/api`.
- Run start/completion time and exit status: tests ran from 2026-10-20T18:38:15Z to 18:40:22Z, and the report was written at 18:40:23Z. Exit status: BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` (written 18:40:22Z), sha256 `1426666b0569ffc2c41316053deca61582f081c0116076396821fd31a66eae09`. `server/build/reports/jacoco/test/jacocoTestReport.xml` (written 18:40:23Z), sha256 `55df1f63a218784b22d1de2aa0f96d5cbd9b02caa20837e5677bf9c32a2310fd`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Both artifacts were written by this run, after HEAD d46c83e was committed (18:35:46Z). The JUnit XML includes the new test, and the per-file counters I parsed match the coordinator's numbers.
- Final run after latest test additions and production fixes: yes.

Values below use covered/total (percentage). "Statements" are JaCoCo instructions, because JaCoCo has no statement counter.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarController.kt` | 362/366 (98.9%) | 25/26 (96.2%) | 10/11 (90.9%) | 40/40 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarDtos.kt` | 1411/1505 (93.8%) | 34/40 (85.0%) | 104/122 (85.2%) | 213/213 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventController.kt` | 653/659 (99.1%) | 35/36 (97.2%) | 12/14 (85.7%) | 72/73 (98.6%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt` | 2820/2858 (98.7%) | 325/338 (96.2%) | 49/51 (96.1%) | 341/344 (99.1%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarService.kt` | 496/525 (94.5%) | 44/46 (95.7%) | 22/23 (95.7%) | 64/65 (98.5%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/EventCommands.kt` | 1098/1192 (92.1%) | 119/128 (93.0%) | 47/55 (85.5%) | 140/141 (99.3%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/EventTrashContributor.kt` | 199/211 (94.3%) | 24/28 (85.7%) | 6/7 (85.7%) | 31/31 (100.0%) |
| **Aggregate (7 files, summed counters)** | 7039/7316 (96.2%) | 606/642 (94.4%) | 250/283 (88.3%) | 901/907 (99.3%) |

Aggregate scope: all 7 task production files were measured, and none is missing. Excluded: `api/openapi.yaml` and `web/src/app/api/generated/**` (generated, see above).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarEventService.kt:292-299` (`seriesBase`) | TW-2 fix: ALL and FOLLOWING, `allDay` true, false and absent, both series kinds | Fully covered (no missed instructions or branches). The new mirror test was added. CR-6 was accepted by the parent, so no test. |
| `CalendarEventService.kt:528` | Override addressed while its master is not active | Unreachable since the TW-1 fix, because `load` already returns 404. The behavior is covered by `an override of a trashed series is not found`. Justified gap. |
| `CalendarEventService.kt:228-229` | Rethrow of an integrity violation when updating an existing override | Defensive code. The 409 path for a new override is tested. Without fault injection there is no realistic trigger. Justified gap. |
| `CalendarEventService.kt:87`, `:114` (partial branches) | An empty `calendarIds` list falls back to the visible calendars; missing title | The fallback has no stated requirement. A missing title is rejected first by bean validation, which is tested. Low risk. Justified gap. |
| `CalendarEventService.kt:494, 500, 571, 656, 679` (partial branches) | Kotlin null-safe and lambda branches in rule parsing, `isGenerated` and the URL check | Generated bytecode branches. The behaviors themselves are tested. Justified gap. |
| `CalendarEventService.kt:375, 382, 692` | Default-argument bridge of `carryExceptions`; companion initializer | Synthetic Kotlin code. Justified gap. |
| `EventCommands.kt:236-242` (partial branches) | `propagate` for overrides whose description, attendees, reminders, transparency or URL diverged | The same rule applies to every field. Title, location and status are tested, and so is the CR-5 line. Low risk. Justified gap. |
| `EventCommands.kt:25-37, 157, 160, 164, 183, 202, 208-214` | Data-class synthetics; `Timing.resolve`/`compareTo` branches for mixed kinds | Synthetic code, or a documented invariant (one kind per series). `EventCommandsTest.Resolve` covers the resolve paths. Justified gap. |
| `CalendarService.kt:25-30, 119`; `CalendarDtos.kt:244-263`; `CalendarController.kt:158, 174`; `CalendarEventController.kt:225, 254, 273`; `EventTrashContributor.kt:31, 41, 48, 55, 65` | Default constructors, companions, the `"body"` fallback for Jackson errors, Trash paths for override ids | Unchanged since the earlier rounds. Synthetic code, or paths that no API input reaches (overrides are never trashed on their own). Justified gap. |

## Unable to test

- `.taskcheck` and `./gradlew check` (lint and static checks) were not run in this round. The parent runs `.taskcheck` before `done`.
- I could not run Gradle myself in this session because of the permission system. All execution results above come from the coordinator's runs, and I checked them read-only against the artifacts.
- CR-6 has deliberately no test (accepted by the parent).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. TW-2 is fixed and covered for ALL and FOLLOWING with `allDay` true, false and absent. One new test covers the mirror case (`allDay:true` through a timed override of an all-day series). The focused run passed 145/145 and the full server `test` task passed 1789/1789. Task-scoped coverage is 99.3% lines (901/907) and 94.4% branches (606/642), with the TW-2 code fully covered.
