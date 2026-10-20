# Test Report

Date: 2026-10-20

Task: reminders-core - Reminders and notifications backend

Scope: `d26c6d66e4d949160af48f66baf8a0b094846ab2..d12c4ec111a4484ae20b99a4b4213407c123abab` (one task commit
`d12c4ec`, base and commit supplied by the parent worker; branch `main`, working tree clean before testing). Production
files: the five `core/reminders` Kotlin files, the Flyway migration, `core/events/domain/ClientNotification.kt` and
the `SseEventHub.kt` change. Generated `api/openapi.yaml` and `web/src/app/api/generated/**` are out of test scope.

Requirements:
- `tasks/reminders-core.md` acceptance criteria and the four recorded assumptions (SSE `reminder` event via
  `ClientNotification`, channel `NOTIFICATION`, scheduler polling with `SKIP LOCKED`, API shape incl. `GET /{id}`,
  `upcoming` limit 1000, `ItemDeletedEvent` cleanup)
- `SPEC.md` §4.6 (reminders), §4.2 (API conventions: Problem Details, UUIDv7, ISO-8601)
- `docs/ARCHITECTURE.md` "Coverage" and "Persistence conventions"; `AGENTS.md` project notes
- Parent worker instructions (MockMvc API, scheduler wiring, SSE delivery after commit, restart, rollback)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/reminders/ReminderWebTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/reminders/ReminderEventsStreamTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/reminders/ReminderSchedulerTests.kt` (new; classes
  `ReminderSchedulerRunTests`, `ReminderSchedulerEnabledTests`, `ReminderSchedulerDisabledTests`)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/reminders/ReminderServiceEdgeCaseTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/events/ClientNotificationTests.kt` (new; classes
  `ClientNotificationHubTests`, `ClientNotificationStreamTests`)

No existing test was modified, weakened, skipped or deleted. No production code was changed.

## Tests added or updated

Count: 40 (ReminderWebTests 12, ReminderEventsStreamTests 3, ReminderServiceEdgeCaseTests 14,
ReminderSchedulerRunTests 3, ReminderSchedulerEnabledTests 1, ReminderSchedulerDisabledTests 2,
ClientNotificationHubTests 3, ClientNotificationStreamTests 2). The 5 existing `ReminderServiceTests` were kept.

## Requirement coverage

Focused command (F): `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.reminders.*' --tests 'ch.sonensei.cockpit.core.events.*'`
(Postgres 16 via Testcontainers, `MutableClock` test clock, fake item resolvers).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `reminder` table + POST creates (201, Location, UUIDv7, resolved item, default channel, past time allowed, type without resolver accepted) | `POST creates a pending reminder with the resolved item and its location`, `POST accepts an explicit channel, a time in the past and an item type without a resolver` | F | Pass |
| AC1 POST validation (missing itemType/itemId/remindAt, unknown item, bad enum/channel/instant, malformed/empty body -> 400 Problem Details, nothing stored) | `POST rejects missing fields, an unknown item and invalid values` | F | Pass |
| AC1 GET list by item (fired and pending, earliest first, other items excluded, empty list, failing resolver -> unavailable) and 400 for missing/invalid itemType/itemId | `GET lists the reminders of one item, fired or not, earliest first`, `GET of a list with a failing resolver still answers with the item unavailable`, `GET needs a valid itemType and itemId` | F | Pass |
| AC1 GET by id / DELETE (200, 204, 404 unknown, 400 malformed, second DELETE 404) | `GET by id returns the reminder, an unknown id is 404 and a malformed one 400`, `DELETE removes the reminder and a second DELETE is 404`, `get and delete of an unknown reminder are not found` | F | Pass |
| AC1 `GET /upcoming?until=` (unfired only, overdue included, `until` inclusive, 1 microsecond later excluded, empty, 400 without/invalid until, max 1000) | `GET upcoming returns unfired reminders up to until, overdue ones included and the boundary inclusive`, `GET upcoming needs a valid until`, `upcoming returns at most MAX_UPCOMING reminders, earliest first` | F | Pass |
| AC1 security: 401 without login on every endpoint, 403 without CSRF for POST/DELETE | `reminders need a login`, `state-changing requests need the CSRF token` | F | Pass |
| AC2 scheduler fires due reminders once and sets firedAt (exact boundary, overdue after downtime, batch of 100 per call, backlog drained by one run) | `a reminder fires exactly at its time, not a microsecond before`, `an overdue reminder from while the server was down fires once on the next run`, `fireDue fires at most one batch per call, earliest first`, `a backlog larger than one batch fires completely in one run`, `run repeats while a whole batch fired and stops at the first partial batch`, `run with nothing due fires once and stops`, `a failing run is logged instead of thrown, so the next scheduled run retries` | F | Pass |
| AC2 scheduler bean wiring (enabled with `poll-interval=PT0.1S` fires by itself; disabled in tests -> no bean) | `the background scheduler fires a due reminder once and leaves a future one pending` (context closed with `@DirtiesContext`), `without the scheduler enabled no scheduler bean exists` | F | Pass |
| AC2 `reminder` SSE event with resolved item title reaches a connected client, only after commit; separate `change` REMINDER/UPDATED event | `a fired reminder reaches a connected client after the commit as a reminder event with the item title` | F | Pass |
| AC2 rollback -> no notification, reminder stays due and fires exactly once later | `a rolled-back firing sends no reminder event and the reminder fires once later`, `a notification of a rolled-back transaction is dropped, a committed one is sent after the commit` | F | Pass |
| AC2 item gone -> fired silently; resolver failing / no resolver -> event without title | `a reminder of a deleted item sends no reminder event`, `a failing resolver still sends the notification, without a title`, `a reminder of an item type without a resolver fires with a notification without a title` | F | Pass |
| AC3 fires once, not twice after restart (test clock) and never twice under concurrent schedulers (`SKIP LOCKED`) | existing `a fired reminder does not fire again after a restart`, `a reminder another transaction is firing is skipped instead of waited for or fired twice`, `a reminder cannot be marked fired twice` | F | Pass |
| Change events CREATED/UPDATED/DELETED; ItemDeletedEvent removes only that item's reminders; deleted reminder never fires | `create, fire and delete publish CREATED, UPDATED and DELETED change events`, `deleting an item removes only its reminders and an item without reminders publishes nothing`, `a deleted reminder never fires` | F | Pass |
| `ClientNotification` / `SseEventHub.onNotification` (named JSON event to all clients, outside a transaction right away, dropped after destroy, blank/`change` name rejected) | `ClientNotificationHubTests` (3), `a notification published outside a transaction is sent right away` | F | Pass |
| remindAt stored at microsecond precision | `remindAt is stored at microsecond precision` | F | Pass |
| AC3 `./gradlew test --tests 'ch.sonensei.cockpit.core.reminders.*'` passes | all of the above in `core.reminders` | F (3 extra repeated runs with `cleanTest`, all exit 0) | Pass |
| Migration follows the project's layout convention (ARCHITECTURE "Persistence conventions") | existing `MigrationConventionTests.every migration lives in a module folder and is named V yyyyMMddHHmm __ module _ desc` | `./gradlew test` (full) | Fail: TW-1 |
| AC4 `api/openapi.yaml` regenerated, `OpenApiContractTest` passes | existing `OpenApiContractTest` (no new test) | `./gradlew test` (full) | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.reminders.*' --tests 'ch.sonensei.cockpit.core.events.*'`
and `cd server && ./gradlew ktlintCheck`

Result:

Pass. 73 tests in `core.reminders` (40: 35 new + 5 existing) and `core.events` (33: 5 new + 28 existing),
0 failures; repeated three more times with
`cleanTest`, all exit 0. `ktlintCheck` exit 0.

## Full test execution

Command:

`cd server && ./gradlew cleanTest cleanJacocoTestReport test jacocoTestReport` (2026-10-20 15:48:29 to 15:51:01 +02:00)

Result:

Fail: 995 tests, 1 failed: `MigrationConventionTests > every migration lives in a module folder and is named V
yyyyMMddHHmm __ module _ desc()` (TW-1). `jacocoTestReport` did not run because `test` failed.

An earlier full run (15:43:08 to 15:45:20) additionally had a failure in the pre-existing
`EventsStreamTests > an event of a committed transaction reaches a connected client, a rolled-back one does not()`:
the assertion read the stream while the event JSON was half written (`..."op":"` then end of body). It passed in the
final full run and in all focused runs. This is a read-while-writing race in that test's `awaitBody` condition (it
waits for the id, then asserts on later fields), not a production defect; the same race initially hit one of my new
tests, which now wait for a complete event (`data:...\n\n`). Recorded as observation TW-2 (not changed: it is an
existing test outside this scope).

## .taskcheck

Result:

Fail. `sh scripts/check.sh` (15:52:38 to 15:55:26 +02:00): `tfp: completion gate tests` passed; `server: ktlint +
tests` failed with 995 tests, 1 failed (`MigrationConventionTests`, TW-1). The script stops there (`set -e`), so the
web and android steps were not run.

## Bugs found

### Reminder migration violates the migration layout convention and breaks the server check

Finding ID:

`TW-1`

Test:

`ch.sonensei.cockpit.core.persistence.MigrationConventionTests.every migration lives in a module folder and is named V yyyyMMddHHmm __ module _ desc()`

Production source:

`server/src/main/resources/db/migration/core-reminders/V202610201500__core_reminders.sql`

Requirement:

docs/ARCHITECTURE.md "Persistence conventions" / AGENTS.md: migrations live in
`db/migration/MODULE/V{yyyyMMddHHmm}__MODULE_DESC.sql`; the folder must equal the MODULE part of the file
name, which the test matches with `[a-z0-9]+` (no hyphen). `.taskcheck` (`./gradlew check`) must pass for `done`.

Expected behavior:

The folder name equals the module in the file name, e.g. `core/V202610201500__core_reminders.sql` or
`reminders/V202610201500__reminders_reminder.sql` (which one is the parent's call; the task's `touches` names
`db/migration/core-reminders`, so a path change may need an assumption).

Actual behavior:

`AssertionFailedError: core-reminders/V202610201500__core_reminders.sql expected core-reminders but was core`;
`./gradlew test`, `./gradlew check` and `.taskcheck` fail.

Severity:

High (blocks `.taskcheck` and therefore `tasks.py done`).

Fix required:

`Yes - production code must be fixed by the parent worker / fixer.`

### Observation: pre-existing SSE stream test reads a half-written event

Finding ID:

`TW-2`

Test:

`ch.sonensei.cockpit.core.events.EventsStreamTests.an event of a committed transaction reaches a connected client, a rolled-back one does not()`

Production source:

None (test-side race in `EventsStreamTests.awaitBody` usage).

Requirement:

Deterministic tests.

Expected behavior:

The test waits for the complete event before asserting on its fields.

Actual behavior:

Failed once in 1 of 2 full runs and passed in every other run; the body ended mid-JSON.

Severity:

Low.

Fix required:

No production fix. Optional test hardening outside this task's scope (wait for `\n\n` after the event), for the
parent to decide.

## Coverage

Status: verified for the task test selection (fresh run, exit 0). Whole-suite coverage unavailable: the full run
exits 1 because of TW-1, so JaCoCo produced no report for it.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 173/176 executable added lines (98.3%), 42/44 branches on added lines (95.5%); method:
JaCoCo XML per-line `ci`/`mb`/`cb` counters intersected with added lines of `git diff -U0 d26c6d6..d12c4ec` per
file (script `.tmp/tw_changed_lines.py`, Git-ignored); removed and non-executable lines are not counted.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `reminders-core`; base
  `d26c6d66e4d949160af48f66baf8a0b094846ab2`; HEAD `d12c4ec111a4484ae20b99a4b4213407c123abab`; task commit `d12c4ec`
  and base supplied by the parent, confirmed with `git diff --stat d26c6d6..HEAD` (only reminder, events and
  generated API files).
- Source/test snapshot identifier (including uncommitted inputs): HEAD `d12c4ec` with no production changes, plus
  untracked tests (sha256 prefix): `ReminderEventsStreamTests.kt` a36b9f23971609ff, `ReminderSchedulerTests.kt`
  725893b376d02f15, `ReminderServiceEdgeCaseTests.kt` 997335201480aa41, `ReminderServiceTests.kt` (committed)
  ba1813184f317f77, `ReminderWebTests.kt` 630f758e6bbcccb8, `ClientNotificationTests.kt` d1452850c3b03d76.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/api/ReminderController.kt`,
  `.../reminders/domain/Reminder.kt`, `.../reminders/domain/ReminderService.kt`,
  `.../reminders/infra/ReminderRepository.kt`, `.../reminders/infra/ReminderScheduler.kt`,
  `.../core/events/domain/ClientNotification.kt`, `.../core/events/infra/SseEventHub.kt`. Excluded: the SQL migration
  (not instrumentable by JaCoCo; exercised by Flyway in every Testcontainers test), `api/openapi.yaml` and
  `web/src/app/api/generated/**` (generated), `server/src/test/**`. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (default
  tool version of the project's Gradle 9.8.1; not pinned in `build.gradle.kts`);
  `./gradlew cleanTest cleanJacocoTestReport test --tests 'ch.sonensei.cockpit.core.reminders.*' --tests 'ch.sonensei.cockpit.core.events.*' jacocoTestReport`
  in `server/`.
- Run start/completion time and exit status: 2026-10-20 15:51:09 to 15:51:39 +02:00, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (written 15:51:38, sha256 d8abe73bd00a449b06e797bf295b0ad36a3598acf6c6b31132d1ad80adff247b); report was cleaned
  first with `cleanJacocoTestReport`.
- Evidence outputs are fresh and inputs stayed unchanged: yes; test file hashes and `git status` identical before
  and after the run; no production file changed.
- Final run after latest test additions and production fixes: yes for the tests (no test changed after the run);
  no production fix has been made yet, so the numbers describe the pre-fix revision `d12c4ec`. A TW-1 fix (moving or
  renaming the SQL file) does not change any instrumented Kotlin file, but evidence must be refreshed per
  docs/QUALITY.md if code changes.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements (JaCoCo instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `core/reminders/api/ReminderController.kt` | 313/360 (86.9%) | 11/12 (91.7%) | 31/33 (93.9%) | 76/78 (97.4%) |
| `core/reminders/domain/Reminder.kt` | 105/123 (85.4%) | 4/4 (100%) | 13/14 (92.9%) | 21/22 (95.5%) |
| `core/reminders/domain/ReminderService.kt` | 413/414 (99.8%) | 17/18 (94.4%) | 20/20 (100%) | 55/55 (100%) |
| `core/reminders/infra/ReminderRepository.kt` | N/A (interface, no executable code) | N/A | N/A | N/A |
| `core/reminders/infra/ReminderScheduler.kt` | 34/34 (100%) | 2/2 (100%) | 4/4 (100%) | 10/10 (100%) |
| `core/events/domain/ClientNotification.kt` | 43/43 (100%) | 8/8 (100%) | 3/3 (100%) | 6/6 (100%) |
| `core/events/infra/SseEventHub.kt` (whole file, mostly pre-existing) | 289/310 (93.2%) | 4/4 (100%) | 17/17 (100%) | 51/51 (100%) |
| Aggregate (summed counters, 6 measured files; ReminderRepository N/A; migration excluded) | 1197/1284 (93.2%) | 46/48 (95.8%) | 88/91 (96.7%) | 219/222 (98.6%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ReminderController.kt:180,195,198,211` (data-class headers and default-argument values of `ReminderItemResponse`/`ReminderResponse`) | Kotlin-generated `copy`/`equals`/`toString` and synthetic default-argument constructors | Justified gap: generated code, the responses are always built with every argument |
| `ReminderController.kt:233` (`checkNotNull(reminder.createdAt)` failure branch) | Defensive: a persisted reminder always has `createdAt` | Justified gap: not reachable through the API |
| `Reminder.kt:28,38-40` (JPA no-arg constructor, default-argument constructor) | Generated by the Kotlin JPA plugin / default `channel`/`id` | Justified gap: generated |
| `ReminderService.kt:150` (`isGone` when `exists()` returns true although `resolve()` returned null) | A resolver whose `exists` and `resolve` disagree; the reminder then fires with a title-less notification | Justified gap: needs a resolver inconsistent with the `ItemResolver.exists` default; the "gone", "failing" and "no resolver" paths are tested |
| `SseEventHub.kt:64,116,122` (`runCatching { emitter.complete() }` lambdas) | Pre-existing cleanup code, not changed by the task | Out of task scope; the task's added lines in this file are 5/5 covered |

## Unable to test

- AC4 (OpenAPI regenerated): not re-tested with new tests; the existing `OpenApiContractTest` passed in the full
  run without `UPDATE_OPENAPI`.
- Real browser notification display and the Android local alarm: client-side, outside this server task.
- Web and Android parts of `.taskcheck`: not reached because the server step fails on TW-1.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1: the
migration path breaks `MigrationConventionTests`, `./gradlew check` and `.taskcheck`). All 40 new tests and the
existing reminder and events tests pass; task-scoped coverage is high with only generated or defensive code
uncovered.
