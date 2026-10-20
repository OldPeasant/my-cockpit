# Test Report

Date: 2026-10-20

Task: p0-db-conventions - Persistence conventions: base entity, UUIDv7, clock, migrations layout

Scope: base `5ee5f092209a1a200bd74b58a2de42d1013a39e5` .. HEAD `5625a4fdc4f458d68d18da8a517014f55078dedf`
(branch `main`). The only task-owned commit is `57382ea5c60529636b830bedca73e09a5aeaf70f`; HEAD merges it.
`git diff --stat 5ee5f09 HEAD` lists exactly the 13 files of `57382ea`, so no unrelated work is included.
Before this report there were no uncommitted changes. The only uncommitted inputs are the test files
listed below, plus `reviews/p0-db-conventions/review.md`, which the code-reviewer is writing and which I did not touch.

Requirements:
- `tasks/p0-db-conventions.md`: acceptance criteria 1-4 and the recorded assumptions (JPA auditing with a
  Clock-backed `DateTimeProvider`, client ids must be UUIDv7, `Persistable` insert-on-save, test-only fixture migration)
- `SPEC.md` §4.2 (as referenced by the task)
- `docs/ARCHITECTURE.md` "Persistence conventions" and "Coverage"
- `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/BaseEntityTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/ClockConfigurationTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/MigrationConventionTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/MutableClockTests.kt` (1 test added)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/PersistenceConventionsTests.kt` (5 tests added)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/UuidV7Tests.kt` (1 test added)

The test migration `server/src/test/resources/db/migration/core/V202610200001__core_test_persistence_fixture.sql`
is unchanged. No existing test was modified, weakened or disabled. No production file was changed.

## Tests added or updated

Count: 16 new tests (16 existing tests from the task commit kept unchanged; 32 in `core.persistence` now)

- BaseEntityTests: `a new entity gets a fresh UUIDv7 id and counts as new`, `a client-supplied UUIDv7 id is kept`,
  `a version 7 id with a non-RFC variant is rejected`, `the nil id is rejected`, `entities are equal by type and id`
- ClockConfigurationTests: `the application clock is the system clock in UTC`,
  `auditing timestamps are read from the injected clock on every call`
- MigrationConventionTests: `every migration lives in a module folder and is named V yyyyMMddHHmm __ module _ desc`,
  `the core pg_trgm migration has the lowest version`
- MutableClockTests: `setting the time through a zoned view moves the original clock`
- UuidV7Tests: `version 7 with a non-RFC variant and the nil id are not UUIDv7`
- PersistenceConventionsTests (Postgres 16 via Testcontainers):
  `creating the same client id twice fails instead of overwriting the first row` (a regression guard for
  `Persistable`: without it, Spring Data would merge and silently overwrite the first row),
  `timestamps are stored as the exact UTC instant of the clock`,
  `active rows come in creation order and deleted rows newest deletion first` (also: the inherited `findAll` still sees the Trash),
  `a lazy proxy equals the loaded entity with the same id`,
  `the first core migration creates no tables` (runs Flyway with `target=202610200000` into a throwaway schema)

## Requirement coverage

All rows were run with `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.persistence.*'`
(focused) and again inside `cd server && ./gradlew ktlintCheck check jacocoTestReport` (full server suite, final run).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `BaseEntity` UUIDv7 id generated server-side | `create assigns a UUIDv7 id, timestamps from the clock and version 0`; `a new entity gets a fresh UUIDv7 id and counts as new` | as above | Pass |
| AC1 id accepted from the client (idempotent create) and must be UUIDv7 | `a client-supplied UUIDv7 id is kept, and creating it again is detectable`; `a client-supplied UUIDv7 id is kept`; `creating the same client id twice fails instead of overwriting the first row`; `ids that are not UUIDv7 are rejected`; `a version 7 id with a non-RFC variant is rejected`; `the nil id is rejected` | as above | Pass |
| AC1 `createdAt`/`updatedAt` UTC `timestamptz` | `timestamps are stored as timestamptz`; `timestamps are stored as the exact UTC instant of the clock`; `update moves updatedAt to the clock's time, keeps createdAt and bumps the version` | as above | Pass |
| AC1 `version` (`@Version`) | `update moves updatedAt ... bumps the version`; `saving a stale version raises an optimistic locking conflict` | as above | Pass |
| AC1 `SoftDeletable` with `deletedAt` | `soft delete keeps the first deletion time and restore brings the row back`; `soft-deleted rows are excluded by the repository helper` | as above | Pass |
| AC1 entity identity (equals/hashCode, proxies) | `entities are equal by type and id`; `a lazy proxy equals the loaded entity with the same id` | as above | Pass |
| AC2 injectable `Clock` bean used for all timestamps; tests substitute a mutable clock | `the injectable clock is the one tests substitute`; `the application clock is the system clock in UTC`; `auditing timestamps are read from the injected clock on every call`; MutableClockTests (3) | as above | Pass |
| AC3 migration convention (module subfolder, `V` + yyyyMMddHHmm version, `__`, module prefix, description, `.sql`) | `every migration lives in a module folder and is named V yyyyMMddHHmm __ module _ desc` (the documentation itself is in `docs/ARCHITECTURE.md` "Persistence conventions"; the text was checked by reading, not by a test) | as above | Pass |
| AC3 first migration enables `pg_trgm` and creates nothing else | `the first core migration ran and enabled pg_trgm`; `the first core migration creates no tables`; `the core pg_trgm migration has the lowest version` | as above | Pass |
| AC3 "never edit applied ones" | process rule, enforced by Flyway checksum validation (pre-existing configuration) | n/a | Not testable as task behavior |
| AC4 UUIDv7 ids are time-ordered | `ids are time-ordered and unique, also within the same millisecond`; `ids created later have a later timestamp prefix`; `active rows come in creation order and deleted rows newest deletion first` (DB `order by id`) | as above | Pass |
| AC4 optimistic locking conflict on stale version | `saving a stale version raises an optimistic locking conflict` | as above | Pass |
| AC4 soft-deleted rows excluded by repository helper | `soft-deleted rows are excluded by the repository helper`; `active rows come in creation order and deleted rows newest deletion first` | as above | Pass |
| AC4 `./gradlew test --tests 'ch.sonensei.cockpit.core.persistence.*'` passes (Testcontainers) | all 32 tests in the package | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.persistence.*'` | Pass (32 tests, 0 failures, 0 skipped) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.persistence.*'`

Result:

Pass. 32 tests, 0 failures, 0 errors, 0 skipped: BaseEntityTests 5, ClockConfigurationTests 2,
MigrationConventionTests 2, MutableClockTests 3, UuidV7Tests 5, PersistenceConventionsTests 15.
Before my changes, the 16 original tests had also passed on this machine (Gradle results 2026-10-20T08:57Z).

`cd server && ./gradlew ktlintCheck`: Pass.

## Full test execution

Command:

`cd server && ./gradlew ktlintCheck check jacocoTestReport --console=plain -q`

Result:

Pass. Exit 0, 53 tests across 15 suites, 0 failures, 0 errors, 0 skipped. Last run 2026-10-20 09:02:09Z-09:02:18Z.
Web and Android suites were not run: the task touches only `server/`.

## .taskcheck

Result:

Not run. `sh scripts/check.sh` was denied by the tool permission system in this sub-agent session.
The server part of it (`./gradlew check`, which includes ktlint and the unit tests) passed above. The parent worker must run `.taskcheck`.

## Bugs found

None. No test exposed behavior that violates the acceptance criteria or the recorded assumptions.

## Coverage

Status: verified (whole-file, for every instrumentable task-changed production file).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage, because every scoped production file was added by the task
(`git diff 5ee5f09 HEAD` shows only new files). No separate changed-line tool was run.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-db-conventions`;
  base `5ee5f092209a1a200bd74b58a2de42d1013a39e5` (from the parent); HEAD `5625a4fdc4f458d68d18da8a517014f55078dedf`;
  task commit `57382ea5c60529636b830bedca73e09a5aeaf70f` (from the parent). The scope was cross-checked with
  `git diff --name-only 5ee5f09 HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): production code = HEAD `5625a4f` with no uncommitted
  production changes. Test blob hashes (`git hash-object`) for the final run: BaseEntityTests `6c8bdf5e`,
  ClockConfigurationTests `ca8bbb64`, MigrationConventionTests `204c570b`, MutableClockTests `b6fae013`,
  PersistenceConventionsTests `67fc5c18`, UuidV7Tests `ba5ecadb`, PersistenceFixtures unchanged at HEAD.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/{BaseEntity,ClockConfiguration,MutableClock,SoftDeletable,UuidV7}.kt`.
  Excluded: `SoftDeletableRepository.kt`, an interface with only abstract `@Query` methods and no executable bytecode, so JaCoCo
  reports no counters for it; its behavior is exercised through the Spring Data proxy in PersistenceConventionsTests.
  `db/migration/core/V202610200000__core_enable_pg_trgm.sql` is SQL that JaCoCo cannot instrument; it is verified by the Flyway tests above.
  `docs/ARCHITECTURE.md` is documentation. There are no renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (default tool version, Gradle 9.8.1,
  JVM Corretto 21.0.5); `./gradlew ktlintCheck check jacocoTestReport --console=plain -q` run in `server/`; full server
  `test` task (all non-`external` tests).
- Run start/completion time and exit status: JaCoCo session `R1963MACOS.local-6fb62710`, start 1792486928985 (2026-10-20T09:02:08.985Z),
  dump 1792486938391 (2026-10-20T09:02:18.391Z). Exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (git blob `30c17c070821e56de2e2a098000c7dcd665b0b06`) and `server/build/reports/jacoco/test/html/index.html`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Gradle re-executed `test` after the last test edit, the session
  timestamps postdate the edit, and the test blob hashes were taken after the run.
- Final run after latest test additions and production fixes: yes for the test additions. No production fixes were made. If the
  fixer changes production code, these numbers are stale.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.
"Statements" are JaCoCo INSTRUCTION counters (JaCoCo has no statement counter).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/BaseEntity.kt` | 86/107 (80.4%) | 12/12 (100%) | 10/14 (71.4%) | 19/24 (79.2%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/ClockConfiguration.kt` | 15/15 (100%) | N/A (0/0) | 4/4 (100%) | 3/3 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/MutableClock.kt` | 81/81 (100%) | 2/2 (100%) | 8/8 (100%) | 9/9 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/SoftDeletable.kt` | 35/39 (89.7%) | 4/4 (100%) | 6/7 (85.7%) | 10/11 (90.9%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/UuidV7.kt` | 24/24 (100%) | 4/4 (100%) | 3/3 (100%) | 3/3 (100%) |
| **Aggregate (summed counters, 5 measured files; excludes `SoftDeletableRepository.kt` [no bytecode] and the SQL migration [not instrumentable])** | 241/266 (90.6%) | 22/22 (100%) | 31/36 (86.1%) | 44/50 (88.0%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `BaseEntity.kt:71` (reflexive `this === other` branch), `:72` (`other !is BaseEntity`) | equals contract | Covered by the `entities are equal by type and id` additions (now 12/12 branches) |
| `BaseEntity.kt:31-33` (class header, 9 instructions, 1 method) | Kotlin compiler-generated constructor bytecode (default-argument / no-arg plugin constructor) that no application or test code calls | Non-material generated glue; left uncovered |
| `BaseEntity.kt:41,46,51` (`protected set` of `createdAt`/`updatedAt`/`version`) | Generated setters; Hibernate uses field access (`@Id` on a field) and auditing writes the fields, so nothing calls these setters | Non-material; the behavior they guard (read-only from outside) is enforced by the compiler |
| `SoftDeletable.kt:27` (`protected set` of `deletedAt`) | Generated setter; `softDelete`/`restore` write the backing field directly | Non-material; `softDelete`/`restore` are fully covered |

## Unable to test

- `.taskcheck` (`sh scripts/check.sh`): not run here because the tool permission was denied. Follow-up: the parent runs it.
- AC3 "never edit applied migrations" is a process rule, not runtime behavior of this task. Flyway checksum validation
  (existing configuration) would catch it at startup; no test was added. No follow-up is needed.
- Observation (not a finding): with the real `Clock.systemUTC()`, the in-memory `createdAt` after `save()` can hold
  sub-microsecond precision that Postgres `timestamptz` truncates on reload. The tests use a whole-second `MutableClock`, so this
  is not exercised. No acceptance criterion covers it. The code-reviewer or parent can decide whether to truncate to micros.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. 16 tests were added, all 32 `core.persistence`
tests and the full server suite (53 tests, ktlint) pass, and `.taskcheck` remains to be run by the parent worker.
