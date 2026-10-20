# Code Review

Date: 2026-10-20

Task: p0-db-conventions - Persistence conventions: base entity, UUIDv7, clock, migrations layout

Scope: final review round on the final code. `git diff faed60e5b7fb230ffba31be82693c545c208a23d..2c12988` (committed changes only). This round looks most closely at 186593b (fix for CR-5) and 2c12988 (new detached-proxy `toString`/`hashCode` test and tests.md). I re-read the whole task diff for regressions. The working tree had no uncommitted changes when the review started.

Requirements:
- `tasks/p0-db-conventions.md` acceptance criteria 1-4 and its ASSUMPTION notes (JPA auditing with a Clock-backed DateTimeProvider, UUIDv7-only client ids, `Persistable` for assigned ids, a test-only fixture migration with a `_<n>` suffix, a microsecond clock, an open `deletedAt` getter)
- SPEC.md §4.2 (UUIDv7 ids, UTC `timestamptz`, `version` optimistic locking, soft delete via `deletedAt`)
- docs/ARCHITECTURE.md "Persistence conventions", AGENTS.md project notes (migration path and naming, module packages), docs/QUALITY.md (report contract)
- The previous review round (CR-1..CR-5) and `reviews/p0-db-conventions/fixes.md`

Git range:
- Branch: main
- HEAD: 2c12988
- Base/start: faed60e5b7fb230ffba31be82693c545c208a23d
- Commits reviewed: 6 (12a8c1f, 967d118, 2850168, 510073a, 186593b, 2c12988)

Commands and results:
- `git diff 510073a..2c12988 -- docs server`: KDoc and ARCHITECTURE.md now state the proxy limitation, `toString` uses `javaClass.simpleName`, the `entityClass` helper KDoc says "proxy argument", and the test `toString names the entity class and id, and on a detached proxy it fails as documented` was added.
- I read all 6 main files in `core/persistence`, both migrations, `PersistenceConventionsTests`, `MigrationConventionTests`, `PersistenceFixtures` and the ARCHITECTURE.md section in full.
- `grep` for `Instant.now`, `LocalDateTime.now`, `@EnableJpaAuditing` and clock beans in `server/src/main`: only `ClockConfiguration` matches, and nothing in main code calls `now()` directly.
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.persistence.*'` (Docker, Postgres Testcontainers): BUILD SUCCESSFUL. The test results from this run show 37 tests, 0 failures, 0 skipped (PersistenceConventionsTests 18, BaseEntityTests 5, UuidV7Tests 5, ClockConfigurationTests 4, MutableClockTests 3, MigrationConventionTests 2).
- `python tasks.py verify p0-db-conventions` (read-only listing): no criterion has recorded evidence yet. The parent records it in a later workflow step, so this is not a finding.

Acceptance criteria assessment:
1. `BaseEntity` has a UUIDv7 `id`. It is generated, or supplied by the client and checked for version 7 and the RFC variant. `createdAt`/`updatedAt` are `Instant` values in `timestamptz` columns (checked against `information_schema`), and `version` uses `@Version`. `SoftDeletable`/`SoftDeletableEntity` add `deletedAt`. Met.
2. A single UTC `Clock` bean with a microsecond tick feeds the auditing `DateTimeProvider` and `softDelete(clock)`. Tests substitute a `@Primary MutableClock`, and `appClock shouldBe clock` confirms the substitution. Met.
3. ARCHITECTURE.md documents the path pattern and the rule never to edit or rename applied migrations. `core/V202610200000__core_enable_pg_trgm.sql` only enables `pg_trgm`. Tests check this through a Flyway `target` run into an empty schema, and `MigrationConventionTests` guards the naming. Met.
4. The tests cover time-ordered UUIDv7 ids, the conflict on a stale-version save, and that `findActiveById`/`findAllActive` exclude soft-deleted rows, against Postgres Testcontainers. I ran the named command myself and it passed (see above). Met.

Status of earlier findings:
- CR-1 (P2, clock precision): **Resolved.** The clock ticks in microseconds (`ClockConfiguration.kt:23`), and `storableInstant()` (`ClockConfiguration.kt:30`) truncates values from substituted clocks. Both have tests.
- CR-2 (P3, proxy initialization in `equals`): **Resolved.** `BaseEntity.kt:73-78` reads the class and id of a proxy argument through `hibernateLazyInitializer`. Tests confirm this does not initialize the proxy, both inside a session and for a detached proxy (`PersistenceConventionsTests.kt:220-250`). `deletedAt` is open again, so lazy proxies work (`SoftDeletable.kt:26-27`).
- CR-3 (P3, duplicate client id test): **Resolved.** `PersistenceConventionsTests.kt:163-174` covers it.
- CR-4 (P3, test migration version collision): **Resolved.** The fixture migration is `V202610200000_1__core_test_persistence_fixture.sql`. `MigrationConventionTests` requires the `_<n>` suffix exactly for `test_` migrations, and ARCHITECTURE.md documents the rule.
- CR-5 (P3, undocumented proxy-receiver limitation): **Resolved.** The `BaseEntity` KDoc (`BaseEntity.kt:29-30`) and ARCHITECTURE.md ("Entities" bullet) now say that `equals`/`hashCode`/`toString` called on an uninitialized proxy load it, fail once it is detached, and that ids should be compared instead. `toString` no longer contains the unreachable proxy branch (`BaseEntity.kt:82`). Called on a proxy inside a session, `toString` still prints the entity class name, because Hibernate delegates the call to the loaded target (asserted at `PersistenceConventionsTests.kt:233`). The new test (`PersistenceConventionsTests.kt:252-262`) fixes the documented detached behavior. The earlier observation about the uncommitted detached-proxy test is settled: the committed test only uses receiver-safe `saved == detached` comparisons, and it passes.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

CR-1 to CR-5 are resolved in the code at HEAD 2c12988, and all four acceptance criteria are implemented with meaningful tests that pass against Postgres Testcontainers. The parent worker still has to record evidence for each criterion with `tasks.py verify`, run `.taskcheck` and write `quality.json` before `done`.
