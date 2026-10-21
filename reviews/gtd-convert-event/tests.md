# Test Report

Date: 2026-10-21

Task: gtd-convert-event - Convert task to calendar event

Scope: task commit `8caa872feffb535804d6a4c29dee6b28bf0b306e` (HEAD) on base `a8fe02662740b1b60542fb4cd96988739c8533ef` (`git diff a8fe0266..8caa872f`), plus two new, uncommitted test files (listed below). Production files changed by the task: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/api/TaskToEventController.kt` and `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/domain/TaskToEventService.kt`. The generated files the task also changed (`api/openapi.yaml`, `web/src/app/api/generated/`) are checked by `OpenApiContractTest` and are not counted in coverage.

Requirements:
- `tasks/gtd-convert-event.md`: acceptance criteria and the recorded assumptions (the time fields are the same as createEvent; `calendarId` is optional and defaults to the default calendar; event title is the task title; description is the task notes, or none when they are blank)
- SPEC.md §5.3 ("To calendar" in inbox processing) and §4.2 (Problem Details, ETag/If-Match)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`
- Code-review finding CR-1 from the parent: a 409 for a read-only calendar and a 400 for a missing default calendar must leave the task intact

Execution note: the test-writer sub-agent was not allowed to run Gradle in this environment, so the parent worker ran every Gradle command below. The test-writer checked the results itself by reading the fresh JUnit XML and JaCoCo XML files that the run produced.

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert/TaskToEventEdgeCaseTests.kt` (new, untracked; written in an earlier, interrupted test-writer run, then reviewed and kept as is)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert/TaskToEventRollbackTests.kt` (new, untracked; same history)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert/TaskToEventWebTests.kt` (part of the task commit; not changed by the test-writer)

## Tests added or updated

Count: 23 (22 in `TaskToEventEdgeCaseTests`, 1 in `TaskToEventRollbackTests`). The task commit already had 9 tests in `TaskToEventWebTests`.

## Requirement coverage

Command F = `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*' jacocoTestReport` (the parent ran it at 16:27 CEST; BUILD SUCCESSFUL).
Command O = `UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` and then `./gradlew test --tests '*OpenApiContractTest'` (the parent ran both).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: creates the event (title, notes as description, calendar, timed and all-day, Location, ETag) | Web: `creates a timed event from title and notes and moves the task to the Trash`, `creates an all-day event`, `without a calendar it goes to the default calendar`; Edge: `the ETag and the body describe the new event as GET returns it`, `whitespace-only notes give no description`, `a timed event without end lasts one hour, a given time zone is kept`, `a completed task converts like an open one`, `a synced calendar gets the event through its provider, the task goes to the Trash` | F | Pass |
| AC1: keeps links (direction, label, made manual; links to missing items skipped; types without a resolver kept) | Web: `carries links over in their direction with their label`; Edge: `links to other tasks are carried over, links to trashed tasks are skipped, unresolvable types are kept`, `restoring the converted task from the Trash brings it back with its links and keeps the event` | F | Pass |
| AC1: deletes the task (to the Trash, out of search, change events) | Web: timed-event test above; Edge: `the task leaves the search index and the event enters it with the notes as body`, `announces the new event as CREATED and the task as DELETED`, `converting twice gives one event, the second call is not found` | F | Pass |
| AC1: one transaction, a failure changes nothing | Web: `invalid times change nothing`, `an unknown calendar changes nothing`; Edge: `a conversion that fails after the task was trashed leaves the search index untouched`, `a failing provider is a bad gateway and rolls back the event, the task deletion and the copied links`, `an unknown time zone or an all-day end before its start changes nothing`, `a missing or malformed body changes nothing`; Rollback: `a failing link copy rolls back the event, the task deletion and the search index` | F | Pass |
| CR-1: a read-only calendar (409) and a missing default calendar (400) leave the task intact | Edge: `a read-only local calendar is a conflict and changes nothing`, `a read-only synced calendar is a conflict and nothing reaches the provider`, `a calendar of an external source without sync support is a conflict`, `without a calendar and without a default calendar it is a bad request and changes nothing`. Each one ends with `assertUnchanged(task)` (task `deleted_at` is still null and `calendar_event` has no rows). No new assertion was needed. | F | Pass |
| If-Match (SPEC §4.2) | Web: `a stale If-Match changes nothing, a matching one converts`; Edge: `If-Match star, a blank header and a list containing the current tag convert`, `a weak If-Match tag never matches` | F | Pass |
| 404 for unknown and trashed tasks, 400 for a malformed id, auth and CSRF | Web: `unknown and trashed tasks are not found`, `requires authentication`; Edge: `a malformed task id is a bad request`, `without a CSRF token nothing is converted` | F | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*'` passes (Testcontainers Postgres) | all 58 tests in the package | F | Pass |
| AC3: `api/openapi.yaml` regenerated, and `OpenApiContractTest` passes without the flag | `OpenApiContractTest` | O | Pass (regenerating left no diff in `api/` or `web/`, as reported by the parent) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*' jacocoTestReport` (the parent ran it on 2026-10-21 at about 16:27 CEST)

Result:

Pass. The JUnit XML in `server/build/test-results/test/` (files written at 16:27:35 +0200) gives: TaskToEventEdgeCaseTests 22 tests, 0 failures, 0 errors, 0 skipped; TaskToEventRollbackTests 1/0/0/0; TaskToEventWebTests 9/0/0/0; TaskToNoteEdgeCaseTests 17/0/0/0; TaskToNoteRollbackTests 1/0/0/0; TaskToNoteWebTests 8/0/0/0. That is 58 tests, none failing.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run. The test-writer was not allowed to run Gradle, and the parent did not report a full-suite run. The parent runs the full suite as part of `.taskcheck` before `done`.

## .taskcheck

Result:

Not run by the test-writer (no permission to run builds). `sh scripts/check.sh` is left to the parent.

## Bugs found

None. No test failed, and reading the production code against the acceptance criteria turned up no defect.

## Coverage

Status: verified (whole-file coverage of the files the task changed, from a fresh run on the current snapshot)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured as a separate number. Both production files are new in this task, so every executable line in them is a changed line, and the whole-file numbers equal the changed-line numbers.

Evidence:
- Task id, base, measured HEAD and file scope: gtd-convert-event; base `a8fe02662740b1b60542fb4cd96988739c8533ef`; HEAD `8caa872feffb535804d6a4c29dee6b28bf0b306e`. File scope comes from `git diff --name-only a8fe0266..HEAD -- src/main` and matches the task's `touches`. The task is a single commit.
- Source and test snapshot (sha256, taken before the run and checked again after it; unchanged): `TaskToEventController.kt` 73f339f0c84ab00f431cb57d8e1448f8015e26fb40012e3b46bf0aae5bf4cdca; `TaskToEventService.kt` 72f33c76b61dd29e8effa0780cebc916bad265ceb206bb548c88e84b2b86fdee; `TaskToEventEdgeCaseTests.kt` (untracked) 9ce0d775510a4e5437c8fc0a0eeac9e3da1212b398a10dc43fb2c3bb280d9d40; `TaskToEventRollbackTests.kt` (untracked) 821231b7edc96e309f565df6e23e046dc4d59a22dae19f4107137e6604ee929c; `TaskToEventWebTests.kt` 2783b7fc0311fc8304454c45d0a208ac72cb8a546274bda2b48113a71c4d0e7e.
- Included files: the two production files above. Nothing was renamed, deleted or excluded. The generated `api/openapi.yaml` and `web/src/app/api/generated/` are not executable server code, so they are out of coverage scope (AC3 covers them).
- Provider, command, directory and tests run: JaCoCo through the Gradle `jacoco` plugin (version not pinned, Gradle default); `./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*' jacocoTestReport` in `server/`; tests run: the `ch.sonensei.cockpit.gtd.convert` package (58 tests). The parent ran it.
- Run time and exit status: about 16:27 CEST on 2026-10-21, BUILD SUCCESSFUL (exit 0). The JUnit suites have timestamps from 14:27:13Z to 14:27:35Z.
- Report path and hash: `server/build/reports/jacoco/test/jacocoTestReport.xml` (written 16:27:37 +0200), sha256 f9898f56fbbbf5e4f32250f2c90857acc334e32594efc9c775ef7249d82aa3ab.
- Freshness: the report and the JUnit XML are newer than the last change to any input file (16:26:01). An older report from 16:16 came from an earlier version of the code and was not used. The snapshot hashes were the same after the run.
- Final run after the latest test additions and production fixes: yes. The test-writer changed no files after this run, and no production fixes have been made.

Values below use covered/total (percentage). JaCoCo has no statement counter, so the Statements column shows JaCoCo INSTRUCTION counters.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/domain/TaskToEventService.kt` | 215/215 (100%) | 18/18 (100%) | 3/3 (100%) | 29/29 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert/api/TaskToEventController.kt` | 157/175 (89.7%) | 6/6 (100%) | 7/13 (53.8%) | 28/28 (100%) |
| Aggregate (summed counters, both files measured, none missing) | 372/390 (95.4%) | 24/24 (100%) | 10/16 (62.5%) | 57/57 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TaskToEventController.kt:39-44`: the getters `getAllDay`, `getStart`, `getEnd`, `getTimeZone`, `getStartDate`, `getEndDate` of the `ConvertToEventRequest` data class (3 instructions each, 18 in total) | None. Jackson builds the request through its constructor, and `timing()` reads the backing fields directly, so nothing in production calls these getters. The lines themselves count as covered (constructor code). All request fields are exercised through the endpoint. | Not material. No test added: calling the getters directly would only raise the number and verify no behavior. |

## Unable to test

- Full server suite and `.taskcheck`: the test-writer had no permission to run builds. The parent runs them before `done`. This is follow-up for the parent, not a gap in tests.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The 23 tests from the earlier test-writer run were reviewed and kept, and all 58 tests in the package pass on the current snapshot (parent's run). CR-1 was already covered by `assertUnchanged(task)` in the read-only-calendar and missing-default-calendar tests, so no test change and no rerun are needed for it.
