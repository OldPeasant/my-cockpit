# Test Report

Date: 2026-10-20

Task: cal-local-api - Calendar API for local calendars

Scope: base `9e2d0743e12cb9df3e5e16f10f113cdb9fbceec2` to HEAD `0855287f060a7770a87c62e5c147d112288f0c8b` (branch `main`, merge of `origin/wip/cal-local-api`). Task commits: a8cae2c (implementation), cc50c8f (API tests, openapi.yaml, web client), 7ac3610 (earlier test-writer edge-case tests), addf13a (fixes CR-1..CR-3, TW-1), e64ad1c (more edge-case and content-type tests). The parent supplied the base and commit list; `git log 9e2d0743..HEAD -- server/src/main/kotlin/ch/sonensei/cockpit/calendar/api server/src/test/kotlin/ch/sonensei/cockpit/calendar/api` lists exactly these 5 commits. Uncommitted at measurement: one new untracked test file, `CalendarSeriesRequestWebTests.kt` (this report's addition). No production file was uncommitted.

Requirements:
- `tasks/cal-local-api.md`: 5 acceptance criteria and the recorded ASSUMPTION notes (scope defaults, occurrence format, FOLLOWING/ALL time semantics, DELETE semantics, range rules)
- SPEC.md §6.4 (calendar API), §4.2 (ETag/If-Match, Problem Details) as referenced by the task
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md project notes
- `reviews/cal-local-api/review.md` and `fixes.md` (earlier review and fix rounds)
- Parent instructions: check existing tests against the criteria, add only meaningful gaps

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/api/CalendarSeriesRequestWebTests.kt` (new)

## Tests added or updated

Count: 7 (all new, in `CalendarSeriesRequestWebTests`)

Review of the existing 125 tests (`CalendarWebTests`, `CalendarEventWebTests`, `CalendarEventEdgeCaseWebTests`, `CalendarSeriesWebTests`, `CalendarSeriesEdgeCaseWebTests`, `CalendarEventControllerRaceTest`, `CalendarApiContentTypeTests`, `EventCommandsTest`, `OpenApiContractTest`): they cover every criterion in depth (calendar CRUD and default flag, range expansion incl. DST, 400-day limit and visibility, THIS/FOLLOWING/ALL edits and deletes incl. COUNT/UNTIL, all-day, moved overrides (CR-1), calendar moves, change events, search re-indexing, Trash list/restore/purge, idempotent create races, content types). The gaps I found and closed:

| New test | Gap closed |
|---|---|
| `series changes and deletes check the If-Match of the addressed event and change nothing on a mismatch` | If-Match was only tested on single events. Now: stale ETag on PATCH ALL/THIS/FOLLOWING and DELETE THIS of a series master gives 412, a missing one on DELETE FOLLOWING 428; an override is checked against its own version (the master's ETag gives 412); nothing changes on a mismatch; the override's own ETag succeeds. |
| `an unknown scope and malformed range parameters are validation problems` | Invalid `scope` enum, a malformed `calendarIds` UUID and a malformed `from` are 400 Problem Details naming the parameter (not 500). |
| `the calendarIds parameter may be repeated or comma-separated` | The documented `calendarIds` forms (comma-separated, repeated), filtering to one calendar, an unknown calendar id lists nothing. |
| `unknown and trashed events cannot be changed or deleted` | PATCH/DELETE of an unknown id and of a trashed series (with THIS, FOLLOWING and ALL) are 404. |
| `an override moved out of its series' span is listed where it now is, not where it was` | Range expansion of an override moved past the end of a COUNT series: listed in the new range with its original `occurrence`, absent from its old slot. |
| `DELETE FOLLOWING through an override ends the series before its occurrence` | FOLLOWING addressed through an override id (scope given, occurrence implied) for DELETE. |
| `PATCH FOLLOWING through an override splits the series there` | The same for PATCH: the split happens at the override's occurrence, the override moves to the new series and keeps its own field. |

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `GET/POST/PATCH /api/v1/calendar/calendars`; `GET /api/v1/calendar/events?from=&to=&calendarIds=` returns expanded occurrences | `CalendarWebTests` (6), `CalendarEventEdgeCaseWebTests` range/calendar tests, `CalendarSeriesWebTests.a series is expanded at its wall-clock time across DST...`, `CalendarEventWebTests.GET returns the occurrences...`, `GET rejects a missing, inverted or too long range`; new: `the calendarIds parameter may be repeated or comma-separated`, `an unknown scope and malformed range parameters are validation problems`, `an override moved out of its series' span...` | `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.api.*' --tests '*OpenApiContractTest' jacocoTestReport` | Pass |
| AC2: `POST/PATCH/DELETE /api/v1/calendar/events/{id}?scope=THIS\|FOLLOWING\|ALL&occurrence=` with correct series splitting/overrides | `CalendarEventWebTests`, `CalendarSeriesWebTests` (15), `CalendarSeriesEdgeCaseWebTests` (34), `EventCommandsTest` (29), `CalendarEventControllerRaceTest` (4); new: `series changes and deletes check the If-Match...`, `unknown and trashed events cannot be changed or deleted`, `DELETE FOLLOWING through an override...`, `PATCH FOLLOWING through an override...` | same | Pass |
| AC3: ChangeEvents, search, trash for events | `CalendarEventWebTests` (POST/PATCH announce and index, DELETE/restore, purge with overrides and links, global search), `CalendarSeriesEdgeCaseWebTests` (change events of occurrence deletes and ALL propagation, search after split, cancelled overrides leave search, trashed series with overrides listed once/restored, overrides of trashed series 404), `CalendarWebTests` (CALENDAR change events) | same | Pass |
| AC4: `./gradlew test --tests 'ch.sonensei.cockpit.calendar.api.*'` passes (Postgres via Testcontainers) | all 131 tests in `ch.sonensei.cockpit.calendar.api.*` (Testcontainers Postgres via `PostgresTestBase`) | same | Pass |
| AC5: `api/openapi.yaml` regenerated and `OpenApiContractTest` passes without the flag | `OpenApiContractTest`, `CalendarApiContentTypeTests.the contract documents successful calendar responses as JSON only` | same (without `UPDATE_OPENAPI`) | Pass. Regeneration with `UPDATE_OPENAPI=1` was not re-run by me (no production or API change in this round). |

## Focused test execution

Command:

`cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.api.*' --tests '*OpenApiContractTest' jacocoTestReport`

Result:

Pass. 132 tests, 0 failures, 0 skipped (CalendarApiContentTypeTests 2, CalendarEventEdgeCaseWebTests 17, CalendarEventControllerRaceTest 4, CalendarEventWebTests 17, CalendarSeriesEdgeCaseWebTests 34, CalendarSeriesWebTests 15, CalendarSeriesRequestWebTests 7, CalendarWebTests 6, EventCommandsTest 29, OpenApiContractTest 1). Before my addition the same selection ran 125 tests, 0 failures.

Lint: `cd server && ./gradlew ktlintCheck` - Pass (after one line-wrapping fix in the new test file).

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Fail: 1711 tests, 1 failed, outside this task: `ch.sonensei.cockpit.core.reminders.ReminderServiceEdgeCaseTests > deleting a reminder while the scheduler fires it succeeds instead of failing on its version()` (`expected:<(DELETED, ...)> but was:<(CREATED, ...)>`). Every other test, including all calendar tests, passed. Re-run alone (`./gradlew test --tests '*ReminderServiceEdgeCaseTests'`) it passes, so it is order-dependent or timing-dependent within the full suite. It is not in this task's code (`core/reminders`), and the task notes record the same failure on 2026-10-20 before this round. Not counted as a defect of this task; the parent needs to resolve it (or find its owning task) before `.taskcheck` can pass.

## .taskcheck

Result:

Fail: `sh scripts/check.sh` stopped at the server test step with the same single failure (`ReminderServiceEdgeCaseTests`, 1711 tests, 1 failed). Run took about 2.5 minutes (17:59:31Z to 18:01:59Z). The web and android steps after it did not run.

## Bugs found

None in the task scope. All 7 new tests passed on the first run against HEAD `0855287`.

Out of scope, for the parent: the order-dependent `ReminderServiceEdgeCaseTests` failure above (not a finding of this task, no TW id assigned).

## Coverage

Status: verified (fresh run, exit status 0, all scoped files measured).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All 7 production files were added by this task (the diff from the base has no pre-existing lines in them), so the whole-file numbers equal changed-line coverage for these files.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `cal-local-api`; base `9e2d0743e12cb9df3e5e16f10f113cdb9fbceec2` (from the parent); measured HEAD `0855287f060a7770a87c62e5c147d112288f0c8b`; task commits a8cae2c, cc50c8f, 7ac3610, addf13a, e64ad1c (parent list, confirmed by `git log` on the task's `touches` paths); production scope from `git diff --name-only 9e2d0743..HEAD -- server/src/main web/src android/app/src/main api`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `0855287f` plus untracked `server/src/test/kotlin/ch/sonensei/cockpit/calendar/api/CalendarSeriesRequestWebTests.kt` sha256 `096d01a220ecce4218bf761ec313a56d5d28ca5f4880c881bf0355f6a18e49dc` (same hash before and after the run). No other uncommitted changes.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/{CalendarController,CalendarDtos,CalendarEventController,CalendarEventService,CalendarService,EventCommands,EventTrashContributor}.kt`. Excluded: `api/openapi.yaml` (generated contract, no executable code; checked by `OpenApiContractTest`) and `web/src/app/api/generated/**` (generated client, regenerated by `npm run api:gen`; not task-authored logic). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (project default tool version; HTML report footer says only "JaCoCo"), Gradle 9.8.1; `./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.api.*' --tests '*OpenApiContractTest' jacocoTestReport` in `server/`. `--rerun` forces a fresh test run (without it, Gradle reported `:test UP-TO-DATE` from an earlier run).
- Run start/completion time and exit status: 2026-10-20 17:56:23Z to 17:56:50Z, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 `bc2594e94e6b513295cc237c7a652be847f579595fb42e8dd09b27c59df753ba`; `server/build/jacoco/test.exec` sha256 `cb47e6efc7a46fafb33797afd8a8a0de2d5c291c9a95bbd300610b9ec1d6933c`; both written 19:56 local (17:56Z) by this run. Counters were read from the XML `sourcefile` element counters of package `ch/sonensei/cockpit/calendar/api`.
- Evidence outputs are fresh and inputs stayed unchanged: yes (forced rerun; both files rewritten; the new test file's hash is unchanged; no production edits).
- Final run after latest test additions and production fixes: yes. An earlier fresh run before my addition (17:53Z, 125 tests) gave identical counters: the new tests add behavioral assertions on lines that were already executed.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" are JaCoCo instructions (JaCoCo has no statement counter).

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarController.kt` | 362/366 (98.9%) | 25/26 (96.2%) | 10/11 (90.9%) | 40/40 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarDtos.kt` | 1411/1505 (93.8%) | 34/40 (85.0%) | 104/122 (85.2%) | 213/213 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventController.kt` | 653/659 (99.1%) | 35/36 (97.2%) | 12/14 (85.7%) | 72/73 (98.6%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt` | 2776/2814 (98.6%) | 317/330 (96.1%) | 48/50 (96.0%) | 338/341 (99.1%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarService.kt` | 496/525 (94.5%) | 44/46 (95.7%) | 22/23 (95.7%) | 64/65 (98.5%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/EventCommands.kt` | 1094/1188 (92.1%) | 117/126 (92.9%) | 47/55 (85.5%) | 140/141 (99.3%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/EventTrashContributor.kt` | 199/211 (94.3%) | 24/28 (85.7%) | 6/7 (85.7%) | 31/31 (100.0%) |
| **Aggregate (7 files, summed counters)** | 6991/7268 (96.2%) | 596/632 (94.3%) | 249/282 (88.3%) | 898/904 (99.3%) |

Aggregate scope: all 7 task production files measured; none missing. Excluded: `api/openapi.yaml` and `web/src/app/api/generated/**` (generated, see above).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarEventService.kt:512` | Override addressed while its master is not active | Unreachable since the TW-1 fix: `load` (line 593) already returns 404 for such an override. Covered behaviorally by `an override of a trashed series is not found` and the new `unknown and trashed events cannot be changed or deleted`. Justified gap. |
| `CalendarEventService.kt:228-229` | Rethrow of an integrity violation when updating an existing override | Defensive; the 409 path for a new override is tested (`a THIS change racing another one for the same occurrence is a conflict`). No realistic trigger without fault injection. Justified gap. |
| `CalendarEventService.kt:87` (empty `calendarIds` list branch), `:114` (null title value branch) | Empty `calendarIds` falls back to visible calendars; missing title | No test sends an empty `calendarIds` (the behavior is a fallback with no stated requirement; not tested by me). A missing title is tested at the HTTP level (`POST without a title is rejected`), where bean validation (`@NotNull`) answers before the service check. Low risk. Justified gap. |
| `CalendarEventService.kt:478, 484, 555, 640, 663` (partial branches) | Kotlin null-safe/lambda branches in rule parsing, `isGenerated` and URL check | Generated bytecode branches (e.g. `?.` on non-null values, `runCatching` failure of `URI` for inputs the earlier checks already reject). The behaviors are tested (`rules need FREQ...`, `conference URLs must be http(s)...`). Justified gap. |
| `CalendarEventService.kt:359, 366, 676` | Default-argument bridge of `carryExceptions` and the companion initializer | Synthetic Kotlin code. Justified gap. |
| `EventCommands.kt:25-37`, `CalendarService.kt:25-30`, `CalendarDtos.kt:244-263` | Default constructors / default-argument synthetics of `EventChanges`, `CalendarChanges`, `CreateEventRequest` | Synthetic Kotlin data-class code (Jackson uses the full constructor). Justified gap. |
| `EventCommands.kt:157, 160, 164, 183, 202, 208-214, 233-239` | `Timing.resolve` branch for `current == null` while turning all-day, `compareTo` mixed kinds, `EventContent` data-class synthetics | Mixed-kind comparison cannot happen within one series (documented invariant); the resolve paths are tested in `EventCommandsTest.Resolve` (13 tests). Remaining misses are data-class `copy`/`componentN`. Justified gap. |
| `CalendarService.kt:119` (2 of 8 branches) | `makeDefault` when the calendar is already the default | Tested functionally (`PATCH changes the given fields and moves the default flag`); the missing branches are null-safe bytecode branches. Justified gap. |
| `EventTrashContributor.kt:31, 41, 48, 55, 65` | Trashed overrides filtered from the Trash list; restore/purge of an override id or a non-deleted id returns false | Overrides are never soft-deleted on their own (they share the master's state), so the override filter has no input that reaches it through the API; `checkNotNull` and the companion are synthetic. Restore/purge of masters is tested. Low risk. Justified gap. |
| `CalendarEventController.kt:225, 254, 273`; `CalendarController.kt:158, 174` | Jackson exception without a property path (`"body"` fallback); companion initializers | Fallback for a malformed body whose path has no property name; PATCH bodies are already parsed into a `Map`, so conversion errors always carry a field. Companions are synthetic. Justified gap. |

## Unable to test

- Regeneration of `api/openapi.yaml` (`UPDATE_OPENAPI=1 ... OpenApiContractTest`) was not re-run in this round, because nothing in the API changed. The check without the flag passed. The parent ran the regeneration in the fix round (fixes.md).
- `.taskcheck` and the full server suite could not pass because of `ReminderServiceEdgeCaseTests` (outside this task; order-dependent, passes alone). Follow-up needed by the parent before `tasks.py done`.

## Summary

Tests added and all task-scoped validation passed; no production defects found in the task scope. Coverage of the 7 task files is high (99.3% lines, 94.3% branches), and the remaining gaps are justified above. The full server suite and `.taskcheck` fail on one order-dependent test outside this task (`core.reminders.ReminderServiceEdgeCaseTests`). The parent must resolve that before completing the task.
