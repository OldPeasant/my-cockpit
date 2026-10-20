# Fix Report

Date: 2026-10-20

Task: p0-db-conventions - Persistence conventions: base entity, UUIDv7, clock, migrations layout

Scope: current task. Base 5ee5f092209a1a200bd74b58a2de42d1013a39e5 .. HEAD 3a55b45 plus uncommitted fixes. The parent approved findings CR-1, CR-2 and CR-4 from `reviews/p0-db-conventions/review.md`. Changes stay inside `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence`, `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence`, `server/src/test/resources/db/migration/core` and `docs/ARCHITECTURE.md`.

## Fixed findings

### CR-1 - Clock bean has nanosecond precision, but timestamptz stores microseconds

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/ClockConfiguration.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/SoftDeletable.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/ClockConfigurationTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/PersistenceConventionsTests.kt`
- `docs/ARCHITECTURE.md`

Problem:
`Clock.systemUTC()` can return nanosecond instants (Linux, JDK 15+), but PostgreSQL `timestamptz` keeps only microseconds. As a result, `createdAt`, `updatedAt` and `deletedAt` held in memory after a save could differ from the values read back later.

Fix:
- The application clock bean is now `Clock.tick(Clock.systemUTC(), Duration.of(1, ChronoUnit.MICROS))`, so every instant taken from the bean can be stored as is.
- A new internal extension `Clock.storableInstant()` truncates to microseconds. The auditing `DateTimeProvider` and `SoftDeletableEntity.softDelete(clock)` use it, so a clock substituted in tests (`MutableClock` with nanos) also produces values the database can store exactly.
- ARCHITECTURE.md now notes in one phrase that timestamps use microsecond precision, like `timestamptz`.

Tests added:
- `the application clock ticks in microseconds, the precision of timestamptz`
- `auditing timestamps are truncated to microseconds, also from a substituted clock`
- `sub-microsecond clock instants are stored so that saved and reloaded timestamps are equal`: Postgres via Testcontainers, with the MutableClock at `...00.123456789Z`. It checks that `createdAt`, `updatedAt` and `deletedAt` are equal on the saved entity and on the reloaded one, and that both are `...00.123456Z`.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.persistence.*'` - Pass

### CR-2 - equals initializes lazy proxies via Hibernate.getClass

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/BaseEntity.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/SoftDeletable.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/PersistenceConventionsTests.kt`

Problem:
`Hibernate.getClass` initializes uninitialized proxies. While verifying the finding, I found a second problem: the existing test "a lazy proxy equals the loaded entity with the same id" never got a proxy. At startup Hibernate logged `HHH000305: Could not create proxy factory for PersistenceFixture`, caused by `Getter methods of lazy classes cannot be final: SoftDeletableEntity#getDeletedAt`. The cause was `final override var deletedAt`. With this, no `SoftDeletableEntity` subclass could be lazily proxied: `getReferenceById` and lazy to-one associations loaded the row eagerly.

Fix:
- A new private helper `entityClass(entity)` returns `hibernateLazyInitializer.persistentClass` for a `HibernateProxy` and `javaClass` for anything else. `equals` and `toString` use it.
- `equals` reads the other side's id through `hibernateLazyInitializer.identifier` when the other side is a proxy. With field access, calling `getId()` on the proxy would initialize it.
- I removed `final` from `SoftDeletableEntity.deletedAt` (with a one-line comment explaining why), so Hibernate can create proxies again. The setter stays `protected`.
- The proxy test now asserts that `getReferenceById` returns an uninitialized proxy, and that `saved == proxy` does not initialize it. All the earlier assertions are kept: equality in both directions, hashCode, inequality with another entity, and toString.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.persistence.*'` - Pass. HHH000305 no longer appears in the test output.

### CR-4 - Test-only migration uses a real-looking timestamp version

Files changed:
- `server/src/test/resources/db/migration/core/V202610200001__core_test_persistence_fixture.sql` renamed to `server/src/test/resources/db/migration/core/V202610200000_1__core_test_persistence_fixture.sql` (content unchanged)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/MigrationConventionTests.kt`
- `docs/ARCHITECTURE.md`

Problem:
The fixture used version `202610200001`, which belongs to the shared real minute-stamp namespace. A real migration created with the same stamp would break every Spring test.

Fix:
- Flyway reads the new version as `202610200000.1`. Real migrations never have a suffix, so the two cannot collide. The fixture still sorts after the `pg_trgm` migration and stays outside `target("202610200000")`, which the "first core migration creates no tables" test relies on.
- I chose this over the reviewer's `V197001010000` suggestion. A 1970 version would sort first, which breaks the "pg_trgm has the lowest version" rule and the target-based "creates no tables" test.
- The convention test regex now allows an optional `_N` version suffix. A new assertion requires the suffix exactly when the description starts with `test_`, so real migrations still have to follow the plain format.
- The "lowest version" test now compares the version including the suffix. Before, it compared only the 12 digits, and the tie gave a nondeterministic result.
- ARCHITECTURE.md now documents the naming of test-only migrations next to the existing "Test-only tables" sentence.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.persistence.*'` - Pass (MigrationConventionTests 2/2; "the first core migration creates no tables" passes)

## Not fixed

### CR-3 - Idempotent re-create with an existing client id is not covered by a test

Status:
Invalid (already resolved)

Reason:
The test-writer already added `creating the same client id twice fails instead of overwriting the first row` (PersistenceConventionsTests). It asserts `DataIntegrityViolationException` and that the first row is unchanged. The parent confirmed that no change is needed. The test passes in the runs listed below.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.persistence.*'` (Docker, Postgres 16 Testcontainers) - Pass, 35 tests, 0 failures, 0 skipped (BaseEntityTests 5, ClockConfigurationTests 4, MigrationConventionTests 2, MutableClockTests 3, PersistenceConventionsTests 16, UuidV7Tests 5)

Full test suite:
- `cd server && ./gradlew check` - BUILD SUCCESSFUL, 56 server tests, 0 failures, 0 skipped

Lint/type-check/build:
- `ktlintCheck` is included in both commands above - Pass. Kotlin compilation shows no warnings.

`.taskcheck`:
- Not run. The sandbox did not allow `sh scripts/check.sh` (needs approval). The parent worker must run it. Its server part (`./gradlew check`) passed above. No web or android files were changed.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
