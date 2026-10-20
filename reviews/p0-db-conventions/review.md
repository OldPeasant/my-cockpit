# Code Review

Date: 2026-10-20

Task: p0-db-conventions - Persistence conventions: base entity, UUIDv7, clock, migrations layout

Scope: `git diff 5ee5f092209a1a200bd74b58a2de42d1013a39e5 HEAD` (task commit 57382ea, merged at 5625a4f). Committed changes only; working tree was clean at review time.

Requirements:
- `tasks/p0-db-conventions.md` acceptance criteria 1-4 and its ASSUMPTION notes (JPA auditing with Clock-backed DateTimeProvider; client ids must be UUIDv7; Persistable for assigned ids; test-only fixture migration)
- SPEC.md §4.2 (UUIDv7 ids, createdAt/updatedAt as UTC timestamptz, version for optimistic locking, soft delete via deletedAt, Trash), SPEC.md e2e "test clock" scenario
- docs/ARCHITECTURE.md (conventions, new "Persistence conventions" section), AGENTS.md project notes (migration path/naming, module packages)
- Existing configuration: `server/src/main/resources/application.properties` (Flyway `classpath:db/migration`, `out-of-order=true`, `ddl-auto=validate`, `hibernate.jdbc.time_zone=UTC`), `server/build.gradle.kts` (plugin.jpa, allOpen for `@MappedSuperclass`/`@Entity`), `support/PostgresTestBase.kt`

Git range:
- Branch: main
- HEAD: 5625a4fdc4f458d68d18da8a517014f55078dedf
- Base/start: 5ee5f092209a1a200bd74b58a2de42d1013a39e5
- Commits reviewed: 2 (57382ea implementation, 5625a4f merge of `origin/wip/p0-db-conventions`)

Commands and results:
- `git diff --stat 5ee5f09 HEAD` -> 13 files, +505 lines (6 main Kotlin files, 1 main migration, 4 test Kotlin files, 1 test migration, ARCHITECTURE.md)
- `git status --short` -> clean
- Read-only inspection (grep) for other `Clock` beans, `@EnableJpaAuditing` and `Instant.now()` in `server/src` -> only the new `ClockConfiguration`; no conflicting beans
- Test run reported by the parent worker (not re-run by the reviewer): `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.persistence.*'` with Docker -> 16 tests passed

Acceptance criteria assessment:
1. `BaseEntity` (UUIDv7 id generated or client-supplied and validated, `createdAt`/`updatedAt` Instant mapped to `timestamptz`, `@Version version`) and `SoftDeletable`/`SoftDeletableEntity` with `deletedAt`: implemented (`BaseEntity.kt`, `SoftDeletable.kt`). `timestamptz` storage is asserted against the fixture table.
2. Injectable `Clock` bean used for entity timestamps via `DateTimeProvider`; tests substitute a `@Primary MutableClock` (`appClock shouldBe clock` test): implemented. See CR-1 for a precision concern.
3. Migration convention documented in ARCHITECTURE.md with the required path pattern and "never edit applied ones"; first migration `core/V202610200000__core_enable_pg_trgm.sql` only creates the extension: implemented and asserted against `flyway_schema_history`/`pg_extension`.
4. Tests for UUIDv7 ordering (10 000 ids, text order + uniqueness), optimistic locking (`OptimisticLockingFailureException` on a stale save), soft-delete exclusion (`findActiveById`/`findAllActive`/`findAllDeleted`) against Postgres Testcontainers: present and meaningful.

Summary: 0 P0, 0 P1, 1 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Clock bean has nanosecond precision, but `timestamptz` stores microseconds: in-memory and stored timestamps diverge**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/ClockConfiguration.kt:19` (also `:22`)
    - Category: Correctness
    - Problem: `Clock.systemUTC()` returns nanosecond-resolution instants on Linux JDKs (15+), the platform of CI and the Docker image; macOS gives microseconds, so local runs hide it. The auditing `DateTimeProvider` writes that value into `createdAt`/`updatedAt` of the managed entity, while PostgreSQL `timestamptz` keeps only microseconds (pgjdbc rounds when binding). After `save()` the entity in memory holds e.g. `...12.123456789Z`, but a later read returns `...12.123457Z`. All tests use `MutableClock` with whole-second instants, so they cannot detect this.
    - Why it matters: Every module builds on this. API responses for create/update (built from the saved entity) will report different `createdAt`/`updatedAt` values than a later GET of the same resource. That breaks equality checks in clients and tests, and any ETag, sync cursor or "changed since" comparison that uses `updatedAt`. Such comparisons are likely in sync (SPEC.md CalDAV/CardDAV, Android offline sync).
    - Suggested fix: Make the application clock microsecond-precise at the source, e.g. `Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000))`, or truncate in the `DateTimeProvider` (`clock.instant().truncatedTo(ChronoUnit.MICROS)`). The first option also covers `softDelete(clock)` and any other timestamp taken from the bean. Add a test with a sub-microsecond `MutableClock` instant to check that the saved entity equals the reloaded one.

## P3 Low

- [ ] **CR-2 | `equals` initializes lazy proxies via `Hibernate.getClass`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/BaseEntity.kt:73` (also `:78`)
    - Category: Quality
    - Problem: `Hibernate.getClass(...)` initializes an uninitialized proxy as a side effect (documented Hibernate behaviour). Comparing an entity with a reference from `getReferenceById` therefore triggers a SELECT. Outside a session (`spring.jpa.open-in-view=false`) it throws `LazyInitializationException`. `toString` has the same problem when it is called on a detached proxy.
    - Why it matters: `BaseEntity.equals` runs for every entity in every module, e.g. in sets of linked entities (SPEC.md §14 link system). Logging or comparing a detached reference fails in a way that is hard to trace.
    - Suggested fix: Read the class without initializing, e.g. `(o as? HibernateProxy)?.hibernateLazyInitializer?.persistentClass ?: o.javaClass`, and use it in both `equals` and `toString`.

- [ ] **CR-3 | Idempotent re-create with an existing client id is not covered by a test**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/PersistenceConventionsTests.kt:92`
    - Category: Tests
    - Problem: The test `a client-supplied UUIDv7 id is kept, and creating it again is detectable` saves only once and then calls `existsById`. It never saves a second new entity with the same client id. Because `isNew()` is `true` for any freshly built instance, a second `save()` runs `persist`, which fails at flush with a duplicate key. It does not merge or overwrite the existing row. Nothing checks this behaviour, which the idempotent-create contract (criterion 1, SPEC.md §4.2) depends on.
    - Why it matters: Later API tasks will build idempotent create on this. A test that fixes the base-layer behaviour (for example `DataIntegrityViolationException`, existing row unchanged) protects against a change that would make a retry silently overwrite data.
    - Suggested fix: Add a test that saves `PersistenceFixture("A", id)` and then `PersistenceFixture("B", id)`. Assert the exception type and that the stored title is still "A". Alternatively, rename the test so it does not claim more than it checks.

- [ ] **CR-4 | Test-only migration uses a real-looking timestamp version that can collide with production migrations**
    - File: `server/src/test/resources/db/migration/core/V202610200001__core_test_persistence_fixture.sql:1`
    - Category: Quality
    - Problem: The test fixture migration uses version `202610200001`, a real time in the shared `V<yyyyMMddHHmm>` namespace. Test and main resources are both scanned under `classpath:db/migration`. A production migration in any module that is later created with the same minute stamp makes Flyway fail in every Spring test with "Found more than one migration with version 202610200001", even though production would still work.
    - Why it matters: This is unlikely but confusing to diagnose, and it breaks the guarantee in the documented convention that "parallel tasks never clash on version numbers".
    - Suggested fix: Give test-only migrations a version that cannot occur for real files, such as a date in the past before any production migration (`V197001010000__core_test_...`) or another reserved scheme. Document that rule next to the "Test-only tables" sentence in ARCHITECTURE.md.

## Review conclusion

Material issues found; fix before completing the task.

All four acceptance criteria are implemented and backed by meaningful tests. CR-1 (P2) should be fixed before other modules build on the clock and timestamps. CR-2 to CR-4 are low-priority hardening items that the parent worker may fix or explicitly defer.
