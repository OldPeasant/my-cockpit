# Test Report

Date: 2026-10-20

Task: p0-db-conventions - Persistence conventions: base entity, UUIDv7, clock, migrations layout

Scope: final revision. `git diff faed60e5b7fb230ffba31be82693c545c208a23d..186593b0880853789c553e06d82f7994c7405cb5` (task commits 12a8c1f, 967d118, 2850168, 510073a, 186593b on main), plus this round's uncommitted test change in `PersistenceConventionsTests.kt` (sha256 `214ad2cc2db2c5e35336372fcd37d359c3c5dbc6b7725355176e2dc5ce9f24f8`). No production code was changed by the test-writer.

Requirements:
- `tasks/p0-db-conventions.md` acceptance criteria 1-4 and its ASSUMPTION notes
- SPEC.md §4.2, docs/ARCHITECTURE.md "Persistence conventions" and "Coverage"
- `reviews/p0-db-conventions/review.md` CR-5 (proxy `equals`/`hashCode`/`toString` limitation, now documented in the `BaseEntity` KDoc and ARCHITECTURE.md by 186593b)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/PersistenceConventionsTests.kt`

## Tests added or updated

Count: 1 (added)

- `toString names the entity class and id, and on a detached proxy it fails as documented`: `toString()` of an entity reloaded from Postgres is `PersistenceFixture(id=<id>)`. `toString()` and `hashCode()` on a detached, uninitialized proxy throw `LazyInitializationException`, which is the limitation documented by 186593b (CR-5). This guards the documentation: if the behavior changes, the docs must change too.

The existing tests already covered the other parts of the 186593b `toString` change: `toString` on an in-memory entity (`BaseEntityTests.entities are equal by type and id`) and on an uninitialized proxy inside a session (`PersistenceConventionsTests.a lazy proxy equals the loaded entity with the same id`, expecting `PersistenceFixture(id=...)`, so `javaClass.simpleName` resolves to the entity class through the proxy). The review of all other tests against the criteria found no gaps.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `BaseEntity`: UUIDv7 id generated or accepted from the client; `createdAt`/`updatedAt` UTC `timestamptz`; `@Version version`; `SoftDeletable` with `deletedAt` | `BaseEntityTests` (5), `PersistenceConventionsTests`: `create assigns a UUIDv7 id...`, `a client-supplied UUIDv7 id is kept...`, `ids that are not UUIDv7 are rejected`, `creating the same client id twice fails...`, `timestamps are stored as timestamptz`, `timestamps are stored as the exact UTC instant...`, `update moves updatedAt...bumps the version`, `a lazy proxy equals...`, `an entity compares with a detached uninitialized proxy...`, `toString names the entity class and id...` | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.persistence.*' jacocoTestReport` (Docker, Postgres Testcontainers) | Pass |
| AC2 injectable `Clock` used for all timestamps; tests substitute a fixed/mutable clock | `ClockConfigurationTests` (4), `MutableClockTests` (3), `PersistenceConventionsTests`: `the injectable clock is the one tests substitute`, `update moves updatedAt to the clock's time...`, `sub-microsecond clock instants are stored...`, soft-delete timestamp asserts | same | Pass |
| AC3 migration convention documented; first migration only enables `pg_trgm` | `MigrationConventionTests` (2), `PersistenceConventionsTests`: `the first core migration ran and enabled pg_trgm`, `the first core migration creates no tables`. Documentation: `docs/ARCHITECTURE.md` "Persistence conventions" bullet "Migrations" read and matches the criterion (path pattern, never edit applied ones) | same | Pass (documentation part checked by reading) |
| AC4 UUIDv7 ids are time-ordered | `UuidV7Tests`: `ids are time-ordered and unique, also within the same millisecond`, `ids created later have a later timestamp prefix` (+3 more) | same | Pass |
| AC4 optimistic locking raises a conflict on a stale version | `saving a stale version raises an optimistic locking conflict` | same | Pass |
| AC4 soft-deleted rows excluded by the repository helper | `soft-deleted rows are excluded by the repository helper`, `soft delete keeps the first deletion time and restore brings the row back`, `active rows come in creation order and deleted rows newest deletion first` | same | Pass |
| AC4 `./gradlew test --tests 'ch.sonensei.cockpit.core.persistence.*'` passes with Postgres via Testcontainers | all 37 tests in the package | same | Pass (37 tests, 0 failures, 0 skipped) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.persistence.*' jacocoTestReport`

Result:

Pass. BUILD SUCCESSFUL, 2026-10-20T11:29:57+02:00 to 11:30:17+02:00. Results: `BaseEntityTests` 5, `ClockConfigurationTests` 4, `MigrationConventionTests` 2, `MutableClockTests` 3, `PersistenceConventionsTests` 18, `UuidV7Tests` 5 = 37 tests, 0 failures, 0 errors, 0 skipped. ktlintCheck passed.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Pass. BUILD SUCCESSFUL; 28 test suites, 138 tests, 0 failures, 0 errors, 0 skipped.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (TFP gate tests, server check, web check and build, android ktlint, unit tests and lint) -> "all checks passed".

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to the whole-file numbers. Every scoped production file was added by this task (`git diff --name-status` shows `A` for all of them), so all of their executable lines are changed lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-db-conventions; base `faed60e5b7fb230ffba31be82693c545c208a23d` (given by the parent); HEAD `186593b0880853789c553e06d82f7994c7405cb5`; task commits 12a8c1f..186593b given by the parent and matching the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 186593b plus the uncommitted `PersistenceConventionsTests.kt` sha256 `214ad2cc2db2c5e35336372fcd37d359c3c5dbc6b7725355176e2dc5ce9f24f8`. sha256 of all main and test files in `core/persistence` was taken before the run and compared after it: unchanged.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/{BaseEntity,ClockConfiguration,MutableClock,SoftDeletable,SoftDeletableRepository,UuidV7}.kt`. Excluded: `server/src/main/resources/db/migration/core/V202610200000__core_enable_pg_trgm.sql` (SQL, not instrumented by JaCoCo; covered behaviorally by the two migration tests), docs and review reports (not code). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (Gradle 9.8.1 default tool version), `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.persistence.*' jacocoTestReport`, working directory `server/`, test selection `ch.sonensei.cockpit.core.persistence.*`.
- Run start/completion time and exit status: 2026-10-20T11:29:57+02:00 to 11:30:17+02:00, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (written 11:30:17, sha256 `5a5a7a2f8837134f984f53451ee7b3081d8ec79ec56fa8e9ef2ccfd41ae17bb4`), JaCoCo session `dell26-730b88e6`; `server/build/jacoco/test.exec` written 11:30:16.
- Evidence outputs are fresh and inputs stayed unchanged: yes (the test task reran because a test file changed and `outputs.upToDateWhen { false }`; the report and exec timestamps fall inside the run; the snapshot did not change).
- Final run after latest test additions and production fixes: yes (includes the test added in this round; no production change after 186593b).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" = JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/BaseEntity.kt` | 112/133 (84.2%) | 16/16 (100%) | 11/15 (73.3%) | 21/26 (80.8%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/ClockConfiguration.kt` | 29/29 (100%) | N/A (0/0) | 5/5 (100%) | 4/4 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/MutableClock.kt` | 81/81 (100%) | 2/2 (100%) | 8/8 (100%) | 9/9 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/SoftDeletable.kt` | 39/39 (100%) | 4/4 (100%) | 7/7 (100%) | 11/11 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/SoftDeletableRepository.kt` | N/A | N/A | N/A | N/A |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/UuidV7.kt` | 24/24 (100%) | 4/4 (100%) | 3/3 (100%) | 3/3 (100%) |
| **Aggregate (summed counters, 5 measured files)** | 285/306 (93.1%) | 26/26 (100%) | 34/38 (89.5%) | 48/53 (90.6%) |

`SoftDeletableRepository.kt` is an interface with only abstract `@Query` methods: it is listed in the report with no counters (no executable bytecode). It is not counted in the aggregate. Its three queries are exercised against Postgres by the soft-delete tests above. Excluded from the aggregate: the SQL migration (see above).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `BaseEntity.kt:34-36` (class header / constructor with `id: UUID? = null`) | Kotlin's synthetic default-argument constructor `BaseEntity(UUID?, Int, DefaultConstructorMarker)`. Subclasses always pass `id` explicitly, so it is never called. The generate-when-null path itself is covered (line 39 is hit by every new entity without a client id). | Justified gap: compiler-generated bridge, no behavior of its own. |
| `BaseEntity.kt:44`, `:49`, `:54` (`protected set` of `createdAt`, `updatedAt`, `version`) | Setters that Hibernate and auditing bypass through field access. Behavior (auditing sets the timestamps, `@Version` increments) is asserted by the Testcontainers tests. | Justified gap: unused accessors; testing them would need a test-only subclass and test nothing required. |

## Unable to test

- None for the acceptance criteria. The documentation part of AC3 (convention text in ARCHITECTURE.md) is checked by reading, not by an automated test. `MigrationConventionTests` enforces the file naming pattern itself.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. One test was added for the final revision (toString on a reloaded entity, and the documented CR-5 failure of `toString`/`hashCode` on a detached proxy). The focused persistence suite (37 tests, Testcontainers), `./gradlew check` (138 tests) and `.taskcheck` all pass. Not committed by the test-writer: the parent worker verifies and commits the test change and this report.
