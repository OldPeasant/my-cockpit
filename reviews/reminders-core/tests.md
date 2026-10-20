# Test Report

Date: 2026-10-20

Task: reminders-core - Reminders and notifications backend (rerun on the final code after the CR-1/CR-2 fixes)

Scope: `git diff 3f0c01c6983106a3c91f1493a7c8842b0f566951..266252450e788bab2fa92a1c10a5b7c7fb67593c` on branch
`main` (base and task commits supplied by the parent worker): `e1b919f` (implementation), `b7828f2` (review report),
`8238e1c` (fixes CR-1/CR-2: `ReminderItems` isolates item resolvers in `REQUIRES_NEW` read-only transactions,
`ReminderRepository.deleteReminder` bulk delete without version check), `c4c7dcc` (regenerated `api/openapi.yaml` and
web client), `2662524` (WIP `ReminderGtdResolverTests.kt` from an earlier interrupted test-writer rerun). Plus this
run's uncommitted addition to `ReminderGtdResolverTests.kt` (see snapshot below). Earlier `tests.md` content described
a pre-fix revision and is fully replaced.

Requirements:
- `tasks/reminders-core.md` acceptance criteria 1-4 and its five ASSUMPTION notes (SSE `reminder` event via
  `ClientNotification`, channel `NOTIFICATION`, polling scheduler with `SKIP LOCKED` and silent firing for gone items,
  API shape incl. `GET /{id}`, immutable reminders, `upcoming` max 1000, `ItemDeletedEvent` cleanup, migration folder)
- `SPEC.md` §4.6 (reminders), §4.3 (ItemResolver), §4.2 (API conventions)
- `docs/ARCHITECTURE.md` "Coverage"; `docs/QUALITY.md`; `AGENTS.md` project notes
- `reviews/reminders-core/review.md` findings CR-1 and CR-2 (behaviour the fixes must guarantee)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/reminders/ReminderGtdResolverTests.kt` (WIP file from `2662524`
  reviewed, kept unchanged, 3 tests added in this run)

Tests from the fix commit reviewed without changes: `ReminderTransactionalResolverTests.kt` (CR-1, failing
`@Transactional` resolver), the two CR-2 tests in `ReminderServiceEdgeCaseTests.kt`.

## Tests added or updated

Count: 3 added in this run (plus the 4 WIP tests committed in `2662524` in `ReminderGtdResolverTests`, reviewed and
kept unchanged).

Added (real gtd `TASK`/`PROJECT` resolvers, which are `@Transactional` proxies, no fakes):
- `creating a reminder for a missing or trashed real task is rejected`: the existence check in `create` still sees the
  Trash with the real resolver; nothing is stored.
- `a reminder of a real project fires with the project title and deep link`: the `PROJECT` resolver path through
  `ReminderItems` in the firing transaction.
- `listings resolve tasks and projects together and keep reminders of a trashed task unavailable`: `upcoming`
  (`resolveAll` with its per-item cache) over mixed item types, a trashed item and a repeated item, order preserved.

WIP tests reviewed and kept: `a reminder of a real task fires once with the task title and deep link`,
`a reminder of a task moved to the Trash is marked fired without notification`,
`a reminder can be created in the transaction that creates its task and resolves after the commit`,
`a firing batch resolves each real task in its own transaction while the reminders stay locked`.

## Requirement coverage

Focused command (F): `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.reminders.*' --tests 'ch.sonensei.cockpit.core.events.*' --tests '*OpenApiContractTest' jacocoTestReport`
(Postgres via Testcontainers, `MutableClock` test clock).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `reminder` table + `GET/POST/DELETE /api/v1/reminders` (itemType, itemId, remindAt), `GET /upcoming?until=` | `ReminderWebTests` (12: POST create/validation/channel/CSRF/login, GET list/by id/404/400, upcoming incl. boundary and validation, DELETE + second DELETE 404) | F | Pass |
| AC1: DELETE racing with the scheduler does not fail on the version (CR-2) | `ReminderServiceEdgeCaseTests`: `deleting a reminder while the scheduler fires it succeeds instead of failing on its version`, `a fired reminder can be deleted although the scheduler changed its version` | F | Pass |
| AC2: scheduler fires due reminders once and sets `firedAt` | `ReminderServiceTests`, `ReminderServiceEdgeCaseTests` (exact time, overdue, batch limit, SKIP LOCKED concurrency, markFired twice), `ReminderSchedulerTests` (5 across 3 classes) | F | Pass |
| AC2: `reminder` SSE event with the resolved item title, after commit | `ReminderEventsStreamTests` (3, incl. rollback), `ClientNotificationHubTests`, `ClientNotificationStreamTests`, `ReminderGtdResolverTests` real task/project title and deep link | F | Pass |
| AC2: a failing real (`@Transactional`) resolver neither rolls back the batch nor breaks reads (CR-1) | `ReminderTransactionalResolverTests` (3), `ReminderGtdResolverTests.a firing batch resolves each real task in its own transaction while the reminders stay locked` | F | Pass |
| ASSUMPTION: gone (trashed/deleted) item -> marked fired without notification; create for unknown item rejected | `ReminderGtdResolverTests` (trashed task fires silently; missing or trashed task rejected), `ReminderServiceTests`, `ReminderTransactionalResolverTests` | F | Pass |
| ASSUMPTION: permanently deleted item loses its reminders (`ItemDeletedEvent`) | `ReminderServiceTests.changes publish REMINDER change events and a permanently deleted item loses its reminders`, `ReminderServiceEdgeCaseTests.deleting an item removes only its reminders...` (fake resolvers; gtd has no `TrashContributor` yet, so no end-to-end purge path exists) | F | Pass |
| AC3: reminders tests pass with Testcontainers and the test clock (fires once, not twice after restart) | `ReminderServiceTests.a fired reminder does not fire again after a restart` and all `core.reminders.*` (52 tests) | F | Pass |
| AC4: `api/openapi.yaml` regenerated, `OpenApiContractTest` passes without the flag | `OpenApiContractTest` | F (no `UPDATE_OPENAPI`) | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.reminders.*' --tests 'ch.sonensei.cockpit.core.events.*' --tests '*OpenApiContractTest' jacocoTestReport`

Result:

Pass. BUILD SUCCESSFUL (2026-10-20 16:38:05 -> 16:39:32 +02:00). 86 tests, 0 failures, 0 errors, 0 skipped:
ReminderEventsStreamTests 3, ReminderGtdResolverTests 7, ReminderSchedulerDisabledTests 2,
ReminderSchedulerEnabledTests 1, ReminderSchedulerRunTests 3, ReminderServiceEdgeCaseTests 16, ReminderServiceTests 5,
ReminderTransactionalResolverTests 3, ReminderWebTests 12 (reminders: 52); ChangeEventTests 6,
ClientNotificationHubTests 3, ClientNotificationStreamTests 2, EventsAsyncDispatchTests 1, EventsHttpStreamTests 2,
EventsStreamTests 5, SseEventHubTests 14 (events: 33); OpenApiContractTest 1.

Also: `cd server && ./gradlew ktlintCheck` -> BUILD SUCCESSFUL (after `./gradlew ktlintFormat`, which changed only the
new test file).

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Pass. BUILD SUCCESSFUL in 3m 24s (2026-10-20 16:40 -> 16:43 +02:00); 1118 server tests, 0 failures, 0 errors,
0 skipped; ktlint and the other check tasks passed.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (2026-10-20 16:43:50 -> 16:45:05 +02:00) -> "== all checks passed" (server, web lint/tests/build,
android ktlint + unit tests + lint).

## Bugs found

None. No production defect was found on the final revision.

Observation (not a defect, no fix requested): because `ReminderItems` resolves display data in a `REQUIRES_NEW`
transaction, a caller that creates an item and its reminder in one transaction gets the reminder back with
`item == null` until that transaction commits. The existence check still runs in the caller's transaction, so creation
succeeds, and the KDoc of `ReminderService.create` documents it; the REST API is not affected (the item is always
committed before `POST /reminders`). Each resolver call also borrows a second pool connection while the firing
transaction holds its own; acceptable for a single-user server, noted for awareness.

## Unable to test

- End-to-end permanent delete of a real gtd task (Trash purge -> `ItemDeletedEvent` -> reminders removed): the gtd module
  has no `TrashContributor` yet, so no production path purges a task. The `ItemDeletedEvent` listener is tested with
  fake resolvers. Follow-up belongs to the task that adds the gtd Trash.
- Android sync consumption of `upcoming` and the browser notification: outside this task (server only).

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found on
the final revision (CR-1 and CR-2 fixes are covered by passing tests).

## Coverage

Status: verified (fresh JaCoCo run on the final revision plus this run's test addition).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (JaCoCo setup has no changed-line support; not reconstructed by hand).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `reminders-core`; base
  `3f0c01c6983106a3c91f1493a7c8842b0f566951` and commits `e1b919f b7828f2 8238e1c c4c7dcc 2662524` supplied by the
  parent and matching `git log 3f0c01c..HEAD`; measured HEAD `266252450e788bab2fa92a1c10a5b7c7fb67593c`; file scope
  from `git diff --name-only 3f0c01c..HEAD -- server/src/main web/src android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `2662524` plus one uncommitted test file,
  `ReminderGtdResolverTests.kt` sha256 `2468ab6530c29b863fabe7b3df3abdb312ab4abcb0ecf0ae81b3a7ccbd68e80e` (identical before
  and after the runs; no production file uncommitted).
- Exact included files: `core/events/domain/ClientNotification.kt`, `core/events/infra/SseEventHub.kt`,
  `core/reminders/api/ReminderController.kt`, `core/reminders/domain/Reminder.kt`, `core/reminders/domain/ReminderItems.kt`,
  `core/reminders/domain/ReminderService.kt`, `core/reminders/infra/ReminderRepository.kt`,
  `core/reminders/infra/ReminderScheduler.kt` (under `server/src/main/kotlin/ch/sonensei/cockpit/`). Excluded: the Flyway
  migration `V202610201538__core_reminders.sql` (SQL, not instrumentable; applied by every Testcontainers test);
  `web/src/app/api/generated/**` (generated client, no behaviour of this task; web coverage not run). No renamed or
  deleted files.
- Provider/version, command, working directory, and test selection: Gradle `jacoco` plugin (JaCoCo via Gradle 9.8.1,
  JVM 21.0.12.1); `./gradlew test --tests 'ch.sonensei.cockpit.core.reminders.*' --tests 'ch.sonensei.cockpit.core.events.*' --tests '*OpenApiContractTest' jacocoTestReport`
  in `server/`; the 86 tests listed above.
- Run start/completion time and exit status: 2026-10-20T16:38:05+02:00 -> 16:39:32+02:00, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (sha256 `e1fea3a705540e2c9f6b269630108efe98cb7962c9b59c29a45c762d8b898058`, written 16:39:32), exec
  `server/build/jacoco/test.exec` written 16:39:28.
- Evidence outputs are fresh and inputs stayed unchanged: yes; test task re-executed (test input changed), report and
  exec timestamps inside the run window, test-result timestamps 14:38-14:39Z, snapshot hash unchanged afterwards.
- Final run after latest test additions and production fixes: yes (after `8238e1c` fixes and after this run's added tests).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo counts
instructions (reported as Statements).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `core/events/domain/ClientNotification.kt` | 43/43 (100%) | 8/8 (100%) | 3/3 (100%) | 6/6 (100%) |
| `core/events/infra/SseEventHub.kt` | 289/310 (93.2%) | 4/4 (100%) | 17/17 (100%) | 51/51 (100%) |
| `core/reminders/api/ReminderController.kt` | 313/360 (86.9%) | 11/12 (91.7%) | 31/33 (93.9%) | 76/78 (97.4%) |
| `core/reminders/domain/Reminder.kt` | 105/123 (85.4%) | 4/4 (100%) | 13/14 (92.9%) | 21/22 (95.5%) |
| `core/reminders/domain/ReminderItems.kt` | 151/152 (99.3%) | 9/10 (90.0%) | 5/5 (100%) | 16/16 (100%) |
| `core/reminders/domain/ReminderService.kt` | 327/327 (100%) | 12/12 (100%) | 18/18 (100%) | 48/48 (100%) |
| `core/reminders/infra/ReminderRepository.kt` | N/A | N/A | N/A | N/A |
| `core/reminders/infra/ReminderScheduler.kt` | 34/34 (100%) | 2/2 (100%) | 4/4 (100%) | 10/10 (100%) |
| **Aggregate (7 measured files, summed counters)** | 1262/1349 (93.6%) | 50/52 (96.2%) | 91/94 (96.8%) | 228/231 (98.7%) |

`ReminderRepository.kt` is a Spring Data interface without default methods: JaCoCo lists the source file with no
executable counters (zero denominator, N/A); its queries run through the Spring Data proxy and are exercised by the
service, scheduler, concurrency and web tests above. Excluded from the aggregate: the migration and the generated web
client (see above). No scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ReminderController.kt:180,190-198` (`ReminderItemResponse`/`ReminderResponse` default-argument constructors), `:208,211` (`ReminderListResponse` synthetic members) | none: Kotlin-generated default-parameter constructors; the main constructors are covered by every API test | Non-material, left uncovered |
| `ReminderController.kt:233` (1 branch: `checkNotNull(reminder.createdAt)` failing) | defensive; `createdAt` is set on persist, a reminder returned by the service is always persisted | Non-material, left uncovered |
| `Reminder.kt:28,38-40` (JPA/default-argument synthetic constructors) | none: Hibernate no-arg/default-argument constructors | Non-material, left uncovered |
| `ReminderItems.kt:46` (1 branch: `TransactionTemplate.execute` returning `null`) | not reachable: the callback always returns a non-null Boolean; the failure path (`runCatching` -> `false`) is covered by `ReminderTransactionalResolverTests` | Non-material, left uncovered |
| `SseEventHub.kt:64,116,122` (`runCatching { emitter.complete() }` lambdas on timeout/send failure/destroy) | best-effort cleanup inside already-tested paths (`SseEventHubTests`) | Non-material, left uncovered |
