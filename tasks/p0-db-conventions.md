---
title: Persistence conventions: base entity, UUIDv7, clock, migrations layout
status: in_progress
owner: dell26/agent-4
deps: [p0-server-deps]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/persistence,server/src/test/kotlin/ch/sonensei/cockpit/core/persistence,server/src/main/resources/db/migration/core]
prio: 1
lease_until: 2026-10-20T09:53:56Z
releases: 0
extended: 
hold: 
---
## Goal
Shared persistence building blocks every module uses (SPEC.md §4.2).

## Acceptance criteria
- [x] `BaseEntity` with UUIDv7 `id` (generated server-side, or accepted from the client for idempotent creates), `createdAt`, `updatedAt` (UTC `timestamptz`), `version` (`@Version`); `SoftDeletable` with `deletedAt`
  - verified (2026-10-20 11:34+0200 by dell26/agent-4): Read BaseEntity.kt/SoftDeletable.kt + cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.persistence.*' (Postgres 16 Testcontainers on Docker) -> 37 passed; PersistenceConventionsTests assert UUIDv7 generated/client ids (non-v7 rejected, duplicate client id fails without overwrite), timestamptz columns via information_schema, @Version, deletedAt
- [x] An injectable `Clock` bean is used for all timestamps; tests can substitute a fixed/mutable clock
  - verified (2026-10-20 11:34+0200 by dell26/agent-4): ClockConfigurationTests + PersistenceConventionsTests with @Primary MutableClock (same gradle run, Testcontainers) -> pass; createdAt/updatedAt/deletedAt follow the substituted clock; grep server/src/main for Instant.now( -> none
- [x] Migration convention documented in `docs/ARCHITECTURE.md`: `db/migration/<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql`, never edit applied ones; first migration enables `pg_trgm` and creates nothing else module-specific
  - verified (2026-10-20 11:34+0200 by dell26/agent-4): docs/ARCHITECTURE.md 'Persistence conventions' documents db/migration/<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql and never-edit rule; core/V202610200000__core_enable_pg_trgm.sql only CREATE EXTENSION pg_trgm; MigrationConventionTests + 'first core migration creates no tables' (Flyway target, Testcontainers) -> pass
- [x] Tests: UUIDv7 ids are time-ordered; optimistic locking raises a conflict on a stale version; soft-deleted rows are excluded by the repository helper; `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.persistence.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-20 11:34+0200 by dell26/agent-4): cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.persistence.*' with Docker/Testcontainers Postgres -> BUILD SUCCESSFUL, 37 tests 0 failures (UUIDv7 time-ordered, stale version -> OptimisticLockingFailureException, soft-deleted excluded by findActiveById/findAllActive); sh scripts/check.sh -> all checks passed

## Notes
- ASSUMPTION (2026-10-20 10:50+0200 by R1963MACOS/agent-2): Entity timestamps use Spring Data JPA auditing (@EnableJpaAuditing with a DateTimeProvider backed by the Clock bean) instead of a custom listener; Clock bean (systemUTC) and MutableClock live in core.persistence because the task touches only that package
- ASSUMPTION (2026-10-20 10:50+0200 by R1963MACOS/agent-2): Client-supplied entity ids must be UUIDv7 (BaseEntity rejects other versions with IllegalArgumentException); BaseEntity implements Persistable so save() of an assigned id inserts instead of merging
- ASSUMPTION (2026-10-20 10:50+0200 by R1963MACOS/agent-2): Persistence tests use a test-only migration server/src/test/resources/db/migration/core/V202610200001__core_test_persistence_fixture.sql (table test_persistence_fixture), present in every test DB, so the test entity passes ddl-auto=validate in the shared Spring context
- release by R1963MACOS/agent-2: Implementation complete in commit a00c815 (core/persistence: BaseEntity, SoftDeletableEntity, SoftDeletableRepository, UuidV7, ClockConfiguration+JPA auditing, MutableClock; core/V202610200000__core_enable_pg_trgm.sql; test migration + PersistenceConventionsTests; ARCHITECTURE.md 'Persistence conventions'). ktlintCheck and the non-DB unit tests (UuidV7Tests, MutableClockTests) pass. BLOCKED here: no container runtime on R1963MACOS (Docker Desktop not running, could not be started), so PersistenceConventionsTests (Testcontainers) and .taskcheck could not run. Left: run ./gradlew test --tests 'ch.sonensei.cockpit.core.persistence.*' on a machine with Docker/Podman, fix if needed, then test-writer + code-reviewer (+fixer), verify criteria 1-4, quality.json, done.
- hold by R1963MACOS/agent-2: Needs a container runtime: Docker Desktop on R1963MACOS is not running and agents cannot start it (open -a Docker needs approval); no podman/colima/local Postgres. Implementation is complete on wip/p0-db-conventions (merged, ktlintCheck + UuidV7Tests/MutableClockTests pass). Human: start Docker, then unhold; remaining: run ./gradlew test --tests 'ch.sonensei.cockpit.core.persistence.*', test-writer + code-reviewer, verify, quality.json, done. Released once already for the same reason and it came back to the same machine.
- ASSUMPTION (2026-10-20 11:08+0200 by R1963MACOS/agent-1): The Clock bean ticks in microseconds and audit/soft-delete timestamps are truncated to microseconds (Clock.storableInstant), so in-memory values equal what Postgres timestamptz stores
- ASSUMPTION (2026-10-20 11:08+0200 by R1963MACOS/agent-1): Test-only migrations use a sub-version suffix (V<timestamp>_N__<module>_test_*.sql) so they cannot collide with real timestamped migrations; SoftDeletableEntity.deletedAt getter is open so Hibernate can create lazy proxies
- release by R1963MACOS/agent-1: handed off by R1963MACOS/agent-1: You've hit your session limit · resets 2:50pm (Europe/Zurich)
